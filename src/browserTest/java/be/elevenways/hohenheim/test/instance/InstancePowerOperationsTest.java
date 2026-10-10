package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.test.LegacyStepPayloads;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimRefusalReason;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.instance.InstanceOperations.PowerResult;
import be.elevenways.hohenheim.migration.M011_ReviewHardening;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.server.instance.InstanceOperationHandlers;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.zenit.common.orm.lease.Leases;
import be.elevenways.zenit.common.operation.OperationInvocation;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.protoblast.common.thread.ExecutionContext;
import be.elevenways.zenit.common.security.SystemPurpose;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationResult;
import be.elevenways.zenit.common.operation.PlacementSurface;
import be.elevenways.zenit.common.operation.ZenitPlacementSurface;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.RecordCapabilityRules;
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
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Hohenheim's start, stop, restart, backup and snapshot are
 * operations, run from a schedule step and from the API route through one pipeline. Stored power steps keep running,
 * a restart holds one lock, the trigger names the surface that asked, and the services write the activity rows.
 *
 * AIDEV-NOTE: daemon-free, over the backup lane's fake native kind. The API surface is driven through the pipeline
 * exactly as {@code InstanceApi}'s routes build their request; the HTTP wire itself is pinned by
 * {@code TenantInstanceApiTest}, which stays unchanged.
 */
class InstancePowerOperationsTest {
    private static final SystemPurpose OTHER_SYSTEM_WORK = SystemPurpose.declare(
        Identifier.of("hohenheim_test", "other_power_work"));

    private static SqlDatasource datasource;
    private static BackupLaneFixture fixture;
    private static int tenantId;

    @BeforeAll
    static void setUp() throws Exception {
        fixture = BackupLaneFixture.install();
        datasource = fixture.datasource;
        Db.run(datasource, () -> tenantId = ApiSupport.user("power-ops@surface.test", "Power Operator"));
    }

    @AfterAll
    static void tearDown() {
        FakeNativeDaemons.DURING_START.set(null);
        BackupLaneFixture.uninstall();
    }

    @Test
    void anInProgressRefusalKeepsTheCoreEdgeAnswer() {
        DomainRefusal refusal = new DomainRefusal(ZenitRefusalReason.IN_PROGRESS, "another command is running");
        int[] status = {0};
        assertThatThrownBy(() -> ApiConduits.refusal(answering(status), refusal))
            .as("an unmapped core refusal reaches core unchanged").isSameAs(refusal);
        assertThat(status[0]).as("the frozen API adapter writes no replacement status").isZero();
    }

