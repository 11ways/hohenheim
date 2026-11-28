package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.migration.M009_StorageChoices;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.migration.MigrationKey;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.test.support.TestSqliteFiles;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static be.elevenways.hohenheim.test.migration.InstallsAt.withIntegrityMode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live upgrade from the published M008 adds the storage screens' columns, lets every operator's storage declared
 * before reach private addresses as it did and keeps every storage a customer held from them; its rollback drops
 * exactly those columns and keeps every row.
 *
 * AIDEV-NOTE: SQLite alone, as the hosts run (SqliteOnlyDatabaseGuardTest): the published M001_HohenheimBaseline
 * cannot be applied on PostgreSQL (it creates managed_databases with its foreign key to database_engines before that
 * table exists), so no Hohenheim install reaches M009 there; the journey reads the backend only through
 * {@link #columnsOf}, ready for the day the baseline runs elsewhere.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class StorageChoicesMigrationTest {

    private static final List<String> STORAGE_COLUMNS = List.of("private_addresses_allowed", "shared", "added_by");

    private static final List<String> DATABASE_COLUMNS = List.of("backup_target_id", "dumps_kept");

    @Test
    void theUpgradeKeepsOperatorsReachAndTheRollbackDropsOnlyItsColumns() throws Exception {
        File db = File.createTempFile("hohenheim-storage-choices", ".db");
        db.delete();
        db.deleteOnExit();
        // A published install holds M001 to M008 as the live hosts applied them.
        InstallsAt.migrateBelow(db.toPath(), new M009_StorageChoices().getVersion());
        SqliteDatasource datasource = new SqliteDatasource(TestSqliteFiles.url(db.toPath()));
        try {
            upgradeAndRollBack(datasource, "1");
        } finally {
            datasource.close();
        }
    }

    /**
     * The journey from the schema M008 left.
     *
     * @param truth the backend's spelling of a stored true in SQL text
     */
    private static void upgradeAndRollBack(SqlDatasource datasource, String truth) {
        // 1. Three storages as the hosts hold them: the operator's nightly dumps' folder, a customer's bucket whose
        //    manage grant is live, and one whose customer's grant expired; no switch, no sharing, no adder yet.
        datasource.rawUpdate("INSERT INTO backup_targets (id, name, kind, settings) VALUES"
            + " (1, 'Backup disk', 'hohenheim:filesystem', '{\"path\":\"/mnt/backup/hohenheim\"}'),"
            + " (2, 'Customer bucket', 'storage_s3', '{}'), (3, 'Former customer', 'storage_s3', '{}')");
        datasource.rawUpdate("INSERT INTO auth_record_grants (id, subject_type, subject_id, model, record_id,"
            + " capability, value, expires_at) VALUES"
            + " ('g-live', 'user', '0190f0a0-0000-7000-8000-000000000001', 'hohenheim:backup_target', '2', 'manage', "
            + truth + ", NULL),"
            + " ('g-expired', 'user', '0190f0a0-0000-7000-8000-000000000002', 'hohenheim:backup_target', '3', "
            + "'manage', " + truth + ", '2026-01-01 00:00:00')");
        assertThat(BackupTargetModel.MODEL_ID.toString()).as("step 1: the grants name storages by this model id")
            .isEqualTo("hohenheim:backup_target");
        assertThat(HohenheimCapabilities.MANAGE).as("step 1: and their ownership by this capability")
            .isEqualTo("manage");
        List<String> storagesBefore = columnsOf(datasource, "backup_targets");
        List<String> databasesBefore = columnsOf(datasource, "managed_databases");
        assertThat(storagesBefore).as("step 1: the published storages have no switch yet")
            .doesNotContainAnyElementsOf(STORAGE_COLUMNS);
        assertThat(databasesBefore).as("step 1: the published databases choose no storage yet")
            .doesNotContainAnyElementsOf(DATABASE_COLUMNS);

        // 2. The upgrade, under the strict integrity posture the hosts run, applies M009 and what came after it;
        //    those later ones are rolled back, so the steps below read the schema M009 leaves.
        List<MigrationKey> applied = fromM009(datasource);
        withIntegrityMode("fail", () -> assertThat(new MigrationRunner(datasource).migrate().requireSuccess()
            .getAppliedCount()).as("step 2: the upgrade applies M009 and its successors").isEqualTo(applied.size()));
        List<MigrationKey> later = new ArrayList<>(applied.subList(1, applied.size()));
        if (!later.isEmpty()) {
            new MigrationRunner(datasource).rollback(later).requireSuccess();
        }

        // 3. The operator's storage keeps reaching private addresses; neither customer's ever does. None is shared,
        //    who added them is not known, and a database chooses no storage and number of its own until someone does.
        assertThat(columnsOf(datasource, "backup_targets")).as("step 3: the storage columns")
            .containsAll(STORAGE_COLUMNS).hasSize(storagesBefore.size() + STORAGE_COLUMNS.size());
        assertThat(columnsOf(datasource, "managed_databases")).as("step 3: the database columns")
            .containsAll(DATABASE_COLUMNS).hasSize(databasesBefore.size() + DATABASE_COLUMNS.size());
        List<Boolean> allowed = new ArrayList<>();
        List<Boolean> shared = new ArrayList<>();
        for (var storage : datasource.rawQuery("SELECT private_addresses_allowed, shared, added_by FROM backup_targets"
                + " ORDER BY id")) {
            allowed.add(truthy(storage.get("private_addresses_allowed")));
            shared.add(truthy(storage.get("shared")));
            assertThat(storage.get("added_by")).as("step 3: who added it is not known").isNull();
        }
        assertThat(allowed).as("step 3: the operator's reach kept, the customers' storages kept from it")
            .containsExactly(true, false, false);
        assertThat(shared).as("step 3: shared with nobody").containsExactly(false, false, false);

        // 4. The rollback drops the five columns and keeps the storages.
        new MigrationRunner(datasource).rollback(List.of(MigrationKey.of(new M009_StorageChoices())))
            .requireSuccess();
        assertThat(columnsOf(datasource, "backup_targets")).as("step 4: the storages table is as it was")
            .containsExactlyInAnyOrderElementsOf(storagesBefore);
        assertThat(columnsOf(datasource, "managed_databases")).as("step 4: the databases table is as it was")
            .containsExactlyInAnyOrderElementsOf(databasesBefore);
        assertThat(((Number) datasource.rawQuery("SELECT COUNT(*) AS c FROM backup_targets").get(0).get("c"))
            .longValue()).as("step 4: the storages survive").isEqualTo(3L);
    }

    /** @return M009 and every later migration of Hohenheim's stream, M009 first */
    private static List<MigrationKey> fromM009(SqlDatasource datasource) {
        String version = new M009_StorageChoices().getVersion();
        return InstallsAt.hohenheimMigrations(datasource.getDatasourceIdentifier()).stream()
            .filter(migration -> migration.getVersion().compareTo(version) >= 0).map(MigrationKey::of).toList();
    }

    private static List<String> columnsOf(SqlDatasource datasource, String table) {
        String sql = datasource instanceof SqliteDatasource ? "SELECT name FROM pragma_table_info('" + table + "')"
            : "SELECT column_name AS name FROM information_schema.columns WHERE table_schema = current_schema()"
                + " AND table_name = '" + table + "' ORDER BY ordinal_position";
        return datasource.rawQuery(sql).stream().map(row -> String.valueOf(row.get("name"))).toList();
    }

    private static boolean truthy(Object stored) {
        return stored instanceof Number number ? number.intValue() != 0 : Boolean.TRUE.equals(stored);
    }
}
