package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.hohenheim.migration.M001_HohenheimBaseline;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationException;
import be.elevenways.zenit.common.orm.migration.MigrationKey;
import be.elevenways.zenit.common.orm.migration.MigrationResult;
import be.elevenways.zenit.common.orm.migration.MigrationRunnerResult;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationChecksum;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.setting.DryFileSource;
import be.elevenways.zenit.server.setting.ServerSettings;
import be.elevenways.zenit.test.support.SchemaSnapshot;
import be.elevenways.zenit.test.support.TestSqliteFiles;
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

import static be.elevenways.hohenheim.test.migration.InstallsAt.withIntegrityMode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The install contract of Hohenheim's migration stream: a fresh database migrates, re-migrates and passes strict
 * integrity; the schema it produced really carries the constraints the code relies on; the baseline refuses a
 * rollback; and a database still recording the squashed 001..019 chain adopts the baseline instead of running it,
 * or is refused when it holds only part of that chain.
 *
 * AIDEV-NOTE: the golden checksum pins are a DEPLOYED HIGH-WATER MARK, not a hand-list (2026-08-29). Editing an
 * APPLIED migration makes the next --run-migrations rehearsal refuse under database.migration_integrity=fail, so
 * exactly one fact is declared here -- DEPLOYED_THROUGH -- and the rule derives from it: every discovered migration
 * at or below the mark MUST carry a digest in the committed pin table, and that digest MUST still match. Raising the
 * mark plus pasting the pin lines the failure prints is the ONE edit a deploy that applied migrations owes; a pin is
 * never regenerated to make a red build green. Comments and formatting are outside the digest. The lines to paste
 * are printed by the offline command {@code --migration-checksums} of the deployed jar.
 *
 * AIDEV-NOTE: the 001..019 chain was squashed into M001_HohenheimBaseline on 2026-10-21. The baseline is pinned from
 * the day it landed although no install has RUN it: every install that must keep booting adopts it through zenit
 * core's baseline mechanism (Migration.replaces), which records its checksum, so it is as frozen as the chain it
 * stands in for.
 */
class MigrationIntegrityTest {

    /** The highest version every install that must keep booting has applied or adopted; see the class note. */
    private static final String DEPLOYED_THROUGH = "2026_10_21_000001";

    /** The versions of the chain M001_HohenheimBaseline squashed, which the live installs recorded. */
    private static final List<String> SQUASHED_VERSIONS = List.of("001", "002", "003", "004", "006", "007", "008",
        "009", "010", "011", "012", "013", "014", "015", "016", "017", "018", "019");

    /** The non-ledger tables of a database, the schema a refusal must leave untouched. */
    private static final String SCHEMA_TABLES = "SELECT COUNT(*) AS c FROM sqlite_master WHERE type = 'table'"
        + " AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'zenit_%'";

