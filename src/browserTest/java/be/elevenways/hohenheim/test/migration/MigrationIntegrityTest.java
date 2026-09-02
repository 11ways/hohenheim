package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.hohenheim.migration.InitialMigration;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationResult;
import be.elevenways.zenit.common.orm.migration.MigrationRunnerResult;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationChecksum;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.setting.DryFileSource;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The install contract of the ONE hohenheim migration: a fresh database migrates,
 * re-migrates and passes strict integrity; the schema it produced really carries the
 * constraints the code relies on; and down() undoes it completely enough that up() can
 * rebuild it.
 *
 * AIDEV-NOTE: the golden checksum pins are a DEPLOYED HIGH-WATER MARK, not a hand-list
 * (2026-08-29). Editing an APPLIED migration makes the next --run-migrations rehearsal
 * refuse under database.migration_integrity=fail, and "applied" stopped meaning "001" the
 * day starfleet ran M002/M003. So exactly one fact is declared here -- DEPLOYED_THROUGH,
 * the highest version every deployed install has applied -- and the rule derives from it:
 * every discovered migration at or below the mark MUST carry a digest in the committed
 * pin table, and that digest MUST still match. A migration ABOVE the mark is not pinned,
 * because nothing has applied it yet and editing it is still free. Raising the mark plus
 * pasting the pin lines the failure prints is the ONE edit a deploy that applied
 * migrations owes (docs/deploy-starfleet.md step 8); a pin is never regenerated to make a
 * red build green. Comments and formatting are outside the digest.
 */
class MigrationIntegrityTest {

    /**
     * The highest migration version every deployed install has applied; see the class note.
     */
    private static final String DEPLOYED_THROUGH = "009";

