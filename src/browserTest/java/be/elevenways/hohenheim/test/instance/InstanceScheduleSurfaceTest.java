package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.server.cms.InstanceAttachmentParts;
import be.elevenways.hohenheim.test.PanelEntryViews;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.schedule.ScheduleRunView;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.InstanceBackupParts;
import be.elevenways.hohenheim.server.cms.InstanceSnapshotParts;
import be.elevenways.hohenheim.server.cms.InstanceScheduleRunParts;
import be.elevenways.hohenheim.server.cms.InstanceScheduleStepsPage;
import be.elevenways.hohenheim.instance.InstanceScheduleOperations;
import be.elevenways.hohenheim.server.cms.InstanceScheduleParts;
import be.elevenways.hohenheim.server.cms.InstanceScheduleStepParts;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.zenit.common.edit.Discriminated;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.edit.EditContext;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.PlacementSurfaces;
import be.elevenways.zenit.common.operation.ZenitPlacementSurface;
import be.elevenways.zenit.common.orm.field.RegistryMemberField;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.server.panel.PartsLists;
import be.elevenways.zenit.cms.server.panel.PartsReads;
import be.elevenways.zenit.cms.server.panel.ResourceVerbs;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceVerb;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
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
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.server.operation.OperationPipeline;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * {@code requireManage} -- the InstanceAttachmentParts.devicesAdmin() affordance lesson, unapplied.
 */
class InstanceScheduleSurfaceTest extends HohenheimTestBase {
    @Test
    void adminAndTenantStepEditorsDeclareTheSamePlacedOperationInput() {
        // 1. Both existing resources retain their registration and authority, sharing the reusable input entry.
        for (PanelResource<Row> parts : List.of(InstanceScheduleStepParts.admin(), InstanceScheduleStepParts.manage())) {
            FormSpec spec = Objects.requireNonNull(parts.form()).spec();
            Discriminated input = (Discriminated) spec.findEntry("input");
            assertThat(input).as("step 1: the existing step surface declares operation input").isNotNull();
            assertThat(input.field()).as("step 1: input uses the existing encrypted stored-input field")
                .isSameAs(RecordScheduleStepModel.INPUT);
            assertThat(input.discriminator()).as("step 1: the action selects its declared operation form")
                .isEqualTo(RecordScheduleStepModel.ACTION.getName());
            assertThat(spec.findEntry("payload")).as("step 1: this editor no longer draws legacy payload").isNull();

            // 2. Every offered action is an operation placed on the scheduler's existing surface.
            Select<?> actions = (Select<?>) spec.findEntry("action");
            for (var option : actions.options().resolve(EditContext.of(AccessContext.anonymous()))) {
                var member = ((RegistryMemberField) RecordScheduleStepModel.ACTION).memberFor(String.valueOf(option.value()));
                assertThat(member).as("step 2: no legacy action is offered by the operation input editor").isInstanceOf(Operation.class);
                assertThat(PlacementSurfaces.isPlaced(ZenitPlacementSurface.SCHEDULE_STEP, ((Operation<?, ?, ?>) member).id()))
                    .as("step 2: option belongs to the schedule placement").isTrue();
            }
        }
    }

    private static final String PREFIX = "schedsurf-";

    /** A step action nothing registers: the operation pipeline records its failed attempt without a daemon. */
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
        assertThat(InstanceScheduleParts.manage().actions()).as("step 1: the run-now operation is placed")
            .anyMatch(action -> action.id().equals(InstanceScheduleOperations.RUN_SCHEDULE.id()));

        // 1. The affordance follows the capability, not merely ENABLED.
        assertThat(OperationPipeline.offer(InstanceScheduleOperations.RUN_SCHEDULE,
                contextOf(viewerId, "Schedule Viewer"), schedule))
            .as("step 1: a view-only delegate is not offered run-now").isInstanceOf(OperationPipeline.Offer.Hidden.class);
        assertThat(OperationPipeline.offer(InstanceScheduleOperations.RUN_SCHEDULE,
                contextOf(ownerId, "Schedule Owner"), schedule))
            .as("step 1: while the manage holder (CONFIG implied) is")
            .isInstanceOf(OperationPipeline.Offer.Available.class);