    @Test
    void aBackupDuringAReleaseDeployKeepsItsFrozenInProgressBody() {
        Db.run(datasource, () -> {
            var localHost = HostFixtures.captureLocal();
            try {
                // 1. Release-managed instances require Docker; use the shared admitted local-host fixture.
                HostFixtures.makeLocalPlaceable(16L * 1024);
                int id = BackupLaneFixture.instanceRecord("backup-deploy-target", fixture.hostId);
                Row row = Models.get(InstanceModel.class).findById(id);
                row.set(InstanceModel.KIND, InstanceKinds.kindsWhere(handler -> handler.releaseManaged()).getFirst());
                row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
                row.set(InstanceModel.BACKUP_TARGET_ID, fixture.targetId);
                Models.get(InstanceModel.class).save(row);
                for (String capability : List.of(HohenheimCapabilities.VIEW, HohenheimCapabilities.BACKUPS)) {
                    RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, id, capability, true);
                }
                AccessContext tenant = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(tenantId, "Power Operator")));
                String commandKey = InstanceOperations.BACKUP.command().leaseKey().apply(
                    new OperationInvocation(InstanceOperations.BACKUP.id(), List.of(String.valueOf(id)),
                        null, null, null, Map.of()));
                assertThat(commandKey).as("step 1: the command and deploy claim use one physical key")
                    .isEqualTo(InstanceOperations.KEYS.key(id));
                AtomicReference<Throwable> refused = new AtomicReference<>();
                InstanceOperationLock.production().exclusive(id, InstanceOperationLock.Contention.REFUSE, () -> {
                    Thread backup = new Thread(ExecutionContext.wrap(() -> Db.run(datasource,
                        () -> refused.set(catchThrowable(() -> api(InstanceOperations.BACKUP, tenant, id))))),
                        "backup-during-deploy");
                    backup.start();
                    try {
                        backup.join(TimeUnit.SECONDS.toMillis(Leases.DEFAULT_WAIT_SECONDS + 15L));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Backup contention journey interrupted", interrupted);
                    }
                    assertThat(backup.isAlive()).as("step 2: the API refuses after the normal wait, never queues for an hour")
                        .isFalse();
                });
                // 2. The production deploy claim yields typed core contention and the frozen instance wire answer.
                assertThat(refused.get()).as("step 2: a live deploy is contention, not a crash")
                    .isInstanceOfSatisfying(DomainRefusal.class, refusal -> assertThat(refusal.reason())
                        .isEqualTo(ZenitRefusalReason.IN_PROGRESS));
                int[] status = {0};
                String body = Json.stringify(ApiConduits.refusal(answering(status), (DomainRefusal) refused.get(), row).get());
                assertThat(status[0]).as("step 2: the instance wire remains a 422 refusal").isEqualTo(422);
                assertThat(body).as("step 2: backup during deploy has the byte-identical instance in-progress body")
                    .isEqualTo("{\"status\":422,\"code\":\"instance_operation_in_progress\",\"message\":\"Another operation on "
                        + "instance backup-deploy-target is still running; try again when it has finished\",\"violations\":[{\"code\":"
                        + "\"instance_operation_in_progress\",\"message\":\"Another operation on instance backup-deploy-target is still "
                        + "running; try again when it has finished\"}]}");
            } finally {
                localHost.restore();
            }
        });
    }

    @Test
    void scheduledSystemSubjectsHaveAnExplicitAudience() {
        Db.run(datasource, () -> {
            int instanceId = BackupLaneFixture.instanceRecord("system-source-target", fixture.hostId);
            int scheduleId = schedule(instanceId, "declared system stop", null);
            // 1. Every instance operation placed on schedules binds the same explicit system source.
            for (Operation<Row, ?, ?> operation : List.of(InstanceOperations.START, InstanceOperations.STOP,
                    InstanceOperations.RESTART, InstanceOperations.BACKUP, InstanceOperations.SNAPSHOT,
                    InstanceOperations.CONSOLE_COMMAND, InstanceOperations.APP_UPDATE)) {
                Row subject = OperationRequest.of(operation, ZenitPlacementSurface.SCHEDULE_STEP)
                    .asSystem(RecordSchedules.systemIdentity(scheduleId), null)
                    .subjectKeys(List.of(String.valueOf(instanceId)))
                    .loadSubjects().getFirst();
                assertThat(subject.get(InstanceModel.ID)).as("1: system loads the subject of %s", operation.id())
                    .isEqualTo(instanceId);
                assertThatThrownBy(() -> OperationRequest.of(operation, ZenitPlacementSurface.SCHEDULE_STEP)
                    .caller(AccessContext.anonymous()).subjectKeys(List.of(String.valueOf(instanceId))).loadSubjects())
                    .as("1: anonymous gets no subject of %s", operation.id())
                    .isInstanceOfSatisfying(DomainRefusal.class,
                        refusal -> assertThat(refusal.reason()).isSameAs(ZenitRefusalReason.NOT_FOUND));
                assertThatThrownBy(() -> OperationRequest.of(operation, ZenitPlacementSurface.SCHEDULE_STEP)
                    .asSystem(ExecutionIdentity.system("record-schedule:" + scheduleId), null)
                    .subjectKeys(List.of(String.valueOf(instanceId))).loadSubjects())
                    .as("1: a schedule-shaped label cannot supply authority for %s", operation.id())
                    .isInstanceOfSatisfying(DomainRefusal.class,
                        refusal -> assertThat(refusal.reason()).isSameAs(ZenitRefusalReason.NOT_FOUND));
                assertThatThrownBy(() -> OperationRequest.of(operation, ZenitPlacementSurface.SCHEDULE_STEP)
                    .asSystem(ExecutionIdentity.system(OTHER_SYSTEM_WORK, "unrelated system work"), null)
                    .subjectKeys(List.of(String.valueOf(instanceId))).loadSubjects())
                    .as("1: another system purpose gets no subject of %s", operation.id())
                    .isInstanceOfSatisfying(DomainRefusal.class,
                        refusal -> assertThat(refusal.reason()).isSameAs(ZenitRefusalReason.NOT_FOUND));
            }

            // 2. A schedule with no run_as executes, not just its subject-loading probe.
            new InstanceService().deploy(instanceId);
            assertThat(Models.get(InstanceModel.class).findById(instanceId).get(InstanceModel.STATUS))
                .as("2: the fixture has a deployed workload and its host fence").isEqualTo(InstanceModel.STATUS_RUNNING);
            step(scheduleId, InstanceOperations.STOP, null, StepFailurePolicy.ABORT);
            Row run = new RecordSchedules(datasource).runNow(scheduleId);
            Row stopped = stepRuns(run).getFirst();
            assertThat(stopped.get(RecordScheduleStepRunModel.STATUS))
                .as("2: the system stop loaded and executed its subject; error: %s",
                    stopped.get(RecordScheduleStepRunModel.ERROR)).isEqualTo(StepStatus.OK.storageKey());
            assertThat(Models.get(InstanceModel.class).findById(instanceId).get(InstanceModel.STATUS))
                .as("2: the system operation stopped the deployed workload").isEqualTo(InstanceModel.STATUS_STOPPED);
        });
    }

    @Test
    void theInstanceOperationsRunFromEverySurfaceJourney() {
        Db.run(datasource, () -> {
            int instanceId = BackupLaneFixture.instanceRecord("ops-target", fixture.hostId);
            for (String capability : List.of(HohenheimCapabilities.VIEW, HohenheimCapabilities.POWER,
                HohenheimCapabilities.BACKUPS, HohenheimCapabilities.SNAPSHOTS)) {
                RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, instanceId, capability,
                    true);
            }
            AccessContext tenant = AccessContext.of(TenantConduits.stubFor(
                new UserPrincipal(tenantId, "Power Operator")));
            assertThat(RecordCapabilityRules.of(InstanceModel.MODEL_ID).visibilityCapability())
                .as("instances declare their existing view capability for concealment")
                .isEqualTo(HohenheimCapabilities.VIEW);

            // 1. The slice one migration rewrites stored power steps by their stored operation (start, stop,
            //    restart, and a blank one as the old default restart), names the backup and snapshot operations,
            //    and moves the snapshot's payload into the stored input.
            int legacySchedule = schedule(instanceId, "legacy chain", null);
            int start = legacyStep(legacySchedule, 1, "hohenheim:power", Map.of("operation", "start"));
            int stop = legacyStep(legacySchedule, 2, "hohenheim:power", Map.of("operation", "stop"));
            int restart = legacyStep(legacySchedule, 3, "hohenheim:power", Map.of("operation", "restart"));
            int blank = legacyStep(legacySchedule, 4, "hohenheim:power", Map.of("operation", ""));
            int snapshot = legacyStep(legacySchedule, 5, "hohenheim:snapshot", Map.of("note", "before nightly"));
            int backup = legacyStep(legacySchedule, 6, "hohenheim:backup", Map.of());
            M011_ReviewHardening.renameInstanceScheduleSteps(datasource);
            assertThat(storedAction(start)).as("step 1: a stored start is the start operation")
                .isEqualTo(InstanceOperations.START.id().toString());
            assertThat(storedAction(stop)).as("step 1: a stored stop is the stop operation")
                .isEqualTo(InstanceOperations.STOP.id().toString());
            assertThat(storedAction(restart)).as("step 1: a stored restart is the restart operation")
                .isEqualTo(InstanceOperations.RESTART.id().toString());
            assertThat(storedAction(blank)).as("step 1: a blank operation is the old default, restart")
                .isEqualTo(InstanceOperations.RESTART.id().toString());
            assertThat(storedAction(backup)).as("step 1: a backup step is the backup operation")
                .isEqualTo(InstanceOperations.BACKUP.id().toString());
            assertThat(storedAction(snapshot)).as("step 1: a snapshot step is the snapshot operation")
                .isEqualTo(InstanceOperations.SNAPSHOT.id().toString());
            Row snapshotStep = Models.get(RecordScheduleStepModel.class).find().noCache()
                .where(RecordScheduleStepModel.ID.eq(snapshot)).first();
            assertThat(snapshotStep.get(RecordScheduleStepModel.INPUT)).as("step 1: its note moved into the input")
                .isEqualTo(Map.of("note", "before nightly"));
            assertThat(LegacyStepPayloads.of(snapshot)).as("step 1: and the payload is cleared")
                .isNull();

            // 2. A stored power operation no operation replaces fails the migration, naming the step.
            int unknown = legacyStep(legacySchedule, 7, "hohenheim:power", Map.of("operation", "reboot"));
            assertThatThrownBy(() -> M011_ReviewHardening.renameInstanceScheduleSteps(datasource))
                .as("step 2: an unknown stored operation fails the migration")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Schedule step " + unknown)
                .hasMessageContaining("reboot");
            LEGACY_STEPS.find().where(STEP_ID.eq(unknown)).delete();

            // 3. A scheduled restart runs through the operation, as the schedule's run_as principal, under the
            //    schedule trigger, and the service records its two halves.
            new InstanceService().deploy(instanceId);
            int stoppedBefore = activity(instanceId, HohenheimActivityAction.STOPPED);
            int deployedBefore = activity(instanceId, HohenheimActivityAction.DEPLOYED);
            int restartSchedule = schedule(instanceId, "scheduled restart", (long) tenantId);
            step(restartSchedule, InstanceOperations.RESTART, null, StepFailurePolicy.ABORT);
            Row restartRun = new RecordSchedules(datasource).runNow(restartSchedule);
            Row restartStep = stepRuns(restartRun).get(0);
            assertThat(restartStep.get(RecordScheduleStepRunModel.STATUS)).as("step 3: the scheduled restart ran")
                .isEqualTo(StepStatus.OK.storageKey());
            assertThat(restartStep.get(RecordScheduleStepRunModel.OUTCOME))
                .as("step 3: under the schedule trigger, the instance running again")
                .isEqualTo(InstanceModel.STATUS_RUNNING + " (" + DeployTrigger.SCHEDULE.word() + ")");
            assertThat(DeployTrigger.SCHEDULE.startsStoppedWorkload())
                .as("step 3: with exactly the system trigger's start policy")
                .isEqualTo(DeployTrigger.SYSTEM.startsStoppedWorkload());
            assertThat(activity(instanceId, HohenheimActivityAction.STOPPED) - stoppedBefore)
                .as("step 3: the service recorded the stop half once").isEqualTo(1);
            assertThat(activity(instanceId, HohenheimActivityAction.DEPLOYED) - deployedBefore)
                .as("step 3: and the deploy half once").isEqualTo(1);

            // 4. A restart asked over the API records the API trigger, and a surface that names no trigger is
            //    refused outright, never guessed.
            PowerResult apiRestart = api(InstanceOperations.RESTART, tenant, instanceId).value();
            assertThat(apiRestart).as("step 4: the API restart answers the instance running under the API trigger")
                .isEqualTo(new PowerResult(InstanceModel.STATUS_RUNNING, DeployTrigger.API.word(), false));
            PlacementSurface elsewhere = new PlacementSurface() {
                @Override public Identifier id() { return Identifier.of("test", "elsewhere"); }
                @Override public Microcopy label() { return Microcopy.literal("Elsewhere"); }
                @Override public boolean redrawsRefusedForm() { return false; }
            };
            assertThatThrownBy(() -> InstanceOperationHandlers.triggerOf(elsewhere))
                .as("step 4: an undeclared surface has no trigger").isInstanceOf(IllegalStateException.class);
            assertThat(InstanceOperationHandlers.triggerOf(CmsPlacementSurface.ADMIN_ACTION))
                .as("step 4: an admin panel click deploys under the manual trigger").isEqualTo(DeployTrigger.MANUAL);

            // 5. The restart holds ONE lock across both halves: a start asked while its deploy half runs is refused
            //    by the in-progress refusal.
            AtomicReference<Throwable> rival = new AtomicReference<>();
            FakeNativeDaemons.DURING_START.set(() -> {
                Thread thread = new Thread(ExecutionContext.wrap(() -> Db.run(datasource,
                    () -> rival.set(catchThrowable(() -> api(InstanceOperations.START, tenant, instanceId))))),
                    "rival-start");
                thread.start();
                try {
                    thread.join(TimeUnit.SECONDS.toMillis(5));
                    assertThat(thread.isAlive()).as("step 5: a different power verb reaches its refusal immediately")
                        .isFalse();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            api(InstanceOperations.RESTART, tenant, instanceId);
            assertThat(rival.get()).as("step 5: the rival start is refused while the restart holds the record")
                .isInstanceOfSatisfying(Violations.class, violations -> assertThat(violations.all())
                    .anySatisfy(violation -> assertThat(violation.message().key())
                        .isEqualTo("instance_operation_in_progress")));
            int[] refusedStatus = {0};
            String refusedBody = Json.stringify(ApiConduits.refusal(answering(refusedStatus),
                (Violations) rival.get()).get());
            assertThat(refusedStatus[0]).as("step 5: the frozen /api/v1 adapter still answers 422").isEqualTo(422);
            assertThat(refusedBody).as("step 5: start during restart keeps the byte-identical /api/v1 refusal body")
                .isEqualTo("{\"status\":422,\"code\":\"instance_operation_in_progress\",\"message\":\"Another operation on "
                    + "instance ops-target is still running; try again when it has finished\",\"violations\":[{\"code\":"
                    + "\"instance_operation_in_progress\",\"message\":\"Another operation on instance ops-target is still "
                    + "running; try again when it has finished\"}]}");

            // 6. Stop is idempotent on every surface: the API stops a running instance, a second stop answers
            //    "already stopped" without touching it, and a scheduled stop of a stopped instance succeeds.
            PowerResult stopped = api(InstanceOperations.STOP, tenant, instanceId).value();
            assertThat(stopped).as("step 6: the API stop stopped it")
                .isEqualTo(new PowerResult(InstanceModel.STATUS_STOPPED, null, false));
            int stopsAfterFirst = activity(instanceId, HohenheimActivityAction.STOPPED);
            assertThat(api(InstanceOperations.STOP, tenant, instanceId).value())
                .as("step 6: a second stop answers already stopped")
                .isEqualTo(new PowerResult(InstanceModel.STATUS_STOPPED, null, true));
            int stopSchedule = schedule(instanceId, "scheduled stop", (long) tenantId);
            step(stopSchedule, InstanceOperations.STOP, null, StepFailurePolicy.ABORT);
            Row stopStep = stepRuns(new RecordSchedules(datasource).runNow(stopSchedule)).get(0);
            assertThat(stopStep.get(RecordScheduleStepRunModel.STATUS))
                .as("step 6: a scheduled stop of a stopped instance succeeds").isEqualTo(StepStatus.OK.storageKey());
            assertThat(stopStep.get(RecordScheduleStepRunModel.OUTCOME)).as("step 6: as already stopped")
                .isEqualTo("already " + InstanceModel.STATUS_STOPPED);
            assertThat(activity(instanceId, HohenheimActivityAction.STOPPED))
                .as("step 6: neither no-op stop recorded a stop").isEqualTo(stopsAfterFirst);

            // 7. Backup and snapshot: the service writes exactly one activity row each, whichever surface asked;
            //    a scheduled snapshot carries its stored note.
            new InstanceService().deploy(instanceId);
            Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(instanceId))
                .assign(InstanceModel.BACKUP_TARGET_ID, fixture.targetId)
                .updateAll();
            int backupsBefore = activity(instanceId, HohenheimActivityAction.BACKUP);
            Integer backupId = api(InstanceOperations.BACKUP, tenant, instanceId).value();
            assertThat(backupId).as("step 7: the API backup answers the backup's id").isNotNull();
            assertThat(activity(instanceId, HohenheimActivityAction.BACKUP) - backupsBefore)
                .as("step 7: one backup row, written by the service").isEqualTo(1);
            int snapshotsBefore = activity(instanceId, HohenheimActivityAction.SNAPSHOT);
            int snapshotSchedule = schedule(instanceId, "scheduled snapshot", (long) tenantId);
            step(snapshotSchedule, InstanceOperations.SNAPSHOT, Map.of("note", "nightly"), StepFailurePolicy.ABORT);
            Row snapshotRun = stepRuns(new RecordSchedules(datasource).runNow(snapshotSchedule)).get(0);
            assertThat(snapshotRun.get(RecordScheduleStepRunModel.STATUS)).as("step 7: the scheduled snapshot ran")
                .isEqualTo(StepStatus.OK.storageKey());
            Row taken = Models.get(InstanceSnapshotModel.class).find().noCache()
                .where(InstanceSnapshotModel.INSTANCE_ID.eq(instanceId))
                .orderBy(InstanceSnapshotModel.ID, SortOrder.DESC).first();
            assertThat(taken.get(InstanceSnapshotModel.NOTE)).as("step 7: with the stored note")
                .isEqualTo("nightly");
            assertThat(activity(instanceId, HohenheimActivityAction.SNAPSHOT) - snapshotsBefore)
                .as("step 7: one snapshot row, written by the service").isEqualTo(1);

            // 8. A database that is not ready refuses a start with a retriable reason: the API answers it in
            //    today's 422 envelope, byte-identical to the service's own refusal, and a scheduled start under RETRY
            //    is due again rather than refused for good.
            int waiting = BackupLaneFixture.instanceRecord("ops-waiting", fixture.hostId);
            RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, waiting,
                HohenheimCapabilities.POWER, true);
            linkWaitingDatabase(waiting);
            Microcopy notReady = InstanceDatabaseLinks.notReadyReason(waiting);
            assertThat(notReady).as("step 8: the fixture database is not ready").isNotNull();
            Throwable refused = catchThrowable(() -> api(InstanceOperations.START, tenant, waiting));
            assertThat(refused).as("step 8: the start is refused by the retriable reason")
                .isInstanceOfSatisfying(DomainRefusal.class, refusal -> {
                    assertThat(refusal.is(HohenheimRefusalReason.DATABASE_NOT_READY)).isTrue();
                    assertThat(refusal.reason().retriable()).isTrue();
                });
            int[] adapterStatus = {0};
            int[] serviceStatus = {0};
            ActionResult<Object> adapted = ApiConduits.refusal(answering(adapterStatus), (DomainRefusal) refused);
            ActionResult<Object> service = ApiConduits.refusal(answering(serviceStatus), Violations.ofForm(notReady));
            assertThat(adapterStatus[0]).as("step 8: the API answers 422, as before").isEqualTo(422)
                .isEqualTo(serviceStatus[0]);
            assertThat(adapted.get()).as("step 8: with the very body the service's refusal wrote")
                .isEqualTo(service.get());
            assertThat(String.valueOf(((Map<?, ?>) adapted.get()).get("code")))
                .as("step 8: code database_not_ready").isEqualTo("database_not_ready");
            int waitingSchedule = schedule(waiting, "waiting start", (long) tenantId);
            step(waitingSchedule, InstanceOperations.START, null, StepFailurePolicy.RETRY);
            Row waitingStep = stepRuns(new RecordSchedules(datasource).runNow(waitingSchedule)).get(0);
            assertThat(waitingStep.get(RecordScheduleStepRunModel.STATUS))
                .as("step 8: a scheduled start under RETRY is due again").isEqualTo(StepStatus.DUE.storageKey());
        });
    }

    /** The request {@code InstanceApi}'s routes build: the API surface, the key's caller, the visible row. */
    private static <R> OperationResult<R> api(Operation<Row, ?, R> operation, AccessContext caller, int instanceId) {
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        return OperationPipeline.invoke(OperationRequest.of(operation, ZenitPlacementSurface.HTTP_API)
            .caller(caller)
            .subjects(List.of(row)));
    }

    private static int schedule(int instanceId, String name, @Nullable Long runAs) {
        Model schedules = Models.get(RecordScheduleModel.class);
        Row schedule = schedules.createEmptyRow();
        schedule.set(RecordScheduleModel.MODEL, InstanceModel.MODEL_ID.toString());
        schedule.set(RecordScheduleModel.RECORD_ID, String.valueOf(instanceId));
        schedule.set(RecordScheduleModel.NAME, name);
        schedule.set(RecordScheduleModel.CRON, "0 4 * * *");
        schedule.set(RecordScheduleModel.ENABLED, true);
        schedule.set(RecordScheduleModel.NEXT_FIRE_AT, Now.instant().plusSeconds(86_400));
        schedule.set(RecordScheduleModel.RUN_AS, runAs);
        schedules.save(schedule);
        return schedule.get(RecordScheduleModel.ID);
    }

    private static void step(int scheduleId, Operation<Row, ?, ?> operation, @Nullable Map<String, Object> input,
                             StepFailurePolicy policy) {
        Model steps = Models.get(RecordScheduleStepModel.class);
        Row step = steps.createEmptyRow();
        step.set(RecordScheduleStepModel.SCHEDULE_ID, scheduleId);
        step.set(RecordScheduleStepModel.POSITION, 1);
        step.set(RecordScheduleStepModel.ACTION, operation.id().toString());
        step.set(RecordScheduleStepModel.INPUT, input);
        step.set(RecordScheduleStepModel.FAILURE_POLICY, policy.storageKey());
        step.set(RecordScheduleStepModel.RETRY_LIMIT, 3);
        steps.save(step);
    }

    /** The step table's columns as the code of tag before-module-fit wrote them. */
    private static final IntegerField STEP_ID = IntegerField.builder().name("id").build();
    private static final IntegerField STEP_SCHEDULE = IntegerField.builder().name("schedule_id").build();
    private static final IntegerField STEP_ORDER = IntegerField.builder().name("step_order").build();
    private static final StringField STEP_ACTION = StringField.builder().name("action").build();
    private static final SchemaField STEP_PAYLOAD = SchemaField.builder("payload").build();
    private static final StringField STEP_POLICY = StringField.builder().name("failure_policy").build();
    private static final FrozenModel LEGACY_STEPS = new FrozenModel("zenit_record_schedule_steps", STEP_ID,
        STEP_SCHEDULE, STEP_ORDER, STEP_ACTION, STEP_PAYLOAD, STEP_POLICY);

    /** A step as the code of tag before-module-fit stored it: a legacy action id and its payload, written raw. */
    private static int legacyStep(int scheduleId, int position, String action, Map<String, Object> payload) {
        Row step = LEGACY_STEPS.createEmptyRow();
        step.set(STEP_SCHEDULE, scheduleId);
        step.set(STEP_ORDER, position);
        step.set(STEP_ACTION, action);
        step.set(STEP_PAYLOAD, new LinkedHashMap<>(payload));
        step.set(STEP_POLICY, StepFailurePolicy.ABORT.storageKey());
        LEGACY_STEPS.save(step);
        return step.get(STEP_ID);
    }

    private static String storedAction(int stepId) {
        return LEGACY_STEPS.find().where(STEP_ID.eq(stepId)).first().get(STEP_ACTION);
    }

    private static List<Row> stepRuns(Row run) {
        return Models.get(RecordScheduleStepRunModel.class).findForRun(run.get(RecordScheduleRunModel.ID));
    }

    private static int activity(int instanceId, HohenheimActivityAction action) {
        return (int) Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ActivityModel.RECORD_ID.eq(String.valueOf(instanceId)))
            .where(ActivityModel.ACTION.eq(action.id().toString()))
            .count();
    }

    /** A database still provisioning, attached to the instance: what a deploy must not start without. */
    private static void linkWaitingDatabase(int instanceId) {
        Model databases = Models.get(DatabaseModel.class);
        Row database = databases.createEmptyRow();
        database.set(DatabaseModel.NAME, "ops-waiting-db");
        database.set(DatabaseModel.ENGINE, "postgres");
        database.set(DatabaseModel.DB_NAME, "appdb");
        database.set(DatabaseModel.DB_USER, "appuser");
        database.set(DatabaseModel.DB_PASSWORD, "s3cr3t-ops-pw");
        database.set(DatabaseModel.SERVER_ID, fixture.hostId);
        database.set(DatabaseModel.STATUS, DatabaseModel.STATUS_PROVISIONING);
        databases.save(database);
        Model links = Models.get(InstanceDatabaseModel.class);
        Row link = links.createEmptyRow();
        link.set(InstanceDatabaseModel.INSTANCE_ID, instanceId);
        link.set(InstanceDatabaseModel.DATABASE_ID, database.get(DatabaseModel.ID));
        link.set(InstanceDatabaseModel.ENV_PREFIX, "APP_DB");
        links.save(link);
    }

    /** A conduit that answers in English and records the status a refusal sets. */
    private static Conduit answering(int[] status) {
        return (Conduit) Proxy.newProxyInstance(InstancePowerOperationsTest.class.getClassLoader(),
            new Class<?>[] { Conduit.class }, (proxy, method, args) -> switch (method.getName()) {
                case "setResponseStatus" -> {
                    status[0] = (int) args[0];
                    yield null;
                }
                case "getLocales" -> LocaleChain.ofTags("en");
                case "getMessageResolver" -> Zenit.getMessageResolver();
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "answering conduit";
                default -> method.getReturnType() == boolean.class ? Boolean.FALSE : null;
            });
    }
}
