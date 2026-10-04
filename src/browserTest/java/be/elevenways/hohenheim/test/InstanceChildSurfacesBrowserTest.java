package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.InstanceBackupOperations;
import be.elevenways.hohenheim.instance.InstanceScheduleOperations;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceChildDeletes;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.render.table.SynthesizedRowActions;
import be.elevenways.zenit.cms.test.support.PanelSurfaceComparer;
import be.elevenways.zenit.cms.test.support.PanelSurfaces;
import be.elevenways.zenit.cms.test.support.PlacedOperationMoves;
import be.elevenways.zenit.cms.test.support.SurfaceBaselines;
import be.elevenways.zenit.cms.test.support.SurfaceCase;
import be.elevenways.zenit.cms.test.support.TwinCorrespondence;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.common.task.record.RunStatus;
import be.elevenways.zenit.common.task.record.RunTrigger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The instance child entries of stage 5 B14 (backups, quotas, config files, schedules, their steps and runs), admin
 * and tenant twins, stored before they move onto shared parts and compared exactly after it.
 *
 * AIDEV-NOTE: the stored sets ({@code /panel-surfaces/instance-children/<entry>.txt}, one per entry because each
 * entry's delete moves onto its own operation) are the behaviour captured before the legacy row resources moved onto
 * parts. The accepted differences are declared per set: restore-to-new and the deletes moved onto their operations.
 * A failing comparison is a changed surface, never a file to refresh.
 */
class InstanceChildSurfacesBrowserTest extends HohenheimTestBase {

    private static final String PREFIX = "b14-surfaces-";
    private static final String ADMIN = HohenheimSlugs.ADMIN;
    private static final String MANAGE = HohenheimSlugs.MANAGE;
    private static final String BACKUPS = "instance-backups";
    private static final String QUOTAS = "instance-quotas";
    private static final String FILES = "instance-files";
    private static final String SCHEDULES = "instance-schedules";
    private static final String STEPS = "instance-schedule-steps";
    private static final String RUNS = "instance-schedule-runs";

    private static String instanceId;
    private static String targetId;
    private static String completeBackupId;
    private static String failedBackupId;
    private static String quotaId;
    private static String fileId;
    private static String scheduleId;
    private static String stepId;
    private static String runId;
    private static AccessContext operator;
    private static AccessContext tenantBackups;
    private static AccessContext tenantSchedules;

    @BeforeAll
    static void seed() {
        int backupsUser = ApiSupport.user(PREFIX + "backups@hohenheim.local", "B14 Backups Tenant");
        int schedulesUser = ApiSupport.user(PREFIX + "schedules@hohenheim.local", "B14 Schedules Tenant");

        int instance = instance(PREFIX + "instance");
        instanceId = String.valueOf(instance);
        RecordGrants.grant(GrantSubjectType.USER, backupsUser, InstanceModel.MODEL_ID, instance,
            HohenheimAccess.VIEW, true);
        RecordGrants.grant(GrantSubjectType.USER, backupsUser, InstanceModel.MODEL_ID, instance,
            HohenheimAccess.BACKUPS, true);
        RecordGrants.grant(GrantSubjectType.USER, schedulesUser, InstanceModel.MODEL_ID, instance,
            HohenheimAccess.MANAGE, true);

        int target = target(PREFIX + "target");
        targetId = String.valueOf(target);
        completeBackupId = String.valueOf(backup(instance, target, InstanceBackupModel.STATUS_COMPLETE,
            PREFIX + "complete.tar.zst"));
        failedBackupId = String.valueOf(backup(instance, target, InstanceBackupModel.STATUS_FAILED, null));
        quotaId = String.valueOf(quota("user:" + backupsUser));
        fileId = String.valueOf(file(instance, "/etc/b14/surfaces.conf"));

        int schedule = schedule(instance, schedulesUser);
        scheduleId = String.valueOf(schedule);
        stepId = String.valueOf(step(schedule));
        runId = String.valueOf(run(schedule, instance));

        operator = access(operatorPrincipal());
        tenantBackups = access(new UserPrincipal(backupsUser, "B14 Backups Tenant"));
        tenantSchedules = access(new UserPrincipal(schedulesUser, "B14 Schedules Tenant"));
    }

    @Test
    void theInstanceChildEntriesOfferWhatTheyOfferedBeforeTheMove() {
        // Each entry's own stored set: the deletes moved onto different operations, one declaration per set.
        Map<String, SurfaceBaselines> stored = new LinkedHashMap<>();
        stored.put(BACKUPS, baselines(BACKUPS)
            .placedOperations(PlacedOperationMoves.of(InstanceBackupOperations.RESTORE_BACKUP.id())
                .synthesized(BACKUPS, SynthesizedRowActions.DELETE, InstanceBackupOperations.DELETE_BACKUP.id())));
        stored.put(QUOTAS, baselines(QUOTAS).placedOperations(PlacedOperationMoves.NONE
            .synthesized(QUOTAS, SynthesizedRowActions.DELETE, InstanceChildDeletes.QUOTA.id())));
        stored.put(FILES, baselines(FILES).placedOperations(PlacedOperationMoves.NONE
            .synthesized(FILES, SynthesizedRowActions.DELETE, InstanceChildDeletes.FILE.id())));
        stored.put(SCHEDULES, baselines(SCHEDULES).placedOperations(PlacedOperationMoves.NONE
            .synthesized(SCHEDULES, SynthesizedRowActions.DELETE, InstanceScheduleOperations.DELETE_SCHEDULE.id())));
        stored.put(STEPS, baselines(STEPS).placedOperations(PlacedOperationMoves.NONE
            .synthesized(STEPS, SynthesizedRowActions.DELETE, InstanceScheduleOperations.DELETE_STEP.id())));
        stored.put(RUNS, baselines(RUNS).placedOperations(PlacedOperationMoves.NONE
            .synthesized(RUNS, SynthesizedRowActions.DELETE, InstanceChildDeletes.SCHEDULE_RUN.id())));

        // 1. Every admin child entry for the operator, record-less; a tenant is refused the admin panel.
        for (String entry : List.of(BACKUPS, QUOTAS, FILES, SCHEDULES, STEPS, RUNS)) {
            stored.get(entry).check(capture(SurfaceCase.of(ADMIN, entry, "operator", operator)));
            stored.get(entry).check(capture(SurfaceCase.of(ADMIN, entry, "tenant-backups", tenantBackups)
                .refusedFor(ZenitRefusalReason.FORBIDDEN)));
        }

        // 2. Each admin entry on its record: a complete backup offers restore-to-new, a failed one does not.
        stored.get(BACKUPS).check(capture(SurfaceCase.of(ADMIN, BACKUPS, "operator", operator)
            .onRecord(completeBackupId, "complete")));
        stored.get(BACKUPS).check(capture(SurfaceCase.of(ADMIN, BACKUPS, "operator", operator)
            .onRecord(failedBackupId, "failed")));
        stored.get(QUOTAS).check(capture(SurfaceCase.of(ADMIN, QUOTAS, "operator", operator)
            .onRecord(quotaId, "quota")));
        stored.get(FILES).check(capture(SurfaceCase.of(ADMIN, FILES, "operator", operator).onRecord(fileId, "file")));
        stored.get(SCHEDULES).check(capture(SurfaceCase.of(ADMIN, SCHEDULES, "operator", operator)
            .onRecord(scheduleId, "schedule")));
        stored.get(STEPS).check(capture(SurfaceCase.of(ADMIN, STEPS, "operator", operator).onRecord(stepId, "step")));
        stored.get(RUNS).check(capture(SurfaceCase.of(ADMIN, RUNS, "operator", operator).onRecord(runId, "run")));
        stored.get(BACKUPS).check(capture(SurfaceCase.of(ADMIN, BACKUPS, "operator", operator)
            .selecting(List.of(completeBackupId, failedBackupId), "sel")));

        // 3. The parent prefills the create forms carry from their tabs.
        stored.get(FILES).check(capture(SurfaceCase.of(ADMIN, FILES, "operator", operator).named(ADMIN + "." + FILES
            + ".operator.prefill").withParameter(HohenheimParams.INSTANCE_ID_PREFILL.getName(), instanceId)));
        stored.get(STEPS).check(capture(SurfaceCase.of(ADMIN, STEPS, "operator", operator).named(ADMIN + "." + STEPS
            + ".operator.prefill").withParameter(HohenheimParams.SCHEDULE_ID_PREFILL.getName(), scheduleId)));

        // 4. The /manage twins for the tenants holding their capability on the instance.
        stored.get(BACKUPS).check(capture(SurfaceCase.of(MANAGE, BACKUPS, "tenant-backups", tenantBackups)));
        stored.get(BACKUPS).check(capture(SurfaceCase.of(MANAGE, BACKUPS, "tenant-backups", tenantBackups)
            .onRecord(completeBackupId, "complete")));
        stored.get(SCHEDULES).check(capture(SurfaceCase.of(MANAGE, SCHEDULES, "tenant-schedules", tenantSchedules)));
        stored.get(SCHEDULES).check(capture(SurfaceCase.of(MANAGE, SCHEDULES, "tenant-schedules", tenantSchedules)
            .onRecord(scheduleId, "schedule")));
        stored.get(STEPS).check(capture(SurfaceCase.of(MANAGE, STEPS, "tenant-schedules", tenantSchedules)
            .onRecord(stepId, "step")));

        // 5. Every stored case matched exactly, and the tenant's backup twin is the admin's on the same record, minus
        //    what it narrows away (restore-to-new and the history tab).
        List<String> failures = new ArrayList<>();
        for (SurfaceBaselines set : stored.values()) {
            try {
                set.finish();
            } catch (AssertionError mismatch) {
                failures.add(mismatch.getMessage());
            }
        }
        try {
            PanelSurfaceComparer.assertNarrower(stored.get(BACKUPS).captured(MANAGE + "." + BACKUPS
                    + ".tenant-backups.complete"), stored.get(BACKUPS).captured(ADMIN + "." + BACKUPS
                    + ".operator.complete"),
                TwinCorrespondence.between(MANAGE + "/" + BACKUPS, ADMIN + "/" + BACKUPS));
        } catch (AssertionError difference) {
            failures.add(difference.getMessage());
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(String.join("\n\n", failures));
        }
    }

    private static SurfaceBaselines baselines(String entry) {
        return SurfaceBaselines.load(InstanceChildSurfacesBrowserTest.class,
            "/panel-surfaces/instance-children/" + entry + ".txt");
    }

    /** A capture with every generated fixture id declared at the bindings a destination carries it. */
    private static PanelSurfaces capture(SurfaceCase fixture) {
        return PanelSurfaces.capture(fixture
            .key(HohenheimSlugs.INSTANCES, "instance", instanceId)
            .key(HohenheimParams.INSTANCE_ID_PREFILL.getName(), "instance", instanceId)
            .key("backup-targets", "target", targetId)
            .key(BACKUPS, "complete_backup", completeBackupId).key(BACKUPS, "failed_backup", failedBackupId)
            .key(QUOTAS, "quota", quotaId)
            .key(FILES, "file", fileId)
            .key(SCHEDULES, "schedule", scheduleId)
            .key(HohenheimParams.SCHEDULE_ID_PREFILL.getName(), "schedule", scheduleId)
            .key(STEPS, "step", stepId)
            .key(RUNS, "run", runId));
    }

    private static AccessContext access(UserPrincipal principal) {
        return AccessContext.of(TenantConduits.stubFor(principal));
    }

    private static UserPrincipal operatorPrincipal() {
        Row admin = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return new UserPrincipal(admin.get(UserModel.ID), "Test Admin");
    }

    private static int instance(String name) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static int target(String name) {
        Model targets = Models.get(BackupTargetModel.class);
        Row row = targets.createEmptyRow();
        row.set(BackupTargetModel.NAME, name);
        row.set(BackupTargetModel.KIND, "hohenheim:filesystem");
        row.set(BackupTargetModel.SETTINGS, Map.of("path", "/tmp/" + name));
        targets.save(row);
        return row.get(BackupTargetModel.ID);
    }

    private static int backup(int instance, int target, String status, String remoteKey) {
        Model backups = Models.get(InstanceBackupModel.class);
        Row row = backups.createEmptyRow();
        row.set(InstanceBackupModel.INSTANCE_ID, instance);
        row.set(InstanceBackupModel.TARGET_ID, target);
        row.set(InstanceBackupModel.STATUS, status);
        row.set(InstanceBackupModel.REMOTE_KEY, remoteKey);
        row.set(InstanceBackupModel.SIZE_BYTES, 4096L);
        backups.save(row);
        return row.get(InstanceBackupModel.ID);
    }

    private static int quota(String subjects) {
        Model quotas = Models.get(InstanceQuotaModel.class);
        Row row = quotas.createEmptyRow();
        row.set(InstanceQuotaModel.SUBJECTS, subjects);
        row.set(InstanceQuotaModel.MAX_INSTANCES, 3);
        quotas.save(row);
        return row.get(InstanceQuotaModel.ID);
    }

    private static int file(int instance, String path) {
        Model files = Models.get(InstanceFileModel.class);
        Row row = files.createEmptyRow();
        row.set(InstanceFileModel.INSTANCE_ID, instance);
        row.set(InstanceFileModel.CONTAINER_PATH, path);
        row.set(InstanceFileModel.CONTENT, "listen=25565");
        row.set(InstanceFileModel.MODE, "0644");
        files.save(row);
        return row.get(InstanceFileModel.ID);
    }

    private static int schedule(int instance, int runAs) {
        Model schedules = Models.get(RecordScheduleModel.class);
        Row row = schedules.createEmptyRow();
        row.set(RecordScheduleModel.MODEL, InstanceModel.MODEL_ID.toString());
        row.set(RecordScheduleModel.RECORD_ID, String.valueOf(instance));
        row.set(RecordScheduleModel.NAME, PREFIX + "nightly");
        row.set(RecordScheduleModel.CRON, "0 4 * * *");
        row.set(RecordScheduleModel.ENABLED, false);
        row.set(RecordScheduleModel.RUN_AS, (long) runAs);
        schedules.save(row);
        return row.get(RecordScheduleModel.ID);
    }

    private static int step(int schedule) {
        Model steps = Models.get(RecordScheduleStepModel.class);
        Row row = steps.createEmptyRow();
        row.set(RecordScheduleStepModel.SCHEDULE_ID, schedule);
        row.set(RecordScheduleStepModel.POSITION, 1);
        row.set(RecordScheduleStepModel.ACTION, "zenit:power_stop");
        steps.save(row);
        return row.get(RecordScheduleStepModel.ID);
    }

    private static int run(int schedule, int instance) {
        Model runs = Models.get(RecordScheduleRunModel.class);
        Row row = runs.createEmptyRow();
        row.set(RecordScheduleRunModel.SCHEDULE_ID, schedule);
        row.set(RecordScheduleRunModel.MODEL, InstanceModel.MODEL_ID.toString());
        row.set(RecordScheduleRunModel.RECORD_ID, String.valueOf(instance));
        row.set(RecordScheduleRunModel.STATUS, RunStatus.COMPLETED.storageKey());
        row.set(RecordScheduleRunModel.TRIGGER, RunTrigger.MANUAL.storageKey());
        row.set(RecordScheduleRunModel.CLAIM_FENCE, 1L);
        row.set(RecordScheduleRunModel.STARTED_AT, Now.instant());
        row.set(RecordScheduleRunModel.ENDED_AT, Now.instant());
        runs.save(row);
        return row.get(RecordScheduleRunModel.ID);
    }
}