        // 2. A forged invoke by the viewer is 404 -- hidden action reads as missing on
        //    invoke too, never a capability oracle.
        long runsBefore = Models.get(RecordScheduleRunModel.class).find()
            .where(RecordScheduleRunModel.SCHEDULE_ID.eq(scheduleId)).count();
        HttpResponse<String> forged = httpPostForm(
            "/manage/instance-schedules/invoke/hohenheim.run_schedule?ids=" + scheduleId, "",
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

        // 4. Another model's schedule whose record id happens to be this instance's id (a preview's expiry shares
        //    the table) is no instance schedule: the instance's manager is offered none of its verbs.
        Model schedules = Models.get(RecordScheduleModel.class);
        Row foreign = schedules.createEmptyRow();
        foreign.set(RecordScheduleModel.MODEL, PreviewDeploymentModel.MODEL_ID.toString());
        foreign.set(RecordScheduleModel.RECORD_ID, String.valueOf(instanceId));
        foreign.set(RecordScheduleModel.NAME, PREFIX + "foreign");
        foreign.set(RecordScheduleModel.CRON, "0 5 * * *");
        foreign.set(RecordScheduleModel.ENABLED, true);
        schedules.save(foreign);
        Integer foreignId = foreign.get(RecordScheduleModel.ID);
        Model steps = Models.get(RecordScheduleStepModel.class);
        Row foreignStep = steps.createEmptyRow();
        foreignStep.set(RecordScheduleStepModel.SCHEDULE_ID, foreignId);
        foreignStep.set(RecordScheduleStepModel.POSITION, 1);
        foreignStep.set(RecordScheduleStepModel.ACTION, "zenit:power_stop");
        steps.save(foreignStep);
        try {
            AccessContext owner = contextOf(ownerId, "Schedule Owner");
            assertThat(OperationPipeline.offer(InstanceScheduleOperations.RUN_SCHEDULE, owner, foreign))
                .as("step 4: another model's schedule is not run as the instance's")
                .isInstanceOf(OperationPipeline.Offer.Hidden.class);
            assertThat(OperationPipeline.offer(InstanceScheduleOperations.DELETE_SCHEDULE, owner, foreign))
                .as("step 4: nor deleted as the instance's")
                .isInstanceOf(OperationPipeline.Offer.Hidden.class);
            assertThat(OperationPipeline.offer(InstanceScheduleOperations.DELETE_STEP, owner, foreignStep))
                .as("step 4: nor is its step removed as the instance's")
                .isInstanceOf(OperationPipeline.Offer.Hidden.class);
        } finally {
            steps.delete(foreignStep.get(RecordScheduleStepModel.ID));
            schedules.delete(foreignId);
        }
    }

