package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin-UI wave's surface contract, over HTTP: the fleet list shows every kind but
 * release with a "Managed by" column, tab sets follow the kind, generated rows are
 * read-only, the runtime-image catalog refuses edits to code-owned rows, volumes
 * declare through their funnel, and the Expose action prefills the site create form.
 */
class AdminUiSurfaceTest extends HohenheimTestBase {

    private static Integer workspaceId;
    private static Integer applicationId;
    private static Integer dockerId;
    private static Integer generatedDbInstanceId;
    private static Integer releaseId;
    private static Integer databaseId;

    @BeforeAll
    static void seedFleet() {
        workspaceId = instance("ui-wave-workspace", "hohenheim:workspace");
        applicationId = instance("ui-wave-app", "hohenheim:application");
        dockerId = instance("ui-wave-docker", "hohenheim:docker_container");

        // A generated database engine container, written the way its owning tier
        // writes it (inside the GeneratedRows attribution scope).
        var databases = Models.get(DatabaseModel.class);
        Row database = databases.find().where(DatabaseModel.NAME.eq("ui-wave-db")).first();
        if (database == null) {
            database = databases.createEmptyRow();
            database.set(DatabaseModel.NAME, "ui-wave-db");
            database.set(DatabaseModel.ENGINE, "postgres");
            database.set(DatabaseModel.DB_USER, "appuser");
            database.set(DatabaseModel.DB_PASSWORD, "s3cret");
            database.set(DatabaseModel.DB_NAME, "appdb");
            databases.save(database);
        }
        databaseId = database.get(DatabaseModel.ID);
        generatedDbInstanceId = generated("ui-wave-db-engine",
            "hohenheim:database_container", "database", databaseId);
        releaseId = generated("ui-wave-release-1",
            "hohenheim:release", "application", applicationId);
    }

