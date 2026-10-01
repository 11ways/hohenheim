package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.schedule.ScheduleRunView;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.InstanceScheduleRunResource;
import be.elevenways.hohenheim.server.cms.InstanceScheduleStepsPage;
import be.elevenways.hohenheim.server.cms.ManageInstanceScheduleResource;
import be.elevenways.hohenheim.server.cms.ManageInstanceScheduleStepResource;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepRunModel;
import be.elevenways.zenit.common.task.record.StepStatus;
import be.elevenways.zenit.server.task.record.RecordSchedules;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schedule surface's authority edges: firing a schedule off-cron and the
 * synthesized edit/delete affordances both follow {@code CONFIG} on the schedule's
 * target instance -- the same verb every schedule WRITE already enforced.
 *
 * Two defects pinned here. The run-now row action used to gate its visibility on
 * ENABLED alone and its handler on nothing, so a VIEW-only delegate could fire another
 * tenant's schedule off-cron (execution stayed authorized against the stored
 * {@code run_as}, so the net effect was off-schedule triggering plus failed-run debris
 * -- contained, but an act the viewer held no verb for). And the list offered Edit and
 * Delete affordances to that same delegate, every submit refused by
 * {@code requireManage} -- the InstanceDeviceResource affordance lesson, unapplied.
 */
class InstanceScheduleSurfaceTest extends HohenheimTestBase {

    private static final String PREFIX = "schedsurf-";

    /** A step action nothing registers: the step ends unrun deterministically, without a daemon. */
    private static final String UNKNOWN_ACTION = "hohenheim:schedsurf_no_such_action";

    private static Integer ownerId;
    private static Integer viewerId;
    private static TestSession viewerHttp;

    private static Integer instanceId;
    private static Integer scheduleId;
    private static Integer stepId;

    @BeforeAll
    static void seed() {
        ownerId = ApiSupport.user("schedsurf-owner@surface.test", "Schedule Owner");
        viewerId = ApiSupport.user("schedsurf-viewer@surface.test", "Schedule Viewer");

        viewerHttp = sessionFor(viewerId);

        Model instances = Models.get(InstanceModel.class);
        Row instance = instances.createEmptyRow();
        instance.set(InstanceModel.NAME, PREFIX + "target");
        instance.set(InstanceModel.KIND, "hohenheim:docker_container");
        instance.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        instance.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        instances.save(instance);
        instanceId = instance.get(InstanceModel.ID);

        RecordGrants.grant(GrantSubjectType.USER, ownerId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, viewerId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.VIEW, true);

        Model schedules = Models.get(RecordScheduleModel.class);
        Row schedule = schedules.createEmptyRow();
        schedule.set(RecordScheduleModel.MODEL, InstanceModel.MODEL_ID.toString());
        schedule.set(RecordScheduleModel.RECORD_ID, String.valueOf(instanceId));
        schedule.set(RecordScheduleModel.NAME, PREFIX + "nightly");
        schedule.set(RecordScheduleModel.CRON, "0 4 * * *");
        schedule.set(RecordScheduleModel.ENABLED, true);
        schedule.set(RecordScheduleModel.RUN_AS, ownerId.longValue());
        schedules.save(schedule);
        scheduleId = schedule.get(RecordScheduleModel.ID);

        Model steps = Models.get(RecordScheduleStepModel.class);
        Row step = steps.createEmptyRow();
        step.set(RecordScheduleStepModel.SCHEDULE_ID, scheduleId);
        step.set(RecordScheduleStepModel.POSITION, 1);
        step.set(RecordScheduleStepModel.ACTION, "zenit:power_stop");
        steps.save(step);
        stepId = step.get(RecordScheduleStepModel.ID);
    }

    @AfterAll
    static void cleanUp() {
        if (stepId != null) {
            Models.get(RecordScheduleStepModel.class).delete(stepId);
        }
        if (scheduleId != null) {
            Model runs = Models.get(RecordScheduleRunModel.class);
            for (Row run : runs.find()
                    .where(RecordScheduleRunModel.SCHEDULE_ID.eq(scheduleId)).all()) {
                runs.delete(run.get(RecordScheduleRunModel.ID));
            }
            Models.get(RecordScheduleModel.class).delete(scheduleId);
        }
        if (instanceId != null) {
            HardDeletes.byId(Models.get(InstanceModel.class), instanceId);
        }
    }

    private static AccessContext contextOf(int userId, String name) {
        return AccessContext.of(TenantConduits.stubFor(new UserPrincipal(userId, name)));
    }

