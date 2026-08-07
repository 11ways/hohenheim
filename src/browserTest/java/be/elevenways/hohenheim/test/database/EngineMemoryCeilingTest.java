package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Datasources;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The engine memory ceiling, end to end on a real daemon: a managed MONGO provisioned
 * through the product funnel gets its ENGINE's declared footprint as a cgroup cap, never
 * once runs against that cap while starting, and then serves.
 *
 * AIDEV-NOTE: this test exists because the previous flat 512 MB footprint was BELOW
 * mongo's and mysql's measured startup peak. Because charge == cap, that number is also
 * the cgroup ceiling, so those engines spent their whole init pinned against it,
 * reclaiming hundreds of times, and survived only while host reclaim kept up. Under the
 * parallel browserTest lane it did not: the cgroup OOM killer took mongod (a CHILD of the
 * entrypoint script during init, so the container stayed UP and looked healthy) and the
 * next client connection got "MongoNetworkError: connect ECONNREFUSED". Step 2 is the
 * assertion that encodes that defect -- a cap merely being "big enough not to crash
 * today" is exactly the state that flaked, so the test demands the engine never touched
 * its ceiling at all.
 */
class EngineMemoryCeilingTest {

    private static PrivateNetns netns;

    @BeforeAll
    static void enforcePolicy() throws IOException {
        netns = PrivateNetns.installEnforcing();
        assumeTrue(netns != null,
            "no private netns: record-backed provisioning refuses without an enforceable policy");
    }

    @AfterAll
    static void restorePolicy() {
        PrivateNetns.uninstall(netns);
        netns = null;
    }

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String MONGO_IMAGE = "mongo:7";

    @Test
    void mongoRunsUnderItsOwnEngineFootprintAndNeverHitsTheCeiling() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, MONGO_IMAGE), MONGO_IMAGE + " not present locally");

        DatabaseService service = new DatabaseService(freshDatasource());
        String name = "mongomem" + System.nanoTime();
        try {
            // The real funnel, with no memory_limit_mb: the kind's per-engine default is
            // what the daemon must end up applying.
            service.create(name, ManagedDatabase.Engine.MONGO, MONGO_IMAGE,
                "appuser", "secret123", "appdb", true);   // ephemeral: tmpfs
            String container = EngineHandles.of(name);

            // 1. The DAEMON's own view of the cgroup cap is the engine's declared
            //    footprint, not the flat 512 MB every kind used to book.
            long expected = (long) ManagedDatabase.Engine.MONGO.footprintMb() * 1024 * 1024;
            Map<?, ?> hostConfig = (Map<?, ?>) docker.inspectContainer(container).get("HostConfig");
            assertThat(((Number) hostConfig.get("Memory")).longValue())
                .withFailMessage("step 1: HostConfig.Memory is %s, expected mongo's declared"
                    + " footprint of %s MB (%s bytes)", hostConfig.get("Memory"),
                    ManagedDatabase.Engine.MONGO.footprintMb(), expected)
                .isEqualTo(expected);

            // 2. The product's readiness gate has already returned by now, so the whole of
            //    the engine's init is behind us: its cgroup must never have reached the
            //    ceiling (max) and nothing in it may have been OOM-killed (oom_kill).
            //    Scoped to THIS container's own cgroup -- /sys/fs/cgroup inside a
            //    container is its own cgroup namespace, never a daemon-wide reading.
            Map<String, Long> events = memoryEvents(docker, container);
            assertThat(events.get("oom_kill"))
                .withFailMessage("step 2: mongo's cgroup OOM-killed %s process(es) at a %s MB"
                    + " cap -- memory.events was %s", events.get("oom_kill"),
                    ManagedDatabase.Engine.MONGO.footprintMb(), events)
                .isZero();
            assertThat(events.get("max"))
                .withFailMessage("step 2: mongo ran against its %s MB ceiling %s time(s) while"
                    + " starting -- it only survives while host reclaim keeps up, which is the"
                    + " ECONNREFUSED flake; memory.events was %s",
                    ManagedDatabase.Engine.MONGO.footprintMb(), events.get("max"), events)
                .isZero();

            // 3. The engine SERVES under that cap: assert the returned VALUE, never an exit
            //    code (a client can exit 0 while printing a refusal).
            mongo(docker, container, "db.getSiblingDB('appdb').ceiling.insertOne({ x: 42 })");
            String count = mongo(docker, container,
                "db.getSiblingDB('appdb').ceiling.countDocuments({ x: 42 })");
            assertThat(count)
                .withFailMessage("step 3: mongo did not serve the inserted document back;"
                    + " countDocuments printed '%s'", count)
                .isEqualTo("1");
        } finally {
            try {
                service.destroy(name, true);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /** The container's OWN {@code memory.events} counters, read from inside its cgroup namespace. */
    private static Map<String, Long> memoryEvents(DockerClient docker, String container)
            throws IOException {
        DockerClient.ExecResult result =
            docker.exec(container, List.of("cat", "/sys/fs/cgroup/memory.events"));
        assertThat(result.exitCode())
            .withFailMessage("could not read memory.events: %s", result.stderr()).isZero();
        Map<String, Long> events = new LinkedHashMap<>();
        for (String line : result.stdout().split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2) {
                events.put(parts[0], Long.parseLong(parts[1]));
            }
        }
        // A cgroup v2 memory controller always publishes both; their absence means we read
        // something else entirely and every assertion below would be vacuous.
        assertThat(events).containsKeys("max", "oom_kill");
        return events;
    }

    /** @return the trimmed VALUE mongosh printed, so no caller can assert on an exit code alone */
    private static String mongo(DockerClient docker, String container, String eval) throws IOException {
        DockerClient.ExecResult result = docker.exec(container, List.of(
            "mongosh", "--username", "appuser", "--password", "secret123",
            "--authenticationDatabase", "admin", "--quiet", "--eval", eval));
        assertThat(result.exitCode())
            .withFailMessage("mongo eval failed: %s", result.stderr()).isZero();
        return result.stdout().trim();
    }

    private static SqliteDatasource freshDatasource() throws IOException {
        File db = File.createTempFile("hohenheim-enginemem-test", ".db");
        db.delete();
        db.deleteOnExit();
        SqliteDatasource ds = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(ds).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, ds);
        HohenheimTestRuntime.ensureBooted();
        return ds;
    }

    private static boolean imagePresent(DockerClient docker, String tag) throws IOException {
        for (Object image : docker.listImages()) {
            Object repoTags = ((Map<?, ?>) image).get("RepoTags");
            if (repoTags instanceof List<?> tags && tags.contains(tag)) {
                return true;
            }
        }
        return false;
    }
}
