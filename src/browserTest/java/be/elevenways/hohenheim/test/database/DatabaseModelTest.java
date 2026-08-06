package be.elevenways.hohenheim.test.database;

import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isolated round-trip test for {@link DatabaseModel}: builds a temp SQLite with the full
 * auto-discovered migration set and scopes model access to it via {@link Db}, so it never touches
 * the shared runtime datasource.
 */
class DatabaseModelTest {

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-dbmodel-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        Datasources.register(Datasources.DEFAULT, datasource);
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
