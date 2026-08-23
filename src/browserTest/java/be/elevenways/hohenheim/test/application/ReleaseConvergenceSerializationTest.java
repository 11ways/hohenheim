package be.elevenways.hohenheim.test.application;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.ConvergenceLocks;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two convergences of ONE application cannot both flip: the second WAITS for the first
 * and then observes its outcome, and an application that somehow already carries two
 * serving releases is repaired rather than left running both forever.
 *
 * <p>This is the falsifiable half of the per-application convergence lock. The overlap is
 * FORCED, not hoped for: the fake daemon holds the first converge inside the start of its
 * candidate -- so the lock is provably held -- and the second converge is only released
 * once the JVM itself reports it blocked on that exact monitor. Without the lock the two
 * read the same serving release, both build and both flip, and the application ends with
 * two rows role {@code serving} of which only the newer is ever resolved again.
 *
 * <p>WHAT THIS CANNOT PROVE: anything about a SECOND controller. The lock is an
 * intra-process monitor, exactly like the preview lane's; two controllers are excluded by
 * the host lease and the fenced writes, and {@link ReleaseEngine#sweepDuplicateServing()}
 * -- the second journey here -- is the net under both.
 */
class ReleaseConvergenceSerializationTest {

    private static SqlDatasource datasource;
    private static FakeDockerDaemon daemon;
    private static Integer savedProbeTimeout;
    private static Integer savedProbeInterval;
    private static Integer savedDrain;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        daemon = new FakeDockerDaemon();
        daemon.install();
        savedProbeTimeout = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS);
        savedProbeInterval = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Releases.PROBE_INTERVAL_MS);
        savedDrain = HohenheimSettings.VALUES.getValue(HohenheimSettings.Releases.DRAIN_SECONDS);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, 5);
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
     * A webhook and a Deploy button hit the same application while the first is mid-build:
     * the second queues, and exactly one release ever serves.
     */
    @Test
    void aSecondConvergeWaitsForTheFirstAndNeverMintsARivalServingRelease() throws Exception {
        int localServerId = Db.supply(datasource, ServerModel::localServerId);
        int applicationId = Db.supply(datasource, () -> application("racer", "v1"));
        try {
            // 1. One release already serves; this is the row both racers will read.
            int firstId = Db.supply(datasource, () -> {
                ApplicationReleases.converge(applicationId, Map.of());
                return ApplicationReleases.ownedServing(applicationId).get(InstanceModel.ID);
            });
            long bookedWithOneRelease = Db.supply(datasource,
                () -> InstanceCapacity.bookedMbOn(localServerId));
            assertThat(bookedWithOneRelease)
                .as("step 1: the serving release booked capacity on its host")
                .isGreaterThan(0);

            // 2. Converge A is stalled INSIDE the daemon start of its candidate -- past
            //    the read of the serving release, holding the application's lock, with
            //    the flip still ahead of it. That is the whole window the defect lived in.
            Db.run(datasource, () -> setSettings(applicationId, "v2"));
            CountDownLatch aStalled = new CountDownLatch(1);
            CountDownLatch resumeA = new CountDownLatch(1);
            daemon.duringNextStart(() -> {
                aStalled.countDown();
                awaitLatch(resumeA);
            });
            AtomicReference<ApplicationReleases.Release> aResult = new AtomicReference<>();
            AtomicReference<Throwable> aFailure = new AtomicReference<>();
            Thread a = converger(applicationId, aResult, aFailure, "converge-a");
            a.start();
            assertThat(aStalled.await(120, TimeUnit.SECONDS))
                .as("step 2: converge A reached its stall point inside the candidate's start")
                .isTrue();

            // 3. Converge B starts while A is stalled, and A is resumed only once B is
            //    BLOCKED ON THE APPLICATION'S OWN MONITOR -- reported by the JVM, not
            //    inferred from a sleep. The `!isAlive` arm is what keeps the falsification
            //    honest: with the lock removed B simply runs to completion here instead,
            //    the overlap still happens, and the invariants below are what fail.
            AtomicReference<ApplicationReleases.Release> bResult = new AtomicReference<>();
            AtomicReference<Throwable> bFailure = new AtomicReference<>();
            Thread b = converger(applicationId, bResult, bFailure, "converge-b");
            b.start();
            Object lock = ConvergenceLocks.forApplication(applicationId);
            await("step 3: converge B either queued on the lock or ran past it",
                () -> blockedOn(b, lock) || !b.isAlive());
            boolean bQueuedOnTheLock = blockedOn(b, lock);

            // 4. Both finish. B waited (it did not coalesce away), so it converged for
            //    real -- onto the release A had just made serving.
            resumeA.countDown();
            a.join(TimeUnit.SECONDS.toMillis(120));
            b.join(TimeUnit.SECONDS.toMillis(120));
            assertThat(a.isAlive()).as("step 4: converge A finished").isFalse();
            assertThat(b.isAlive()).as("step 4: converge B finished").isFalse();
            assertThat(aFailure.get()).as("step 4: converge A did not fail").isNull();
            assertThat(bFailure.get()).as("step 4: converge B did not fail").isNull();

            Db.run(datasource, () -> {
                int servingId = ApplicationReleases.ownedServing(applicationId)
                    .get(InstanceModel.ID);
                // 5. THE INVARIANT: one serving release, and it is A's candidate. A second
                //    one would be resolved by nothing and reclaimed by nothing.
                assertThat(servingRolesOf(applicationId))
                    .as("step 5: exactly ONE release of the application serves")
                    .hasSize(1);
                assertThat(servingId)
                    .as("step 5: the release the flip produced, not the one both read")
                    .isNotEqualTo(firstId);
                assertThat(aResult.get().instanceId())
                    .as("step 5: which is the release converge A produced")
                    .isEqualTo(servingId);
                assertThat(bResult.get().instanceId())
                    .as("step 5: and converge B returned that same release rather than"
                        + " minting a rival for the source it was asked to deploy")
                    .isEqualTo(servingId);

                // 6. B recorded HONESTLY: it ran no second gated swap, because by the time
                //    it held the lock the source it wanted was already serving.
                assertThat(operationsOf(applicationId))
                    .as("step 6: two release operations -- the initial release and A's"
                        + " flip; a racing B would have recorded a third")
                    .hasSize(2);

                // 7. The loser of the flip -- the release BOTH read -- is retired, and the
                //    drain settles it: stopped, its observed port claim released. Nothing
                //    is stranded holding a port or a booking.
                await("step 7: A's flip completes after its drain window",
                    () -> ReleaseOperationModel.STATUS_SUCCEEDED.equals(
                        latestOp(applicationId).get(ReleaseOperationModel.STATUS)));
                Row previous = Models.get(InstanceModel.class).findById(firstId);
                assertThat(previous.get(InstanceModel.RUNTIME_ROLE))
                    .as("step 7: the previously serving release is retired")
                    .isEqualTo(InstanceModel.ROLE_RETIRED);
                assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, firstId))
                    .as("step 7: and holds no port claim once the drain stopped it")
                    .isEmpty();
                assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, servingId))
                    .as("step 7: while the serving release does hold one")
                    .isNotEmpty();
                assertThat(liveReleasesOf(applicationId))
                    .as("step 7: exactly two release rows live -- serving and the retained"
                        + " rollback target; a third would be the stranded row")
                    .hasSize(2);
                assertThat(InstanceCapacity.bookedMbOn(localServerId))
                    .as("step 7: and the host is booked for exactly those two releases")
                    .isEqualTo(bookedWithOneRelease + InstanceCapacity.footprintMbOf(
                        Models.get(InstanceModel.class).findById(servingId)));

                // 8. And the reason all of that held: B really did contend for the lock
                //    while A was mid-flight. Asserted last so a lock-less engine fails on
                //    the INVARIANT above rather than on the handshake.
                assertThat(bQueuedOnTheLock)
                    .as("step 8: converge B was blocked on the application's own"
                        + " convergence monitor, not merely slow")
                    .isTrue();
            });
        } finally {
            Db.run(datasource, () -> ApplicationReleases.destroyFor(applicationId));
        }
    }

    /**
     * The repair net: an application carrying two serving releases -- what any historical
     * race could already have written -- keeps the newest and reclaims the rest.
     */
    @Test
    void theSweeperReclaimsEveryStrandedSecondServingRelease() {
        Db.run(datasource, () -> {
            int localServerId = ServerModel.localServerId();
            int applicationId = application("stranded", "v1");
            try {
                ApplicationReleases.converge(applicationId, Map.of());
                int olderId = ApplicationReleases.ownedServing(applicationId)
                    .get(InstanceModel.ID);
                setSettings(applicationId, "v2");
                ApplicationReleases.converge(applicationId, Map.of());
                int newerId = ApplicationReleases.ownedServing(applicationId)
                    .get(InstanceModel.ID);
                await("step 0: the flip settled",
                    () -> ReleaseOperationModel.STATUS_SUCCEEDED.equals(
                        latestOp(applicationId).get(ReleaseOperationModel.STATUS)));

                // 1. Hand-write the corrupt state: the older release is serving again,
                //    beside the newer one. Nothing in the engine walks this shape --
                //    reclaimOlderRetired sees only retired rows, sweepOrphanCandidates
                //    only candidates -- so it would run forever.
                new InstanceService().assignRuntimeRole(olderId, InstanceModel.ROLE_SERVING);
                assertThat(servingRolesOf(applicationId))
                    .as("step 1: the application now has TWO serving releases")
                    .hasSize(2);
                long bookedCorrupt = InstanceCapacity.bookedMbOn(localServerId);
                int strandedFootprint = InstanceCapacity.footprintMbOf(
                    Models.get(InstanceModel.class).findById(olderId));

                // 2. The sweeper keeps ownedServing's own pick and reclaims the rest.
                ReleaseEngine.sweepDuplicateServing();
                assertThat(servingRolesOf(applicationId))
                    .as("step 2: exactly one serving release survives the sweep")
                    .hasSize(1);
                assertThat((Object) ApplicationReleases.ownedServing(applicationId)
                        .get(InstanceModel.ID))
                    .as("step 2: and it is the one the proxy already resolves")
                    .isEqualTo(newerId);

                // 3. RECLAIMED, not merely re-roled: the container is gone, the record is
                //    trashed, the port claim released and the booking handed back.
                Row stranded = Models.get(InstanceModel.class).findById(olderId);
                assertThat(stranded.get(InstanceModel.DELETED_AT))
                    .as("step 3: the stranded release's record is trashed").isNotNull();
                assertThat(daemon.exists(FakeDockerDaemon.handleOf(olderId)))
                    .as("step 3: its container is gone from the daemon").isFalse();
                assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, olderId))
                    .as("step 3: and it holds no port claim").isEmpty();
                assertThat(InstanceCapacity.bookedMbOn(localServerId))
                    .as("step 3: its booked capacity went back to the host")
                    .isEqualTo(bookedCorrupt - strandedFootprint);

                // 4. The survivor is UNTOUCHED: still serving, still running, still holding
                //    the port the proxy forwards to.
                assertThat(Models.get(InstanceModel.class).findById(newerId)
                        .get(InstanceModel.RUNTIME_ROLE))
                    .as("step 4: the surviving release still serves")
                    .isEqualTo(InstanceModel.ROLE_SERVING);
                assertThat(daemon.isRunning(FakeDockerDaemon.handleOf(newerId)))
                    .as("step 4: and its container still runs").isTrue();
                assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, newerId))
                    .as("step 4: holding its port claim").isNotEmpty();
            } finally {
                ApplicationReleases.destroyFor(applicationId);
            }
        });
    }

    // -- fixtures --------------------------------------------------------------

    private static Thread converger(int applicationId,
                                    AtomicReference<ApplicationReleases.Release> result,
                                    AtomicReference<Throwable> failure, String name) {
        return new Thread(() -> {
            try {
                Db.run(datasource, () ->
                    result.set(ApplicationReleases.converge(applicationId, Map.of())));
            } catch (Throwable refused) {
                failure.set(refused);
            }
        }, name);
    }

    /**
     * Whether a thread is blocked entering EXACTLY this monitor.
     *
     * AIDEV-NOTE: the JVM's own answer, so the handshake needs no sleep and no guess. A
     * plain {@code Thread.State.BLOCKED} would also be true of a thread parked on the
     * datasource or the daemon transport, which is precisely the false green that would
     * make the falsification of this journey unreliable.
     */
    private static boolean blockedOn(Thread thread, Object monitor) {
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId());
        if (info == null) {
            return false;
        }
        LockInfo lock = info.getLockInfo();
        return Thread.State.BLOCKED.equals(info.getThreadState()) && lock != null
            && lock.getIdentityHashCode() == System.identityHashCode(monitor);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the stalled converge was never resumed");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** An authored application record with an image source the fake daemon answers for. */
    private static int application(String name, String tag) {
        Row application = Models.get(InstanceModel.class).createEmptyRow();
        application.set(InstanceModel.NAME, name);
        application.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        application.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        application.set(InstanceModel.SETTINGS, settingsFor(tag));
        Models.get(InstanceModel.class).save(application);
        return application.get(InstanceModel.ID);
    }

    private static void setSettings(int applicationId, String tag) {
        Row application = Models.get(InstanceModel.class).findById(applicationId);
        application.set(InstanceModel.SETTINGS, settingsFor(tag));
        Models.get(InstanceModel.class).save(application);
    }

    private static Map<String, Object> settingsFor(String tag) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "fake/app");
        settings.put("tag", tag);
        settings.put("container_port", 8080);
        return settings;
    }

    private static List<Row> releasesWithRole(int applicationId, String role) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(InstanceModel.GENERATED_FOR_ID.eq(applicationId))
            .where(InstanceModel.RUNTIME_ROLE.eq(role))
            .where(InstanceModel.DELETED_AT.isNull())
            .all();
    }

    private static List<Row> servingRolesOf(int applicationId) {
        return releasesWithRole(applicationId, InstanceModel.ROLE_SERVING);
    }

    private static List<Row> liveReleasesOf(int applicationId) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(InstanceModel.GENERATED_FOR_ID.eq(applicationId))
            .where(InstanceModel.DELETED_AT.isNull())
            .all();
    }

    private static List<Row> operationsOf(int applicationId) {
        return Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ReleaseOperationModel.FOR_ID.eq(applicationId))
            .all();
    }

    private static Row latestOp(int applicationId) {
        return Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ReleaseOperationModel.FOR_ID.eq(applicationId))
            .orderBy(ReleaseOperationModel.ID, SortOrder.DESC)
            .first();
    }

    /** Bounded wait: the drain and the rival thread both settle off this thread. */
    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }
}
