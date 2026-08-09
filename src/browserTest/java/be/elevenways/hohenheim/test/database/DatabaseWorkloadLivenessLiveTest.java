package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.WorkloadLiveness;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The managed-database tier's answer when the ENGINE is OOM-killed inside a container
 * that keeps running -- the exact shape of the flake {@code d6a9bf6} right-sized around,
 * reported rather than merely made rarer.
 *
 * AIDEV-NOTE: the assertion that matters is that {@code running()} is STILL true here.
 * That is not a bug to fix by flipping it: the container genuinely runs, and every
 * daemon agrees. The tier has to carry a second, named answer for the engine inside it,
 * which is what {@code workloadDead()} reads.
 */
class DatabaseWorkloadLivenessLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String REDIS_IMAGE = "redis:7-alpine";

    /** Redis's measured startup peak is ~9 MiB, so this caps it without starving it. */
    private static final int MEMORY_LIMIT_MB = 64;

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

    @Test
    void anOomKilledEngineStopsBeingReportedHealthy() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, REDIS_IMAGE);

        DatabaseService service = new DatabaseService(freshDatasource());
        String name = "livenessdb" + System.nanoTime();
        try {
            service.create(name, ManagedDatabase.Engine.REDIS, REDIS_IMAGE,
                "appuser", "secret123", "appdb", true, ServerService.LOCAL,
                ResourceLimits.of(MEMORY_LIMIT_MB, null));
            String container = EngineHandles.of(name);

            // 1. A provisioned engine is SERVING, not merely "the container is up".
            DatabaseService.Detail healthy = service.detail(name);
            assertThat(healthy.containerState())
                .as("step 1: the engine container runs").isEqualTo(ContainerState.RUNNING);
            assertThat(healthy.liveness())
                .withFailMessage("step 1: a freshly provisioned engine reported %s",
                    healthy.liveness())
                .isEqualTo(WorkloadLiveness.SERVING);
            assertThat(healthy.workloadDead())
                .as("step 1: nothing has been killed yet").isFalse();

            // 2. Kill a process inside the engine's own cgroup. Asserted on the kernel's
            //    counter for THIS container, never on the exec's exit code and never on a
            //    daemon-wide reading (four forks share this daemon).
            DockerClient.ExecResult killed =
                docker.exec(container, List.of("tail", "/dev/zero"));
            Map<String, Long> events = memoryEvents(docker, container);
            assertThat(events.get("oom_kill"))
                .withFailMessage("step 2: nothing was OOM-killed (exec exit %s), so the"
                    + " defect was never reproduced; memory.events was %s",
                    killed.exitCode(), events)
                .isPositive();

            // 3. THE DEFECT. The container is still up and every daemon says "running" --
            //    which is precisely why the tier used to call this healthy.
            DatabaseService.Detail dead = service.detail(name);
            assertThat(dead.containerState())
                .as("step 3: the container itself is genuinely still running")
                .isEqualTo(ContainerState.RUNNING);
            assertThat(dead.running())
                .as("step 3: running() still says yes -- that is the lie's premise").isTrue();
            assertThat(dead.liveness())
                .withFailMessage("step 3: a process inside the engine's cgroup was"
                    + " OOM-killed and the tier reported liveness %s", dead.liveness())
                .isEqualTo(WorkloadLiveness.WORKLOAD_DEAD);
            assertThat(dead.workloadDead())
                .withFailMessage("step 3: the attention surface reads workloadDead() and it"
                    + " answered false, so an operator is still told this engine is fine")
                .isTrue();
        } finally {
            try {
                service.destroy(name, true);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /** The container's OWN {@code memory.events}, read inside its cgroup namespace. */
    private static Map<String, Long> memoryEvents(DockerClient docker, String container)
            throws IOException {
        DockerClient.ExecResult result =
            docker.exec(container, List.of("cat", "/sys/fs/cgroup/memory.events"));
        Map<String, Long> events = new LinkedHashMap<>();
        for (String line : result.stdout().split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2) {
                events.put(parts[0], Long.parseLong(parts[1]));
            }
        }
        assertThat(events)
            .withFailMessage("memory.events did not parse as cgroup v2 (stdout '%s',"
                + " stderr '%s'), so every counter assertion would be vacuous",
                result.stdout(), result.stderr())
            .containsKeys("max", "oom_kill");
        return events;
    }

    private static SqliteDatasource freshDatasource() throws IOException {
        File db = File.createTempFile("hohenheim-dbliveness-test", ".db");
        db.delete();
        db.deleteOnExit();
        SqliteDatasource ds = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(ds).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, ds);
        HohenheimTestRuntime.ensureBooted();
        return ds;
    }
}
