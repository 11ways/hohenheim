package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.schedule.InstanceBackupAction;
import be.elevenways.hohenheim.server.schedule.InstanceConsoleCommandAction;
import be.elevenways.hohenheim.server.schedule.InstancePowerAction;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.task.record.RecordSchedules;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Record schedules against a REAL daemon: the Pterodactyl-parity chain ("warn the
 * players, then restart"), live grant revocation stopping a stored schedule, the
 * migrated backup schedule producing a real artifact, and schedules dying with their
 * record through the SOFT-delete destroy path. Every record assertion reads the
 * database; every workload assertion reads the daemon.
 */
class InstanceScheduleLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;
    private static Path workRoot;
    private static String previousSnapshotPath;
    private static String previousStagingPath;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-schedule-live-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // Unique per-class instance ids => unique daemon handles (no cross-class 409s).
        InstanceIdOffsets.apply(datasource);
        HohenheimAccess.declareGrantableModels();
        HohenheimTestRuntime.ensureBooted();

        workRoot = Files.createTempDirectory("hohenheim-schedule-test");
        previousSnapshotPath = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Backup.SNAPSHOT_PATH);
        previousStagingPath = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Backup.STAGING_PATH);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.SNAPSHOT_PATH,
            workRoot.resolve("snapshots").toString());
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.STAGING_PATH,
            workRoot.resolve("staging").toString());
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(
            workRoot.resolve("test-keyring.keys")));

        if (PrivateNetns.available()) {
            netns = new PrivateNetns();
            WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
        }
    }

    @AfterAll
    static void tearDown() {
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
            netns = null;
        }
        FieldEncryption.installKeyring(null);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.SNAPSHOT_PATH,
            previousSnapshotPath);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.STAGING_PATH,
            previousStagingPath);
    }

    private static void assumeLiveDaemon() {
        Assumptions.assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        Assumptions.assumeTrue(netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");
    }

    // -- fixtures -------------------------------------------------------------

    private static int consoleInstance(String name) {
        Row template = Models.get(InstanceTemplateModel.class).createEmptyRow();
        template.set(InstanceTemplateModel.NAME, name + "-template");
        template.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        template.set(InstanceTemplateModel.STOP_COMMAND, "exit");
        Models.get(InstanceTemplateModel.class).save(template);

        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        // No command: alpine's /bin/sh reads console lines from OpenStdin stdin.
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine");
        settings.put("tag", "latest");
        settings.put("volumes", Map.of("data", "/data"));
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.TEMPLATE_ID, template.get(InstanceTemplateModel.ID));
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static int schedule(int instanceId, String name, Long runAs) {
        Row row = Models.get(RecordScheduleModel.class).createEmptyRow();
        row.set(RecordScheduleModel.MODEL, InstanceModel.MODEL_ID.toString());
        row.set(RecordScheduleModel.RECORD_ID, String.valueOf(instanceId));
        row.set(RecordScheduleModel.NAME, name);
        row.set(RecordScheduleModel.CRON, "0 4 * * *");
        row.set(RecordScheduleModel.ENABLED, true);
        row.set(RecordScheduleModel.RUN_AS, runAs);
        Models.get(RecordScheduleModel.class).save(row);
        return row.get(RecordScheduleModel.ID);
    }

    private static void step(int scheduleId, int position, String action, int offsetSeconds,
                             Map<String, Object> payload) {
        Row row = Models.get(RecordScheduleStepModel.class).createEmptyRow();
        row.set(RecordScheduleStepModel.SCHEDULE_ID, scheduleId);
        row.set(RecordScheduleStepModel.POSITION, position);
        row.set(RecordScheduleStepModel.ACTION, action);
        row.set(RecordScheduleStepModel.OFFSET_SECONDS, offsetSeconds);
        row.set(RecordScheduleStepModel.FAILURE_POLICY, RecordScheduleStepModel.POLICY_ABORT);
        if (payload != null) {
            row.set(RecordScheduleStepModel.PAYLOAD, payload);
        }
        Models.get(RecordScheduleStepModel.class).save(row);
    }

    private static String containerId(DockerClient docker, String handle) {
        try {
            Object id = docker.inspectContainer(handle).get("Id");
            return id == null ? "" : id.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static String execRead(DockerClient docker, String handle, String command) {
        try {
            DockerClient.ExecResult result = docker.exec(handle, List.of("sh", "-c", command));
            return result.exitCode() == 0 ? result.stdout().trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean await(long timeoutMs, java.util.function.Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return Boolean.TRUE.equals(condition.get());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stepsOf(Row run) {
        Object raw = run.get(RecordScheduleRunModel.STEP_RESULTS);
        Object steps = raw instanceof Map<?, ?> map
            ? ((Map<String, Object>) map).get(RecordScheduleRunModel.KEY_STEPS) : null;
        return steps instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    // -- the journey ----------------------------------------------------------

    @Test
    void warnRestartRevocationBackupAndDestroyJourney() throws Exception {
        assumeLiveDaemon();
        DockerClient docker = new DockerClient();

        // The schedule's OWNER: a real user with a real manage grant, both revocable.
        Row tenant = AuthModels.users().createEmptyRow();
        tenant.set(UserModel.EMAIL, "schedule-tenant@hohenheim.local");
        tenant.set(UserModel.DISPLAY_NAME, "Schedule Tenant");
        tenant.set(UserModel.ENABLED, true);
        tenant.set(UserModel.CREATED_AT, Instant.now());
        tenant.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(tenant);
        long tenantId = tenant.get(UserModel.ID);

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            int id = consoleInstance("schedule-journey");
            String handle = "hohenheim-instance-" + id;
            InstanceService service = new InstanceService();
            RecordSchedules recordSchedules = new RecordSchedules(datasource);

            RecordGrants.grant("user", (int) tenantId, InstanceModel.MODEL_ID, id,
                HohenheimAccess.MANAGE, true);
            RecordGrants.grant("user", (int) tenantId, InstanceModel.MODEL_ID, id,
                HohenheimAccess.BACKUPS, true);

            try {
                // 1. Deploy and reach a live console-capable workload.
                service.deploy(id);
                assertThat(await(15_000, () -> InstanceModel.STATUS_RUNNING.equals(
                        Models.get(InstanceModel.class).findById(id).get(InstanceModel.STATUS))))
                    .as("step 1: the instance record reaches running").isTrue();
                String beforeRestart = containerId(docker, handle);
                assertThat(beforeRestart).as("step 1: the daemon runs a container").isNotEmpty();

                // 2. THE Pterodactyl-parity chain: warn on the console, restart 2s later.
                int chainId = schedule(id, "restart with warning", tenantId);
                step(chainId, 1, InstanceConsoleCommandAction.ID.toString(), 0,
                    Map.of("command", "echo warned >> /data/marker"));
                step(chainId, 2, InstancePowerAction.ID.toString(), 2,
                    Map.of("operation", InstancePowerAction.OP_RESTART));

                Row chainRun = recordSchedules.runNow(chainId);
                assertThat(chainRun).as("step 2: the chain ran").isNotNull();
                assertThat(chainRun.get(RecordScheduleRunModel.STATUS))
                    .as("step 2: both steps completed")
                    .isEqualTo(RecordScheduleRunModel.STATUS_COMPLETED);

                // 3. Host truth: the warning reached the workload's console (persisted
                //    on the volume across the restart) and the restart REPLACED the
                //    container (new daemon id) while the record says running again.
                assertThat(await(15_000, () -> {
                    String now = containerId(docker, handle);
                    return !now.isEmpty() && !now.equals(beforeRestart);
                })).as("step 3: the restart produced a NEW container").isTrue();
                assertThat(await(15_000, () -> InstanceModel.STATUS_RUNNING.equals(
                        Models.get(InstanceModel.class).findById(id).get(InstanceModel.STATUS))))
                    .as("step 3: and the record is running again").isTrue();
                String marker = execRead(docker, handle, "cat /data/marker");
                assertThat(marker)
                    .as("step 3: the console warning was really delivered before the restart")
                    .isEqualTo("warned");

                // 4. REVOKE the manage grant. The stored schedule must stop working:
                //    no console line, no restart -- host state, not a log line.
                RecordGrants.revoke("user", (int) tenantId, InstanceModel.MODEL_ID, id,
                    HohenheimAccess.MANAGE);
                String beforeRevoked = containerId(docker, handle);

                Row revokedRun = recordSchedules.runNow(chainId);
                assertThat(revokedRun.get(RecordScheduleRunModel.STATUS))
                    .as("step 4: the run aborted on the refused step")
                    .isEqualTo(RecordScheduleRunModel.STATUS_ABORTED);
                assertThat(stepsOf(revokedRun).get(0).get(RecordScheduleRunModel.KEY_STATUS))
                    .as("step 4: the console step was REFUSED, not executed")
                    .isEqualTo(RecordScheduleRunModel.STEP_REFUSED);
                assertThat(execRead(docker, handle, "cat /data/marker"))
                    .as("step 4: no second console line reached the workload")
                    .isEqualTo("warned");
                assertThat(containerId(docker, handle))
                    .as("step 4: and no restart happened -- the container is untouched")
                    .isEqualTo(beforeRevoked);

                // 5. The migrated backup path: a per-record backup schedule (system
                //    authority, the M061 shape) produces a real COMPLETE artifact.
                Path targetDir = workRoot.resolve("target");
                Row targetRow = Models.get(BackupTargetModel.class).createEmptyRow();
                targetRow.set(BackupTargetModel.NAME, "schedule-target");
                targetRow.set(BackupTargetModel.KIND, "hohenheim:filesystem");
                targetRow.set(BackupTargetModel.SETTINGS, Map.of("path", targetDir.toString()));
                Models.get(BackupTargetModel.class).save(targetRow);
                Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
                    .assign(InstanceModel.BACKUP_TARGET_ID,
                        (Integer) targetRow.get(BackupTargetModel.ID))
                    .updateAll();

                int backupScheduleId = schedule(id, "Nightly backup", null);
                step(backupScheduleId, 1, InstanceBackupAction.ID.toString(), 0, null);

                Row backupRun = recordSchedules.runNow(backupScheduleId);
                assertThat(backupRun.get(RecordScheduleRunModel.STATUS))
                    .as("step 5: the backup chain completed")
                    .isEqualTo(RecordScheduleRunModel.STATUS_COMPLETED);
                Row backup = Models.get(InstanceBackupModel.class).find()
                    .where(InstanceBackupModel.INSTANCE_ID.eq(id)).first();
                assertThat(backup).as("step 5: a backup row exists").isNotNull();
                assertThat(backup.get(InstanceBackupModel.STATUS))
                    .as("step 5: and it is complete")
                    .isEqualTo(InstanceBackupModel.STATUS_COMPLETE);
                assertThat(Files.isRegularFile(
                        targetDir.resolve(backup.get(InstanceBackupModel.REMOTE_KEY))))
                    .as("step 5: the artifact really exists at the target").isTrue();

                // 6. Destroy SOFT-deletes the record (remove hooks never fire), and the
                //    schedules must die with it anyway.
                service.destroy(id);
                assertThat(Models.get(InstanceModel.class).findById(id)
                        .get(InstanceModel.DELETED_AT))
                    .as("step 6: the record is soft-deleted, not removed").isNotNull();
                assertThat(Models.get(RecordScheduleModel.class)
                        .findForRecord(InstanceModel.MODEL_ID, id))
                    .as("step 6: every schedule of the record is gone").isEmpty();
                assertThat(Models.get(RecordScheduleStepModel.class).find()
                        .where(RecordScheduleStepModel.SCHEDULE_ID.in(
                            List.of(chainId, backupScheduleId))).count())
                    .as("step 6: their steps are gone").isEqualTo(0);
                assertThat(Models.get(RecordScheduleRunModel.class).find()
                        .where(RecordScheduleRunModel.SCHEDULE_ID.in(
                            List.of(chainId, backupScheduleId))).count())
                    .as("step 6: their run history is gone").isEqualTo(0);
            } finally {
                try {
                    new InstanceService().destroy(id);
                } catch (RuntimeException ignored) {
                    // Already destroyed in step 6; leave no container behind either way.
                }
            }
        });
    }
}
