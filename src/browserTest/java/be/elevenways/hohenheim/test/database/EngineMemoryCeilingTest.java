package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.live.LiveLane;
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
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every engine's DECLARED memory footprint, end to end on a real daemon: a database
 * provisioned through the product funnel gets its engine's footprint as the cgroup cap,
 * runs its whole init with a fifth of that cap still spare, is never OOM-killed, and
 * then serves a value back.
 *
 * AIDEV-NOTE: the assertion here is HEADROOM ({@code memory.peak} against the cap), and
 * two earlier waves disagreed about what to assert instead. Read this before changing it.
 *
 * The flake it defends against is real and was observed twice: at a cap below its peak an
 * engine spends its whole init pinned against the ceiling, and under the parallel
 * browserTest lane the cgroup OOM killer takes the engine process (a CHILD of the
 * entrypoint script, so the container stays UP and looks healthy) and the next connection
 * gets ECONNREFUSED. The first attempt at pinning that asserted {@code memory.events}
 * "max" was zero. That assertion is WRONG twice over: "max" counts reclaim invocations,
 * and reclaim is not a kill (a busy-but-alive workload churning page cache reports
 * thousands); and empirically it is not even stable for a FIXED adequate cap -- measured
 * 2026-08-07, one and the same mongo at 768 MB reported max=0 on a warm host and max=570
 * in the test lane, because whichever cgroup first faults the image's pages is charged
 * for them. It was an assertion about the host, not about the cap.
 *
 * {@code memory.peak} cannot say that, because it is bounded by the cap BY CONSTRUCTION:
 * a peak that approaches the cap is direct evidence the engine's demand was clipped, which
 * is exactly the pre-kill state. So the cap must be big enough that the peak stays well
 * under it, and {@link #HEADROOM_NUMERATOR}/{@link #HEADROOM_DENOMINATOR} is how much
 * "well under" the product declares.
 *
 * The obvious objection to a percentage-of-cap gate is that a cgroup fills opportunistically
 * with reclaimable page cache, so the peak might just follow whatever cap it is given and
 * the gate would never terminate. Measured, it does not: mongo peaked at the same 835 MiB
 * through this funnel at a 1024 MB cap and at a 1280 MB cap. Re-measure before assuming
 * that of a new engine.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class EngineMemoryCeilingTest {

    /**
     * A declared footprint must leave at least a fifth of itself spare at the measured
     * peak -- a startup measurement never observes query load, so a cap the engine runs
     * flush against at rest has nothing left for the work it exists to do.
     */
    private static final long HEADROOM_NUMERATOR = 4;
    private static final long HEADROOM_DENOMINATOR = 5;

    private static PrivateNetns netns;

    @BeforeAll
    static void enforcePolicy() throws IOException {
        netns = PrivateNetns.installEnforcing();
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: record-backed provisioning refuses without an enforceable policy");
    }

    @AfterAll
    static void restorePolicy() {
        PrivateNetns.uninstall(netns);
        netns = null;
    }

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String USER = "appuser";
    private static final String PASSWORD = "secret123";
    private static final String DATABASE = "appdb";

    /** Runs one round trip inside the started engine and returns the VALUE it printed. */
    private interface ServeCheck {
        String serve(DockerClient docker, String container) throws IOException;
    }

    @Test
    void postgresRunsUnderItsOwnEngineFootprintWithHeadroomToSpare() throws IOException {
        journey(ManagedDatabase.Engine.POSTGRES, "postgres:17-alpine", "42",
            (docker, container) -> value(docker, container,
                List.of("psql", "-U", USER, "-d", DATABASE, "-tAc", "select 42"),
                List.of("PGPASSWORD=" + PASSWORD)));
    }

    @Test
    void redisRunsUnderItsOwnEngineFootprintWithHeadroomToSpare() throws IOException {
        journey(ManagedDatabase.Engine.REDIS, "redis:7-alpine", "42", (docker, container) -> {
            List<String> auth = List.of("REDISCLI_AUTH=" + PASSWORD);
            // The engine's own reply, never the exit code: redis-cli exits 0 while
            // printing "NOAUTH Authentication required." -- the trap this repo has hit
            // twice -- so a discarded set result would swallow exactly that refusal.
            String stored = value(docker, container,
                List.of("redis-cli", "-p", "6379", "set", "ceiling", "42"), auth);
            assertThat(stored)
                .withFailMessage("redis-cli set exited 0 but replied '%s', not OK --"
                    + " redis-cli exits 0 even when printing a NOAUTH-style refusal",
                    stored)
                .isEqualTo("OK");
            return value(docker, container, List.of("redis-cli", "-p", "6379", "get", "ceiling"), auth);
        });
    }

    @Test
    void mysqlRunsUnderItsOwnEngineFootprintWithHeadroomToSpare() throws IOException {
        journey(ManagedDatabase.Engine.MYSQL, "mysql:8.0", "42",
            (docker, container) -> value(docker, container,
                List.of("mysql", "-u", USER, "-D", DATABASE, "-N", "-B", "-e", "select 42"),
                List.of("MYSQL_PWD=" + PASSWORD)));
    }

    @Test
    void mongoRunsUnderItsOwnEngineFootprintWithHeadroomToSpare() throws IOException {
        journey(ManagedDatabase.Engine.MONGO, "mongo:7", "1", (docker, container) -> {
            mongo(docker, container, "db.getSiblingDB('appdb').ceiling.insertOne({ x: 42 })");
            return mongo(docker, container,
                "db.getSiblingDB('appdb').ceiling.countDocuments({ x: 42 })");
        });
    }

    /**
     * The whole journey for one engine: provision through the real funnel with no
     * {@code memory_limit_mb}, then judge the cap the daemon applied, the pressure the
     * engine's own cgroup recorded reaching readiness, and whether it serves.
     */
    private void journey(ManagedDatabase.Engine engine, String image, String expectedValue,
                         ServeCheck serve) throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, image);

        DatabaseService service = new DatabaseService(freshDatasource());
        String name = engine.token() + "mem" + System.nanoTime();
        try {
            // The real funnel, with no memory_limit_mb: the kind's per-engine default is
            // what the daemon must end up applying. Ephemeral, so the data dir is a tmpfs
            // charged to the same cgroup -- the conservative shape.
            service.create(name, engine, image, USER, PASSWORD, DATABASE, true);
            String container = EngineHandles.of(name);

            // 1. The DAEMON's own view of the cgroup cap is this engine's declared
            //    footprint, not a flat number every kind shares.
            long cap = (long) engine.footprintMb(true) * 1024 * 1024;
            Map<?, ?> hostConfig = (Map<?, ?>) docker.inspectContainer(container).get("HostConfig");
            assertThat(((Number) hostConfig.get("Memory")).longValue())
                .withFailMessage("step 1 (%s): HostConfig.Memory is %s, expected the engine's"
                    + " declared footprint of %s MB (%s bytes)", engine, hostConfig.get("Memory"),
                    engine.footprintMb(true), cap)
                .isEqualTo(cap);

            // 2. The product's readiness gate has already returned, so the whole of the
            //    engine's init is behind us: nothing in its cgroup may have been OOM-killed.
            //    Scoped to THIS container's own cgroup -- /sys/fs/cgroup inside a container
            //    is its own cgroup namespace, never a daemon-wide reading.
            Map<String, Long> events = memoryEvents(docker, container);
            assertThat(events.get("oom_kill"))
                .withFailMessage("step 2 (%s): the engine's cgroup OOM-killed %s process(es) at"
                    + " its %s MB cap -- memory.events was %s", engine, events.get("oom_kill"),
                    engine.footprintMb(true), events)
                .isZero();

            // 3. And it got there with headroom. memory.peak cannot exceed the cap, so a
            //    peak near it means demand was CLIPPED, which is the pre-kill state.
            long peak = memoryPeak(docker, container);
            assertThat(peak)
                .withFailMessage("step 3 (%s): the engine peaked at %s MiB of its own %s MB"
                    + " footprint (%s%%) -- a cap it runs flush against at rest has nothing"
                    + " left for query load, and is the ECONNREFUSED flake; declare a bigger"
                    + " footprint or prove the engine needs less", engine, peak / 1024 / 1024,
                    engine.footprintMb(true), peak * 100 / cap)
                .isLessThanOrEqualTo(cap * HEADROOM_NUMERATOR / HEADROOM_DENOMINATOR);

            // 4. The engine SERVES under that cap: assert the returned VALUE, never an exit
            //    code (a client can exit 0 while printing a refusal).
            String served = serve.serve(docker, container);
            assertThat(served)
                .withFailMessage("step 4 (%s): the engine did not serve the expected value back;"
                    + " it printed '%s'", engine, served)
                .isEqualTo(expectedValue);
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
        String text = read(docker, container, "/sys/fs/cgroup/memory.events");
        Map<String, Long> events = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2) {
                events.put(parts[0], Long.parseLong(parts[1]));
            }
        }
        // A cgroup v2 memory controller always publishes both; their absence means we read
        // something else entirely and every assertion on it would be vacuous.
        assertThat(events).containsKeys("max", "oom_kill");
        return events;
    }

    /** The container's OWN high-water mark in bytes, which the kernel bounds by the cap. */
    private static long memoryPeak(DockerClient docker, String container) throws IOException {
        return Long.parseLong(read(docker, container, "/sys/fs/cgroup/memory.peak").trim());
    }

    /** @throws AssertionError when the file cannot be read, so no reading is silently skipped */
    private static String read(DockerClient docker, String container, String path)
            throws IOException {
        DockerClient.ExecResult result = docker.exec(container, List.of("cat", path));
        assertThat(result.exitCode())
            .withFailMessage("could not read %s: %s", path, result.stderr()).isZero();
        return result.stdout();
    }

    /** @return the trimmed VALUE the client printed, so no caller can assert on an exit code alone */
    private static String value(DockerClient docker, String container, List<String> command,
                                List<String> env) throws IOException {
        DockerClient.ExecResult result = docker.exec(container, command, env);
        assertThat(result.exitCode())
            .withFailMessage("client failed: %s", result.stderr()).isZero();
        return result.stdout().trim();
    }

    private static String mongo(DockerClient docker, String container, String eval) throws IOException {
        return value(docker, container, List.of(
            "mongosh", "--username", USER, "--password", PASSWORD,
            "--authenticationDatabase", "admin", "--quiet", "--eval", eval), List.of());
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
}
