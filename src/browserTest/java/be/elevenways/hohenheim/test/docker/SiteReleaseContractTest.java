package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.docker.SiteInstances;
import be.elevenways.hohenheim.server.docker.SiteReleases;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The health-gated release engine's STATE MACHINE with no daemon anywhere: candidate up,
 * probe outcome, swap, drain, retention, reclaim, the digest pin, the rollback pin and
 * boot recovery, all against {@link FakeDockerDaemon} -- one in-memory daemon whose
 * running containers really listen on loopback, so the engine's health probe is a genuine
 * HTTP round trip and an unhealthy candidate is a real 503 rather than a stubbed boolean.
 *
 * WHAT THIS CANNOT PROVE, and therefore what {@code SiteReleaseLiveTest} remains the only
 * proof of -- read no hermetic green here as total coverage:
 *
 * <ul>
 *   <li>zero dropped requests across the swap. There is no proxy and no traffic here; the
 *       routing-generation swap that makes the switch atomic is the live test's Hammer.</li>
 *   <li>{@code DockerInstanceRuntime}, {@code WorkloadNetworks} and the nftables workload
 *       policy. The fake stands in for the kind's runtime, so container hardening, the
 *       private network and its kernel chains are not exercised at all.</li>
 *   <li>the image fingerprint pin ({@code pinResolvedImage}), which needs a driver that
 *       reports an image identity; the fake deliberately reports none.</li>
 *   <li>a real image, a real pull and a real build. The digest here is derived from the
 *       reference, so it proves the engine PINS and RE-USES a digest, never that a
 *       registry produced one.</li>
 * </ul>
 *
 * AIDEV-NOTE: every journey mints its OWN site record and tears it down through the
 * production {@code destroyFor}, so the port ledger and the fake daemon start each journey
 * clean and no assertion can be satisfied by another journey's leftovers.
 */
class SiteReleaseContractTest {

    private static final String SITE_MODEL = SiteModel.MODEL_ID.toString();