    /** Classpath resource holding one {@code <class><TAB><digest>} line per pinned migration. */
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
     * A database that recorded the squashed 001..019 chain (kuifje, robbedoes, starfleet, daystrom) ADOPTS
     * M001_HohenheimBaseline under the shipped strict posture without running it, then applies every migration
     * appended after the baseline, and one holding only part of that chain is refused with nothing written.
     */
    @Test
    void aDatabaseThatAppliedTheSquashedChainAdoptsTheBaselineAndAPartOfItRefuses() throws Exception {
        String stream = HohenheimMigration.STREAM;
        M001_HohenheimBaseline baseline = new M001_HohenheimBaseline();
        String version = baseline.getVersion();
        String adoption = version + " (hohenheim_baseline), replacing " + SQUASHED_VERSIONS.size() + " versions "
            + SQUASHED_VERSIONS;

        // 1. The declared replaced versions are exactly the chain the live installs recorded.
        assertThat(baseline.getReplacedVersions()).as("step 1: the complete old chain")
            .containsExactlyInAnyOrderElementsOf(SQUASHED_VERSIONS);

        // 2. The database a live install holds: the whole schema, its ledger naming 001..019 instead of the baseline.
        SqliteDatasource datasource = squashedChainDatabase("squashed-chain");
        List<String> otherStreams = otherStreamsHistory(datasource);

        // 3. A strict rehearsal says it would adopt and writes nothing.
        withIntegrityMode("fail", () -> {
            MigrationRunner rehearsal = new MigrationRunner(datasource);
            rehearsal.setDryRun(true);
            rehearsal.migrate().requireSuccess();
            assertThat(rehearsal.getBaselineReport()).as("step 3: the rehearsal line")
                .containsExactly("baseline " + stream + ": would adopt " + adoption);
        });
        assertThat(history(datasource)).as("step 3: the rehearsal wrote nothing")
            .containsExactlyElementsOf(SQUASHED_VERSIONS.stream().map(old -> old + " APPLIED").toList());

        // 4. THE ASSERTION -- the strict migrate adopts: the baseline row with its checksum replaces the old rows, the
        //    baseline never runs, only the migrations appended after it apply, and every other stream's ledger stays
        //    exactly as it was. The schema ends as a fresh install's.
        List<String> appended = InstallsAt.hohenheimMigrations(datasource.getDatasourceIdentifier()).stream()
            .map(Migration::getVersion).filter(other -> other.compareTo(version) > 0).toList();
        withIntegrityMode("fail", () -> {
            MigrationRunner adopting = new MigrationRunner(datasource);
            assertThat(adopting.migrate().requireSuccess().getAppliedCount())
                .as("step 4: only the appended migrations executed").isEqualTo(appended.size());
            assertThat(adopting.getBaselineReport()).as("step 4: the adoption line")
                .containsExactly("baseline " + stream + ": adopt " + adoption);
        });
        List<String> recorded = new ArrayList<>(List.of(version + " APPLIED"));
        appended.forEach(other -> recorded.add(other + " APPLIED"));
        assertThat(history(datasource)).as("step 4: the baseline and the appended migrations are recorded")
            .containsExactlyElementsOf(recorded);
        assertThat(datasource.getMigrationRecord(MigrationKey.of(stream, version)).getChecksum())
            .as("step 4: the baseline's checksum").isEqualTo(MigrationChecksum.compute(baseline));
        assertThat(otherStreamsHistory(datasource)).as("step 4: other streams untouched").isEqualTo(otherStreams);
        SqliteDatasource fresh = emptyDatabase("fresh-beside-adoption");
        new MigrationRunner(fresh).migrate().requireSuccess();
        SchemaSnapshot.assertSame(SchemaSnapshot.of(fresh), SchemaSnapshot.of(datasource),
            "step 4: the adopted install's schema is a fresh install's");
        fresh.close();

        // 5. Settled: a further strict migrate applies and reports nothing.
        withIntegrityMode("fail", () -> {
            MigrationRunner again = new MigrationRunner(datasource);
            assertThat(again.migrate().requireSuccess().getAppliedCount()).as("step 5: nothing to do").isZero();
            assertThat(again.getBaselineReport()).as("step 5: no line once settled").isEmpty();
        });
        datasource.close();

        // 6. A database that stopped half-way (012 missing) is refused naming it, and nothing is written anywhere.
        SqliteDatasource partial = squashedChainDatabase("half-chain");
        partial.rawUpdate("DELETE FROM zenit_migrations WHERE stream = '" + stream + "' AND version = '012'");
        SchemaSnapshot halfWay = SchemaSnapshot.of(partial);
        List<String> halfHistory = history(partial);
        List<String> halfOthers = otherStreamsHistory(partial);
        withIntegrityMode("fail", () -> assertThatThrownBy(() -> new MigrationRunner(partial).migrate())
            .as("step 6: the incomplete chain refuses")
            .isInstanceOf(MigrationException.class)
            .hasMessageContaining("missing [012]"));
        assertThat(history(partial)).as("step 6: this stream's ledger untouched").isEqualTo(halfHistory);
        assertThat(otherStreamsHistory(partial)).as("step 6: other streams untouched").isEqualTo(halfOthers);
        SchemaSnapshot.assertSame(halfWay, SchemaSnapshot.of(partial), "step 6: schema untouched");
        partial.close();
    }

