package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
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
            rendered.add(item.severity() + " " + item.url());
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
}
