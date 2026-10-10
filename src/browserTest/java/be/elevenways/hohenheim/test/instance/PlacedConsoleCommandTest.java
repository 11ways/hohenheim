package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.server.cms.InstanceParts;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.PlacedActionClicks;
import be.elevenways.hohenheim.test.Poll;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.common.operation.PlacementSurface;
import be.elevenways.zenit.common.operation.ZenitPlacementSurface;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepRunModel;
import be.elevenways.zenit.common.task.record.StepFailurePolicy;
import be.elevenways.zenit.common.task.record.StepStatus;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.server.task.record.RecordSchedules;
import be.elevenways.zenit.test.support.TestAccessContexts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The console line is one placed operation: both instance panels place it with the line asked in its dialog (the
 * console tab posts to the same invoke), the admin click, the API and a schedule step each send the line and record it
 * once, a blank line is refused by the form, and a product-generated instance is refused on every surface.
 *
 * AIDEV-NOTE: daemon-free, over {@link FakeNativeDaemons}' recorded console stdin; the surfaces are driven through the
 * pipeline exactly as the invoke route, {@code InstanceApi} and the schedule runner build their request.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class PlacedConsoleCommandTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final String HOST = "placed-console-host";

    private static SqlDatasource datasource;
    private static int hostId;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        FakeNativeDaemons.register();
        Db.run(datasource, () -> hostId = HostFixtures.admittedIncusHost(HOST));
    }

    @AfterEach
    void closeStreams() {
        FakeNativeDaemons.resetStreams();
    }

    @Test
    void theConsoleLineIsOnePlacedOperationOnEverySurface() {
        Db.run(datasource, () -> {
            // 1. Both instance panels place the operation and ask its line.
            PanelAction<Row> onAdmin = PlacedActionClicks.placed(InstanceParts.admin(),
                "console_command_instance");
            PanelAction<Row> onManage = PlacedActionClicks.placed(InstanceParts.manage(),
                "console_command_instance");
            assertThat(onAdmin.operation()).as("step 1: the admin places the console operation")
                .isEqualTo(InstanceOperations.CONSOLE_COMMAND);
            assertThat(onManage.operation()).as("step 1: and so does /manage")
                .isEqualTo(InstanceOperations.CONSOLE_COMMAND);
            assertThat(onAdmin.asksInput()).as("step 1: the line is asked in the action's form").isTrue();

            // 2. A running authored instance: the admin click and the API each send their line once and record it.
            int instanceId = instanceRecord("placed-console");
            new InstanceService().deploy(instanceId);
            Row row = Models.get(InstanceModel.class).findById(instanceId);
            send(CmsPlacementSurface.ADMIN_ACTION, row, "say admin");
            send(ZenitPlacementSurface.HTTP_API, row, "say api");
            FakeNativeDaemons.ScriptedStream stream =
                FakeNativeDaemons.CONSOLE_STREAMS.get(FakeNativeDaemons.handleOf(instanceId));
            assertThat(stream).as("step 2: the console session is open").isNotNull();
            Poll.until("step 2: both lines reached stdin", WAIT, () -> stream.stdinWrites().size() >= 2);
            assertThat(String.join("", stream.stdinWrites())).as("step 2: each line once, in order")
                .containsSubsequence("say admin", "say api");
            assertThat(consoleRows(instanceId)).as("step 2: one activity row per line").isEqualTo(2);

            // 3. A schedule step sends its stored line through the same operation, recorded the same way.
            int runAs = ApiSupport.user("placed-console-runner@test", "Console Runner");
            RecordGrants.grant(GrantSubjectType.USER, runAs, InstanceModel.MODEL_ID, instanceId,
                HohenheimCapabilities.MANAGE, true);
            int schedule = schedule(instanceId, (long) runAs);
            step(schedule, Map.of(InstanceOperations.COMMAND.getName(), "say scheduled"));
            Row run = new RecordSchedules(datasource).runNow(schedule);
            Row stepRun = Models.get(RecordScheduleStepRunModel.class).findForRun(run.get(RecordScheduleRunModel.ID))
                .get(0);
            assertThat(stepRun.get(RecordScheduleStepRunModel.STATUS)).as("step 3: the scheduled line ran")
                .isEqualTo(StepStatus.OK.storageKey());
            Poll.until("step 3: the scheduled line reached stdin", WAIT, () -> stream.stdinWrites().size() >= 3);
            assertThat(consoleRows(instanceId)).as("step 3: and was recorded once").isEqualTo(3);

            // 4. A blank line is refused by the form before anything is sent or recorded.
            assertThat(catchThrowable(() -> send(CmsPlacementSurface.ADMIN_ACTION, row, "")))
                .as("step 4: the form refuses a blank line").isInstanceOf(Violations.class);
            assertThat(stream.stdinWrites()).as("step 4: nothing more reached stdin").hasSize(3);
            assertThat(consoleRows(instanceId)).as("step 4: nor the activity log").isEqualTo(3);

            // 5. A product-generated instance is refused on every surface: no offer, no invoke, nothing recorded.
            int generatedId = generatedInstanceRecord("placed-console-generated");
            Row generated = Models.get(InstanceModel.class).findById(generatedId);
            assertThat(OperationPipeline.offer(InstanceOperations.CONSOLE_COMMAND, TestAccessContexts.allAllowed(),
                    generated))
                .as("step 5: the admin offers nothing on a generated instance")
                .isNotInstanceOf(OperationPipeline.Offer.Available.class);
            for (PlacementSurface surface : List.of(CmsPlacementSurface.ADMIN_ACTION, ZenitPlacementSurface.HTTP_API,
                    ZenitPlacementSurface.SCHEDULE_STEP)) {
                assertThat(catchThrowable(() -> send(surface, generated, "say generated")))
                    .as("step 5: %s refuses a generated instance", surface.id()).isInstanceOf(DomainRefusal.class);
            }
            assertThat(consoleRows(generatedId)).as("step 5: and nothing was recorded on it").isZero();
        });
    }

    /** The request every surface builds: the caller, the one instance and the line as the form's value. */
    private static void send(PlacementSurface surface, Row instance, String line) {
        OperationPipeline.invoke(OperationRequest.of(InstanceOperations.CONSOLE_COMMAND, surface)
            .caller(TestAccessContexts.allAllowed())
            .subjects(List.of(instance))
            .form(Map.of(InstanceOperations.COMMAND.getName(), line)));
    }

    private static int consoleRows(int instanceId) {
        return (int) Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ActivityModel.RECORD_ID.eq(String.valueOf(instanceId)))
            .where(ActivityModel.ACTION.eq(HohenheimActivityAction.CONSOLE_COMMAND.id().toString()))
            .count();
    }

    private static int instanceRecord(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "fake/image")));
        row.set(InstanceModel.SERVER_ID, hostId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    /** An instance a product tier generated: only the generated-row lane may stamp that attribution. */
    private static int generatedInstanceRecord(String name) {
        int[] id = new int[1];
        try {
            GeneratedRows.as(new GeneratedRows.Attribution("test:product", InstanceModel.MODEL_ID.toString(), null),
                () -> id[0] = instanceRecord(name));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return id[0];
    }

    private static int schedule(int instanceId, Long runAs) {
        Model schedules = Models.get(RecordScheduleModel.class);
        Row schedule = schedules.createEmptyRow();
        schedule.set(RecordScheduleModel.MODEL, InstanceModel.MODEL_ID.toString());
        schedule.set(RecordScheduleModel.RECORD_ID, String.valueOf(instanceId));
        schedule.set(RecordScheduleModel.NAME, "scheduled console line");
        schedule.set(RecordScheduleModel.CRON, "0 4 * * *");
        schedule.set(RecordScheduleModel.ENABLED, true);
        schedule.set(RecordScheduleModel.NEXT_FIRE_AT, Now.instant().plusSeconds(86_400));
        schedule.set(RecordScheduleModel.RUN_AS, runAs);
        schedules.save(schedule);
        return schedule.get(RecordScheduleModel.ID);
    }

    private static void step(int scheduleId, Map<String, Object> input) {
        Model steps = Models.get(RecordScheduleStepModel.class);
        Row step = steps.createEmptyRow();
        step.set(RecordScheduleStepModel.SCHEDULE_ID, scheduleId);
        step.set(RecordScheduleStepModel.POSITION, 1);
        step.set(RecordScheduleStepModel.ACTION, InstanceOperations.CONSOLE_COMMAND.id().toString());
        step.set(RecordScheduleStepModel.INPUT, input);
        step.set(RecordScheduleStepModel.FAILURE_POLICY, StepFailurePolicy.ABORT.storageKey());
        step.set(RecordScheduleStepModel.RETRY_LIMIT, 3);
        steps.save(step);
    }
}
