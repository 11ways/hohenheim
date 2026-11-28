package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.M009_StorageChoices;
import be.elevenways.zenit.common.orm.migration.MigrationKey;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.test.support.TestSqliteFiles;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static be.elevenways.hohenheim.test.migration.InstallsAt.withIntegrityMode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live upgrade from the published M008 adds the storage screens' columns and lets every storage declared before
 * reach private addresses as it did; its rollback drops exactly those columns and keeps every row.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class StorageChoicesMigrationTest {

    private static final List<String> STORAGE_COLUMNS = List.of("private_addresses_allowed", "shared", "added_by");

    private static final List<String> DATABASE_COLUMNS = List.of("backup_target_id", "dumps_kept");

    @Test
    void theUpgradeKeepsEveryStoragesReachAndTheRollbackDropsOnlyItsColumns() throws Exception {
        String version = new M009_StorageChoices().getVersion();
        File db = File.createTempFile("hohenheim-storage-choices", ".db");
        db.delete();
        db.deleteOnExit();

        // 1. A published install holds M001 to M008 as the live hosts applied them, and an operator's storage as
        //    Robbedoes stores its nightly dumps' folder: no switch, no sharing, no one named as its adder.
        InstallsAt.migrateBelow(db.toPath(), version);
        SqliteDatasource datasource = new SqliteDatasource(TestSqliteFiles.url(db.toPath()));
        try {
            datasource.rawUpdate("INSERT INTO backup_targets (id, name, kind, settings) VALUES"
                + " (1, 'Backup disk', 'hohenheim:filesystem', '{\"path\":\"/mnt/backup/hohenheim\"}')");
            List<String> storagesBefore = columnsOf(datasource, "backup_targets");
            List<String> databasesBefore = columnsOf(datasource, "managed_databases");
            assertThat(storagesBefore).as("step 1: the published storages have no switch yet")
                .doesNotContainAnyElementsOf(STORAGE_COLUMNS);
            assertThat(databasesBefore).as("step 1: the published databases choose no storage yet")
                .doesNotContainAnyElementsOf(DATABASE_COLUMNS);

            // 2. The upgrade, under the strict integrity posture the hosts run, applies M009 and what came after it;
            //    those later ones are rolled back, so the steps below read the schema M009 leaves.
            List<MigrationKey> later = InstallsAt.hohenheimMigrations(datasource.getDatasourceIdentifier()).stream()
                .filter(migration -> migration.getVersion().compareTo(version) > 0).map(MigrationKey::of).toList();
            withIntegrityMode("fail", () -> assertThat(new MigrationRunner(datasource).migrate().requireSuccess()
                .getAppliedCount()).as("step 2: the upgrade applies M009 and its successors")
                .isEqualTo(1 + later.size()));
            if (!later.isEmpty()) {
                new MigrationRunner(datasource).rollback(later).requireSuccess();
            }

            // 3. The storage keeps reaching private addresses as it did, shared with nobody, its adder unknown; a
            //    database chooses no storage and number of its own until someone does.
            assertThat(columnsOf(datasource, "backup_targets")).as("step 3: the storage columns")
                .containsAll(STORAGE_COLUMNS).hasSize(storagesBefore.size() + STORAGE_COLUMNS.size());
            assertThat(columnsOf(datasource, "managed_databases")).as("step 3: the database columns")
                .containsAll(DATABASE_COLUMNS).hasSize(databasesBefore.size() + DATABASE_COLUMNS.size());
            var storage = datasource.rawQuery("SELECT private_addresses_allowed, shared, added_by FROM backup_targets"
                + " WHERE id = 1").get(0);
            assertThat(truthy(storage.get("private_addresses_allowed"))).as("step 3: its private reach is kept")
                .isTrue();
            assertThat(truthy(storage.get("shared"))).as("step 3: shared with nobody").isFalse();
            assertThat(storage.get("added_by")).as("step 3: who added it is not known").isNull();

            // 4. The rollback drops the five columns and keeps the storage.
            new MigrationRunner(datasource).rollback(List.of(MigrationKey.of(new M009_StorageChoices())))
                .requireSuccess();
            assertThat(columnsOf(datasource, "backup_targets")).as("step 4: the storages table is as it was")
                .containsExactlyElementsOf(storagesBefore);
            assertThat(columnsOf(datasource, "managed_databases")).as("step 4: the databases table is as it was")
                .containsExactlyElementsOf(databasesBefore);
            assertThat(((Number) datasource.rawQuery("SELECT COUNT(*) AS c FROM backup_targets").get(0).get("c"))
                .longValue()).as("step 4: the storage survives").isEqualTo(1L);
        } finally {
            datasource.close();
        }
    }

    private static List<String> columnsOf(SqliteDatasource datasource, String table) {
        return datasource.rawQuery("SELECT name FROM pragma_table_info('" + table + "')").stream()
            .map(row -> String.valueOf(row.get("name"))).toList();
    }

    private static boolean truthy(Object stored) {
        return stored instanceof Number number ? number.intValue() != 0 : Boolean.TRUE.equals(stored);
    }
}
