package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.WorkloadLiveness;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
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
 * Workload liveness against a REAL daemon: a container whose entrypoint outlives an
 * OOM-killed CHILD stays {@code running} to Docker, and the instance tier must stop
 * calling that healthy.
 *
 * AIDEV-NOTE: this is the reporting half of the defect {@code d6a9bf6} only right-sized
 * around. A database engine killed by the cgroup OOM killer dies as a child of the
 * container entrypoint, so the container never exits, {@code ContainerState} stays
 * RUNNING, and hohenheim reported an engine that refuses every connection as healthy.
 * The step-3 PRESSURE anchor is as load-bearing as the step-5 kill: reading
 * {@code memory.events max} instead of {@code oom_kill} would report every busy workload
 * as broken, which is just the opposite lie.
 *
 * Every daemon assertion is scoped to THIS class's own container handle -- never a
 * daemon-wide count, which four parallel forks share.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class WorkloadLivenessLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    /** Small enough that a runaway child hits it in under a second, big enough for alpine. */
    private static final int MEMORY_LIMIT_MB = 64;

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-liveness-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
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

    @Test
    void anOomKilledChildIsReportedDeadWhileItsContainerKeepsRunning() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, "alpine:latest");
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            ServerModel.localServerId();
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("image", "alpine");
            settings.put("tag", "latest");
            settings.put("command", "sleep 600");
            settings.put("memory_limit_mb", MEMORY_LIMIT_MB);
            int id = instanceRecord("liveness-oom", settings);
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
            InstanceService service = new InstanceService();

            try {
                // 1. Deploy, and confirm the daemon really applied the small cap: without
                //    it every later step would be measuring nothing.
                InstanceStatus deployed = service.deploy(id);
                assertThat(deployed.state())
                    .as("step 1: deploy reports RUNNING").isEqualTo(ContainerState.RUNNING);
                Map<?, ?> hostConfig =
                    (Map<?, ?>) docker.inspectContainer(handle).get("HostConfig");
                assertThat(((Number) hostConfig.get("Memory")).longValue())
                    .withFailMessage("step 1: the daemon applied Memory=%s, expected the"
                        + " declared %s MB cap", hostConfig.get("Memory"), MEMORY_LIMIT_MB)
                    .isEqualTo(MEMORY_LIMIT_MB * 1024L * 1024L);

                // 2. A freshly deployed workload is SERVING, not merely "running".
                assertThat(deployed.liveness())
                    .as("step 2: a fresh deploy is SERVING").isEqualTo(WorkloadLiveness.SERVING);
                assertThat(service.liveStatus(id).workloadDead())
                    .as("step 2: nothing has been killed yet").isFalse();

                // 3. THE POSITIVE ANCHOR. Drive the cgroup hard against its ceiling
                //    WITHOUT killing anything: memory.events "max" counts reclaim, which
                //    is survivable and normal, and a status that read "max" would now
                //    report this healthy workload as dead.
                //
                // AIDEV-NOTE: the ballast MUST be written with oflag=direct, and dd's
                // stderr must NOT be suppressed. Buffered writing was the anchor's
                // original technique and it raced the OOM killer: a buffered write
                // accumulates DIRTY page cache, which is unreclaimable until writeback
                // completes, so the cgroup could hit its ceiling with nothing to reclaim
                // and the kernel killed dd itself -- measured 1 run in 6 on this host,
                // giving the pressure-only anchor an oom_kill it asserts against.
                // O_DIRECT bypasses the page cache on the write, so the only memory this
                // step charges is dd's single 1 MB buffer; the two buffered READS that
                // follow are what generate the reclaim, and CLEAN page cache is always
                // reclaimable, so the read phase cannot be killed. 12/12 kill-free after
                // the change, each run adding ~1670 to "max". Suppressing dd's stderr is
                // what made the original failure report an empty stderr and say nothing
                // about why; an O_DIRECT-less filesystem must be able to name itself.
                DockerClient.ExecResult pressure = docker.exec(handle, List.of("sh", "-c",
                    "dd if=/dev/zero of=/tmp/ballast bs=1M count=256 oflag=direct"
                        + " && cat /tmp/ballast > /dev/null"
                        + " && cat /tmp/ballast > /dev/null && echo pressure-survived"));
                assertThat(pressure.stdout().trim())
                    .withFailMessage("step 3: the pressure workload did not survive;"
                        + " stdout '%s', stderr '%s'", pressure.stdout(), pressure.stderr())
                    .isEqualTo("pressure-survived");
                Map<String, Long> pressed = memoryEvents(docker, handle);
                assertThat(pressed.get("max"))
                    .withFailMessage("step 3: the cgroup never reached its ceiling, so this"
                        + " step proves nothing about pressure; memory.events was %s", pressed)
                    .isPositive();
                assertThat(pressed.get("oom_kill"))
                    .withFailMessage("step 3: the kernel killed something, so this is no"
                        + " longer a pressure-only anchor; memory.events was %s", pressed)
                    .isZero();
                InstanceStatus underPressure = service.liveStatus(id);
                assertThat(underPressure.liveness())
                    .withFailMessage("step 3: a busy-but-alive workload was reported %s --"
                        + " reclaim pressure is not a kill", underPressure.liveness())
                    .isEqualTo(WorkloadLiveness.SERVING);
                assertThat(underPressure.workloadDead())
                    .as("step 3: pressure alone is not death").isFalse();

                // 4. Now kill a CHILD for real. Asserted on the kernel's own counter and
                //    on the container still running, never on the exec's exit code alone.
                DockerClient.ExecResult killed =
                    docker.exec(handle, List.of("tail", "/dev/zero"));
                Map<String, Long> after = memoryEvents(docker, handle);
                assertThat(after.get("oom_kill"))
                    .withFailMessage("step 4: nothing was OOM-killed (exec exit %s), so the"
                        + " defect was never reproduced; memory.events was %s",
                        killed.exitCode(), after)
                    .isPositive();
                Map<?, ?> state = (Map<?, ?>) docker.inspectContainer(handle).get("State");
                assertThat(state.get("Running"))
                    .withFailMessage("step 4: the container itself died, which is the case"
                        + " hohenheim ALREADY reported honestly; State was %s", state)
                    .isEqualTo(Boolean.TRUE);

                // 5. THE DEFECT. The container is still running -- and that is exactly why
                //    ContainerState alone reported this healthy.
                InstanceStatus dead = service.liveStatus(id);
                assertThat(dead.state())
                    .as("step 5: the container is genuinely still RUNNING")
                    .isEqualTo(ContainerState.RUNNING);
                assertThat(dead.running())
                    .as("step 5: running() still says yes -- that is the lie's premise")
                    .isTrue();
                assertThat(dead.liveness())
                    .withFailMessage("step 5: the workload was OOM-killed inside a running"
                        + " container and liveness reported %s", dead.liveness())
                    .isEqualTo(WorkloadLiveness.WORKLOAD_DEAD);
                assertThat(dead.workloadDead())
                    .as("step 5: the tier names the dead workload").isTrue();

                // 6. A restart is what clears it: the state is sticky within a run, not
                //    permanent, so an operator who acts gets an honest answer back.
                service.stop(id);
                InstanceStatus restarted = service.deploy(id);
                assertThat(restarted.liveness())
                    .withFailMessage("step 6: after a restart the workload is fresh, yet"
                        + " liveness reported %s", restarted.liveness())
                    .isEqualTo(WorkloadLiveness.SERVING);
            } catch (IOException daemon) {
                throw new RuntimeException(daemon);
            } finally {
                try {
                    service.destroy(id);
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
        });
    }

    /** The container's OWN {@code memory.events}, read inside its cgroup namespace. */
    private static Map<String, Long> memoryEvents(DockerClient docker, String handle)
            throws IOException {
        DockerClient.ExecResult result =
            docker.exec(handle, List.of("cat", "/sys/fs/cgroup/memory.events"));
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

    private static int instanceRecord(String name, Map<String, Object> settings) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, settings);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }
}
