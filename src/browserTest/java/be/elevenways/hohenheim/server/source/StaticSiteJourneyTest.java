package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A static site from git walked from "Put something online" to served files and back: the wizard, a build of a local
 * repository on the workload host, the proxy serving its output, a push the scheduled check deploys, the new content
 * served, then the delete that takes its releases, containers and built images with it.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@Tag("slow") // live lane: a real daemon, a sandboxed build, a private netns and the proxy; runs via `zenit-dev test --all`
class StaticSiteJourneyTest extends HohenheimTestBase {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String NAME = "static-journey";
    private static final String HOSTNAME = "static-journey.test";

    /** Unique per run, so a stale checkout or image of an earlier run can never answer for this one. */
    private static final String MARK = "static-" + System.nanoTime();

    private static PrivateNetns netns;
    private static ProxyServer proxy;
    private static Path upstream;
    private static Integer instanceId;

    @BeforeAll
    static void boot() throws Exception {
        netns = PrivateNetns.installEnforcing();
        HostFixtures.admitLocal();
        HostFixtures.makeLocalPlaceable(8192);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, false);
        upstream = Files.createTempDirectory("hohenheim-static-upstream");
        git("init", "-q", "-b", "main");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        Files.writeString(upstream.resolve("index.html"), "<h1>" + MARK + "-one</h1>\n");
        git("add", ".");
        git("commit", "-q", "-m", "first page");
        proxy = ProxyTestSupport.startProxy();
        ServerMain.adoptProxyServer(proxy);
        // As ServerMain's boot does: the website the run writes reaches the routing table through the model hooks.
        ProxyReloadHooks.install();
    }

    @AfterAll
    static void tearDown() {
        if (instanceId != null) {
            try {
                ApplicationReleases.destroyFor(instanceId);
            } catch (RuntimeException e) {
                System.err.println("teardown: release reclaim failed - " + e.getMessage());
            }
        }
        for (Row site : Models.get(SiteModel.class).find().where(SiteModel.NAME.eq(NAME)).all()) {
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
        OperationRun run = Poll.value("step 3: run " + runId + " ends", Duration.ofMinutes(5), Duration.ofMillis(200),
            () -> {
                OperationRun seen = OperationRuns.of(runId, operator);
                return seen != null && seen.status() != OperationRunStatus.RUNNING ? seen : null;
            });
        assertThat(run.status()).as("step 3: the run succeeded (%s)", run.outcome())
            .isEqualTo(OperationRunStatus.SUCCEEDED);
        Row site = Models.get(InstanceModel.class).find().where(InstanceModel.NAME.eq(NAME)).first();
        assertThat(site).as("step 3: the static site exists").isNotNull();
        instanceId = site.get(InstanceModel.ID);
        assertThat(String.valueOf((Object) site.get(InstanceModel.KIND))).as("step 3: of its kind").isEqualTo(kind);

        // 4. The serving release runs the built output: the proxy serves the page, its health answers, and the
        //    repository's hidden files are never served.
        String first = git("rev-parse", "HEAD").trim();
        Row serving = servingAt(first, "step 4");
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
        git("commit", "-q", "-am", "second page");
        String second = git("rev-parse", "HEAD").trim();
        Row record = Models.get(InstanceModel.class).findById(instanceId);
        GitSourcePolls.Check check = GitSourcePolls.check(record, SiteSources.settingsOfInstance(record),
            Now.instant());
        assertThat(check.finding()).as("step 5: the check deploys the pushed head (%s)", check.failure())
            .isEqualTo(GitSourcePolls.Finding.DEPLOYING);

        // 6. The redeploy serves the new content.
        Row redeployed = servingAt(second, "step 6");
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

    /** @return the serving release once it runs {@code commit}; a failed build of it fails the step with its log */
    private static Row servingAt(String commit, String step) {
        return Poll.value(step + ": a release of " + commit + " serves", Duration.ofMinutes(5), Duration.ofMillis(500),
            () -> {
                Row serving = ApplicationReleases.ownedServing(instanceId);
                if (serving != null && commit.equals(ApplicationReleases.storedSettings(serving).get("commit_sha"))) {
                    return serving;
                }
                Row build = Models.get(BuildOperationModel.class).find()
                    .where(BuildOperationModel.FOR_ID.eq(instanceId))
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

    private static String git(String... args) throws Exception {
        ProcessOutcome outcome = Subprocess.of("git", args).directory(upstream).timeout(Duration.ofSeconds(30))
            .mergeStderr().run();
        String output = outcome.stdout().text();
        if (!outcome.succeeded()) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + outcome.describe());
        }
        return output;
    }
}
