package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVolumeModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.server.cms.InstanceTemplateVolumeResource;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceTemplates;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.Resource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The operator surface over {@code instance_template_volumes}: a template's volumes can
 * be DECLARED in the panel, and what is declared there is what a create copies.
 *
 * AIDEV-NOTE: the table shipped with no admin surface at all, so the only way to declare
 * a volume was to write the row programmatically -- a create-from-template feature no
 * operator could reach. The resource is the {@code InstanceTemplateDatabaseResource}
 * shape (a nav-hidden RowResource parented to the template's Contents tab), and its
 * authoring rules are the create's own, asked earlier rather than re-stated.
 */
class InstanceTemplateVolumeSurfaceTest extends HohenheimTestBase {

    private static final String PREFIX = "tpl-vol-ui-";

    private static final AccessContext ADMIN = AccessContext.anonymous();

    @AfterAll
    static void cleanUp() {
        Model instances = Models.get(InstanceModel.class);
        for (Row row : instances.find().where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
            int instanceId = row.get(InstanceModel.ID);
            Models.get(InstanceVolumeModel.class).find()
                .where(InstanceVolumeModel.INSTANCE_ID.eq(instanceId)).delete();
            instances.delete(instanceId);
        }
        Model templates = Models.get(InstanceTemplateModel.class);
        for (Row row : templates.find().where(InstanceTemplateModel.NAME.startsWith(PREFIX)).all()) {
            int templateId = row.get(InstanceTemplateModel.ID);
            Models.get(InstanceTemplateVolumeModel.class).find()
                .where(InstanceTemplateVolumeModel.TEMPLATE_ID.eq(templateId)).delete();
            templates.delete(templateId);
        }
    }

    @Test
    void aVolumeDeclaredThroughTheResourceIsCopiedOntoTheInstanceTheTemplateCreates()
            throws Exception {

        // 1. The peer exists in the admin panel and every declaration it makes about its
        //    own list is one the framework can honour.
        Resource<?> resource = registeredResource("instance-template-volumes");
        assertThat(resource).as("step 1: the panel offers the template volumes resource")
            .isNotNull();
        resource.validateDeclarations();
        assertThat(resource.showInNav())
            .as("step 1: it is reached through the template, not the sidebar").isFalse();

        // 2. An operator declares a volume on a template through the resource -- the only
        //    thing that used to require writing the row by hand.
        int templateId = template(PREFIX + "carrier", ApplicationKind.ID.toString());
        new InstanceTemplateVolumeResource().persistRow(Map.of(
            "template_id", templateId,
            "name", "data",
            "container_path", "/var/lib/app",
            "quota_bytes", 256L * 1024L * 1024L,
            "exclusive", true), ADMIN);
        List<Row> declared = Models.get(InstanceTemplateVolumeModel.class)
            .findByTemplateId(templateId);
        assertThat(declared).as("step 2: the declaration was stored").hasSize(1);

        // 3. And the template's Contents tab shows it, linking into the same resource.
        String contents = adminGet(
            "/admin/instance-templates/" + templateId + "/page/contents").body();
        assertThat(contents).as("step 3: the Contents tab names the declared volume")
            .contains("/var/lib/app").contains("instance-template-volumes");

        // 4. Creating from that template copies the declaration onto the instance, with
        //    the facts that decide what the deploy mounts intact.
        int instanceId = create(templateId, PREFIX + "carried");
        List<Row> copied = InstanceVolumes.declaredFor(instanceId);
        assertThat(copied).as("step 4: the declared volume was copied").hasSize(1);
        assertThat((String) copied.get(0).get(InstanceVolumeModel.CONTAINER_PATH))
            .as("step 4: at the path the operator declared").isEqualTo("/var/lib/app");
        assertThat((Long) copied.get(0).get(InstanceVolumeModel.QUOTA_BYTES))
            .as("step 4: with the quota they declared").isEqualTo(256L * 1024L * 1024L);
        assertThat((Boolean) copied.get(0).get(InstanceVolumeModel.EXCLUSIVE))
            .as("step 4: and the exclusivity").isTrue();
    }

    /**
     * The authoring surface refuses what the create would refuse, while the operator is
     * still looking at the form.
     */
    @Test
    void theResourceRefusesADeclarationTheCreateCouldNeverHonour() {
        InstanceTemplateVolumeResource resource = new InstanceTemplateVolumeResource();
        int templateId = template(PREFIX + "refusing", ApplicationKind.ID.toString());
        resource.persistRow(Map.of("template_id", templateId, "name", "data",
            "container_path", "/var/lib/app", "exclusive", false), ADMIN);

        // 1. A second declaration of one NAME would become a single volume on every
        //    instance (the copy re-declares that one name), so one of the two would be
        //    silently lost.
        assertThat(violationKeys(catchThrowable(() -> resource.persistRow(
                Map.of("template_id", templateId, "name", "data",
                    "container_path", "/var/other", "exclusive", false), ADMIN))))
            .as("step 1: a duplicate volume name is refused, named")
            .contains("template_volume_name_taken");

        // 2. Two volumes at one container path would hand the daemon two binds at one
        //    path -- the volume tier's own collision rule, asked at authoring time.
        assertThat(violationKeys(catchThrowable(() -> resource.persistRow(
                Map.of("template_id", templateId, "name", "cache",
                    "container_path", "/var/lib/app", "exclusive", false), ADMIN))))
            .as("step 2: a colliding container path is refused, named")
            .contains("volume_container_path_conflict");

        // 3. A name that is not a plain directory name is the containment guarantee.
        assertThat(violationKeys(catchThrowable(() -> resource.persistRow(
                Map.of("template_id", templateId, "name", "../etc",
                    "container_path", "/var/cache/app", "exclusive", false), ADMIN))))
            .as("step 3: a traversing volume name is refused, named")
            .contains("volume_name_invalid");

        // 4. A quota no backend could apply would be stored, shown as a limit and never
        //    enforced.
        assertThat(violationKeys(catchThrowable(() -> resource.persistRow(
                Map.of("template_id", templateId, "name", "cache",
                    "container_path", "/var/cache/app", "quota_bytes", 0L,
                    "exclusive", false), ADMIN))))
            .as("step 4: a non-positive quota is refused, named")
            .contains("volume_quota_invalid");

        // 5. And a template whose kind mounts no volumes at all cannot be given one here,
        //    rather than being given one that refuses every create it is used for.
        int wrongKind = template(PREFIX + "wrong-kind", "hohenheim:docker_container");
        assertThat(violationKeys(catchThrowable(() -> resource.persistRow(
                Map.of("template_id", wrongKind, "name", "data",
                    "container_path", "/var/lib/app", "exclusive", false), ADMIN))))
            .as("step 5: a kind that mounts none is refused, named")
            .contains("template_volume_kind_unsupported");

        // 6. FALSIFIED: a free name at a free path on the same template is accepted, so
        //    every refusal above discriminates rather than forbidding a second volume.
        resource.persistRow(Map.of("template_id", templateId, "name", "cache",
            "container_path", "/var/cache/app", "exclusive", false), ADMIN);
        assertThat(Models.get(InstanceTemplateVolumeModel.class).findByTemplateId(templateId))
            .as("step 6: the template carries both volumes").hasSize(2);
    }

    // -- fixtures ---------------------------------------------------------------

    private static Resource<?> registeredResource(String slug) {
        Panel panel = PanelRegistry.getBySlug("admin");
        assertThat(panel).as("the admin panel is registered").isNotNull();
        for (PanelPeer peer : panel.peers()) {
            if (peer instanceof Resource<?> resource && slug.equals(resource.slug())) {
                return resource;
            }
        }
        return null;
    }

    private static int template(String name, String kind) {
        Model templates = Models.get(InstanceTemplateModel.class);
        Row row = templates.createEmptyRow();
        row.set(InstanceTemplateModel.NAME, name);
        row.set(InstanceTemplateModel.KIND, kind);
        row.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<String, Object>());
        row.set(InstanceTemplateModel.APPROVED_AT, Instant.now());
        row.set(InstanceTemplateModel.APPROVED_BY_USER_ID, 1L);
        templates.save(row);
        return row.get(InstanceTemplateModel.ID);
    }

    private static int create(int templateId, String name) {
        return new InstanceTemplates().createFromTemplate(
            Models.get(InstanceTemplateModel.class).findById(templateId),
            name, null, Map.of(), null);
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
