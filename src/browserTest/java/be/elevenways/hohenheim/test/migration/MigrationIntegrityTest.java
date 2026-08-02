package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.M017_CreateServers;
import be.elevenways.hohenheim.migration.M042_CreateStacks;
import be.elevenways.hohenheim.migration.M043_StackUniqueKeys;
import be.elevenways.hohenheim.migration.M051_PortLedgerAndHostFks;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import be.elevenways.zenit.common.orm.migration.MigrationDirection;
import be.elevenways.zenit.common.orm.migration.operation.AddColumnOperation;
import be.elevenways.zenit.common.orm.migration.operation.MigrationOperation;
import be.elevenways.zenit.common.orm.migration.MigrationResult;
import be.elevenways.zenit.common.orm.migration.MigrationRunnerResult;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationChecksum;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.setting.DryFileSource;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The migration set as an upgrade contract: a fresh install migrates and re-migrates cleanly,
 * every ALTER-only migration survives being re-applied to a schema that already has its columns,
 * a database carrying duplicate stack rows still boots, and every shipped migration's
 * structural checksum stays pinned (M042 doubly so).
 */
class MigrationIntegrityTest {

    /**
     * M042's structural checksum as recorded before its model-derived
     * {@code createSchemaTableFor} calls were replaced with literal DDL (2026-07-29).
     * The checksum is a SHA-256 over every operation's canonical signature -- table names,
     * per-column type/nullable/unique/pk/auto/default/length/precision/references, index names
     * and columns -- so an unchanged value IS the proof that the frozen DDL is equivalent.
     * It must never change again: a sub-schema change gets a NEW migration.
     */
    private static final String M042_FROZEN_CHECKSUM =
        "cd499511042cc811111f668ee815a9e1548861bd486aec244548c3ddb67397b4";

    /**
     * Every hohenheim migration whose {@code up()} is nothing but ALTER TABLE ADD COLUMN, so
     * re-applying it to an already-migrated schema must be a clean no-op. Migrations that also
     * CREATE tables or indexes are excluded: those are not re-runnable and never claimed to be.
     *
     * @return the ALTER-only subset of the runner's own discovery, so a new migration is
     *         covered automatically instead of rotting out of a hand-maintained list
     */
    // AIDEV-NOTE: Scoped to be.elevenways.hohenheim on purpose: zenit's own
    // M002_AddSystemTaskBootClaim and M002_AddActivityRecordTitle are also ADD-COLUMN-only
    // but lack .ifNotExists(), so their replay posture is upstream's contract, not this
    // repo's. up() only RECORDS operations into a fresh builder (the MigrationChecksum
    // pattern); nothing executes here.
    private static List<Supplier<Migration>> alterOnlyMigrations() {
        List<Supplier<Migration>> result = new ArrayList<>();
        for (Supplier<Migration> supplier : MigrationRunner.discoverMigrations("default")) {
            Migration migration = supplier.get();
            if (!migration.getClass().getName().startsWith("be.elevenways.hohenheim.")) {
                continue;
            }
            MigrationBuilder recorded = new MigrationBuilder();
            migration.up(recorded);
            List<MigrationOperation> operations = recorded.getOperations();
            if (!operations.isEmpty()
                    && operations.stream().allMatch(op -> op instanceof AddColumnOperation)) {
                result.add(supplier);
            }
        }
        return result;
    }

