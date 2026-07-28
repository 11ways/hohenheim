package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.M008_AddCertificateLifecycleFields;
import be.elevenways.hohenheim.migration.M010_AddDomainLeExclude;
import be.elevenways.hohenheim.migration.M014_AddSiteSourceFields;
import be.elevenways.hohenheim.migration.M016_AddDatabaseStatus;
import be.elevenways.hohenheim.migration.M018_AddDatabaseServer;
import be.elevenways.hohenheim.migration.M020_AddCertRetryFields;
import be.elevenways.hohenheim.migration.M021_AddCertLetsencryptEmail;
import be.elevenways.hohenheim.migration.M023_AddDomainResponseHeaders;
import be.elevenways.hohenheim.migration.M029_AddDatabaseLimits;
import be.elevenways.hohenheim.migration.M030_AddNotificationEvents;
import be.elevenways.hohenheim.migration.M031_AddCertExpiryStamp;
import be.elevenways.hohenheim.migration.M033_AddCertificateChallenge;
import be.elevenways.hohenheim.migration.M037_AddDnssec;
import be.elevenways.hohenheim.migration.M038_AddDynamicDns;
import be.elevenways.hohenheim.migration.M042_CreateStacks;
import be.elevenways.hohenheim.migration.M043_StackUniqueKeys;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationDirection;
import be.elevenways.zenit.common.orm.migration.MigrationResult;
import be.elevenways.zenit.common.orm.migration.MigrationRunnerResult;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationChecksum;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The migration set as an upgrade contract: a fresh install migrates and re-migrates cleanly,
 * every ALTER-only migration survives being re-applied to a schema that already has its columns,
 * a database carrying duplicate stack rows still boots, and M042 stays frozen.
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
     * Every migration whose {@code up()} is nothing but ALTER TABLE ADD COLUMN, so re-applying it
     * to an already-migrated schema must be a clean no-op. Migrations that also CREATE tables or
     * indexes are excluded: those are not re-runnable and never claimed to be.
     */
    private static final List<Supplier<Migration>> ALTER_ONLY_MIGRATIONS = List.of(
        M008_AddCertificateLifecycleFields::new,
        M010_AddDomainLeExclude::new,
        M014_AddSiteSourceFields::new,
        M016_AddDatabaseStatus::new,
        M018_AddDatabaseServer::new,
        M020_AddCertRetryFields::new,
        M021_AddCertLetsencryptEmail::new,
        M023_AddDomainResponseHeaders::new,
        M029_AddDatabaseLimits::new,
        M030_AddNotificationEvents::new,
        M031_AddCertExpiryStamp::new,
        M033_AddCertificateChallenge::new,
        M037_AddDnssec::new,
        M038_AddDynamicDns::new
    );

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

        // 5. A fresh install has zero integrity findings, so the strictest mode boots green.
        //    This is the evidence for eventually shipping database.migration_integrity=fail.
        withIntegrityMode("fail", () -> {
            MigrationRunnerResult strict = new MigrationRunner(datasource)
                .acknowledgeMissingMigrationVersions(
                    HohenheimDatabase.RETIRED_MIGRATION_VERSIONS.toArray(new String[0]))
                .migrate();
            assertThat(strict.isSuccess()).as("strict migrate on a clean install").isTrue();
        });
    }

    @Test
    void alterOnlyMigrationsReApplyToADivergentInstall() throws Exception {
        SqliteDatasource datasource = emptyDatabase("divergent");

        // 1. Bring the database fully up to date the normal way.
        new MigrationRunner(datasource).migrate().requireSuccess();

        // 2. Lose the history rows of every ALTER-only migration while keeping the schema: the
        //    state an operator lands in after restoring a populated database into a fresh install,
        //    or after hand-repairing zenit_migrations.
        for (Supplier<Migration> supplier : ALTER_ONLY_MIGRATIONS) {
            datasource.rawUpdate("DELETE FROM zenit_migrations WHERE version = ?",
                supplier.get().getVersion());
        }

        // 3. The normal boot path replays exactly those versions. Without .ifNotExists() the first
        //    one dies on "duplicate column name" and the whole batch stops.
        MigrationRunnerResult replay = new MigrationRunner(datasource).migrate();
        assertThat(replay.isSuccess())
            .as("replaying migrations onto an existing schema: %s", failureDetail(replay))
            .isTrue();
        assertThat(replay.getAppliedCount())
            .as("every deleted version was replayed")
            .isEqualTo(ALTER_ONLY_MIGRATIONS.size());

        // 4. The replay was a pure no-op: the data those tables already held is untouched.
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
            // 2. Unacknowledged, those rows are fatal in strict mode -- this is what would have
            //    happened the moment the shipped default moved to "fail".
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
