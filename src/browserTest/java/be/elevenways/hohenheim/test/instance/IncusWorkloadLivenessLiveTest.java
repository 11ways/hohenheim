package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.WorkloadLiveness;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
import be.elevenways.hohenheim.test.live.LiveLane;
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
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Incus half of two questions the Docker tier already answered, proven against the
 * REAL remote daemon: what the host's kernel does to a workload (preflight), and whether
 * the workload inside a running instance is still alive (liveness).
 *
 * AIDEV-NOTE: {@code IncusInstanceRuntime.status} used to answer
 * {@link WorkloadLiveness#UNKNOWN} unconditionally, which was honest and blind -- an Incus
 * workload whose engine is OOM-killed inside a still-running instance reported nothing at
 * all. Incus 7.3's instance state carries no OOM counter (measured: the whole
 * {@code /1.0/instances/x/state} body is usage/peak/swap), so the signal comes out of the
 * instance's OWN cgroup over exec. The step-3 PRESSURE anchor is exactly as load-bearing
 * here as in {@link WorkloadLivenessLiveTest}: {@code memory.events max} counts survivable
 * reclaim, and reading it instead of {@code oom_kill} would report every busy workload as
 * dead. Measured on daystrom before any of this was written: 400 MB of page-cache churn in
 * a 64 MiB container gave {@code max 7168, oom_kill 0} while it was perfectly healthy.
 *
 * Every assertion is scoped to THIS class's own instance handle -- never a daemon-wide
 * count, which parallel forks and a shared remote host would make meaningless.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class IncusWorkloadLivenessLiveTest {

    private static final String HOST = "live-incus-liveness";

    /** Small system-container image; the host's 3.9 GiB of RAM is the binding limit. */
    private static final String IMAGE = "alpine/3.22";

    /** Small enough that a runaway child hits it in under a second, big enough for alpine. */
    private static final int MEMORY_LIMIT_MB = 64;

    private static SqliteDatasource datasource;
    private static LiveIncusHost remote;

    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveIncusHost.configured();
        LiveLane.require(LiveLane.Need.INCUS_HOST, remote != null,
            "no live incus host enrolled at " + LiveIncusHost.CONFIG);

        File db = File.createTempFile("hohenheim-incus-liveness-live", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();

        Db.run(datasource, () ->
            remote.enrollThroughProduct(HOST, "hohenheim-live-liveness"));
    }

    @AfterAll
    static void tearDown() {
        if (remote != null) {
            // Both sweeps are unconditional and fixture-tracked: a setUp that aborts
            // AFTER the trust step (a failing required preflight check) must still hand
            // back the working admin credential it enrolled.
            System.out.println("=== cleanup: shared objects -> "
                + remote.releaseControllerSharedObjects());
            System.out.println("=== cleanup: authorized_keys -> "
                + remote.releaseAuthorizedKeys());
            System.out.println("=== cleanup: incus trust -> " + remote.releaseTrustEntries());
        }
    }

    /**
     * The preflight now RUNS a workload on the host and reads what the kernel did to it,
     * instead of relaying a daemon claim -- and cleans the probe up afterwards.
     */
    @Test
    void preflightProvesTheHostKernelIsolatesARealWorkload() {
        Db.run(datasource, () -> {
            // 1. The stored report of the enrollment ceremony's own preflight run.
            Row host = Models.get(ServerModel.class).findByName(HOST);
            String userns = HostPreflight.storedCheckStatus(host, IncusPreflight.USERNS_CHECK);
            String seccomp = HostPreflight.storedCheckStatus(host, IncusPreflight.SECCOMP_CHECK);
            assertThat(userns)
                .withFailMessage("step 1: no user-namespace verdict was stored at all, so"
                    + " admission rests on nothing")
                .isNotNull();
            assertThat(seccomp)
                .withFailMessage("step 1: no seccomp verdict was stored at all").isNotNull();

            // 2. An Incus system container is unprivileged by DEFAULT, so a tenant host
            //    that fails this is misconfigured -- and this host must pass, or the
            //    enrollment above would already have refused admission.
            assertThat(userns)
                .withFailMessage("step 2: the host handed the probe workload the host's"
                    + " own uid range; stored verdict '%s'", userns)
                .isEqualTo(HostPreflight.STATUS_PASS);
            assertThat(seccomp)
                .withFailMessage("step 2: seccomp is not filtering inside a workload on"
                    + " this host; stored verdict '%s'", seccomp)
                .isEqualTo(HostPreflight.STATUS_PASS);

            // 3. The detail is EVIDENCE, not a claim: it has to carry the literal uid_map
            //    the kernel wrote, which no daemon-config reader could produce.
            Map<?, ?> checks = storedChecks(host);
            String usernsDetail = detailOf(checks, IncusPreflight.USERNS_CHECK);
            assertThat(usernsDetail)
                .withFailMessage("step 3: the userns detail carries no uid_map, so nothing"
                    + " was read from the probe instance's kernel: '%s'", usernsDetail)
                .contains("uid_map reads '");
            assertThat(usernsDetail)
                .withFailMessage("step 3: the probe read back the IDENTITY map, which is"
                    + " what an UNREMAPPED container shows: '%s'", usernsDetail)
                .doesNotContain(HostPreflight.IDENTITY_UID_MAP);

            // 4. The LSM check is advisory and RECORDED either way. daystrom runs Arch,
            //    whose kernel carries no AppArmor at all (measured:
            //    /sys/module/apparmor/parameters/enabled reads 'N'), so a required check
            //    here would make the reference host inadmissible.
            assertThat(HostPreflight.storedCheckStatus(host, "lsm"))
                .as("step 4: the LSM posture is recorded, pass or warn").isNotNull();

            // 5. The probe left NOTHING behind: no hohenheim-preflight-* instance on the
            //    daemon. Scoped to the probe's own name prefix, never a total count.
            try {
                assertThat(remote.hostCommand("incus", "list", "-f", "csv", "-c", "n"))
                    .withFailMessage("step 5: a preflight probe instance survived its own"
                        + " probe, so every future run inherits one more")
                    .doesNotContain("hohenheim-preflight-");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * The liveness axis on the Incus driver, walked through pressure and a real kill.
     */
    @Test
    void anOomKilledChildIsReportedDeadWhileItsIncusInstanceKeepsRunning() {
        Db.run(datasource, () -> {
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("image", IMAGE);
            settings.put("memory_limit_mb", MEMORY_LIMIT_MB);
            int id = instanceRecord("incus-liveness", settings);
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
            InstanceService service = new InstanceService();

            try {
                // 1. Deploy, and confirm the daemon really applied the small cap: without
                //    it every later step would be measuring nothing.
                InstanceStatus deployed = service.deploy(id);
                assertThat(deployed.state())
                    .as("step 1: deploy reports RUNNING").isEqualTo(ContainerState.RUNNING);
                assertThat(remote.exec(handle, "cat /sys/fs/cgroup/memory.max"))
                    .withFailMessage("step 1: the daemon did not apply the declared %s MB"
                        + " cap inside the instance", MEMORY_LIMIT_MB)
                    .isEqualTo(String.valueOf(MEMORY_LIMIT_MB * 1024L * 1024L));

                // 2. A freshly deployed workload is SERVING -- the answer this driver
                //    could not give at all until now.
                assertThat(deployed.liveness())
                    .withFailMessage("step 2: a fresh deploy reported %s", deployed.liveness())
                    .isEqualTo(WorkloadLiveness.SERVING);

                // 3. THE POSITIVE ANCHOR. Drive the cgroup against its ceiling WITHOUT
                //    killing anything: memory.events "max" counts reclaim, which is
                //    survivable and normal, and a driver that read "max" would now call
                //    this healthy workload dead.
                assertThat(remote.exec(handle,
                        "dd if=/dev/zero of=/root/ballast bs=1M count=400 2>/dev/null;"
                            + " cat /root/ballast > /dev/null; cat /root/ballast > /dev/null;"
                            + " rm -f /root/ballast; echo pressure-survived"))
                    .as("step 3: the pressure workload survived").contains("pressure-survived");
                Map<String, Long> pressed = memoryEvents(handle);
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
                //    on the instance still running, never on an exec's exit code alone.
                remote.exec(handle, "tail /dev/zero > /dev/null 2>&1; true");
                Map<String, Long> after = memoryEvents(handle);
                assertThat(after.get("oom_kill"))
                    .withFailMessage("step 4: nothing was OOM-killed, so the defect was"
                        + " never reproduced; memory.events was %s", after)
                    .isPositive();
                assertThat(remote.instanceInfoOrError(handle))
                    .withFailMessage("step 4: the instance itself died, which is the case"
                        + " this driver ALREADY reported honestly")
                    .contains("RUNNING");

                // 5. THE DEFECT. The instance is still running -- which is exactly why
                //    ContainerState alone reported this healthy.
                InstanceStatus dead = service.liveStatus(id);
                assertThat(dead.state())
                    .as("step 5: the instance is genuinely still RUNNING")
                    .isEqualTo(ContainerState.RUNNING);
                assertThat(dead.liveness())
                    .withFailMessage("step 5: the workload was OOM-killed inside a running"
                        + " instance and liveness reported %s", dead.liveness())
                    .isEqualTo(WorkloadLiveness.WORKLOAD_DEAD);
                assertThat(dead.workloadDead())
                    .as("step 5: the tier names the dead workload").isTrue();

                // 6. A restart is what clears it: sticky within a run, not permanent.
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
                remote.forceDelete(handle);
            }
        });
    }

    /** The instance's OWN {@code memory.events}, read over the host's CLI, not our driver. */
    private static Map<String, Long> memoryEvents(String handle) throws IOException {
        String body = remote.exec(handle, "cat /sys/fs/cgroup/memory.events");
        Map<String, Long> events = new LinkedHashMap<>();
        for (String line : body.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2) {
                events.put(parts[0], Long.parseLong(parts[1]));
            }
        }
        assertThat(events)
            .withFailMessage("memory.events did not parse as cgroup v2 ('%s'), so every"
                + " counter assertion would be vacuous", body)
            .containsKeys("max", "oom_kill");
        return events;
    }

    private static Map<?, ?> storedChecks(Row host) {
        Object capabilities = host.get(ServerModel.CAPABILITIES);
        assertThat(capabilities).as("the host carries a stored capabilities report")
            .isInstanceOf(Map.class);
        Object checks = ((Map<?, ?>) capabilities).get("checks");
        assertThat(checks).as("the stored report carries its checks").isInstanceOf(Map.class);
        return (Map<?, ?>) checks;
    }

    private static String detailOf(Map<?, ?> checks, String name) {
        Object check = checks.get(name);
        assertThat(check).as("the stored report carries the '%s' check", name)
            .isInstanceOf(Map.class);
        return String.valueOf(((Map<?, ?>) check).get("detail"));
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
}
