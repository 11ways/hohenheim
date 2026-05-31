package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.migration.M019_CreateNotificationChannels;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.test.TestModels;
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

/** Isolated round-trip test for {@link NotificationChannelModel}. */
class NotificationChannelModelTest {

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-notifications-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner((MigrationCapableDatasource) datasource,
            List.of(M019_CreateNotificationChannels::new)).migrate();
        TestModels.registerAll();
    }

    @Test
    void persistsAndReloadsChannel() {
        Db.run(datasource, () -> {
            NotificationChannelModel model = Models.get(NotificationChannelModel.class);
            Row row = model.createEmptyRow();
            row.set(NotificationChannelModel.NAME, "ops-slack");
            row.set(NotificationChannelModel.KIND, "webhook");
            row.set(NotificationChannelModel.FORMAT, "slack");
            row.set(NotificationChannelModel.URL, "https://hooks.example.com/services/AAA/BBB/CCC");
            model.save(row);

            Row reloaded = model.findByName("ops-slack");
            assertThat(reloaded).isNotNull();
            assertThat((String) reloaded.get(NotificationChannelModel.FORMAT)).isEqualTo("slack");
            assertThat((String) reloaded.get(NotificationChannelModel.URL))
                .isEqualTo("https://hooks.example.com/services/AAA/BBB/CCC");
        });
    }
}
