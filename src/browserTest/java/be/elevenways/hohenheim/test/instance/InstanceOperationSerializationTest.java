package be.elevenways.hohenheim.test.instance;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.database.DatabaseEngines;
import be.elevenways.hohenheim.server.database.DatabaseWrites;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.test.database.EngineHandles;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.zenit.test.support.DatasourceProbe;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.Nullable;
import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.server.cms.InstanceAttention;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.instance.UnfinishedDestroys;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.EngineHost;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.stack.StackInstances;
import be.elevenways.protoblast.common.registry.Identifier;
import java.io.IOException;
import java.io.UncheckedIOException;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.util.BlastLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import be.elevenways.zenit.common.orm.lease.Lease;
import be.elevenways.zenit.common.orm.lease.Leases;
import be.elevenways.protoblast.testsupport.thread.Racers;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceStatusReconciler;
import be.elevenways.hohenheim.server.instance.InstanceSnapshots;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * One instance record, one operation at a time: a second deploy or stop is REFUSED while a
 * deploy runs, and a protected capture/restore window always ends, even when the work in
 * it fails with a named refusal instead of an IO error.
 *
 * AIDEV-NOTE: the overlap is FORCED, never hoped for. The rival operations run on another
 * thread from inside the first deploy's own outcome-write seam, so the first operation
 * provably holds the record when they arrive; the named-refusal windows use the fake
 * daemon's DURING_SNAPSHOT / DURING_RESTORE hooks, which throw on the caller's thread in
 * the middle of the window.
 */
class InstanceOperationSerializationTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.standalone();

    private static SqlDatasource datasource;
    private static int hostId;

    /**
     * The journeys' second operations, opened per test: a Racers is single-use after close().
     *
     * AIDEV-NOTE: Racers runs each on a platform thread: an operation waits for SQLite's write lock in the driver's
     * native code, which would pin a virtual thread's carrier.
     */
    private Racers operations;

    @BeforeAll
    static void setUp() throws Exception {
        BackupLaneFixture fixture = BackupLaneFixture.install(APP.database());
        datasource = fixture.datasource;
        hostId = fixture.hostId;
    }

    @AfterAll
    static void tearDown() {
        FakeNativeDaemons.DURING_SNAPSHOT.set(null);
        FakeNativeDaemons.DURING_RESTORE.set(null);
        BackupLaneFixture.uninstall();
    }

    @BeforeEach
    void openOperations() {
        this.operations = Racers.named("operations on one instance record");
    }

    @AfterEach
    void stopOperations() {
        this.operations.close();
    }

    @Test
    void aSecondOperationIsRefusedWhileTheFirstHoldsTheRecord() {
        Db.run(datasource, () -> {
            int id = BackupLaneFixture.instanceRecord("serial-overlap", hostId);
            AtomicReference<Throwable> rivalDeploy = new AtomicReference<>();
            AtomicReference<Throwable> rivalStop = new AtomicReference<>();
            boolean[] fired = {false};

            // 1. A deploy runs, and between its daemon work and its outcome write two
            //    rival operations arrive from another thread: a second deploy (the double
            //    click, the webhook beside the button) and a stop.
            InstanceService driving = new InstanceService(HostLeases.production(), () -> {
                if (fired[0]) {
                    return;
                }
                fired[0] = true;
                this.operations.start("the rival deploy and stop", () -> {
                    Db.run(datasource, () -> {
                        rivalDeploy.set(catchThrowable(() -> new InstanceService().deploy(id)));
                        rivalStop.set(catchThrowable(() -> new InstanceService().stop(id)));
                    });
                    return null;
                }).await(Duration.ofSeconds(60)).value();
            });
            driving.deploy(id);

            assertThat(rivalDeploy.get())
                .as("step 1: the second deploy is refused, not run beside the first")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key())
                            .as("step 1: by the named in-progress refusal")
                            .isEqualTo("instance_operation_in_progress")));
            assertThat(rivalStop.get())
                .as("step 1: and so is a stop that would pull the workload from under it")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all()).anySatisfy(violation ->
                        assertThat(violation.message().key())
                            .isEqualTo("instance_operation_in_progress")));

            // 2. The first deploy's outcome is the one that stands, with ONE workload.
            assertThat(statusOf(id))
                .as("step 2: the deploy that held the record stamped running")
                .isEqualTo(InstanceModel.STATUS_RUNNING);
            assertThat(FakeNativeDaemons.daemonOf(hostId).get(FakeNativeDaemons.handleOf(id)).running)
                .as("step 2: and its workload runs")
                .isTrue();

            // 3. The record is FREE once the operation ended: the refused stop now runs.
            new InstanceService().stop(id);
            assertThat(statusOf(id))
                .as("step 3: a stop after the deploy finished is accepted")
                .isEqualTo(InstanceModel.STATUS_STOPPED);

            // 4. Re-entrant on one thread: a restart is a stop and a deploy under ONE hold,
            //    and neither half refuses the other.
            new InstanceService().restart(id);
            assertThat(statusOf(id))
                .as("step 4: the restart's own two halves never collide")
                .isEqualTo(InstanceModel.STATUS_RUNNING);
        });
    }

    @Test
    void aNamedRefusalInsideAProtectedWindowStillEndsTheWindow() {
        Db.run(datasource, () -> {
            int id = BackupLaneFixture.instanceRecord("window-refusal", hostId);
            new InstanceService().deploy(id);
            InstanceSnapshots snapshots = new InstanceSnapshots();

            // 1. A capture whose daemon step answers with a NAMED refusal, not an IO error:
            //    the net that caught only IOException left the record `capturing`.
            FakeNativeDaemons.DURING_SNAPSHOT.set(() -> {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("snapshots_unsupported")
                    .withArg("kind", "fake"));
            });
            Throwable captureRefused = catchThrowable(() -> snapshots.create(id, "refused"));
            assertThat(captureRefused)
                .as("step 1: the capture fails with the refusal the daemon step raised")
                .isInstanceOf(Violations.class);
            assertThat(statusOf(id))
                .as("step 1: and the window ENDED -- the capture changed nothing, so the"
                    + " record is handed back as it was, never left capturing")
                .isEqualTo(InstanceModel.STATUS_RUNNING);

            // 2. Nothing protected is left behind: a power verb is accepted right away.
            new InstanceService().stop(id);
            assertThat(statusOf(id))
                .as("step 2: the record is operable again")
                .isEqualTo(InstanceModel.STATUS_STOPPED);

            // 3. A restore past its point of no return, refused by name in the middle: the
            //    payload may be half-written, so the record says ERROR -- never `restoring`
            //    with every verb refusing it until the next boot.
            new InstanceService().deploy(id);
            int snapshotId = snapshots.create(id, "good");
            FakeNativeDaemons.DURING_RESTORE.set(() -> {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("snapshots_unsupported")
                    .withArg("kind", "fake"));
            });
            Throwable restoreRefused = catchThrowable(() -> snapshots.restore(snapshotId));
            assertThat(restoreRefused)
                .as("step 3: the restore fails with the named refusal")
                .isInstanceOf(Violations.class);
            assertThat(statusOf(id))
                .as("step 3: and the record is held in error for an operator, not restoring")
                .isEqualTo(InstanceModel.STATUS_ERROR);

            // 4. Falsified: the same restore without the refusal completes and restarts.
            Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(id))
                .assign(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING)
                .updateAll();
            FakeNativeDaemons.daemonOf(hostId).get(FakeNativeDaemons.handleOf(id)).running = true;
            snapshots.restore(snapshotId);
            assertThat(statusOf(id))
                .as("step 4: an unrefused restore ends running, so step 3 was the refusal")
                .isEqualTo(InstanceModel.STATUS_RUNNING);
        });
    }

    private static String statusOf(int id) {
        return Models.get(InstanceModel.class).findById(id).get(InstanceModel.STATUS);
    }

    /**
     * A destroy's end is the record's outcome: the record reads destroyed only once the destroy let go of its claim,
     * so a client that sees it destroyed and removes its data at once is never refused by the destroy it just read
     * about (InstanceConsoleGenerationTest.aReadinessFlipReadsRunningOnlyOnceTheWatchLetGoOfTheRecord's shape). While
     * it holds the record, the record reads destroying: the durable intent a rival's claim would finish.
     */
    @Test
    void aDestroyReadsDestroyedOnlyOnceItLetGoOfTheRecord() {
        int[] record = new int[1];
        Db.run(datasource, () -> {
            record[0] = BackupLaneFixture.instanceRecord("destroy-release", hostId);
            new InstanceService().deploy(record[0]);
        });
        int id = record[0];
        String handle = FakeNativeDaemons.handleOf(id);

        // 1. The destroy logs its end while it still holds the record's claim; what the record reads at that moment
        //    is what a client polling it could act on.
        List<String> readAtEnd = new CopyOnWriteArrayList<>();
        BlastLog.LogSink previous = BlastLog.getLogSink();
        try {
            BlastLog.setLogSink(args -> {
                if (Arrays.toString(args).contains("INSTANCE: destroyed, " + handle)) {
                    readAtEnd.add(stateOf(id));
                }
                if (previous != null) {
                    previous.log(args);
                }
            });
            Racers.Running<Object> destroying = this.operations.start("the destroy", () -> {
                Db.run(datasource, () -> new InstanceService().destroy(id));
                return null;
            });

            // 2. A client waits for the record to read destroyed and removes its data at once: the record is free.
            Db.run(datasource, () -> {
                Poll.until("step 2: the record reads destroyed", Duration.ofSeconds(30),
                    () -> stateOf(id).startsWith("trashed"));
                assertThatCode(() -> new InstanceService().destroyWithData(id))
                    .as("step 2: the delete-with-data right after the destroy is admitted")
                    .doesNotThrowAnyException();
            });
            destroying.await(Duration.ofSeconds(60)).value();
        } finally {
            BlastLog.setLogSink(previous);
        }
        assertThat(readAtEnd).as("step 1: while the destroy held the record it read neither destroyed nor stopped,"
                + " but being destroyed")
            .containsExactly("live " + InstanceModel.STATUS_DESTROYING);
        Db.run(datasource, () -> assertThat(stateOf(id)).as("step 2: the destroy's end stands")
            .isEqualTo("trashed " + InstanceModel.STATUS_STOPPED));
    }

    /** Whether the record is trashed, and its status, read with the trash included. */
    private static String stateOf(int id) {
        Row row = Models.get(InstanceModel.class).find().withTrashed().where(InstanceModel.ID.eq(id)).first();
        return (row.get(InstanceModel.DELETED_AT) == null ? "live " : "trashed ") + row.get(InstanceModel.STATUS);
    }

    /**
     * A rival controller that claims the record between the destroy's release and the publication of its end (which
     * that newer claim drops, ClaimedRowsHoldJourneyTest's "newer claim elsewhere") meets a record that already reads
     * destroying, and FINISHES the destroy instead of running it: the record ends trashed and stopped, never live with
     * its cleanup done.
     */
    @Test
    void aRivalClaimBetweenReleaseAndPublicationFinishesTheDestroy() {
        int[] record = new int[1];
        Db.run(datasource, () -> {
            record[0] = BackupLaneFixture.instanceRecord("destroy-rival", hostId);
            new InstanceService().deploy(record[0]);
        });
        int id = record[0];
        Leases rivalCoordinator = Leases.independent(datasource);
        HostLeases rival = new HostLeases(d -> rivalCoordinator, Leases.DEFAULT_TTL);
        AtomicReference<String> readByRival = new AtomicReference<>();
        AtomicReference<Throwable> rivalClaim = new AtomicReference<>();
        AtomicBoolean rivalRan = new AtomicBoolean();

        // 1. The destroy lets go of the record, and before its end is written a rival controller claims the record to
        //    run something on it.
        Db.run(datasource, () -> new InstanceService(HostLeases.production(), () -> {}, () -> {
            if (readByRival.get() != null) {
                return;
            }
            readByRival.set(stateOf(id));
            rivalClaim.set(catchThrowable(() -> InstanceOperationLock.of(rival).exclusive(id,
                InstanceOperationLock.Contention.REFUSE, () -> rivalRan.set(true))));
        }).destroy(id));
        assertThat(readByRival.get()).as("step 1: the rival met a record whose destroy was under way")
            .isEqualTo("live " + InstanceModel.STATUS_DESTROYING);

        // 2. The rival never ran the record: its claim finished the destroy, so it found no record left to run.
        assertThat(rivalRan.get()).as("step 2: the rival's operation never ran").isFalse();
        assertThat(rivalClaim.get()).as("step 2: it was refused as for a missing record")
            .isInstanceOfSatisfying(Violations.class, refused -> assertThat(refused.all()).anySatisfy(violation ->
                assertThat(violation.message().key()).isEqualTo("instance_not_found")));

        // 3. The record ends destroyed (trashed and stopped) and its workload is gone, though the destroy's own end
        //    was dropped by the newer claim.
        Db.run(datasource, () -> assertThat(stateOf(id)).as("step 3: trashed and stopped, never live")
            .isEqualTo("trashed " + InstanceModel.STATUS_STOPPED));
        assertThat(FakeNativeDaemons.daemonOf(hostId)).as("step 3: the workload is gone")
            .doesNotContainKey(FakeNativeDaemons.handleOf(id));
    }

    /**
     * A record left destroying (its cleanup done, the end its destroy staged dropped by a newer claim that never came
     * back) is finished by the status reconciler and by the boot settle, which never observe or run it.
     */
    @Test
    void theReconcilerAndTheBootSettleFinishARecordLeftDestroying() {
        int reconciled = leftDestroying("destroy-left-reconciled");
        int settled = leftDestroying("destroy-left-boot");
        Db.run(datasource, () -> {
            // 1. Both read destroying, their workloads already gone.
            assertThat(List.of(stateOf(reconciled), stateOf(settled))).as("step 1: left destroying")
                .containsOnly("live " + InstanceModel.STATUS_DESTROYING);
            assertThat(FakeNativeDaemons.daemonOf(hostId)).as("step 1: the cleanup was done")
                .doesNotContainKey(FakeNativeDaemons.handleOf(reconciled))
                .doesNotContainKey(FakeNativeDaemons.handleOf(settled));

            // 2. The reconciler finishes the first: trashed and stopped.
            assertThat(new InstanceStatusReconciler().reconcile(reconciled).verdict())
                .as("step 2: the reconciler finished the destroy")
                .isEqualTo(InstanceStatusReconciler.Verdict.FINISHED);
            assertThat(stateOf(reconciled)).as("step 2: trashed and stopped")
                .isEqualTo("trashed " + InstanceModel.STATUS_STOPPED);

            // 3. The boot settle finishes the second.
            InstanceService.recoverInterrupted();
            assertThat(stateOf(settled)).as("step 3: the boot settle finished it")
                .isEqualTo("trashed " + InstanceModel.STATUS_STOPPED);

            // 4. Nothing is left for the next sweep.
            assertThat(new InstanceStatusReconciler().reconcile(reconciled).verdict())
                .as("step 4: a finished record is gone from the sweep")
                .isEqualTo(InstanceStatusReconciler.Verdict.SKIPPED);
        });
    }

    /**
     * A record an owning tier generated (a database's, an engine's, a stack service's) and left destroying is finished
     * by the UNSCOPED reconciler and boot settle exactly like an unowned one: the finish trashes it inside the
     * removal-only scope instead of meeting the ownership guard and burning its retry budget. That guard still refuses
     * an unscoped edit and an unscoped delete of an owned record.
     */
    @Test
    void theUnscopedSweepAndBootFinishOwnedRecordsLeftDestroyingWhileTheGuardStillRefusesEdits() {
        // The finish reads the attribution alone, so the owner ids need no owner rows.
        int databaseSwept = leftDestroying("destroy-owned-db-swept");
        int engineSwept = leftDestroying("destroy-owned-engine-swept");
        int serviceSwept = leftDestroying("destroy-owned-service-swept");
        int databaseBooted = leftDestroying("destroy-owned-db-booted");
        int engineBooted = leftDestroying("destroy-owned-engine-booted");
        Db.run(datasource, () -> {
            // 1. Each is owned and left destroying, its cleanup done.
            attributeTo(databaseSwept, DatabaseInstances.SOURCE, DatabaseModel.MODEL_ID, 9_100_000 + databaseSwept);
            attributeTo(engineSwept, DatabaseEngines.SOURCE, DatabaseEngineModel.MODEL_ID, 9_100_000 + engineSwept);
            attributeTo(serviceSwept, StackInstances.SOURCE, StackServiceModel.MODEL_ID, 9_100_000 + serviceSwept);
            attributeTo(databaseBooted, DatabaseInstances.SOURCE, DatabaseModel.MODEL_ID, 9_100_000 + databaseBooted);
            attributeTo(engineBooted, DatabaseEngines.SOURCE, DatabaseEngineModel.MODEL_ID, 9_100_000 + engineBooted);
            List<Integer> owned = List.of(databaseSwept, engineSwept, serviceSwept, databaseBooted, engineBooted);
            assertThat(owned.stream().map(InstanceOperationSerializationTest::stateOf).toList())
                .as("step 1: left destroying").containsOnly("live " + InstanceModel.STATUS_DESTROYING);

            // 2. The unscoped reconciler finishes the database's, the engine's and the service's.
            for (int id : List.of(databaseSwept, engineSwept, serviceSwept)) {
                assertThat(new InstanceStatusReconciler().reconcile(id).verdict())
                    .as("step 2: the reconciler finished owned record %s", id)
                    .isEqualTo(InstanceStatusReconciler.Verdict.FINISHED);
                assertThat(stateOf(id)).as("step 2: owned record %s is trashed and stopped", id)
                    .isEqualTo("trashed " + InstanceModel.STATUS_STOPPED);
            }

            // 3. The boot settle finishes the other two.
            InstanceService.recoverInterrupted();
            assertThat(List.of(stateOf(databaseBooted), stateOf(engineBooted))).as("step 3: the boot settle finished both")
                .containsOnly("trashed " + InstanceModel.STATUS_STOPPED);
            assertThat(owned.stream().map(id -> InstanceModel.stoppageCauseOf(Models.get(InstanceModel.class).find()
                    .withTrashed().where(InstanceModel.ID.eq(id)).first())).toList())
                .as("step 3: no finish was counted as unfinished").containsOnlyNulls();

            // 4. An unscoped edit and an unscoped delete of an owned record are still refused by the ownership guard.
            int live = BackupLaneFixture.instanceRecord("owned-guarded", hostId);
            attributeTo(live, DatabaseInstances.SOURCE, DatabaseModel.MODEL_ID, 9_100_000 + live);
            Row edited = Models.get(InstanceModel.class).findById(live);
            edited.set(InstanceModel.NAME, "owned-guarded-renamed");
            assertGeneratedReadonly(() -> Models.get(InstanceModel.class).save(edited), "step 4: the edit is refused");
            assertGeneratedReadonly(() -> Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(live))
                .delete(), "step 4: the delete is refused");
            assertThat(stateOf(live)).as("step 4: the owned record is untouched")
                .startsWith("live ");
        });
    }

    /**
     * A fresh destroy of an owned record outside its owner's scope is refused by the ownership guard's own rule BEFORE
     * any runtime work, so the record never stays live with its workload gone; inside the owner's scope it destroys.
     */
    @Test
    void anUnscopedFreshDestroyOfAnOwnedRecordIsRefusedBeforeItsWorkloadIsTouched() {
        Db.run(datasource, () -> {
            int id = BackupLaneFixture.instanceRecord("destroy-owned-unscoped", hostId);
            new InstanceService().deploy(id);
            int ownerId = 9_100_000 + id;
            attributeTo(id, DatabaseInstances.SOURCE, DatabaseModel.MODEL_ID, ownerId);
            String handle = FakeNativeDaemons.handleOf(id);

            // 1. Unscoped, the destroy is refused by the ownership code and never asks the runtime for anything.
            assertGeneratedReadonly(() -> new InstanceService().destroy(id), "step 1: the unscoped destroy is refused");
            assertThat(FakeNativeDaemons.DESTROY_CALLS.getOrDefault(handle, 0))
                .as("step 1: no runtime destroy was made").isZero();
            assertThat(FakeNativeDaemons.daemonOf(hostId)).as("step 1: the workload still exists").containsKey(handle);
            assertThat(stateOf(id)).as("step 1: the record stays live").startsWith("live ");

            // 2. Inside the owner's scope the same destroy tears the workload down and trashes the record.
            OwnedInstances.inScopeUnchecked(DatabaseInstances.SOURCE, DatabaseModel.MODEL_ID, ownerId,
                () -> new InstanceService().destroy(id));
            assertThat(FakeNativeDaemons.DESTROY_CALLS.get(handle)).as("step 2: one runtime destroy").isEqualTo(1);
            assertThat(FakeNativeDaemons.daemonOf(hostId)).as("step 2: the workload is gone").doesNotContainKey(handle);
            assertThat(stateOf(id)).as("step 2: trashed and stopped").isEqualTo("trashed " + InstanceModel.STATUS_STOPPED);
        });
    }

    private static void assertGeneratedReadonly(Runnable call, String step) {
        assertThatThrownBy(call::run).as(step)
            .isInstanceOfSatisfying(Violations.class, refused -> assertThat(refused.all())
                .anySatisfy(violation ->
                    assertThat(violation.message().key()).isEqualTo("instance_generated_readonly")));
    }

    /**
     * A destroy whose end keeps failing (a write hook of the trash throws) is retried only as its backoff allows and,
     * after the bound, no more: the record says why (its report retried by a later claim when the bound was reached
     * inside a transaction), the dashboard names it, every other operation and every background destroy is refused by
     * name instead of meeting the raw failure, and only the operator's requested Delete retries it, finishing the
     * destroy once the end can be written.
     */
    @Test
    void anUnfinishableDestroyIsRetriedWithBackoffThenHeldWithItsReason() {
        int id = leftDestroying("destroy-unfinishable");
        AtomicBoolean failing = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        InstanceService failingEnds = failingEnds(failing, attempts);
        InstanceStatusReconciler sweep = new InstanceStatusReconciler(failingEnds);
        Duration offset = Now.offset();
        try {
            Db.run(datasource, () -> {
                // 1. The first sweep tries the end once; it fails, and the record stays destroying.
                assertThat(sweep.reconcile(id).verdict()).as("step 1: the failed end finished nothing")
                    .isEqualTo(InstanceStatusReconciler.Verdict.SKIPPED);
                assertThat(attempts.get()).as("step 1: one attempt").isEqualTo(1);
                assertThat(stateOf(id)).as("step 1: still destroying")
                    .isEqualTo("live " + InstanceModel.STATUS_DESTROYING);

                // 2. Each next attempt waits its backoff: a sweep before it is due tries nothing, one after it does.
                //    The last one runs inside a transaction, which the failure must roll back.
                Duration waited = Duration.ZERO;
                for (int failed = 1; failed < UnfinishedDestroys.AUTOMATIC_ATTEMPTS; failed++) {
                    waited = waited.plus(UnfinishedDestroys.BACKOFF.delayAfter(failed));
                    Now.setOffset(offset.plus(waited).minusSeconds(30));
                    sweep.reconcile(id);
                    assertThat(attempts.get()).as("step 2: no attempt before the wait after failure %s", failed)
                        .isEqualTo(failed);
                    Now.setOffset(offset.plus(waited));
                    if (failed + 1 < UnfinishedDestroys.AUTOMATIC_ATTEMPTS) {
                        sweep.reconcile(id);
                    } else {
                        // The end fails AFTER it wrote the stopped status (the seam runs where the trash's hooks
                        // do), and the caller swallows the failure inside its callback: the transaction must still
                        // roll back, never commit stopped-without-trash.
                        AtomicReference<Throwable> inTransaction = new AtomicReference<>();
                        Throwable commit = catchThrowable(() -> datasource.withTransaction(tx ->
                            inTransaction.set(catchThrowable(() -> sweep.reconcile(id)))));
                        assertThat(inTransaction.get()).as("step 2: inside a transaction the failure is thrown, named")
                            .isInstanceOf(Violations.class);
                        assertThat(commit).as("step 2: the swallowed failure still vetoed the commit").isNotNull();
                        assertThat(stateOf(id)).as("step 2: rolled back, never committed stopped")
                            .isEqualTo("live " + InstanceModel.STATUS_DESTROYING);
                    }
                    assertThat(attempts.get()).as("step 2: an attempt once the wait after failure %s passed", failed)
                        .isEqualTo(failed + 1);
                }
                assertThat(InstanceModel.stoppageCauseOf(Models.get(InstanceModel.class).findById(id)))
                    .as("step 2: the bound was reached inside a transaction, so its report is still owed").isNull();

                // 3. After the bound neither the sweep nor the boot tries the end again; the next sweep writes the
                //    owed report, and the record says why.
                Now.setOffset(offset.plus(waited).plusDays(1));
                sweep.reconcile(id);
                InstanceService.recoverInterrupted();
                assertThat(attempts.get()).as("step 3: no automatic attempt past the bound")
                    .isEqualTo(UnfinishedDestroys.AUTOMATIC_ATTEMPTS);
                assertThat(stateOf(id)).as("step 3: still destroying, never trashed by a seamless boot finish")
                    .isEqualTo("live " + InstanceModel.STATUS_DESTROYING);
                Row held = Models.get(InstanceModel.class).findById(id);
                assertThat(InstanceModel.stoppageCauseOf(held)).as("step 3: the record says its destroy is unfinished")
                    .isEqualTo(HohenheimActivityAction.DESTROY_UNFINISHED);
                assertThat((String) held.get(InstanceModel.STOPPAGE_DETAIL)).as("step 3: and why")
                    .contains("the quota release hook failed");

                // 4. The dashboard names it, pointing at the record its Delete is on.
                List<AttentionItem> items = new ArrayList<>();
                InstanceAttention.unfinishedDestroys(items);
                assertThat(items).as("step 4: one attention item for the record").filteredOn(item ->
                        AttentionSubject.instance(id).equals(item.about()))
                    .singleElement().satisfies(item -> {
                        assertThat(item.severity()).isEqualTo(AttentionSeverity.ERROR);
                        assertThat(item.title().key()).isEqualTo("instance_unfinished_destroy");
                        assertThat(item.action().key()).isEqualTo("act_delete_again");
                        assertThat(item.target()).isNotNull();
                    });

                // 5. Another operation, and a background destroy, are refused by name with that reason.
                assertThatThrownBy(() -> new InstanceService().stop(id)).as("step 5: a stop is refused by name")
                    .isInstanceOfSatisfying(Violations.class, refused -> assertThat(refused.all())
                        .anySatisfy(violation -> {
                            assertThat(violation.message().key()).isEqualTo("instance_destroy_unfinished");
                            assertThat(String.valueOf(violation.message().args().get("reason")))
                                .contains("the quota release hook failed");
                        }));
                assertUnfinished(() -> failingEnds.destroy(id), "step 5: a background destroy keeps the budget");
                assertThat(attempts.get()).as("step 5: neither tried the end")
                    .isEqualTo(UnfinishedDestroys.AUTOMATIC_ATTEMPTS);

                // 6. The operator's requested Delete retries it at once; while the end still fails, it is refused too.
                assertUnfinished(() -> failingEnds.destroy(id, UnfinishedDestroys.Policy.REQUESTED),
                    "step 6: the requested Delete is refused by name");
                assertThat(attempts.get()).as("step 6: the Delete tried the end")
                    .isEqualTo(UnfinishedDestroys.AUTOMATIC_ATTEMPTS + 1);

                // 7. A requested Delete that succeeds inside a transaction which then rolls back keeps the budget:
                //    the record is still held, so a sweep does not finish it even now that the end can be written.
                failing.set(false);
                assertThat(catchThrowable(() -> datasource.withTransaction(tx -> {
                    failingEnds.destroy(id, UnfinishedDestroys.Policy.REQUESTED);
                    throw new IllegalStateException("the caller rolls back");
                }))).as("step 7: the caller's transaction rolled back").hasMessageContaining("the caller rolls back");
                assertThat(stateOf(id)).as("step 7: the rollback restored the unfinished destroy")
                    .isEqualTo("live " + InstanceModel.STATUS_DESTROYING);
                sweep.reconcile(id);
                assertThat(stateOf(id)).as("step 7: still held, so no sweep finished it")
                    .isEqualTo("live " + InstanceModel.STATUS_DESTROYING);

                // 8. Once the end can be written, the requested Delete finishes the destroy.
                failingEnds.destroy(id, UnfinishedDestroys.Policy.REQUESTED);
                assertThat(stateOf(id)).as("step 8: trashed and stopped")
                    .isEqualTo("trashed " + InstanceModel.STATUS_STOPPED);
            });
        } finally {
            Now.setOffset(offset);
        }
    }

    /**
     * A destroy whose OWN staged end fails as it is published (not one a newer claim dropped) is refused by name and
     * counted once; a later claim, inside a transaction or outside one, never replays that failed end outside the
     * budget, and the sweep finishes the destroy once it is due and the end can be written.
     */
    @Test
    void aFailedStagedEndIsCountedOnceAndNeverReplayedByALaterClaim() {
        int[] record = new int[1];
        Db.run(datasource, () -> {
            record[0] = BackupLaneFixture.instanceRecord("destroy-staged-failure", hostId);
            new InstanceService().deploy(record[0]);
        });
        int id = record[0];
        AtomicBoolean failing = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        InstanceService failingEnds = failingEnds(failing, attempts);
        Duration offset = Now.offset();
        try {
            Db.run(datasource, () -> {
                // 1. The destroy's end fails as it is published: the destroy is refused by name, never the raw failure.
                assertUnfinished(() -> failingEnds.destroy(id), "step 1: the destroy is refused by name");
                assertThat(attempts.get()).as("step 1: one attempt").isEqualTo(1);
                assertThat(stateOf(id)).as("step 1: left destroying, its cleanup done")
                    .isEqualTo("live " + InstanceModel.STATUS_DESTROYING);

                // 2. A later claim inside a transaction meets no replay of the failed end: refused as not due yet.
                assertUnfinished(() -> datasource.withTransaction(tx -> new InstanceService().stop(id)),
                    "step 2: a claim inside a transaction is refused by name");
                assertThat(attempts.get()).as("step 2: the failed end was not replayed").isEqualTo(1);

                // 3. Nor outside one: a sweep before the backoff tries nothing.
                InstanceStatusReconciler sweep = new InstanceStatusReconciler(failingEnds);
                sweep.reconcile(id);
                assertThat(attempts.get()).as("step 3: still one attempt").isEqualTo(1);
                assertThat(stateOf(id)).as("step 3: still destroying")
                    .isEqualTo("live " + InstanceModel.STATUS_DESTROYING);

                // 4. Once due, with the end writable, the sweep finishes the destroy.
                failing.set(false);
                Now.setOffset(offset.plus(UnfinishedDestroys.BACKOFF.delayAfter(1)));
                assertThat(sweep.reconcile(id).verdict()).as("step 4: the sweep finished it")
                    .isEqualTo(InstanceStatusReconciler.Verdict.FINISHED);
                assertThat(stateOf(id)).as("step 4: trashed and stopped")
                    .isEqualTo("trashed " + InstanceModel.STATUS_STOPPED);
            });
        } finally {
            Now.setOffset(offset);
        }
    }

    /**
     * A claim that let go of a record's failed ends inside a transaction forgets only the failures it saw: its
     * after-commit hook, running late, never erases a destroy that failed its end in between, which keeps its backoff.
     */
    @Test
    void aLateAfterCommitHookNeverForgetsANewerFailedDestroy() {
        int[] record = new int[1];
        Db.run(datasource, () -> {
            record[0] = BackupLaneFixture.instanceRecord("destroy-late-forget", hostId);
            new InstanceService().deploy(record[0]);
        });
        int id = record[0];
        AtomicInteger attempts = new AtomicInteger();
        InstanceService failingEnds = failingEnds(new AtomicBoolean(true), attempts);
        InstanceStatusReconciler sweep = new InstanceStatusReconciler(failingEnds);

        // 1. No failure was counted when the earlier claim asked: while its hook is held back, a destroy fails its end.
        this.meanwhileAfterCommit(id, () -> Db.run(datasource,
            () -> assertUnfinished(() -> failingEnds.destroy(id), "step 1: the destroy's end failed")));
        Db.run(datasource, () -> sweep.reconcile(id));
        assertThat(attempts.get()).as("step 1: the late hook kept the failure, so a sweep waits its backoff")
            .isEqualTo(1);

        // 2. A failure was counted when it asked (a record no longer destroying, as another controller can leave
        //    it): a destroy failing in between is newer, and the late hook keeps it too.
        Db.run(datasource, () -> Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
            .assign(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED)
            .updateAll());
        this.meanwhileAfterCommit(id, () -> Db.run(datasource,
            () -> assertUnfinished(() -> failingEnds.destroy(id), "step 2: the destroy's end failed again")));
        Db.run(datasource, () -> sweep.reconcile(id));
        assertThat(attempts.get()).as("step 2: the newer failure kept its backoff").isEqualTo(2);
        assertThat(Db.supply(datasource, () -> stateOf(id))).as("step 2: still destroying")
            .isEqualTo("live " + InstanceModel.STATUS_DESTROYING);
    }

    /**
     * Runs {@code meanwhile} after an earlier transaction claimed the record (not being destroyed, so it lets go of
     * the record's failed ends) and committed, while that transaction's after-commit hooks are still held back.
     */
    private void meanwhileAfterCommit(int id, Runnable meanwhile) {
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        Racers.Running<Object> earlier = this.operations.start("an earlier transaction claiming the record", () -> {
            Db.run(datasource, () -> datasource.withTransaction(tx -> {
                // Registered first, so the claim's own after-commit hook runs behind it.
                datasource.afterCommit(() -> {
                    committed.countDown();
                    await(proceed);
                });
                assertThat(InstanceOperationLock.of(HostLeases.production()).finishTerminating(id,
                    UnfinishedDestroys.Policy.AUTOMATIC, () -> {})).as("the record is not being destroyed").isFalse();
            }));
            return null;
        });
        try {
            await(committed);
            meanwhile.run();
        } finally {
            proceed.countDown();
        }
        earlier.await(Duration.ofSeconds(30));
    }

    /**
     * A release takes no Delete of its own, so its application's Delete is the requested retry of a release whose
     * destroy was left unfinished: the dashboard points there, a background teardown of the application keeps the
     * release's budget (and is refused by name), and the application's requested Delete finishes the release and then
     * the application.
     */
    @Test
    void anApplicationsRequestedDeleteRetriesAHeldReleaseItTearsDown() {
        int releaseId = leftDestroying("destroy-held-release");
        AtomicBoolean failing = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        InstanceService failingEnds = failingEnds(failing, attempts);
        Db.run(datasource, () -> {
            // 1. The release belongs to an application, and it is held: it carried the unfinished stoppage from an
            //    earlier controller, and its one automatic attempt here failed.
            Row app = Models.get(InstanceModel.class).createEmptyRow();
            app.set(InstanceModel.NAME, "held-release-app");
            app.set(InstanceModel.KIND, "hohenheim:application");
            // Hostless: an application's host must run Docker, the fixture's is Incus, and its release is what ran.
            app.set(InstanceModel.SETTINGS, Map.of());
            Models.get(InstanceModel.class).save(app);
            int appId = app.get(InstanceModel.ID);
            Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(releaseId))
                .assign(InstanceModel.GENERATED_BY, "hohenheim:release")
                .assign(InstanceModel.GENERATED_FOR_MODEL, InstanceModel.MODEL_ID.toString())
                .assign(InstanceModel.GENERATED_FOR_ID, appId)
                .assign(InstanceModel.STOPPAGE_CAUSE, HohenheimActivityAction.DESTROY_UNFINISHED.id().getPath())
                .updateAll();
            new InstanceStatusReconciler(failingEnds).reconcile(releaseId);
            assertThat(attempts.get()).as("step 1: one automatic attempt, then held").isEqualTo(1);

            // 2. The dashboard points at the application, whose Delete retries it.
            List<AttentionItem> items = new ArrayList<>();
            InstanceAttention.unfinishedDestroys(items);
            assertThat(items).filteredOn(item -> AttentionSubject.instance(releaseId).equals(item.about()))
                .as("step 2: the item names the application's Delete").singleElement().satisfies(item -> {
                    assertThat(item.action().key()).isEqualTo("act_delete_owner_again");
                    assertThat(String.valueOf(item.action().args().get("name"))).isEqualTo("held-release-app");
                });

            // 3. A background teardown of the application keeps the release's budget: refused by name, untried.
            failing.set(false);
            assertUnfinished(() -> failingEnds.destroy(appId), "step 3: the automatic teardown is refused");
            assertThat(stateOf(releaseId)).as("step 3: the release is still destroying")
                .isEqualTo("live " + InstanceModel.STATUS_DESTROYING);
            assertThat(stateOf(appId)).as("step 3: the application stands").startsWith("live ");

            // 4. The application's requested Delete finishes the release, then ends the application.
            failingEnds.destroy(appId, UnfinishedDestroys.Policy.REQUESTED);
            assertThat(stateOf(releaseId)).as("step 4: the release is trashed and stopped")
                .isEqualTo("trashed " + InstanceModel.STATUS_STOPPED);
            assertThat(stateOf(appId)).as("step 4: the application is destroyed").startsWith("trashed ");
        });
    }

    /**
     * A stack service's workload and a database engine's instance take no Delete of their own either: the service's
     * requested Delete (its stack's carries the same policy) retries a held workload and the dashboard points there,
     * the engine's requested destroy retries its held instance, and every automatic teardown keeps the budget.
     */
    @Test
    void aStackServicesRequestedDeleteRetriesAHeldWorkloadAndAnEnginesRetriesItsInstance() {
        int workloadId = leftDestroying("destroy-held-service");
        int engineInstanceId = leftDestroying("destroy-held-engine");
        AtomicInteger attempts = new AtomicInteger();
        InstanceService failingEnds = failingEnds(new AtomicBoolean(true), attempts);
        Db.run(datasource, () -> {
            // 1. A stack service owns the workload and a shared engine the other instance; both are held: they
            //    carried the unfinished stoppage from an earlier controller, and one automatic attempt here failed.
            Row stack = Models.get(StackModel.class).createEmptyRow();
            stack.set(StackModel.NAME, "held-workload-stack");
            Models.get(StackModel.class).save(stack);
            Row service = Models.get(StackServiceModel.class).createEmptyRow();
            service.set(StackServiceModel.STACK_ID, stack.get(StackModel.ID));
            service.set(StackServiceModel.NAME, "held-workload-service");
            service.set(StackServiceModel.IMAGE, "alpine:latest");
            Models.get(StackServiceModel.class).save(service);
            int serviceId = service.get(StackServiceModel.ID);
            // The engine instance is found by its attribution alone, so its owner id needs no engine row.
            int engineId = 9_000_000 + engineInstanceId;
            heldFor(workloadId, StackInstances.SOURCE, StackServiceModel.MODEL_ID, serviceId, failingEnds);
            heldFor(engineInstanceId, DatabaseInstances.SOURCE, DatabaseEngineModel.MODEL_ID, engineId, failingEnds);
            assertThat(attempts.get()).as("step 1: one automatic attempt each, then held").isEqualTo(2);

            // 2. The dashboard points at the service, whose Delete retries it.
            List<AttentionItem> items = new ArrayList<>();
            InstanceAttention.unfinishedDestroys(items);
            assertThat(items).filteredOn(item -> AttentionSubject.instance(workloadId).equals(item.about()))
                .as("step 2: the item names the service's Delete").singleElement().satisfies(item -> {
                    assertThat(item.action().key()).isEqualTo("act_delete_owner_again");
                    assertThat(String.valueOf(item.action().args().get("name"))).contains("held-workload-service");
                });

            // 3. The automatic teardowns keep the budget: refused by name, untried.
            EngineHost engine = new EngineHost(DatabaseEngineModel.MODEL_ID, engineId, "held-engine",
                ManagedDatabase.Engine.POSTGRES, null, false, "root", "secret", "postgres",
                ServerModel.canonicalServerId(hostId), ResourceLimits.of(null, null), true);
            assertUnfinished(() -> unchecked(() -> StackInstances.destroyFor(serviceId)),
                "step 3: a stack deploy's prune is refused");
            assertUnfinished(() -> unchecked(() -> DatabaseInstances.destroyFor(engine, false)),
                "step 3: an automatic engine teardown is refused");
            assertThat(stateOf(workloadId)).as("step 3: the workload is still destroying")
                .isEqualTo("live " + InstanceModel.STATUS_DESTROYING);
            assertThat(stateOf(engineInstanceId)).as("step 3: the engine instance is still destroying")
                .isEqualTo("live " + InstanceModel.STATUS_DESTROYING);

            // 4. The service's requested Delete finishes its workload, the engine's its instance. Past the instance
            //    the engine teardown asks the host's Docker daemon, which this lane's Incus host is not: only the
            //    instance is this test's to judge.
            unchecked(() -> StackInstances.destroyFor(serviceId, UnfinishedDestroys.Policy.REQUESTED));
            assertThat(stateOf(workloadId)).as("step 4: the workload is trashed and stopped")
                .isEqualTo("trashed " + InstanceModel.STATUS_STOPPED);
            catchThrowable(() -> DatabaseInstances.destroyFor(engine, false, UnfinishedDestroys.Policy.REQUESTED));
            assertThat(stateOf(engineInstanceId)).as("step 4: the engine instance is trashed and stopped")
                .isEqualTo("trashed " + InstanceModel.STATUS_STOPPED);
        });
    }

    /**
     * A move onto a shared engine that could not destroy the old dedicated engine leaves it held under a database that
     * is live on the shared engine: its Delete would drop that live database, so the dashboard routes to the
     * database's own "finish removing the old engine" action, which retries only the leftover and never asks the
     * serving shared engine for anything. The move's automatic cleanup keeps its budget.
     */
    @Test
    void aSharedDatabasesOldEngineIsFinishedByItsOwnActionAndTheDatabaseSurvives() {
        int leftoverId = leftDestroying("destroy-held-old-engine");
        AtomicInteger attempts = new AtomicInteger();
        InstanceService failingEnds = failingEnds(new AtomicBoolean(true), attempts);
        onFakeLocalDocker(docker -> Db.run(datasource, () -> {
            // 1. The database moved onto a serving shared engine; the old engine's destroy was left unfinished,
            //    carried the stoppage from an earlier controller, and failed once here: held.
            Row engine = servingSharedEngine("old-engine-shared");
            int databaseId = databaseRecord("moved-db", engine);
            heldFor(leftoverId, DatabaseInstances.SOURCE, DatabaseModel.MODEL_ID, databaseId, failingEnds);
            assertThat(attempts.get()).as("step 1: one automatic attempt, then held").isEqualTo(1);
            Row moved = Models.get(DatabaseModel.class).findById(databaseId);
            assertThat(DatabaseInstances.holdOldEngines(List.of(moved))).as("step 1: the action applies")
                .containsExactly(true);

            // 2. The dashboard points at the database's own action, never its Delete.
            List<AttentionItem> items = new ArrayList<>();
            InstanceAttention.unfinishedDestroys(items);
            assertThat(items).filteredOn(item -> AttentionSubject.instance(leftoverId).equals(item.about()))
                .as("step 2: the item names the old-engine action").singleElement().satisfies(item -> {
                    assertThat(item.action().key()).isEqualTo("act_finish_old_engine");
                    assertThat(String.valueOf(item.action().args().get("name"))).contains("moved-db");
                    assertThat(item.target().toUrl()).contains("/" + databaseId + "/");
                });

            // 3. The move's own cleanup is automatic: refused by name, untried, the leftover still destroying.
            assertUnfinished(() -> unchecked(() -> DatabaseInstances.destroyFor(moved, false)),
                "step 3: the automatic cleanup keeps the budget");
            assertThat(attempts.get()).as("step 3: untried").isEqualTo(1);
            assertThat(stateOf(leftoverId)).as("step 3: still destroying")
                .isEqualTo("live " + InstanceModel.STATUS_DESTROYING);

            // 4. The action finishes the leftover; the database stays live and shared, the shared engine is never
            //    asked to drop it, remove its user or remove its data, and the action no longer applies.
            DatabaseWrites.finishOldEngine(moved);
            assertThat(stateOf(leftoverId)).as("step 4: the old engine is trashed and stopped")
                .isEqualTo("trashed " + InstanceModel.STATUS_STOPPED);
            assertThat(docker.callCount(execOn(engine)))
                .as("step 4: no logical drop or user removal ran on the shared engine").isZero();
            assertThat(docker.callCount(volumeRemovalOf(engine)))
                .as("step 4: the shared engine's data volume stays").isZero();
            Row survivor = Models.get(DatabaseModel.class).findById(databaseId);
            assertThat(survivor).as("step 4: the database survives").isNotNull();
            assertThat((String) survivor.get(DatabaseModel.STATUS)).as("step 4: still active")
                .isEqualTo(DatabaseModel.STATUS_ACTIVE);
            assertThat(DatabaseModel.isShared(survivor)).as("step 4: still shared").isTrue();
            assertThat(DatabaseInstances.holdOldEngines(List.of(survivor))).as("step 4: nothing left to finish")
                .containsExactly(false);
            Models.get(DatabaseModel.class).find().where(DatabaseModel.ID.eq(databaseId)).delete();
        }));
    }

    /**
     * A shared database's Delete finishes the old engine it still owns BEFORE it drops anything: while that finish
     * fails the shared engine is never asked to drop the live database and the record stays; once the finish succeeds
     * the Delete reaches the shared engine, which shows the fixture records a drop when there is one.
     */
    @Test
    void aSharedDeleteWhoseOldEngineFinishFailsDropsNothingAndKeepsTheRecord() {
        int leftoverId = leftDestroying("destroy-held-delete-engine");
        InstanceService failingEnds = failingEnds(new AtomicBoolean(true), new AtomicInteger());
        onFakeLocalDocker(docker -> Db.run(datasource, () -> {
            // 1. A database moved onto a serving shared engine still owns its held old engine.
            Row engine = servingSharedEngine("delete-engine-shared");
            int databaseId = databaseRecord("moved-delete-db", engine);
            heldFor(leftoverId, DatabaseInstances.SOURCE, DatabaseModel.MODEL_ID, databaseId, failingEnds);

            // 2. Another controller holds the old engine's claim, so the Delete cannot finish it and fails.
            Lease rivalClaim = Leases.independent(datasource).acquire(InstanceOperationLock.KEY_PREFIX + leftoverId, 0);
            assertThat(rivalClaim).as("step 2: the rival holds the old engine").isNotNull();
            try {
                assertThatThrownBy(() -> DatabaseWrites.destroy(Models.get(DatabaseModel.class).findById(databaseId)))
                    .as("step 2: the Delete fails").isInstanceOf(Violations.class);
            } finally {
                rivalClaim.release();
            }
            assertThat(docker.callCount(execOn(engine)))
                .as("step 2: the live logical database and its user were never dropped").isZero();
            Row kept = Models.get(DatabaseModel.class).findById(databaseId);
            assertThat(kept).as("step 2: the record is kept").isNotNull();
            assertThat((String) kept.get(DatabaseModel.STATUS)).as("step 2: marked as a failed destroy")
                .isEqualTo(DatabaseModel.STATUS_DESTROY_FAILED);
            assertThat(stateOf(leftoverId)).as("step 2: the old engine is still destroying")
                .isEqualTo("live " + InstanceModel.STATUS_DESTROYING);

            // 3. With the claim free the Delete finishes the old engine and only then asks the shared engine to drop;
            //    this fake daemon answers no exec, so the drop is unconfirmed and the record kept, but it was asked.
            assertThatThrownBy(() -> DatabaseWrites.destroy(Models.get(DatabaseModel.class).findById(databaseId)))
                .as("step 3: the fake engine cannot confirm the drop").isInstanceOf(Violations.class);
            assertThat(stateOf(leftoverId)).as("step 3: the old engine is trashed and stopped")
                .isEqualTo("trashed " + InstanceModel.STATUS_STOPPED);
            assertThat(docker.callCount(execOn(engine))).as("step 3: the drop reached the shared engine once")
                .isEqualTo(1);
            assertThat(docker.callCount(volumeRemovalOf(engine))).as("step 3: the shared data volume stays")
                .isZero();
            assertThat(Models.get(DatabaseModel.class).findById(databaseId)).as("step 3: the record is kept")
                .isNotNull();
            Models.get(DatabaseModel.class).find().where(DatabaseModel.ID.eq(databaseId)).delete();
        }));
    }

    /**
     * The automatic cleanup can end the old engine after the action read it and before the action claims it: the
     * action then meets no record to claim, and since a fresh read finds the old engine ended, it reports success.
     */
    @Test
    void anOldEngineFinishOvertakenByTheAutomaticCleanupSucceeds() {
        int leftoverId = leftDestroying("destroy-raced-old-engine");
        onFakeLocalDocker(docker -> Db.run(datasource, () -> {
            int databaseId = databaseRecord("raced-db", sharedEngine("raced-engine-shared"));
            attributeTo(leftoverId, DatabaseInstances.SOURCE, DatabaseModel.MODEL_ID, databaseId);
            Row moved = Models.get(DatabaseModel.class).findById(databaseId);
            AtomicReference<String> inWindow = new AtomicReference<>();

            // 1. The action's teardown reads the old engine; before it claims it, the move's automatic cleanup (the
            //    same teardown under the automatic policy, inside the owner's scope) ends it.
            assertThatCode(() -> DatabaseInstances.destroyFor(EngineHost.dedicated(moved), false,
                    UnfinishedDestroys.Policy.REQUESTED, () -> {
                        unchecked(() -> DatabaseInstances.destroyFor(moved, false));
                        inWindow.set(stateOf(leftoverId));
                    }))
                .as("step 1: the action reports success").doesNotThrowAnyException();
            assertThat(inWindow.get()).as("step 1: the automatic cleanup ended the old engine inside the window")
                .isEqualTo("trashed " + InstanceModel.STATUS_STOPPED);

            // 2. The database is untouched and nothing is left to finish.
            Row survivor = Models.get(DatabaseModel.class).findById(databaseId);
            assertThat((String) survivor.get(DatabaseModel.STATUS)).as("step 2: still active")
                .isEqualTo(DatabaseModel.STATUS_ACTIVE);
            assertThat(DatabaseInstances.holdOldEngines(List.of(survivor))).as("step 2: nothing left to finish")
                .containsExactly(false);
            Models.get(DatabaseModel.class).find().where(DatabaseModel.ID.eq(databaseId)).delete();
        }));
    }

    /** Which databases of a page offer the old-engine action is one read, however many rows the page holds. */
    @Test
    void theOldEngineActionOfAPageIsDecidedInOneRead() {
        int firstLeftover = leftDestroying("destroy-page-old-engine-1");
        int secondLeftover = leftDestroying("destroy-page-old-engine-2");
        InstanceService failingEnds = failingEnds(new AtomicBoolean(true), new AtomicInteger());
        Db.run(datasource, () -> {
            Row engine = sharedEngine("page-engine-shared");
            List<Integer> ids = new ArrayList<>();

            // 1. A page of two: a shared database whose old engine is held, and a dedicated one.
            ids.add(databaseRecord("page-held-1", engine));
            heldFor(firstLeftover, DatabaseInstances.SOURCE, DatabaseModel.MODEL_ID, ids.get(0), failingEnds);
            ids.add(databaseRecord("page-dedicated-1", null));
            DatasourceProbe probe = DatasourceProbe.on(datasource);
            DatabaseInstances.holdOldEngines(page(ids));
            List<Row> two = page(ids);
            probe.reset();
            assertThat(DatabaseInstances.holdOldEngines(two)).as("step 1: only the held one applies")
                .containsExactly(true, false);
            int instanceReads = probe.queriesAgainst(InstanceModel.MODEL_ID);
            int roundTrips = probe.roundTrips();

            // 2. Twice the rows, another held one and a shared one with no old engine among them: the same reads.
            ids.add(databaseRecord("page-held-2", engine));
            heldFor(secondLeftover, DatabaseInstances.SOURCE, DatabaseModel.MODEL_ID, ids.get(2), failingEnds);
            ids.add(databaseRecord("page-clean", engine));
            List<Row> four = page(ids);
            probe.reset();
            assertThat(DatabaseInstances.holdOldEngines(four)).as("step 2: each answered in order")
                .containsExactly(true, false, true, false);
            assertThat(probe.queriesAgainst(InstanceModel.MODEL_ID)).as("step 2: one instance read for any page")
                .isEqualTo(instanceReads).isEqualTo(1);
            assertThat(probe.roundTrips()).as("step 2: no round trip per row").isEqualTo(roundTrips);
            Models.get(DatabaseModel.class).find().where(DatabaseModel.ID.in(ids)).delete();
        });
    }

    /** The database rows of a page, in order. */
    private static List<Row> page(List<Integer> ids) {
        List<Row> rows = new ArrayList<>();
        for (int id : ids) {
            rows.add(Models.get(DatabaseModel.class).findById(id));
        }
        return rows;
    }

    /** Runs {@code journey} with the local host's Docker daemon faked, so every call to it is recorded. */
    private static void onFakeLocalDocker(Consumer<FakeDockerDaemon> journey) {
        FakeDockerDaemon daemon = new FakeDockerDaemon();
        DockerClient.overrideLocalTransportForTest(() -> daemon);
        try {
            journey.accept(daemon);
        } finally {
            DockerClient.overrideLocalTransportForTest(null);
            daemon.close();
        }
    }

    /** A shared engine row on the local host that owns no instance. */
    private static Row sharedEngine(String name) {
        Row engine = Models.get(DatabaseEngineModel.class).createEmptyRow();
        engine.set(DatabaseEngineModel.NAME, name);
        engine.set(DatabaseEngineModel.ENGINE, DatabaseModel.ENGINE_POSTGRES);
        engine.set(DatabaseEngineModel.SERVER_ID, ServerModel.localServerId());
        engine.set(DatabaseEngineModel.ROOT_USER, "root");
        engine.set(DatabaseEngineModel.ROOT_PASSWORD, "shared-root-pw");
        engine.set(DatabaseEngineModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        Models.get(DatabaseEngineModel.class).save(engine);
        return engine;
    }

    /**
     * A shared engine that SERVES: its owned instance is deployed and answers running, while its logical-database
     * commands and volume removals go to the local host's daemon, which a test fakes ({@link #onFakeLocalDocker}).
     */
    private static Row servingSharedEngine(String name) {
        int instanceId = BackupLaneFixture.instanceRecord(name + "-instance", hostId);
        new InstanceService().deploy(instanceId);
        Row engine = sharedEngine(name);
        attributeTo(instanceId, DatabaseEngines.SOURCE, DatabaseEngineModel.MODEL_ID,
            engine.get(DatabaseEngineModel.ID));
        assertThat(DatabaseInstances.liveStatus(EngineHost.ofEngine(engine)).running())
            .as("the shared engine serves").isTrue();
        return engine;
    }

    /** A database record on the local host: dedicated without an engine, a logical database on it with one. */
    private static int databaseRecord(String name, @Nullable Row engine) {
        String identifier = name.replace('-', '_');
        Row row = Models.get(DatabaseModel.class).createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, DatabaseModel.ENGINE_POSTGRES);
        row.set(DatabaseModel.DB_NAME, identifier);
        row.set(DatabaseModel.DB_USER, identifier);
        row.set(DatabaseModel.DB_PASSWORD, "database-pw");
        row.set(DatabaseModel.SERVER_ID, ServerModel.localServerId());
        row.set(DatabaseModel.PLACEMENT, engine == null
            ? DatabaseModel.PLACEMENT_DEDICATED : DatabaseModel.PLACEMENT_SHARED);
        row.set(DatabaseModel.ENGINE_ID, engine == null ? null : engine.get(DatabaseEngineModel.ID));
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        Models.get(DatabaseModel.class).save(row);
        return row.get(DatabaseModel.ID);
    }

    /** The exec a logical-database command (a drop, a user removal) asks of the engine's container. */
    private static String execOn(Row engine) {
        return "api:POST /containers/" + EngineHandles.ofEngine(engine) + "/exec";
    }

    /** The removal of the shared engine's data volume. */
    private static String volumeRemovalOf(Row engine) {
        return "api:DELETE /volumes/" + EngineHost.ofEngine(engine).dataVolume();
    }

    /** Attributes an instance record to an owning tier's record, as that tier's generated rows carry it. */
    private static void attributeTo(int id, String source, Identifier ownerModel, int ownerId) {
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
            .assign(InstanceModel.GENERATED_BY, source)
            .assign(InstanceModel.GENERATED_FOR_MODEL, ownerModel.toString())
            .assign(InstanceModel.GENERATED_FOR_ID, ownerId)
            .updateAll();
    }

    /**
     * Attributes a record left destroying to an owning tier's record and holds it, as a restarted controller finds
     * one that carried the unfinished stoppage: its one automatic attempt here fails.
     */
    private static void heldFor(int id, String source, Identifier ownerModel, int ownerId,
                                InstanceService failingEnds) {
        attributeTo(id, source, ownerModel, ownerId);
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
            .assign(InstanceModel.STOPPAGE_CAUSE, HohenheimActivityAction.DESTROY_UNFINISHED.id().getPath())
            .updateAll();
        new InstanceStatusReconciler(failingEnds).reconcile(id);
    }

    /** An owning tier's teardown, its refusal unwrapped from the IOException that tier reports it as. */
    private static void unchecked(IoWork work) {
        try {
            work.run();
        } catch (IOException failed) {
            if (failed.getCause() instanceof Violations refused) {
                throw refused;
            }
            throw new UncheckedIOException(failed);
        }
    }

    private interface IoWork {
        void run() throws IOException;
    }

    /** A service whose destroy ends fail while {@code failing} holds, counting each attempt. */
    private static InstanceService failingEnds(AtomicBoolean failing, AtomicInteger attempts) {
        return new InstanceService(HostLeases.production(), () -> {}, () -> {
            if (failing.get()) {
                attempts.incrementAndGet();
                throw new IllegalStateException("the quota release hook failed");
            }
        });
    }

    private static void assertUnfinished(Runnable call, String step) {
        assertThatThrownBy(call::run).as(step)
            .isInstanceOfSatisfying(Violations.class, refused -> assertThat(refused.all())
                .anySatisfy(violation ->
                    assertThat(violation.message().key()).isEqualTo("instance_destroy_unfinished")));
    }

    /**
     * A deployed record whose destroy's end is dropped: a rival controller claims the record between the release and
     * the end (its lease, and its fence stamped on the row, as ClaimedRows stamps it) and dies before it does anything
     * else.
     */
    private static int leftDestroying(String name) {
        int[] record = new int[1];
        Db.run(datasource, () -> {
            record[0] = BackupLaneFixture.instanceRecord(name, hostId);
            new InstanceService().deploy(record[0]);
        });
        int id = record[0];
        Leases dyingRival = Leases.independent(datasource);
        Db.run(datasource, () -> new InstanceService(HostLeases.production(), () -> {}, () -> {
            Lease claim = dyingRival.acquire(InstanceOperationLock.KEY_PREFIX + id, 0);
            assertThat(claim).as("the rival takes the record's claim once the destroy let go of it").isNotNull();
            Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
                .assign(InstanceModel.CLAIM_FENCE, claim.fence())
                .updateAll();
            claim.release();
        }).destroy(id));
        return id;
    }

    /**
     * The record's claim is one lease across every controller: a second operation is refused or queued behind the
     * first, a holder that stalls past its TTL is taken over by a rival with a later fence, and the stalled holder's
     * late outcome write is refused by name while the rival's state stands.
     */
    @Test
    void theRecordsClaimSerializesTakesOverACrashedHolderAndRefusesItsLateWrite() throws Exception {
        int[] record = new int[1];
        Db.run(datasource, () -> record[0] = BackupLaneFixture.instanceRecord("claim-journey", hostId));
        int id = record[0];
        Leases crashedCoordinator = Leases.independent(datasource);
        Leases rivalCoordinator = Leases.independent(datasource);
        HostLeases first = new HostLeases(Leases::of, Leases.DEFAULT_TTL);
        HostLeases crashed = new HostLeases(d -> crashedCoordinator, Duration.ofSeconds(1));
        HostLeases rival = new HostLeases(d -> rivalCoordinator, Leases.DEFAULT_TTL);

        // 1. While one operation holds the record, a second is refused at once, and a queued one runs only after the
        //    first released it.
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Racers.Running<Object> holder = this.operations.start("the holding operation", () -> {
            Db.run(datasource, () -> InstanceOperationLock.of(first).exclusive(id,
                InstanceOperationLock.Contention.REFUSE, () -> {
                    holding.countDown();
                    await(release);
                }));
            return null;
        });
        assertThat(holding.await(30, TimeUnit.SECONDS)).as("step 1: the first operation holds the record").isTrue();
        Db.run(datasource, () -> assertThatThrownBy(() -> InstanceOperationLock.of(rival).exclusive(id,
                InstanceOperationLock.Contention.REFUSE, () -> {}))
            .as("step 1: a second operation, even another controller's, is refused while the first runs")
            .isInstanceOfSatisfying(Violations.class, refused -> assertThat(refused.all()).anySatisfy(violation ->
                assertThat(violation.message().key()).isEqualTo("instance_operation_in_progress"))));
        AtomicLong queuedRanAt = new AtomicLong();
        Racers.Running<Object> queued = this.operations.start("the queued operation", () -> {
            Db.run(datasource, () -> InstanceOperationLock.of(first).exclusive(id,
                InstanceOperationLock.Contention.QUEUE, () -> queuedRanAt.set(System.nanoTime())));
            return null;
        });
        Poll.until("step 1: the second operation waits for the record", Duration.ofSeconds(30),
            () -> InstanceOperationLock.of(first).isQueued(id, queued.thread()));
        long releasedAt = System.nanoTime();
        release.countDown();
        holder.await(Duration.ofSeconds(30));
        queued.await(Duration.ofSeconds(30));
        assertThat(queuedRanAt.get()).as("step 1: the queued operation ran only after the release")
            .isGreaterThan(releasedAt);

        // 2. A holder stalls past its 1s TTL (never renewed in time) in the middle of its operation; a rival takes
        //    the record over and records its outcome.
        CountDownLatch stalled = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Throwable> lateWrite = new AtomicReference<>();
        Racers.Running<Object> stale = this.operations.start("the stale holder", () -> {
            Db.run(datasource, () -> InstanceOperationLock.of(crashed).exclusive(id,
                InstanceOperationLock.Contention.REFUSE, () -> {
                    stalled.countDown();
                    await(resume);
                    // 3. It resumes and writes its outcome through its old claim.
                    lateWrite.set(catchThrowable(() ->
                        new InstanceService(crashed, () -> {}).assignRuntimeRole(id, InstanceModel.ROLE_RETIRED)));
                }));
            return null;
        });
        assertThat(stalled.await(30, TimeUnit.SECONDS)).as("step 2: the stale holder holds the record").isTrue();
        String claimKey = InstanceOperationLock.KEY_PREFIX + id;
        Poll.until("step 2: the stalled claim lapsed", Duration.ofSeconds(30),
            () -> !rivalCoordinator.anyHeld(List.of(claimKey)));
        Db.run(datasource, () -> {
            new InstanceService(rival, () -> {}).assignRuntimeRole(id, InstanceModel.ROLE_SERVING);
            // The host stays free for the stale holder: what refuses its write must be the record's claim alone.
            rival.release(ServerModel.canonicalServerId(hostId));
        });

        // 3. The stale holder's late write is refused by name, and the rival's outcome stands.
        resume.countDown();
        stale.await(Duration.ofSeconds(60));
        assertThat(lateWrite.get()).as("step 3: the late write is a hard failure, not a shrug")
            .isInstanceOfSatisfying(Violations.class, refused -> assertThat(refused.all()).anySatisfy(violation ->
                assertThat(violation.message().key()).isEqualTo("instance_fenced_out")));
        Db.run(datasource, () -> assertThat(
                (String) Models.get(InstanceModel.class).findById(id).get(InstanceModel.RUNTIME_ROLE))
            .as("step 3: the rival's outcome stands").isEqualTo(InstanceModel.ROLE_SERVING));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the journey never released the latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

}