    /**
     * This stream runs in version order inside zenit's one cross-stream dependency order, which
     * is what the strict out-of-order check judges an upgraded install against.
     *
     * AIDEV-NOTE: the runner breaks ties by version text, then stream. The baseline's dated version
     * "2026_10_21_000001" sorts after the recorded "001".."019" and among the other streams' dated
     * versions (after every one dated earlier; a same-version baseline of another stream is ordered
     * by stream name); it depends on no other stream's table. zenit's implicit same-stream chain keeps
     * every later version behind its predecessor, so an appended migration must take a LATER dated
     * version: a "021" would sort before the baseline.
     */
    @Test
    void thisStreamRunsInVersionOrderInsideTheDependencyOrder() throws Exception {
        SqliteDatasource datasource = emptyDatabase("order");

        // 1. A fresh install runs the whole discovered set in the runner's one order.
        MigrationRunnerResult fresh = new MigrationRunner(datasource).migrate();
        assertThat(fresh.isSuccess()).as("step 1: fresh migrate failed: %s", failureDetail(fresh))
            .isTrue();

        // 2. This stream's slice of that order is exactly its version order, and complete.
        List<String> executed = fresh.getResults().stream()
            .filter(result -> HohenheimMigration.STREAM.equals(result.getKey().stream()))
            .map(MigrationResult::getVersion)
            .toList();
        List<String> versions = InstallsAt.hohenheimMigrations(datasource.getDatasourceIdentifier())
            .stream().map(Migration::getVersion).toList();
        assertThat(executed)
            .as("step 2: the %s migrations run in version order (see HohenheimMigration)",
                HohenheimMigration.STREAM)
            .containsExactlyElementsOf(versions);
        datasource.close();
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
     * The baseline is irreversible: a rollback is refused before any down() runs and the schema stays whole.
     */
    @Test
    void theBaselineRefusesARollbackAndKeepsTheSchema() throws Exception {
        SqliteDatasource datasource = emptyDatabase("rollback");
        List<Supplier<Migration>> only = List.of(M001_HohenheimBaseline::new);

        // 1. Build the whole schema on its own; it depends on nothing outside itself.
        assertThat(new MigrationRunner(datasource, only).migrate().isSuccess()).as("step 1: the schema builds")
            .isTrue();
        long created = scalar(datasource, SCHEMA_TABLES);
        assertThat(created).as("step 1: the schema is not empty").isGreaterThan(50L);

        // 2. Rolling back is refused up front, and every table survives it.
        assertThatThrownBy(() -> new MigrationRunner(datasource, only).rollback())
            .as("step 2: the irreversible baseline refuses its rollback")
            .isInstanceOf(MigrationException.class);
        assertThat(scalar(datasource, SCHEMA_TABLES)).as("step 2: no table was dropped").isEqualTo(created);
        datasource.close();
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
            .as("step 1: and the stream is the squashed baseline plus the migrations appended after it")
            .containsExactly("M001_HohenheimBaseline stream=" + HohenheimMigration.STREAM,
                "M002_ReleaseAttribution stream=" + HohenheimMigration.STREAM,
                "M003_CredentialGenerations stream=" + HohenheimMigration.STREAM);

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
                    + "Paste these lines into src/browserTest/resources/" + PIN_RESOURCE
                    + " (the same lines come from `java -jar <server jar> --migration-checksums`,"
                    + " no database and no test run needed):"
                    + System.lineSeparator() + String.join(System.lineSeparator(), pasteLines))
            .isEmpty();
    }

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

    /**
     * @return a database at the state the live installs recorded the squashed chain in (no migration appended after
     *         the baseline applied yet), its ledger naming 001..019 instead of the baseline
     */
    private static SqliteDatasource squashedChainDatabase(String label) throws Exception {
        File db = File.createTempFile("hohenheim-migration-" + label, ".db");
        db.delete();
        db.deleteOnExit();
        String stream = HohenheimMigration.STREAM;
        String baseline = new M001_HohenheimBaseline().getVersion();
        InstallsAt.migrateThrough(db.toPath(), baseline);
        SqliteDatasource datasource = new SqliteDatasource(TestSqliteFiles.url(db.toPath()));
        for (String version : SQUASHED_VERSIONS) {
            datasource.rawUpdate("INSERT INTO zenit_migrations (stream, version, name, applied_at, direction,"
                + " execution_time_ms, state, checksum) SELECT stream, '" + version + "', 'squashed " + version
                + "', applied_at, direction, execution_time_ms, state, checksum FROM zenit_migrations"
                + " WHERE stream = '" + stream + "' AND version = '" + baseline + "'");
        }
        datasource.rawUpdate("DELETE FROM zenit_migrations WHERE stream = '" + stream + "' AND version = '"
            + baseline + "'");
        return datasource;
    }

    /** @return this stream's ledger as sorted {@code <version> <state>} lines */
    private static List<String> history(SqliteDatasource datasource) {
        return datasource.getMigrationHistory().stream()
            .filter(row -> HohenheimMigration.STREAM.equals(row.getStream()))
            .map(row -> row.getVersion() + " " + row.getState()).sorted().toList();
    }

    /** @return every other stream's ledger rows, whole, sorted */
    private static List<String> otherStreamsHistory(SqliteDatasource datasource) {
        return datasource.rawQuery("SELECT quote(stream) || ' ' || quote(version) || ' ' || quote(name) || ' '"
            + " || quote(applied_at) || ' ' || quote(direction) || ' ' || quote(execution_time_ms) || ' '"
            + " || quote(state) || ' ' || quote(checksum) AS c FROM zenit_migrations WHERE stream <> '"
            + HohenheimMigration.STREAM + "' ORDER BY c").stream().map(row -> String.valueOf(row.get("c"))).toList();
    }

    /** {@link #emptyDatabase} with SQLite foreign-key enforcement switched on per connection. */
    private static SqliteDatasource enforcingDatabase(String label) throws Exception {
        File db = File.createTempFile("hohenheim-migration-" + label, ".db");
        db.delete();
        db.deleteOnExit();
        return new SqliteDatasource(TestSqliteFiles.url(db.toPath()) + "&foreign_keys=on");
    }

    private static SqliteDatasource emptyDatabase(String label) throws Exception {
        File db = File.createTempFile("hohenheim-migration-" + label, ".db");
        db.delete();
        db.deleteOnExit();
        return new SqliteDatasource(TestSqliteFiles.url(db.toPath()));
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
