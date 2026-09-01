package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVolumeModel;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.TemplatePortability;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A template's declared VOLUMES survive export and import, and an unhonourable
 * declaration in a document is refused by name.
 *
 * AIDEV-NOTE: the defect this closes is the create-time one ({@code
 * InstanceTemplateVolumeCopyTest}) exactly one layer up. The export carried variables,
 * files and databases but not volumes, so a round-tripped template silently became one
 * that creates instances with no volumes at all -- and both the export and the import
 * reported success. The import validates through the SAME rule set the create asks
 * ({@code InstanceTemplates.requireVolumesDeclarable}), so a document that could only
 * ever fail at create time is refused while importing instead.
 */
class InstanceTemplateVolumePortabilityTest extends HohenheimTestBase {

    private static final String PREFIX = "tpl-vol-port-";

    @AfterAll
    static void cleanUp() {
        Model templates = Models.get(InstanceTemplateModel.class);
        for (Row row : templates.find().where(InstanceTemplateModel.NAME.startsWith(PREFIX)).all()) {
            int templateId = row.get(InstanceTemplateModel.ID);
            Models.get(InstanceTemplateVolumeModel.class).find()
                .where(InstanceTemplateVolumeModel.TEMPLATE_ID.eq(templateId)).delete();
            templates.delete(templateId);
        }
    }

    @Test
    void volumesRideTheDocumentAndAnUnhonourableDeclarationIsRefusedByName() {

        // 1. A template declaring two volumes: one quota'd and exclusive, one plain.
        int templateId = template(PREFIX + "source");
        volume(templateId, "data", "/var/lib/app", 256L * 1024L * 1024L, true);
        volume(templateId, "cache", "/var/cache/app", null, false);
        TemplatePortability portability = new TemplatePortability();

        // 2. The export NAMES them. Before this contract the document carried variables,
        //    files and databases and silently left the volumes behind.
        String document = portability.export(
            Models.get(InstanceTemplateModel.class).findById(templateId));
        assertThat(document).as("step 2: the document carries the volumes")
            .contains("\"volumes\"").contains("/var/lib/app").contains("/var/cache/app");

        // 3. The import re-creates both, with every fact that decides what an instance
        //    created from the imported template will mount: the container path, the quota
        //    and the exclusivity.
        int importedId = portability.importDocument(
            resigned(document, body -> body.put("name", PREFIX + "imported")), "test");
        List<Row> imported = Models.get(InstanceTemplateVolumeModel.class)
            .findByTemplateId(importedId);
        assertThat(imported).as("step 3: both declarations round-trip").hasSize(2);
        Row data = byName(imported, "data");
        assertThat((String) data.get(InstanceTemplateVolumeModel.CONTAINER_PATH))
            .as("step 3: the container path round-trips").isEqualTo("/var/lib/app");
        assertThat((Long) data.get(InstanceTemplateVolumeModel.QUOTA_BYTES))
            .as("step 3: the quota round-trips").isEqualTo(256L * 1024L * 1024L);
        assertThat((Boolean) data.get(InstanceTemplateVolumeModel.EXCLUSIVE))
            .as("step 3: the exclusivity round-trips").isTrue();
        Row cache = byName(imported, "cache");
        assertThat((Long) cache.get(InstanceTemplateVolumeModel.QUOTA_BYTES))
            .as("step 3: an unquota'd declaration stays unquota'd").isNull();
        assertThat((Boolean) cache.get(InstanceTemplateVolumeModel.EXCLUSIVE))
            .as("step 3: and a shared one stays shared").isFalse();

        // 4. A name that is not a plain directory name is refused HERE, not at the first
        //    create -- and the refused import creates nothing at all.
        long before = Models.get(InstanceTemplateModel.class).find().count();
        assertThat(violationKeys(refusedImport(document, body -> {
                body.put("name", PREFIX + "escaping");
                volumeAt(body, 0).put("name", "../etc");
            })))
            .as("step 4: a traversing volume name is refused, named")
            .contains("volume_name_invalid");
        assertThat(Models.get(InstanceTemplateModel.class).find().count())
            .as("step 4: the refused import created NOTHING").isEqualTo(before);

        // 5. So is a quota no backend could apply, and a second declaration at a container
        //    path another one already holds -- the same two the create refuses.
        assertThat(violationKeys(refusedImport(document, body -> {
                body.put("name", PREFIX + "zero-quota");
                volumeAt(body, 0).put("quota_bytes", 0);
            })))
            .as("step 5: a non-positive quota is refused, named")
            .contains("volume_quota_invalid");
        assertThat(violationKeys(refusedImport(document, body -> {
                body.put("name", PREFIX + "clashing");
                // The export is name-ordered, so entry 0 is `cache`: pointing it at the
                // path `data` already holds is the collision.
                volumeAt(body, 0).put("container_path", "/var/lib/app");
            })))
            .as("step 5: two volumes at one container path are refused, named")
            .contains("volume_container_path_conflict");

        // 6. And a kind that mounts no volumes at all: the declaration could never be
        //    honoured by any instance, so importing it would be importing dead data.
        assertThat(violationKeys(refusedImport(document, body -> {
                body.put("name", PREFIX + "wrong-kind");
                body.put("kind", "hohenheim:docker_container");
            })))
            .as("step 6: a volume beside a kind that mounts none is refused, named")
            .contains("template_volume_kind_unsupported");

        // 7. FALSIFIED: the very same document imports cleanly under another name, so
        //    every refusal above is about the offending declaration rather than about a
        //    document that declares volumes at all.
        assertThat(Models.get(InstanceTemplateVolumeModel.class).findByTemplateId(
                portability.importDocument(
                    resigned(document, body -> body.put("name", PREFIX + "again")), "test")))
            .as("step 7: the unedited document imports its volumes again").hasSize(2);
    }

