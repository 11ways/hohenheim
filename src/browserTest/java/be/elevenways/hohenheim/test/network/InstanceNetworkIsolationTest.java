package be.elevenways.hohenheim.test.network;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerReconciler;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.InstanceNetworks;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.LiveIdOffsets;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Per-instance private networking against a REAL daemon: where the container actually
 * lands, what it can actually reach from inside itself, and what happens on a host that
 * cannot enforce the policy.
 *
 * AIDEV-NOTE: the reachability assertions are {@code nc} runs INSIDE the running
 * container against another instance's real container IP, not a reading of the spec we
 * sent. The refusal assertion checks the DAEMON for the absence of a container, because
 * "the API refused" and "nothing started" are independent facts in this codebase -- that
 * gap is the entire reason this applier exists instead of NftService.
 */
class InstanceNetworkIsolationTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String CLIENT_IMAGE = "alpine:latest";

    /** A neighbour that actually LISTENS, so "reachable" is exit 0 and not an inference. */
    private static final String SERVER_IMAGE = "nginx:alpine";

    /** A database in which every owner-labelled record still exists. */
    private static final DockerReconciler.Records LIVE_RECORDS = new DockerReconciler.Records() {
        @Override
        public boolean liveById(Identifier model, String id) {
            return true;
        }

        @Override
        public boolean liveByName(Identifier model, String name) {
            return false;
        }
    };

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-instance-network-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // Unique per-class instance ids => unique daemon handles (no cross-class 409s).
        LiveIdOffsets.apply(datasource);
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void twoTenantsGetPrivateNetworksAndCannotReachEachOther() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, CLIENT_IMAGE), CLIENT_IMAGE + " not present locally");
        assumeTrue(imagePresent(docker, SERVER_IMAGE), SERVER_IMAGE + " not present locally");
        assumeTrue(PrivateNetns.available(),
            "no private netns available: the deploy path cannot enforce a policy here");

        try (PrivateNetns netns = new PrivateNetns()) {
            WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
            int[] ids = new int[2];
            try {
                Db.run(datasource, () -> {
                    HostFixtures.admitLocal();
                    InstanceService service = new InstanceService();
                    ids[0] = record("net-tenant-a", CLIENT_IMAGE, "sleep 600");
                    ids[1] = record("net-tenant-b", SERVER_IMAGE, null);
                    String handleA = "hohenheim-instance-" + ids[0];
                    String handleB = "hohenheim-instance-" + ids[1];

                    service.deploy(ids[0]);
                    service.deploy(ids[1]);

                    // 1. Each container is attached to its OWN network and to nothing else --
                    //    in particular not to the default bridge every other tier shares.
                    List<String> networksA = networkNamesOf(docker, handleA);
                    assertThat(networksA)
                        .as("step 1: tenant A sits on exactly its own private network")
                        .containsExactly(InstanceNetworks.networkName(handleA));
                    assertThat(networksA)
                        .as("step 1: and never on the shared default bridge")
                        .doesNotContain("bridge");

                    // 2. The network carries owner labels at BIRTH, so the reconciler
                    //    attributes it instead of reporting a permanent orphan.
                    Map<String, Object> network = io(() -> docker.inspectNetwork(
                        InstanceNetworks.networkName(handleA)));
                    OwnerLabels.Owner owner = OwnerLabels.parse(
                        (Map<?, ?>) network.get("Labels"));
                    assertThat(owner).as("step 2: the network is owner-labelled").isNotNull();
                    assertThat(owner.id()).as("step 2: by the instance that created it")
                        .isEqualTo(String.valueOf(ids[0]));
                    DockerReconciler.Finding finding = DockerReconciler.classify(
                        DockerReconciler.KIND_NETWORK,
                        InstanceNetworks.networkName(handleA),
                        (Map<?, ?>) network.get("Labels"),
                        LIVE_RECORDS);
                    assertThat(finding.bucket())
                        .as("step 2: a live instance's network reconciles as OWNED, never ORPHANED")
                        .isEqualTo(DockerReconciler.Bucket.OWNED);

                    // 3. THE COUNTERFACTUAL, first half: tenant A cannot reach tenant B's
                    //    container IP, which on the shared bridge it could.
                    String addressB = addressOf(docker, handleB);
                    assertThat(connects(docker, handleA, addressB, 80))
                        .as("step 3: tenant A cannot open a TCP connection to tenant B ("
                            + addressB + ":80)")
                        .isFalse();

                    // 4. THE COUNTERFACTUAL, second half: the probe is not broken. Put A on
                    //    B's network (the only legitimate post-hoc connect: a SECOND network
                    //    for an already-isolated container) and the very same probe succeeds.
                    io(() -> docker.connectContainerToNetwork(
                        InstanceNetworks.networkName(handleB), handleA, null));
                    try {
                        assertThat(connects(docker, handleA, addressB, 80))
                            .as("step 4: the same probe succeeds once the two share a network,"
                                + " so step 3 measured isolation and not a broken probe")
                            .isTrue();
                    } finally {
                        io(() -> docker.disconnectContainerFromNetwork(
                            InstanceNetworks.networkName(handleB), handleA, true));
                    }

                    // 5. Teardown removes the network with the workload; nothing lingers for
                    //    the reconciler to report.
                    service.destroy(ids[0]);
                    assertThat(io(() -> docker.findNetworkByName(
                        InstanceNetworks.networkName(handleA))))
                        .as("step 5: destroy removes the private network too").isNull();
                    service.destroy(ids[1]);
                });
            } finally {
                WorkloadNetworkPolicy.overrideForTest(null);
                for (int id : ids) {
                    cleanup(docker, "hohenheim-instance-" + id);
                }
            }
        }
    }

    /**
     * The behaviour that separates this applier from NftService: no enforcement means no
     * workload, asserted against the daemon rather than against the return value.
     */
    @Test
    void aTenantWorkloadDoesNotStartOnAHostThatCannotEnforceThePolicy() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, CLIENT_IMAGE), CLIENT_IMAGE + " not present locally");

        // The PRODUCTION applier with enforcement switched off, which is the default posture
        // of every host that has not configured passwordless nft.
        WorkloadNetworkPolicy.overrideForTest(new WorkloadNetworkPolicy(
            (args, stdin) -> {
                throw new AssertionError("nft must never be invoked when enforcement is off");
            }, () -> false));
        int[] ids = new int[1];
        try {
            Db.run(datasource, () -> {
                HostFixtures.admitLocal();
                ids[0] = record("net-refused", CLIENT_IMAGE, "sleep 600");
                String handle = "hohenheim-instance-" + ids[0];

                // 1. The deploy is refused, naming the reason the operator has to act on.
                Throwable thrown = catchThrowable(() -> new InstanceService().deploy(ids[0]));
                assertThat(thrown).as("step 1: the deploy refuses")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all()).anySatisfy(violation -> {
                            assertThat(violation.message().key())
                                .as("step 1: as the named deploy failure")
                                .isEqualTo("instance_deploy_failed");
                            assertThat(String.valueOf(violation.message().args().get("reason")))
                                .as("step 1: naming the missing enforcement, not a bare failure")
                                .contains("security.nftables_enabled");
                        }));

                // 2. NOT MERELY REFUSED: no container exists on the daemon. NftService would
                //    have returned success here and the container would be running.
                Throwable inspect = catchThrowable(() -> docker.inspectContainer(handle));
                assertThat(inspect).as("step 2: no container was created at all")
                    .isInstanceOf(DockerClient.ApiException.class);

                // 3. And no half-built network was left behind either.
                assertThat(io(() -> docker.findNetworkByName(
                    InstanceNetworks.networkName(handle))))
                    .as("step 3: no unenforced network was left behind").isNull();

                // 4. The record is stamped error rather than quietly claiming to run.
                assertThat((String) Models.get(InstanceModel.class).findById(ids[0])
                    .get(InstanceModel.STATUS))
                    .as("step 4: the record says error").isEqualTo(InstanceModel.STATUS_ERROR);
            });
        } finally {
            WorkloadNetworkPolicy.overrideForTest(null);
            cleanup(docker, "hohenheim-instance-" + ids[0]);
        }
    }

    // -- helpers ---------------------------------------------------------------

    private static int record(String name, String image, String command) {
        Map<String, Object> settings = new LinkedHashMap<>();
        int colon = image.indexOf(':');
        settings.put("image", colon > 0 ? image.substring(0, colon) : image);
        settings.put("tag", colon > 0 ? image.substring(colon + 1) : "latest");
        if (command != null) {
            settings.put("command", command);
        }
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name + "-" + System.nanoTime());
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, settings);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static Map<?, ?> networksOf(DockerClient docker, String handle) {
        Object settings = io(() -> docker.inspectContainer(handle)).get("NetworkSettings");
        Object networks = settings instanceof Map<?, ?> map ? map.get("Networks") : null;
        return networks instanceof Map<?, ?> map ? map : Map.of();
    }

    private static List<String> networkNamesOf(DockerClient docker, String handle) {
        List<String> names = new ArrayList<>();
        for (Object key : networksOf(docker, handle).keySet()) {
            names.add(String.valueOf(key));
        }
        return names;
    }

    private static String addressOf(DockerClient docker, String handle) {
        for (Object endpoint : networksOf(docker, handle).values()) {
            if (endpoint instanceof Map<?, ?> map && map.get("IPAddress") instanceof String ip
                    && !ip.isBlank()) {
                return ip;
            }
        }
        throw new IllegalStateException("container " + handle + " has no address");
    }

    /** @return whether a TCP connection opens from inside one container to an address */
    private static boolean connects(DockerClient docker, String handle, String address, int port) {
        return io(() -> docker.exec(handle,
            List.of("sh", "-c", "nc -w 2 " + address + " " + port + " </dev/null")))
            .exitCode() == 0;
    }

    /** Java-checked-exception plumbing: Db.run takes a Runnable, the daemon throws IOException. */
    private interface IoCall<T> {
        T get() throws IOException;
    }

    private static <T> T io(IoCall<T> call) {
        try {
            return call.get();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void io(IoVoid call) {
        try {
            call.run();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private interface IoVoid {
        void run() throws IOException;
    }

    private static boolean imagePresent(DockerClient docker, String image) throws IOException {
        for (Object entry : docker.listImages()) {
            if (entry instanceof Map<?, ?> map && map.get("RepoTags") instanceof List<?> tags
                    && tags.contains(image)) {
                return true;
            }
        }
        return false;
    }

    private static void cleanup(DockerClient docker, String handle) {
        try {
            docker.removeContainer(handle, true);
        } catch (IOException ignored) {
            // a passing run has nothing to remove
        }
        try {
            docker.removeNetwork(InstanceNetworks.networkName(handle));
        } catch (IOException ignored) {
            // likewise
        }
    }
}