    /**
     * Run-now is offered exactly where a CONFIG holder stands, and a forged invoke by a
     * viewer reads as MISSING -- the dispatcher re-checks visibleFor on invoke -- while
     * the handler's own gate backstops it. STATE is the proof: no run row appears.
     */
    @Test
    void runNowIsOfferedAndInvocableOnlyWithConfig() throws Exception {
        Row schedule = Models.get(RecordScheduleModel.class).findById(scheduleId);
        RowAction.Invoke<Row> runNow = null;
        for (RowAction<Row> action : new ManageInstanceScheduleResource().rowActions()) {
            if ("run_schedule".equals(action.id().getPath())
                    && action instanceof RowAction.Invoke<Row> invoke) {
                runNow = invoke;
            }
        }
        assertThat(runNow).as("step 1: the run-now action exists").isNotNull();

        // 1. The affordance follows the capability, not merely ENABLED.
        assertThat(runNow.isVisibleFor(schedule, contextOf(viewerId, "Schedule Viewer")))
            .as("step 1: a view-only delegate is not offered run-now").isFalse();
        assertThat(runNow.isVisibleFor(schedule, contextOf(ownerId, "Schedule Owner")))
            .as("step 1: while the manage holder (CONFIG implied) is").isTrue();

        // 2. A forged invoke by the viewer is 404 -- hidden action reads as missing on
        //    invoke too, never a capability oracle.
        long runsBefore = Models.get(RecordScheduleRunModel.class).find()
            .where(RecordScheduleRunModel.SCHEDULE_ID.eq(scheduleId)).count();
        HttpResponse<String> forged = httpPostForm(
            "/manage/instance-schedules/" + scheduleId + "/action/run_schedule", "",
            viewerHttp.token(), viewerHttp.csrf());
        assertThat(forged.statusCode())
            .as("step 2: a view-only delegate's forged run-now reads as missing")
            .isEqualTo(404);

        // 3. STATE: nothing fired. A refusal that had already started the chain would
        //    pass step 2 and still be the defect.
        assertThat(Models.get(RecordScheduleRunModel.class).find()
                .where(RecordScheduleRunModel.SCHEDULE_ID.eq(scheduleId)).count())
            .as("step 3: no run row was minted by the refused invoke")
            .isEqualTo(runsBefore);
    }

    /**
     * The affordance half: edit/delete on schedules AND their steps are offered exactly
     * where CONFIG holds, and track the live grant graph.
     */
    @Test
    void scheduleAndStepAffordancesFollowConfig() {
        Row schedule = Models.get(RecordScheduleModel.class).findById(scheduleId);
        Row step = Models.get(RecordScheduleStepModel.class).findById(stepId);
        ManageInstanceScheduleResource scheduleResource = new ManageInstanceScheduleResource();
        ManageInstanceScheduleStepResource stepResource = new ManageInstanceScheduleStepResource();

        AccessContext viewer = contextOf(viewerId, "Schedule Viewer");
        AccessContext owner = contextOf(ownerId, "Schedule Owner");

        // 1. THE PREMISE: the viewer's read scope really does include this schedule, so
        //    an absent affordance below is a WRITE decision and not invisibility.
        assertThat(scheduleResource.accessFunction().decide(viewer).isDenied())
            .as("step 1: the viewer's schedule read scope is an allow").isFalse();

        // 2. Withheld from view-only; offered to the manage holder. Both resources.
        assertThat(scheduleResource.updatableBy(schedule, viewer))
            .as("step 2: a view-only delegate gets no schedule edit affordance").isFalse();
        assertThat(scheduleResource.deletableBy(schedule, viewer))
            .as("step 2: nor a schedule delete button").isFalse();
        assertThat(stepResource.updatableBy(step, viewer))
            .as("step 2: nor a step edit affordance").isFalse();
        assertThat(stepResource.deletableBy(step, viewer))
            .as("step 2: nor a step delete button").isFalse();

        assertThat(scheduleResource.updatableBy(schedule, owner))
            .as("step 2: the manage holder keeps its schedule edit affordance").isTrue();
        assertThat(scheduleResource.deletableBy(schedule, owner))
            .as("step 2: and its delete button").isTrue();
        assertThat(stepResource.updatableBy(step, owner))
            .as("step 2: and the step's edit affordance").isTrue();
        assertThat(stepResource.deletableBy(step, owner))
            .as("step 2: and the step's delete button").isTrue();

        // 3. Revocation withdraws them: the answer tracks the live grant graph.
        //    revoke, never grant(false) -- a planted deny is STICKY (deny beats a later
        //    allow), so grant(false) would poison the owner for every later test.
        RecordGrants.revoke(GrantSubjectType.USER, ownerId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.MANAGE);
        try {
            AccessContext revoked = contextOf(ownerId, "Schedule Owner");
            assertThat(scheduleResource.updatableBy(schedule, revoked))
                .as("step 3: a revoked grant withdraws the schedule edit affordance")
                .isFalse();
            assertThat(stepResource.deletableBy(step, revoked))
                .as("step 3: and the step delete button").isFalse();
        } finally {
            RecordGrants.grant(GrantSubjectType.USER, ownerId, InstanceModel.MODEL_ID, instanceId,
                HohenheimAccess.MANAGE, true);
        }
    }

