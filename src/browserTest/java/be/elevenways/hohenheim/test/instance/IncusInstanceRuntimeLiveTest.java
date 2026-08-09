package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.instance.InstancePlacement;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.ConsoleStream;
import be.elevenways.hohenheim.server.runtime.ConsoleStreamSupport;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Driver #2 against the REAL remote Incus daemon: the full product funnel (enrollment,
 * preflight, admission, placement, deploy through {@link InstanceService}) ends in a
 * genuine system container, asserted at the DAEMON (over the host's own CLI and the
 * REST API) beside every service answer. Uses a small Alpine image because the host's
 * 3.9 GiB of RAM is the binding limit, and removes everything it creates.
 */
class IncusInstanceRuntimeLiveTest {

    private static final String HOST = "live-incus";

    /** Small system-container image; the RAM budget rules out anything fatter. */
    private static final String IMAGE = "alpine/3.22";

    private static SqliteDatasource datasource;
    private static LiveIncusHost remote;
    private static String enrolledFingerprint;

    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveIncusHost.configured();
        LiveLane.require(LiveLane.Need.INCUS_HOST, remote != null,
            "no live incus host enrolled at " + LiveIncusHost.CONFIG);

        File db = File.createTempFile("hohenheim-incus-instance-live", ".db");
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

