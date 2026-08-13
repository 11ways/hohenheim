package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.hohenheim.migration.InitialMigration;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationResult;
import be.elevenways.zenit.common.orm.migration.MigrationRunnerResult;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.setting.DryFileSource;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The install contract of the ONE hohenheim migration: a fresh database migrates,
 * re-migrates and passes strict integrity; the schema it produced really carries the
 * constraints the code relies on; and down() undoes it completely enough that up() can
 * rebuild it.
 *
 * AIDEV-NOTE: there is deliberately no golden checksum ledger any more. It existed to
 * catch an edit to an ALREADY-SHIPPED migration, which was the right guard while the
 * schema grew by appending M003..M092. Hohenheim has no installations, so editing this
 * one migration in place IS the sanctioned way to change the schema (see
 * InitialMigration's own note) -- a pinned digest would fail on every legitimate change
 * and teach people to regenerate it without reading, which is worse than not having it.
 */
class MigrationIntegrityTest {

    @Test
    void aFreshInstallMigratesReMigratesAndPassesStrictIntegrity() throws Exception {
        SqliteDatasource datasource = emptyDatabase("fresh");

        // 1. Auto-discovery (never a hand-written list) builds the whole schema in one pass.
        MigrationRunnerResult first = new MigrationRunner(datasource).migrate();
        assertThat(first.isSuccess())
            .as("fresh migrate failed: %s", failureDetail(first))
            .isTrue();
        assertThat(first.getAppliedCount()).as("a fresh install applies migrations").isPositive();

        // 2. The table-stored SchemaField child tables really exist with their full column
        //    sets: they are the shape most easily lost when folding a chain into one create.
        assertThat(columnsOf(datasource, "SELECT name FROM pragma_table_info('stack_services_mounts')"))
            .as("mounts child table")
            .containsExactly("id", "stack_service_id", "order_key", "type", "name",
                "container_path", "external_name", "created_at", "updated_at");
        assertThat(columnsOf(datasource, "SELECT name FROM pragma_table_info('stack_services_ports')"))
            .as("ports child table")
            .containsExactly("id", "stack_service_id", "order_key", "container_port", "host_port",
                "protocol", "host_ip", "created_at", "updated_at");
        assertThat(columnsOf(datasource, "SELECT name FROM pragma_table_info('stack_services_depends_on')"))
            .as("depends_on child table")
            .containsExactly("id", "stack_service_id", "order_key", "service", "condition",
                "created_at", "updated_at");

        // 3. Migrating again is a clean no-op: the history table already covers every version.
        MigrationRunnerResult second = new MigrationRunner(datasource).migrate();
        assertThat(second.isSuccess()).as("second migrate must not fail").isTrue();
        assertThat(second.getAppliedCount()).as("second migrate applies nothing").isZero();

        // 4. A fresh install has zero integrity findings, so the SHIPPED posture boots green.
        //    Pinned explicitly so the guarantee survives an ambient settings change.
        withIntegrityMode("fail", () -> {
            MigrationRunnerResult strict = new MigrationRunner(datasource).migrate();
            assertThat(strict.isSuccess()).as("strict migrate on a clean install").isTrue();
        });
    }

    /**
     * The uniqueness the storage layer -- not application code -- must enforce, including the
     * two constraints folded in with the consolidation (a backup target's name and a DNS
     * record's dyndns credential were only indexed, never constrained).
     */
    @Test
    void theSchemaRefusesTheDuplicatesTheCodeAssumesCannotExist() throws Exception {
        SqliteDatasource datasource = emptyDatabase("uniques");
        new MigrationRunner(datasource).migrate().requireSuccess();

        // 1. A site slug is the public identity of a site.
        datasource.rawUpdate("INSERT INTO sites (id, name, slug) VALUES (1, 'A', 'shared')");
        assertThatThrownBy(() -> datasource.rawUpdate(
                "INSERT INTO sites (id, name, slug) VALUES (2, 'B', 'shared')"))
            .as("step 1: two sites may not share a slug");

        // 2. A service name is unique WITHIN its stack, not globally.
        datasource.rawUpdate("INSERT INTO stacks (id, name) VALUES (1, 'app')");
        datasource.rawUpdate("INSERT INTO stacks (id, name) VALUES (2, 'other')");
        datasource.rawUpdate("INSERT INTO stack_services (id, stack_id, name) VALUES (1, 1, 'web')");
        datasource.rawUpdate("INSERT INTO stack_services (id, stack_id, name) VALUES (2, 2, 'web')");
        assertThatThrownBy(() -> datasource.rawUpdate(
                "INSERT INTO stack_services (id, stack_id, name) VALUES (3, 1, 'web')"))
            .as("step 2: one stack may not hold two services of one name");

        // 3. Backup target names: every backup row and schedule refers to a target by NAME,
        //    so two targets sharing one would make every reference ambiguous.
        datasource.rawUpdate("INSERT INTO backup_targets (id, name, kind) VALUES (1, 'nightly', 'local')");
        assertThatThrownBy(() -> datasource.rawUpdate(
                "INSERT INTO backup_targets (id, name, kind) VALUES (2, 'nightly', 's3')"))
            .as("step 3: two backup targets may not share a name");

        // 4. Dyndns credentials: the resolver takes the first row it finds for a record, so
        //    a second credential per record would silently decide which token authenticates.
        datasource.rawUpdate("INSERT INTO dns_zones (id, origin) VALUES (1, 'z.test')");
        datasource.rawUpdate("INSERT INTO dns_records (id, zone_id, name) VALUES (1, 1, 'home')");
        datasource.rawUpdate(
            "INSERT INTO dns_dyndns_credentials (id, record_id, token_digest) VALUES (1, 1, 'a')");
        assertThatThrownBy(() -> datasource.rawUpdate(
                "INSERT INTO dns_dyndns_credentials (id, record_id, token_digest) VALUES (2, 1, 'b')"))
            .as("step 4: a DNS record may not hold two dyndns credentials");
    }

    /**
     * The backup-target references really are DECLARED as foreign keys, and really do
     * refuse once enforcement is switched on.
     *
     * AIDEV-NOTE: read what this proves and what it does not. BackupTargetModel's
     * before-remove hook is SELECT-count-then-DELETE and therefore raceable; the natural
     * durable answer is the constraint below. It is declared -- but SQLite enforces
     * foreign keys PER CONNECTION and hohenheim's control-plane URL does not set
     * {@code ?foreign_keys=on} (the same finding ServerModel's refuseRemovalWhileOwned
     * note records), so at runtime today the hook is the enforcement and the constraint is
     * documentation. This test switches the pragma on for its OWN connection precisely so
     * the declaration cannot silently rot before that decision is taken: the day
     * enforcement is turned on control-plane-wide, this is already proven to bite.
     */
    @Test
    void theBackupTargetReferencesAreRealForeignKeysAndRefuseWhenEnforced() throws Exception {
        // Enforcement is declared in the URL, never by a PRAGMA statement: the datasource
        // hands out a connection per call from a pool, so a pragma run on one borrowed
        // connection would silently not apply to the next statement.
        SqliteDatasource datasource = enforcingDatabase("target-fk");
        new MigrationRunner(datasource).migrate().requireSuccess();

        // 1. BOTH reference lanes are declared: an instance's chosen destination and a
        //    backup row's target. A guard that only knew one of them would be half a guard.
        assertThat(columnsOf(datasource,
                "SELECT \"table\" AS name FROM pragma_foreign_key_list('instance_backups')"))
            .as("step 1: instance_backups.target_id references the targets table")
            .contains("backup_targets");
        assertThat(columnsOf(datasource,
                "SELECT \"table\" AS name FROM pragma_foreign_key_list('instances')"))
            .as("step 1: and so does instances.backup_target_id")
            .contains("backup_targets");

        // 2. The BACKUP-ROW lane really refuses -- this is the racing writer's row, the one
        //    the count-then-delete window cannot see.
        datasource.rawUpdate("INSERT INTO backup_targets (id, name, kind) VALUES (1, 'off-host', 'ssh')");
        datasource.rawUpdate("INSERT INTO instances (id, name, kind) VALUES (1, 'workload', 'k')");
        datasource.rawUpdate("INSERT INTO instance_backups (id, instance_id, target_id, status)"
            + " VALUES (1, 1, 1, 'complete')");
        assertThatThrownBy(() -> datasource.rawUpdate("DELETE FROM backup_targets WHERE id = 1"))
            .as("step 2: the engine refuses to strand a backup row, race or no race");

        // 3. The INSTANCE-DESTINATION lane refuses on its own, with no backup row at all.
        datasource.rawUpdate("DELETE FROM instance_backups WHERE id = 1");
        datasource.rawUpdate("UPDATE instances SET backup_target_id = 1 WHERE id = 1");
        assertThatThrownBy(() -> datasource.rawUpdate("DELETE FROM backup_targets WHERE id = 1"))
            .as("step 3: an instance still pointing at the target blocks the delete too");

        // 4. And with nothing pointing at it the delete succeeds, so steps 2 and 3 refused
        //    for the reference and not because the row was undeletable.
        datasource.rawUpdate("UPDATE instances SET backup_target_id = NULL WHERE id = 1");
        datasource.rawUpdate("DELETE FROM backup_targets WHERE id = 1");
        assertThat(scalar(datasource, "SELECT COUNT(*) AS c FROM backup_targets"))
            .as("step 4: an unreferenced target deletes normally").isZero();
    }

    /**
     * down() must drop everything up() created: an incomplete down leaves a table behind
     * that the next up() then fails to create, which is the only way to find out.
     */
    @Test
    void theSchemaRollsBackCompletelyAndRebuilds() throws Exception {
        SqliteDatasource datasource = emptyDatabase("roundtrip");
        List<Supplier<Migration>> only = List.of(InitialMigration::new);

        // 1. Build the whole schema on its own; it depends on nothing outside itself.
        MigrationRunner runner = new MigrationRunner(datasource, only);
        assertThat(runner.migrate().isSuccess()).as("step 1: the schema builds").isTrue();
        long created = scalar(datasource,
            "SELECT COUNT(*) AS c FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
            + " AND name NOT LIKE 'zenit_%'");
        assertThat(created).as("step 1: the schema is not empty").isGreaterThan(50L);

        // 2. Rolling back leaves nothing of it behind.
        assertThat(new MigrationRunner(datasource, only).rollback().isSuccess())
            .as("step 2: the schema rolls back").isTrue();
        assertThat(scalar(datasource,
            "SELECT COUNT(*) AS c FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
            + " AND name NOT LIKE 'zenit_%'"))
            .as("step 2: down() dropped every table up() created").isZero();

        // 3. And it rebuilds identically, which a partial down() could not survive.
        assertThat(new MigrationRunner(datasource, only).migrate().isSuccess())
            .as("step 3: the schema rebuilds").isTrue();
        assertThat(scalar(datasource,
            "SELECT COUNT(*) AS c FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
            + " AND name NOT LIKE 'zenit_%'"))
            .as("step 3: the rebuilt schema has the same tables").isEqualTo(created);
    }

    @Test
    void shippedSettingsDoNotDowngradeMigrationIntegrity() {
        // 1. The framework ships the strict posture.
        assertThat(ServerSettings.Database.MIGRATION_INTEGRITY.getDefaultValue())
            .as("the framework's shipped database.migration_integrity")
            .isEqualTo("fail");

        // 2. Hohenheim must not opt out of it. A "warn" pin here was written when warn WAS
        //    the framework default; left in place it is a deliberate downgrade that turns
        //    every checksum and out-of-order finding into a log line nobody reads.
        Path defaults = Path.of("settings", "default.dry");
        assertThat(defaults).as("the shipped defaults file").exists();
        Object database = new DryFileSource(defaults).snapshot().get("database");
        if (database instanceof Map<?, ?> group) {
            assertThat(group.get("migration_integrity"))
                .as("settings/default.dry must not pin database.migration_integrity below the"
                    + " framework default; the enforcement everything else gained is the point")
                .isNull();
        }
    }

    @Test
    void hohenheimShipsExactlyOneMigrationAndItDeclaresTheStream() {
        // The consolidation's standing invariant: a second hohenheim migration means someone
        // appended an incremental change instead of editing InitialMigration, which is what
        // the no-installations doctrine forbids.
        List<String> found = new ArrayList<>();
        for (Supplier<Migration> supplier : MigrationRunner.discoverMigrations("default")) {
            Migration migration = supplier.get();
            if (!migration.getClass().getName().startsWith("be.elevenways.hohenheim.")) {
                continue;
            }
            found.add(migration.getClass().getSimpleName() + " stream=" + migration.getVersionStream());
        }
        assertThat(found)
            .as("hohenheim ships ONE migration; a schema change EDITS it, never appends")
            .containsExactly("InitialMigration stream=" + HohenheimMigration.STREAM);
    }

    // -- helpers --------------------------------------------------------------

    /** {@link #emptyDatabase} with SQLite foreign-key enforcement switched on per connection. */
    private static SqliteDatasource enforcingDatabase(String label) throws Exception {
        File db = File.createTempFile("hohenheim-migration-" + label, ".db");
        db.delete();
        db.deleteOnExit();
        return new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath() + "?foreign_keys=on");
    }

    private static SqliteDatasource emptyDatabase(String label) throws Exception {
        File db = File.createTempFile("hohenheim-migration-" + label, ".db");
        db.delete();
        db.deleteOnExit();
        return new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
    }

    /**
     * Run a body under a temporary {@code database.migration_integrity} mode. Package-visible
     * so any fixture that deliberately builds an unusual install does not need a second copy.
     */
    static void withIntegrityMode(String mode, Runnable body) {
        String previous = ServerSettings.VALUES.getValue(ServerSettings.Database.MIGRATION_INTEGRITY);
        ServerSettings.VALUES.setValue(ServerSettings.Database.MIGRATION_INTEGRITY, mode);
        try {
            body.run();
        } finally {
            ServerSettings.VALUES.setValue(ServerSettings.Database.MIGRATION_INTEGRITY, previous);
        }
    }

    private static List<String> columnsOf(SqliteDatasource datasource, String pragmaSql) {
        return datasource.rawQuery(pragmaSql).stream()
            .map(row -> String.valueOf(row.get("name"))).toList();
    }

    private static long scalar(SqliteDatasource datasource, String sql) {
        return ((Number) datasource.rawQuery(sql).get(0).get("c")).longValue();
    }

    private static String failureDetail(MigrationRunnerResult result) {
        MigrationResult failure = result.getFirstFailure();
        return failure == null ? "no failure recorded" : failure.getVersion() + ": "
            + failure.getMessage() + (failure.getError() == null ? "" : " / " + failure.getError());
    }
}