    /**
     * New runs record their steps as step-run rows and no longer write {@code step_results}: both screens that show a
     * run's steps (the runs list column and the Steps tab) read them through {@code RecordScheduleRuns.steps}, so a
     * new run never shows as an empty step list.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aNewRunShowsItsStepsOnBothScreens() {
        RecordSchedules schedules = new RecordSchedules(Db.currentOrDefault());
        Model scheduleModel = Models.get(RecordScheduleModel.class);
        Row schedule = scheduleModel.createEmptyRow();
        schedule.set(RecordScheduleModel.MODEL, InstanceModel.MODEL_ID.toString());
        schedule.set(RecordScheduleModel.RECORD_ID, String.valueOf(instanceId));
        schedule.set(RecordScheduleModel.NAME, PREFIX + "steps-read");
        schedule.set(RecordScheduleModel.CRON, "0 4 * * *");
        schedule.set(RecordScheduleModel.ENABLED, true);
        schedule.set(RecordScheduleModel.RUN_AS, ownerId.longValue());
        scheduleModel.save(schedule);
        int readScheduleId = schedule.get(RecordScheduleModel.ID);

        try {
            Row step = Models.get(RecordScheduleStepModel.class).createEmptyRow();
            step.set(RecordScheduleStepModel.SCHEDULE_ID, readScheduleId);
            step.set(RecordScheduleStepModel.POSITION, 1);
            step.set(RecordScheduleStepModel.ACTION, UNKNOWN_ACTION);
            Models.get(RecordScheduleStepModel.class).save(step);

            // 1. A run fired now: its one step ends unrun (no such action), recorded as a step-run row only.
            Row run = schedules.runNow(readScheduleId);
            assertThat(run).as("step 1: the schedule ran").isNotNull();
            assertThat(run.get(RecordScheduleRunModel.STEP_RESULTS))
                .as("step 1: a new run writes no step_results map, so a reader of it would show nothing")
                .isNull();
            Row stepRun = Models.get(RecordScheduleStepRunModel.class).find()
                .where(RecordScheduleStepRunModel.RUN_ID.eq((Integer) run.get(RecordScheduleRunModel.ID))).first();
            assertThat(stepRun).as("step 1: the step's outcome is a step-run row").isNotNull();
            String status = stepRun.get(RecordScheduleStepRunModel.STATUS);
            String error = stepRun.get(RecordScheduleStepRunModel.ERROR);
            assertThat(StepStatus.fromKey(status).isOpen()).as("step 1: the step ended").isFalse();
            assertThat(error).as("step 1: and says why").isNotBlank();
            String expected = "1:" + UNKNOWN_ACTION + "=" + status + " (" + error + ")";

            // 2. The runs list shows the step's verdict in its steps column.
            InstanceScheduleRunResource runResource = new InstanceScheduleRunResource();
            ColumnSpec stepsColumn = runResource.tableSpec().column("steps");
            assertThat(stepsColumn).as("step 2: the runs list declares a steps column").isNotNull();
            assertThat(runResource.cellValue(run, stepsColumn)).as("step 2: the runs list shows the step's verdict")
                .isEqualTo(expected);

            // 3. The schedule's Steps tab shows the same verdict for that run.
            Conduit conduit = TenantConduits.stubFor(new UserPrincipal(ownerId, "Schedule Owner"));
            Map<String, Object> vars = (Map<String, Object>) new InstanceScheduleStepsPage()
                .render(conduit, AccessContext.of(conduit), scheduleModel.findById(readScheduleId)).get();
            List<ScheduleRunView> runs = (List<ScheduleRunView>) vars.get("runs");
            int runId = run.get(RecordScheduleRunModel.ID);
            assertThat(runs).as("step 3: the Steps tab lists the run with its steps")
                .filteredOn(view -> view.id() == runId)
                .singleElement()
                .extracting(ScheduleRunView::summary)
                .isEqualTo(expected);
        } finally {
            schedules.deleteSchedule(readScheduleId);
        }
    }

    /**
     * The grantScope refactor's tri-state translation, pinned per refactored resource:
     * an unconstrained walk answer (the admin row today; an instances-wide type-level
     * row the day one lands) maps to an unconstrained decision WITHOUT enumerating --
     * the enumeration is the spelling that THROWS on a whole-model scope.
     */
    @Test
    void anUnconstrainedWalkAnswerNeverEnumerates() {
        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        AccessContext operator = contextOf(admin.get(UserModel.ID), "Test Admin");
        AccessContext viewer = contextOf(viewerId, "Schedule Viewer");

        // Every refactored resource must survive an ALL answer AND keep scoping a
        // grant-holding tenant. accessFunction() throwing here is exactly the 500 the
        // hand-rolled idiom would produce once a type-level row exists.
        for (var resource : new be.elevenways.zenit.cms.common.resource.RowResource[] {
                new ManageInstanceScheduleResource(),
                new be.elevenways.hohenheim.server.cms.ManageInstanceSnapshotResource(),
                new be.elevenways.hohenheim.server.cms.ManageInstanceBackupResource(),
                new be.elevenways.hohenheim.server.cms.ManageInstanceDeviceResource(),
                new be.elevenways.hohenheim.server.cms.ManageInstanceDatabaseResource(),
                new ManageInstanceScheduleStepResource()}) {
            assertThat(resource.accessFunction().decide(operator).isDenied())
                .as("%s translates ALL without enumerating", resource.id()).isFalse();
            assertThat(resource.accessFunction().decide(viewer).isDenied())
                .as("%s answers a tenant without throwing", resource.id()).isFalse();
        }
    }

}
