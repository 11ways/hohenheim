package be.elevenways.hohenheim.test.network;

import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The managed-database tier's isolation, against a REAL daemon and REAL packets: two
 * databases owned as separate records land on separate policied networks, and one
 * cannot open a TCP connection to the other -- which on the shared bridge they always
 * could. The positive anchor (put A on B's network and the same probe succeeds) proves
 * the negative measured isolation and not a broken probe.
 *
 * AIDEV-NOTE: this is the DB counterpart of {@link WorkloadNetworkPolicyTest} (rules in
 * a netns) and {@code InstanceNetworkIsolationTest} (Docker path). It provisions through
 * the PRODUCTION {@link DatabaseService}, which declares PRIVATE + closed egress, so the
 * property proven is the one a real tenant gets. Redis is the fixture because it boots
 * in a second where Postgres initds for tens.
 */
class DatabaseNetworkIsolationTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String REDIS_IMAGE = "redis:7-alpine";

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-db-network-test", ".db");
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
        netns = PrivateNetns.installEnforcing();
    }

    @Test
    void twoDatabasesGetPrivateNetworksAndCannotReachEachOther() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, REDIS_IMAGE), REDIS_IMAGE + " not present locally");
        assumeTrue(netns != null,
            "no private netns: a record-backed database refuses to provision unprotected");

        String a = "dbnet-a-" + System.nanoTime();
        String b = "dbnet-b-" + System.nanoTime();
        String handleA = ControllerScope.handle(ControllerScope.KIND_DB, a);
        String handleB = ControllerScope.handle(ControllerScope.KIND_DB, b);
        try {
            Db.run(datasource, () -> {
                DatabaseService service = new DatabaseService(docker, datasource);
                io(() -> service.create(a, ManagedDatabase.Engine.REDIS, REDIS_IMAGE,
                    "unused", "unused", "unused", true, ServerModel.MODE_LOCAL));
                io(() -> service.create(b, ManagedDatabase.Engine.REDIS, REDIS_IMAGE,
                    "unused", "unused", "unused", true, ServerModel.MODE_LOCAL));

                // 1. Each database sits on exactly its OWN network, never the shared bridge.
                assertThat(networkNamesOf(docker, handleA))
                    .as("step 1: database A is on its own private network only")
                    .containsExactly(WorkloadNetworks.networkName(handleA));
                assertThat(networkNamesOf(docker, handleA))
                    .as("step 1: and never on the shared default bridge")
                    .doesNotContain("bridge");

                // 2. THE COUNTERFACTUAL, first half: A cannot reach B's redis port, which
                //    on the shared bridge it could.
                String addressB = addressOf(docker, handleB);
                assertThat(connects(docker, handleA, addressB, 6379))
                    .as("step 2: database A cannot open a TCP connection to database B ("
                        + addressB + ":6379)")
                    .isFalse();

                // 3. THE COUNTERFACTUAL, second half: the probe is sound. Put A on B's
                //    network and the very same probe succeeds.
                io(() -> docker.connectContainerToNetwork(
                    WorkloadNetworks.networkName(handleB), handleA, null));
                try {
                    assertThat(connects(docker, handleA, addressB, 6379))
                        .as("step 3: the same probe succeeds once the two share a network,"
                            + " so step 2 measured isolation and not a broken probe")
                        .isTrue();
                } finally {
                    io(() -> docker.disconnectContainerFromNetwork(
                        WorkloadNetworks.networkName(handleB), handleA, true));
                }

                // 4. Destroy removes the private network with the workload.
                io(() -> service.destroy(a, true));
                assertThat(io(() -> docker.findNetworkByName(
                    WorkloadNetworks.networkName(handleA))))
                    .as("step 4: destroy removes the private network too").isNull();
                io(() -> service.destroy(b, true));
            });
        } finally {
            cleanup(docker, handleA);
            cleanup(docker, handleB);
        }
    }

    // -- helpers ---------------------------------------------------------------

    private static Map<?, ?> networksOf(DockerClient docker, String handle) {
        Object settings = io(() -> docker.inspectContainer(handle)).get("NetworkSettings");
        Object networks = settings instanceof Map<?, ?> map ? map.get("Networks") : null;
        return networks instanceof Map<?, ?> map ? map : Map.of();
    }

    private static List<String> networkNamesOf(DockerClient docker, String handle) {
        return networksOf(docker, handle).keySet().stream().map(String::valueOf).toList();
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

    private static boolean connects(DockerClient docker, String handle, String address, int port) {
        return io(() -> docker.exec(handle,
            List.of("sh", "-c", "nc -w 2 " + address + " " + port + " </dev/null")))
            .exitCode() == 0;
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
            docker.removeNetwork(WorkloadNetworks.networkName(handle));
        } catch (IOException ignored) {
            // likewise
        }
    }

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
}
