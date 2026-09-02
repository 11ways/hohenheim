package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Isolated round-trip test for {@link DatabaseModel}: builds a temp SQLite with the full
 * auto-discovered migration set and scopes model access to it via {@link Db}, so it never touches
 * the shared runtime datasource.
 */
class DatabaseModelTest {

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void persistsAndReloadsADatabaseRecord() {
        Db.run(datasource, () -> {
            DatabaseModel model = Models.get(DatabaseModel.class);
            Row row = model.createEmptyRow();
            row.set(DatabaseModel.NAME, "blog");
            row.set(DatabaseModel.ENGINE, "postgres");
            row.set(DatabaseModel.IMAGE, "postgres:17-alpine");
            row.set(DatabaseModel.DB_USER, "appuser");
            row.set(DatabaseModel.DB_PASSWORD, "secret123");
            row.set(DatabaseModel.DB_NAME, "appdb");
            row.set(DatabaseModel.EPHEMERAL, false);
            row.set(DatabaseModel.SERVER_ID, edgeServerId());
            model.save(row);

            Row reloaded = model.findByName("blog");
            assertThat(reloaded).isNotNull();
            assertThat((Integer) reloaded.get(DatabaseModel.SERVER_ID)).isEqualTo(edgeServerId());
            assertThat((String) reloaded.get(DatabaseModel.ENGINE)).isEqualTo("postgres");
            assertThat((String) reloaded.get(DatabaseModel.IMAGE)).isEqualTo("postgres:17-alpine");
            assertThat((String) reloaded.get(DatabaseModel.DB_USER)).isEqualTo("appuser");
            assertThat((String) reloaded.get(DatabaseModel.DB_NAME)).isEqualTo("appdb");
            assertThat((Boolean) reloaded.get(DatabaseModel.EPHEMERAL)).isFalse();
        });
    }

    @Test
    void ephemeralFlagRoundTripsAndImageUpdates() {
        Db.run(datasource, () -> {
            DatabaseModel model = Models.get(DatabaseModel.class);
            Row row = model.createEmptyRow();
            row.set(DatabaseModel.NAME, "preview");
            row.set(DatabaseModel.ENGINE, "mysql");
            row.set(DatabaseModel.DB_USER, "appuser");
            row.set(DatabaseModel.DB_PASSWORD, "secret123");
            row.set(DatabaseModel.DB_NAME, "appdb");
            row.set(DatabaseModel.EPHEMERAL, true);
            model.save(row);

            Row reloaded = model.findByName("preview");
            assertThat((Boolean) reloaded.get(DatabaseModel.EPHEMERAL)).isTrue();

            reloaded.set(DatabaseModel.IMAGE, "mysql:8.0");
            model.save(reloaded);

            assertThat((String) model.findByName("preview").get(DatabaseModel.IMAGE)).isEqualTo("mysql:8.0");
        });
    }

    /**
     * The name is a filesystem path SEGMENT downstream ({@code BackupDatabases} resolves it
     * onto the backup root, and the retention prune DELETES files in whatever directory that
     * lands in), so a traversal spelling has to be refused at the WRITE, not at the reader.
     *
     * AIDEV-NOTE: {@code Path.resolve("../../etc")} walks out of the root silently -- it is
     * not an error, it is the documented behaviour. Fixing it in BackupDatabases alone would
     * leave every other consumer of the name (container handle, network alias, env injection)
     * to re-derive the same rule; refusing the spelling is one answer for all of them.
     */
    @Test
    void aTraversalSpellingIsRefusedBeforeItBecomesABackupPath() {
        Db.run(datasource, () -> {
            DatabaseModel model = Models.get(DatabaseModel.class);
            Path backupRoot = Path.of("/var/backups/hohenheim");
            for (String hostile : List.of("../../etc", "..", "a/b", "/absolute",
                    "trav../ersal", "", "-leading")) {
                Violations refused = catchThrowableOfType(() -> {
                    Row row = model.createEmptyRow();
                    row.set(DatabaseModel.NAME, hostile);
                    row.set(DatabaseModel.ENGINE, "postgres");
                    row.set(DatabaseModel.DB_USER, "appuser");
                    row.set(DatabaseModel.DB_PASSWORD, "secret123");
                    row.set(DatabaseModel.DB_NAME, "appdb");
                    model.save(row);
                }, Violations.class);
                assertThat((Throwable) refused)
                    .as("'%s' is not a storable database name", hostile).isNotNull();
                assertThat(refused.all().get(0).message().key())
                    .as("'%s' names its own refusal", hostile).isEqualTo("database_name_invalid");
                assertThat(refused.all().get(0).fieldName()).isEqualTo(DatabaseModel.NAME.getName());
                assertThat(model.findByName(hostile))
                    .as("'%s' left no row", hostile).isNull();
            }

            // POSITIVE ANCHOR: the ordinary spellings still save, and every one of them
            // stays INSIDE the backup root once resolved -- which is the property the
            // refusal exists to guarantee.
            for (String valid : List.of("blog2", "app-db.primary", "Under_score", "x")) {
                Row row = model.createEmptyRow();
                row.set(DatabaseModel.NAME, valid);
                row.set(DatabaseModel.ENGINE, "postgres");
                row.set(DatabaseModel.DB_USER, "appuser");
                row.set(DatabaseModel.DB_PASSWORD, "secret123");
                row.set(DatabaseModel.DB_NAME, "appdb");
                model.save(row);
                assertThat(model.findByName(valid))
                    .as("'%s' is an ordinary name and still saves", valid).isNotNull();
                assertThat(backupRoot.resolve(valid).normalize().startsWith(backupRoot))
                    .as("'%s' resolves inside the backup root", valid).isTrue();
            }
        });
    }

