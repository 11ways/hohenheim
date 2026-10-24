package be.elevenways.hohenheim.server.game;

import be.elevenways.hohenheim.instance.StatusProbe;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The starters players connect to directly gain the Minecraft status probe only while their settings are still what
 * was seeded: an operator's edit sticks, and this install's own seeded starters declare it.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class StatusProbeSeedTest extends HohenheimTestBase {

    @Test
    void onlyStartersStillAtTheirSeededSettingsGainTheStatusProbe() {
        List<Row> made = new ArrayList<>();
        try {
            // 1. This install's seeded standalone server declares the probe: a fresh seed equals its baseline.
            Row seeded = Models.get(InstanceTemplateModel.class).find()
                .where(InstanceTemplateModel.NAME.eq(GameTemplateSeeder.MINECRAFT_SERVER))
                .and(InstanceTemplateModel.SOURCE.eq(InstanceTemplateModel.SOURCE_STARTER))
                .orderBy(InstanceTemplateModel.ID, SortOrder.ASC).first();
            assertThat(seeded).as("step 1: the standalone starter is seeded").isNotNull();
            assertThat(settingsOf(seeded)).as("step 1: and declares the status probe")
                .containsEntry(StatusProbe.SETTING, StatusProbe.MINECRAFT_JAVA.token());

            // 2. Starters at the shipped baseline gain it; one an operator edited keeps exactly what it holds.
            Row server = starter(made, GameTemplateSeeder.MINECRAFT_SERVER, GameTemplateSeeder.standaloneSettings());
            Row proxy = starter(made, GameTemplateSeeder.VELOCITY_PROXY, GameTemplateSeeder.velocitySettings());
            Map<String, Object> edited = new LinkedHashMap<>(GameTemplateSeeder.standaloneSettings());
            edited.put("memory_limit_mb", 4096);
            Row customized = starter(made, GameTemplateSeeder.MINECRAFT_SERVER, edited);
            GameTemplateSeeder.declareStatusProbes();
            assertThat(settingsOf(server)).as("step 2: the seeded server declares the probe")
                .containsEntry(StatusProbe.SETTING, StatusProbe.MINECRAFT_JAVA.token());
            assertThat(settingsOf(proxy)).as("step 2: so does the seeded proxy")
                .containsEntry(StatusProbe.SETTING, StatusProbe.MINECRAFT_JAVA.token());
            assertThat(settingsOf(customized)).as("step 2: an edited starter is left as the operator made it")
                .doesNotContainKey(StatusProbe.SETTING).containsEntry("memory_limit_mb", 4096);
        } finally {
            for (Row row : made) {
                HardDeletes.row(Models.get(InstanceTemplateModel.class), row);
            }
        }
    }

    /** @return a starter template row as a seed writes it */
    private static Row starter(List<Row> made, String name, Map<String, Object> settings) {
        var templates = Models.get(InstanceTemplateModel.class);
        Row row = templates.createEmptyRow();
        row.set(InstanceTemplateModel.NAME, name);
        row.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        row.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<>(settings));
        row.set(InstanceTemplateModel.SOURCE, InstanceTemplateModel.SOURCE_STARTER);
        templates.save(row);
        made.add(row);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> settingsOf(Row template) {
        Row fresh = Models.get(InstanceTemplateModel.class).findById(template.get(InstanceTemplateModel.ID));
        return (Map<String, Object>) fresh.get(InstanceTemplateModel.SETTINGS);
    }
}
