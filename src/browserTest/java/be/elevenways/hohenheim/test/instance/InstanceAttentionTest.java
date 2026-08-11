package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.server.task.BackupControlPlane;
import be.elevenways.hohenheim.server.task.CleanOldProclogs;
import be.elevenways.zenit.common.task.TaskStatus;
import be.elevenways.zenit.common.task.orm.SystemTaskHistoryModel;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three instance attention collectors, each proven BOTH ways: it fires on the real
 * condition and stays silent on the healthy one. A collector that always fires is the same
 * defect as one that never does, so every step below has a negative half.
 */
class InstanceAttentionTest {

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-instance-attention-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
    }

    private static int instance(String name, String status) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS,
            new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
        row.set(InstanceModel.STATUS, status);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static void backup(int instanceId, String status) {
        Row row = Models.get(InstanceBackupModel.class).createEmptyRow();
        row.set(InstanceBackupModel.INSTANCE_ID, instanceId);
        row.set(InstanceBackupModel.STATUS, status);
        row.set(InstanceBackupModel.ERROR,
            InstanceBackupModel.STATUS_FAILED.equals(status) ? "target refused the upload" : null);
        Models.get(InstanceBackupModel.class).save(row);
    }

    private static void observeDisk(int instanceId, long used, long limit) {
        Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .assign(InstanceModel.DISK_USED_BYTES, used)
            .assign(InstanceModel.DISK_LIMIT_BYTES, limit)
            .assign(InstanceModel.DISK_OBSERVED_AT, Instant.now())
            .updateAll();
    }

    /** Each item as "severity url": the two facts a dashboard reader acts on. */
    private static List<String> raised(java.util.function.Consumer<List<AttentionItem>> collector) {
        List<AttentionItem> items = new ArrayList<>();
        collector.accept(items);
        List<String> rendered = new ArrayList<>();
        for (AttentionItem item : items) {
            rendered.add(item.severity() + " " + targetUrl(item));
        }
        return rendered;
    }

    @Test
    void eachInstanceCollectorFiresOnItsConditionAndOnlyOnIt() {
        Db.run(datasource, () -> {
            int healthy = instance("attn-healthy", InstanceModel.STATUS_RUNNING);
            int crashed = instance("attn-crashed", InstanceModel.STATUS_ERROR);
            int stopped = instance("attn-stopped", InstanceModel.STATUS_STOPPED);

            // 1. CRASHED: only the errored instance is named. A running one and a
            //    deliberately stopped one are not incidents.
            assertThat(raised(AttentionCollector::crashedInstances))
                .as("step 1: exactly the crashed instance surfaces, as an error linked to"
                    + " its console -- not the running one, not the stopped one")
                .containsExactly("error /admin/instances/" + crashed + "/page/console");

            // 2. Soft-deleting the crashed instance silences it: an item about a record in
            //    the trash is noise nobody can act on.
            Row trashed = Models.get(InstanceModel.class).findById(crashed);
            trashed.set(InstanceModel.DELETED_AT, Instant.now());
            Models.get(InstanceModel.class).save(trashed);
            assertThat(raised(AttentionCollector::crashedInstances))
                .as("step 2: a trashed instance raises nothing").isEmpty();

            // 3. BACKUP FAILED: only the LATEST attempt speaks. An instance whose old
            //    failure was followed by a success is healthy; one whose newest attempt
            //    failed is not.
            backup(healthy, InstanceBackupModel.STATUS_FAILED);
            backup(healthy, InstanceBackupModel.STATUS_COMPLETE);
            backup(stopped, InstanceBackupModel.STATUS_FAILED);
            assertThat(raised(AttentionCollector::failedInstanceBackups))
                .as("step 3: only the instance whose NEWEST backup failed surfaces; the one"
                    + " that recovered does not, because an old failure is not news")
                .containsExactly("error /admin/instances/" + stopped + "/page/backups");

            // 4. DISK: never measured means silence, not zero. This is the Docker tier's
            //    permanent state, so it must not produce a single item.
            assertThat(raised(AttentionCollector::instancesLowOnDisk))
                .as("step 4: an unmeasured disk raises nothing at all").isEmpty();

            // 5. Measured but comfortable: still silence.
            observeDisk(healthy, 1_000_000_000L, 10_000_000_000L);
            assertThat(raised(AttentionCollector::instancesLowOnDisk))
                .as("step 5: 10% of an enforced ceiling is not an alarm").isEmpty();

            // 6. Measured WITHOUT an enforced ceiling (limit 0, the incus shape for a
            //    workload that declares no root size): still silence, because there is no
            //    ceiling to be near. A collector dividing by the pool size would fire here.
            observeDisk(stopped, 9_000_000_000L, 0L);
            assertThat(raised(AttentionCollector::instancesLowOnDisk))
                .as("step 6: a workload with no enforced ceiling has no percentage, so a"
                    + " 9 GB occupancy raises nothing").isEmpty();

            // 7. Measured and nearly full: the item finally fires, and only for that one.
            observeDisk(healthy, 9_600_000_000L, 10_000_000_000L);
            assertThat(raised(AttentionCollector::instancesLowOnDisk))
                .as("step 7: 96% of a real, enforced ceiling raises exactly one ERROR item,"
                    + " and the unbounded workload still stays quiet")
                .containsExactly("error /admin/instances/" + healthy);

            // 8. Between the two thresholds it is a WARNING, not an error: "worth watching"
            //    and "about to break" are different operator problems.
            observeDisk(healthy, 8_800_000_000L, 10_000_000_000L);
            assertThat(raised(AttentionCollector::instancesLowOnDisk))
                .as("step 8: 88% is a warning, not an error")
                .containsExactly("warning /admin/instances/" + healthy);
        });
    }

    /**
     * The backup FRESHNESS projection: the latest-FAILED collector answers "is it
     * failing right now", this one answers "when did it last succeed" -- an instance
     * with a declared backup target and no (recent) COMPLETE backup must not read as
     * green. What this cannot prove: the real nightly cadence; ages are forged.
     */
    @Test
    void anInstanceWithABackupTargetButNoRecentSuccessStopsReadingAsGreen() {
        Db.run(datasource, () -> {
            int covered = instance("attn-fresh-covered", InstanceModel.STATUS_RUNNING);
            int uncovered = instance("attn-fresh-uncovered", InstanceModel.STATUS_RUNNING);
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.STALE_AFTER_DAYS, 7);
            Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(covered))
                .assign(InstanceModel.BACKUP_TARGET_ID, 4242)
                .updateAll();

            // 1. A declared target and ZERO completed backups: the never-backed-up item
            //    fires. The instance WITHOUT a target stays silent -- no target means no
            //    declared intent, and alarming it would drown the signal.
            assertThat(raisedKeys(AttentionCollector::staleInstanceBackups))
                .as("step 1: exactly the target-declared instance raises the"
                    + " never-backed-up item")
                .containsExactly("instance_backup_never warning /admin/instances/"
                    + covered + "/page/backups");

            // 2. A FAILED attempt is not a success: still the never item (the failure
            //    itself is the OTHER collector's error).
            backup(covered, InstanceBackupModel.STATUS_FAILED);
            assertThat(raisedKeys(AttentionCollector::staleInstanceBackups))
                .as("step 2: failed attempts do not count as coverage")
                .containsExactly("instance_backup_never warning /admin/instances/"
                    + covered + "/page/backups");

            // 3. A recent COMPLETE backup: silence.
            backup(covered, InstanceBackupModel.STATUS_COMPLETE);
            assertThat(raisedKeys(AttentionCollector::staleInstanceBackups))
                .as("step 3: a fresh completed backup silences the projection").isEmpty();

            // 4. Age that success beyond the window: the STALE item fires -- last
            //    success six months ago must not look like last night.
            Models.get(InstanceBackupModel.class).find()
                .where(InstanceBackupModel.INSTANCE_ID.eq(covered))
                .where(InstanceBackupModel.STATUS.eq(InstanceBackupModel.STATUS_COMPLETE))
                .assign(InstanceBackupModel.CREATED_AT,
                    Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS))
                .updateAll();
            assertThat(raisedKeys(AttentionCollector::staleInstanceBackups))
                .as("step 4: a success older than the window raises the stale item")
                .containsExactly("instance_backup_stale warning /admin/instances/"
                    + covered + "/page/backups");

            // 5. The check is an operator dial: 0 disables it entirely.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.STALE_AFTER_DAYS, 0);
            assertThat(raisedKeys(AttentionCollector::staleInstanceBackups))
                .as("step 5: stale_after_days 0 disables the freshness check").isEmpty();
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.STALE_AFTER_DAYS, 7);

            trash(covered);
            trash(uncovered);
        });
    }

    /**
     * The task projection judges every DECLARED type by ITS OWN newest history row: a
     * nightly task's failure must survive a flood of newer rows from chattier tasks
     * (the old recent-200 window was a few hours deep and went green by mid-morning),
     * and the control-plane freshness item fires when the newest COMPLETED run of the
     * backup task is too old -- a capability ("a destination is configured") is not an
     * observation ("archives are actually landing").
     */
    @Test
    void aNightlyTaskFailureIsNotBuriedByChattierTasksAndBackupFreshnessIsObserved() {
        Db.run(datasource, () -> {
            // The lite boot never starts the task service, so its datasource-scoped
            // model is registered here (the table exists: M001_CreateSystemTaskTables
            // is auto-discovered by the migration runner).
            if (Models.get(SystemTaskHistoryModel.MODEL_ID) == null) {
                Models.registerInstance(new SystemTaskHistoryModel(datasource));
            }
            String nightly = BackupControlPlane.class.getName();
            String chatty = CleanOldProclogs.class.getName();

            // 1. One FAILED nightly run, then 250 newer successful runs of another task.
            taskRun(nightly, TaskStatus.FAILED,
                Instant.now().minus(9, java.time.temporal.ChronoUnit.HOURS));
            for (int i = 0; i < 250; i++) {
                taskRun(chatty, TaskStatus.COMPLETED,
                    Instant.now().minusSeconds(250 - i));
            }
            assertThat(raisedKeys(AttentionCollector::failedTasks))
                .as("step 1: the nightly failure is judged by its OWN newest row, so 250"
                    + " newer rows of a chattier task cannot scroll it out of sight")
                .contains("task warning ");

            // 2. Freshness: a destination is configured but the newest COMPLETED backup
            //    run is 3 days old -- the observation item fires.
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET, "attn-target");
            taskRun(nightly, TaskStatus.COMPLETED,
                Instant.now().minus(3, java.time.temporal.ChronoUnit.DAYS));
            assertThat(raisedKeys(AttentionCollector::controlPlaneBackupFreshness))
                .as("step 2: a stale newest success raises the freshness item")
                .containsExactly("control_plane_backup_stale error /admin/settings");

            // 3. A COMPLETED run within the window silences it.
            taskRun(nightly, TaskStatus.COMPLETED,
                Instant.now().minus(6, java.time.temporal.ChronoUnit.HOURS));
            assertThat(raisedKeys(AttentionCollector::controlPlaneBackupFreshness))
                .as("step 3: a recent success is silence").isEmpty();

            // 4. And that recent success also clears the failed-tasks item for the type.
            assertThat(raisedKeys(AttentionCollector::failedTasks))
                .as("step 4: the newest row of the nightly type is now COMPLETED, so its"
                    + " failure item clears; no other declared type has news")
                .isEmpty();

            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET, "");
        });
    }

    private static void taskRun(String typePath, TaskStatus status, Instant startedAt) {
        var model = (SystemTaskHistoryModel) Models.get(SystemTaskHistoryModel.MODEL_ID);
        Row row = model.createEmptyRow();
        row.set(SystemTaskHistoryModel.TASK_TYPE, typePath);
        row.set(SystemTaskHistoryModel.STATUS, status.name());
        row.set(SystemTaskHistoryModel.STARTED_AT, startedAt);
        model.save(row);
    }

    private static void trash(int instanceId) {
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        row.set(InstanceModel.DELETED_AT, Instant.now());
        Models.get(InstanceModel.class).save(row);
    }

    /** Each item as "titleKey severity url": key included where two claims share a url. */
    private static List<String> raisedKeys(
            java.util.function.Consumer<List<AttentionItem>> collector) {
        List<AttentionItem> items = new ArrayList<>();
        collector.accept(items);
        List<String> rendered = new ArrayList<>();
        for (AttentionItem item : items) {
            rendered.add(item.title().key() + " " + item.severity() + " "
                + targetUrl(item));
        }
        return rendered;
    }

    /**
     * The item's destination as a path, blank when it has none -- the contract the
     * removed {@code AttentionItem.url()} had, kept so this projection still pins URL
     * LITERALS rather than rebuilding them from the same builder the code uses.
     */
    private static String targetUrl(AttentionItem item) {
        return item.target() == null ? "" : item.target().toUrl();
    }
}
