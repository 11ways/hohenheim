package be.elevenways.hohenheim.test.database;

import be.elevenways.zenit.common.orm.datasource.Datasources;
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
 * Isolated round-trip test for {@link ServerModel} against its own temp database, so it never
 * touches the shared runtime datasource.
 */
class ServerModelTest {

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-servermodel-test", ".db");
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
