package be.elevenways.hohenheim.test.wordpress;

import be.elevenways.hohenheim.instance.ReadinessKind;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateDatabaseModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.InstanceTemplates;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.instance.TemplateDatabases;
import be.elevenways.hohenheim.server.instance.TemplatePortability;
import be.elevenways.hohenheim.server.wordpress.WordPressPhp;
import be.elevenways.hohenheim.server.wordpress.WordPressTemplateSeeder;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.seed.Seeds;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The WordPress starter templates without a daemon: one template per PHP member with
 * the image, docroot volume, proxy fix and declared MySQL database the doc promises; the
 * PHP vocabulary fails closed; a declared database survives export/import and dies with
 * its template; and creating an instance from the template allocates the database on the
 * instance's host and attaches it under {@code WORDPRESS_DB}.
 */
class WordPressTemplateSeedTest extends HohenheimTestBase {

    private static final String PREFIX = "wp-seed-";

    private static Integer hostId;

    @BeforeAll
    static void seedHost() {
        hostId = admittedHost();
    }

    @AfterAll
    static void cleanUp() {
        Model databases = Models.get(DatabaseModel.class);
        Model instances = Models.get(InstanceModel.class);
        InstanceDatabaseModel links = Models.get(InstanceDatabaseModel.class);
        if (hostId != null) {
            for (Row row : databases.find().where(DatabaseModel.SERVER_ID.eq(hostId)).all()) {
                Integer id = row.get(DatabaseModel.ID);
                for (Row link : links.findByDatabaseId(id)) {
                    links.delete(link.get(InstanceDatabaseModel.ID));
                }
            }
            // Engine rows carry generated attribution and are undeletable outside their
            // tier's system scope; the instance rows created from the template are plain.
            GeneratedRows.sweeping("database", () -> {
                for (Row instance : instances.find()
                        .where(InstanceModel.SERVER_ID.eq(hostId)).all()) {
                    instances.delete(instance.get(InstanceModel.ID));
                }
            });
            for (Row row : databases.find().where(DatabaseModel.SERVER_ID.eq(hostId)).all()) {
                databases.delete(row.get(DatabaseModel.ID));
            }
            Models.get(ServerModel.class).delete(hostId);
        }
        Model templates = Models.get(InstanceTemplateModel.class);
        for (Row row : templates.find().where(InstanceTemplateModel.NAME.startsWith(PREFIX)).all()) {
            templates.delete(row.get(InstanceTemplateModel.ID));
        }
    }