    private static Integer instance(String name, String kind) {
        var instances = Models.get(InstanceModel.class);
        Row existing = instances.find().where(InstanceModel.NAME.eq(name)).first();
        if (existing != null) {
            return existing.get(InstanceModel.ID);
        }
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, kind);
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of()));
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static Integer generated(String name, String kind, String source, int ownerId) {
        var instances = Models.get(InstanceModel.class);
        Row existing = instances.find().where(InstanceModel.NAME.eq(name)).first();
        if (existing != null) {
            return existing.get(InstanceModel.ID);
        }
        var ownerModel = "database".equals(source) ? DatabaseModel.MODEL_ID : InstanceModel.MODEL_ID;
        OwnedInstances.inScopeUnchecked(source, ownerModel, ownerId, () -> {
            Row row = instances.createEmptyRow();
            row.set(InstanceModel.NAME, name);
            row.set(InstanceModel.KIND, kind);
            row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of()));
            instances.save(row);
        });
        return instances.find().where(InstanceModel.NAME.eq(name)).first()
            .get(InstanceModel.ID);
    }

    /**
     * The fleet list: every kind visible, generated rows carry their owner (linked),
     * release rows stay with their application's Deploys tab.
     */
    @Test
    void fleetListShowsEveryKindButReleaseWithManagedBy() throws Exception {
        // Filtered to this class's fixtures: the suite shares one database, so the
        // unfiltered page 1 is other classes' rows.
        String list = adminGet("/admin/instances?filter.name=ui-wave").body();

        assertThat(list).as("authored rows are listed")
            .contains("ui-wave-workspace", "ui-wave-app", "ui-wave-docker");
        assertThat(list).as("the generated database engine is listed too")
            .contains("ui-wave-db-engine");
        assertThat(list).as("release rows are deploy artifacts, not fleet entries")
            .doesNotContain("ui-wave-release-1");
        assertThat(list).as("the managed-by cell links the owning database record")
            .contains("/admin/databases/" + databaseId);
        assertThat(list).as("the relational host filter is offered")
            .contains("filter.server.name");
        assertThat(list).as("the Expose affordance leads to a prefilled site create")
            .contains("instance_id=" + applicationId);

        // The release row's own surface is its application's detail, and its direct
        // URL answers MISSING, never a half-broken page.
        assertThat(adminGet("/admin/instances/" + releaseId).statusCode()).isEqualTo(404);
    }

    /** Tab sets follow the kind: never a tab that cannot work for that record. */
    @Test
    void tabSetsFollowTheKind() throws Exception {
        String workspace = adminGet("/admin/instances/" + workspaceId + "/page/overview").body();
        assertThat(workspace).as("a workspace mounts volumes").contains("/page/volumes");
        assertThat(workspace).as("a workspace browses its files").contains("/page/files");
        assertThat(workspace).as("a workspace has no release history")
            .doesNotContain("/page/deployments");

        String application = adminGet("/admin/instances/" + applicationId + "/page/overview").body();
        assertThat(application).as("an application deploys releases").contains("/page/deployments");
        assertThat(application).as("an application mounts volumes").contains("/page/volumes");

        String docker = adminGet("/admin/instances/" + dockerId + "/page/overview").body();
        assertThat(docker).as("a raw container declares no instance volumes")
            .doesNotContain("/page/volumes");
        assertThat(docker).as("a raw container has no release history")
            .doesNotContain("/page/deployments");
        assertThat(docker).as("docker attaches no devices").doesNotContain("/page/devices");
    }

    /**
     * FALSIFICATION x2: a generated row's edit lane and power verbs can only refuse, so
     * the surface never offers them -- and the direct POSTs are refused too.
     */
    @Test
    void generatedRowsAreReadOnlyEverywhere() throws Exception {
        // The detail form renders read-only (no optimistic-concurrency token = no form).
        String detail = adminGet("/admin/instances/" + generatedDbInstanceId).body();
        assertThat(detail).as("no editable form is offered on a generated row")
            .doesNotContain("cms__snapshot");

        // A forged direct update is refused, not silently accepted.
        HttpResponse<String> update = httpPostForm(
            "/admin/instances/" + generatedDbInstanceId,
            "name=hijacked-engine", sessionToken, csrfToken);
        assertThat(update.statusCode()).as("the update endpoint refuses").isEqualTo(403);
        assertThat(Models.get(InstanceModel.class).findById(generatedDbInstanceId)
            .get(InstanceModel.NAME)).isEqualTo("ui-wave-db-engine");

        // The power verb is not offered AND its invoke answers missing.
        HttpResponse<String> deploy = httpPostForm(
            "/admin/instances/" + generatedDbInstanceId + "/action/deploy_instance",
            "", sessionToken, csrfToken);
        assertThat(deploy.statusCode())
            .as("a hidden action's invoke answers 404, the unoffered-slug rule")
            .isEqualTo(404);
    }

    /** The runtime-image catalog: seeded truth reads, code-owned rows refuse edits. */
    @Test
    void runtimeImageCatalogIsHonestAboutOwnership() throws Exception {
        String list = adminGet("/admin/runtime-images").body();
        assertThat(list).as("the seeded catalog renders").contains("node-22", "debian-13");

        Row builtin = Models.get(RuntimeImageModel.class).find()
            .where(RuntimeImageModel.NAME.eq("node-22")).first();
        assertThat(builtin).isNotNull();

        // FALSIFICATION: editing a built-in reports refusal, never a save the next boot
        // silently reverts.
        HttpResponse<String> update = httpPostForm(
            "/admin/runtime-images/" + builtin.get(RuntimeImageModel.ID),
            "name=node-22&docker_image=evil/image", sessionToken, csrfToken);
        assertThat(update.statusCode()).isEqualTo(403);
        assertThat(Models.get(RuntimeImageModel.class)
            .findById(builtin.get(RuntimeImageModel.ID))
            .get(RuntimeImageModel.DOCKER_IMAGE)).isNotEqualTo("evil/image");

        // An operator-authored variant stays editable: the refusal above is ownership,
        // not a frozen resource.
        var images = Models.get(RuntimeImageModel.class);
        Row custom = images.find().where(RuntimeImageModel.NAME.eq("ui-wave-custom")).first();
        if (custom == null) {
            custom = images.createEmptyRow();
            custom.set(RuntimeImageModel.NAME, "ui-wave-custom");
            custom.set(RuntimeImageModel.DOCKER_IMAGE, "example/custom:1");
            custom.set(RuntimeImageModel.BUILTIN, false);
            custom.set(RuntimeImageModel.ENABLED, true);
            images.save(custom);
        }
        HttpResponse<String> customUpdate = httpPostForm(
            "/admin/runtime-images/" + custom.get(RuntimeImageModel.ID),
            "name=ui-wave-custom&docker_image=example/custom:2&enabled=true",
            sessionToken, csrfToken);
        assertThat(customUpdate.statusCode()).isIn(302, 303);
        assertThat(images.findById(custom.get(RuntimeImageModel.ID))
            .get(RuntimeImageModel.DOCKER_IMAGE)).isEqualTo("example/custom:2");
    }

    /**
     * Volumes declare through the InstanceVolumes funnel (host path derived, quota in
     * MB stored as bytes), and the guards refuse by name: FALSIFICATION for the
     * home-volume protection and the kind gate.
     */
    @Test
    void volumesDeclareThroughTheFunnelAndTheGuardsHold() throws Exception {
        // Declare via the CMS form: quota entered in MB, stored in bytes.
        HttpResponse<String> create = httpPostForm("/admin/instance-volumes/new",
            "instance_id=" + workspaceId + "&name=data&container_path=%2Fdata&quota_mb=100",
            sessionToken, csrfToken);
        assertThat(create.statusCode()).isIn(302, 303);
        Row declared = Models.get(InstanceVolumeModel.class).find()
            .where(InstanceVolumeModel.INSTANCE_ID.eq(workspaceId))
            .where(InstanceVolumeModel.NAME.eq("data")).first();
        assertThat(declared).isNotNull();
        assertThat((Object) declared.get(InstanceVolumeModel.QUOTA_BYTES))
            .isEqualTo(100L * 1024 * 1024);
        assertThat(String.valueOf((Object) declared.get(InstanceVolumeModel.HOST_PATH)))
            .as("the host path is derived and stored as evidence")
            .contains("/volumes/" + workspaceId + "/data");

        // The Volumes tab renders the declaration and offers the add affordance.
        String tab = adminGet("/admin/instances/" + workspaceId + "/page/volumes").body();
        assertThat(tab).contains("/data").contains("add-volume-link");

        // FALSIFICATION 1: the workspace home volume refuses its own destruction.
        InstanceVolumes.declare(workspaceId, "home", "/home/site", null, false);
        Row home = Models.get(InstanceVolumeModel.class).find()
            .where(InstanceVolumeModel.INSTANCE_ID.eq(workspaceId))
            .where(InstanceVolumeModel.NAME.eq("home")).first();
        HttpResponse<String> destroyHome = httpPostForm(
            "/admin/instance-volumes/" + home.get(InstanceVolumeModel.ID)
                + "/action/destroy_volume",
            "", sessionToken, csrfToken);
        assertThat(destroyHome.statusCode()).isIn(302, 303);
        assertThat(Models.get(InstanceVolumeModel.class)
            .findById(home.get(InstanceVolumeModel.ID)))
            .as("the home volume survives the refusal").isNotNull();

        // FALSIFICATION 2: a kind that mounts no volumes cannot be declared onto.
        HttpResponse<String> wrongKind = httpPostForm("/admin/instance-volumes/new",
            "instance_id=" + dockerId + "&name=data&container_path=%2Fdata",
            sessionToken, csrfToken);
        assertThat(wrongKind.statusCode()).isNotIn(302, 303);
        assertThat(Models.get(InstanceVolumeModel.class).find()
            .where(InstanceVolumeModel.INSTANCE_ID.eq(dockerId)).count()).isZero();

        // There is deliberately NO plain delete route for a volume declaration.
        assertThat(httpPostForm("/admin/instance-volumes/" + declared.get(InstanceVolumeModel.ID)
                + "/delete", "", sessionToken, csrfToken).statusCode())
            .as("the only removal is the typed-confirm destroy action")
            .isEqualTo(404);
    }

    /**
     * FALSIFICATION: the site form's instance pick is re-narrowed server-side -- a
     * hand-posted instance the picker never offers (a generated database engine
     * publishes no site-servable port) is refused, and the exposable control passes.
     */
    @Test
    void siteUpstreamPickIsNarrowedServerSide() throws Exception {
        HttpResponse<String> refused = httpPostForm("/admin/sites/new",
            "name=ui-wave-refused-site&upstream_kind=hohenheim%3Ainstance&instance_id="
                + generatedDbInstanceId,
            sessionToken, csrfToken);
        assertThat(refused.statusCode())
            .as("a database engine as an upstream must not create-redirect")
            .isNotIn(302, 303);
        assertThat(Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("ui-wave-refused-site"))
            .count()).isZero();

        HttpResponse<String> accepted = httpPostForm("/admin/sites/new",
            "name=ui-wave-exposed-site&upstream_kind=hohenheim%3Ainstance&instance_id="
                + applicationId,
            sessionToken, csrfToken);
        assertThat(accepted.statusCode())
            .as("the control: the application is exposable").isIn(302, 303);
        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("ui-wave-exposed-site"))
            .first();
        assertThat(site).isNotNull();
        assertThat((Object) site.get(SiteModel.INSTANCE_ID))
            .isEqualTo(applicationId);
    }

    /** The sites list keeps its verbs: toggle inline, edit and delete synthesized. */
    @Test
    void sitesListOffersItsRowActions() throws Exception {
        String list = adminGet("/admin/sites").body();
        assertThat(list).as("the toggle verb renders").contains("toggle_site");
        assertThat(list).as("the synthesized delete renders").contains("data-action-id=\"zenitcms:delete\"");
    }

    /** The Expose action's target: the site create form arrives prefilled. */
    @Test
    void exposePrefillsTheSiteCreateForm() throws Exception {
        String form = adminGet("/admin/sites/new?upstream_kind=hohenheim%3Ainstance"
            + "&instance_id=" + applicationId).body();
        assertThat(form)
            .as("the instance upstream's settings sub-form renders server-side, which"
                + " only happens when the prefilled card is the selection")
            .contains("name=\"settings.port\"");
        assertThat(form).as("the instance pick is present for the prefilled id")
            .contains("name=\"instance_id\"");
    }
}