        // The REAL enrollment path, product code end to end: identity, pin, confirm,
        // token enrollment, preflight, admit -- no fixture shortcut writes the verdict.
        Db.run(datasource, () -> enrolledFingerprint =
            remote.enrollThroughProduct(HOST, "hohenheim-live-instance"));
    }

    @AfterAll
    static void tearDown() {
        if (remote != null) {
            System.out.println("=== cleanup: authorized_keys -> "
                + remote.releaseAuthorizedKeys());
        }
        if (remote != null && enrolledFingerprint != null) {
            try {
                remote.removeTrustEntry(enrolledFingerprint);
            } catch (IOException ignored) {
                // nothing enrolled, nothing to remove
            }
        }
    }

    private static int instanceRecord(String name, Map<String, Object> settings) {
        Row host = Models.get(ServerModel.class).findByName(HOST);
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:incus_container");
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.SERVER_ID, host.get(ServerModel.ID));
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    @Test
    void deployConsoleStopRedeployDestroyJourneyAgainstTheRealIncusDaemon() {
        Db.run(datasource, () -> {
            // 0. Placement itself picks this host for the incus runtime -- the only
            //    admitted incus host there is -- and refuses the docker runtime, for
            //    which no admitted host exists in this database.
            Row host = Models.get(ServerModel.class).findByName(HOST);
            int hostId = host.get(ServerModel.ID);
            assertThat(InstancePlacement.forActor(null, null,
                    InstancePlacement.Workload.forRuntime(ServerModel.RUNTIME_INCUS)))
                .as("step 0: the incus runtime places on the admitted incus host")
                .isEqualTo(hostId);
            assertThat(catchThrowable(() ->
                    InstancePlacement.chooseForOwner("",
                        InstancePlacement.Workload.forRuntime(ServerModel.RUNTIME_DOCKER))))
                .as("step 0: the docker runtime cannot land on it")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key())
                            .isEqualTo("no_placement_available")));

            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("image", IMAGE);
            settings.put("memory_limit_mb", 128);
            settings.put("environment_variables", Map.of("HOHENHEIM_MARK", "live-proof"));
            int id = instanceRecord("incus-journey", settings);
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
            InstanceService service = new InstanceService();
            IncusClient incus = new ServerService().incusClientFor(HOST);
            Map<String, String> ownerLabels = OwnerLabels.of(InstanceModel.MODEL_ID, id);

            try {
                // 1. Deploy through the product funnel: running, record stamped.
                InstanceStatus deployed = service.deploy(id);
                assertThat(deployed.state())
                    .as("step 1: deploy reports RUNNING").isEqualTo(ContainerState.RUNNING);
                assertThat((String) Models.get(InstanceModel.class).findById(id)
                        .get(InstanceModel.STATUS))
                    .as("step 1: the record was stamped running")
                    .isEqualTo(InstanceModel.STATUS_RUNNING);

                // 2. DAEMON truth, twice over: the host's own CLI reports it RUNNING,
                //    and the REST object carries the owner labels (user.*) plus the
                //    limits and env, all stamped at create.
                assertThat(infoOf(handle))
                    .as("step 2: the host's own CLI sees the container running")
                    .contains("RUNNING");
                Map<String, Object> definition = instanceOf(incus, handle);
                Map<?, ?> config = (Map<?, ?>) definition.get("config");
                ownerLabels.forEach((key, value) ->
                    assertThat(config.get("user." + key))
                        .as("step 2: owner label %s stamped at create", key)
                        .isEqualTo(value));
                assertThat(config.get("limits.memory"))
                    .as("step 2: the memory cap landed").isEqualTo("128MiB");
                assertThat(config.get("environment.HOHENHEIM_MARK"))
                    .as("step 2: the env variable landed").isEqualTo("live-proof");
                assertThat(config.get("security.privileged"))
                    .as("step 2: UNPRIVILEGED by default -- no privileged flag was set")
                    .isNull();

                // 3. The console websocket is live: what we type reaches the workload's
                //    console and its output comes back over the SAME stream.
                ConsoleStreamSupport consoles =
                    (ConsoleStreamSupport) service.resolve(id).runtime();
                try {
                    ConsoleStreamSupport.Console console = consoles.openConsole(handle);
                    assertThat(console.stdinDelivered())
                        .as("step 3: an incus console delivers stdin by construction").isTrue();
                    try (ConsoleStream stream = console.stream()) {
                        stream.writeStdin("\n".getBytes(StandardCharsets.UTF_8));
                        String echoed = pullText(stream, 15_000);
                        assertThat(echoed)
                            .as("step 3: the console answered our newline with output")
                            .isNotEmpty();
                    }
                    assertThat(consoles.consoleTail(handle, 50))
                        .as("step 3: the console ring buffer holds the boot/getty output")
                        .isNotBlank();

                    // 4. exitCode honesty: null while running; once stopped it is a
                    //    NAMED refusal (Incus reports no init exit status), never an
                    //    invented 0.
                    assertThat(consoles.exitCode(handle))
                        .as("step 4: no exit code while running").isNull();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                service.stop(id);
                assertThat(service.liveStatus(id).state())
                    .as("step 4: the daemon reports STOPPED, not absent")
                    .isEqualTo(ContainerState.STOPPED);
                assertThat(infoOf(handle))
                    .as("step 4: the host's own CLI agrees").contains("STOPPED");
                assertThat(catchThrowable(() -> consoles.exitCode(handle)))
                    .as("step 4: a stopped incus workload's exit code is a named refusal")
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("no init exit status");

                // 5. Redeploy CONVERGES onto the OWN stopped instance (attribution
                //    verified; the rootfs is stateful, so it is never recreated from
                //    the image -- the snapshot/backup live test proves data survives).
                InstanceStatus redeployed = service.deploy(id);
                assertThat(redeployed.state())
                    .as("step 5: redeploy runs again").isEqualTo(ContainerState.RUNNING);

                // 6. Destroy: verified teardown at the daemon, record soft-deleted.
                service.destroy(id);
                assertThat(catchThrowable(() -> incus.instance(handle)))
                    .as("step 6: the instance is ABSENT at the daemon")
                    .isInstanceOfSatisfying(IncusClient.ApiException.class,
                        e -> assertThat(e.isNotFound()).isTrue());
                assertThat(infoOf(handle))
                    .as("step 6: the host's own CLI agrees it is gone")
                    .contains("ERROR");
                assertThat((Object) Models.get(InstanceModel.class).findById(id)
                        .get(InstanceModel.DELETED_AT))
                    .as("step 6: the record is soft-deleted, not erased").isNotNull();
            } finally {
                cleanup(incus, handle);
            }
        });
    }

    /**
     * The name-collision refusal on driver #2: a same-named instance WITHOUT our owner
     * labels is never replaced -- deploy refuses by name and the stranger survives.
     */
    @Test
    void deployRefusesToReplaceAForeignIncusInstance() {
        Db.run(datasource, () -> {
            int id = instanceRecord("incus-collision", new LinkedHashMap<>(
                Map.of("image", IMAGE, "memory_limit_mb", 128)));
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
            IncusClient incus = new ServerService().incusClientFor(HOST);
            try {
                // 1. A stranger already holds the name, with NO owner labels.
                try {
                    incus.createInstance(Map.of(
                        "name", handle,
                        "type", "container",
                        "source", Map.of("type", "image", "protocol", "simplestreams",
                            "server", "https://images.linuxcontainers.org", "alias", IMAGE),
                        "profiles", List.of("default")));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                // 2. Deploy refuses loudly with the named violation...
                Throwable refusal = catchThrowable(() -> new InstanceService().deploy(id));
                assertThat(refusal)
                    .as("step 2: deploying onto a foreign name is a refusal, not a replace")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all()).anySatisfy(violation -> {
                            assertThat(violation.message().key())
                                .isEqualTo("instance_deploy_failed");
                            assertThat(String.valueOf(
                                    violation.message().args().get("reason")))
                                .as("step 2: and the reason names the attribution failure")
                                .contains("does not attribute");
                        }));

                // 3. ...and the DAEMON still has the stranger, untouched and label-less.
                Map<String, Object> stranger = instanceOf(incus, handle);
                Map<?, ?> config = (Map<?, ?>) stranger.get("config");
                assertThat(config.get("user." + OwnerLabels.MODEL))
                    .as("step 3: the foreign instance survives, still unattributed")
                    .isNull();
            } finally {
                cleanup(incus, handle);
            }
        });
    }

    // -- plumbing -------------------------------------------------------------

    /** Daemon truth over the host's OWN CLI (unchecked for lambda bodies). */
    private static String infoOf(String handle) {
        try {
            return remote.instanceInfoOrError(handle);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Object> instanceOf(IncusClient incus, String handle) {
        try {
            return incus.instance(handle);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Pull console output with a deadline; a silent console fails the assertion. */
    private static String pullText(ConsoleStream stream, long timeoutMs) {
        ExecutorService pump = Executors.newSingleThreadExecutor();
        try {
            Future<ConsoleStream.Chunk> next = pump.submit(stream::next);
            ConsoleStream.Chunk chunk = next.get(timeoutMs, TimeUnit.MILLISECONDS);
            return chunk == null ? "" : new String(chunk.data(), StandardCharsets.UTF_8);
        } catch (TimeoutException silent) {
            return "";
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pump.shutdownNow();
        }
    }

    private static void cleanup(IncusClient incus, String handle) {
        try {
            incus.changeState(handle, "stop", 0, true);
        } catch (IOException ignored) {
            // already stopped or absent
        }
        try {
            incus.deleteInstance(handle);
        } catch (IOException ignored) {
            // already gone
        }
    }
}
