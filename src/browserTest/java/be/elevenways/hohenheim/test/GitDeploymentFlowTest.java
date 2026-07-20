package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DeploymentModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.source.GitSiteRequestHandler;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.*;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end deployment visibility: a git-sourced static site records its deploy
 * history (with captured log + commit), the Deployments tab serves and gates
 * per-record, and deploy-now / rollback drive real slot activations.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GitDeploymentFlowTest extends HohenheimTestBase {

    private static Path upstreamRepo;
    private static ProxyServer proxy;
    private static Integer siteId;
    private static Integer plainSiteId;
    private static String firstCommit;
    private static String secondCommit;

    @BeforeAll
    static void initRepoAndProxy() throws Exception {
        upstreamRepo = Files.createTempDirectory("hohenheim-git-upstream");
        gitIn(upstreamRepo, "init", "-q", "-b", "main");
        gitIn(upstreamRepo, "config", "user.email", "test@example.com");
        gitIn(upstreamRepo, "config", "user.name", "Test");
        Files.writeString(upstreamRepo.resolve("index.html"), "v1");
        gitIn(upstreamRepo, "add", ".");
        gitIn(upstreamRepo, "commit", "-q", "-m", "v1");
        firstCommit = gitIn(upstreamRepo, "rev-parse", "HEAD").trim();

        // A git-sourced static site plus a plain one (for the per-record 404).
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Git Flow Site");
        site.set(SiteModel.SLUG, "git-flow-site");
        site.set(SiteModel.SITE_TYPE, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "."));
        site.set(SiteModel.SOURCE, SiteModel.SOURCE_GIT);
        Map<String, Object> sourceSettings = new LinkedHashMap<>();
        sourceSettings.put("repository_url", upstreamRepo.toString());
        sourceSettings.put("branch", "main");
        sourceSettings.put("shallow_clone", false);
        site.set(SiteModel.SOURCE_SETTINGS, sourceSettings);
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);
        siteId = site.get(SiteModel.ID);

        Row plain = siteModel.createEmptyRow();
        plain.set(SiteModel.NAME, "Plain Flow Site");
        plain.set(SiteModel.SLUG, "plain-flow-site");
        plain.set(SiteModel.SITE_TYPE, "hohenheim:static");
        plain.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        plain.set(SiteModel.SOURCE, "local");
        plain.set(SiteModel.STATUS, "active");
        plain.set(SiteModel.ENABLED, true);
        siteModel.save(plain);
        plainSiteId = plain.get(SiteModel.ID);

        // Sites only enter the route table (and findHandlerBySiteId) via a domain.
        addDomain(siteId, "git-flow.test");
        addDomain(plainSiteId, "plain-flow.test");

        // The proxy constructs the git handler (kicking off the initial deploy)
        // and is adopted so the deploy endpoints can reach it.
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        proxy = new ProxyServer();
        proxy.start();
        ServerMain.adoptProxyServer(proxy);
    }

    @AfterAll
    static void stopProxy() {
        ServerMain.adoptProxyServer(null);
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
    }

    private static void addDomain(Integer forSiteId, String hostname) {
        var domainModel = Models.get(SiteDomainModel.class);
        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, forSiteId);
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, "exact");
        domainModel.save(domain);
    }

    private static String gitIn(Path repo, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
            .directory(repo.toFile())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + output);
        }
        return output;
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 240; i++) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    private static List<Row> deployments() {
        return Models.get(DeploymentModel.class).findBySiteId(siteId, 50);
    }

    private static boolean hasFinished(int count) {
        List<Row> rows = deployments();
        return rows.size() >= count
            && !DeploymentModel.STATUS_RUNNING.equals(rows.get(0).get(DeploymentModel.STATUS));
    }

    private static GitSiteRequestHandler gitHandler() {
        return (GitSiteRequestHandler) proxy.getDispatcher().findHandlerBySiteId(siteId);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postAction(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(""))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void initialDeployIsRecordedWithLogAndCommit() throws Exception {
        await("initial deploy", () -> hasFinished(1));

        Row deploy = deployments().get(0);
        assertThat((String) deploy.get(DeploymentModel.STATUS)).isEqualTo(DeploymentModel.STATUS_SUCCESS);
        assertThat((String) deploy.get(DeploymentModel.REASON)).isEqualTo("initial");
        assertThat((String) deploy.get(DeploymentModel.COMMIT_SHA)).isEqualTo(firstCommit);
        assertThat((String) deploy.get(DeploymentModel.SLOT)).isIn("a", "b");
        assertThat((String) deploy.get(DeploymentModel.LOG)).contains("Cloning repository");
        assertThat((Integer) deploy.get(DeploymentModel.DURATION_MS)).isNotNull();
        assertThat(gitHandler().getCurrentCommit()).isEqualTo(firstCommit);
    }

    @Test
    @Order(2)
    void deploymentsPageRendersForGitSitesAnd404sForOthers() throws Exception {
        var page = get("/admin/sites/" + siteId + "/page/deployments");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("Deploy now");
        assertThat(page.body()).contains(firstCommit.substring(0, 8));

        // The history table is striped and leads with the Started column.
        // (host attribute order is not deterministic, so match within the tag)
        assertThat(page.body()).containsPattern("<pl-table[^>]*\\bstriped\\b");
        int header = page.body().indexOf("<pl-table-header");
        assertThat(header).isPositive();
        String headerRow = page.body().substring(header, page.body().indexOf("</pl-table-header>", header));
        assertThat(headerRow.indexOf("Started"))
            .as("Started is the first column")
            .isLessThan(headerRow.indexOf("Status"));

        var blocked = get("/admin/sites/" + plainSiteId + "/page/deployments");
        assertThat(blocked.statusCode()).isEqualTo(404);
    }

    @Test
    @Order(2)
    void webhookCardShowsUrlAndSecretOnceMinted() throws Exception {
        // This site was seeded without a webhook secret: no card.
        var before = get("/admin/sites/" + siteId + "/page/deployments");
        assertThat(before.body()).doesNotContain("Push webhook");

        // Mint one the way SiteResource.normalizeSource does on save.
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.find().where(SiteModel.ID.eq(siteId)).first();
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = new LinkedHashMap<>(
            (Map<String, Object>) site.get(SiteModel.SOURCE_SETTINGS));
        settings.put("webhook_secret", "whsec-test-123");
        settings.put("auto_deploy", true);
        site.set(SiteModel.SOURCE_SETTINGS, settings);
        siteModel.save(site);

        var page = get("/admin/sites/" + siteId + "/page/deployments");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("Push webhook");
        assertThat(page.body()).contains("://git-flow.test/api/webhooks/git/git-flow-site");
        assertThat(page.body()).contains("whsec-test-123");
        assertThat(page.body()).doesNotContain("Auto-deploy is disabled");
    }

    @Test
    @Order(3)
    void manualDeployPicksUpNewCommit() throws Exception {
        Files.writeString(upstreamRepo.resolve("index.html"), "v2");
        gitIn(upstreamRepo, "commit", "-qam", "v2");
        secondCommit = gitIn(upstreamRepo, "rev-parse", "HEAD").trim();

        var response = postAction("/sites/" + siteId + "/deploy");
        assertThat(response.statusCode()).isIn(302, 303);

        await("manual deploy", () -> hasFinished(2));
        Row deploy = deployments().get(0);
        assertThat((String) deploy.get(DeploymentModel.STATUS)).isEqualTo(DeploymentModel.STATUS_SUCCESS);
        assertThat((String) deploy.get(DeploymentModel.REASON)).isEqualTo("manual");
        assertThat((String) deploy.get(DeploymentModel.COMMIT_SHA)).isEqualTo(secondCommit);
        assertThat(gitHandler().getCurrentCommit()).isEqualTo(secondCommit);
    }

    @Test
    @Order(5)
    void automationApiDeploysWithAnApiKeyOnly() throws Exception {
        // A session cookie must NOT be able to act on the csrf-exempt API route.
        var sessionDeploy = postAction("/api/sites/" + siteId + "/deploy");
        assertThat(sessionDeploy.statusCode()).isEqualTo(403);

        Row user = be.elevenways.zenit.auth.server.AuthModels.users().find()
            .where(be.elevenways.zenit.auth.model.UserModel.EMAIL.eq("test@hohenheim.local")).first();
        var key = be.elevenways.zenit.auth.server.ApiKeyService.create(
            user.get(be.elevenways.zenit.auth.model.UserModel.ID), "flow-test-key",
            List.of("hohenheim.*"), null);

        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

        // Bearer GET: the site list carries slug, health and git state.
        var list = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + "/api/sites"))
            .header("Authorization", "Bearer " + key.plaintext())
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("git-flow-site").contains("current_commit");

        // Bearer POST: the deploy queues and is attributed to the api origin.
        int before = deployments().size();
        var deploy = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + "/api/sites/" + siteId + "/deploy"))
            .header("Authorization", "Bearer " + key.plaintext())
            .POST(HttpRequest.BodyPublishers.ofString(""))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(deploy.statusCode()).isEqualTo(200);
        assertThat(deploy.body()).contains("queued");

        await("api deploy", () -> hasFinished(before + 1));
        assertThat((String) deployments().get(0).get(DeploymentModel.REASON)).isEqualTo("api");
    }

    @Test
    @Order(4)
    void rollbackActivatesThePreviousSlot() throws Exception {
        assertThat(gitHandler().hasPreviousSlot()).isTrue();

        var response = postAction("/sites/" + siteId + "/rollback");
        assertThat(response.statusCode()).isIn(302, 303);

        await("rollback", () -> hasFinished(3));
        Row deploy = deployments().get(0);
        assertThat((String) deploy.get(DeploymentModel.STATUS)).isEqualTo(DeploymentModel.STATUS_SUCCESS);
        assertThat((String) deploy.get(DeploymentModel.REASON)).isEqualTo(GitSiteRequestHandler.REASON_ROLLBACK);
        assertThat((String) deploy.get(DeploymentModel.COMMIT_SHA)).isEqualTo(firstCommit);
        assertThat(gitHandler().getCurrentCommit()).isEqualTo(firstCommit);
    }
}
