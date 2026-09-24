package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A destroyed instance never pins its backup target: its destination pointer is history and
 * is detached when the target goes, while the backups it left behind still protect the target.
 *
 * AIDEV-NOTE: the refusal used to count TRASHED instances, and nothing purges a trashed row,
 * so under enforced foreign keys every target a destroyed workload ever backed up to could
 * never be deleted -- the dead end ServerModel had for hosts. Daemon-free: every decision is
 * over stored record state.
 */
class BackupTargetRemovalTest {

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void aTrashedInstanceNeverPinsItsBackupTarget() throws Exception {
        String path = Files.createTempDirectory("hohenheim-target-removal").toAbsolutePath().toString();
        Db.run(datasource, () -> {
            Row target = Models.get(BackupTargetModel.class).createEmptyRow();
            target.set(BackupTargetModel.NAME, "removal-target");
            target.set(BackupTargetModel.KIND, "hohenheim:filesystem");
            target.set(BackupTargetModel.SETTINGS, Map.of("path", path));
            Models.get(BackupTargetModel.class).save(target);
            int targetId = target.get(BackupTargetModel.ID);
            int instanceId = instanceBackingUpTo("removal-workload", targetId);

            // 1. A LIVE instance that backs up to the target still refuses its removal.
            assertThat(violationKeyOf(catchThrowable(() ->
                    Models.get(BackupTargetModel.class).delete((Object) targetId))))
                .as("step 1: a live destination refuses by name").isEqualTo("backup_target_in_use");

            // 2. Destroyed (soft-deleted), with a COMPLETE backup it left behind: the backup is
            //    the recovery asset and still refuses; the trashed pointer alone no longer would.
            Row instance = StoredRows.byId(Models.get(InstanceModel.class), instanceId);
            instance.set(InstanceModel.DELETED_AT, Now.instant());
            Models.get(InstanceModel.class).save(instance);
            int backupId = backupRow(instanceId, targetId, InstanceBackupModel.STATUS_COMPLETE);
            Throwable byBackup = catchThrowable(() ->
                Models.get(BackupTargetModel.class).delete((Object) targetId));
            assertThat(violationKeyOf(byBackup))
                .as("step 2: a COMPLETE backup of a destroyed instance still protects the target")
                .isEqualTo("backup_target_in_use");
            assertThat(((Violations) byBackup).all().get(0).message().args().get("instances"))
                .as("step 2: and the refusal counts no instance -- the trashed one is history")
                .isEqualTo(0L);

            // 3. With only a FAILED backup left, the target goes: the trashed instance and the
            //    evidence row are kept, each with its pointer detached.
            Models.get(InstanceBackupModel.class).find()
                .where(InstanceBackupModel.ID.eq(backupId))
                .assign(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_FAILED)
                .updateAll();
            Models.get(BackupTargetModel.class).delete((Object) targetId);
            assertThat(Models.get(BackupTargetModel.class).findById(targetId))
                .as("step 3: the target a destroyed instance used is deletable").isNull();
            // Trashed: a default find hides it by design, so the kept row is read as stored.
            Row trashed = StoredRows.byId(Models.get(InstanceModel.class), instanceId);
            assertThat(trashed).as("step 3: the trashed instance row is kept").isNotNull();
            assertThat((Object) trashed.get(InstanceModel.BACKUP_TARGET_ID))
                .as("step 3: with its destination pointer detached").isNull();
            Row evidence = Models.get(InstanceBackupModel.class).findById(backupId);
            assertThat(evidence).as("step 3: the FAILED backup row is kept as evidence").isNotNull();
            assertThat((Object) evidence.get(InstanceBackupModel.TARGET_ID))
                .as("step 3: with its target pointer detached").isNull();
        });
    }

    private static int instanceBackingUpTo(String name, int targetId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "command", "sleep 60"));
        row.set(InstanceModel.BACKUP_TARGET_ID, targetId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static int backupRow(int instanceId, int targetId, String status) {
        Row row = Models.get(InstanceBackupModel.class).createEmptyRow();
        row.set(InstanceBackupModel.INSTANCE_ID, instanceId);
        row.set(InstanceBackupModel.TARGET_ID, targetId);
        row.set(InstanceBackupModel.STATUS, status);
        row.set(InstanceBackupModel.REMOTE_KEY, "removal/" + instanceId + ".tar.age");
        Models.get(InstanceBackupModel.class).save(row);
        return row.get(InstanceBackupModel.ID);
    }

    private static String violationKeyOf(Throwable thrown) {
        assertThat(thrown).as("the delete was refused with Violations").isInstanceOf(Violations.class);
        return ((Violations) thrown).all().get(0).message().key();
    }
}
