package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Screenshot generator for the admin-UI wave: walks every page the wave touched, in
 * light AND dark, over a realistic fixture fleet, and writes the captures where a
 * human can review them. It asserts only that each page renders (title/2xx via the
 * navigation itself); the point is the pictures.
 *
 * AIDEV-NOTE: captures land OUTSIDE the repo (~/temp/hohenheim-ui). The class stays in
 * the suite so the pages it walks stay renderable; the screenshots are a cheap side
 * effect of that walk, and the directory is created on demand.
 */
class AdminUiScreenshotTest extends HohenheimTestBase {

    private static final Path OUT = Paths.get(System.getProperty("user.home"),
        "temp", "hohenheim-ui");

    private static Integer dockerHostId;
    private static Integer workspaceId;
    private static Integer applicationId;

    @BeforeAll
    static void seedFleet() throws Exception {
        Files.createDirectories(OUT);

        dockerHostId = host("orion", ServerModel.RUNTIME_DOCKER, "btrfs");
        host("vega", ServerModel.RUNTIME_INCUS, "none");

        workspaceId = instance("blog-workspace", "hohenheim:workspace", dockerHostId);
        applicationId = instance("shop-api", "hohenheim:application", dockerHostId);
        instance("adhoc-redis", "hohenheim:docker_container", dockerHostId);

        // A generated database engine, so the list shows a "Managed by" row.
        var databases = Models.get(DatabaseModel.class);
        Row database = databases.find().where(DatabaseModel.NAME.eq("shop-db")).first();
        if (database == null) {
            database = databases.createEmptyRow();
            database.set(DatabaseModel.NAME, "shop-db");
            database.set(DatabaseModel.ENGINE, "postgres");
            database.set(DatabaseModel.DB_USER, "shop");
            database.set(DatabaseModel.DB_PASSWORD, "s3cret");
            database.set(DatabaseModel.DB_NAME, "shop");
            databases.save(database);
        }
        Integer databaseId = database.get(DatabaseModel.ID);
        if (Models.get(InstanceModel.class).find()
                .where(InstanceModel.NAME.eq("shop-db-engine")).first() == null) {
            OwnedInstances.inScopeUnchecked("database", DatabaseModel.MODEL_ID, databaseId, () -> {
                Row row = Models.get(InstanceModel.class).createEmptyRow();
                row.set(InstanceModel.NAME, "shop-db-engine");
                row.set(InstanceModel.KIND, "hohenheim:database_container");
                row.set(InstanceModel.SERVER_ID, dockerHostId);
                row.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
                row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of()));
                Models.get(InstanceModel.class).save(row);
            });
        }

        // Volumes with observed usage, so the tab shows real columns.
        InstanceVolumes.declare(workspaceId, "home", "/home/site", 2048L * 1024 * 1024, false);
        InstanceVolumes.declare(workspaceId, "uploads", "/home/site/uploads",
            512L * 1024 * 1024, false);
        var volumes = Models.get(InstanceVolumeModel.class);
        for (Row volume : volumes.find()
                .where(InstanceVolumeModel.INSTANCE_ID.eq(workspaceId)).all()) {
            volume.set(InstanceVolumeModel.USED_BYTES,
                "home".equals(volume.get(InstanceVolumeModel.NAME))
                    ? 731L * 1024 * 1024 : 48L * 1024 * 1024);
            volume.set(InstanceVolumeModel.OBSERVED_AT, Instant.now());
            volumes.save(volume);
        }

        // A deploy history for the application's Deploys tab.
        releaseOperation(applicationId, ReleaseOperationModel.STATUS_SUCCEEDED,
            "9f3ab21c44", null, 42150, 90);
        releaseOperation(applicationId, ReleaseOperationModel.STATUS_FAILED,
            "1adf99e072", "health probe never answered on port 3000", 61400, 30);
        releaseOperation(applicationId, ReleaseOperationModel.STATUS_SUCCEEDED,
            "77e0c5b911", null, 39800, 5);

        // Sites: one per interesting upstream, with hostnames and mixed TLS.
        site("Blog", "blog", "hohenheim:static",
            Map.of("root_path", "/srv/blog"), null, "blog.example.com", true);
        site("Shop", "shop", "hohenheim:instance",
            Map.of("port", "", "scheme", "http"), applicationId, "shop.example.com", true);
        site("Legacy redirect", "legacy", "hohenheim:redirect",
            Map.of("target_url", "https://blog.example.com"), null, "old.example.com", false);
    }

    private static Integer host(String name, String runtime, String backend) {
        var servers = Models.get(ServerModel.class);
        Row existing = servers.find().where(ServerModel.NAME.eq(name)).first();
        if (existing != null) {
            return existing.get(ServerModel.ID);
        }
        Row row = servers.createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.MODE, ServerModel.MODE_SSH);
        row.set(ServerModel.RUNTIME, runtime);
        row.set(ServerModel.VOLUME_BACKEND, backend);
        row.set(ServerModel.SSH_TARGET, "root@" + name + ".internal");
        row.set(ServerModel.PUBLIC_IPV4, "203.0.113." + (Math.abs(name.hashCode()) % 200 + 10));
        servers.save(row);
        return row.get(ServerModel.ID);
    }

    private static Integer instance(String name, String kind, Integer serverId) {
        var instances = Models.get(InstanceModel.class);
        Row existing = instances.find().where(InstanceModel.NAME.eq(name)).first();
        if (existing != null) {
            return existing.get(InstanceModel.ID);
        }
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, kind);
        row.set(InstanceModel.SERVER_ID, serverId);
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of()));
        // A kind that cannot run without a runtime image may not be written without one
        // (InstanceDeclarations); the seeded builtin is what the create form would pick.
        InstanceKindHandler handler = InstanceKinds.getHandler(kind);
        if (handler != null && handler.requiresRuntimeImage()) {
            Row image = Models.get(RuntimeImageModel.class).find()
                .where(RuntimeImageModel.NAME.eq("node-22")).first();
            row.set(InstanceModel.RUNTIME_IMAGE_ID,
                image == null ? null : image.get(RuntimeImageModel.ID));
        }
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static void releaseOperation(Integer applicationId, String status,
                                         String sha, String failure, int durationMs,
                                         int minutesAgo) {
        var operations = Models.get(ReleaseOperationModel.class);
        if (operations.find().where(ReleaseOperationModel.IMAGE_ID.eq(sha)).first() != null) {
            return;
        }
        Row row = operations.createEmptyRow();
        row.set(ReleaseOperationModel.KIND, ReleaseOperationModel.KIND_RELEASE);
        row.set(ReleaseOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        row.set(ReleaseOperationModel.FOR_ID, applicationId);
        row.set(ReleaseOperationModel.STATUS, status);
        row.set(ReleaseOperationModel.IMAGE_ID, sha);
        row.set(ReleaseOperationModel.FAILURE_REASON, failure);
        row.set(ReleaseOperationModel.STEP_LOG,
            "checkout " + sha + "\nnixpacks build\nprobe /healthz\n"
                + (failure == null ? "switch + drain" : "FAILED: " + failure));
        row.set(ReleaseOperationModel.STARTED_AT,
            Instant.now().minusSeconds(minutesAgo * 60L));
        row.set(ReleaseOperationModel.DURATION_MS, durationMs);
        operations.save(row);
    }

    private static void site(String name, String slug, String kind,
                             Map<String, Object> settings, Integer instanceId,
                             String hostname, boolean forceSsl) {
        var sites = Models.get(SiteModel.class);
        if (sites.find().where(SiteModel.SLUG.eq(slug)).first() != null) {
            return;
        }
        Row row = sites.createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, kind);
        row.set(SiteModel.SETTINGS, new LinkedHashMap<>(settings));
        row.set(SiteModel.INSTANCE_ID, instanceId);
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.ENABLED, true);
        sites.save(row);
        Row domain = Models.get(SiteDomainModel.class).createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, row.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domain.set(SiteDomainModel.FORCE_SSL, forceSsl);
        Models.get(SiteDomainModel.class).save(domain);
    }

    private void capture(String path, String slug) {
        capture(path, slug, null);
    }

    /** Render {@code path} in both themes and write the two captures. */
    private void capture(String path, String slug, Runnable interaction) {
        for (String theme : List.of("light", "dark")) {
            page.context().addCookies(List.of(new Cookie("pl-theme", theme)
                .setDomain("localhost").setPath("/")));
            page.setViewportSize(1440, 900);
            navigateToApp(path);
            waitForHydration();
            if (interaction != null) {
                interaction.run();
            }
            page.screenshot(new Page.ScreenshotOptions()
                .setPath(OUT.resolve(slug + "-" + theme + ".png"))
                .setFullPage(true));
        }
    }

    @Test
    void captureEveryTouchedPageInBothThemes() {
        capture("/admin/instances", "instances-list");
        capture("/admin/instances/new", "instance-create-blank");
        capture("/admin/instances/new", "instance-create-workspace", () -> {
            page.click("pl-choice-group[name='kind']"
                + " pl-choice-card[data-value='hohenheim:workspace'] button");
            waitForReactiveIdle();
            waitForReactiveIdle();
        });
        // The same form with one kind-settings fold opened: the pair is the review
        // evidence that a schema-declared section hides fields rather than dropping them.
        capture("/admin/instances/new", "instance-create-workspace-build-open", () -> {
            page.click("pl-choice-group[name='kind']"
                + " pl-choice-card[data-value='hohenheim:workspace'] button");
            waitForReactiveIdle();
            waitForReactiveIdle();
            page.click("pl-fieldset[data-path='settings']"
                + " pl-card[data-section='build'] pl-collapsible-trigger");
            waitForReactiveIdle();
        });
        capture("/admin/instances/" + workspaceId + "/page/overview", "instance-overview");
        capture("/admin/instances/" + workspaceId + "/page/volumes", "instance-volumes");
        capture("/admin/instances/" + workspaceId, "instance-settings-form");
        capture("/admin/instances/" + applicationId + "/page/overview",
            "application-overview");
        capture("/admin/instances/" + applicationId + "/page/deployments",
            "instance-deployments");
        capture("/admin/sites", "sites-list");
        capture("/admin/sites/new", "site-create-blank");
        // The same form with its advanced section opened: the pair is the review
        // evidence that a fold hides fields rather than dropping them.
        capture("/admin/sites/new", "site-create-advanced-open", () -> {
            page.click("[data-section='advanced'] pl-collapsible-trigger");
            waitForReactiveIdle();
        });
        capture("/admin/sites/new?upstream_kind=hohenheim%3Ainstance&instance_id="
            + applicationId, "site-create-expose-prefill");
        capture("/admin/runtime-images", "runtime-images-list");
        capture("/admin/servers/" + dockerHostId + "/page/overview", "host-overview");
    }
}
