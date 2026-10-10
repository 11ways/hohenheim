package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import be.elevenways.zenit.common.task.record.RecordScheduleRuns;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.common.task.record.RunStatus;
import be.elevenways.zenit.common.task.record.StepFailurePolicy;
import be.elevenways.zenit.common.task.record.StepStatus;
import be.elevenways.zenit.comms.CommsChannel;
import be.elevenways.zenit.comms.server.Comms;
import be.elevenways.zenit.comms.server.CommsDeliveryModel;
import be.elevenways.zenit.comms.server.CommsDispatcher;
import be.elevenways.zenit.comms.server.transport.TransportTypes;
import be.elevenways.zenit.server.task.record.RecordSchedules;
import be.elevenways.zenit.test.support.OutboundFixture;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A scheduled backup still alerts the operator when it fails: the backup operation's
 * schedule placement carries the failure hook, so a failed step alerts, a step reaped with an unknown outcome alerts
 * in its own words, and a successful backup alerts nothing.
 */
class ScheduledBackupAlertTest {

    private static final List<String> BODIES = new CopyOnWriteArrayList<>();

    private static SqlDatasource datasource;
    private static BackupLaneFixture fixture;

    private HttpServer receiver;
    private OutboundFixture outbound;

    @BeforeAll
    static void boot() throws Exception {
        HohenheimEndpoints.init();
        fixture = BackupLaneFixture.install();
        datasource = fixture.datasource;
    }

    @AfterAll
    static void uninstall() {
        BackupLaneFixture.uninstall();
    }