    @Test
    void theStarterTemplatesCarryWhatTheDocPromises() throws Exception {
        Seeds.run(Datasources.getDefault(), new WordPressTemplateSeeder());
        InstanceTemplateModel templates = Models.get(InstanceTemplateModel.class);

        // 1. One template per PHP member, derived from the vocabulary -- a member added
        //    without a template, or a template without a member, fails here.
        for (WordPressPhp php : WordPressPhp.values()) {
            Row template = templates.find()
                .where(InstanceTemplateModel.NAME.eq(WordPressTemplateSeeder.templateName(php)))
                .first();
            assertThat(template).as("step 1: '%s' is seeded", php).isNotNull();
            int templateId = template.get(InstanceTemplateModel.ID);

            // 2. The kind settings: the official image at the member's tag, port 80, the
            //    docroot on a managed volume, the proxy fix in the environment.
            Map<?, ?> settings = (Map<?, ?>) template.get(InstanceTemplateModel.SETTINGS);
            assertThat(settings.get("image")).as("step 2: image").isEqualTo(WordPressPhp.IMAGE);
            assertThat(settings.get("tag")).as("step 2: tag of %s", php).isEqualTo(php.tag());
            assertThat(((Number) settings.get("container_port")).intValue())
                .as("step 2: Apache's port").isEqualTo(80);
            assertThat(((Map<?, ?>) settings.get("volumes")).get(WordPressTemplateSeeder.DOCROOT_VOLUME))
                .as("step 2: the docroot rides a managed volume")
                .isEqualTo(WordPressTemplateSeeder.DOCROOT);
            assertThat(String.valueOf(((Map<?, ?>) settings.get("environment_variables"))
                    .get("WORDPRESS_CONFIG_EXTRA")))
                .as("step 2: the reverse-proxy HTTPS fix")
                .contains("HTTP_X_FORWARDED_PROTO").contains("$_SERVER['HTTPS'] = 'on'");
            assertThat((String) template.get(InstanceTemplateModel.READINESS_KIND))
                .as("step 2: http readiness").isEqualTo(ReadinessKind.HTTP.token());
            assertThat((String) template.get(InstanceTemplateModel.READINESS_TARGET))
                .as("step 2: probed at the front page").isEqualTo("/");
            assertThat(template.get(InstanceTemplateModel.APPROVED_AT))
                .as("step 2: starter content lands unapproved").isNull();

            // 3. The typed table-prefix variable and the declared MySQL database under
            //    the image's own prefix.
            List<Row> variables = Models.get(InstanceTemplateVariableModel.class)
                .findByTemplateId(templateId);
            assertThat(variables).as("step 3: one variable").hasSize(1);
            assertThat((String) variables.get(0).get(InstanceTemplateVariableModel.KEY))
                .isEqualTo(WordPressTemplateSeeder.TABLE_PREFIX_KEY);
            assertThat((String) variables.get(0).get(InstanceTemplateVariableModel.DEFAULT_VALUE))
                .as("step 3: WordPress's default prefix").isEqualTo("wp_");
            List<Row> declared = TemplateDatabases.declared(templateId);
            assertThat(declared).as("step 3: one declared database").hasSize(1);
            assertThat((String) declared.get(0).get(InstanceTemplateDatabaseModel.ENGINE))
                .isEqualTo(DatabaseModel.ENGINE_MYSQL);
            assertThat((String) declared.get(0).get(InstanceTemplateDatabaseModel.ENV_PREFIX))
                .isEqualTo(WordPressTemplateSeeder.DB_PREFIX);
            assertThat(declared.get(0).get(InstanceTemplateDatabaseModel.IMAGE))
                .as("step 3: the engine's default image, no override").isNull();

            // 4. The Contents tab renders the declaration beside variables and files.
            HttpResponse<String> contents = httpGet(
                "/admin/instance-templates/" + templateId + "/page/contents", sessionToken);
            assertThat(contents.statusCode()).as("step 4: contents tab").isEqualTo(200);
            assertThat(contents.body()).as("step 4: names the declared database")
                .contains(WordPressTemplateSeeder.DB_PREFIX)
                .contains("instance-template-databases");
        }

        // 5. The vocabulary fails closed and every member maps to a distinct tag.
        assertThat(WordPressPhp.forVersion("5.6")).as("step 5: unknown version").isNull();
        assertThat(WordPressPhp.forVersion(null)).as("step 5: null version").isNull();
        assertThat(WordPressPhp.forVersion("7.4")).isEqualTo(WordPressPhp.PHP_7_4);
        assertThat(WordPressPhp.PHP_7_4.frozen()).as("step 5: 7.4 is the frozen tag").isTrue();
        assertThat(WordPressPhp.PHP_8_1.frozen()).as("step 5: 8.1 is maintained").isFalse();
        List<String> tags = new ArrayList<>();
        for (WordPressPhp php : WordPressPhp.values()) {
            tags.add(php.tag());
        }
        assertThat(tags).doesNotHaveDuplicates();

        // 6. The seed is ledgered once: a second run adds nothing.
        long before = templates.find().count();
        Seeds.run(Datasources.getDefault(), new WordPressTemplateSeeder());
        assertThat(templates.find().count()).as("step 6: once means once").isEqualTo(before);
    }