    @Test
    void aFreshInstallMigratesReMigratesAndPassesStrictIntegrity() throws Exception {
        SqliteDatasource datasource = emptyDatabase("fresh");

        // 1. Auto-discovery (never a hand-written list) builds the whole schema in one pass.
        MigrationRunnerResult first = new MigrationRunner(datasource).migrate();
        assertThat(first.isSuccess())
            .as("fresh migrate failed: %s", failureDetail(first))
            .isTrue();
        assertThat(first.getAppliedCount()).as("a fresh install applies migrations").isPositive();

        // 2. The frozen M042 DDL really did create the three SchemaField child tables.
        assertThat(columnsOf(datasource, "SELECT name FROM pragma_table_info('stack_services_mounts')"))
            .as("frozen mounts child table")
            .containsExactly("id", "stack_service_id", "order_key", "type", "name",
                "container_path", "external_name", "created_at", "updated_at");
        assertThat(columnsOf(datasource, "SELECT name FROM pragma_table_info('stack_services_ports')"))
            .as("frozen ports child table")
            .containsExactly("id", "stack_service_id", "order_key", "container_port", "host_port",
                "protocol", "host_ip", "created_at", "updated_at");
        assertThat(columnsOf(datasource, "SELECT name FROM pragma_table_info('stack_services_depends_on')"))
            .as("frozen depends_on child table")
            .containsExactly("id", "stack_service_id", "order_key", "service", "condition",
                "created_at", "updated_at");

        // 3. The guarded singleton insert produced exactly one stub row.
        assertThat(scalar(datasource, "SELECT COUNT(*) AS c FROM spamservice_installations"))
            .as("spamservice singleton stub").isEqualTo(1L);

        // 4. Migrating again is a clean no-op: the history table already covers every version.
        MigrationRunnerResult second = new MigrationRunner(datasource).migrate();
        assertThat(second.isSuccess()).as("second migrate must not fail").isTrue();
        assertThat(second.getAppliedCount()).as("second migrate applies nothing").isZero();

        // 5. A fresh install has zero integrity findings, so the SHIPPED posture boots green.
        //    Pinned explicitly so the guarantee survives an ambient settings change.
        withIntegrityMode("fail", () -> {
            MigrationRunnerResult strict = new MigrationRunner(datasource)
                .acknowledgeMissingMigrationVersions(
                    HohenheimDatabase.RETIRED_MIGRATION_VERSIONS.toArray(new String[0]))
                .migrate();
            assertThat(strict.isSuccess()).as("strict migrate on a clean install").isTrue();
        });
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
    void alterOnlyMigrationsReApplyToADivergentInstall() throws Exception {
        SqliteDatasource datasource = emptyDatabase("divergent");

        // 1. The derived list really derives: 14 ALTER-only migrations existed when the
        //    hand-list was replaced (2026-07-29), and migrations are never deleted, so a
        //    smaller set means the derivation itself broke.
        List<Supplier<Migration>> alterOnly = alterOnlyMigrations();
        assertThat(alterOnly)
            .as("the derived ALTER-only migration set")
            .hasSizeGreaterThanOrEqualTo(14);

        // 2. Bring the database fully up to date the normal way.
        new MigrationRunner(datasource).migrate().requireSuccess();

        // 3. Lose the history rows of every ALTER-only migration while keeping the schema: the
        //    state an operator lands in after restoring a populated database into a fresh install,
        //    or after hand-repairing zenit_migrations.
        for (Supplier<Migration> supplier : alterOnly) {
            datasource.rawUpdate("DELETE FROM zenit_migrations WHERE version = ?",
                supplier.get().getVersion());
        }

        // 4. Under the SHIPPED posture that history is out of order (old versions pending
        //    behind applied newer ones), so the boot is REFUSED. Replaying a hand-repaired
        //    history is an operator decision, never something the framework does silently.
        assertThatThrownBy(() -> new MigrationRunner(datasource).migrate())
            .as("a divergent history must not replay itself under the shipped fail posture")
            .hasMessageContaining("out of order");

        // 5. Once the operator declares that downgrade, the replay runs -- and replays exactly
        //    those versions. Without .ifNotExists() the first one dies on "duplicate column
        //    name" and the whole batch stops.
        withIntegrityMode("warn", () -> {
            MigrationRunnerResult replay = new MigrationRunner(datasource).migrate();
            assertThat(replay.isSuccess())
                .as("replaying migrations onto an existing schema: %s", failureDetail(replay))
                .isTrue();
            assertThat(replay.getAppliedCount())
                .as("every deleted version was replayed")
                .isEqualTo(alterOnly.size());
        });

        // 6. The replay was a pure no-op: the data those tables already held is untouched.
        assertThat(scalar(datasource, "SELECT COUNT(*) AS c FROM spamservice_installations"))
            .as("a replay never rewrites data").isEqualTo(1L);
    }

    @Test
    void duplicateStackRowsAreHealedInsteadOfBrickingBoot() throws Exception {
        SqliteDatasource datasource = emptyDatabase("duplicates");

        // 1. Only M042: the pre-M043 state an existing install would be in. Migration mechanics
        //    is the one documented exemption from the auto-discovery rule.
        new MigrationRunner(datasource, List.of(M042_CreateStacks::new)).migrate().requireSuccess();

        // 2. Plant exactly the data that used to kill boot: three services sharing a name inside
        //    one stack, and two files sharing a container path inside one service.
        datasource.rawUpdate("INSERT INTO stacks (id, name) VALUES (1, 'app')");
        datasource.rawUpdate("INSERT INTO stack_services (id, stack_id, name) VALUES (1, 1, 'web')");
        datasource.rawUpdate("INSERT INTO stack_services (id, stack_id, name) VALUES (2, 1, 'web')");
        datasource.rawUpdate("INSERT INTO stack_services (id, stack_id, name) VALUES (3, 1, 'web')");
        datasource.rawUpdate("INSERT INTO stack_services (id, stack_id, name) VALUES (4, 2, 'web')");
        datasource.rawUpdate(
            "INSERT INTO stack_files (id, stack_service_id, container_path) VALUES (1, 1, '/etc/app.conf')");
        datasource.rawUpdate(
            "INSERT INTO stack_files (id, stack_service_id, container_path) VALUES (2, 1, '/etc/app.conf')");

        // 3. M043 now heals rather than aborting, so the batch completes and the control plane boots.
        MigrationRunnerResult result = new MigrationRunner(datasource,
            List.of(M042_CreateStacks::new, M043_StackUniqueKeys::new)).migrate();
        assertThat(result.isSuccess())
            .as("M043 must survive pre-existing duplicates: %s", failureDetail(result))
            .isTrue();

        // 4. Nothing was deleted, the lowest id kept its name, and the losers were renamed by id.
        assertThat(scalar(datasource, "SELECT COUNT(*) AS c FROM stack_services"))
            .as("healing never drops rows").isEqualTo(4L);
        assertThat(nameOfService(datasource, 1)).as("lowest id keeps its name").isEqualTo("web");
        assertThat(nameOfService(datasource, 2)).isEqualTo("web__dup2");
        assertThat(nameOfService(datasource, 3)).isEqualTo("web__dup3");
        assertThat(nameOfService(datasource, 4))
            .as("a same name in another stack is not a duplicate").isEqualTo("web");
        assertThat(pathOfFile(datasource, 1)).isEqualTo("/etc/app.conf");
        assertThat(pathOfFile(datasource, 2)).isEqualTo("/etc/app.conf__dup2");

        // 5. The constraint the healing exists to allow is really in place afterwards.
        assertThat(scalar(datasource, "SELECT COUNT(*) AS c FROM sqlite_master WHERE type = 'index'"
                + " AND name = 'stack_services_stack_id_name_unique'"))
            .as("the unique index was created after healing").isEqualTo(1L);
        assertThatThrownBy(() -> datasource.rawUpdate(
                "INSERT INTO stack_services (id, stack_id, name) VALUES (5, 1, 'web')"))
            .as("a fresh duplicate is now refused by the database");
    }

    @Test
    void retiredMigrationVersionsAreAcknowledgedInsteadOfBlockingBoot() throws Exception {
        SqliteDatasource datasource = emptyDatabase("retired");
        new MigrationRunner(datasource).migrate().requireSuccess();

        // 1. Replay the real situation: history rows whose migration class no longer exists.
        Instant now = Instant.now();
        for (String version : HohenheimDatabase.RETIRED_MIGRATION_VERSIONS) {
            datasource.recordMigration(version, "retired " + version, MigrationDirection.UP, now, now);
        }

        withIntegrityMode("fail", () -> {
            // 2. Unacknowledged, those rows are fatal under the shipped posture: the boot
            //    refuses rather than logging a finding nobody reads.
            assertThatThrownBy(() -> new MigrationRunner(datasource).migrate())
                .as("an unacknowledged retired version is a strict-mode finding")
                .hasMessageContaining("2026_03_31_000001");

            // 3. Acknowledged the way HohenheimDatabase does it, the same database migrates.
            MigrationRunnerResult acknowledged = new MigrationRunner(datasource)
                .acknowledgeMissingMigrationVersions(
                    HohenheimDatabase.RETIRED_MIGRATION_VERSIONS.toArray(new String[0]))
                .migrate();
            assertThat(acknowledged.isSuccess())
                .as("acknowledging retired versions clears the finding: %s",
                    failureDetail(acknowledged))
                .isTrue();
        });
    }

    @Test
    void everyShippedMigrationChecksumStaysPinned() throws Exception {
        // 1. Compute the structural checksum of every discovered hohenheim migration.
        List<Migration> migrations = new ArrayList<>();
        for (Supplier<Migration> supplier : MigrationRunner.discoverMigrations("default")) {
            Migration migration = supplier.get();
            // AIDEV-NOTE: the prefix is the REPO, not one package. Filtering on
            // be.elevenways.hohenheim.migration silently excluded every migration in
            // be.elevenways.hohenheim.server.migration -- M045 shipped uncovered, and the
            // golden file jumped straight from M044 to M046 without anything noticing.
            if (migration.getClass().getName().startsWith("be.elevenways.hohenheim.")) {
                migrations.add(migration);
            }
        }
        migrations.sort(java.util.Comparator.comparing(Migration::getVersion));
        assertThat(migrations).as("discovery finds the shipped migrations").isNotEmpty();

        StringBuilder computed = new StringBuilder();
        for (Migration migration : migrations) {
            computed.append(migration.getVersion())
                .append(' ').append(migration.getClass().getSimpleName())
                .append(' ').append(MigrationChecksum.compute(migration))
                .append('\n');
        }

        // 2. The golden file pins them all: an edit-after-apply anywhere in the set fails here,
        //    not on the live install's integrity check.
        String golden;
        try (var stream = MigrationIntegrityTest.class.getResourceAsStream("/migration-checksums.txt")) {
            assertThat(stream).as("src/browserTest/resources/migration-checksums.txt exists").isNotNull();
            golden = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        }
        assertThat(computed.toString())
            .as("Shipped migration checksums are pinned in src/browserTest/resources/"
                + "migration-checksums.txt. A NEW migration appends its own line (copy it from "
                + "the computed content below). A CHANGED line means an applied migration was "
                + "edited after it shipped -- write a new migration instead; never retro-edit "
                + "the pinned line. A MISSING migration must also be appended to "
                + "HohenheimDatabase.RETIRED_MIGRATION_VERSIONS in the same commit. "
                + "Computed content:\n%s", computed)
            .isEqualTo(golden);
    }

    /**
     * M051's two heals against a legacy-spelled database: every host spelling folds onto
     * ONE servers.id, unknown names get a visible placeholder row instead of a silent
     * redirect to local, and the declared stack ports land in the ledger with the lowest
     * service id winning a contested tuple.
     */
    @Test
    void hostSpellingsAndDeclaredPortsAreHealedOntoTheLedger() throws Exception {
        SqliteDatasource datasource = emptyDatabase("hostheal");

        // 1. The pre-M051 state: servers + stack tables, plus the two legacy-spelled
        //    tables M051 alters (fixture DDL: migration mechanics, the documented
        //    exemption from auto-discovery).
        new MigrationRunner(datasource, List.of(M017_CreateServers::new, M042_CreateStacks::new,
            MigrationIntegrityTest::legacyHostTables)).migrate().requireSuccess();

        // 2. Plant every legacy spelling of "this machine" plus one unknown remote:
        //    blank, "local" and a registry-key spelling must become ONE host.
        datasource.rawUpdate("INSERT INTO stacks (id, name, server_name) VALUES (1, 'a', '')");
        datasource.rawUpdate("INSERT INTO stacks (id, name, server_name) VALUES (2, 'b', 'local')");
        datasource.rawUpdate(
            "INSERT INTO stacks (id, name, server_name) VALUES (3, 'c', 'hohenheim:edge-9')");
        datasource.rawUpdate("INSERT INTO stack_services (id, stack_id, name) VALUES (1, 1, 'w1')");
        datasource.rawUpdate("INSERT INTO stack_services (id, stack_id, name) VALUES (2, 2, 'w2')");
        datasource.rawUpdate("INSERT INTO stack_services (id, stack_id, name) VALUES (3, 3, 'w3')");
        // Service 1 and 2 contest ONE local tuple ('' vs 0.0.0.0 are the same bind);
        // service 3 declares the same port on the OTHER host -- a different tuple.
        datasource.rawUpdate("INSERT INTO stack_services_ports (stack_service_id, order_key,"
            + " container_port, host_port, protocol, host_ip) VALUES (1, 1024, 80, 8400, 'tcp', '')");
        datasource.rawUpdate("INSERT INTO stack_services_ports (stack_service_id, order_key,"
            + " container_port, host_port, protocol, host_ip) VALUES (2, 1024, 80, 8400, 'tcp', '0.0.0.0')");
        datasource.rawUpdate("INSERT INTO stack_services_ports (stack_service_id, order_key,"
            + " container_port, host_port, protocol, host_ip) VALUES (3, 1024, 80, 8400, 'tcp', '')");
        datasource.rawUpdate("INSERT INTO managed_databases (id, name) VALUES (1, 'plaindb')");
        datasource.rawUpdate("INSERT INTO sites (id, site_type, settings)"
            + " VALUES (1, 'docker', '{\"server\":\"hohenheim:edge-9\"}')");

        // 3. M051 heals rather than aborting.
        MigrationRunnerResult result = new MigrationRunner(datasource,
            List.of(M017_CreateServers::new, M042_CreateStacks::new,
                MigrationIntegrityTest::legacyHostTables, M051_PortLedgerAndHostFks::new)).migrate();
        assertThat(result.isSuccess())
            .as("M051 must survive legacy spellings: %s", failureDetail(result))
            .isTrue();

        // 4. ONE local host: blank and "local" resolved to the SAME server id, the
        //    unknown remote got a visible placeholder row, and server_name is gone.
        long localId = ((Number) datasource.rawQuery(
            "SELECT id FROM servers WHERE name = 'local'").get(0).get("id")).longValue();
        long edgeId = ((Number) datasource.rawQuery(
            "SELECT id FROM servers WHERE name = 'edge-9' AND mode = 'ssh'").get(0).get("id")).longValue();
        assertThat(scalar(datasource, "SELECT COUNT(*) AS c FROM stacks WHERE server_id = " + localId))
            .as("'' and 'local' fold onto ONE local host").isEqualTo(2L);
        assertThat(scalar(datasource, "SELECT COUNT(*) AS c FROM stacks WHERE server_id = " + edgeId))
            .as("the unknown remote is a real (placeholder) row, not a silent local").isEqualTo(1L);
        assertThat(columnsOf(datasource, "SELECT name FROM pragma_table_info('stacks')"))
            .as("the legacy spelling column is gone").doesNotContain("server_name");
        assertThat(scalar(datasource,
            "SELECT COUNT(*) AS c FROM managed_databases WHERE server_id = " + localId))
            .as("a NULL database server_name is the local host").isEqualTo(1L);
        assertThat(String.valueOf(datasource.rawQuery(
                "SELECT settings FROM sites WHERE id = 1").get(0).get("settings")))
            .as("the docker site's settings key was rewritten to the id registry key")
            .contains("hohenheim:" + edgeId);

        // 5. The ledger holds exactly TWO claims for :8400 -- one per host -- and the
        //    contested local tuple went to the LOWEST service id.
        assertThat(scalar(datasource, "SELECT COUNT(*) AS c FROM port_allocations WHERE port = 8400"))
            .as("one claim per host for the contested port").isEqualTo(2L);
        assertThat(scalar(datasource, "SELECT COUNT(*) AS c FROM port_allocations"
                + " WHERE server_id = " + localId + " AND owner_id = 1"))
            .as("the lowest service id kept the local claim").isEqualTo(1L);
        assertThat(scalar(datasource, "SELECT COUNT(*) AS c FROM port_allocations"
                + " WHERE server_id = " + edgeId + " AND owner_id = 3"))
            .as("the other host's identical port is its own tuple").isEqualTo(1L);
    }

    /**
     * Fixture DDL: the pre-M051 shape of the two tables M051 alters that M042 does not
     * create. ANONYMOUS on purpose -- a named public nested Migration would be picked up
     * by auto-discovery and applied to every real database.
     */
    private static Migration legacyHostTables() {
        return new Migration("2026_08_02_999999", "Legacy host-spelling fixture tables") {
            @Override
            public void up(MigrationBuilder schema) {
                schema.createTable("managed_databases", table -> {
                    table.id();
                    table.string("name", 128);
                    table.addColumn("server_name", ColumnType.STRING,
                        col -> col.maxLength(128).nullable(true));
                });
                schema.createTable("sites", table -> {
                    table.id();
                    table.string("site_type", 64);
                    table.json("settings");
                });
            }

            @Override
            public void down(MigrationBuilder schema) {
                schema.dropTable("sites");
                schema.dropTable("managed_databases");
            }
        };
    }

    @Test
    void m042StaysFrozen() {
        assertThat(MigrationChecksum.compute(new M042_CreateStacks()))
            .as("M042 is an applied migration: its structure must never move again. If this "
                + "fails because a stack sub-schema changed, write a NEW migration instead.")
            .isEqualTo(M042_FROZEN_CHECKSUM);
    }

    // -- helpers --------------------------------------------------------------

    private static SqliteDatasource emptyDatabase(String label) throws Exception {
        File db = File.createTempFile("hohenheim-migration-" + label, ".db");
        db.delete();
        db.deleteOnExit();
        return new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
    }

    private static void withIntegrityMode(String mode, Runnable body) {
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

    private static String nameOfService(SqliteDatasource datasource, int id) {
        return String.valueOf(datasource
            .rawQuery("SELECT name FROM stack_services WHERE id = ?", id).get(0).get("name"));
    }

    private static String pathOfFile(SqliteDatasource datasource, int id) {
        return String.valueOf(datasource
            .rawQuery("SELECT container_path FROM stack_files WHERE id = ?", id)
            .get(0).get("container_path"));
    }

    private static String failureDetail(MigrationRunnerResult result) {
        MigrationResult failure = result.getFirstFailure();
        return failure == null ? "no failure recorded" : messageOf(failure);
    }

    private static String messageOf(MigrationResult result) {
        Throwable error = result.getError();
        return result.getVersion() + ": " + result.getMessage()
            + (error == null ? "" : " / " + error);
    }
}
