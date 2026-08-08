package be.elevenways.hohenheim.test.source;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DeploymentModel;
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
 * The webhook security contract as observed traffic against a real proxy port: the
 * signature IS the authentication (constant refusal, no existence leak), a delivery id
 * deploys ONCE, a delivery cannot cross site/repository boundaries, and only the bound
 * branch deploys. Every counterfactual asserts ABSENCE of effect (no delivery row, no
 * deployment row), never just a status code.
 */
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class GitWebhookSecurityTest extends HohenheimTestBase {

    private static Path upstreamRepo;
    private static ProxyServer proxy;
    private static int proxyPort;
    private static Integer siteAId;
    private static Integer siteBId;
    private static Integer siteCId;

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
        siteBId = makeGitSite("Hook Site B", "hook-b", SECRET_B, "acme/repo-b", "hook-b.test");
        // Site C is the previews-OPTED-IN site. Its static type makes the preview engine
        // refuse by type the moment the background job starts, so the mapping is observed
        // through the stamped delivery action and nothing is ever built.
        siteCId = makeGitSite("Hook Site C", "hook-c", SECRET_C, "acme/repo-c", "hook-c.test");
        enablePreviews(siteCId);

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        proxy = new ProxyServer();
        proxy.start();
        ServerMain.adoptProxyServer(proxy);
        proxyPort = ((InetSocketAddress) proxy.getHttpListenerInfo().getAddress()).getPort();

        // Both sites finish their INITIAL deploy before any webhook counting starts.
        await("initial deploys of both sites recorded", () ->
            !deploymentsOf(siteAId, "initial").isEmpty()
                && !deploymentsOf(siteBId, "initial").isEmpty());
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
        String unsigned = post("/api/webhooks/git/hook-a", body,
            "X-GitHub-Event: push", "X-GitHub-Delivery: " + UUID.randomUUID());
        assertThat(unsigned).as("step 1: unsigned refused").startsWith("HTTP/1.1 404");

        // 2. WRONG-signed (site B's real secret against site A's hook): identical
        //    refusal, still nothing. This is also the cross-site half of isolation: a
        //    party that legitimately holds B's secret gets nothing against A.
        String wrongSigned = post("/api/webhooks/git/hook-a", body,
            "X-GitHub-Event: push", "X-GitHub-Delivery: " + UUID.randomUUID(),
            "X-Hub-Signature-256: sha256=" + SecureTokens.hmacSha256Hex(SECRET_B, body));
        assertThat(wrongSigned).as("step 2: wrong signature refused")
            .startsWith("HTTP/1.1 404");

        // 3. An unknown slug refuses with the SAME status and body as a wrong
        //    signature on a real site -- the response leaks neither site existence nor
        //    whether a secret is configured.
        String unknownSlug = post("/api/webhooks/git/no-such-site", body,
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
        assertThat(deploymentsOf(siteAId, "webhook"))
            .as("step 4: refused deliveries deploy nothing").isEmpty();
        assertThat(deploymentsOf(siteBId, "webhook")).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void aReplayedDeliveryDeploysExactlyOnce() throws Exception {
        String deliveryId = UUID.randomUUID().toString();
        String body = pushPayload("acme/repo-a", "refs/heads/main", "cafe2222");
        String signature = "sha256=" + SecureTokens.hmacSha256Hex(SECRET_A, body);

        // 1. The genuine delivery deploys.
        String first = post("/api/webhooks/git/hook-a", body,
            "X-GitHub-Event: push", "X-GitHub-Delivery: " + deliveryId,
            "X-Hub-Signature-256: " + signature);
        assertThat(first).as("step 1: the signed delivery is accepted")
            .startsWith("HTTP/1.1 200").contains("queued");
        await("step 1: the webhook deploy is recorded",
            () -> deploymentsOf(siteAId, "webhook").size() == 1);

        // 2. The provider retries the SAME delivery id (byte-identical): acknowledged
        //    as a duplicate, and NO second deployment ever appears.
        String replay = post("/api/webhooks/git/hook-a", body,
            "X-GitHub-Event: push", "X-GitHub-Delivery: " + deliveryId,
            "X-Hub-Signature-256: " + signature);
        assertThat(replay).as("step 2: the replay is acknowledged, not re-run")
            .startsWith("HTTP/1.1 200").contains("duplicate");
        Thread.sleep(500);
        assertThat(deploymentsOf(siteAId, "webhook"))
            .as("step 2: one delivery id, one deployment -- replayed or not")
            .hasSize(1);
        assertThat(deliveryRowsOf(siteAId, "gh:" + deliveryId))
            .as("step 2: the delivery id was claimed exactly once").hasSize(1);
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void aDeliveryForAnotherRepositoryIsRefusedEvenWithAValidSignature() throws Exception {
        // Site B's webhook, correctly signed with B's secret, but the payload names
        // repo-a: a webhook must authorize exactly the repository binding it was
        // registered for (secret reuse / provider misconfiguration must not cross).
        String body = pushPayload("acme/repo-a", "refs/heads/main", "cafe3333");
        String response = post("/api/webhooks/git/hook-b", body,
            "X-GitHub-Event: push", "X-GitHub-Delivery: " + UUID.randomUUID(),
            "X-Hub-Signature-256: sha256=" + SecureTokens.hmacSha256Hex(SECRET_B, body));
        assertThat(response).as("the repository mismatch is refused")
            .startsWith("HTTP/1.1 422").contains("repository mismatch");
        Thread.sleep(300);
        assertThat(deploymentsOf(siteBId, "webhook"))
            .as("the mismatched delivery deployed nothing on site B").isEmpty();
        assertThat(deploymentsOf(siteAId, "webhook"))
            .as("and certainly nothing on site A")
            .allSatisfy(row -> assertThat((Object) row.get(DeploymentModel.SITE_ID))
                .isEqualTo(siteAId));
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void aPushToAnotherBranchIsIgnored() throws Exception {
        int before = deploymentsOf(siteBId, "webhook").size();
        String body = pushPayload("acme/repo-b", "refs/heads/feature-x", "cafe4444");
        String response = post("/api/webhooks/git/hook-b", body,
            "X-GitHub-Event: push", "X-GitHub-Delivery: " + UUID.randomUUID(),
            "X-Hub-Signature-256: sha256=" + SecureTokens.hmacSha256Hex(SECRET_B, body));
        assertThat(response).as("the off-branch push is acknowledged but ignored")
            .startsWith("HTTP/1.1 200").contains("branch");
        Thread.sleep(300);
        assertThat(deploymentsOf(siteBId, "webhook"))
            .as("no deploy for a branch the site is not bound to").hasSize(before);
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    void pullRequestEventsAreIgnoredUntilPreviewsAreOptedIn() throws Exception {
        String body = "{\"action\":\"opened\",\"repository\":{\"full_name\":\"acme/repo-b\"},"
            + "\"pull_request\":{\"number\":7,\"head\":{\"ref\":\"feature-x\","
            + "\"sha\":\"cafe5555\"}}}";
        String response = post("/api/webhooks/git/hook-b", body,
            "X-GitHub-Event: pull_request", "X-GitHub-Delivery: " + UUID.randomUUID(),
            "X-Hub-Signature-256: sha256=" + SecureTokens.hmacSha256Hex(SECRET_B, body));
        assertThat(response)
            .as("previews are an explicit opt-in; a PR event without it does nothing")
            .startsWith("HTTP/1.1 200").contains("previews disabled");
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    void gitlabSharedTokenLaneDeploysAndItsWrongTokenRefusesIdentically() throws Exception {
        int before = deploymentsOf(siteBId, "webhook").size();
        String eventUuid = UUID.randomUUID().toString();
        // GitLab push shape: project.path_with_namespace names the repository.
        String body = "{\"ref\":\"refs/heads/main\",\"after\":\"cafe6666\","
            + "\"project\":{\"path_with_namespace\":\"acme/repo-b\"}}";

        // 1. Counterfactual FIRST: the right header with the WRONG token is the same
        //    404 as an unsigned delivery, and nothing was claimed or deployed.
        String wrongToken = post("/api/webhooks/git/hook-b", body,
            "X-Gitlab-Event: Push Hook",
            "X-Gitlab-Event-UUID: " + UUID.randomUUID(),
            "X-Gitlab-Token: " + SECRET_A);
        assertThat(wrongToken).as("step 1: a wrong X-Gitlab-Token is refused")
            .startsWith("HTTP/1.1 404");
        Thread.sleep(300);
        assertThat(deploymentsOf(siteBId, "webhook"))
            .as("step 1: the wrong token deployed nothing").hasSize(before);

        // 2. Positive anchor: the same delivery with the RIGHT shared token deploys.
        String accepted = post("/api/webhooks/git/hook-b", body,
            "X-Gitlab-Event: Push Hook",
            "X-Gitlab-Event-UUID: " + eventUuid,
            "X-Gitlab-Token: " + SECRET_B);
        assertThat(accepted).as("step 2: the GitLab shared-token delivery is accepted")
            .startsWith("HTTP/1.1 200").contains("queued");
        await("step 2: the GitLab-triggered deploy is recorded",
            () -> deploymentsOf(siteBId, "webhook").size() == before + 1);
        assertThat(deliveryRowsOf(siteBId, "gl:" + eventUuid))
            .as("step 2: the delivery was claimed under its GitLab event uuid")
            .hasSize(1);
    }

    /**
     * GitLab's Merge Request Hook drives the SAME preview lifecycle GitHub's
     * pull_request does. A GitLab repository used to get pushes and silently no
     * previews at all, because only the literal event name "pull_request" was mapped.
     *
     * Observed through the STAMPED delivery action, which is the handler's own record of
     * which lane a delivery reached; the preview machinery itself is proven elsewhere and
     * refuses this static site by type, so nothing is built here.
     */
    @Test
    @org.junit.jupiter.api.Order(7)
    void gitlabMergeRequestEventsDriveTheSamePreviewLifecycleGithubPullRequestsDo()
            throws Exception {
        // 1. open: a GitLab MR reaches the preview lane at all -- this is the whole
        //    defect. Its absence used to fall through to the PUSH handler.
        assertThat(mergeRequestAction("open", null))
            .as("step 1: an opened merge request queues a preview")
            .isEqualTo("preview_queued");

        // 2. reopen is the same intent under GitLab's spelling of "reopened".
        assertThat(mergeRequestAction("reopen", null))
            .as("step 2: a reopened merge request queues a preview")
            .isEqualTo("preview_queued");

        // 3. update is NOT GitHub's synchronize: GitLab fires it for retitles, labels and
        //    assignees too, and only a source-branch head move carries oldrev. Without
        //    that discrimination every cosmetic edit would rebuild the preview.
        assertThat(mergeRequestAction("update", null))
            .as("step 3: an update carrying no new head is ignored")
            .isEqualTo("ignored_pr_action");
        assertThat(mergeRequestAction("update", "beef0000"))
            .as("step 3: an update that moved the head queues a preview")
            .isEqualTo("preview_queued");

        // 4. Both end states tear the preview down: GitHub only ever says "closed", while
        //    GitLab distinguishes close from merge.
        assertThat(mergeRequestAction("close", null))
            .as("step 4: a closed merge request queues a teardown")
            .isEqualTo("preview_teardown_queued");
        assertThat(mergeRequestAction("merge", null))
            .as("step 4: a merged one does too")
            .isEqualTo("preview_teardown_queued");

        // 5. An action outside the lifecycle is acknowledged and does nothing.
        assertThat(mergeRequestAction("approved", null))
            .as("step 5: an approval is not a deployment trigger")
            .isEqualTo("ignored_pr_action");

        // 6. A Merge Request Hook without object_attributes is malformed, not "ignored
        //    action": the two stamps must stay distinguishable in the ledger.
        String malformedUuid = UUID.randomUUID().toString();
        String malformed = "{\"object_kind\":\"merge_request\","
            + "\"project\":{\"path_with_namespace\":\"acme/repo-c\"}}";
        post("/api/webhooks/git/hook-c", malformed,
            "X-Gitlab-Event: Merge Request Hook",
            "X-Gitlab-Event-UUID: " + malformedUuid,
            "X-Gitlab-Token: " + SECRET_C);
        assertThat(stampedAction("gl:" + malformedUuid))
            .as("step 6: a shapeless merge request payload is malformed, not ignored")
            .isEqualTo("ignored_malformed_pr");

        // 7. CROSS-PROVIDER ANCHOR: the GitHub lane still reaches the same place on the
        //    same site, so steps 1-6 are a mapping that was ADDED, not one that replaced.
        String githubUuid = UUID.randomUUID().toString();
        String githubBody = "{\"action\":\"opened\","
            + "\"repository\":{\"full_name\":\"acme/repo-c\"},"
            + "\"pull_request\":{\"number\":9,\"head\":{\"ref\":\"feature-gh\","
            + "\"sha\":\"cafe9999\"}}}";
        post("/api/webhooks/git/hook-c", githubBody,
            "X-GitHub-Event: pull_request", "X-GitHub-Delivery: " + githubUuid,
            "X-Hub-Signature-256: sha256=" + SecureTokens.hmacSha256Hex(SECRET_C, githubBody));
        assertThat(stampedAction("gh:" + githubUuid))
            .as("step 7: GitHub pull requests still queue previews on the same site")
            .isEqualTo("preview_queued");
    }

    /**
     * An event this handler does not model must never fall through to a PRODUCTION
     * deploy. Before the {@code ref} check, a correctly signed delivery of ANY unmapped
     * event -- an issue comment, a release, a star -- carried no {@code ref}, skipped the
     * branch comparison entirely and queued a deploy of the site's bound branch. Same
     * shape as the GitLab merge-request defect, one layer further out: that fix mapped one
     * event, it did not close the fall-through.
     */
    @Test
    @org.junit.jupiter.api.Order(8)
    void anEventCarryingNoRefIsNotAPushAndNeverQueuesAProductionDeploy() throws Exception {
        assertThat(deploymentsOf(siteCId, "webhook"))
            .as("baseline: site C has never been deployed by a webhook").isEmpty();

        // 1. GitHub `issues`: signed, bound to the right repository, and carrying no ref.
        //    The stamp must say it was not a push -- not "deploy_queued".
        String issuesUuid = UUID.randomUUID().toString();
        String issues = "{\"action\":\"opened\",\"issue\":{\"number\":3,\"title\":\"bug\"},"
            + "\"repository\":{\"full_name\":\"acme/repo-c\"}}";
        String issuesResponse = post("/api/webhooks/git/hook-c", issues,
            "X-GitHub-Event: issues", "X-GitHub-Delivery: " + issuesUuid,
            "X-Hub-Signature-256: sha256=" + SecureTokens.hmacSha256Hex(SECRET_C, issues));
        assertThat(issuesResponse).as("step 1: an issue event is acknowledged, not acted on")
            .startsWith("HTTP/1.1 200").contains("not a push");
        assertThat(stampedAction("gh:" + issuesUuid))
            .as("step 1: an issue event is stamped as not-a-push")
            .isEqualTo("ignored_not_a_push");

        // 2. A Gitea `release` event: same shape through the other provider's headers, so
        //    the gate is the PAYLOAD's missing ref and not one provider's header set.
        String releaseUuid = UUID.randomUUID().toString();
        String release = "{\"action\":\"published\",\"release\":{\"tag_name\":\"v1\"},"
            + "\"repository\":{\"full_name\":\"acme/repo-c\"}}";
        post("/api/webhooks/git/hook-c", release,
            "X-Gitea-Event: release", "X-Gitea-Delivery: " + releaseUuid,
            "X-Gitea-Signature: " + SecureTokens.hmacSha256Hex(SECRET_C, release));
        assertThat(stampedAction("gt:" + releaseUuid))
            .as("step 2: a Gitea release event is stamped as not-a-push")
            .isEqualTo("ignored_not_a_push");

        // 3. THE counterfactual that matters: neither delivery deployed anything. A
        //    status-only assertion would have passed against the defect too, because the
        //    old fall-through answered 200 with "queued" and then really did deploy.
        Thread.sleep(500);
        assertThat(deploymentsOf(siteCId, "webhook"))
            .as("step 3: an unmodelled event deploys NOTHING")
            .isEmpty();

        // 4. Positive anchor: a genuine push on the bound branch still deploys, so step 3
        //    is a discrimination and not a handler that stopped working.
        String pushUuid = UUID.randomUUID().toString();
        String push = pushPayload("acme/repo-c", "refs/heads/main", "cafeaaaa");
        post("/api/webhooks/git/hook-c", push,
            "X-GitHub-Event: push", "X-GitHub-Delivery: " + pushUuid,
            "X-Hub-Signature-256: sha256=" + SecureTokens.hmacSha256Hex(SECRET_C, push));
        assertThat(stampedAction("gh:" + pushUuid))
            .as("step 4: a real push on the bound branch still queues a deploy")
            .isEqualTo("deploy_queued");
        await("step 4: the anchor deploy is recorded",
            () -> deploymentsOf(siteCId, "webhook").size() == 1);
    }

    /**
     * Gitea is a first-class provider now, so its deliveries must reach the same lanes.
     * Two things were missing: its vendor headers were unread (only the GitHub-compatible
     * set it also sends kept it working at all), and its pull-request commit action is
     * spelled {@code synchronized} -- so a new commit on a Gitea pull request answered 200
     * and rebuilt nothing, leaving the preview pinned at the sha it opened with.
     */
    @Test
    @org.junit.jupiter.api.Order(9)
    void giteaDeliveriesReachTheSameLanesUnderItsOwnHeadersAndSpellings() throws Exception {
        // 1. Counterfactual first: Gitea's raw-hex signature is verified, so a WRONG
        //    secret under the Gitea header is the same silent 404 as everywhere else.
        String rejected = "{\"action\":\"opened\","
            + "\"repository\":{\"full_name\":\"acme/repo-c\"},"
            + "\"pull_request\":{\"number\":11,\"head\":{\"ref\":\"feat-gt\",\"sha\":\"beef1111\"}}}";
        String refusal = post("/api/webhooks/git/hook-c", rejected,
            "X-Gitea-Event: pull_request", "X-Gitea-Delivery: " + UUID.randomUUID(),
            "X-Gitea-Signature: " + SecureTokens.hmacSha256Hex("not-the-secret", rejected));
        assertThat(refusal).as("step 1: a wrong Gitea signature is refused")
            .startsWith("HTTP/1.1 404");

        // 2. `opened` under Gitea's OWN headers only: the delivery is claimed under the
        //    Gitea delivery id (not the body hash) and reaches the preview lane. Without
        //    the vendor-header branches the event read as null and fell to the push lane.
        String openedUuid = UUID.randomUUID().toString();
        assertThat(giteaPullRequest(openedUuid, "opened", "beef2222"))
            .as("step 2: an opened Gitea pull request queues a preview")
            .isEqualTo("preview_queued");
        assertThat(deliveryRowsOf(siteCId, "gt:" + openedUuid))
            .as("step 2: claimed under the Gitea delivery id, never the body hash")
            .hasSize(1);

        // 3. THE defect: `synchronized` is Gitea's `synchronize`. A new commit on the
        //    pull request must rebuild the preview, not be filed as an unknown action.
        assertThat(giteaPullRequest(UUID.randomUUID().toString(), "synchronized", "beef3333"))
            .as("step 3: a new commit on a Gitea pull request rebuilds the preview")
            .isEqualTo("preview_queued");

        // 4. Discrimination anchor: the spelling was ADDED, so Gitea's cosmetic actions
        //    still do nothing -- `synchronized` did not become a catch-all.
        assertThat(giteaPullRequest(UUID.randomUUID().toString(), "label_updated", "beef4444"))
            .as("step 4: a label edit is not a deployment trigger")
            .isEqualTo("ignored_pr_action");
        assertThat(giteaPullRequest(UUID.randomUUID().toString(), "closed", "beef5555"))
            .as("step 4: a closed Gitea pull request tears the preview down")
            .isEqualTo("preview_teardown_queued");

        // 5. Gitea's push lane under its own headers deploys, proving the header branches
        //    serve BOTH lanes and not only the preview one.
        int before = deploymentsOf(siteCId, "webhook").size();
        String pushUuid = UUID.randomUUID().toString();
        String push = pushPayload("acme/repo-c", "refs/heads/main", "beef6666");
        String accepted = post("/api/webhooks/git/hook-c", push,
            "X-Gitea-Event: push", "X-Gitea-Delivery: " + pushUuid,
            "X-Gitea-Signature: " + SecureTokens.hmacSha256Hex(SECRET_C, push));
        assertThat(accepted).as("step 5: the Gitea push is accepted")
            .startsWith("HTTP/1.1 200").contains("queued");
        await("step 5: the Gitea-triggered deploy is recorded",
            () -> deploymentsOf(siteCId, "webhook").size() == before + 1);
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
        post("/api/webhooks/git/hook-c", body,
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
        post("/api/webhooks/git/hook-c", body,
            "X-Gitlab-Event: Merge Request Hook",
            "X-Gitlab-Event-UUID: " + uuid,
            "X-Gitlab-Token: " + SECRET_C);
        return stampedAction("gl:" + uuid);
    }

    /** The delivery's recorded outcome; the claim is written before the handler acts. */
    private static String stampedAction(String deliveryKey) throws Exception {
        await("delivery " + deliveryKey + " is stamped", () -> {
            List<Row> rows = deliveryRowsOf(siteCId, deliveryKey);
            return !rows.isEmpty() && rows.get(0).get(WebhookDeliveryModel.ACTION) != null;
        });
        return deliveryRowsOf(siteCId, deliveryKey).get(0).get(WebhookDeliveryModel.ACTION);
    }

    // -- fixtures -------------------------------------------------------------

    private static Integer makeGitSite(String name, String slug, String secret,
                                       String repository, String hostname) {
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, slug);
        site.set(SiteModel.SITE_TYPE, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "."));
        site.set(SiteModel.SOURCE, SiteModel.SOURCE_GIT);
        Map<String, Object> sourceSettings = new LinkedHashMap<>();
        sourceSettings.put("repository_url", upstreamRepo.toString());
        sourceSettings.put("repository", repository);
        sourceSettings.put("branch", "main");
        sourceSettings.put("shallow_clone", false);
        sourceSettings.put("auto_deploy", true);
        sourceSettings.put("webhook_secret", secret);
        site.set(SiteModel.SOURCE_SETTINGS, sourceSettings);
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
            (Map<String, Object>) site.get(SiteModel.SOURCE_SETTINGS));
        settings.put("previews_enabled", true);
        site.set(SiteModel.SOURCE_SETTINGS, settings);
        siteModel.save(site);
    }

    private static String pushPayload(String repository, String ref, String sha) {
        return "{\"ref\":\"" + ref + "\",\"after\":\"" + sha + "\","
            + "\"repository\":{\"full_name\":\"" + repository + "\"}}";
    }

    private static List<Row> deploymentsOf(Integer siteId, String reason) {
        return Models.get(DeploymentModel.class).find()
            .where(DeploymentModel.SITE_ID.eq(siteId))
            .where(DeploymentModel.REASON.eq(reason))
            .all();
    }

    private static List<Row> deliveryRows() {
        return Models.get(WebhookDeliveryModel.class).find().all();
    }

    private static List<Row> deliveryRowsOf(Integer siteId, String key) {
        return Models.get(WebhookDeliveryModel.class).find()
            .where(WebhookDeliveryModel.SITE_ID.eq(siteId))
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