    @Test
    void aDeclaredDatabaseIsPortableCascadesAndIsAllocatedOnCreate() {
        Seeds.run(Datasources.getDefault(), new WordPressTemplateSeeder());
        InstanceTemplateModel templates = Models.get(InstanceTemplateModel.class);
        Row seeded = templates.find()
            .where(InstanceTemplateModel.NAME.eq(
                WordPressTemplateSeeder.templateName(WordPressPhp.PHP_8_1)))
            .first();
        TemplatePortability portability = new TemplatePortability();

        // 1. Export carries the declaration; import re-creates it on a fresh template.
        String document = portability.export(seeded);
        assertThat(document).as("step 1: the document names the declaration")
            .contains("\"databases\"").contains(WordPressTemplateSeeder.DB_PREFIX);
        int importedId = portability.importDocument(
            resigned(document, body -> body.put("name", PREFIX + "imported")), "test");
        List<Row> imported = TemplateDatabases.declared(importedId);
        assertThat(imported).as("step 1: the declaration was imported").hasSize(1);
        assertThat((String) imported.get(0).get(InstanceTemplateDatabaseModel.ENGINE))
            .isEqualTo(DatabaseModel.ENGINE_MYSQL);

        // 2. COUNTERFACTUAL: an engine no engine carries is refused at import, by name,
        //    and nothing is created.
        long before = templates.find().count();
        Throwable unknown = catchThrowable(() -> portability.importDocument(
            resigned(document, body -> ((List<Map<String, Object>>) body.get("databases"))
                .get(0).put("engine", "oracle")), "test"));
        assertThat(violationKeys(unknown)).as("step 2: unknown engine refused")
            .contains("unknown_engine");
        assertThat(templates.find().count()).as("step 2: nothing created").isEqualTo(before);

        // 3. Creating an instance from the template allocates the database on the
        //    instance's host and attaches it under the declared prefix.
        seeded.set(InstanceTemplateModel.APPROVED_AT, Instant.now());
        templates.save(seeded);
        int instanceId = new InstanceTemplates().createFromTemplate(
            templates.findById(seeded.get(InstanceTemplateModel.ID)),
            PREFIX + "Any Media", hostId, Map.of(), null);
        List<Row> links = Models.get(InstanceDatabaseModel.class).findByInstanceId(instanceId);
        assertThat(links).as("step 3: exactly one attachment").hasSize(1);
        assertThat((String) links.get(0).get(InstanceDatabaseModel.ENV_PREFIX))
            .as("step 3: under the image's prefix").isEqualTo(WordPressTemplateSeeder.DB_PREFIX);
        Row database = Models.get(DatabaseModel.class)
            .findById(links.get(0).get(InstanceDatabaseModel.DATABASE_ID));
        assertThat(database).as("step 3: the database record exists").isNotNull();
        assertThat((String) database.get(DatabaseModel.NAME))
            .as("step 3: named after the instance and the prefix")
            .isEqualTo("wp-seed-any-media-wordpress-db");
        assertThat((String) database.get(DatabaseModel.ENGINE)).isEqualTo(DatabaseModel.ENGINE_MYSQL);
        assertThat((Integer) database.get(DatabaseModel.SERVER_ID))
            .as("step 3: on the instance's own host").isEqualTo(hostId);
        assertThat((String) database.get(DatabaseModel.DB_USER)).isNotBlank();
        assertThat((String) database.get(DatabaseModel.DB_PASSWORD)).isNotBlank();
        assertThat(new InstanceVariables().valuesFor(instanceId)
                .get(WordPressTemplateSeeder.TABLE_PREFIX_KEY))
            .as("step 3: the table prefix default rode along").isEqualTo("wp_");

        // 4. COUNTERFACTUAL: the same name again is refused BEFORE any instance row is
        //    written -- the database label is taken.
        long instancesBefore = Models.get(InstanceModel.class).find().count();
        Throwable taken = catchThrowable(() -> new InstanceTemplates().createFromTemplate(
            templates.findById(seeded.get(InstanceTemplateModel.ID)),
            PREFIX + "Any Media", hostId, Map.of(), null));
        assertThat(violationKeys(taken)).as("step 4: label taken").contains("database_name_taken");
        assertThat(Models.get(InstanceModel.class).find().count())
            .as("step 4: no instance row was written").isEqualTo(instancesBefore);

        // 5. The declaration dies with its template.
        int declarationId = imported.get(0).get(InstanceTemplateDatabaseModel.ID);
        templates.delete(importedId);
        assertThat(Models.get(InstanceTemplateDatabaseModel.class).findById(declarationId))
            .as("step 5: cascaded").isNull();

        // 6. The label derivation: slugged, suffixed, inside the funnel's ceiling.
        assertThat(TemplateDatabases.labelFor("Diax Live WordPress", "WORDPRESS_DB"))
            .isEqualTo("diax-live-wordpress-wordpress-db");
        assertThat(TemplateDatabases.labelFor("a-very-long-instance-name-that-goes-on", "DB"))
            .hasSizeLessThanOrEqualTo(32).endsWith("-db").doesNotContain("--");
        assertThat(TemplateDatabases.labelFor("***", "DB")).isEqualTo("db-db");
    }

    // -- plumbing -------------------------------------------------------------

    /**
     * The document with {@code edit} applied to its body and the checksum RE-SIGNED: the
     * digest covers the whole body (a rename is a checksum refusal, as the policy test
     * proves), and a hostile author signs their own edits, so what the importer must
     * refuse about the CONTENT is only reachable through a validly signed document.
     */
    @SuppressWarnings("unchecked")
    private static String resigned(String document, java.util.function.Consumer<Map<String, Object>> edit) {
        Map<String, Object> parsed = (Map<String, Object>) Zenit.DRY.parse(document);
        Map<String, Object> body = (Map<String, Object>) parsed.get("template");
        edit.accept(body);
        Map<String, Object> checksum = new LinkedHashMap<>();
        checksum.put("algorithm", "sha256");
        checksum.put("value", TemplatePortability.checksumOf(body));
        parsed.put("checksum", checksum);
        return Zenit.DRY.stringify(parsed);
    }

    private static int admittedHost() {
        Model servers = Models.get(ServerModel.class);
        Row row = servers.createEmptyRow();
        row.set(ServerModel.NAME, PREFIX + "host");
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.PREFLIGHT_OK, true);
        servers.save(row);
        HostFixtures.acknowledgePosture(row);
        HostPreflight.store(PREFIX + "host", new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok")),
            Map.of(HostPreflight.MEM_TOTAL_FACT, 16L * 1024 * 1024 * 1024), true,
            Instant.now(), null));
        return row.get(ServerModel.ID);
    }

    private static String violationKeys(Throwable thrown) {
        assertThat(thrown).isInstanceOf(Violations.class);
        StringBuilder keys = new StringBuilder();
        for (var violation : ((Violations) thrown).all()) {
            keys.append(violation.fieldName()).append('=')
                .append(violation.message().key()).append(' ');
        }
        return keys.toString();
    }
}
