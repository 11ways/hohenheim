package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceStatusReconciler;
import be.elevenways.hohenheim.server.instance.InstanceStatusReconciler.Verdict;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status column against daemon truth: a workload that died stops reporting itself
 * alive, a host that cannot be asked never makes one look dead, and a record an operation
 * is driving right now is left alone.
 *
 * AIDEV-NOTE: the defect (workspace-journey audit, defect 2). A default workspace has no
 * template and {@code crash_policy none}, so {@code InstanceConsoles.prepare} opens no
 * watch for it and nothing else reconciled instance status -- the list pill and the
 * Overview badge rendered a column stamped at the last deploy and never touched again.
 * The reproduction below is that exact shape: deploy, then let the workload's process go
 * away with nobody watching.
 *
 * AIDEV-NOTE: {@link BackupLaneFixture} is the fake-native lane's fixture, named for its
 * first consumer -- a migrated private database, a fake daemon and an admitted host. A
 * third hand-rolled copy of that seeding is what reusing it avoids.
 */
class InstanceStatusReconcileTest {

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
        BackupLaneFixture.uninstall();
        FakeNativeDaemons.UNREACHABLE_HANDLES.clear();
    }

    /**
     * The whole point: a dead workload is corrected, and only a daemon that ANSWERED is
     * ever allowed to move the column.
     */
    @Test
    void aDeadWorkloadIsCorrectedWhileAnUnreachableHostMovesNothing() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceStatusReconciler reconciler = new InstanceStatusReconciler();
            int id = BackupLaneFixture.instanceRecord("reconcile-crash", hostId);
            service.deploy(id);

            // 1. Deployed: the record says running, and NOTHING has confirmed that
            //    against a daemon -- the badge is a claim about the past, and the column
            //    that dates it is honestly empty.
            assertThat((String) statusOf(id))
                .as("step 1: a fresh deploy stamps running")
                .isEqualTo(InstanceModel.STATUS_RUNNING);
            assertThat(observedAtOf(id))
                .as("step 1: and nothing has confirmed it against the runtime yet")
                .isNull();

            // 2. A sweep while the workload really is up CONFIRMS: the status does not
            //    move, and the confirmation is dated.
            InstanceStatusReconciler.Outcome confirmed = reconciler.reconcile(id);
            assertThat(confirmed.verdict())
                .as("step 2: a daemon that agrees confirms rather than corrects")
                .isEqualTo(Verdict.CONFIRMED);
            assertThat((String) statusOf(id))
                .as("step 2: and the status stays running")
                .isEqualTo(InstanceModel.STATUS_RUNNING);
            Instant firstConfirmation = observedAtOf(id);
            assertThat(firstConfirmation)
                .as("step 2: the confirmation is stamped, so the badge can date itself")
                .isNotNull();

            // 3. THE DEFECT: the workload's process exits with nobody watching. The
            //    record still claims running; the daemon says otherwise.
            FakeNativeDaemons.daemonOf(hostId)
                .get(FakeNativeDaemons.handleOf(id)).running = false;
            InstanceStatusReconciler.Outcome corrected = reconciler.reconcile(id);
            assertThat(corrected.verdict())
                .as("step 3: a daemon that disagrees corrects the record")
                .isEqualTo(Verdict.CORRECTED);
            assertThat(corrected.observed())
                .as("step 3: on the daemon's own answer, named")
                .isEqualTo(ContainerState.STOPPED);
            assertThat((String) statusOf(id))
                .as("step 3: the stored column now says what is actually true -- error,"
                    + " never plain stopped: an operator stop and an unobserved death"
                    + " must not render as the same pill")
                .isEqualTo(InstanceModel.STATUS_ERROR);

            // 4. The correction is ACCOUNTABLE: an operator can find out when their box
            //    stopped and on whose evidence, without reading a log file.
            List<Row> reconciled = activity(id,
                InstanceStatusReconciler.ACTIVITY_RECONCILE_ACTION);
            assertThat(reconciled)
                .as("step 4: the correction is recorded on the record's own activity")
                .hasSize(1);
            assertThat((String) reconciled.get(0).get(ActivityModel.DETAIL))
                .as("step 4: naming both statuses, the crash shape and the evidence")
                .contains(InstanceModel.STATUS_RUNNING)
                .contains(InstanceModel.STATUS_ERROR)
                .contains("died without an observed stop");

            // 4b. And the dashboard SEES it: the crashedInstances attention item reads
            //     exactly the status this correction stamped, and links to this record.
            List<AttentionItem> attention = new ArrayList<>();
            AttentionCollector.crashedInstances(attention);
            assertThat(attention)
                .as("step 4b: the crashed workload is a dashboard attention item")
                .anySatisfy(item -> assertThat(item.target().toUrl())
                    .contains("/instances/" + id + "/"));

            // 5. THE HONESTY RULE, falsified against the very same disagreement: put the
            //    record back to running and make the daemon UNANSWERABLE rather than
            //    negative. The identical sweep must now move nothing at all.
            Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(id))
                .assign(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING)
                .assign(InstanceModel.STATUS_OBSERVED_AT, (Object) null)
                .updateAll();
            FakeNativeDaemons.UNREACHABLE_HANDLES.add(FakeNativeDaemons.handleOf(id));
            InstanceStatusReconciler.Outcome unasked = reconciler.reconcile(id);
            assertThat(unasked.verdict())
                .as("step 5: an unreachable daemon is not evidence of anything")
                .isEqualTo(Verdict.COULD_NOT_ASK);
            assertThat((String) statusOf(id))
                .as("step 5: so the status is NOT downgraded")
                .isEqualTo(InstanceModel.STATUS_RUNNING);
            assertThat(observedAtOf(id))
                .as("step 5: and nothing claims the status was confirmed")
                .isNull();

            // 6. FALSIFIED the other way: make the SAME handle answerable again and the
            //    same call corrects it -- so step 5 was about reachability, not about a
            //    sweeper that had simply stopped working.
            FakeNativeDaemons.UNREACHABLE_HANDLES.remove(FakeNativeDaemons.handleOf(id));
            assertThat(reconciler.reconcile(id).verdict())
                .as("step 6: the daemon answers again and the correction lands")
                .isEqualTo(Verdict.CORRECTED);
            assertThat((String) statusOf(id))
                .as("step 6: error again, because the daemon says the workload died")
                .isEqualTo(InstanceModel.STATUS_ERROR);
        });
    }

    /**
     * The sweep must not fight an operation in flight: a deploy that is between its
     * daemon work and its own outcome write legitimately disagrees with the record.
     */
    @Test
    void aRecordAnOperationIsDrivingRightNowIsLeftAlone() {
        Db.run(datasource, () -> {
            InstanceStatusReconciler reconciler = new InstanceStatusReconciler();
            int id = BackupLaneFixture.instanceRecord("reconcile-inflight", hostId);
            new InstanceService().deploy(id);

            // 1. The record claims running and the daemon agrees, as after any deploy.
            assertThat((String) statusOf(id))
                .as("step 1: deployed and stamped running")
                .isEqualTo(InstanceModel.STATUS_RUNNING);

            // 2. Deploy again, and sweep from INSIDE the window between the daemon work
            //    and the fenced outcome write -- with the workload made to look dead, so
            //    the sweep has every reason to "correct" it. This is the real shape of
            //    the race: a deploy replacing a container takes as long as an image pull,
            //    and the record carries its PREVIOUS status the whole time.
            InstanceStatusReconciler.Outcome[] midDeploy = new InstanceStatusReconciler.Outcome[1];
            InstanceService driving = new InstanceService(HostLeases.production(), () -> {
                FakeNativeDaemons.daemonOf(hostId)
                    .get(FakeNativeDaemons.handleOf(id)).running = false;
                midDeploy[0] = reconciler.reconcile(id);
            });
            driving.deploy(id);

            assertThat(midDeploy[0].verdict())
                .as("step 2: a record this controller is mid-operation on is skipped")
                .isEqualTo(Verdict.SKIPPED);
            assertThat((String) statusOf(id))
                .as("step 2: and the deploy's own outcome write is what stands")
                .isEqualTo(InstanceModel.STATUS_RUNNING);

            // 3. FALSIFIED: the deploy is over, nothing is in flight, and the SAME
            //    disagreement is now corrected -- so step 2 was the in-flight mark and
            //    not a sweeper that never corrects anything.
            assertThat(reconciler.reconcile(id).verdict())
                .as("step 3: once the operation is done the same state is corrected")
                .isEqualTo(Verdict.CORRECTED);
            assertThat((String) statusOf(id))
                .as("step 3: to the crash shape the daemon's answer implies")
                .isEqualTo(InstanceModel.STATUS_ERROR);

            // 4. A PROTECTED status (a capture or restore holding the record) is never
            //    swept: it is another operation's to settle, and overwriting it would
            //    un-protect the very thing it guards.
            Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(id))
                .assign(InstanceModel.STATUS, InstanceModel.STATUS_CAPTURING)
                .updateAll();
            assertThat(reconciler.reconcile(id).verdict())
                .as("step 4: a capturing record is left to the capture")
                .isEqualTo(Verdict.SKIPPED);
            assertThat((String) statusOf(id))
                .as("step 4: with its protected status intact")
                .isEqualTo(InstanceModel.STATUS_CAPTURING);

            // 5. And a record whose template install has not finished: the deploy lane
            //    itself refuses there, so the sweep must not stamp over the lifecycle.
            Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(id))
                .assign(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING)
                .assign(InstanceModel.INSTALL_STATE, InstanceModel.INSTALL_INSTALLING)
                .updateAll();
            assertThat(reconciler.reconcile(id).verdict())
                .as("step 5: a mid-install record is skipped")
                .isEqualTo(Verdict.SKIPPED);
            assertThat((String) statusOf(id))
                .as("step 5: and keeps the status the install lane will settle")
                .isEqualTo(InstanceModel.STATUS_RUNNING);

            // 6. FALSIFIED: settle the install and the identical sweep corrects, so step
            //    5 was the install lifecycle and nothing else about this record.
            Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(id))
                .assign(InstanceModel.INSTALL_STATE, InstanceModel.INSTALL_INSTALLED)
                .updateAll();
            assertThat(reconciler.reconcile(id).verdict())
                .as("step 6: an installed record is reconciled normally")
                .isEqualTo(Verdict.CORRECTED);
        });
    }

    /**
     * The restart half: an UNWATCHED workload under {@code crash_policy restart} comes
     * back from an unobserved death (which is also what recovers it after a host
     * reboot), and a crash LOOP suspends the restarts with a named error instead of
     * spinning the daemon forever.
     */
    @Test
    void aCrashedWorkloadWithRestartPolicyComesBackUntilItFlaps() {
        Db.run(datasource, () -> {
            InstanceStatusReconciler reconciler = new InstanceStatusReconciler();
            int id = BackupLaneFixture.instanceRecord("reconcile-restart", hostId);
            Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(id))
                .assign(InstanceModel.CRASH_POLICY, InstanceModel.CRASH_RESTART)
                .updateAll();
            new InstanceService().deploy(id);

            // 1. First unobserved death: the correction lands AND the workload is
            //    redeployed -- the record settles back to running on its own.
            FakeNativeDaemons.daemonOf(hostId)
                .get(FakeNativeDaemons.handleOf(id)).running = false;
            assertThat(reconciler.reconcile(id).verdict())
                .as("step 1: the death is corrected")
                .isEqualTo(Verdict.CORRECTED);
            awaitStatus(id, InstanceModel.STATUS_RUNNING,
                "step 1: and crash policy restart brings the workload back");

            // 2. Second death inside the flap window: still restarted.
            FakeNativeDaemons.daemonOf(hostId)
                .get(FakeNativeDaemons.handleOf(id)).running = false;
            assertThat(reconciler.reconcile(id).verdict())
                .as("step 2: corrected again")
                .isEqualTo(Verdict.CORRECTED);
            awaitStatus(id, InstanceModel.STATUS_RUNNING,
                "step 2: and restarted again");

            // 3. Third death inside the window trips flap protection: the record settles
            //    to ERROR, restarts are suspended, and the loop is named in the activity.
            FakeNativeDaemons.daemonOf(hostId)
                .get(FakeNativeDaemons.handleOf(id)).running = false;
            assertThat(reconciler.reconcile(id).verdict())
                .as("step 3: the third crash is still a correction")
                .isEqualTo(Verdict.CORRECTED);
            awaitStatus(id, InstanceModel.STATUS_ERROR,
                "step 3: but flap protection suspends the restarts");
            List<Row> reconciled = activity(id,
                InstanceStatusReconciler.ACTIVITY_RECONCILE_ACTION);
            assertThat((String) reconciled.get(0).get(ActivityModel.DETAIL))
                .as("step 3: and the suspension is named where the operator looks")
                .contains("crash loop");
        });
    }

    /** Bounded wait for an async redeploy (or an error settle) to stamp the column. */
    private static void awaitStatus(int instanceId, String expected, String what) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(statusOf(instanceId))) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat((String) statusOf(instanceId)).as(what).isEqualTo(expected);
    }

    private static String statusOf(int instanceId) {
        return Models.get(InstanceModel.class).findById(instanceId).get(InstanceModel.STATUS);
    }

    private static Instant observedAtOf(int instanceId) {
        return Models.get(InstanceModel.class).findById(instanceId)
            .get(InstanceModel.STATUS_OBSERVED_AT);
    }

    private static List<Row> activity(int instanceId, String action) {
        return Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ActivityModel.RECORD_ID.eq(String.valueOf(instanceId)))
            .where(ActivityModel.ACTION.eq(action))
            .orderBy(ActivityModel.ID, SortOrder.DESC)
            .all();
    }
}