    /**
     * PLACEMENT and its ENGINE_ID binding are ONE fact, enforced at the write funnel: a
     * shared record names an engine, a dedicated one names none, a tmpfs database is its
     * own container by definition, and a record written before the column reads as the
     * dedicated database it has always been.
     */
    @Test
    void placementAndItsEngineBindingAreOneFactAtTheWriteFunnel() {
        Db.run(datasource, () -> {
            DatabaseModel model = Models.get(DatabaseModel.class);

            // 1. A new record that declares no placement is DEDICATED explicitly -- the
            //    default is written, never left null, so null can only ever mean "older
            //    than the column".
            Row created = model.createEmptyRow();
            created.set(DatabaseModel.NAME, "placement-default");
            created.set(DatabaseModel.ENGINE, "mongo");
            created.set(DatabaseModel.DB_USER, "appuser");
            created.set(DatabaseModel.DB_PASSWORD, "secret123");
            created.set(DatabaseModel.DB_NAME, "appdb");
            model.save(created);
            Row stored = model.findByName("placement-default");
            assertThat((String) stored.get(DatabaseModel.PLACEMENT))
                .as("step 1: a new record is born explicitly dedicated")
                .isEqualTo(DatabaseModel.PLACEMENT_DEDICATED);
            assertThat(DatabaseModel.isShared(stored))
                .as("step 1: and reads as dedicated").isFalse();

            // 2. A row that PREDATES the column carries a null placement, and reads as
            //    the dedicated database it is -- the migration rewrites nothing.
            stored.set(DatabaseModel.PLACEMENT, null);
            model.save(stored);
            Row legacy = model.findByName("placement-default");
            assertThat((String) legacy.get(DatabaseModel.PLACEMENT))
                .as("step 2: an existing row keeps its null placement").isNull();
            assertThat(DatabaseModel.isShared(legacy))
                .as("step 2: null reads as dedicated, never as a third placement").isFalse();

            // 3. A SHARED record with no engine is refused by name: the placement and the
            //    binding cannot disagree.
            Violations noEngine = catchThrowableOfType(() -> {
                Row row = model.createEmptyRow();
                row.set(DatabaseModel.NAME, "shared-no-engine");
                row.set(DatabaseModel.ENGINE, "mongo");
                row.set(DatabaseModel.DB_USER, "appuser");
                row.set(DatabaseModel.DB_PASSWORD, "secret123");
                row.set(DatabaseModel.DB_NAME, "appdb");
                row.set(DatabaseModel.PLACEMENT, DatabaseModel.PLACEMENT_SHARED);
                model.save(row);
            }, Violations.class);
            assertThat((Throwable) noEngine)
                .as("step 3: a shared record without an engine is refused").isNotNull();
            assertThat(noEngine.all().get(0).message().key())
                .as("step 3: and names its own refusal")
                .isEqualTo("database_shared_without_engine");
            assertThat(model.findByName("shared-no-engine"))
                .as("step 3: nothing was stored").isNull();

            // 4. And the reverse: a DEDICATED record may not name one.
            Violations engineOnDedicated = catchThrowableOfType(() -> {
                Row row = model.createEmptyRow();
                row.set(DatabaseModel.NAME, "dedicated-with-engine");
                row.set(DatabaseModel.ENGINE, "mongo");
                row.set(DatabaseModel.DB_USER, "appuser");
                row.set(DatabaseModel.DB_PASSWORD, "secret123");
                row.set(DatabaseModel.DB_NAME, "appdb");
                row.set(DatabaseModel.PLACEMENT, DatabaseModel.PLACEMENT_DEDICATED);
                row.set(DatabaseModel.ENGINE_ID, 4242);
                model.save(row);
            }, Violations.class);
            assertThat((Throwable) engineOnDedicated)
                .as("step 4: a dedicated record naming an engine is refused").isNotNull();
            assertThat(engineOnDedicated.all().get(0).message().key())
                .as("step 4: and names its own refusal")
                .isEqualTo("database_engine_on_dedicated");
            assertThat(engineOnDedicated.all().get(0).fieldName())
                .isEqualTo(DatabaseModel.ENGINE_ID.getName());

            // 5. A tmpfs database is its own container by definition, so shared +
            //    ephemeral is refused even with an engine named.
            Violations ephemeralShared = catchThrowableOfType(() -> {
                Row row = model.createEmptyRow();
                row.set(DatabaseModel.NAME, "shared-ephemeral");
                row.set(DatabaseModel.ENGINE, "mongo");
                row.set(DatabaseModel.DB_USER, "appuser");
                row.set(DatabaseModel.DB_PASSWORD, "secret123");
                row.set(DatabaseModel.DB_NAME, "appdb");
                row.set(DatabaseModel.PLACEMENT, DatabaseModel.PLACEMENT_SHARED);
                row.set(DatabaseModel.ENGINE_ID, 4242);
                row.set(DatabaseModel.EPHEMERAL, true);
                model.save(row);
            }, Violations.class);
            assertThat((Throwable) ephemeralShared)
                .as("step 5: an ephemeral shared record is refused").isNotNull();
            assertThat(ephemeralShared.all().get(0).message().key())
                .as("step 5: and names its own refusal")
                .isEqualTo("database_ephemeral_shared");
        });
    }

