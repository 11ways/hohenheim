package be.elevenways.hohenheim.test.source;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.WebhookDeliveryModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.source.GitWebhookHandler;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.security.SecureTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The webhook front door: a delivery that is unsigned, wrongly signed, aimed at another
 * repository, or on another branch must be refused IDENTICALLY and must queue nothing.
 *
 * AIDEV-NOTE: this class lost its deploy-driven half on 2026-08-22 when the site-keyed git
 * checkout lane was deleted with {@code sites.source} (phase-0 design section 3). Brief 7
 * re-keyed the webhook onto the APPLICATION INSTANCE -- the URL's last segment is that
 * instance's id, never a site slug -- and the refusal half below moved with it. Still owed
 * back, and still unobservable here because nothing in this class ever completes a deploy:
 * replay-once, the GitLab/Gitea shared-token lanes, the no-ref lane and the webhook-driven
 * preview lifecycle. What remains asserts ABSENCE and therefore needs no pipeline at all.
 */
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class GitWebhookSecurityTest extends HohenheimTestBase {

    private static Path upstreamRepo;
    private static ProxyServer proxy;
    private static int proxyPort;
    private static Integer siteAId;
    private static Integer siteBId;
    private static Integer siteCId;

    /** The webhook URL names the APPLICATION instance the site exposes, not the site. */
    private static Integer appAId;
    private static Integer appBId;
    private static Integer appCId;

    private static final String SECRET_A = "hook-secret-a-777";
    private static final String SECRET_B = "hook-secret-b-888";
    private static final String SECRET_C = "hook-secret-c-999";

    @BeforeAll
    static void initSitesAndProxy() throws Exception {
        GitWebhookHandler.limiter().clear();
        upstreamRepo = Files.createTempDirectory("hohenheim-hook-upstream");
        gitIn(upstreamRepo, "init", "-q", "-b", "main");
        gitIn(upstreamRepo, "config", "user.email", "test@example.com");
        gitIn(upstreamRepo, "config", "user.name", "Test");
        Files.writeString(upstreamRepo.resolve("index.html"), "hook-v1");
        gitIn(upstreamRepo, "add", ".");
        gitIn(upstreamRepo, "commit", "-q", "-m", "v1");

        siteAId = makeGitSite("Hook Site A", "hook-a", SECRET_A, "acme/repo-a", "hook-a.test");
        appAId = applicationOf(siteAId);
        siteBId = makeGitSite("Hook Site B", "hook-b", SECRET_B, "acme/repo-b", "hook-b.test");
        appBId = applicationOf(siteBId);
        // Site C is the previews-OPTED-IN site. Its static type makes the preview engine
        // refuse by type the moment the background job starts, so the mapping is observed
        // through the stamped delivery action and nothing is ever built.
        siteCId = makeGitSite("Hook Site C", "hook-c", SECRET_C, "acme/repo-c", "hook-c.test");
        appCId = applicationOf(siteCId);
        enablePreviews(siteCId);

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        proxy = new ProxyServer();
        proxy.start();
        ServerMain.adoptProxyServer(proxy);
        proxyPort = ((InetSocketAddress) proxy.getHttpListenerInfo().getAddress()).getPort();

        // AIDEV-NOTE: no initial deploy is awaited any more. The site-keyed git checkout
        // lane was deleted with sites.source (phase-0 design section 3) -- a checkout lives
        // in the workspace volume or the build context of the instance a site exposes -- so
        // nothing here builds. What survives in this class is the half that asserts a
        // delivery deploys NOTHING and leaks nothing, which needs no pipeline at all.
    }

    @AfterAll
    static void stopProxy() {
        ServerMain.adoptProxyServer(null);
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void unsignedAndWrongSignedDeliveriesDeployNothingAndLeakNothing() throws Exception {
        String body = pushPayload("acme/repo-a", "refs/heads/main", "cafe1111");

        // 1. Unsigned delivery: refused, and NOTHING happened -- no delivery claim, no
        //    deployment. The refusal is the proof the endpoint's only authentication is
        //    the signature.
        String unsigned = post(hookUrl(appAId), body,
            "X-GitHub-Event: push", "X-GitHub-Delivery: " + UUID.randomUUID());
        assertThat(unsigned).as("step 1: unsigned refused").startsWith("HTTP/1.1 404");

        // 2. WRONG-signed (site B's real secret against site A's hook): identical
        //    refusal, still nothing. This is also the cross-site half of isolation: a
        //    party that legitimately holds B's secret gets nothing against A.
        String wrongSigned = post(hookUrl(appAId), body,
            "X-GitHub-Event: push", "X-GitHub-Delivery: " + UUID.randomUUID(),
            "X-Hub-Signature-256: sha256=" + SecureTokens.hmacSha256Hex(SECRET_B, body));
        assertThat(wrongSigned).as("step 2: wrong signature refused")
            .startsWith("HTTP/1.1 404");

        // 3. A URL naming no application refuses with the SAME status and body as a
        //    wrong signature on a real one -- the response leaks neither the existence
        //    of the application nor whether a secret is configured.
        String unknownSlug = post(GitWebhookHandler.PREFIX + "999000111", body,
            "X-GitHub-Event: push",
            "X-Hub-Signature-256: sha256=" + SecureTokens.hmacSha256Hex(SECRET_A, body));
        assertThat(statusAndBody(unknownSlug))
            .as("step 3: unknown site and wrong signature are indistinguishable")
            .isEqualTo(statusAndBody(wrongSigned))
            .isEqualTo(statusAndBody(unsigned));

        // 4. The counterfactual: no build, no deploy, no claimed delivery, anywhere.
        Thread.sleep(300);
        assertThat(deliveryRows())
            .as("step 4: refused deliveries claim no delivery id").isEmpty();
        assertThat(releaseOperationsOf(appAId))
            .as("step 4: refused deliveries deploy nothing").isEmpty();
        assertThat(releaseOperationsOf(appBId)).isEmpty();
    }

    /**
     * The replay ledger and the not-a-push guard, on a delivery that CLAIMS without
     * deploying.
     *
     * AIDEV-NOTE: the payload is deliberately an OFF-BRANCH push. The claim is taken
     * before the branch is even looked at, so this exercises the ledger end to end while
     * starting no checkout -- which is what lets a replay journey stay hermetic. Asserting
     * it on a deploying delivery would need a repository, a build and a daemon.
     */
    @Test
    @org.junit.jupiter.api.Order(2)
    void aReplayedDeliveryIsClaimedOnceAndAnEventWithoutARefIsNotAPush() throws Exception {

        String deliveryId = UUID.randomUUID().toString();
        String body = pushPayload("acme/repo-b", "refs/heads/feature-replay", "cafe2222");

        // 1. The first delivery is acted on and its id is claimed.
        String first = post(hookUrl(appBId), body, "X-GitHub-Event: push",
            "X-GitHub-Delivery: " + deliveryId,
            "X-Hub-Signature-256: sha256=" + SecureTokens.hmacSha256Hex(SECRET_B, body));
        assertThat(first).as("step 1: the first delivery is acted on")
            .startsWith("HTTP/1.1 200");
        assertThat(deliveryRowsOf(appBId, "gh:" + deliveryId))
            .as("step 1: and its id is claimed").hasSize(1);

        // 2. The SAME delivery id again -- a provider retry, which every provider does on
        //    a slow or lost response. It must be answered, not ACTED on a second time.
        String replay = post(hookUrl(appBId), body, "X-GitHub-Event: push",
            "X-GitHub-Delivery: " + deliveryId,
            "X-Hub-Signature-256: sha256=" + SecureTokens.hmacSha256Hex(SECRET_B, body));
        assertThat(replay).as("step 2: the replay is recognized as a duplicate")
            .startsWith("HTTP/1.1 200").contains("duplicate");
        assertThat(deliveryRowsOf(appBId, "gh:" + deliveryId))
            .as("step 2: and the ledger still holds exactly one claim for that id")
            .hasSize(1);

        // 3. An event carrying NO ref is not a push, whatever else it is: it must be
        //    ignored rather than fall through to a deploy of the default branch. Every
        //    unmodelled provider event (issues, stars, releases, review comments) lands
        //    here, and before the guard existed every one of them queued a deploy.
        String refless = "{\"repository\":{\"full_name\":\"acme/repo-b\"}}";
        String ignored = post(hookUrl(appBId), refless, "X-GitHub-Event: issues",
            "X-GitHub-Delivery: " + UUID.randomUUID(),
            "X-Hub-Signature-256: sha256=" + SecureTokens.hmacSha256Hex(SECRET_B, refless));
        assertThat(ignored).as("step 3: an event with no ref is not a push")
            .startsWith("HTTP/1.1 200").contains("not a push");

        // 4. The counterfactual for all three: nothing deployed.
        Thread.sleep(300);
        assertThat(releaseOperationsOf(appBId))
            .as("step 4: a replay and an unmodelled event deploy nothing").isEmpty();
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void aDeliveryForAnotherRepositoryIsRefusedEvenWithAValidSignature() throws Exception {
        // Site B's webhook, correctly signed with B's secret, but the payload names
        // repo-a: a webhook must authorize exactly the repository binding it was
        // registered for (secret reuse / provider misconfiguration must not cross).
        String body = pushPayload("acme/repo-a", "refs/heads/main", "cafe3333");
        String response = post(hookUrl(appBId), body,
            "X-GitHub-Event: push", "X-GitHub-Delivery: " + UUID.randomUUID(),
            "X-Hub-Signature-256: sha256=" + SecureTokens.hmacSha256Hex(SECRET_B, body));
        assertThat(response).as("the repository mismatch is refused")
            .startsWith("HTTP/1.1 422").contains("repository mismatch");
        Thread.sleep(300);
        assertThat(releaseOperationsOf(appBId))
            .as("the mismatched delivery deployed nothing on site B's application")
            .isEmpty();
        assertThat(releaseOperationsOf(appAId))
            .as("and certainly nothing on site A's application -- the repository the"
                + " payload named is A's, which is exactly the crossing being refused")
            .isEmpty();
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void aPushToAnotherBranchIsIgnored() throws Exception {
        int before = releaseOperationsOf(appBId).size();
        String body = pushPayload("acme/repo-b", "refs/heads/feature-x", "cafe4444");
        String response = post(hookUrl(appBId), body,
            "X-GitHub-Event: push", "X-GitHub-Delivery: " + UUID.randomUUID(),
            "X-Hub-Signature-256: sha256=" + SecureTokens.hmacSha256Hex(SECRET_B, body));
        assertThat(response).as("the off-branch push is acknowledged but ignored")
            .startsWith("HTTP/1.1 200").contains("branch");
        Thread.sleep(300);
        assertThat(releaseOperationsOf(appBId))
            .as("no deploy for a branch the application is not bound to").hasSize(before);
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    void pullRequestEventsAreIgnoredUntilPreviewsAreOptedIn() throws Exception {
        String body = "{\"action\":\"opened\",\"repository\":{\"full_name\":\"acme/repo-b\"},"
            + "\"pull_request\":{\"number\":7,\"head\":{\"ref\":\"feature-x\","
            + "\"sha\":\"cafe5555\"}}}";
        String response = post(hookUrl(appBId), body,
            "X-GitHub-Event: pull_request", "X-GitHub-Delivery: " + UUID.randomUUID(),
            "X-Hub-Signature-256: sha256=" + SecureTokens.hmacSha256Hex(SECRET_B, body));
        assertThat(response)
            .as("previews are an explicit opt-in; a PR event without it does nothing")
            .startsWith("HTTP/1.1 200").contains("previews disabled");
    }

    private static void setPreviewBranches(Integer siteId, List<String> patterns) {
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.findById(siteId);
        Map<String, Object> settings = new LinkedHashMap<>(
            TestSources.sourceSettingsOf(site));
        settings.put("preview_branches", patterns);
        TestSources.updateSourceSettings(site, settings);
        siteModel.save(site);
    }

    /** A live preview row as the deploy lane would leave one, without building anything. */
    private static Row seedPreviewRow(Integer applicationId, String ref) {
        var model = Models.get(PreviewDeploymentModel.class);
        Row preview = model.createEmptyRow();
        preview.set(PreviewDeploymentModel.APPLICATION_ID, applicationId);
        preview.set(PreviewDeploymentModel.REF, ref);
        preview.set(PreviewDeploymentModel.HOSTNAME,
            "hook-c--feature-login.preview.test");
        preview.set(PreviewDeploymentModel.STATUS, PreviewDeploymentModel.STATUS_RUNNING);
        preview.set(PreviewDeploymentModel.EXPIRES_AT,
            java.time.Instant.now().plusSeconds(3600));
        model.save(preview);
        return preview;
    }

    /** A push payload as providers send it when the ref was deleted. */
    private static String deletedPushPayload(String repository, String ref) {
        return "{\"ref\":\"" + ref + "\",\"deleted\":true,"
            + "\"after\":\"0000000000000000000000000000000000000000\","
            + "\"repository\":{\"full_name\":\"" + repository + "\"}}";
    }

    /**
     * Deliver one Gitea pull_request event under Gitea's own headers (vendor delivery id,
     * raw-hex signature) and report what the handler stamped.
     */
    private static String giteaPullRequest(String uuid, String action, String sha)
            throws Exception {
        String body = "{\"action\":\"" + action + "\",\"number\":11,"
            + "\"repository\":{\"full_name\":\"acme/repo-c\"},"
            + "\"pull_request\":{\"number\":11,\"head\":{\"ref\":\"feat-gt\","
            + "\"sha\":\"" + sha + "\"}}}";
        post(hookUrl(appCId), body,
            "X-Gitea-Event: pull_request", "X-Gitea-Delivery: " + uuid,
            "X-Gitea-Signature: " + SecureTokens.hmacSha256Hex(SECRET_C, body));
        return stampedAction("gt:" + uuid);
    }

    /**
     * Deliver one GitLab Merge Request Hook and report what the handler stamped.
     *
     * @param oldrev the source branch's previous head, or null for an update that
     *               carried no new commits
     */
    private static String mergeRequestAction(String action, String oldrev) throws Exception {
        String uuid = UUID.randomUUID().toString();
        String body = "{\"object_kind\":\"merge_request\","
            + "\"project\":{\"path_with_namespace\":\"acme/repo-c\"},"
            + "\"object_attributes\":{\"iid\":42,\"source_branch\":\"feature-gl\","
            + "\"target_branch\":\"main\",\"action\":\"" + action + "\","
            + (oldrev == null ? "" : "\"oldrev\":\"" + oldrev + "\",")
            + "\"last_commit\":{\"id\":\"cafe7777\"}}}";
        post(hookUrl(appCId), body,
            "X-Gitlab-Event: Merge Request Hook",
            "X-Gitlab-Event-UUID: " + uuid,
            "X-Gitlab-Token: " + SECRET_C);
        return stampedAction("gl:" + uuid);
    }

    /** The delivery's recorded outcome; the claim is written before the handler acts. */
    private static String stampedAction(String deliveryKey) throws Exception {
        await("delivery " + deliveryKey + " is stamped", () -> {
            List<Row> rows = deliveryRowsOf(appCId, deliveryKey);
            return !rows.isEmpty() && rows.get(0).get(WebhookDeliveryModel.ACTION) != null;
        });
        return deliveryRowsOf(appCId, deliveryKey).get(0).get(WebhookDeliveryModel.ACTION);
    }

    // -- fixtures -------------------------------------------------------------

    private static Integer makeGitSite(String name, String slug, String secret,
                                       String repository, String hostname) {
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, slug);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "."));

        Map<String, Object> sourceSettings = new LinkedHashMap<>();
        sourceSettings.put("repository_url", upstreamRepo.toString());
        sourceSettings.put("repository", repository);
        sourceSettings.put("branch", "main");
        sourceSettings.put("shallow_clone", false);
        sourceSettings.put("auto_deploy", true);
        sourceSettings.put("webhook_secret", secret);
        TestSources.attachGitSource(site, sourceSettings);
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);

        var domainModel = Models.get(SiteDomainModel.class);
        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, "exact");
        domainModel.save(domain);
        return site.get(SiteModel.ID);
    }

    @SuppressWarnings("unchecked")
    private static void enablePreviews(Integer siteId) {
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.findById(siteId);
        Map<String, Object> settings = new LinkedHashMap<>(
            TestSources.sourceSettingsOf(site));
        settings.put("previews_enabled", true);
        TestSources.updateSourceSettings(site, settings);
        siteModel.save(site);
    }

    private static String pushPayload(String repository, String ref, String sha) {
        return "{\"ref\":\"" + ref + "\",\"after\":\"" + sha + "\","
            + "\"repository\":{\"full_name\":\"" + repository + "\"}}";
    }

    /** The webhook URL of an application: its instance id is the whole last segment. */
    private static String hookUrl(Integer applicationId) {
        return GitWebhookHandler.PREFIX + applicationId;
    }

    /** The application instance a site exposes, which the webhook URL names. */
    private static Integer applicationOf(Integer siteId) {
        return Models.get(SiteModel.class).findById(siteId).get(SiteModel.INSTANCE_ID);
    }

    /**
     * What a completed deploy leaves behind. A git deployment IS a release operation now;
     * the separate deployments table died with the host-slot lane, so "deployed nothing"
     * is asserted against the record the release engine actually writes.
     */
    private static List<Row> releaseOperationsOf(Integer applicationId) {
        return Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ReleaseOperationModel.FOR_ID.eq(applicationId))
            .all();
    }

    private static List<Row> deliveryRows() {
        return Models.get(WebhookDeliveryModel.class).find().all();
    }

    private static List<Row> deliveryRowsOf(Integer applicationId, String key) {
        return Models.get(WebhookDeliveryModel.class).find()
            .where(WebhookDeliveryModel.INSTANCE_ID.eq(applicationId))
            .where(WebhookDeliveryModel.DELIVERY_KEY.eq(key))
            .all();
    }

    /** Status line + body only, so header ordering noise cannot mask a leak. */
    private static String statusAndBody(String response) {
        int split = response.indexOf("\n\n");
        String statusLine = response.lines().findFirst().orElse("");
        return statusLine + "|" + (split >= 0 ? response.substring(split + 2).trim() : "");
    }

    private static String post(String path, String body, String... headerLines)
            throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(8000);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            StringBuilder request = new StringBuilder()
                .append("POST ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: webhook.test\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Content-Length: ").append(payload.length).append("\r\n");
            for (String line : headerLines) {
                request.append(line).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            out.write(payload);
            out.flush();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }
            } catch (java.net.SocketTimeoutException partial) {
                // whatever was read is the observable response
            }
            return response.toString();
        }
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
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                || process.exitValue() != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + output);
        }
        return output;
    }

    private static void await(String what, BooleanSupplier condition)
            throws InterruptedException {
        for (int i = 0; i < 240; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }
}
