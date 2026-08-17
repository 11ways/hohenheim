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
import java.time.Instant;
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
        sourceSettings.put("build_command",
            "printf 'operator-safe build output\\n' # build-command-secret-flow");
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
        return postAction(path, "");
    }

    private HttpResponse<String> postAction(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** The initial deploy record, the Deployments tab it renders (gating, table, log, webhook card) and the failure icon. */
    @Test
    @Order(1)
    void initialDeployAndDeploymentsTab() throws Exception {
        await("initial deploy", () -> hasFinished(1));

        Row deploy = deployments().get(0);
        assertThat((String) deploy.get(DeploymentModel.STATUS)).isEqualTo(DeploymentModel.STATUS_SUCCESS);
        assertThat((String) deploy.get(DeploymentModel.REASON)).isEqualTo("initial");
        assertThat((String) deploy.get(DeploymentModel.COMMIT_SHA)).isEqualTo(firstCommit);
        assertThat((String) deploy.get(DeploymentModel.SLOT)).isIn("a", "b");
        assertThat((String) deploy.get(DeploymentModel.LOG))
            .contains("Cloning repository")
            .contains("Build started")
            .contains("operator-safe build output")
            .contains("Build succeeded")
            .doesNotContain("build-command-secret-flow");
        assertThat((Integer) deploy.get(DeploymentModel.DURATION_MS)).isNotNull();
        assertThat(gitHandler().getCurrentCommit()).isEqualTo(firstCommit);

        var deployPage = get("/admin/sites/" + siteId + "/page/deployments");
        assertThat(deployPage.statusCode()).isEqualTo(200);
        assertThat(deployPage.body()).contains("Deploy now");
        assertThat(deployPage.body()).contains(firstCommit.substring(0, 8));

        // The action forms carry the framework _return target of THIS page, so
        // their handlers redirect back to the panel that rendered it.
        assertThat(deployPage.body())
            .contains("name=\"_return\"")
            .contains("value=\"/admin/sites/" + siteId + "/page/deployments\"")
            .doesNotContain("name=\"panel\"");

        // The accessible table leads with the Started column.
        assertThat(deployPage.body()).contains("<pl-table");
        int head = deployPage.body().indexOf("<pl-table-header");
        assertThat(head).isPositive();
        String headerRow = deployPage.body().substring(head, deployPage.body().indexOf("</pl-table-row>", head));
        assertThat(headerRow.indexOf("Started"))
            .as("Started is the first column")
            .isLessThan(headerRow.indexOf("Status"));
        // The successful initial deploy captured a build log, so its detail
        // row + collapsible log render (no error line: nothing failed).
        assertThat(deployPage.body()).contains("hh-deploy-detail");
        assertThat(deployPage.body()).contains("hh-deploy-log");
        assertThat(deployPage.body()).contains("Build log");
        assertThat(deployPage.body()).contains("Build started").contains("Build succeeded");
        assertThat(deployPage.body()).doesNotContain("build-command-secret-flow");
        // This site was seeded without a webhook secret: no card yet.
        assertThat(deployPage.body()).doesNotContain("Push webhook");

        var blocked = get("/admin/sites/" + plainSiteId + "/page/deployments");
        assertThat(blocked.statusCode()).isEqualTo(404);

        // Mint a webhook secret the way SiteResource.normalizeSource does on save.
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.find().where(SiteModel.ID.eq(siteId)).first();
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = new LinkedHashMap<>(
            (Map<String, Object>) site.get(SiteModel.SOURCE_SETTINGS));
        settings.put("webhook_secret", "whsec-test-123");
        settings.put("auto_deploy", true);
        site.set(SiteModel.SOURCE_SETTINGS, settings);
        siteModel.save(site);

        var withSecret = get("/admin/sites/" + siteId + "/page/deployments");
        assertThat(withSecret.statusCode()).isEqualTo(200);
        assertThat(withSecret.body()).contains("Push webhook");
        assertThat(withSecret.body()).contains("://git-flow.test/api/webhooks/git/git-flow-site");
        assertThat(withSecret.body()).contains("whsec-test-123");
        assertThat(withSecret.body()).doesNotContain("Auto-deploy is disabled");

        // A failed deployment renders the supported warning icon, not a missing one.
        DeploymentModel model = Models.get(DeploymentModel.class);
        Row failed = model.createEmptyRow();
        failed.set(DeploymentModel.SITE_ID, siteId);
        failed.set(DeploymentModel.STATUS, DeploymentModel.STATUS_FAILED);
        failed.set(DeploymentModel.REASON, "test");
        failed.set(DeploymentModel.ERROR, "Synthetic deployment failure");
        failed.set(DeploymentModel.STARTED_AT, Instant.now());
        model.save(failed);

        try {
            navigateToApp("/admin/sites/" + siteId + "/page/deployments");
            waitForHydration();
            var icon = page.locator(".hh-deploy-error pl-icon[name='triangle-exclamation']").first();
            assertThat(icon.count()).isEqualTo(1);
            assertThat(icon.locator("svg[data-pl-icon-missing]").count()).isZero();
            assertThat(page.locator(".hh-deploy-error pl-icon[name='circle-exclamation']").count()).isZero();
        } finally {
            model.delete(failed);
        }
    }

    /** Manual deploy of a new commit, rollback to the previous slot, api-key automation and the return-target contract. */
    @Test
    @Order(2)
    void deployRollbackAndAutomationJourney() throws Exception {
        Files.writeString(upstreamRepo.resolve("index.html"), "v2");
        gitIn(upstreamRepo, "commit", "-qam", "v2");
        String secondCommit = gitIn(upstreamRepo, "rev-parse", "HEAD").trim();

        var response = postAction("/sites/" + siteId + "/deploy");
        assertThat(response.statusCode()).isIn(302, 303);

        await("manual deploy", () -> hasFinished(2));
        Row deploy = deployments().get(0);
        assertThat((String) deploy.get(DeploymentModel.STATUS)).isEqualTo(DeploymentModel.STATUS_SUCCESS);
        assertThat((String) deploy.get(DeploymentModel.REASON)).isEqualTo("manual");
        assertThat((String) deploy.get(DeploymentModel.COMMIT_SHA)).isEqualTo(secondCommit);
        assertThat(gitHandler().getCurrentCommit()).isEqualTo(secondCommit);

        // Rollback re-activates the previous slot (the first commit). Driven through the
        // REAL page: the form declares use:CmsConfirm.destructive, so the click must open
        // the shell dialog, cancel must NOT roll back, and confirm must.
        // AIDEV-NOTE: counterfactual (pre-conversion): with the dead data-confirm
        // attribute the first click posted immediately -- the dialog assertion fails and
        // the cancel branch finds a third deployment already running.
        assertThat(gitHandler().hasPreviousSlot()).isTrue();

        navigateToApp("/admin/sites/" + siteId + "/page/deployments");
        waitForHydration();
        String rollbackButton = "form[action$='/rollback'] pl-button";
        waitForSelector(rollbackButton);

        click(rollbackButton);
        assertIsVisible(".pl-alertdialog-modal[data-open]");
        click("[data-cms-confirm-cancel]");
        assertIsNotVisible(".pl-alertdialog-modal");
        page.waitForTimeout(400);
        assertThat(deployments()).as("cancel must not roll back").hasSize(2);

        click(rollbackButton);
        assertIsVisible(".pl-alertdialog-modal[data-open]");
        click("[data-cms-confirm-ok]");

        await("rollback", () -> hasFinished(3));
        deploy = deployments().get(0);
        assertThat((String) deploy.get(DeploymentModel.STATUS)).isEqualTo(DeploymentModel.STATUS_SUCCESS);
        assertThat((String) deploy.get(DeploymentModel.REASON)).isEqualTo(GitSiteRequestHandler.REASON_ROLLBACK);
        assertThat((String) deploy.get(DeploymentModel.COMMIT_SHA)).isEqualTo(firstCommit);
        assertThat(gitHandler().getCurrentCommit()).isEqualTo(firstCommit);

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
        var apiDeploy = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + "/api/sites/" + siteId + "/deploy"))
            .header("Authorization", "Bearer " + key.plaintext())
            .POST(HttpRequest.BodyPublishers.ofString(""))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(apiDeploy.statusCode()).isEqualTo(200);
        assertThat(apiDeploy.body()).contains("queued");

        await("api deploy", () -> hasFinished(before + 1));
        assertThat((String) deployments().get(0).get(DeploymentModel.REASON)).isEqualTo("api");

        // deploy/cancel is a no-op while nothing deploys, so this only
        // exercises the redirect contract of the six subpage handlers.
        String manageTarget = "/manage/sites/" + siteId + "/page/deployments";
        var manageReturn = postAction("/sites/" + siteId + "/deploy/cancel",
            "_return=" + java.net.URLEncoder.encode(manageTarget, java.nio.charset.StandardCharsets.UTF_8));
        assertThat(manageReturn.statusCode()).isIn(302, 303);
        assertThat(manageReturn.headers().firstValue("Location")).hasValue(manageTarget);

        var forged = postAction("/sites/" + siteId + "/deploy/cancel",
            "_return=" + java.net.URLEncoder.encode("https://evil.example/phish", java.nio.charset.StandardCharsets.UTF_8));
        assertThat(forged.statusCode()).isIn(302, 303);
        assertThat(forged.headers().firstValue("Location"))
            .hasValue("/admin/sites/" + siteId + "/page/deployments");

        // The process handlers share the return mechanism, and a static site has no
        // managed handler -- so the action cannot run. It must SAY SO on the page it
        // returns to: this used to be a plain reload, which is indistinguishable from
        // "started" to the operator who clicked the button.
        String processTarget = "/manage/sites/" + siteId + "/page/processes";
        var processReturn = postAction("/sites/" + siteId + "/processes/start",
            "_return=" + java.net.URLEncoder.encode(processTarget, java.nio.charset.StandardCharsets.UTF_8));
        assertThat(processReturn.statusCode()).isIn(302, 303);
        assertThat(processReturn.headers().firstValue("Location"))
            .as("a start that could not run returns to the submitted page")
            .get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
            .isEqualTo(processTarget);
        var startRefusal = popFlash();
        assertThat(startRefusal)
            .as("a start that could not run says WHY -- this used to be a silent reload")
            .isNotNull();
        assertThat(startRefusal.message().key())
            .as("the reason names the missing handler")
            .isEqualTo("no_handler");

        var forgedProcess = postAction("/sites/" + siteId + "/processes/start",
            "_return=" + java.net.URLEncoder.encode("//evil.example/phish", java.nio.charset.StandardCharsets.UTF_8));
        assertThat(forgedProcess.statusCode()).isIn(302, 303);
        assertThat(forgedProcess.headers().firstValue("Location"))
            .as("and a forged return target still falls back to the admin page")
            .get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
            .isEqualTo("/admin/sites/" + siteId + "/page/processes")
            .doesNotContain("evil.example");
        assertThat(popFlash())
            .as("the fallback still carries the reason")
            .isNotNull();
    }
}
