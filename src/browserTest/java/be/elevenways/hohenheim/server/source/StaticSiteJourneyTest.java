package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteRedirectModel;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.StaticUpstreamKind;
import be.elevenways.hohenheim.site.ProtectPath;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.RecordedChanges;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.cms.PutOnline;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.proxy.ProxyReloadHooks;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.server.http.RateLimitMiddleware;
import be.elevenways.hohenheim.test.docker.TestImages;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.server.process.ProcessOutcome;
import be.elevenways.protoblast.server.process.Subprocess;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.operation.OperationRunStatus;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.server.operation.OperationRun;
import be.elevenways.zenit.server.operation.OperationRuns;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A static site from git walked from "Put something online" to served files and back: the wizard, a build of a local
 * repository on the workload host, the proxy serving its output, a push the scheduled check deploys, the new content
 * served, then the delete that takes its releases, containers and built images with it. And a site that serves a
 * folder deployed from a repository from its own page, without ever going dark.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@Tag("slow") // live lane: a real daemon, a sandboxed build, a private netns and the proxy; runs via `zenit-dev test --all`
class StaticSiteJourneyTest extends HohenheimTestBase {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String NAME = "static-journey";
    private static final String HOSTNAME = "static-journey.test";
    private static final String FOLDER_SITE = "folder-journey";
    private static final String FOLDER_HOSTNAME = "folder-journey.test";

    /** Unique per run, so a stale checkout or image of an earlier run can never answer for this one. */
    private static final String MARK = "static-" + System.nanoTime();

    private static PrivateNetns netns;
    private static ProxyServer proxy;
    private static Path upstream;
    private static Path folderUpstream;
    private static Integer instanceId;
    private static Integer folderAppId;