    // -- fixtures ---------------------------------------------------------------

    private static int template(String name) {
        Model templates = Models.get(InstanceTemplateModel.class);
        Row row = templates.createEmptyRow();
        row.set(InstanceTemplateModel.NAME, name);
        row.set(InstanceTemplateModel.KIND, ApplicationKind.ID.toString());
        row.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<String, Object>());
        templates.save(row);
        return row.get(InstanceTemplateModel.ID);
    }

    private static void volume(int templateId, String name, String containerPath,
                               Long quotaBytes, boolean exclusive) {
        Model volumes = Models.get(InstanceTemplateVolumeModel.class);
        Row row = volumes.createEmptyRow();
        row.set(InstanceTemplateVolumeModel.TEMPLATE_ID, templateId);
        row.set(InstanceTemplateVolumeModel.NAME, name);
        row.set(InstanceTemplateVolumeModel.CONTAINER_PATH, containerPath);
        row.set(InstanceTemplateVolumeModel.QUOTA_BYTES, quotaBytes);
        row.set(InstanceTemplateVolumeModel.EXCLUSIVE, exclusive);
        volumes.save(row);
    }

    private static Throwable refusedImport(String document, Consumer<Map<String, Object>> edit) {
        return catchThrowable(() ->
            new TemplatePortability().importDocument(resigned(document, edit), "test"));
    }

    /**
     * The document with {@code edit} applied to its body and the checksum RE-SIGNED: a
     * hostile author signs their own edits, so what the importer must refuse about the
     * CONTENT is only reachable through a validly signed document.
     */
    @SuppressWarnings("unchecked")
    private static String resigned(String document, Consumer<Map<String, Object>> edit) {
        Map<String, Object> parsed = (Map<String, Object>) Zenit.DRY.parse(document);
        Map<String, Object> body = (Map<String, Object>) parsed.get("template");
        edit.accept(body);
        Map<String, Object> checksum = new LinkedHashMap<>();
        checksum.put("algorithm", "sha256");
        checksum.put("value", TemplatePortability.checksumOf(body));
        parsed.put("checksum", checksum);
        return Zenit.DRY.stringify(parsed);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> volumeAt(Map<String, Object> body, int index) {
        return ((List<Map<String, Object>>) body.get("volumes")).get(index);
    }

    private static Row byName(List<Row> volumes, String name) {
        for (Row volume : volumes) {
            if (name.equals(volume.get(InstanceTemplateVolumeModel.NAME))) {
                return volume;
            }
        }
        throw new AssertionError("no imported volume named " + name);
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