    /**
     * The engine row's name is the same kind of identity a database name is -- a Docker
     * object name AND a data-volume key -- so it goes through the SAME validator, not a
     * second spelling of the rule.
     */
    @Test
    void aDatabaseEngineNameIsValidatedByTheSameRuleAsADatabaseName() {
        Db.run(datasource, () -> {
            DatabaseEngineModel engines = Models.get(DatabaseEngineModel.class);

            // 1. Every spelling a database name refuses, the engine name refuses too, with
            //    the same key -- one rule, two records.
            for (String hostile : List.of("../../etc", "..", "a/b", "/absolute", "",
                    "-leading")) {
                assertThat(DatabaseModel.isValidName(hostile))
                    .as("step 1: '%s' is not a storable name", hostile).isFalse();
                Violations refused = catchThrowableOfType(() -> {
                    Row row = engines.createEmptyRow();
                    row.set(DatabaseEngineModel.NAME, hostile);
                    row.set(DatabaseEngineModel.ENGINE, "mongo");
                    row.set(DatabaseEngineModel.ROOT_USER, "root");
                    row.set(DatabaseEngineModel.ROOT_PASSWORD, "secret123");
                    row.set(DatabaseEngineModel.STATUS, DatabaseModel.STATUS_PROVISIONING);
                    engines.save(row);
                }, Violations.class);
                assertThat((Throwable) refused)
                    .as("step 1: engine name '%s' is refused", hostile).isNotNull();
                assertThat(refused.all().get(0).message().key())
                    .as("step 1: '%s' names the shared refusal", hostile)
                    .isEqualTo("database_name_invalid");
                assertThat(refused.all().get(0).fieldName())
                    .isEqualTo(DatabaseEngineModel.NAME.getName());
            }

            // 2. POSITIVE ANCHOR: the auto-created spelling saves, defaults its host to the
            //    local daemon, and is findable by name and by (host, kind).
            Row row = engines.createEmptyRow();
            row.set(DatabaseEngineModel.NAME, "mongo-local");
            row.set(DatabaseEngineModel.ENGINE, "mongo");
            row.set(DatabaseEngineModel.ROOT_USER, "root");
            row.set(DatabaseEngineModel.ROOT_PASSWORD, "secret123");
            row.set(DatabaseEngineModel.STATUS, DatabaseModel.STATUS_PROVISIONING);
            engines.save(row);
            Row reloaded = engines.findByName("mongo-local");
            assertThat(reloaded).as("step 2: the ordinary spelling saves").isNotNull();
            assertThat((Integer) reloaded.get(DatabaseEngineModel.SERVER_ID))
                .as("step 2: the host FK defaults to the local daemon")
                .isEqualTo(ServerModel.localServerId());
            assertThat(engines.findOnHost(ServerModel.localServerId(), "mongo"))
                .as("step 2: and it is THE mongo engine of that host").isNotNull();
            assertThat(engines.findOnHost(ServerModel.localServerId(), "mysql"))
                .as("step 2: while a kind nobody created has none").isNull();
        });
    }

    /** The named test server's row id, created as an SSH host on first use. */
    private static int edgeServerIdNamed(String name) {
        if ("local".equals(name)) {
            return ServerModel.localServerId();
        }
        ServerModel servers = Models.get(ServerModel.class);
        Row row = servers.findByName(name);
        if (row == null) {
            row = servers.createEmptyRow();
            row.set(ServerModel.NAME, name);
            row.set(ServerModel.MODE, ServerModel.MODE_SSH);
            servers.save(row);
        }
        return row.get(ServerModel.ID);
    }

    private static int edgeServerId() {
        return edgeServerIdNamed("edge-1");
    }
}
