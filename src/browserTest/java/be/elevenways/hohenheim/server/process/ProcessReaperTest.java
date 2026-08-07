package be.elevenways.hohenheim.server.process;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.WorkloadIdentity;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The boot reaper: a child that outlived its controller is killed, and a child of the
 * CURRENT generation never is.
 *
 * AIDEV-NOTE: only two facts are injected -- which pids a uid owns, and which pids a live
 * handler holds -- because neither can be produced honestly without root (a foreign uid's
 * process) or a bound proxy (a live handler). Everything else runs for real: the claims
 * come out of the real {@code system_users} table, the identity refusals run against the
 * real daemon uid, and the processes killed (or spared) are real children of this JVM
 * whose liveness is asserted afterwards. The uid walk itself is pinned separately below
 * against this JVM's own children, so nothing about the mechanism is only mocked.
 */
class ProcessReaperTest {

    /** A uid that is neither root nor the daemon's, so a claim on it is reapable. */
    private static final int SITE_UID = 61001;

    private final List<Process> spawned = new ArrayList<>();
    private final List<Integer> userRows = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        SiteTypes.boot();
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterEach
    void cleanUp() {
        for (Process process : spawned) {
            process.destroyForcibly();
        }
        spawned.clear();
        for (Integer id : userRows) {
            Models.get(SystemUserModel.class).delete(id);
        }
        userRows.clear();
    }

    /**
     * The journey the two false AIDEV-NOTEs used to deny: a child survives a controller
     * restart, and the boot sweep is what actually ends it.
     */
    @Test
    void anOrphanSurvivesARestartAndIsReapedAtBoot() throws Exception {
        claim("reaper-site-a", SITE_UID, 7001);

        // 1. A managed child that outlived its controller is simply still running -- no
        //    JVM of ours holds it, and nothing has ever killed it.
        Process orphan = spawn();
        assertThat(orphan.isAlive())
            .as("step 1: the orphan is alive before the sweep, which is the whole gap")
            .isTrue();

        // 2. The boot sweep identifies it by the site's exclusively claimed uid and kills
        //    it. No live handler claims any pid: this IS a fresh controller generation.
        ProcessReaper.ReapResult result = ProcessReaper.reapOrphans(
            owned(Map.of(SITE_UID, List.of(orphan.pid()))), site -> Set.of());
        assertThat(result.reaped())
            .as("step 2: the sweep must report the orphan as reaped")
            .isEqualTo(1);

        // 3. Reaped means DEAD, verified against the process itself and not against the
        //    sweep's own bookkeeping.
        assertThat(orphan.waitFor(10, TimeUnit.SECONDS))
            .as("step 3: the orphan must really be gone, not merely reported")
            .isTrue();
        assertThat(orphan.isAlive()).as("step 3: no survivor").isFalse();
    }

    /**
     * The DANGEROUS direction: a child the current controller generation still holds is
     * never touched, however old it is.
     */
    @Test
    void aLiveChildOfTheCurrentGenerationIsNeverReaped() throws Exception {
        claim("reaper-site-b", SITE_UID + 1, 7002);

        Process live = spawn();
        Process orphan = spawn();

        // 1. The live handler holds one of the two pids; both are owned by the site uid.
        ProcessReaper.ReapResult result = ProcessReaper.reapOrphans(
            owned(Map.of(SITE_UID + 1, List.of(live.pid(), orphan.pid()))),
            site -> Set.of(live.pid()));

        // 2. Exactly the unheld one dies.
        assertThat(result.reaped())
            .as("step 2: only the pid no live handler claims may be reaped")
            .isEqualTo(1);
        assertThat(orphan.waitFor(10, TimeUnit.SECONDS))
            .as("step 2: the orphan is gone")
            .isTrue();

        // 3. The live child is untouched -- the assertion that would fail if liveness were
        //    judged by age, by uid alone, or not at all.
        assertThat(live.isAlive())
            .as("step 3: a child of the CURRENT generation must survive its own boot sweep")
            .isTrue();
    }

