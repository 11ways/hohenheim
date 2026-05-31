package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.migration.M017_CreateServers;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.migration.MigrationCapableDatasource;
import be.elevenways.zenit.common.orm.migration.MigrationRunner;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isolated round-trip test for {@link ServerModel} (servers table only, no shared runtime).
 */
class ServerModelTest {

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-servermodel-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner((MigrationCapableDatasource) datasource,
            List.of(M017_CreateServers::new)).migrate();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void persistsAndReloadsRemoteServer() {
        Db.run(datasource, () -> {
            ServerModel model = Models.get(ServerModel.class);
            Row row = model.createEmptyRow();
            row.set(ServerModel.NAME, "edge-1");
            row.set(ServerModel.MODE, "ssh");
            row.set(ServerModel.SSH_TARGET, "deploy@edge.example");
            model.save(row);

            Row reloaded = model.findByName("edge-1");
            assertThat(reloaded).isNotNull();
            assertThat((String) reloaded.get(ServerModel.MODE)).isEqualTo("ssh");
            assertThat((String) reloaded.get(ServerModel.SSH_TARGET)).isEqualTo("deploy@edge.example");
        });
    }
}