    /**
     * The affordance half: edit/delete on schedules AND their steps are offered exactly
     * where CONFIG holds, and track the live grant graph.
     */
    @Test
    void scheduleAndStepAffordancesFollowConfig() {
        Row schedule = Models.get(RecordScheduleModel.class).findById(scheduleId);
        Row step = Models.get(RecordScheduleStepModel.class).findById(stepId);
        PanelResource<Row> scheduleResource = PanelEntryViews.of(ManagePanel.SLUG, InstanceScheduleParts.SLUG);
        PanelResource<Row> stepResource = PanelEntryViews.of(ManagePanel.SLUG, InstanceScheduleStepParts.SLUG);
        Panel panel = PanelRegistry.getBySlug(ManagePanel.SLUG);

        AccessContext viewer = contextOf(viewerId, "Schedule Viewer");
        AccessContext owner = contextOf(ownerId, "Schedule Owner");

        // 1. THE PREMISE: the viewer's read scope really does include this schedule, so
        //    an absent affordance below is a WRITE decision and not invisibility.
        assertThat(PartsReads.<Row>loadRow(new PanelRequest(panel, viewer.conduit(), viewer, null), scheduleResource,
            scheduleId, viewer))
            .as("step 1: the viewer's schedule read scope is an allow").isNotNull();

        // 2. Withheld from view-only; offered to the manage holder. Both resources.
        assertThat(ResourceVerbs.permitsBy(panel, scheduleResource, ResourceVerb.UPDATE, schedule, viewer))
            .as("step 2: a view-only delegate gets no schedule edit affordance").isFalse();
        assertThat(ResourceVerbs.permitsBy(panel, scheduleResource, ResourceVerb.DELETE, schedule, viewer))
            .as("step 2: nor a schedule delete button").isFalse();
        assertThat(ResourceVerbs.permitsBy(panel, stepResource, ResourceVerb.UPDATE, step, viewer))
            .as("step 2: nor a step edit affordance").isFalse();
        assertThat(ResourceVerbs.permitsBy(panel, stepResource, ResourceVerb.DELETE, step, viewer))
            .as("step 2: nor a step delete button").isFalse();

        assertThat(ResourceVerbs.permitsBy(panel, scheduleResource, ResourceVerb.UPDATE, schedule, owner))
            .as("step 2: the manage holder keeps its schedule edit affordance").isTrue();
        assertThat(ResourceVerbs.permitsBy(panel, scheduleResource, ResourceVerb.DELETE, schedule, owner))
            .as("step 2: and its delete button").isTrue();
        assertThat(ResourceVerbs.permitsBy(panel, stepResource, ResourceVerb.UPDATE, step, owner))
            .as("step 2: and the step's edit affordance").isTrue();
        assertThat(ResourceVerbs.permitsBy(panel, stepResource, ResourceVerb.DELETE, step, owner))
            .as("step 2: and the step's delete button").isTrue();

        // 3. Revocation withdraws them: the answer tracks the live grant graph.
        //    revoke, never grant(false) -- a planted deny is STICKY (deny beats a later
        //    allow), so grant(false) would poison the owner for every later test.
        RecordGrants.revoke(GrantSubjectType.USER, ownerId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.MANAGE);
        try {
            AccessContext revoked = contextOf(ownerId, "Schedule Owner");
            assertThat(ResourceVerbs.permitsBy(panel, scheduleResource, ResourceVerb.UPDATE, schedule, revoked))
                .as("step 3: a revoked grant withdraws the schedule edit affordance")
                .isFalse();
            assertThat(ResourceVerbs.permitsBy(panel, stepResource, ResourceVerb.DELETE, step, revoked))
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

            // 1. A run fired now: its one failed operation attempt is recorded as a step-run row only.
            Row run = schedules.runNow(readScheduleId);
            assertThat(run).as("step 1: the schedule ran").isNotNull();
            Row stepRun = Models.get(RecordScheduleStepRunModel.class).find()
                .where(RecordScheduleStepRunModel.RUN_ID.eq((Integer) run.get(RecordScheduleRunModel.ID))).first();
            assertThat(stepRun).as("step 1: the step's outcome is a step-run row").isNotNull();
            String status = stepRun.get(RecordScheduleStepRunModel.STATUS);
            String error = stepRun.get(RecordScheduleStepRunModel.ERROR);
            assertThat(StepStatus.fromKey(status).isOpen()).as("step 1: the step ended").isFalse();
            assertThat(error).as("step 1: and says why").isNotBlank();
            String expected = "1:" + UNKNOWN_ACTION + "=" + status + " (" + error + ")";

            // 2. The runs list shows the step's verdict in its steps column.
            PanelResource<Row> runResource = InstanceScheduleRunParts.admin();
            ColumnSpec stepsColumn = PartsLists.tableSpec(runResource).column("steps");
            assertThat(stepsColumn).as("step 2: the runs list declares a steps column").isNotNull();
            String summary = (String) PartsReads.cellValue(null, runResource, null, run, stepsColumn);
            assertThat(summary).as("step 2: the runs list retains the complete step verdict and refusal speech")
                .startsWith(expected);
            assertThat(summary).as("step 2: source step id and actual failed attempt come from the run descriptor")
                .contains("Step " + step.get(RecordScheduleStepModel.ID) + ": 1 attempts; started ", "; ended ")
                .doesNotContain("null");

            // 3. The schedule's Steps tab shows the same verdict for that run.
            Conduit conduit = TenantConduits.stubFor(new UserPrincipal(ownerId, "Schedule Owner"));
            PanelRequest request = new PanelRequest(Objects.requireNonNull(PanelRegistry.getBySlug(ManagePanel.SLUG)),
                conduit, AccessContext.of(conduit), null);
            Map<String, Object> vars = (Map<String, Object>) new InstanceScheduleStepsPage()
                .render(request, scheduleModel.findById(readScheduleId)).get();
            List<ScheduleRunView> runs = (List<ScheduleRunView>) vars.get("runs");
            int runId = run.get(RecordScheduleRunModel.ID);
            assertThat(runs).as("step 3: the Steps tab lists the run with its steps")
                .filteredOn(view -> view.id() == runId)
                .singleElement()
                .extracting(ScheduleRunView::summary)
                .isEqualTo(summary);
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
        Row admin = Models.get(UserModel.class).find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        AccessContext operator = contextOf(admin.get(UserModel.ID), "Test Admin");
        AccessContext viewer = contextOf(viewerId, "Schedule Viewer");

        // Every refactored resource must survive an ALL answer AND keep scoping a
        // grant-holding tenant. The scope throwing here is exactly the 500 the
        // hand-rolled idiom would produce once a type-level row exists.
        for (var resource : List.of(
                PanelEntryViews.of(ManagePanel.SLUG, InstanceScheduleParts.SLUG),
                PanelEntryViews.of(ManagePanel.SLUG, InstanceSnapshotParts.SLUG),
                InstanceBackupParts.manage(),
                PanelEntryViews.of(ManagePanel.SLUG, InstanceAttachmentParts.DEVICES),
                PanelEntryViews.of(ManagePanel.SLUG, InstanceAttachmentParts.DATABASES),
                PanelEntryViews.of(ManagePanel.SLUG, InstanceScheduleStepParts.SLUG))) {
            assertThat(resource.rowScope().accessCriteria(operator))
                .as("%s translates ALL without enumerating", resource.id()).isNull();
            assertThat(resource.rowScope().accessCriteria(viewer))
                .as("%s answers a scoped tenant without throwing", resource.id()).isNotNull();
        }
    }

}