    @BeforeEach
    void setUp() throws Exception {
        BODIES.clear();
        this.receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.receiver.createContext("/", exchange -> {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            exchange.getRequestBody().transferTo(body);
            BODIES.add(body.toString());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        this.receiver.start();
        // The comms guard refuses a loopback destination, so the channel names a public-looking host the fixture
        // routes to the receiver.
        this.outbound = OutboundFixture.route("alerts.example.test", this.receiver.getAddress().getPort());
        Db.run(datasource, () -> {
            Models.get(NotificationChannelModel.class).find().delete();
            Models.get(CommsDeliveryModel.class).find().delete();
            Comms.install(new CommsDispatcher(Map.of(
                CommsChannel.WEBHOOK, List.of(TransportTypes.create("webhook://default"))), 1, true));
            NotificationChannelModel channels = Models.get(NotificationChannelModel.class);
            Row channel = channels.createEmptyRow();
            channel.set(NotificationChannelModel.NAME, "ops");
            channel.set(NotificationChannelModel.KIND, NotificationChannelModel.KIND_WEBHOOK);
            channel.set(NotificationChannelModel.FORMAT, NotificationChannelModel.FORMAT_GENERIC);
            channel.set(NotificationChannelModel.URL, "http://alerts.example.test/alerts");
            channels.save(channel);
        });
    }

    @AfterEach
    void tearDown() {
        RecordSchedules.setBetweenStepHookForTest(null);
        Comms.install(null);
        this.outbound.close();
        this.receiver.stop(0);
    }

    @Test
    void aFailingScheduledBackupAlertsTheOperatorJourney() {
        Db.run(datasource, () -> {
            RecordSchedules schedules = new RecordSchedules(datasource);

            // 1. A due schedule with a backup_instance step on an instance whose backup cannot run: it has no backup
            //    target, so the service refuses it.
            int noTarget = BackupLaneFixture.instanceRecord("alert-no-target", fixture.hostId);
            int failing = schedule(noTarget, "nightly backup", StepFailurePolicy.ABORT, true);

            // 2. The framework sweep runs it on its own thread and the step ends failed.
            schedules.runDue(null);
            assertThat(RecordSchedules.awaitRunningSteps(Duration.ofSeconds(30))).as("step 2: the step ended")
                .isTrue();
            Row run = latestRun(failing);
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

            // 4. A controller dies after the backup step ran and before it recorded an outcome: under RETRY the
            //    reaper marks it UNKNOWN (a backup is not safe to repeat), and the alert says the outcome is unknown.
            BODIES.clear();
            int crashing = schedule(noTarget, "crashing backup", StepFailurePolicy.RETRY, false);
            RecordSchedules.setBetweenStepHookForTest(() -> {
                throw new IllegalStateException("controller died");
            });
            assertThatThrownBy(() -> schedules.runNow(crashing)).as("step 4: the controller died")
                .isInstanceOf(RuntimeException.class);
            RecordSchedules.setBetweenStepHookForTest(null);
            schedules.runDue(null);
            assertThat(RecordSchedules.awaitRunningSteps(Duration.ofSeconds(30))).as("step 4: the sweep ended")
                .isTrue();
            Row crashedRun = latestRun(crashing);
            assertThat(RecordScheduleRuns.steps(crashedRun).get(0).status()).as("step 4: the outcome is unknown")
                .isEqualTo(StepStatus.UNKNOWN);
            assertThat(BODIES).as("step 4: one alert for the unknown outcome").hasSize(1);
            assertThat(BODIES.get(0)).as("step 4: in its own words")
                .contains("\"event\":\"backup_failed\"")
                .contains("instance_backup_unknown_body");

            // 5. A backup that succeeds alerts nothing.
            BODIES.clear();
            int healthy = BackupLaneFixture.instanceRecord("alert-healthy", fixture.hostId);
            new InstanceService().deploy(healthy);
            Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(healthy))
                .assign(InstanceModel.BACKUP_TARGET_ID, fixture.targetId)
                .updateAll();
            int succeeding = schedule(healthy, "healthy backup", StepFailurePolicy.ABORT, false);
            Row healthyRun = schedules.runNow(succeeding);
            assertThat(healthyRun.get(RecordScheduleRunModel.STATUS)).as("step 5: the backup chain completed")
                .isEqualTo(RunStatus.COMPLETED.storageKey());
            assertThat(BODIES).as("step 5: and alerted nobody").isEmpty();
        });
    }

    /**
     * @param due whether the sweep fires it; one that is not runs only when a step asks for it by id
     * @return an instance schedule of one backup_instance step under {@code policy}, system authority
     */
    private static int schedule(int instanceId, String name, StepFailurePolicy policy, boolean due) {
        RecordScheduleModel schedules = Models.get(RecordScheduleModel.class);
        Row schedule = schedules.createEmptyRow();
        schedule.set(RecordScheduleModel.MODEL, InstanceModel.MODEL_ID.toString());
        schedule.set(RecordScheduleModel.RECORD_ID, String.valueOf(instanceId));
        schedule.set(RecordScheduleModel.NAME, name);
        schedule.set(RecordScheduleModel.CRON, "0 3 * * *");
        schedule.set(RecordScheduleModel.ENABLED, true);
        schedule.set(RecordScheduleModel.NEXT_FIRE_AT,
            due ? Now.instant().minusSeconds(60) : Now.instant().plusSeconds(86_400));
        schedules.save(schedule);
        int scheduleId = schedule.get(RecordScheduleModel.ID);

        RecordScheduleStepModel steps = Models.get(RecordScheduleStepModel.class);
        Row step = steps.createEmptyRow();
        step.set(RecordScheduleStepModel.SCHEDULE_ID, scheduleId);
        step.set(RecordScheduleStepModel.POSITION, 1);
        step.set(RecordScheduleStepModel.ACTION, InstanceOperations.BACKUP.id().toString());
        step.set(RecordScheduleStepModel.FAILURE_POLICY, policy.storageKey());
        step.set(RecordScheduleStepModel.RETRY_LIMIT, 3);
        steps.save(step);
        return scheduleId;
    }

    private static Row latestRun(int scheduleId) {
        return Models.get(RecordScheduleRunModel.class).find().noCache()
            .where(RecordScheduleRunModel.SCHEDULE_ID.eq(scheduleId))
            .orderBy(RecordScheduleRunModel.ID, SortOrder.DESC).first();
    }
}
