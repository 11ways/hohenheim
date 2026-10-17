package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.migration.M018_ConvertHohenheimHistory;
import be.elevenways.hohenheim.test.RecordedChanges;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.activity.M015_ConvertActivity;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.history.HistorySubjects;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.test.support.HistoryRows;
import be.elevenways.zenit.test.support.MigrationTestSupport;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M018 converts Hohenheim's own plain row verbs and leaves another module's rows spelled the same way to that module.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class HohenheimHistoryConversionTest {

    private static final String PATHS = "hohenheim.proxy.http_port, hohenheim.sftp.port";

    @Test
    void onlyHohenheimsOwnPlainRowVerbsBecomeTheirChanges() throws Exception {
        // 1. An install before core M015 holds a settings update zenit-cms wrote and a Hohenheim site update.
        File file = File.createTempFile("hohenheim-history-conversion", ".db");
        file.delete();
        file.deleteOnExit();
        SqliteDatasource datasource = new SqliteDatasource("jdbc:sqlite:" + file.getAbsolutePath());
        MigrationTestSupport.migrateBefore(datasource, M015_ConvertActivity.class,
            InstallsAt.discoveredThrough(datasource.getDatasourceIdentifier(), "017"));
        HistoryRows.legacyRow(HistorySubjects.SETTINGS, "hohenheim").action("updated").detail(PATHS)
            .insert(datasource);
        HistoryRows.legacyRow(SiteModel.MODEL_ID, "1").action("updated").insert(datasource);

        // 2. Core M015 keeps both bare verbs as legacy facts.
        MigrationTestSupport.applyUp(datasource, new M015_ConvertActivity());
        String legacy = ZenitActivityAction.LEGACY_KIND.id().toString();
        assertThat(facts(datasource, HistorySubjects.SETTINGS)).as("step 2: the settings row is legacy")
            .containsKey(legacy);
        assertThat(facts(datasource, SiteModel.MODEL_ID)).as("step 2: so is the site row").containsKey(legacy);

        // 3. M018 converts the site's update to its change alone and leaves the settings row, its paths with it.
        assertThat(M018_ConvertHohenheimHistory.plainRowVerbs(datasource)).as("step 3: one row converted")
            .isEqualTo(1);
        assertThat(facts(datasource, SiteModel.MODEL_ID)).as("step 3: the site's update has no fact").isEmpty();
        Map<String, Object> settings = facts(datasource, HistorySubjects.SETTINGS);
        assertThat(settings).as("step 3: the settings row stays for zenit-cms").containsKey(legacy);
        assertThat(settings.get(legacy)).as("step 3: with its verb and changed paths")
            .asInstanceOf(InstanceOfAssertFactories.MAP).containsEntry("kind", "updated")
            .containsEntry("detail", PATHS);
        datasource.close();
    }

    /** @return the facts of the one converted change of the model, by kind id */
    private static @NonNull Map<String, Object> facts(@NonNull SqliteDatasource datasource,
                                                      @NonNull Identifier model) {
        List<RecordedChanges.Recorded> changes = Db.supply(datasource, () -> RecordedChanges.about(datasource, model));
        assertThat(changes).as("the one converted change of " + model).hasSize(1);
        return changes.get(0).facts();
    }
}
