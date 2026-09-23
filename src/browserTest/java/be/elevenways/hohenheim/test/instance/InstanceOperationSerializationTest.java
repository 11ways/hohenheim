package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceSnapshots;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    private static SqlDatasource datasource;
    private static int hostId;

    @BeforeAll
    static void setUp() throws Exception {
        BackupLaneFixture fixture = BackupLaneFixture.install();
        datasource = fixture.datasource;
        hostId = fixture.hostId;
    }

    @AfterAll
    static void tearDown() {
        FakeNativeDaemons.DURING_SNAPSHOT.set(null);
        FakeNativeDaemons.DURING_RESTORE.set(null);
        BackupLaneFixture.uninstall();
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
                Thread rival = new Thread(() -> Db.run(datasource, () -> {
                    rivalDeploy.set(catchThrowable(() -> new InstanceService().deploy(id)));
                    rivalStop.set(catchThrowable(() -> new InstanceService().stop(id)));
                }), "rival-operation");
                rival.start();
                try {
                    rival.join(TimeUnit.SECONDS.toMillis(60));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
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
                throw Violations.ofForm(Microcopy.of("snapshots_unsupported")
                    .withFilter("scope", "violations").withArg("kind", "fake"));
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
                throw Violations.ofForm(Microcopy.of("snapshots_unsupported")
                    .withFilter("scope", "violations").withArg("kind", "fake"));
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
}