    /**
     * A site whose identity is the daemon's own is reaped by NOTHING: a sweep keyed on
     * that uid would kill the control plane.
     */
    @Test
    void aSiteClaimingTheDaemonIdentityIsRefusedNotReaped() throws Exception {
        Integer daemonUid = WorkloadIdentity.daemonUid();
        assertThat(daemonUid).as("this test needs the daemon's own uid").isNotNull();
        claim("reaper-site-daemon", daemonUid, 7003);

        Process notAnOrphan = spawn();

        // 1. The sweep refuses the site by name and kills nothing for it, even though the
        //    injected fact says the uid owns a reapable pid.
        ProcessReaper.ReapResult result = ProcessReaper.reapOrphans(
            owned(Map.of(daemonUid, List.of(notAnOrphan.pid()))), site -> Set.of());
        assertThat(result.reaped())
            .as("step 1: nothing may be reaped for a site claiming the daemon's identity")
            .isZero();
        assertThat(result.sitesRefused())
            .as("step 1: and the refusal must be reported, not silent")
            .isGreaterThanOrEqualTo(1);

        // 2. The process is still running -- this is the control plane in the real case.
        assertThat(notAnOrphan.isAlive())
            .as("step 2: a process under the daemon's own uid is never killed")
            .isTrue();

        // 3. Positive anchor: the same sweep still reaps for a site whose uid IS its own,
        //    so step 1 measured the refusal and not a sweep that does nothing at all.
        claim("reaper-site-c", SITE_UID + 2, 7004);
        Process orphan = spawn();
        ProcessReaper.ReapResult second = ProcessReaper.reapOrphans(
            owned(Map.of(daemonUid, List.of(notAnOrphan.pid()),
                SITE_UID + 2, List.of(orphan.pid()))), site -> Set.of());
        assertThat(second.reaped())
            .as("step 3: a properly-identified orphan is still reaped by the same sweep")
            .isEqualTo(1);
        assertThat(orphan.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(notAnOrphan.isAlive())
            .as("step 3: and the daemon-uid process is STILL alive")
            .isTrue();
    }

    /**
     * The one injected fact, pinned against the kernel: the /proc walk really does answer
     * "which pids does this uid own".
     */
    @Test
    void theUidWalkFindsRealProcessesAndOnlyThose() throws Exception {
        Process child = spawn();
        Integer daemonUid = WorkloadIdentity.daemonUid();
        assertThat(daemonUid).isNotNull();

        assertThat(ProcessReaper.pidsOwnedBy(daemonUid))
            .as("the walk must find this JVM's own child and this JVM itself")
            .contains(child.pid(), ProcessHandle.current().pid());
        assertThat(ProcessReaper.pidsOwnedBy(SITE_UID + 900))
            .as("and must find nothing for a uid nothing runs as")
            .isEmpty();
    }

    // -- helpers --------------------------------------------------------------

    private static IntFunction<List<Long>> owned(Map<Integer, List<Long>> byUid) {
        return uid -> byUid.getOrDefault(uid, List.of());
    }

    /** A real, long-lived child of this JVM standing in for a managed site process. */
    private Process spawn() throws Exception {
        Map<String, String> environment = new LinkedHashMap<>(
            SystemUsers.safeEnvironment(System.getProperty("java.io.tmpdir")));
        Process process = SystemUsers.executionBuilder(null, environment,
            List.of("/bin/sh", "-c", "sleep 600"), false).start();
        spawned.add(process);
        // Give the exec chain (setpriv) time to become the sleep before anything signals.
        Thread.sleep(200);
        assertThat(process.isAlive()).as("the stand-in child must be running").isTrue();
        return process;
    }

    /** A system_users row exclusively claimed by a site, the reaper's only input. */
    private void claim(String name, int uid, int siteId) {
        var model = Models.get(SystemUserModel.class);
        Row row = model.createEmptyRow();
        row.set(SystemUserModel.NAME, name);
        row.set(SystemUserModel.UID, uid);
        row.set(SystemUserModel.GID, uid);
        row.set(SystemUserModel.HOME, "/nonexistent");
        row.set(SystemUserModel.GECOS, "reaper test");
        row.set(SystemUserModel.OBSOLETE, false);
        row.set(SystemUserModel.LAST_SEEN_AT, Instant.now());
        row.set(SystemUserModel.SITE_ID, siteId);
        model.save(row);
        userRows.add(row.get(SystemUserModel.ID));
    }
}
