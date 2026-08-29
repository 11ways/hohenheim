package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The instance tier against a REAL local daemon: deploy/stop/redeploy/destroy as one
 * journey, asserting HOST state (container present/absent, labels at birth, volume
 * labels, port bound) beside every API answer -- in this codebase "the API refused"
 * and "nothing happened" are independent facts. Skips (loudly, via assumptions) only
 * where no daemon or no local alpine image exists.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class InstanceRuntimeLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    private static SqliteDatasource datasource;

    /**
     * The instance tier REFUSES to deploy where its network policy cannot be enforced, so
     * every test here needs a kernel that will take one. This is a private namespace, not
     * the host's -- see {@link PrivateNetns}.
     */
    private static PrivateNetns netns;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-instance-live-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
        if (PrivateNetns.available()) {
            netns = new PrivateNetns();
            WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
        }
    }

    @AfterAll
    static void tearDown() {
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
            netns = null;
        }
    }

    private static int instanceRecord(String name, Map<String, Object> settings) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, settings);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    @Test
    void deployStopRedeployDestroyJourneyAgainstTheRealDaemon() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, "alpine:latest");
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            int localId = ServerModel.localServerId();
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("image", "alpine");
            settings.put("tag", "latest");
            settings.put("command", "sleep 300");
            settings.put("container_port", 8080);
            settings.put("volumes", Map.of("data", "/data"));
            int id = instanceRecord("live-journey", settings);
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
            String volumeName = handle + "-vol-data";
            InstanceService service = new InstanceService();
            Map<String, String> ownerLabels = OwnerLabels.of(InstanceModel.MODEL_ID, id);

            try {
                // 1. Deploy: running, port published, record stamped.
                InstanceStatus deployed = service.deploy(id);
                assertThat(deployed.state())
                    .as("step 1: deploy reports RUNNING").isEqualTo(ContainerState.RUNNING);
                assertThat(deployed.publishedPort())
                    .as("step 1: the declared container port was published").isNotNull();
                assertThat((String) Models.get(InstanceModel.class).findById(id)
                        .get(InstanceModel.STATUS))
                    .as("step 1: the record was stamped running")
                    .isEqualTo(InstanceModel.STATUS_RUNNING);

                // 2. HOST state: the container exists, born with BOTH owner labels; the
                //    named volume exists and was born with them too (Docker never
                //    relabels a volume -- this is the one chance, and it must be taken).
                Map<String, Object> inspect = inspect(docker, handle);
                assertThat(labelsOf(inspect.get("Config")))
                    .as("step 2: container labels stamped at create")
                    .containsAllEntriesOf(ownerLabels);
                Map<String, Object> volume = inspectVolume(docker, volumeName);
                assertThat(volume.get("Labels"))
                    .as("step 2: volume labels stamped at BIRTH")
                    .isInstanceOfSatisfying(Map.class, labels ->
                        assertThat(labels).containsAllEntriesOf(ownerLabels));

                // 3. Ledger state: the observed port is claimed by THIS instance (record-
                //    after), spelled through the canonical loopback claim key.
                Row claim = PortLedger.holderOf(PortLedger.claimKeyOf(
                    localId, "127.0.0.1", deployed.publishedPort(), "tcp"));
                assertThat(claim).as("step 3: the published port has a ledger row").isNotNull();
                assertThat(PortLedger.isOwnedBy(claim, InstanceModel.MODEL_ID, id))
                    .as("step 3: and the instance owns it").isTrue();

                // 4. Stop: daemon-confirmed, so the claims are released as OBSERVED; the
                //    container survives (stopped), its data volume untouched.
                service.stop(id);
                assertThat(service.liveStatus(id).state())
                    .as("step 4: the daemon reports the container STOPPED, not absent")
                    .isEqualTo(ContainerState.STOPPED);
                assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, id))
                    .as("step 4: a confirmed stop releases the port claims (observed free)")
                    .isEmpty();
                assertThat((String) Models.get(InstanceModel.class).findById(id)
                        .get(InstanceModel.STATUS))
                    .as("step 4: the record was stamped stopped")
                    .isEqualTo(InstanceModel.STATUS_STOPPED);

                // 5. Redeploy: replaces the OWN stopped container (verified by label,
                //    never a blind force-remove) and records a fresh ephemeral port.
                InstanceStatus redeployed = service.deploy(id);
                assertThat(redeployed.state())
                    .as("step 5: redeploy runs again").isEqualTo(ContainerState.RUNNING);
                assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, id))
                    .as("step 5: exactly one fresh claim").hasSize(1);

                // 6. Destroy: verified teardown -- container observed gone AT THE DAEMON,
                //    claims released, record soft-deleted, volume KEPT (an operator
                //    decision, surfaced by the reconciler as an orphan).
                service.destroy(id);
                assertThat(catchThrowable(() -> docker.inspectContainer(handle)))
                    .as("step 6: the container is ABSENT at the daemon, not merely claimed gone")
                    .isInstanceOfSatisfying(DockerClient.ApiException.class,
                        e -> assertThat(e.isNotFound()).isTrue());
                assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, id))
                    .as("step 6: no port claim survives a verified destroy").isEmpty();
                Row record = Models.get(InstanceModel.class).findById(id);
                assertThat((Object) record.get(InstanceModel.DELETED_AT))
                    .as("step 6: the record is soft-deleted, not erased").isNotNull();
                assertThat(inspectVolume(docker, volumeName))
                    .as("step 6: the data volume SURVIVES destroy (removal is a human act)")
                    .isNotNull();

                // 7. DELETE WITH DATA on the already-destroyed record: the human act.
                //    The named daemon volume goes WITH it, verified absent at the
                //    daemon and named in the removal report -- the wording of the
                //    dialog ("every volume it owns") used to promise exactly this while
                //    only host-directory rows were ever removed.
                List<String> removed = service.destroyWithData(id);
                assertThat(removed)
                    .as("step 7: the removal report names the named volume")
                    .contains(volumeName);
                assertThat(catchThrowable(() -> docker.inspectVolume(volumeName)))
                    .as("step 7: the named volume is ABSENT at the daemon after delete"
                        + " with data")
                    .isInstanceOfSatisfying(DockerClient.ApiException.class,
                        e -> assertThat(e.isNotFound()).isTrue());
            } finally {
                cleanup(docker, handle, volumeName);
            }
        });
    }

    /**
     * The name-collision refusal: a same-named FOREIGN container is never replaced.
     * Deploy must refuse with a named violation AND leave the stranger untouched.
     */
    @Test
    void deployRefusesToReplaceAForeignContainer() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, "alpine:latest");
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            int id = instanceRecord("collision-victim", new LinkedHashMap<>(
                Map.of("image", "alpine", "command", "sleep 300")));
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
            try {
                // 1. A stranger already holds the name, with NO owner labels.
                Map<String, Object> spec = new LinkedHashMap<>();
                spec.put("Image", "alpine:latest");
                spec.put("Cmd", List.of("sleep", "60"));
                try {
                    docker.createContainer(handle, spec, ContainerHardening.STRICT);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                // 2. Deploy refuses loudly with the named violation...
                Throwable refusal = catchThrowable(() -> new InstanceService().deploy(id));
                assertThat(refusal)
                    .as("step 2: deploying onto a foreign name is a refusal, not a replace")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all())
                            .as("step 2: and the refusal carries the named deploy failure")
                            .anySatisfy(violation -> assertThat(violation.message().key())
                                .isEqualTo("instance_deploy_failed")));
                assertThat((String) Models.get(InstanceModel.class).findById(id)
                        .get(InstanceModel.STATUS))
                    .as("step 2: the record was stamped error, not left claiming success")
                    .isEqualTo(InstanceModel.STATUS_ERROR);

                // 3. ...and the HOST still has the stranger (nothing was force-removed).
                assertThat(labelsOf(inspect(docker, handle).get("Config")))
                    .as("step 3: the foreign container survives, still label-less")
                    .doesNotContainKey(OwnerLabels.MODEL);
            } finally {
                cleanup(docker, handle, null);
            }
        });
    }

    // -- plumbing -------------------------------------------------------------

    private static Map<String, Object> inspect(DockerClient docker, String name) {
        try {
            return docker.inspectContainer(name);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Object> inspectVolume(DockerClient docker, String name) {
        try {
            return docker.inspectVolume(name);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> labelsOf(Object config) {
        Object labels = config instanceof Map<?, ?> c ? c.get("Labels") : null;
        return labels instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static void cleanup(DockerClient docker, String container, String volume) {
        try {
            docker.removeContainer(container, true);
        } catch (IOException ignored) {
            // already gone
        }
        try {
            docker.removeNetwork(WorkloadNetworks.networkName(container));
        } catch (IOException ignored) {
            // a deploy that never reached the network has none to remove
        }
        if (volume != null) {
            try {
                docker.removeVolume(volume, true);
            } catch (IOException ignored) {
                // already gone
            }
        }
    }
}
