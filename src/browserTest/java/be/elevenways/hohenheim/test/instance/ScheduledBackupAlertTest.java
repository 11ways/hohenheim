package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.schedule.InstanceBackupAction;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.comms.CommsChannel;
import be.elevenways.zenit.comms.server.Comms;
import be.elevenways.zenit.comms.server.CommsDeliveryModel;
import be.elevenways.zenit.comms.server.CommsDispatcher;
import be.elevenways.zenit.comms.server.transport.TransportTypes;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import be.elevenways.zenit.common.task.record.RecordScheduleRuns;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.common.task.record.RunStatus;
import be.elevenways.zenit.common.task.record.StepFailurePolicy;
import be.elevenways.zenit.common.task.record.StepStatus;
import be.elevenways.zenit.server.task.record.RecordSchedules;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A scheduled backup step still alerts the operator when it fails, now that schedule steps are stored due rows run
 * by the sweep on their own threads (contract 5.6, plan 5.6 must-hold).
 *
 * AIDEV-NOTE: the reaped UNKNOWN backup alert (contract step 4) needs the backup step to run an operation whose
 * schedule placement carries the failure hook (6.7, slice one); until then a reaped step is logged as
 * {@code zenit.schedule.step_outcome_unknown} by core and no Hohenheim alert is sent for it.
 */
class ScheduledBackupAlertTest {

    private static final List<String> BODIES = new CopyOnWriteArrayList<>();

    private HttpServer receiver;

    @BeforeAll
    static void boot() throws Exception {
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @BeforeEach
    void setUp() throws Exception {
        BODIES.clear();
        Models.get(NotificationChannelModel.class).find().delete();
        Models.get(CommsDeliveryModel.class).find().delete();
        Comms.install(new CommsDispatcher(Map.of(
            CommsChannel.WEBHOOK, List.of(TransportTypes.create("webhook://default"))), 1, true));

        this.receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.receiver.createContext("/", exchange -> {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            exchange.getRequestBody().transferTo(body);
            BODIES.add(body.toString());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        this.receiver.start();

        NotificationChannelModel channels = Models.get(NotificationChannelModel.class);
        Row channel = channels.createEmptyRow();
        channel.set(NotificationChannelModel.NAME, "ops");
        channel.set(NotificationChannelModel.KIND, NotificationChannelModel.KIND_WEBHOOK);
        channel.set(NotificationChannelModel.FORMAT, NotificationChannelModel.FORMAT_GENERIC);
        channel.set(NotificationChannelModel.URL,
            "http://127.0.0.1:" + this.receiver.getAddress().getPort() + "/alerts");
        channels.save(channel);
    }

    @AfterEach
    void tearDown() {
        Comms.install(null);
        this.receiver.stop(0);
    }

    @Test
    void aFailingScheduledBackupAlertsTheOperatorJourney() {
        // 1. A due schedule with a backup step against an instance whose backup cannot run (it does not exist).
        int missingInstance = 987_654;
        RecordScheduleModel schedules = Models.get(RecordScheduleModel.class);
        Row schedule = schedules.createEmptyRow();
        schedule.set(RecordScheduleModel.MODEL, InstanceModel.MODEL_ID.toString());
        schedule.set(RecordScheduleModel.RECORD_ID, String.valueOf(missingInstance));
        schedule.set(RecordScheduleModel.NAME, "nightly backup");
        schedule.set(RecordScheduleModel.CRON, "0 3 * * *");
        schedule.set(RecordScheduleModel.ENABLED, true);
        schedule.set(RecordScheduleModel.NEXT_FIRE_AT, Now.instant().minusSeconds(60));
        schedules.save(schedule);
        int scheduleId = schedule.get(RecordScheduleModel.ID);

        RecordScheduleStepModel steps = Models.get(RecordScheduleStepModel.class);
        Row step = steps.createEmptyRow();
        step.set(RecordScheduleStepModel.SCHEDULE_ID, scheduleId);
        step.set(RecordScheduleStepModel.POSITION, 1);
        step.set(RecordScheduleStepModel.ACTION, InstanceBackupAction.ID.toString());
        step.set(RecordScheduleStepModel.FAILURE_POLICY, StepFailurePolicy.ABORT.storageKey());
        steps.save(step);

        // 2. The framework sweep runs it on its own thread and the step ends failed.
        new RecordSchedules(Datasources.getDefault()).runDue(null);
        assertThat(RecordSchedules.awaitRunningSteps(Duration.ofSeconds(30))).as("step 2: the step ended").isTrue();
        Row run = Models.get(RecordScheduleRunModel.class).find().noCache()
            .where(RecordScheduleRunModel.SCHEDULE_ID.eq(scheduleId)).first();
        assertThat(run).as("step 2: the sweep opened a run").isNotNull();
        assertThat(run.get(RecordScheduleRunModel.STATUS)).as("step 2: the run aborted on the failed backup")
            .isEqualTo(RunStatus.ABORTED.storageKey());
        assertThat(RecordScheduleRuns.steps(run).get(0).status()).as("step 2: the backup step failed")
            .isEqualTo(StepStatus.FAILED);

        // 3. The operator's alert channel received the instance backup failure.
        assertThat(BODIES).as("step 3: one alert reached the channel").hasSize(1);
        assertThat(BODIES.get(0)).as("step 3: it is the instance backup failure")
            .contains("\"event\":\"backup_failed\"")
            .contains("instance_backup_failed_subject");
    }
}
