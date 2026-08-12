package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Datasources;
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
