package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.instance.InstanceKindInfo;
import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.Resource;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * The install-state vocabulary is PINNED against its classifier: a sixth member fails
     * here until a human has decided whether it is worth a badge. The switch in
     * {@code InstanceModel.isNotableInstallState} has no enum to be exhaustive over, so
     * this equality IS the exhaustiveness.
     */
    @Test
    void everyInstallStateMemberIsClassified() {
        assertThat(InstanceModel.INSTALL_STATE.getValues().keySet())
            .as("the declared install-state vocabulary")
            .containsExactlyInAnyOrder(InstanceModel.INSTALL_NONE, InstanceModel.INSTALL_PENDING,
                InstanceModel.INSTALL_INSTALLING, InstanceModel.INSTALL_INSTALLED,
                InstanceModel.INSTALL_FAILED);

        assertThat(InstanceModel.isNotableInstallState(InstanceModel.INSTALL_NONE))
            .as("'no install step' is the majority and says nothing").isFalse();
        assertThat(InstanceModel.isNotableInstallState(null))
            .as("an absent column is the same absence").isFalse();

        for (String notable : List.of(InstanceModel.INSTALL_PENDING,
                InstanceModel.INSTALL_INSTALLING, InstanceModel.INSTALL_INSTALLED,
                InstanceModel.INSTALL_FAILED)) {
            assertThat(InstanceModel.isNotableInstallState(notable))
                .as("'" + notable + "' is a state an operator can act on").isTrue();
        }

        assertThat(InstanceModel.isNotableInstallState("wedged"))
            .as("an undeclared key is SHOWN: hiding what nobody classified is how a stuck"
                + " install becomes invisible")
            .isTrue();
    }

    /**
     * Steps 1-5: the install badge renders only where there IS an install lifecycle, on
     * BOTH surfaces that present one. Step 3 and 4 are the falsification.
     */
    @Test
    void installBadgeRendersOnlyWhereThereIsAnInstallLifecycle() throws Exception {
        var instances = Models.get(InstanceModel.class);
        Row workspace = instances.findById(workspaceId);

        // 1. The fixture is a template-less record, which is the overwhelming majority.
        assertThat((Object) workspace.get(InstanceModel.INSTALL_STATE))
            .as("step 1: the fixture declares no install step")
            .isEqualTo(InstanceModel.INSTALL_NONE);

        // 2. So neither the fleet list nor the record's state band spends a badge on it.
        assertThat(adminGet("/admin/instances?filter.name=ui-wave").body())
            .as("step 2: no per-row 'no install step' badge")
            .doesNotContain("No install step");
        String quietOverview = adminGet("/admin/instances/" + workspaceId
            + "/page/overview").body();
        assertThat(quietOverview).as("step 2: nor one in the record's state band")
            .doesNotContain("No install step");
        assertThat(quietOverview)
            .as("step 2: the band itself still renders the states that DO say something")
            .contains("widget-status-badges");

        try {
            // 3. FALSIFICATION: a pending install is a state an operator acts on, and it
            //    reads on both surfaces.
            workspace.set(InstanceModel.INSTALL_STATE, InstanceModel.INSTALL_PENDING);
            instances.save(workspace);
            assertThat(adminGet("/admin/instances?filter.name=ui-wave-workspace").body())
                .as("step 3: a pending install still reads under the status pill")
                .contains("Install pending");
            assertThat(adminGet("/admin/instances/" + workspaceId + "/page/overview").body())
                .as("step 3: and in the state band")
                .contains("Install pending");

            // 4. FALSIFICATION 2: a failed install is never quietly dropped.
            workspace.set(InstanceModel.INSTALL_STATE, InstanceModel.INSTALL_FAILED);
            instances.save(workspace);
            assertThat(adminGet("/admin/instances/" + workspaceId + "/page/overview").body())
                .as("step 4: a failed install keeps its own wording")
                .contains("Install failed");
        } finally {
            workspace.set(InstanceModel.INSTALL_STATE, InstanceModel.INSTALL_NONE);
            instances.save(workspace);
        }

        // 5. Back to the majority case, and the list is quiet again.
        assertThat(adminGet("/admin/instances?filter.name=ui-wave").body())
            .as("step 5: the badge leaves with the lifecycle")
            .doesNotContain("No install step");
    }

    /**
     * Steps 1-4: Deploy is offered per KIND DECLARATION, never per kind name, and an
     * unrecognised kind fails closed. Steps 3 and 4 are the falsification.
     */
    @Test
    void deployIsOfferedOnlyForKindsAPersonMayPower() throws Exception {
        // 1. Every registered kind answers from its own generatedOnly() declaration.
        List<String> deployable = new ArrayList<>();
        for (InstanceKindInfo entry : InstanceKindRegistry.REGISTRY) {
            Identifier id = InstanceKindRegistry.REGISTRY.getId(entry);
            if (id == null) {
                continue;
            }
            InstanceKindHandler handler = InstanceKinds.getHandler(id.toString());
            boolean expected = handler != null && !handler.generatedOnly();
            assertThat(InstanceKinds.isUserDeployable(id.toString()))
                .as("step 1: " + id + " is deployable exactly when it is not owner-managed")
                .isEqualTo(expected);
            if (expected) {
                deployable.add(id.toString());
            }
        }

        // 2. And that is the SAME declaration the create form's kind offer reads, so what
        //    a person may author and what a person may power cannot drift apart.
        List<String> authorable = new ArrayList<>();
        for (FieldOption<String> option : InstanceKinds.authorableOptions()) {
            authorable.add(option.value());
        }
        assertThat(deployable)
            .as("step 2: deployable kinds are exactly the authorable ones")
            .containsExactlyInAnyOrderElementsOf(authorable);

        // 3. FALSIFICATION: a kind with no handler has no driver to deploy through, so
        //    the affordance is NOT offered -- fail closed, never a button that can only
        //    refuse.
        assertThat(InstanceKinds.isUserDeployable("hohenheim:not_a_registered_kind"))
            .as("step 3: an unknown kind is not deployable").isFalse();
        assertThat(InstanceKinds.isUserDeployable(null))
            .as("step 3: neither is an absent one").isFalse();

        // 4. On the surface itself, narrowed to one row each: the authored workspace
        //    offers Deploy, the generated database engine does not.
        assertThat(adminGet("/admin/instances?filter.name=ui-wave-workspace").body())
            .as("step 4: the control -- an authored workspace offers Deploy")
            .contains("deploy_instance");
        String engine = adminGet("/admin/instances?filter.name=ui-wave-db-engine").body();
        assertThat(engine).as("step 4: the engine row is the one listed")
            .contains("ui-wave-db-engine");
        assertThat(engine)
            .as("step 4: an owner-managed kind is never offered Deploy")
            .doesNotContain("deploy_instance");
    }

    /**
     * The list toolbar's ONE quiet overflow carries the sibling catalogs, and the title
     * bar carries none of them.
     *
     * Steps 1-3 are the contract, step 4 the falsification: the demoted peers used to be
     * HeaderAction.Url buttons in the title bar, and the whole point of declaring them as
     * related pages is that no such button can come back.
     */
    @Test
    void siblingCatalogsRideTheRelatedPagesMenu() throws Exception {
        // 1. The toolbar renders exactly one overflow. The marker is the ELEMENT, not the
        //    token: the trigger inside it carries data-cms-header-overflow, so a bare
        //    substring counts two.
        String list = adminGet("/admin/instances").body();
        assertThat(countOf(list, OVERFLOW_MENU))
            .as("step 1: the list toolbar renders its one overflow, and only one")
            .isEqualTo(1);

        // 2. Opening it is the only way to read it: the menu's content is rendered
        //    client-side into the portal, so the served HTML holds an empty element.
        assertThat(relatedItems("/admin/instances"))
            .as("step 2: every demoted catalog is offered as a related page")
            .contains("/admin/backup-targets", "/admin/instance-quotas",
                "/admin/game-domains", "/admin/builds", "/admin/releases");

        // 3. And the sites list makes the same promise over its own two siblings.
        assertThat(relatedItems("/admin/sites"))
            .as("step 3: both of the site tier's catalogs are related pages")
            .contains("/admin/auth-providers", "/admin/previews");

        // 4. FALSIFICATION: the header-action ids the old hack minted are gone. If one
        //    came back it would render as a button beside Create, which is the shape
        //    RelatedPage exists to stop.
        assertThat(list).as("step 4: no sibling link rides a header action any more")
            .doesNotContain("backup_targets_link", "instance_quotas_link",
                "game_domains_link", "builds_link", "releases_link");
        assertThat(adminGet("/admin/sites").body())
            .as("step 4: nor on the site tier")
            .doesNotContain("auth_providers_link", "previews_link");
    }

    /**
     * FALSIFICATION of the /manage half: a delegated resource inherits its operator
     * parent's declarations, and an ADMIN peer slug is not registered in the tenant
     * panel at all -- so the tenant list must declare none. ManagePanel's own
     * registration walk would refuse a leftover at boot, which is why reaching this
     * assertion at all is half the proof.
     */
    @Test
    void theTenantPanelDeclaresNoRelatedPages() {
        Panel admin = PanelRegistry.getBySlug("admin");
        Panel manage = PanelRegistry.getBySlug("manage");
        assertThat(admin).isNotNull();
        assertThat(manage).isNotNull();

        PanelPeer operatorList = admin.peerBySlug("instances");
        PanelPeer tenantList = manage.peerBySlug("instances");
        assertThat(operatorList).isInstanceOf(Resource.class);
        assertThat(tenantList).isInstanceOf(Resource.class);

        assertThat(((Resource<?>) operatorList).relatedPages())
            .as("the operator list names its demoted catalogs").isNotEmpty();
        assertThat(((Resource<?>) tenantList).relatedPages())
            .as("the tenant list names none of them").isEmpty();
        assertThat(((Resource<?>) manage.peerBySlug("sites")).relatedPages())
            .as("nor does the tenant site list").isEmpty();
    }

    /**
     * The record tab strip stops at the everyday destinations; housekeeping tabs live in
     * the "More" menu. Step 3 is the falsification.
     */
    @Test
    void theTabStripFoldsHousekeepingIntoMore() throws Exception {
        String page = adminGet("/admin/instances/" + workspaceId + "/page/overview").body();

        // AIDEV-NOTE: the split is positional because the menu's items are PORTALLED to
        // the end of the document (he-bottom) while the strip's anchors stay in place.
        // The load-bearing half is therefore what is NOT in the strip window.
        int more = page.indexOf("cms-record-tabs-more");
        assertThat(more).as("step 1: the strip renders its More disclosure").isGreaterThan(-1);
        String strip = page.substring(page.indexOf("cms-record-tabs"), more);
        String menu = page.substring(more);

        // 2. What an operator opens daily stays on the strip.
        assertThat(strip).as("step 2: the front door, the console, the files and the stats"
                + " stay visible")
            .contains("/page/overview", "/page/console", "/page/files", "/page/stats");

        // 3. FALSIFICATION: the housekeeping tabs are NOT on the strip -- they are in the
        //    menu. Before the declaration they filled the strip in declaration order and
        //    pushed Files and Stats out of it.
        for (String slug : List.of("volumes", "snapshots", "backups", "schedules")) {
            assertThat(strip).as("step 3: " + slug + " is not an everyday tab")
                .doesNotContain("/page/" + slug);
            assertThat(menu).as("step 3: " + slug + " is reachable in the More menu")
                .contains("/page/" + slug);
        }
    }

    /**
     * The record action band: the declared primary verb leads, at most three verbs sit
     * inline, and a destructive one never does. Step 3 is the falsification.
     */
    @Test
    void destructiveRecordActionsAreNeverInline() throws Exception {
        String page = adminGet("/admin/instances/" + workspaceId + "/page/overview").body();

        int band = page.indexOf("data-cms-record-actions");
        assertThat(band).as("step 1: the record renders its action band").isGreaterThan(-1);
        int menu = page.indexOf("cms-record-actions-overflow", band);
        assertThat(menu).as("step 1: and its one overflow").isGreaterThan(band);

        String inline = page.substring(band, menu);
        String overflow = page.substring(menu);

        // 2. Deploy is the DECLARED primary verb, so it leads the inline band, and the
        //    band never grows past its cap however many actions the resource declares.
        assertThat(inline).as("step 2: the primary verb is inline")
            .contains("deploy_instance");
        assertThat(countOf(inline, "data-action-id="))
            .as("step 2: at most three verbs sit inline")
            .isLessThanOrEqualTo(3);

        // 3. FALSIFICATION: destroying an instance and its data is declared destructive,
        //    so it can never buy an inline slot -- it is in the menu's destructive tail.
        assertThat(inline).as("step 3: the irreversible verb is not inline")
            .doesNotContain("destroy_instance_data");
        assertThat(overflow).as("step 3: it is in the menu")
            .contains("destroy_instance_data");

        // 4. And the housekeeping verbs that opted out are there with it.
        assertThat(inline).as("step 4: housekeeping opted out of the inline band")
            .doesNotContain("snapshot_instance", "backup_instance", "expose_instance");
        assertThat(overflow).as("step 4: and is reachable in the menu")
            .contains("snapshot_instance", "backup_instance");
    }

    /** The list toolbar's one overflow MENU (the trigger's data attribute is not it). */
    private static final String OVERFLOW_MENU = "<pl-dropdown-menu class=\"cms-header-overflow\"";

    /**
     * Where that menu's content actually lives once it opens: the shell's portal.
     *
     * AIDEV-NOTE: :visible is load-bearing. Every dropdown on the page portals its
     * popup into he-bottom whether open or not (the user menu's is there too), so an
     * unqualified selector waits on the first CLOSED one and times out.
     */
    private static final String OVERFLOW_POPUP =
        "he-bottom .pl-dropdown-menu-content__popup:visible";

    /**
     * The hrefs the list toolbar's overflow offers under its "Related pages" label.
     *
     * AIDEV-NOTE: this drives the browser rather than reading the served HTML because
     * the menu's content is rendered CLIENT-side into the he-bottom portal -- the
     * pl-dropdown-menu-content the server sends is empty, so any substring assertion
     * over the response would have matched the command palette's peer index instead and
     * passed with the menu itself broken.
     */
    private List<String> relatedItems(String listPath) {
        navigateToApp(listPath);
        waitForHydration();
        page.click("[data-cms-header-overflow]");
        page.waitForSelector(OVERFLOW_POPUP + " a[href]");
        List<String> hrefs = new ArrayList<>();
        var links = page.locator(OVERFLOW_POPUP + " a[href]");
        for (int index = 0; index < links.count(); index++) {
            hrefs.add(links.nth(index).getAttribute("href"));
        }
        return hrefs;
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at > -1) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
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