    @BeforeAll
    static void boot() throws Exception {
        netns = PrivateNetns.installEnforcing();
        HostFixtures.admitLocal();
        HostFixtures.makeLocalPlaceable(8192);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, false);
        upstream = repository("hohenheim-static-upstream", MARK + "-one");
        folderUpstream = repository("hohenheim-folder-upstream", MARK + "-repo-one");
        proxy = ProxyTestSupport.startProxy();
        ServerMain.adoptProxyServer(proxy);
        // As ServerMain's boot does: the website the run writes reaches the routing table through the model hooks.
        ProxyReloadHooks.install();
    }

    @AfterAll
    static void tearDown() {
        for (Integer app : new Integer[] {instanceId, folderAppId}) {
            if (app == null) {
                continue;
            }
            try {
                ApplicationReleases.destroyFor(app);
            } catch (RuntimeException e) {
                System.err.println("teardown: release reclaim failed - " + e.getMessage());
            }
        }
        for (Row site : Models.get(SiteModel.class).find().where(SiteModel.NAME.in(List.of(NAME, FOLDER_SITE))).all()) {
            for (Row domain : Models.get(SiteDomainModel.class).find()
                    .where(SiteDomainModel.SITE_ID.eq(site.get(SiteModel.ID))).all()) {
                HardDeletes.row(Models.get(SiteDomainModel.class), domain);
            }
            HardDeletes.row(Models.get(SiteModel.class), site);
        }
        ServerMain.adoptProxyServer(null);
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, true);
        PrivateNetns.uninstall(netns);
    }

    @Test
    void aStaticSiteIsBuiltServedRedeployedOnPushAndDeleted() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET), "Docker socket not present");
        LiveLane.require(LiveLane.Need.NETNS, netns != null, "no private netns: the sandbox refuses to build");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, TestImages.ALPINE);
        AccessContext operator = TenantConduits.operator();
        // By its stored id, so the journey compiles (and reads red) where the kind does not exist.
        String kind = "hohenheim:static_site";

        // 1. The chooser offers a static site among the operator's own code, and its card opens the wizard.
        String chooser = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE).body();
        assertThat(chooser).as("step 1: the static site is a choice of its own")
            .contains("instance:" + kind);
        assertThat(adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?choice=instance:" + kind)
                .headers().firstValue("location").orElse(""))
            .as("step 1: its card opens the put-online wizard").contains("workload=hohenheim%3Astatic_site");

        // 2. The wizard asks where, the site's own settings (repository, build command, output folder), then HTTPS.
        String wizard = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?workload=" + kind).body();
        assertThat(wizard).as("step 2: where, settings and HTTPS")
            .contains("data-zf-step=\"where\"").contains("data-zf-step=\"settings\"")
            .contains("data-zf-step=\"https\"");
        assertThat(wizard).as("step 2: the settings ask the repository, the build command and the output folder")
            .contains("repository_url").contains("build_command").contains("output_directory");

        // 3. One run creates the site and its website; its first deploy builds the repository on the host.
        Map<String, Object> form = new LinkedHashMap<>();
        form.put(InstanceModel.NAME.getName(), NAME);
        form.put(PutOnline.HOSTNAME.getName(), HOSTNAME);
        form.put(InstanceModel.KIND.getName(), kind);
        form.put("serverId", ServerModel.localServerId());
        form.put(InstanceModel.SETTINGS.getName(), Map.of("repository_url", upstream.toString(), "branch", "main",
            "build_command", "mkdir -p out && cp index.html out/", "output_directory", "out",
            "build_image", TestImages.ALPINE));
        form.put(PutOnline.HTTPS.getName(), PutOnline.HTTPS_LATER);
        Long runId = OperationPipeline.invoke(OperationRequest.of(PutOnline.PUT_WORKLOAD_ONLINE,
            CmsPlacementSurface.ADMIN_ACTION).caller(operator).form(form)).run();
        assertThat(runId).as("step 3: a background run").isNotNull();
        OperationRun run = ended(runId, operator, "step 3");
        assertThat(run.status()).as("step 3: the run succeeded (%s)", run.outcome())
            .isEqualTo(OperationRunStatus.SUCCEEDED);
        Row site = Models.get(InstanceModel.class).find().where(InstanceModel.NAME.eq(NAME)).first();
        assertThat(site).as("step 3: the static site exists").isNotNull();
        instanceId = site.get(InstanceModel.ID);
        assertThat(String.valueOf((Object) site.get(InstanceModel.KIND))).as("step 3: of its kind").isEqualTo(kind);

        // 4. The serving release runs the built output: the proxy serves the page, its health answers, and the
        //    repository's hidden files are never served.
        String first = git(upstream, "rev-parse", "HEAD").trim();
        Row serving = servingAt(instanceId, first, "step 4");
        String firstImage = String.valueOf(ApplicationReleases.storedSettings(serving).get("image"));
        assertThat(firstImage).as("step 4: the release runs a digest-pinned build").startsWith("sha256:");
        assertThat(String.valueOf(RawValues.map(docker.inspectImage(firstImage).get("Config")).get("User")))
            .as("step 4: the built image serves as an unprivileged user, never root").isEqualTo("65534:65534");
        int port = ProxyTestSupport.httpPort(proxy);
        assertThat(ProxyTestSupport.rawRequest(port, HOSTNAME, "/"))
            .as("step 4: the proxy serves the built page").contains("200").contains(MARK + "-one");
        assertThat(ProxyTestSupport.rawRequest(port, HOSTNAME, "/.git/config"))
            .as("step 4: the checkout's hidden files are not served").doesNotContain("[core]");

        // 5. A push: the scheduled check reads the new head and deploys it.
        Files.writeString(upstream.resolve("index.html"), "<h1>" + MARK + "-two</h1>\n");
        git(upstream, "commit", "-q", "-am", "second page");
        String second = git(upstream, "rev-parse", "HEAD").trim();
        Row record = Models.get(InstanceModel.class).findById(instanceId);
        GitSourcePolls.Check check = GitSourcePolls.check(record, SiteSources.settingsOfInstance(record),
            Now.instant());
        assertThat(check.finding()).as("step 5: the check deploys the pushed head (%s)", check.failure())
            .isEqualTo(GitSourcePolls.Finding.DEPLOYING);

        // 6. The redeploy serves the new content.
        Row redeployed = servingAt(instanceId, second, "step 6");
        String secondImage = String.valueOf(ApplicationReleases.storedSettings(redeployed).get("image"));
        assertThat(secondImage).as("step 6: a new build").isNotEqualTo(firstImage);
        assertThat(ProxyTestSupport.rawRequest(port, HOSTNAME, "/"))
            .as("step 6: the proxy serves the pushed page").contains("200").contains(MARK + "-two");

        // 7. Delete: the releases, their containers and their built images go; the address serves nothing more.
        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, redeployed.get(InstanceModel.ID));
        new InstanceService().destroy(instanceId);
        assertThat(containerExists(docker, handle)).as("step 7: the serving container is gone").isFalse();
        assertThat(ApplicationReleases.ownedServing(instanceId)).as("step 7: nothing serves").isNull();
        for (String image : List.of(firstImage, secondImage)) {
            assertThat(imagePresent(docker, image)).as("step 7: built image %s is reclaimed", image).isFalse();
        }
        assertThat(ProxyTestSupport.rawRequest(port, HOSTNAME, "/"))
            .as("step 7: the address no longer serves the site").doesNotContain(MARK);
    }

    /**
     * A site that serves a folder, with an address, an access list, a protected path and a redirect, is deployed from
     * a repository from its own page: a repository that does not build leaves it on its folder with no app behind;
     * one that builds is released before the site is pointed at it, on the same address with the same protection;
     * every push then deploys; and a tenant who manages the site is neither offered it nor let through.
     */
    @Test
    void aFolderSiteIsDeployedFromARepositoryWithoutGoingDark() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET), "Docker socket not present");
        LiveLane.require(LiveLane.Need.NETNS, netns != null, "no private netns: the sandbox refuses to build");
        LiveLane.requireImage(new DockerClient(), TestImages.ALPINE);
        RateLimitMiddleware.limiter().clear();
        AccessContext operator = TenantConduits.operator();
        int port = ProxyTestSupport.httpPort(proxy);

        // 1. A Static files site serves its folder at its address, behind an access list, with a protected path and a
        //    redirect.
        Path folder = Files.createTempDirectory("hohenheim-folder-site");
        Files.writeString(folder.resolve("index.html"), "<h1>" + MARK + "-folder</h1>\n");
        Row created = ProxyTestSupport.setupSite(StaticUpstreamKind.ID.toString(), FOLDER_SITE, FOLDER_SITE,
            new LinkedHashMap<>(Map.of(StaticUpstreamKind.ROOT_PATH.getName(), folder.toString())));
        ProxyTestSupport.addDomain(created, FOLDER_HOSTNAME, "exact", null, false);
        int siteId = created.get(SiteModel.ID);
        Row list = Models.get(AccessListModel.class).createEmptyRow();
        list.set(AccessListModel.NAME, FOLDER_SITE + " list");
        list.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ANY);
        Models.get(AccessListModel.class).save(list);
        Row listed = site(siteId);
        listed.set(SiteModel.ACCESS_LIST_ID, list.get(AccessListModel.ID));
        Models.get(SiteModel.class).save(listed);
        Map<String, Object> protect = new LinkedHashMap<>();
        protect.put(ProtectPath.PATH.getName(), "/members");
        protect.put(ProtectPath.METHOD.getName(), ProtectPath.METHOD_NETWORK);
        protect.put(ProtectPath.NETWORKS.getName(), List.of("198.51.100.0/24"));
        OperationPipeline.invoke(OperationRequest.of(ProtectPath.OPERATION, CmsPlacementSurface.ADMIN_ACTION)
            .caller(operator).subjects(List.of(site(siteId))).form(protect));
        Row redirect = Models.get(SiteRedirectModel.class).createEmptyRow();
        redirect.set(SiteRedirectModel.SITE_ID, siteId);
        redirect.set(SiteRedirectModel.FROM_PATH, "/old");
        redirect.set(SiteRedirectModel.MATCH, SiteRedirectModel.MATCH_EXACT);
        redirect.set(SiteRedirectModel.TARGET, "/");
        Models.get(SiteRedirectModel.class).save(redirect);
        assertThat(ProxyTestSupport.rawRequest(port, FOLDER_HOSTNAME, "/"))
            .as("step 1: the site serves its folder").contains("200").contains(MARK + "-folder");
        Map<String, Object> kept = keptOf(siteId);
        List<String> guarded = List.of(statusLine(port, "/members"), statusLine(port, "/old"));
        assertThat(guarded).as("step 1: the protected path turns this client away and the redirect answers")
            .noneMatch(line -> line.contains(" 200 "));

        // 2. The operator is offered Deploy from a repository on the site; a tenant who manages it is not, and its
        //    invoke is refused before any run.
        assertThat(OperationPipeline.offer(PutOnline.DEPLOY_FROM_REPOSITORY, operator, site(siteId)))
            .as("step 2: offered to the operator on a Static files site")
            .isInstanceOf(OperationPipeline.Offer.Available.class);
        UUID tenantId = ApiSupport.user("folder-tenant-" + MARK + "@hohenheim.local", "Folder Tenant");
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, siteId, HohenheimCapabilities.MANAGE,
            true);
        AccessContext tenant = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(tenantId, "Folder Tenant")));
        assertThat(OperationPipeline.offer(PutOnline.DEPLOY_FROM_REPOSITORY, tenant, site(siteId)))
            .as("step 2: not offered to a tenant who manages the site")
            .isNotInstanceOf(OperationPipeline.Offer.Available.class);
        assertThat(catchThrowableOfType(DomainRefusal.class, () -> OperationPipeline.invoke(OperationRequest.of(
                PutOnline.DEPLOY_FROM_REPOSITORY, CmsPlacementSurface.ADMIN_ACTION).caller(tenant)
                .subjects(List.of(site(siteId))).form(repositoryForm("mkdir -p out && cp index.html out/")))))
            .as("step 2: and the tenant's invoke is refused").isNotNull()
            .extracting(DomainRefusal::reason).isEqualTo(ZenitRefusalReason.FORBIDDEN);

        // 3. A repository that does not build: the run fails with the build's reason, the site still serves its
        //    folder, and no app is left behind.
        OperationRun failed = ended(deploy(siteId, repositoryForm("exit 3"), operator), operator, "step 3");
        assertThat(failed.status()).as("step 3: the run did not succeed").isNotEqualTo(OperationRunStatus.SUCCEEDED);
        assertThat(failed.outcome()).as("step 3: and says why").isNotNull();
        assertThat(failed.outcome().key()).as("step 3: the build's own reason (%s)", failed.outcome())
            .isEqualTo("application_image_build_failed");
        assertThat((String) site(siteId).get(SiteModel.UPSTREAM_KIND)).as("step 3: the site still serves files")
            .isEqualTo(StaticUpstreamKind.ID.toString());
        assertThat(Models.get(InstanceModel.class).find().where(InstanceModel.NAME.eq(FOLDER_SITE)).first())
            .as("step 3: no app is left behind").isNull();
        assertThat(ProxyTestSupport.rawRequest(port, FOLDER_HOSTNAME, "/"))
            .as("step 3: the folder still answers").contains("200").contains(MARK + "-folder");

        // 4. A repository that builds: the run ends well, the app takes the site's name, and its first release
        //    serves the built page on the site's own address.
        OperationRun run = ended(deploy(siteId, repositoryForm("mkdir -p out && cp index.html out/"), operator),
            operator, "step 4");
        assertThat(run.status()).as("step 4: the run succeeded (%s)", run.outcome())
            .isEqualTo(OperationRunStatus.SUCCEEDED);
        Row app = Models.get(InstanceModel.class).find().where(InstanceModel.NAME.eq(FOLDER_SITE)).first();
        assertThat(app).as("step 4: the app exists under the site's name").isNotNull();
        folderAppId = app.get(InstanceModel.ID);
        assertThat((String) app.get(InstanceModel.KIND)).as("step 4: a Static site").isEqualTo("hohenheim:static_site");
        Row switched = site(siteId);
        assertThat(List.of(String.valueOf((Object) switched.get(SiteModel.UPSTREAM_KIND)),
                String.valueOf((Object) switched.get(SiteModel.INSTANCE_ID))))
            .as("step 4: the site serves the app now")
            .isEqualTo(List.of(InstanceUpstreamKind.ID.toString(), String.valueOf(folderAppId)));
        servingAt(folderAppId, git(folderUpstream, "rev-parse", "HEAD").trim(), "step 4");
        assertThat(ProxyTestSupport.rawRequest(port, FOLDER_HOSTNAME, "/"))
            .as("step 4: the same address serves the built page").contains("200").contains(MARK + "-repo-one");
        assertThat(List.of(statusLine(port, "/members"), statusLine(port, "/old")))
            .as("step 4: the protected path and the redirect answer as they did on the folder").isEqualTo(guarded);

        // 5. Everything else the site had is as it was, the folder is still on disk, and the site's history names it.
        assertThat(keptOf(siteId)).as("step 5: name, addresses, certificates, list, provider, paths, redirects")
            .isEqualTo(kept);
        assertThat(Files.exists(folder.resolve("index.html"))).as("step 5: the folder is still there").isTrue();
        List<RecordedChanges.Recorded> deployed = RecordedChanges.withFact(Models.get(SiteModel.class), siteId,
            HohenheimActivityAction.REPOSITORY_DEPLOYED);
        assertThat(deployed).as("step 5: one history line").hasSize(1);
        assertThat(deployed.getFirst().fact(HohenheimActivityAction.REPOSITORY_DEPLOYED)
                .get(HohenheimActivityAction.PATH_KEY))
            .as("step 5: naming the folder it served before").isEqualTo(folder.toString());

        // 6. The app is listed with its Deploys tab, where the run's onward link leads.
        assertThat(adminGet(CmsRoutes.subpage(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCES, folderAppId,
                HohenheimSlugs.Tab.DEPLOYMENTS).toUrl()).statusCode())
            .as("step 6: the app's Deploys tab opens").isEqualTo(200);

        // 7. A push: the scheduled check deploys it, and the site's address serves the new page.
        Files.writeString(folderUpstream.resolve("index.html"), "<h1>" + MARK + "-repo-two</h1>\n");
        git(folderUpstream, "commit", "-q", "-am", "second page");
        String pushed = git(folderUpstream, "rev-parse", "HEAD").trim();
        Row record = Models.get(InstanceModel.class).findById(folderAppId);
        GitSourcePolls.Check check = GitSourcePolls.check(record, SiteSources.settingsOfInstance(record),
            Now.instant());
        assertThat(check.finding()).as("step 7: the check deploys the pushed head (%s)", check.failure())
            .isEqualTo(GitSourcePolls.Finding.DEPLOYING);
        servingAt(folderAppId, pushed, "step 7");
        assertThat(ProxyTestSupport.rawRequest(port, FOLDER_HOSTNAME, "/"))
            .as("step 7: the address serves the pushed page").contains("200").contains(MARK + "-repo-two");
    }

    /** @return the repository deploy's form: the local host, the fixture repository and this build command */
    private static Map<String, Object> repositoryForm(String buildCommand) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put(InstanceModel.KIND.getName(), "hohenheim:static_site");
        form.put("serverId", ServerModel.localServerId());
        form.put(InstanceModel.SETTINGS.getName(), Map.of("repository_url", folderUpstream.toString(), "branch",
            "main", "build_command", buildCommand, "output_directory", "out", "build_image", TestImages.ALPINE));
        return form;
    }

    /** @return the run the operator's repository deploy of the site started */
    private static long deploy(int siteId, Map<String, Object> form, AccessContext operator) {
        Long runId = OperationPipeline.invoke(OperationRequest.of(PutOnline.DEPLOY_FROM_REPOSITORY,
            CmsPlacementSurface.ADMIN_ACTION).caller(operator).subjects(List.of(site(siteId))).form(form)).run();
        assertThat(runId).as("a background run").isNotNull();
        return runId;
    }

    private static Row site(int siteId) {
        return Models.get(SiteModel.class).findById(siteId);
    }

    /** @return what a repository deploy keeps of a site: everything but its upstream */
    private static Map<String, Object> keptOf(int siteId) {
        Row site = site(siteId);
        List<String> domains = new ArrayList<>();
        for (Row domain : Models.get(SiteDomainModel.class).find().where(SiteDomainModel.SITE_ID.eq(siteId)).all()) {
            domains.add(domain.get(SiteDomainModel.HOSTNAME) + " certificate "
                + domain.get(SiteDomainModel.CERTIFICATE_ID) + " force " + domain.get(SiteDomainModel.FORCE_SSL));
        }
        List<String> paths = new ArrayList<>();
        for (Row path : Models.get(ProtectedPathModel.class).find().where(ProtectedPathModel.SITE_ID.eq(siteId))
                .all()) {
            paths.add(path.get(ProtectedPathModel.PATH) + " list " + path.get(ProtectedPathModel.ACCESS_LIST_ID));
        }
        List<String> redirects = new ArrayList<>();
        for (Row redirect : Models.get(SiteRedirectModel.class).findBySiteId(siteId)) {
            redirects.add(redirect.get(SiteRedirectModel.FROM_PATH) + " to " + redirect.get(SiteRedirectModel.TARGET));
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        kept.put("name", site.get(SiteModel.NAME));
        kept.put("enabled", site.get(SiteModel.ENABLED));
        kept.put("access_list", site.get(SiteModel.ACCESS_LIST_ID));
        kept.put("auth_provider", site.get(SiteModel.AUTH_PROVIDER_ID));
        kept.put("domains", domains);
        kept.put("paths", paths);
        kept.put("redirects", redirects);
        return kept;
    }

    /** Polls until the run has ended; a release builds on the host, so it may take minutes. */
    private static OperationRun ended(long runId, AccessContext viewer, String step) {
        return Poll.value(step + ": run " + runId + " ends", Duration.ofMinutes(5), Duration.ofMillis(200), () -> {
            OperationRun seen = OperationRuns.of(runId, viewer);
            return seen != null && seen.status() != OperationRunStatus.RUNNING ? seen : null;
        });
    }

    /** @return the first line of the folder site's answer to {@code path} */
    private static String statusLine(int port, String path) throws Exception {
        return ProxyTestSupport.rawRequest(port, FOLDER_HOSTNAME, path).lines().findFirst().orElse("");
    }

    /** @return the app's serving release once it runs {@code commit}; a failed build fails the step with its log */
    private static Row servingAt(int appId, String commit, String step) {
        return Poll.value(step + ": a release of " + commit + " serves", Duration.ofMinutes(5), Duration.ofMillis(500),
            () -> {
                Row serving = ApplicationReleases.ownedServing(appId);
                if (serving != null && commit.equals(ApplicationReleases.storedSettings(serving).get("commit_sha"))) {
                    return serving;
                }
                Row build = Models.get(BuildOperationModel.class).find()
                    .where(BuildOperationModel.FOR_ID.eq(appId))
                    .where(BuildOperationModel.SOURCE_REF.eq(commit))
                    .orderBy(BuildOperationModel.ID, SortOrder.DESC).first();
                Object status = build == null ? null : build.get(BuildOperationModel.STATUS);
                if (status != null && !BuildOperationModel.LIFECYCLE.is(status, OperationStatus.RUNNING)
                        && !BuildOperationModel.LIFECYCLE.is(status, OperationStatus.SUCCEEDED)) {
                    throw new AssertionError(step + ": the build of " + commit + " ended " + status + ":\n"
                        + build.get(BuildOperationModel.LOG));
                }
                return null;
            });
    }

    private static boolean containerExists(DockerClient docker, String handle) {
        try {
            docker.inspectContainer(handle);
            return true;
        } catch (IOException gone) {
            return false;
        }
    }

    private static boolean imagePresent(DockerClient docker, String reference) {
        try {
            docker.inspectImage(reference);
            return true;
        } catch (IOException gone) {
            return false;
        }
    }

    /** @return a fresh repository on branch main whose one commit has an index page saying {@code text} */
    private static Path repository(String prefix, String text) throws Exception {
        Path directory = Files.createTempDirectory(prefix);
        git(directory, "init", "-q", "-b", "main");
        git(directory, "config", "user.email", "test@example.com");
        git(directory, "config", "user.name", "Test");
        Files.writeString(directory.resolve("index.html"), "<h1>" + text + "</h1>\n");
        git(directory, "add", ".");
        git(directory, "commit", "-q", "-m", "first page");
        return directory;
    }

    private static String git(Path directory, String... args) throws Exception {
        ProcessOutcome outcome = Subprocess.of("git", args).directory(directory).timeout(Duration.ofSeconds(30))
            .mergeStderr().run();
        String output = outcome.stdout().text();
        if (!outcome.succeeded()) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + outcome.describe());
        }
        return output;
    }
}
