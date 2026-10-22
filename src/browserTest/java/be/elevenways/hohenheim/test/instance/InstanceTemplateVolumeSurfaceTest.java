package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVolumeModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceTemplateDatabaseModel;
import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.server.page.QuickAddState;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.data.RecordSourceEndpoints;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.forms.common.choose.InlineCreateEndpoints;
import be.elevenways.zenit.forms.common.choose.InlineCreateResult;
import be.elevenways.zenit.test.support.EndpointConduit;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceTemplates;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

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
    private static final String VOLUMES = HohenheimSlugs.INSTANCE_TEMPLATE_VOLUMES;
    private static final String DATABASES = HohenheimSlugs.INSTANCE_TEMPLATE_DATABASES;
    private static final String VARIABLES = HohenheimSlugs.INSTANCE_TEMPLATE_VARIABLES;
    private static final String FILES = HohenheimSlugs.INSTANCE_TEMPLATE_FILES;

    @AfterAll
    static void cleanUp() {
        Model instances = Models.get(InstanceModel.class);
        for (Row row : instances.find().withTrashed().where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
            int instanceId = row.get(InstanceModel.ID);
            Models.get(InstanceVolumeModel.class).find()
                .where(InstanceVolumeModel.INSTANCE_ID.eq(instanceId)).delete();
            HardDeletes.byId(instances, instanceId);
        }
        Model templates = Models.get(InstanceTemplateModel.class);
        for (Row row : templates.find().where(InstanceTemplateModel.NAME.startsWith(PREFIX)).all()) {
            int templateId = row.get(InstanceTemplateModel.ID);
            Models.get(InstanceTemplateVolumeModel.class).find()
                .where(InstanceTemplateVolumeModel.TEMPLATE_ID.eq(templateId)).delete();
            Models.get(InstanceTemplateDatabaseModel.class).find()
                .where(InstanceTemplateDatabaseModel.TEMPLATE_ID.eq(templateId)).delete();
            Models.get(InstanceTemplateVariableModel.class).find()
                .where(InstanceTemplateVariableModel.TEMPLATE_ID.eq(templateId)).delete();
            Models.get(InstanceTemplateFileModel.class).find()
                .where(InstanceTemplateFileModel.TEMPLATE_ID.eq(templateId)).delete();
            templates.delete(templateId);
        }
    }

    @Test
    void aVolumeDeclaredThroughTheResourceIsCopiedOntoTheInstanceTheTemplateCreates()
            throws Exception {

        // 1. The peer exists in the admin panel and every declaration it makes about its
        //    own list is one the framework can honour.
        PanelResource<?> resource = registeredResource(HohenheimSlugs.INSTANCE_TEMPLATE_VOLUMES);
        assertThat(resource).as("step 1: the panel offers the template volumes resource")
            .isNotNull();
        resource.validateIn(PanelRegistry.getBySlug("admin"));
        assertThat(resource.showInNav())
            .as("step 1: it is reached through the template, not the sidebar").isFalse();

        // 2. An operator declares a volume on a template through the resource -- the only
        //    thing that used to require writing the row by hand. The limit is entered in MB, as a volume's own is,
        //    and the template is the one the create was opened under (the volume's fixed parent).
        int templateId = template(PREFIX + "carrier", ApplicationKind.ID.toString());
        PanelResourceCalls.create("admin", HohenheimSlugs.INSTANCE_TEMPLATE_VOLUMES, Map.of(
            "name", "data",
            "container_path", "/var/lib/app",
            "quota_mb", 256,
             "exclusive", true), TenantConduits.operatorUnder(templateId));
        List<Row> declared = Models.get(InstanceTemplateVolumeModel.class)
            .findByTemplateId(templateId);
        assertThat(declared).as("step 2: the declaration was stored").hasSize(1);
        assertThat((Long) declared.get(0).get(InstanceTemplateVolumeModel.QUOTA_BYTES))
            .as("step 2: the MB limit is stored in bytes").isEqualTo(256L * 1024L * 1024L);

        // 3. And the template's Contents tab shows it, linking into the same resource, its limit as a size.
        String contents = adminGet(
            "/admin/instance-templates/" + templateId + "/page/contents").body();
        assertThat(contents).as("step 3: the Contents tab names the declared volume")
            .contains("/var/lib/app").contains("instance-template-volumes")
            .as("step 3: the limit reads as a size, never a byte count").contains("256 MB")
            .doesNotContain(">268435456<");

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
    void theResourceRefusesADeclarationTheCreateCouldNeverHonour() throws Exception {
        int templateId = template(PREFIX + "refusing", ApplicationKind.ID.toString());
        PanelResourceCalls.create("admin", HohenheimSlugs.INSTANCE_TEMPLATE_VOLUMES, Map.of(
            "name", "data",
            "container_path", "/var/lib/app", "exclusive", false), TenantConduits.operatorUnder(templateId));

        // 1. A second declaration of one NAME would become a single volume on every
        //    instance (the copy re-declares that one name), so one of the two would be
        //    silently lost.
        assertThat(violationKeys(catchThrowable(() -> PanelResourceCalls.create("admin",
            HohenheimSlugs.INSTANCE_TEMPLATE_VOLUMES,
                Map.of("name", "data", "container_path", "/var/other", "exclusive", false),
                TenantConduits.operatorUnder(templateId)))))
            .as("step 1: a duplicate volume name is refused, named")
            .contains("template_volume_name_taken");

        // 2. Two volumes at one container path would hand the daemon two binds at one
        //    path -- the volume tier's own collision rule, asked at authoring time.
        assertThat(violationKeys(catchThrowable(() -> PanelResourceCalls.create("admin",
            HohenheimSlugs.INSTANCE_TEMPLATE_VOLUMES,
                Map.of("name", "cache", "container_path", "/var/lib/app", "exclusive", false),
                TenantConduits.operatorUnder(templateId)))))
            .as("step 2: a colliding container path is refused, named")
            .contains("volume_container_path_conflict");

        // 3. A name that is not a plain directory name is the containment guarantee.
        assertThat(violationKeys(catchThrowable(() -> PanelResourceCalls.create("admin",
            HohenheimSlugs.INSTANCE_TEMPLATE_VOLUMES,
                Map.of("name", "../etc", "container_path", "/var/cache/app", "exclusive", false),
                TenantConduits.operatorUnder(templateId)))))
            .as("step 3: a traversing volume name is refused, named")
            .contains("volume_name_invalid");

        // 4. A quota no backend could apply would be stored, shown as a limit and never
        //    enforced.
        assertThat(violationKeys(catchThrowable(() -> PanelResourceCalls.create("admin",
            HohenheimSlugs.INSTANCE_TEMPLATE_VOLUMES,
                Map.of("name", "cache",
                    "container_path", "/var/cache/app", "quota_mb", 0,
                     "exclusive", false), TenantConduits.operatorUnder(templateId)))))
            .as("step 4: a non-positive quota is refused, named")
            .contains("volume_quota_invalid");

        // 5. And a template whose kind mounts no volumes at all offers no volume section to declare one in, and cannot
        //    be given one by hand either, rather than being given one that refuses every create it is used for.
        int wrongKind = template(PREFIX + "wrong-kind", "hohenheim:docker_container");
        assertThat(adminGet("/admin/instance-templates/" + wrongKind + "/page/contents").body())
            .as("step 5: THE ASSERTION -- no volume section on a kind that mounts none")
            .doesNotContain("data-cms-child-list=\"" + HohenheimSlugs.INSTANCE_TEMPLATE_VOLUMES + "\"")
            .as("step 5: while its other sections stand")
            .contains("data-cms-child-list=\"" + HohenheimSlugs.INSTANCE_TEMPLATE_VARIABLES + "\"");
        assertThat(adminGet("/admin/instance-templates/" + templateId + "/page/contents").body())
            .as("step 5: the control: a kind that mounts volumes draws the section")
            .contains("data-cms-child-list=\"" + HohenheimSlugs.INSTANCE_TEMPLATE_VOLUMES + "\"");
        assertThat(violationKeys(catchThrowable(() -> PanelResourceCalls.create("admin",
            HohenheimSlugs.INSTANCE_TEMPLATE_VOLUMES,
                Map.of("name", "data", "container_path", "/var/lib/app", "exclusive", false),
                TenantConduits.operatorUnder(wrongKind)))))
            .as("step 5: a kind that mounts none is refused, named")
            .contains("template_volume_kind_unsupported");

        // 6. FALSIFIED: a free name at a free path on the same template is accepted, so
        //    every refusal above discriminates rather than forbidding a second volume.
        PanelResourceCalls.create("admin", HohenheimSlugs.INSTANCE_TEMPLATE_VOLUMES, Map.of(
            "name", "cache",
            "container_path", "/var/cache/app", "exclusive", false), TenantConduits.operatorUnder(templateId));
        assertThat(Models.get(InstanceTemplateVolumeModel.class).findByTemplateId(templateId))
            .as("step 6: the template carries both volumes").hasSize(2);
    }

    /**
     * Every declaration of a template (volume, database, variable, config file) belongs to the template it was declared
     * on, as an instance volume belongs to its instance: each form names the template and offers nothing to change
     * it, a create is written under the template its form was opened from, neither a create nor an edit posting
     * another template moves the declaration, and the variables' quick add on a list filtered to one template adds
     * under that template.
     */
    @Test
    void theTemplateIsTheFixedParentOfEveryDeclarationJourney() throws Exception {
        int owner = template(PREFIX + "owner", ApplicationKind.ID.toString());
        int other = template(PREFIX + "elsewhere", ApplicationKind.ID.toString());
        List<String> slugs = List.of(VOLUMES, DATABASES, VARIABLES, FILES);

        // 1. The Contents tab opens each declaration's create under this template.
        String contents = adminGet("/admin/instance-templates/" + owner + "/page/contents").body();
        for (String slug : slugs) {
            assertThat(contents).as("step 1: the %s add opens under the template", slug)
                .contains("/admin/" + slug + "/new?parent=" + owner);
        }

        // 2. Each create form names the template, and offers no picker to re-point the declaration.
        for (String slug : slugs) {
            assertNamesTheOwnerReadOnly(adminGet("/admin/" + slug + "/new?parent=" + owner).body(),
                "step 2: the " + slug + " create form");
        }

        // 3. A create posting the other template is written under the one its form was opened from.
        assertThat(httpPostForm("/admin/" + VOLUMES + "/new?parent=" + owner,
            "template_id=" + other + "&name=data&container_path=%2Fvar%2Flib%2Fapp&quota_mb=64&"
                + PanelResourceCalls.createEnvelope(), sessionToken, csrfToken).statusCode())
            .as("step 3: the volume create is accepted").isIn(302, 303);
        assertThat(httpPostForm("/admin/" + DATABASES + "/new?parent=" + owner,
            "template_id=" + other + "&engine=" + DatabaseModel.ENGINE_POSTGRES + "&env_prefix=APPDB&"
                + PanelResourceCalls.createEnvelope(), sessionToken, csrfToken).statusCode())
            .as("step 3: the database create is accepted").isIn(302, 303);
        assertThat(httpPostForm("/admin/" + VARIABLES + "/new?parent=" + owner,
            "template_id=" + other + "&key=GREETING&label=Greeting&type=hohenheim%3Astring&"
                + PanelResourceCalls.createEnvelope(), sessionToken, csrfToken).statusCode())
            .as("step 3: the variable create is accepted").isIn(302, 303);
        assertThat(httpPostForm("/admin/" + FILES + "/new?parent=" + owner,
            "template_id=" + other + "&container_path=%2Fetc%2Fapp.conf&content=greeting&mode=0644&"
                + PanelResourceCalls.createEnvelope(), sessionToken, csrfToken).statusCode())
            .as("step 3: the file create is accepted").isIn(302, 303);
        List<Row> volumes = Models.get(InstanceTemplateVolumeModel.class).findByTemplateId(owner);
        List<Row> databases = Models.get(InstanceTemplateDatabaseModel.class).findByTemplateId(owner);
        assertThat(volumes).as("step 3: THE ASSERTION -- the volume is the owner's, never the posted template's")
            .hasSize(1);
        assertThat(databases).as("step 3: THE ASSERTION -- and so is the database").hasSize(1);
        assertThat(Models.get(InstanceTemplateVolumeModel.class).findByTemplateId(other))
            .as("step 3: nothing landed under the posted template").isEmpty();
        assertThat(Models.get(InstanceTemplateDatabaseModel.class).findByTemplateId(other))
            .as("step 3: nothing landed under the posted template").isEmpty();
        List<Row> variables = Models.get(InstanceTemplateVariableModel.class).findByTemplateId(owner);
        List<Row> files = Models.get(InstanceTemplateFileModel.class).findByTemplateId(owner);
        assertThat(variables).as("step 3: THE ASSERTION -- so is the variable").hasSize(1);
        assertThat(files).as("step 3: THE ASSERTION -- and the config file").hasSize(1);
        assertThat(Models.get(InstanceTemplateVariableModel.class).findByTemplateId(other))
            .as("step 3: no variable landed under the posted template").isEmpty();
        assertThat(Models.get(InstanceTemplateFileModel.class).findByTemplateId(other))
            .as("step 3: no file landed under the posted template").isEmpty();
        int volumeId = volumes.get(0).get(InstanceTemplateVolumeModel.ID);
        int databaseId = databases.get(0).get(InstanceTemplateDatabaseModel.ID);
        int variableId = variables.get(0).get(InstanceTemplateVariableModel.ID);
        int fileId = files.get(0).get(InstanceTemplateFileModel.ID);

        // 4. Each edit form names the template read-only too.
        assertNamesTheOwnerReadOnly(adminGet(CmsRoutes.detail("admin", VOLUMES, volumeId).toUrl()).body(),
            "step 4: the volume edit form");
        assertNamesTheOwnerReadOnly(adminGet(CmsRoutes.detail("admin", DATABASES, databaseId).toUrl()).body(),
            "step 4: the database edit form");
        assertNamesTheOwnerReadOnly(adminGet(CmsRoutes.detail("admin", VARIABLES, variableId).toUrl()).body(),
            "step 4: the variable edit form");
        assertNamesTheOwnerReadOnly(adminGet(CmsRoutes.detail("admin", FILES, fileId).toUrl()).body(),
            "step 4: the file edit form");

        // 5. An edit posting the other template writes its other fields and keeps the template.
        assertThat(httpPostEdit("/admin/" + VOLUMES + "/" + volumeId,
            "template_id=" + other + "&name=data&container_path=%2Fvar%2Flib%2Fmoved&quota_mb=64&"
                + PanelResourceCalls.editEnvelope(HohenheimSlugs.ADMIN, VOLUMES, volumeId, TenantConduits.operator()),
            sessionToken, csrfToken).statusCode()).as("step 5: the volume edit is accepted").isIn(302, 303);
        assertThat(httpPostEdit("/admin/" + DATABASES + "/" + databaseId,
            "template_id=" + other + "&engine=" + DatabaseModel.ENGINE_POSTGRES + "&env_prefix=MOVED&"
                + PanelResourceCalls.editEnvelope(HohenheimSlugs.ADMIN, DATABASES, databaseId,
                    TenantConduits.operator()),
            sessionToken, csrfToken).statusCode()).as("step 5: the database edit is accepted").isIn(302, 303);
        Row volume = Models.get(InstanceTemplateVolumeModel.class).findById(volumeId);
        assertThat((String) volume.get(InstanceTemplateVolumeModel.CONTAINER_PATH))
            .as("step 5: the volume edit was written").isEqualTo("/var/lib/moved");
        assertThat((Integer) volume.get(InstanceTemplateVolumeModel.TEMPLATE_ID))
            .as("step 5: THE ASSERTION -- the posted template never re-points the volume").isEqualTo(owner);
        Row database = Models.get(InstanceTemplateDatabaseModel.class).findById(databaseId);
        assertThat((String) database.get(InstanceTemplateDatabaseModel.ENV_PREFIX))
            .as("step 5: the database edit was written").isEqualTo("MOVED");
        assertThat((Integer) database.get(InstanceTemplateDatabaseModel.TEMPLATE_ID))
            .as("step 5: THE ASSERTION -- the posted template never re-points the database").isEqualTo(owner);
        assertThat(httpPostEdit("/admin/" + VARIABLES + "/" + variableId,
            "template_id=" + other + "&key=GREETING&label=Moved&type=hohenheim%3Astring&"
                + PanelResourceCalls.editEnvelope(HohenheimSlugs.ADMIN, VARIABLES, variableId,
                    TenantConduits.operator()),
            sessionToken, csrfToken).statusCode()).as("step 5: the variable edit is accepted").isIn(302, 303);
        assertThat(httpPostEdit("/admin/" + FILES + "/" + fileId,
            "template_id=" + other + "&container_path=%2Fetc%2Fmoved.conf&content=greeting&mode=0644&"
                + PanelResourceCalls.editEnvelope(HohenheimSlugs.ADMIN, FILES, fileId, TenantConduits.operator()),
            sessionToken, csrfToken).statusCode()).as("step 5: the file edit is accepted").isIn(302, 303);
        Row variable = Models.get(InstanceTemplateVariableModel.class).findById(variableId);
        assertThat((String) variable.get(InstanceTemplateVariableModel.LABEL))
            .as("step 5: the variable edit was written").isEqualTo("Moved");
        assertThat((Integer) variable.get(InstanceTemplateVariableModel.TEMPLATE_ID))
            .as("step 5: THE ASSERTION -- the posted template never re-points the variable").isEqualTo(owner);
        Row file = Models.get(InstanceTemplateFileModel.class).findById(fileId);
        assertThat((String) file.get(InstanceTemplateFileModel.CONTAINER_PATH))
            .as("step 5: the file edit was written").isEqualTo("/etc/moved.conf");
        assertThat((Integer) file.get(InstanceTemplateFileModel.TEMPLATE_ID))
            .as("step 5: THE ASSERTION -- the posted template never re-points the file").isEqualTo(owner);

        // 6. The variables' quick add on the list filtered to the owner presets it, and carries it as the create's
        //    parent: an add posting the other template lands under the owner.
        Panel admin = PanelRegistry.getBySlug(HohenheimSlugs.ADMIN);
        @SuppressWarnings("unchecked")
        PanelResource<Row> variableEntry = (PanelResource<Row>) registeredResource(VARIABLES);
        EndpointConduit list = operatorConduit().setParameter(CmsEndpoints.RESOURCE_PARAM, VARIABLES);
        list.setQueryParam(InstanceTemplateVariableModel.TEMPLATE_ID.getName(), String.valueOf(owner));
        Map<String, Object> bar = new LinkedHashMap<>();
        QuickAddState.putVars(bar, admin, variableEntry, AccessContext.of(list),
            "/admin/" + VARIABLES + "?template_id=" + owner, "/admin/" + VARIABLES + "/new");
        assertThat(bar.get("quickAddEnabled")).as("step 6: the filtered list offers its quick add").isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) bar.get("quickAddContext");
        assertThat(context.get(CmsEndpoints.PARENT_PARAM.getName()))
            .as("step 6: the bar carries the preset template as the create's parent").isEqualTo(String.valueOf(owner));
        EndpointConduit add = operatorConduit()
            .setParameter(RecordSourceEndpoints.SOURCE_PARAM, variableEntry.sourceIdIn(admin))
            .setBody(new LinkedHashMap<>(Map.of("key", "QUICK", "label", "Quick", "type", "hohenheim:string",
                "required", false, "template_id", other)));
        context.forEach((name, value) -> add.setQueryParam(name, String.valueOf(value)));
        @SuppressWarnings("unchecked")
        ActionResult<InlineCreateResult> added =
            (ActionResult<InlineCreateResult>) InlineCreateEndpoints.CREATE_SUBMIT.handle(add);
        assertThat(added).as("step 6: the quick add answers").isNotNull();
        assertThat(added.get().succeeded()).as("step 6: the quick add succeeds: %s", added.get().violations())
            .isTrue();
        assertThat(Models.get(InstanceTemplateVariableModel.class).findByTemplateId(owner))
            .as("step 6: THE ASSERTION -- the quick-added variable is the owner's")
            .extracting(row -> (String) row.get(InstanceTemplateVariableModel.KEY)).contains("QUICK");
        assertThat(Models.get(InstanceTemplateVariableModel.class).findByTemplateId(other))
            .as("step 6: nothing landed under the posted template").isEmpty();
    }

    /** A request of the installation operator, as the admin list and its quick add see one. */
    private static EndpointConduit operatorConduit() {
        return new EndpointConduit().withAttribute(ConduitAttributes.PRINCIPAL, TenantConduits.operator().principal());
    }

    // -- fixtures ---------------------------------------------------------------

    private static PanelResource<?> registeredResource(String slug) {
        Panel panel = PanelRegistry.getBySlug("admin");
        assertThat(panel).as("the admin panel is registered").isNotNull();
        return panel.entryBySlug(slug) instanceof PanelResource<?> resource ? resource : null;
    }

    /** A declaration form naming its template, with no editable template field a post could carry. */
    private static void assertNamesTheOwnerReadOnly(String form, String step) {
        assertThat(form).as(step + " names the template").contains(PREFIX + "owner")
            .as(step + " has no editable template picker").doesNotContain("name=\"template_id\"")
            .doesNotContain("<zf-relation-field");
    }

    private static int template(String name, String kind) {
        Model templates = Models.get(InstanceTemplateModel.class);
        Row row = templates.createEmptyRow();
        row.set(InstanceTemplateModel.NAME, name);
        row.set(InstanceTemplateModel.KIND, kind);
        row.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<String, Object>());
        row.set(InstanceTemplateModel.APPROVED_AT, Now.instant());
        row.set(InstanceTemplateModel.APPROVED_BY_USER_ID, TenantConduits.operatorUser().get(UserModel.ID));
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