    /** Classpath resource holding one {@code <class>&lt;TAB&gt;<digest>} line per pinned migration. */
    private static final String PIN_RESOURCE = "migration-pins.txt";

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
     * The upgrade an APPENDED migration exists for: an install that only ever applied
     * version 001 gains the new column, under the strict integrity posture a deployed
     * host boots with.
     */
    @Test
    void anInstallThatOnlyAppliedTheInstallMigrationUpgradesUnderStrictIntegrity() throws Exception {
        SqliteDatasource datasource = emptyDatabase("upgrade");

        // 1. The shape starfleet is in: version 001 applied, nothing appended yet.
        new MigrationRunner(datasource, List.<Supplier<Migration>>of(InitialMigration::new))
            .migrate().requireSuccess();
        assertThat(columnsOf(datasource, "SELECT name FROM pragma_table_info('managed_databases')"))
            .as("step 1: the pre-upgrade install has no failure_reason column")
            .doesNotContain("failure_reason");

        // 2. Upgrading refuses nothing: the recorded checksum for 001 still matches, so the
        //    integrity check passes and the appended migration applies.
        withIntegrityMode("fail", () -> {
            MigrationRunnerResult upgrade = new MigrationRunner(datasource).migrate();
            assertThat(upgrade.isSuccess())
                .as("step 2: the upgrade under integrity=fail failed: %s", failureDetail(upgrade))
                .isTrue();
            assertThat(upgrade.getAppliedCount())
                .as("step 2: the appended migration is applied").isPositive();
        });

        // 3. And the column the appended migration exists for is really there, nullable.
        assertThat(columnsOf(datasource, "SELECT name FROM pragma_table_info('managed_databases')"))
            .as("step 3: failure_reason arrived on the upgraded install")
            .contains("failure_reason");
        datasource.rawUpdate("INSERT INTO managed_databases (id, name) VALUES (1, 'db')");
        assertThat(datasource.rawQuery(
                "SELECT failure_reason AS name FROM managed_databases WHERE id = 1").get(0).get("name"))
            .as("step 3: an existing row's failure_reason is null, never a fabricated reason")
            .isNull();
        datasource.rawUpdate("UPDATE managed_databases SET failure_reason = 'boom' WHERE id = 1");
        assertThat(columnsOf(datasource,
                "SELECT failure_reason AS name FROM managed_databases WHERE id = 1"))
            .as("step 3: and it stores what a failed provision writes")
            .containsExactly("boom");
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

    /**
     * Every hohenheim migration declares the one version stream, and every migration a
     * deployed install has already applied still hashes to what that install recorded.
     */
    @Test
    void everyMigrationDeclaresTheStreamAndEveryDeployedMigrationIsUnchanged() {
        List<Migration> migrations = new ArrayList<>();
        for (Supplier<Migration> supplier : MigrationRunner.discoverMigrations("default")) {
            Migration migration = supplier.get();
            if (migration.getClass().getName().startsWith("be.elevenways.hohenheim.")) {
                migrations.add(migration);
            }
        }

        // 1. One stream, so out-of-order detection judges the whole module together. A
        //    migration in another package that forgets HohenheimMigration shows up here.
        List<String> found = migrations.stream()
            .map(migration -> migration.getClass().getSimpleName() + " stream=" + migration.getVersionStream())
            .toList();
        assertThat(found)
            .as("step 1: every hohenheim migration declares the module's version stream")
            .isNotEmpty()
            .allSatisfy(entry -> assertThat(entry).endsWith(" stream=" + HohenheimMigration.STREAM));
        assertThat(found)
            .as("step 1: and the install migration is one of them")
            .contains("InitialMigration stream=" + HohenheimMigration.STREAM);

        // 2. The deployed high-water mark. Every migration at or below DEPLOYED_THROUGH is
        //    APPLIED somewhere, so its structural checksum is recorded in that install's
        //    zenit_migrations row: moving it makes the install refuse to boot. Append a
        //    migration instead -- a pin is never regenerated to make this green.
        Map<String, String> pins = readPins();
        Set<String> pinnable = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        List<String> pasteLines = new ArrayList<>();

        for (Migration migration : migrations) {
            String name = migration.getClass().getSimpleName();
            if (!isAtOrBelowMark(migration.getVersion())) {
                continue;
            }
            pinnable.add(name);
            String digest = MigrationChecksum.compute(migration);
            String pinned = pins.get(name);
            if (pinned == null) {
                problems.add(name + " (version " + migration.getVersion() + ") is DEPLOYED"
                    + " (at or below the mark " + DEPLOYED_THROUGH + ") but UNPINNED");
                pasteLines.add(name + "\t" + digest);
            } else if (!pinned.equals(digest)) {
                problems.add(name + " (version " + migration.getVersion() + ") CHANGED after it was"
                    + " applied: pinned " + pinned + ", current " + digest
                    + System.lineSeparator() + "its canonical operations are now:" + System.lineSeparator()
                    + MigrationChecksum.canonicalText(migration));
            }
        }
        for (String pinnedName : pins.keySet()) {
            if (!pinnable.contains(pinnedName)) {
                problems.add(pinnedName + " is pinned but is not a discovered migration at or below"
                    + " the mark " + DEPLOYED_THROUGH + ": remove the pin, or raise the mark");
            }
        }

        assertThat(problems)
            .as("step 2: the deployed migrations (version <= %s) must match %s.%s",
                DEPLOYED_THROUGH, PIN_RESOURCE,
                pasteLines.isEmpty() ? "" : System.lineSeparator()
                    + "Paste these lines into src/browserTest/resources/" + PIN_RESOURCE + ":"
                    + System.lineSeparator() + String.join(System.lineSeparator(), pasteLines))
            .isEmpty();
    }

    // -- helpers --------------------------------------------------------------

    /**
     * Whether a version is at or below {@link #DEPLOYED_THROUGH}, comparing zero-padded so
     * a future four-digit version never sorts below a three-digit one.
     */
    private static boolean isAtOrBelowMark(String version) {
        int width = Math.max(version.length(), DEPLOYED_THROUGH.length());
        return padded(version, width).compareTo(padded(DEPLOYED_THROUGH, width)) <= 0;
    }

    private static String padded(String version, int width) {
        return "0".repeat(width - version.length()) + version;
    }

    /** @return the committed pin table, class simple name to structural checksum */
    private static Map<String, String> readPins() {
        Map<String, String> pins = new LinkedHashMap<>();
        try (InputStream stream = MigrationIntegrityTest.class.getClassLoader()
                .getResourceAsStream(PIN_RESOURCE)) {
            assertThat(stream).as("the committed pin table %s is on the test classpath", PIN_RESOURCE)
                .isNotNull();
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\t");
                assertThat(parts).as("every pin line is class<TAB>digest, this one is: %s", trimmed)
                    .hasSize(2);
                pins.put(parts[0].strip(), parts[1].strip());
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return pins;
    }

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
