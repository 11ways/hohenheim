package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.migration.M011_ReviewHardening;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M011 against the git sources production holds: a source that never stored auto_deploy read off, and still reads
 * off once a new source's absent flag reads on.
 *
 * @author Jelle De Loecker
 * @since 0.9.0
 */
class GitSourceAutoDeployMigrationTest {

    private static int seeded;

    @Test
    void everyStoredSourceKeepsTheAutoDeployItHad() throws Exception {
        SqlDatasource datasource = TestDatabases.freshDatasource();

        // 1. Sources as the previous builds stored them: an application and a workspace without the flag, one with
        //    a text flag no reader took for a boolean, one that opted in, and a kind that carries no git source.
        int application = instance(datasource, "hohenheim:application", legacySource());
        int workspace = instance(datasource, "hohenheim:workspace", legacySource());
        Map<String, Object> text = legacySource();
        text.put(GitSourceSchema.AUTO_DEPLOY, "true");
        int textFlag = instance(datasource, "hohenheim:application", text);
        Map<String, Object> optedIn = legacySource();
        optedIn.put(GitSourceSchema.AUTO_DEPLOY, true);
        int deploying = instance(datasource, "hohenheim:application", optedIn);
        int container = instance(datasource, "hohenheim:docker_container",
            new LinkedHashMap<>(Map.of("image", "alpine")));

        // 2. Under today's default an absent flag reads on, so before the data step the legacy rows would deploy.
        assertThat(GitSourceSchema.autoDeploys(settings(datasource, application)))
            .as("step 2: an unmigrated legacy source would now read on").isTrue();

        // 3. The data step stores the off they read before, and every other stored setting stays.
        M011_ReviewHardening.keepStoredSourcesManual(datasource);
        assertThat(GitSourceSchema.autoDeploys(settings(datasource, application)))
            .as("step 3: a legacy application reads off after the migration").isFalse();
        assertThat(settings(datasource, application).get(GitSourceSchema.AUTO_DEPLOY))
            .as("step 3: as an explicit stored false").isEqualTo(false);
        assertThat(settings(datasource, application).get(GitSourceSchema.REPOSITORY_URL))
            .as("step 3: its other settings are kept").isEqualTo("https://git.example.test/acme/legacy.git");
        assertThat(GitSourceSchema.autoDeploys(settings(datasource, workspace)))
            .as("step 3: a legacy workspace reads off too").isFalse();
        assertThat(GitSourceSchema.autoDeploys(settings(datasource, textFlag)))
            .as("step 3: a text flag, which read off, reads off").isFalse();

        // 4. A source that opted in keeps deploying, a kind without a git source is left as stored, and a second
        //    run changes nothing.
        assertThat(GitSourceSchema.autoDeploys(settings(datasource, deploying)))
            .as("step 4: a stored true keeps deploying").isTrue();
        assertThat(settings(datasource, container)).as("step 4: a container gains no source flag")
            .doesNotContainKey(GitSourceSchema.AUTO_DEPLOY);
        M011_ReviewHardening.keepStoredSourcesManual(datasource);
        assertThat(GitSourceSchema.autoDeploys(settings(datasource, deploying)))
            .as("step 4: a second run keeps the opted-in source on").isTrue();
        assertThat(GitSourceSchema.autoDeploys(settings(datasource, application)))
            .as("step 4: and the legacy one off").isFalse();
    }

    private static Map<String, Object> legacySource() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(GitSourceSchema.REPOSITORY_URL, "https://git.example.test/acme/legacy.git");
        settings.put(GitSourceSchema.BRANCH, "main");
        return settings;
    }

    private static int instance(SqlDatasource datasource, String kind, Map<String, Object> stored) {
        List<Integer> ids = new ArrayList<>();
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            StringField name = StringField.builder().name("name").build();
            StringField kindField = StringField.builder().name("kind").build();
            SchemaField settings = SchemaField.builder("settings").build();
            StringField status = StringField.builder().name("status").build();
            FrozenModel instances = new FrozenModel("instances", id, name, kindField, settings, status);
            Row row = instances.createEmptyRow();
            row.set(name, "legacy-source-" + (++seeded));
            row.set(kindField, kind);
            row.set(settings, stored);
            row.set(status, "created");
            ids.add(instances.save(row).get(id));
        });
        return ids.get(0);
    }

    private static Map<String, Object> settings(SqlDatasource datasource, int instanceId) {
        List<Map<String, Object>> found = new ArrayList<>();
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            SchemaField settings = SchemaField.builder("settings").build();
            Row row = new FrozenModel("instances", id, settings).find().where(id.eq(instanceId)).first();
            found.add(row != null ? RawValues.map(row.get(settings)) : Map.of());
        });
        return found.get(0);
    }
}
