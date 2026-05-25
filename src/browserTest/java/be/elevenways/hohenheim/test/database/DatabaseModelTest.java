package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.migration.M015_CreateManagedDatabases;
import be.elevenways.hohenheim.migration.M016_AddDatabaseStatus;
import be.elevenways.hohenheim.migration.M018_AddDatabaseServer;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.migration.MigrationCapableDatasource;
import be.elevenways.zenit.common.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isolated round-trip test for {@link DatabaseModel}: builds a temp SQLite with only the
 * managed_databases migration applied, so it never touches the shared runtime datasource.
 * (M015's place in the full migration chain is exercised by every server-backed test.)
 */
class DatabaseModelTest {

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-dbmodel-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner((MigrationCapableDatasource) datasource,
            List.of(M015_CreateManagedDatabases::new, M016_AddDatabaseStatus::new,
                M018_AddDatabaseServer::new)).migrate();
    }

    @Test
    void persistsAndReloadsADatabaseRecord() {
        DatabaseModel model = new DatabaseModel(datasource);

        Row row = model.createEmptyRow();
        row.set(DatabaseModel.NAME, "blog");
        row.set(DatabaseModel.ENGINE, "postgres");
        row.set(DatabaseModel.IMAGE, "postgres:17-alpine");
        row.set(DatabaseModel.DB_USER, "appuser");
        row.set(DatabaseModel.DB_PASSWORD, "secret123");
        row.set(DatabaseModel.DB_NAME, "appdb");
        row.set(DatabaseModel.EPHEMERAL, false);
        row.set(DatabaseModel.SERVER_NAME, "edge-1");
        model.save(row);

        Row reloaded = model.findByName("blog");
        assertThat(reloaded).isNotNull();
        assertThat((String) reloaded.get(DatabaseModel.SERVER_NAME)).isEqualTo("edge-1");
        assertThat((String) reloaded.get(DatabaseModel.ENGINE)).isEqualTo("postgres");
        assertThat((String) reloaded.get(DatabaseModel.IMAGE)).isEqualTo("postgres:17-alpine");
        assertThat((String) reloaded.get(DatabaseModel.DB_USER)).isEqualTo("appuser");
        assertThat((String) reloaded.get(DatabaseModel.DB_NAME)).isEqualTo("appdb");
        assertThat((Boolean) reloaded.get(DatabaseModel.EPHEMERAL)).isFalse();
    }

    @Test
    void ephemeralFlagRoundTripsAndImageUpdates() {
        DatabaseModel model = new DatabaseModel(datasource);

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
    }
}
