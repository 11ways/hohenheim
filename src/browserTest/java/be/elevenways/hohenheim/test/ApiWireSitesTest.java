package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ArtifactOperationModel;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.hohenheim.test.ApiWire.Caller;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.test.ApiSupport.form;

/**
 * The site, PaaS operation, project and environment routes of {@code /api/v1}: every route's success reply and its
 * main refusal, compared byte for byte to the java-rewrite capture through {@link ApiWire}.
 *
 * AIDEV-NOTE: intended difference (W1a, 2026-10-05): a domain added without force_ssl answers
 * {@code "force_ssl":false}, because a new address is forced once its certificate works (ForceSslLatch), not before;
 * the shape is unchanged and that one value is re-recorded.
 *
 * AIDEV-NOTE: the class runs on a database of its own, copied from the migrated template, so every id is the same
 * on every run; the fixtures are created in one fixed order and every instant they carry is {@link #T0} or
 * {@link #T1}. The rollback fixture converges real releases over {@link FakeDockerDaemon} and is built after every
 * other exchange, so the rows it writes can never shift an id another reply shows.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class ApiWireSitesTest extends HohenheimTestBase {

    private static final Instant T0 = Instant.parse("2026-01-02T03:04:05Z");
    private static final Instant T1 = Instant.parse("2026-01-02T03:05:05Z");

    private static FakeDockerDaemon daemon;
    private static Integer savedProbeTimeout;
    private static Integer savedProbeInterval;
    private static Integer savedDrain;
    private static String savedDataPath;

    private static Caller admin;
    private static Caller tenant;
    private static Caller tenantSession;

    private static int alphaSiteId;
    private static int alphaApplicationId;
    private static int bravoSiteId;
    private static int staticSiteId;
    private static int lockedSiteId;
    private static int bravoDomainId;
    private static int alphaReleaseId;
    private static int bravoReleaseId;
    private static int alphaBuildId;
    private static int bravoBuildId;
    private static int alphaArtifactId;
    private static int bravoArtifactId;
    private static int projectId;
    private static int foreignProjectId;
    private static int environmentId;

    @BeforeAll
    static void seed() throws Exception {
        TestDatabases.freshDatabase();
        daemon = new FakeDockerDaemon();
        daemon.install();
        savedProbeTimeout = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS);
        savedProbeInterval = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS);
        savedDrain = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Releases.DRAIN_SECONDS);
        savedDataPath = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, 2);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS, 50);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, 0);
        // A writable data root: the artifact upload lands under it, and no reply names it.
        Path dataRoot = Files.createTempDirectory("hohenheim-api-wire");
        dataRoot.toFile().deleteOnExit();
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, dataRoot.toString());

        int operatorId = ApiWire.operatorId();
        int tenantId = ApiSupport.user("wire-sites-tenant@surface.test", "Wire Sites Tenant");
        HostFixtures.admitLocal();

        alphaApplicationId = application("wire-alpha-app", "v1");
        alphaSiteId = instanceSite("wire-alpha", alphaApplicationId);
        int bravoApplicationId = application("wire-bravo-app", "v1");
        bravoSiteId = instanceSite("wire-bravo", bravoApplicationId);
        staticSiteId = staticSite("wire-static", false);
        lockedSiteId = staticSite("wire-locked", false);
        domain(alphaSiteId, "alpha.wire.test");
        bravoDomainId = domain(bravoSiteId, "bravo.wire.test");
        domain(lockedSiteId, "localhost");
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, alphaSiteId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, staticSiteId,
            HohenheimAccess.MANAGE, true);

        alphaReleaseId = releaseOperation(alphaApplicationId, "wire-release-log-of-alpha");
        bravoReleaseId = releaseOperation(bravoApplicationId, "wire-release-log-of-bravo");
        alphaBuildId = buildOperation(alphaSiteId, "wire-build-log-of-alpha");
        bravoBuildId = buildOperation(bravoSiteId, "wire-build-log-of-bravo");
        alphaArtifactId = artifactOperation(alphaSiteId, alphaApplicationId);
        bravoArtifactId = artifactOperation(bravoSiteId, bravoApplicationId);

        projectId = project("wire-project", "Wire project");
        foreignProjectId = project("wire-foreign-project", "");
        Projects.addMember(Models.get(ProjectModel.class).findById(projectId), tenantId);
        environmentId = environment(projectId, "wire-production", "Live traffic");
        environment(projectId, "wire-staging", "");
        Row seeded = Models.get(InstanceVariableModel.class).createEmptyRow();
        seeded.set(InstanceVariableModel.ENVIRONMENT_ID, environmentId);
        seeded.set(InstanceVariableModel.KEY, "WIRE_SEEDED");
        seeded.set(InstanceVariableModel.KIND, InstanceVariableModel.KIND_PLAIN);
        seeded.set(InstanceVariableModel.PLAIN_VALUE, "seeded-value");
        Models.get(InstanceVariableModel.class).save(seeded);

        admin = new Caller.Key(ApiKeyService.create(operatorId, "wire-sites-admin", List.of("hohenheim.*"), null)
            .plaintext());
        tenant = new Caller.Key(ApiKeyService.create(tenantId, "wire-sites-tenant",
            List.of(CapabilityScopes.format(SiteModel.MODEL_ID, HohenheimAccess.MANAGE),
                CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimAccess.MANAGE)), null).plaintext());
        tenantSession = new Caller.Session(sessionCookieHeader(sessionFor(tenantId).token()));
    }

    @AfterAll
    static void tearDown() throws Exception {
        FakeDockerDaemon.restore();
        if (daemon != null) {
            daemon.close();
            daemon = null;
        }
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, savedProbeTimeout);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS, savedProbeInterval);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, savedDrain);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, savedDataPath);
        // Later classes of this JVM find the seeded shared database they expect.
        freshSeededDatabase();
    }

    @Test
    void theSiteRoutesAnswerWhatJavaRewriteAnswered() {
        ApiWire wire = new ApiWire("sites", this::requestTo, HohenheimTestBase::sendRequestBytes);
        String alpha = "/api/v1/sites/" + alphaSiteId;
        String bravo = "/api/v1/sites/" + bravoSiteId;

        // 1. The site reads: the list and the detail, refused to a session and for a site the key does not manage.
        wire.get("list sites", tenant, "/api/v1/sites");
        wire.get("list sites as a browser session", tenantSession, "/api/v1/sites");
        wire.get("read a managed site", tenant, alpha);
        wire.get("read an unmanaged site", tenant, bravo);
        wire.get("list a site's domains", tenant, alpha + "/domains");
        wire.get("list an unmanaged site's domains", tenant, bravo + "/domains");

        // 2. The operation records: deployments, releases and builds, each refused across sites.
        wire.get("list deployments", tenant, alpha + "/deployments");
        wire.get("list an unmanaged site's deployments", tenant, bravo + "/deployments");
        wire.get("read a deployment log", tenant, alpha + "/deployments/" + alphaReleaseId + "/log");
        wire.get("read another site's deployment log", tenant, alpha + "/deployments/" + bravoReleaseId + "/log");
        wire.get("list releases", tenant, alpha + "/releases");
        wire.get("list an unmanaged site's releases", tenant, bravo + "/releases");
        wire.get("read a release", tenant, alpha + "/releases/" + alphaReleaseId);
        wire.get("read another site's release", tenant, alpha + "/releases/" + bravoReleaseId);
        wire.get("list builds", tenant, alpha + "/builds");
        wire.get("list an unmanaged site's builds", tenant, bravo + "/builds");
        wire.get("read a build log", tenant, alpha + "/builds/" + alphaBuildId + "/log");
        wire.get("read another site's build log", tenant, alpha + "/builds/" + bravoBuildId + "/log");

        // 3. The artifact reads: the serving artifact and one upload receipt, refused where no application is.
        wire.get("read the current artifact", admin, alpha + "/artifact");
        wire.get("read the artifact of a site without an application", admin,
            "/api/v1/sites/" + staticSiteId + "/artifact");
        wire.get("read an artifact receipt", admin, alpha + "/artifact/" + alphaArtifactId);
        wire.get("read another site's artifact receipt", admin, alpha + "/artifact/" + bravoArtifactId);

        // 4. Projects and environment variables, the environment lane being the admin panel's.
        wire.get("list projects", tenant, "/api/v1/projects");
        wire.get("list projects as a browser session", tenantSession, "/api/v1/projects");
        wire.get("read a member project", tenant, "/api/v1/projects/" + projectId);
        wire.get("read a project without membership", tenant, "/api/v1/projects/" + foreignProjectId);
        String variables = "/api/v1/environments/" + environmentId + "/variables";
        wire.get("list environment variables", admin, variables);
        wire.get("list environment variables with a tenant key", tenant, variables);
        wire.post("set an environment variable", admin, variables,
            form("key", "WIRE_PLAIN", "value", "plain-value"));
        wire.post("set an environment variable of an unknown kind", admin, variables,
            form("key", "WIRE_ODD", "kind", "mystery", "value", "v"));
        wire.post("delete an environment variable", admin, variables + "/delete", form("key", "WIRE_PLAIN"));
        wire.post("delete an absent environment variable", admin, variables + "/delete", form("key", "WIRE_PLAIN"));

        // 5. Domains: add and remove a hostname, refused for a contradicting site and for another site's row.
        ApiWire.Reply added = wire.post("add a domain", admin, alpha + "/domains", form("hostname", "beta.wire.test"));
        wire.post("add a domain naming another site", admin, alpha + "/domains",
            form("hostname", "gamma.wire.test", "site_id", String.valueOf(bravoSiteId)));
        wire.post("remove a domain", admin, alpha + "/domains/" + ApiSupport.idOf(added.text()) + "/delete", "");
        wire.post("remove another site's domain", admin, alpha + "/domains/" + bravoDomainId + "/delete", "");

        // 6. Sites: create one and delete it, refused for an undeclared field and for the site serving this panel.
        ApiWire.Reply created = wire.post("create a site", admin, "/api/v1/sites", form(
            "name", "wire-created", "upstream_kind", "hohenheim:static", "enabled", "false",
            "settings.root_path", "/tmp/wire-created"));
        wire.post("create a site with an undeclared field", admin, "/api/v1/sites", form(
            "name", "wire-stranger", "upstream_kind", "hohenheim:static", "settings.root_path", "/tmp/x",
            "colour", "red"));
        wire.post("delete a site", admin, "/api/v1/sites/" + ApiSupport.idOf(created.text()) + "/delete", "");
        wire.post("delete the site serving this panel", admin, "/api/v1/sites/" + lockedSiteId + "/delete", "");

        // 7. The artifact upload: an empty body is refused before a byte is kept, a body is accepted.
        wire.post("upload an empty artifact", admin, alpha + "/artifact", new byte[0], "application/octet-stream");
        wire.post("upload an artifact", admin, alpha + "/artifact",
            "wire-artifact-bytes".getBytes(StandardCharsets.UTF_8), "application/octet-stream");

        // 8. Rollback: a site without an application has none, an application with a retired release rolls back.
        wire.post("roll back a site without an application", admin,
            "/api/v1/sites/" + staticSiteId + "/rollback", "");
        int rollbackSiteId = rollbackFixture();
        wire.post("roll back", admin, "/api/v1/sites/" + rollbackSiteId + "/rollback", "");

        // 9. Deploy: a site without an application refuses, an application's site queues.
        wire.post("deploy a site without an application", admin, "/api/v1/sites/" + staticSiteId + "/deploy", "");
        wire.post("deploy", admin, alpha + "/deploy", "");

        // 10. Every reply is the java-rewrite one.
        wire.assertGolden();
    }

    // -- fixtures -------------------------------------------------------------------------------------------------

    private static int application(String name, String tag) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        row.set(InstanceModel.SETTINGS, applicationSettings(tag));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        row.set(InstanceModel.CREATED_AT, T0);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static Map<String, Object> applicationSettings(String tag) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "fake/app");
        settings.put("tag", tag);
        settings.put("container_port", 8080);
        return settings;
    }

    private static int instanceSite(String name, int applicationId) {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, name);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:instance");
        row.set(SiteModel.ENABLED, false);
        row.set(SiteModel.INSTANCE_ID, applicationId);
        Models.get(SiteModel.class).save(row);
        return row.get(SiteModel.ID);
    }

    private static int staticSite(String name, boolean enabled) {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, name);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.ENABLED, enabled);
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.SETTINGS, new LinkedHashMap<>(Map.of("root_path", "/tmp/" + name)));
        Models.get(SiteModel.class).save(row);
        return row.get(SiteModel.ID);
    }

    private static int domain(int siteId, String hostname) {
        Row row = Models.get(SiteDomainModel.class).createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, siteId);
        row.set(SiteDomainModel.HOSTNAME, hostname);
        row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        row.set(SiteDomainModel.FORCE_SSL, false);
        Models.get(SiteDomainModel.class).save(row);
        return row.get(SiteDomainModel.ID);
    }

    private static int releaseOperation(int applicationId, String stepLog) {
        Row op = Models.get(ReleaseOperationModel.class).createEmptyRow();
        op.set(ReleaseOperationModel.KIND, ReleaseOperationModel.KIND_RELEASE);
        op.set(ReleaseOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        op.set(ReleaseOperationModel.FOR_ID, applicationId);
        op.set(ReleaseOperationModel.STATUS, ReleaseOperationModel.STATUS_SUCCEEDED);
        op.set(ReleaseOperationModel.IMAGE_ID, "sha256:wire-release");
        op.set(ReleaseOperationModel.STEP_LOG, stepLog);
        op.set(ReleaseOperationModel.STARTED_AT, T0);
        op.set(ReleaseOperationModel.FINISHED_AT, T1);
        op.set(ReleaseOperationModel.DURATION_MS, 60_000);
        op.set(ReleaseOperationModel.CREATED_AT, T0);
        Models.get(ReleaseOperationModel.class).save(op);
        return op.get(ReleaseOperationModel.ID);
    }

    private static int buildOperation(int siteId, String log) {
        Row op = Models.get(BuildOperationModel.class).createEmptyRow();
        op.set(BuildOperationModel.BUILDER_KIND, BuildOperationModel.KIND_DOCKERFILE);
        op.set(BuildOperationModel.FOR_MODEL, SiteModel.MODEL_ID.toString());
        op.set(BuildOperationModel.FOR_ID, siteId);
        op.set(BuildOperationModel.STATUS, BuildOperationModel.STATUS_SUCCEEDED);
        op.set(BuildOperationModel.SOURCE_REF, "main");
        op.set(BuildOperationModel.IMAGE_ID, "sha256:wire-build");
        op.set(BuildOperationModel.EXIT_CODE, 0);
        op.set(BuildOperationModel.LOG, log);
        op.set(BuildOperationModel.STARTED_AT, T0);
        op.set(BuildOperationModel.FINISHED_AT, T1);
        op.set(BuildOperationModel.DURATION_MS, 60_000);
        op.set(BuildOperationModel.CREATED_AT, T0);
        Models.get(BuildOperationModel.class).save(op);
        return op.get(BuildOperationModel.ID);
    }

    private static int artifactOperation(int siteId, int applicationId) {
        Row op = Models.get(ArtifactOperationModel.class).createEmptyRow();
        op.set(ArtifactOperationModel.SITE_ID, siteId);
        op.set(ArtifactOperationModel.APPLICATION_ID, applicationId);
        op.set(ArtifactOperationModel.STATUS, ArtifactOperationModel.SUCCEEDED);
        op.set(ArtifactOperationModel.ARTIFACT_SHA256,
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
        op.set(ArtifactOperationModel.IMAGE_ID, "sha256:wire-artifact");
        op.set(ArtifactOperationModel.FINISHED_AT, T1);
        op.set(ArtifactOperationModel.CREATED_AT, T0);
        Models.get(ArtifactOperationModel.class).save(op);
        return op.get(ArtifactOperationModel.ID);
    }

    private static int project(String name, String description) {
        Row row = Models.get(ProjectModel.class).createEmptyRow();
        row.set(ProjectModel.NAME, name);
        row.set(ProjectModel.DESCRIPTION, description);
        row.set(ProjectModel.CREATED_AT, T0);
        Models.get(ProjectModel.class).save(row);
        return row.get(ProjectModel.ID);
    }

    private static int environment(int projectId, String name, String description) {
        Row row = Models.get(EnvironmentModel.class).createEmptyRow();
        row.set(EnvironmentModel.PROJECT_ID, projectId);
        row.set(EnvironmentModel.NAME, name);
        row.set(EnvironmentModel.DESCRIPTION, description);
        row.set(EnvironmentModel.CREATED_AT, T0);
        Models.get(EnvironmentModel.class).save(row);
        return row.get(EnvironmentModel.ID);
    }

    /**
     * An application site whose application served v1, then v2, so v1 is the retained rollback target.
     *
     * @return the site's id
     */
    private static int rollbackFixture() {
        int applicationId = application("wire-rollback-app", "v1");
        int siteId = instanceSite("wire-rollback", applicationId);
        ApplicationReleases.converge(applicationId, Map.of());
        Row application = Models.get(InstanceModel.class).findById(applicationId);
        application.set(InstanceModel.SETTINGS, applicationSettings("v2"));
        Models.get(InstanceModel.class).save(application);
        ApplicationReleases.converge(applicationId, Map.of());
        Poll.until("the v2 release settles after its drain window", Duration.ofSeconds(15), Duration.ofMillis(50),
            () -> ReleaseOperationModel.STATUS_SUCCEEDED.equals(Models.get(ReleaseOperationModel.class).find()
                .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
                .where(ReleaseOperationModel.FOR_ID.eq(applicationId))
                .orderBy(ReleaseOperationModel.ID, SortOrder.DESC)
                .first().get(ReleaseOperationModel.STATUS)));
        return siteId;
    }
}