    private static SqlDatasource datasource;
    private static FakeDockerDaemon daemon;
    private static Integer savedProbeTimeout;
    private static Integer savedProbeInterval;
    private static Integer savedDrain;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        // ONE registered database per class: the controller identity every daemon
        // resource name resolves through reads the CURRENT datasource.
        HohenheimTestRuntime.ensureBooted();
        daemon = new FakeDockerDaemon();
        daemon.install();
        savedProbeTimeout = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS);
        savedProbeInterval = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Releases.PROBE_INTERVAL_MS);
        savedDrain = HohenheimSettings.VALUES.getValue(HohenheimSettings.Releases.DRAIN_SECONDS);
        // A refused candidate must fail FAST here: the probe window is the test's runtime.
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, 2);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS, 50);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, 0);
        Db.run(datasource, HostFixtures::admitLocal);
    }

    @AfterAll
    static void tearDown() {
        FakeDockerDaemon.restore();
        if (daemon != null) {
            daemon.close();
            daemon = null;
        }
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, savedProbeTimeout);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Releases.PROBE_INTERVAL_MS, savedProbeInterval);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, savedDrain);
    }

    /**
     * The health gate as STATE: a candidate that answers but is unhealthy is destroyed,
     * the operation says so durably, and the prior release is still the serving one --
     * plus the reuse lane in between, which must not mint a release for an unchanged site.
     */
    @Test
    void aRefusedCandidateNeverTakesTrafficAndTheServingReleaseSurvives() {
        Db.run(datasource, () -> {
            int siteId = site("gate-site");
            try {
                // 1. The initial release: nothing to protect, so it deploys directly, and
                //    the record is pinned to the DIGEST behind the tag, never the tag.
                SiteInstances.ensureRunning(siteId, "gate-site", settingsFor("v1"));
                Row serving = servingOf(siteId);
                assertThat(serving).as("step 1: a serving release exists").isNotNull();
                int servingId = serving.get(InstanceModel.ID);
                assertThat(settingsOf(serving).get("image"))
                    .as("step 1: the release is pinned to the image digest, not the tag")
                    .isEqualTo(FakeDockerDaemon.digestOf("fake/app:v1"));
                assertThat(latestOp(siteId).get(ReleaseOperationModel.STATUS))
                    .as("step 1: the initial release is a recorded, succeeded operation")
                    .isEqualTo(ReleaseOperationModel.STATUS_SUCCEEDED);
                assertThat(daemon.isRunning(FakeDockerDaemon.handleOf(servingId)))
                    .as("step 1: and the workload is really running at the daemon").isTrue();

                // 2. An unchanged converge is REUSED: no operation, no second workload.
                int opsAfterFirst = opCount(siteId);
                SiteInstances.ensureRunning(siteId, "gate-site", settingsFor("v1"));
                assertThat(opCount(siteId))
                    .as("step 2: an unchanged site releases nothing at all")
                    .isEqualTo(opsAfterFirst);
                assertThat(servingOf(siteId).get(InstanceModel.ID))
                    .as("step 2: and keeps the very same release").isEqualTo(servingId);

                // 3. A changed source whose candidate ANSWERS but is unhealthy. The gate
                //    must hold: the candidate never becomes serving, and the prior release
                //    is returned still serving rather than the site going down.
                daemon.answerWithForNextWorkload(503);
                SiteInstances.ensureRunning(siteId, "gate-site", settingsFor("v2"));
                Row op = latestOp(siteId);
                assertThat(op.get(ReleaseOperationModel.STATUS))
                    .as("step 3: the refused release is recorded FAILED, never forgotten")
                    .isEqualTo(ReleaseOperationModel.STATUS_FAILED);
                assertThat((String) op.get(ReleaseOperationModel.FAILURE_REASON))
                    .as("step 3: and the failure names the probe")
                    .contains("release_probe_failed");

                // 4. STATE of the refused candidate: soft-deleted record, no container at
                //    the daemon, no port claim -- and the daemon's own call sequence shows
                //    it was created, started, then torn down, never flipped to serving.
                Integer candidateId = op.get(ReleaseOperationModel.CANDIDATE_INSTANCE_ID);
                assertThat(candidateId).as("step 4: the candidate was recorded").isNotNull();
                String candidateHandle = FakeDockerDaemon.handleOf(candidateId);
                assertThat((Object) Models.get(InstanceModel.class).findById(candidateId)
                        .get(InstanceModel.DELETED_AT))
                    .as("step 4: the refused candidate's record is soft-deleted").isNotNull();
                assertThat(daemon.exists(candidateHandle))
                    .as("step 4: and its container is gone at the daemon").isFalse();
                assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, candidateId))
                    .as("step 4: a refused candidate holds no port claim").isEmpty();
                assertThat(daemon.callsFor(candidateHandle))
                    .as("step 4: the candidate's WHOLE life at the daemon was create, start,"
                        + " destroy -- it was never stopped-and-kept the way a RETAINED"
                        + " release is, which is how a refused candidate differs from a"
                        + " superseded one")
                    .containsExactly("create:" + candidateHandle, "start:" + candidateHandle,
                        "destroy:" + candidateHandle);

                // 5. The serving release is untouched: same row, same digest, still
                //    running, and still the ONLY release the site has.
                Row still = servingOf(siteId);
                assertThat(still.get(InstanceModel.ID))
                    .as("step 5: the serving release is the SAME instance")
                    .isEqualTo(servingId);
                assertThat(settingsOf(still).get("image"))
                    .as("step 5: still pinned to the good digest")
                    .isEqualTo(FakeDockerDaemon.digestOf("fake/app:v1"));
                assertThat(daemon.isRunning(FakeDockerDaemon.handleOf(servingId)))
                    .as("step 5: and still running at the daemon").isTrue();
                assertThat(SiteReleases.newestRetired(siteId))
                    .as("step 5: a refused release leaves NO rollback target behind")
                    .isNull();
            } finally {
                SiteInstances.destroyFor(siteId);
            }
        });
    }

    /**
     * The whole forward-and-back lineage: swap, drain, retain exactly one release, roll
     * back onto its digest-pinned spec, reclaim the older one, hold the pin across an
     * unchanged converge and dissolve it on a source change.
     */
    @Test
    void aHealthySwapDrainsRetainsRollsBackAndReclaims() {
        Db.run(datasource, () -> {
            int siteId = site("swap-site");
            try {
                // 1. Release v1, then v2 through the gate.
                SiteInstances.ensureRunning(siteId, "swap-site", settingsFor("v1"));
                int firstId = servingOf(siteId).get(InstanceModel.ID);
                SiteInstances.ensureRunning(siteId, "swap-site", settingsFor("v2"));
                Row swapOp = latestOp(siteId);
                int secondId = servingOf(siteId).get(InstanceModel.ID);
                assertThat(secondId)
                    .as("step 1: a healthy candidate becomes a NEW serving release")
                    .isNotEqualTo(firstId);
                assertThat(settingsOf(servingOf(siteId)).get("image"))
                    .as("step 1: running the v2 digest")
                    .isEqualTo(FakeDockerDaemon.digestOf("fake/app:v2"));

                // 2. Retention is exactly one release deep, and the drain settles the
                //    superseded workload: role retired, container STOPPED but kept.
                assertThat(SiteReleases.newestRetired(siteId).get(InstanceModel.ID))
                    .as("step 2: the superseded release is the retained rollback target")
                    .isEqualTo(firstId);
                await("step 2: the release operation completes after the drain window",
                    () -> ReleaseOperationModel.STATUS_SUCCEEDED.equals(
                        reload(swapOp).get(ReleaseOperationModel.STATUS)));
                assertThat(daemon.exists(FakeDockerDaemon.handleOf(firstId)))
                    .as("step 2: the retained release's container is KEPT").isTrue();
                assertThat(daemon.isRunning(FakeDockerDaemon.handleOf(firstId)))
                    .as("step 2: but stopped once the drain window passed").isFalse();
                assertThat((String) reload(swapOp).get(ReleaseOperationModel.STEP_LOG))
                    .as("step 2: every phase is visible on the durable record")
                    .contains("candidate instance").contains("probing")
                    .contains("health probe passed").contains("switching traffic")
                    .contains("retained as the rollback target").contains("release complete");

                // 3. Roll back onto the RETAINED spec. Nothing is pulled or rebuilt: the
                //    artifact is addressed by content, which is what makes a rollback
                //    survive a moved tag.
                int pullsBefore = daemon.callCount("api:POST /images/create");
                SiteReleases.rollback(siteId);
                Row rollbackOp = latestOp(siteId);
                Row back = servingOf(siteId);
                assertThat(settingsOf(back).get("image"))
                    .as("step 3: the rolled-back release runs the pinned v1 digest")
                    .isEqualTo(FakeDockerDaemon.digestOf("fake/app:v1"));
                assertThat(rollbackOp.get(ReleaseOperationModel.KIND))
                    .as("step 3: the rollback is its own recorded operation")
                    .isEqualTo(ReleaseOperationModel.KIND_ROLLBACK);
                assertThat(daemon.callCount("api:POST /images/create"))
                    .as("step 3: and it pulled NOTHING -- no tag, no source, no registry")
                    .isEqualTo(pullsBefore);

                // 4. RECLAIM: the v2 release becomes the new rollback target and the
                //    ORIGINAL v1 instance is destroyed, record and container alike.
                await("step 4: the rollback operation completes after the drain window",
                    () -> ReleaseOperationModel.STATUS_SUCCEEDED.equals(
                        reload(rollbackOp).get(ReleaseOperationModel.STATUS)));
                assertThat((Object) Models.get(InstanceModel.class).findById(firstId)
                        .get(InstanceModel.DELETED_AT))
                    .as("step 4: the older retired release was reclaimed (record)")
                    .isNotNull();
                assertThat(daemon.exists(FakeDockerDaemon.handleOf(firstId)))
                    .as("step 4: the older retired release was reclaimed (daemon)")
                    .isFalse();
                assertThat(SiteReleases.newestRetired(siteId).get(InstanceModel.ID))
                    .as("step 4: retention stays exactly one release deep")
                    .isEqualTo(secondId);

                // 5. The rollback PINS: the operator rejected exactly this source, so an
                //    unchanged converge must not release the rejected spec back on.
                assertThat(SiteReleases.pinnedByRollback(siteId,
                        SiteReleases.sourceFingerprint(siteId, settingsFor("v2"))))
                    .as("step 5: the rejected source is pinned").isTrue();
                int opsBefore = opCount(siteId);
                SiteInstances.ensureRunning(siteId, "swap-site", settingsFor("v2"));
                assertThat(opCount(siteId))
                    .as("step 5: the pinned converge released nothing")
                    .isEqualTo(opsBefore);
                assertThat(settingsOf(servingOf(siteId)).get("image"))
                    .as("step 5: and the rollback still stands")
                    .isEqualTo(FakeDockerDaemon.digestOf("fake/app:v1"));

                // 6. A SOURCE CHANGE dissolves the pin: the declared spec wins again
                //    through a fresh gated release.
                SiteInstances.ensureRunning(siteId, "swap-site", settingsFor("v3"));
                assertThat(settingsOf(servingOf(siteId)).get("image"))
                    .as("step 6: a changed source dissolves the pin and releases forward")
                    .isEqualTo(FakeDockerDaemon.digestOf("fake/app:v3"));
                assertThat(opCount(siteId))
                    .as("step 6: as its own recorded operation").isGreaterThan(opsBefore);
                // The drain runs on a virtual thread; let it settle before the teardown so
                // the reclaim and destroyFor cannot race over the same rows.
                Row forwardOp = latestOp(siteId);
                await("step 6: the forward release completes after its drain window",
                    () -> ReleaseOperationModel.STATUS_SUCCEEDED.equals(
                        reload(forwardOp).get(ReleaseOperationModel.STATUS)));
            } finally {
                SiteInstances.destroyFor(siteId);
            }
        });
    }

    /**
     * Boot recovery, which has no live coverage of its half-flipped branch at all: a lost
     * drain is finished, a switch that crashed BETWEEN the two role flips is completed, a
     * pre-switch operation loses its candidate and records the truth, and a candidate no
     * operation answers for is swept.
     */
    @Test
    void bootRecoverySettlesEveryHalfFinishedRelease() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, 600);
        Db.run(datasource, () -> {
            int siteId = site("recover-site");
            try {
                // 1. A real release whose drain will not run for 600 seconds: the
                //    lost-drain shape a controller crash leaves behind.
                SiteInstances.ensureRunning(siteId, "recover-site", settingsFor("v1"));
                int firstId = servingOf(siteId).get(InstanceModel.ID);
                SiteInstances.ensureRunning(siteId, "recover-site", settingsFor("v2"));
                int secondId = servingOf(siteId).get(InstanceModel.ID);
                Row drainingOp = latestOp(siteId);
                assertThat(drainingOp.get(ReleaseOperationModel.STATUS))
                    .as("step 1: the superseded release is still draining")
                    .isEqualTo(ReleaseOperationModel.STATUS_DRAINING);
                assertThat(daemon.isRunning(FakeDockerDaemon.handleOf(firstId)))
                    .as("step 1: and its workload is still running").isTrue();

                // 2. A HALF-FLIPPED switch: the candidate already carries the serving
                //    role, the superseded row still does too, and the operation died in
                //    between. Nothing but recovery can settle this, and until now nothing
                //    -- hermetic or live -- ever exercised it.
                int halfCandidate = ownedRelease(siteId, "recover-half",
                    InstanceModel.ROLE_SERVING, "v9");
                int halfRetiring = ownedRelease(siteId, "recover-half-old",
                    InstanceModel.ROLE_SERVING, "v8");
                Row switchingOp = operationRow(siteId, ReleaseOperationModel.STATUS_SWITCHING,
                    halfCandidate, halfRetiring);

                // 3. A PRE-SWITCH operation with a live candidate: the crash-during-probe
                //    shape, whose candidate never took traffic and must be destroyed.
                int probingCandidate = ownedRelease(siteId, "recover-probe",
                    InstanceModel.ROLE_CANDIDATE, "v7");
                deployOwned(siteId, probingCandidate);
                Row probingOp = operationRow(siteId, ReleaseOperationModel.STATUS_PROBING,
                    probingCandidate, null);

                // 4. And an ORPHAN candidate no operation answers for at all.
                int orphan = ownedRelease(siteId, "recover-orphan",
                    InstanceModel.ROLE_CANDIDATE, "v6");
                deployOwned(siteId, orphan);

                SiteReleases.recoverInterrupted();

                // 5. STATE after recovery, every branch:
                assertThat(reload(drainingOp).get(ReleaseOperationModel.STATUS))
                    .as("step 5: the lost drain finishes SUCCEEDED (the switch had landed)")
                    .isEqualTo(ReleaseOperationModel.STATUS_SUCCEEDED);
                assertThat(daemon.isRunning(FakeDockerDaemon.handleOf(firstId)))
                    .as("step 5: and recovery stopped its superseded workload").isFalse();

                assertThat((String) Models.get(InstanceModel.class).findById(halfRetiring)
                        .get(InstanceModel.RUNTIME_ROLE))
                    .as("step 5: the half-flipped switch is COMPLETED -- the old row is"
                        + " retired, so two rows can never both claim to be serving")
                    .isEqualTo(InstanceModel.ROLE_RETIRED);
                assertThat((String) Models.get(InstanceModel.class).findById(halfCandidate)
                        .get(InstanceModel.RUNTIME_ROLE))
                    .as("step 5: and the candidate that had passed its probe keeps serving")
                    .isEqualTo(InstanceModel.ROLE_SERVING);
                assertThat(reload(switchingOp).get(ReleaseOperationModel.STATUS))
                    .as("step 5: the half-flipped operation settles SUCCEEDED, not failed")
                    .isEqualTo(ReleaseOperationModel.STATUS_SUCCEEDED);
                assertThat((String) reload(switchingOp).get(ReleaseOperationModel.STEP_LOG))
                    .as("step 5: and says so on the durable record")
                    .contains("boot recovery completed the half-flipped switch");

                assertThat(reload(probingOp).get(ReleaseOperationModel.STATUS))
                    .as("step 5: the pre-switch operation is INTERRUPTED, visibly")
                    .isEqualTo(ReleaseOperationModel.STATUS_INTERRUPTED);
                assertThat((Object) Models.get(InstanceModel.class).findById(probingCandidate)
                        .get(InstanceModel.DELETED_AT))
                    .as("step 5: its candidate's record died").isNotNull();
                assertThat(daemon.exists(FakeDockerDaemon.handleOf(probingCandidate)))
                    .as("step 5: and its container is gone at the daemon").isFalse();

                assertThat(daemon.exists(FakeDockerDaemon.handleOf(orphan)))
                    .as("step 5: the orphaned candidate nothing answered for was swept")
                    .isFalse();
                assertThat(servingOf(siteId).get(InstanceModel.ID))
                    .as("step 5: and no recovery branch touched a serving release")
                    .isIn(secondId, halfCandidate);
            } finally {
                HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, 0);
                SiteInstances.destroyFor(siteId);
            }
        });
    }

    /**
     * A daemon that reports a container RUNNING while the workload inside it is dead --
     * the OOM-killed engine shape -- must be REDEPLOYED, not reported converged.
     *
     * AIDEV-NOTE: this journey found a behaviour defect and is the counterfactual for its
     * fix. {@code reusableStatus} already refused to reuse an OOM-killed container, but
     * the release path it fell through to computed {@code protecting} from
     * {@code running() && publishedPort() != null} alone, decided the spec was unchanged,
     * and returned the SAME dead release with the operation recorded succeeded ("spec
     * unchanged; fingerprint adopted without a deploy"). The refusal one layer up
     * therefore achieved nothing: the site kept pointing at a dead workload and every
     * converge reported success. See SiteReleases.release.
     */
    @Test
    void aDeadWorkloadIsRedeployedInsteadOfReportedConverged() {
        Db.run(datasource, () -> {
            int siteId = site("dead-site");
            try {
                // 1. A healthy serving release.
                SiteInstances.ensureRunning(siteId, "dead-site", settingsFor("v1"));
                int servingId = servingOf(siteId).get(InstanceModel.ID);
                String handle = FakeDockerDaemon.handleOf(servingId);
                assertThat(daemon.isRunning(handle))
                    .as("step 1: the release is running").isTrue();

                // 2. The workload inside dies while the container keeps reporting RUNNING.
                daemon.reportWorkloadDead(handle);
                assertThat(new InstanceService().liveStatus(servingId).workloadDead())
                    .as("step 2: the driver reports the workload dead inside a running"
                        + " container -- the state running() alone calls healthy")
                    .isTrue();

                // 3. An UNCHANGED converge (a routing reload, the common case) must not
                //    report success over a dead workload: the release is redeployed.
                int createsBefore = daemon.callCount("create:" + handle);
                SiteInstances.ensureRunning(siteId, "dead-site", settingsFor("v1"));
                assertThat(daemon.callCount("create:" + handle))
                    .as("step 3: the dead workload was REDEPLOYED, not adopted")
                    .isEqualTo(createsBefore + 1);

                // 4. STATE: the same release row still serves (nothing was minted) and its
                //    workload is alive again -- a recreate is also what clears the flag.
                assertThat(servingOf(siteId).get(InstanceModel.ID))
                    .as("step 4: the redeploy reused the release row").isEqualTo(servingId);
                assertThat(new InstanceService().liveStatus(servingId).workloadDead())
                    .as("step 4: and the workload is alive again").isFalse();
                assertThat((String) Models.get(InstanceModel.class).findById(servingId)
                        .get(InstanceModel.STATUS))
                    .as("step 4: recorded running, from the deploy's own fenced stamp")
                    .isEqualTo(InstanceModel.STATUS_RUNNING);
            } finally {
                SiteInstances.destroyFor(siteId);
            }
        });
    }

    /**
     * The drain's stop-failure branch: a superseded release the daemon REFUSES to stop
     * must not take the release that just took traffic down with it. The branch is a
     * catch rather than a throw for exactly that reason, and until now nothing executed
     * it -- every {@code completeDrain} call site was exercised on the success path only.
     *
     * AIDEV-NOTE: this journey is why {@code FakeDockerDaemon.refuseStopOf} exists. What
     * it pins: the new release keeps serving, the failure is on the DURABLE record an
     * operator reads (never only in the log), the superseded workload is left visibly
     * behind rather than forgotten, and the drain RUNS ON past the catch to finish the
     * operation SUCCEEDED -- a release that switched traffic is not a failed release.
     */
    @Test
    void aSupersededReleaseWhoseStopFailsNeverTakesTheNewReleaseDownWithIt() {
        Db.run(datasource, () -> {
            int siteId = site("stop-fails-site");
            String supersededHandle = null;
            try {
                // 1. A serving v1 release, and the daemon then wedges ITS handle: every
                //    stop of that container is refused from here on.
                SiteInstances.ensureRunning(siteId, "stop-fails-site", settingsFor("v1"));
                int supersededId = servingOf(siteId).get(InstanceModel.ID);
                supersededHandle = FakeDockerDaemon.handleOf(supersededId);
                daemon.refuseStopOf(supersededHandle);
                assertThat(daemon.isRunning(supersededHandle))
                    .as("step 1: the release to be superseded is running").isTrue();

                // 2. Release v2 over it. The switch happens, then the drain tries -- and
                //    fails -- to stop the superseded workload.
                SiteInstances.ensureRunning(siteId, "stop-fails-site", settingsFor("v2"));
                int servingId = servingOf(siteId).get(InstanceModel.ID);
                assertThat(servingId)
                    .as("step 2: a NEW release took traffic").isNotEqualTo(supersededId);
                await("step 2: the operation settles after the drain window",
                    () -> reload(latestOp(siteId)).get(ReleaseOperationModel.FINISHED_AT)
                        != null);

                // 3. The POSITIVE anchor: the new release is up and serving. A drain that
                //    tore everything down would satisfy "the old one is still there" for
                //    free.
                assertThat(daemon.isRunning(FakeDockerDaemon.handleOf(servingId)))
                    .as("step 3: the new release is running at the daemon, untouched by"
                        + " the failed stop of the one it replaced").isTrue();
                assertThat((String) Models.get(InstanceModel.class).findById(servingId)
                        .get(InstanceModel.STATUS))
                    .as("step 3: and it is recorded running").isEqualTo(
                        InstanceModel.STATUS_RUNNING);

                // 4. The failure is WHERE AN OPERATOR SEES IT -- the durable step log of
                //    the operation, not a line in a log file nobody reads. And the
                //    operation is SUCCEEDED: traffic switched, so this release worked.
                Row op = reload(latestOp(siteId));
                assertThat(Map.of(
                        "status", String.valueOf((Object) op.get(ReleaseOperationModel.STATUS)),
                        "reason", String.valueOf(
                            (Object) op.get(ReleaseOperationModel.FAILURE_REASON))))
                    .as("step 4: a release whose traffic switch landed is SUCCEEDED, and"
                        + " carries no failure reason -- the stop is not the release")
                    .isEqualTo(Map.of("status", ReleaseOperationModel.STATUS_SUCCEEDED,
                        "reason", "null"));
                assertThat((String) op.get(ReleaseOperationModel.STEP_LOG))
                    .as("step 4: the stop failure is recorded verbatim on the record")
                    .contains("WARNING: superseded release stop failed");
                assertThat((String) op.get(ReleaseOperationModel.STEP_LOG))
                    .as("step 4: and the drain RAN ON past the catch to finish the"
                        + " operation -- the branch is a catch, never an early return")
                    .contains("release complete");

                // 5. The leftover is REAL and visible: the workload is still running at
                //    the daemon (nothing pretended otherwise) and its record says ERROR,
                //    which is what the reconciler and the operator both key on.
                assertThat(daemon.callsFor(supersededHandle))
                    .as("step 5: the daemon really refused the stop it was asked for")
                    .contains("stop-refused:" + supersededHandle);
                assertThat(daemon.isRunning(supersededHandle))
                    .as("step 5: so the superseded workload is still up -- a leftover the"
                        + " instance tier surfaces, never a silent shrug").isTrue();
                assertThat((String) Models.get(InstanceModel.class).findById(supersededId)
                        .get(InstanceModel.STATUS))
                    .as("step 5: and its record carries the error, not a false 'stopped'")
                    .isEqualTo(InstanceModel.STATUS_ERROR);
                assertThat((Object) Models.get(InstanceModel.class).findById(supersededId)
                        .get(InstanceModel.RUNTIME_ROLE))
                    .as("step 5: it is still the retained rollback target")
                    .isEqualTo(InstanceModel.ROLE_RETIRED);
            } finally {
                if (supersededHandle != null) {
                    daemon.allowStopOf(supersededHandle);
                }
                HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, 0);
                SiteInstances.destroyFor(siteId);
            }
        });
    }

    // -- fixture plumbing -----------------------------------------------------

    /** The site record the engine's fingerprint, activity and ownership all resolve to. */
    /**
     * The operation record's WRITE DISCIPLINE, the {@code stampRole} lesson applied to
     * the record the engine holds open: a step may never carry the rest of a stale
     * operation Row back to the database.
     *
     * The Row is minted before the daemon work and lives for the whole operation, so
     * every {@code Models.save} of it used to rewrite the kind, the owner and both
     * fingerprints back to their creation-time values -- and report success. The rival
     * writer here is injected at the daemon, which is exactly where the real window is.
     */
    @Test
    void anOperationStepNeverCarriesAStaleOperationRowBackToTheDatabase() {
        Db.run(datasource, () -> {
            int siteId = site("stale-op-site");
            try {
                // 1. A release whose deploy admits a rival writer: by the time the daemon
                //    is asked to start the workload the operation row already exists, is
                //    recorded DEPLOYING, and the engine holds its Row open for the rest of
                //    the operation -- the same window the snapshot tier's operator-editable
                //    note is exposed through today.
                int[] touched = new int[1];
                daemon.duringNextStart(() -> {
                    Row inFlight = latestOp(siteId);
                    touched[0] = inFlight.get(ReleaseOperationModel.ID);
                    Models.get(ReleaseOperationModel.class).find()
                        .where(ReleaseOperationModel.ID.eq(touched[0]))
                        .assign(ReleaseOperationModel.SITE_FINGERPRINT, "rival-site-print")
                        .assign(ReleaseOperationModel.SPEC_FINGERPRINT, "rival-spec-print")
                        .updateAll();
                });
                SiteInstances.ensureRunning(siteId, "stale-op-site", settingsFor("v1"));

                // 2. The POSITIVE anchor: the operation did finish, and its own terminal
                //    columns really landed. A write that persisted nothing at all would
                //    otherwise satisfy step 3 for free.
                Row op = latestOp(siteId);
                assertThat(op.get(ReleaseOperationModel.ID))
                    .as("step 2: the rival wrote the operation the engine is holding")
                    .isEqualTo(touched[0]);
                assertThat(Map.of(
                        "status", String.valueOf((Object) op.get(ReleaseOperationModel.STATUS)),
                        "image", String.valueOf((Object) op.get(ReleaseOperationModel.IMAGE_ID))))
                    .as("step 2: the operation's own outcome columns landed")
                    .isEqualTo(Map.of("status", ReleaseOperationModel.STATUS_SUCCEEDED,
                        "image", FakeDockerDaemon.digestOf("fake/app:v1")));
                assertThat((String) op.get(ReleaseOperationModel.STEP_LOG))
                    .as("step 2: including the step log the terminal write appends to")
                    .contains("deployed");

                // 3. STATE, read back from the database: neither column the finish is NOT
                //    about was restated. Both in ONE assertion -- a whole-row save loses
                //    both, and a per-value assertion would only report whichever it
                //    reached first.
                assertThat(Map.of(
                        "site_fingerprint",
                        String.valueOf((Object) op.get(ReleaseOperationModel.SITE_FINGERPRINT)),
                        "spec_fingerprint",
                        String.valueOf((Object) op.get(ReleaseOperationModel.SPEC_FINGERPRINT))))
                    .as("step 3: every write made to the operation between its creation and"
                        + " its finish SURVIVED it")
                    .isEqualTo(Map.of("site_fingerprint", "rival-site-print",
                        "spec_fingerprint", "rival-spec-print"));
            } finally {
                SiteInstances.destroyFor(siteId);
            }
        });
    }

    private static int site(String slug) {
        Row site = Models.get(SiteModel.class).createEmptyRow();
        site.set(SiteModel.NAME, "Release contract " + slug);
        site.set(SiteModel.SLUG, slug);
        site.set(SiteModel.SITE_TYPE, "hohenheim:docker");
        site.set(SiteModel.SETTINGS, settingsFor("v1"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        Models.get(SiteModel.class).save(site);
        return site.get(SiteModel.ID);
    }

    private static Map<String, Object> settingsFor(String tag) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "fake/app");
        settings.put("tag", tag);
        settings.put("container_port", 8080);
        return settings;
    }

    /** A site-owned release row in a declared role, authored in the site's system scope. */
    private static int ownedRelease(int siteId, String name, String role, String tag) {
        int[] created = new int[1];
        inSiteScope(siteId, () -> {
            Row row = Models.get(InstanceModel.class).createEmptyRow();
            row.set(InstanceModel.NAME, name);
            row.set(InstanceModel.KIND, "hohenheim:site_container");
            row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of(
                "image", FakeDockerDaemon.digestOf("fake/app:" + tag),
                "container_port", 8080)));
            row.set(InstanceModel.RUNTIME_ROLE, role);
            Models.get(InstanceModel.class).save(row);
            created[0] = row.get(InstanceModel.ID);
        });
        return created[0];
    }

    private static void deployOwned(int siteId, int instanceId) {
        inSiteScope(siteId, () -> new InstanceService().deploy(instanceId));
    }

    /** The site tier's own attribution scope; owned release rows are unwritable outside it. */
    private static void inSiteScope(int siteId, Runnable work) {
        try {
            GeneratedRows.as(new GeneratedRows.Attribution(SiteInstances.SOURCE,
                SITE_MODEL, siteId), work::run);
        } catch (RuntimeException unchecked) {
            throw unchecked;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Row operationRow(int siteId, String status, Integer candidateId,
                                    Integer retiredId) {
        Row op = Models.get(ReleaseOperationModel.class).createEmptyRow();
        op.set(ReleaseOperationModel.KIND, ReleaseOperationModel.KIND_RELEASE);
        op.set(ReleaseOperationModel.FOR_MODEL, SITE_MODEL);
        op.set(ReleaseOperationModel.FOR_ID, siteId);
        op.set(ReleaseOperationModel.STATUS, status);
        op.set(ReleaseOperationModel.CANDIDATE_INSTANCE_ID, candidateId);
        op.set(ReleaseOperationModel.RETIRED_INSTANCE_ID, retiredId);
        op.set(ReleaseOperationModel.STARTED_AT, Instant.now());
        op.set(ReleaseOperationModel.STEP_LOG, "");
        Models.get(ReleaseOperationModel.class).save(op);
        return op;
    }

    private static Row servingOf(int siteId) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(SITE_MODEL))
            .where(InstanceModel.GENERATED_FOR_ID.eq(siteId))
            .where(InstanceModel.RUNTIME_ROLE.eq(InstanceModel.ROLE_SERVING))
            .where(InstanceModel.DELETED_AT.isNull())
            .orderBy(InstanceModel.ID, SortOrder.DESC)
            .first();
    }

    private static Map<String, Object> settingsOf(Row instance) {
        return SiteInstances.storedSettings(instance);
    }

    private static Row latestOp(int siteId) {
        return Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.FOR_MODEL.eq(SITE_MODEL))
            .where(ReleaseOperationModel.FOR_ID.eq(siteId))
            .orderBy(ReleaseOperationModel.ID, SortOrder.DESC)
            .first();
    }

    private static int opCount(int siteId) {
        return Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.FOR_MODEL.eq(SITE_MODEL))
            .where(ReleaseOperationModel.FOR_ID.eq(siteId))
            .all().size();
    }

    private static Row reload(Row op) {
        return Models.get(ReleaseOperationModel.class)
            .findById(op.get(ReleaseOperationModel.ID));
    }

    /** Bounded wait: the drain completes on a virtual thread, not inline. */
    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }
}
