package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.dry.Dry;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.http.RateLimiter;
import be.elevenways.zenit.server.security.SecureTokens;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Handles git webhook requests on the proxy port; intercepted by SiteDispatcher before
 * hostname-based routing, which never runs the zenit conduit chain -- so the signature
 * IS the authentication and core's RateLimiter is driven directly (the SiteApiKeys
 * precedent).
 *
 * Security shape: every refusal on the way to signature verification -- unknown slug,
 * unknown site, non-git site, missing secret, wrong signature -- is the SAME 404, so a
 * probe learns nothing about which sites exist or which carry secrets. A delivery id is
 * claimed BEFORE any action (the replay ledger), a payload that names a repository must
 * name the bound one, and only a branch-matching push deploys -- an event carrying no
 * {@code ref} is not a push and is ignored rather than deployed.
 */
public class GitWebhookHandler {

    private static final String PREFIX = "/api/webhooks/git/";
    private static final HttpString X_HUB_SIGNATURE_256 = new HttpString("X-Hub-Signature-256");
    private static final HttpString X_GITLAB_TOKEN = new HttpString("X-Gitlab-Token");
    private static final HttpString X_GITEA_SIGNATURE = new HttpString("X-Gitea-Signature");
    private static final HttpString X_GITHUB_DELIVERY = new HttpString("X-GitHub-Delivery");
    private static final HttpString X_GITLAB_EVENT_UUID = new HttpString("X-Gitlab-Event-UUID");
    private static final HttpString X_GITHUB_EVENT = new HttpString("X-GitHub-Event");
    private static final HttpString X_GITLAB_EVENT = new HttpString("X-Gitlab-Event");
    private static final HttpString X_GITEA_EVENT = new HttpString("X-Gitea-Event");
    private static final HttpString X_GITEA_DELIVERY = new HttpString("X-Gitea-Delivery");

    /** The {@code X-Gitlab-Event} value of a Merge Request Hook (spaces and all). */
    private static final String GITLAB_MERGE_REQUEST_EVENT = "Merge Request Hook";

    /** One constant refusal for everything short of a verified signature. */
    private static final String REFUSAL_BODY = "{\"error\":\"not found\"}";

    /** Payloads larger than this are refused before the body is read. */
    private static final long MAX_BODY_BYTES = 2 * 1024 * 1024;

    private static final RateLimiter LIMITER = new RateLimiter();
    private static final int ATTEMPTS_PER_MINUTE = 60;

    /**
     * Check if the request is a POST to the webhook prefix.
     */
    public static boolean matches(HttpServerExchange exchange) {
        return "POST".equals(exchange.getRequestMethod().toString())
            && exchange.getRequestPath().startsWith(PREFIX);
    }

    /**
     * Handle a webhook request: rate limit, read the raw body, verify the signature,
     * claim the delivery id, then route the event (deploy or preview).
     */
    public static void handle(HttpServerExchange exchange, @Nullable String clientIp) {
        if (!LIMITER.tryAcquire("hohenheim:git-webhook:" + (clientIp == null ? "" : clientIp),
                ATTEMPTS_PER_MINUTE, Duration.ofMinutes(1)).allowed()) {
            sendJson(exchange, 429, "{\"error\":\"rate limited\"}");
            return;
        }

        String path = exchange.getRequestPath();
        String slug = path.substring(PREFIX.length());
        if (slug.isEmpty() || slug.contains("/")) {
            refuse(exchange);
            return;
        }
        long contentLength = exchange.getRequestContentLength();
        if (contentLength > MAX_BODY_BYTES) {
            sendJson(exchange, 413, "{\"error\":\"payload too large\"}");
            return;
        }

        // Read full body, then dispatch to a worker thread for the DB work
        exchange.getRequestReceiver().receiveFullString((ex, body) -> {
            ex.dispatch(() -> {
                try {
                    processWebhook(ex, slug, body);
                } catch (Exception e) {
                    Blast.log("GIT WEBHOOK: error processing webhook for slug", slug, "-", e.getMessage());
                    sendJson(ex, 500, "{\"error\":\"internal error\"}");
                }
            });
        }, StandardCharsets.UTF_8);
    }

    private static void processWebhook(HttpServerExchange exchange, String slug, String body) {
        if (body.length() > MAX_BODY_BYTES) {
            sendJson(exchange, 413, "{\"error\":\"payload too large\"}");
            return;
        }

        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.find()
            .where(SiteModel.SLUG.eq(slug))
            .where(SiteModel.DELETED_AT.isNull())
            .first();

        // Everything up to and including signature verification refuses IDENTICALLY:
        // the response must not reveal whether the site exists or carries a secret.
        Map<String, Object> sourceSettings = site != null
            && SiteModel.SOURCE_GIT.equals(site.get(SiteModel.SOURCE))
            ? castSettings(site.get(SiteModel.SOURCE_SETTINGS)) : null;
        String webhookSecret = sourceSettings != null
            ? str(sourceSettings.get("webhook_secret")) : "";

        if (webhookSecret.isEmpty() || !validateSignature(exchange, body, webhookSecret)) {
            refuse(exchange);
            return;
        }

        int siteId = site.get(SiteModel.ID);

        // Replay claim FIRST: a provider retry (same delivery id) must never act twice.
        String deliveryKey = deliveryKeyOf(exchange, body);
        String event = eventOf(exchange);
        Row claimed = WebhookDeliveries.claim(siteId, deliveryKey, event);
        if (claimed == null) {
            sendJson(exchange, 200, "{\"status\":\"duplicate\"}");
            return;
        }

        // Repository binding: a payload that names a repository must name the one this
        // site is bound to -- a valid signature for the right site is not a licence to
        // act on another repository's events (secret reuse, provider misconfiguration).
        Object payload = parsePayload(body);
        String payloadRepo = payloadRepository(payload);
        String boundRepo = boundRepositoryOf(sourceSettings);
        if (payloadRepo != null && boundRepo != null && !payloadRepo.equalsIgnoreCase(boundRepo)) {
            WebhookDeliveries.stampAction(claimed, "repository_mismatch");
            sendJson(exchange, 422, "{\"error\":\"repository mismatch\"}");
            return;
        }

        if (isPullRequestEvent(event)) {
            handlePullRequest(exchange, claimed, site, sourceSettings, payload, event);
            return;
        }

        handlePush(exchange, claimed, site, sourceSettings, payload, event);
    }

    // -- push -> deploy --------------------------------------------------------

    private static void handlePush(HttpServerExchange exchange, Row claimed, Row site,
                                   Map<String, Object> sourceSettings, Object payload,
                                   @Nullable String event) {
        int siteId = site.get(SiteModel.ID);

        // Branch selection: a push to another branch is not this site's source.
        String configuredBranch = str(sourceSettings.getOrDefault("branch", "main"));
        if (configuredBranch.isEmpty()) {
            configuredBranch = "main";
        }
        String pushedRef = payload instanceof Map<?, ?> map ? str(map.get("ref")) : "";
        // AIDEV-NOTE: a MISSING ref means this is not a push payload at all, and it must
        // never fall through to a production deploy. Every provider's push payload
        // carries `ref` (GitHub/Gitea `refs/heads/x`, GitLab Push Hook the same), so the
        // only deliveries reaching here without one are the events this handler does not
        // model -- issues, releases, stars, repository, a review comment. Before this
        // check an empty ref SKIPPED the branch comparison, so every such delivery queued
        // a production deploy of the default branch: the exact shape the GitLab
        // merge-request mapping was added to fix, still open for every unmapped event on
        // all three providers. An unmodelled event is ignored, loudly and idempotently.
        if (pushedRef.isEmpty()) {
            WebhookDeliveries.stampAction(claimed, "ignored_not_a_push");
            sendJson(exchange, 200, "{\"status\":\"ignored\",\"reason\":\"not a push\"}");
            return;
        }
        if (!pushedRef.equals("refs/heads/" + configuredBranch)) {
            WebhookDeliveries.stampAction(claimed, "ignored_branch");
            sendJson(exchange, 200, "{\"status\":\"ignored\",\"reason\":\"branch\"}");
            return;
        }

        if (!Boolean.TRUE.equals(sourceSettings.get("auto_deploy"))) {
            WebhookDeliveries.stampAction(claimed, "ignored_auto_deploy");
            sendJson(exchange, 200, "{\"status\":\"ignored\",\"reason\":\"auto_deploy disabled\"}");
            return;
        }

        var proxy = ServerMain.getProxyServer();
        if (proxy == null) {
            sendJson(exchange, 503, "{\"error\":\"proxy not running\"}");
            return;
        }

        SiteRequestHandler handler = proxy.getDispatcher().findHandlerBySiteId(siteId);
        if (handler instanceof GitSiteRequestHandler gitHandler) {
            // The pushed head gets a PENDING status right away; the deploy's own
            // completion reports the outcome onto the sha it actually checked out.
            String pushedSha = payload instanceof Map<?, ?> map ? str(map.get("after")) : "";
            DeployStatuses.report(sourceSettings, pushedSha.isEmpty() ? null : pushedSha,
                GitProviderClient.StatusState.PENDING, DeployStatuses.CONTEXT_DEPLOY,
                "Deploy queued", null);
            gitHandler.enqueueDeploy("webhook");
            // An externally-triggered production deploy needs the same trail as
            // the manual admin action (which records deploy_triggered).
            ActivityLog.record(Models.get(SiteModel.class), siteId, "deploy_triggered", "webhook");
            WebhookDeliveries.stampAction(claimed, "deploy_queued");
            Blast.log("GIT WEBHOOK: deploy queued for site", site.get(SiteModel.SLUG),
                "(id:", siteId + ")");
            sendJson(exchange, 200, "{\"status\":\"queued\"}");
        } else {
            sendJson(exchange, 404, "{\"error\":\"handler not found\"}");
        }
    }

    // -- pull request -> preview ----------------------------------------------

    private static void handlePullRequest(HttpServerExchange exchange, Row claimed, Row site,
                                          Map<String, Object> sourceSettings, Object payload,
                                          @Nullable String event) {
        if (!Boolean.TRUE.equals(sourceSettings.get("previews_enabled"))) {
            WebhookDeliveries.stampAction(claimed, "ignored_previews_disabled");
            sendJson(exchange, 200, "{\"status\":\"ignored\",\"reason\":\"previews disabled\"}");
            return;
        }
        PreviewEvent previewEvent = previewEventOf(event, payload);
        if (previewEvent == null) {
            WebhookDeliveries.stampAction(claimed, "ignored_malformed_pr");
            sendJson(exchange, 200, "{\"status\":\"ignored\",\"reason\":\"unrecognized payload\"}");
            return;
        }

        int siteId = site.get(SiteModel.ID);
        String ref = previewEvent.ref();
        Datasource datasource = Db.current();
        switch (previewEvent.intent()) {
            case DEPLOY -> {
                // The build takes minutes; the provider expects an answer in seconds.
                JobRunner.startVirtualThread(() -> withScope(datasource, () ->
                    be.elevenways.hohenheim.server.preview.PreviewDeployments
                        .deployQuietly(siteId, ref, previewEvent.sha(), previewEvent.number())));
                WebhookDeliveries.stampAction(claimed, "preview_queued");
                ActivityLog.record(Models.get(SiteModel.class), siteId,
                    "preview_triggered", "webhook:" + ref);
                sendJson(exchange, 200, "{\"status\":\"preview_queued\"}");
            }
            case TEARDOWN -> {
                JobRunner.startVirtualThread(() -> withScope(datasource, () ->
                    be.elevenways.hohenheim.server.preview.PreviewDeployments
                        .destroyForRefQuietly(siteId, ref, "pr_closed")));
                WebhookDeliveries.stampAction(claimed, "preview_teardown_queued");
                sendJson(exchange, 200, "{\"status\":\"preview_teardown_queued\"}");
            }
            case IGNORE -> {
                WebhookDeliveries.stampAction(claimed, "ignored_pr_action");
                sendJson(exchange, 200, "{\"status\":\"ignored\",\"reason\":\"action\"}");
            }
        }
    }

    /** What a change-request event asks the preview lane for, provider-neutral. */
    enum PreviewIntent { DEPLOY, TEARDOWN, IGNORE }

    /** One change-request event lowered onto the preview lane's vocabulary. */
    record PreviewEvent(@NonNull PreviewIntent intent, @NonNull String ref,
                        @NonNull String sha, @Nullable Integer number) {
    }

    /**
     * Lower a provider's change-request event onto the preview lifecycle.
     *
     * The providers agree on nothing but the concept: GitHub nests the branch and
     * sha under {@code pull_request.head} and names its actions
     * opened/reopened/synchronize/closed; Gitea uses the same nesting but spells the
     * commit action {@code synchronized}; GitLab puts them in
     * {@code object_attributes} ({@code source_branch}, {@code last_commit.id},
     * {@code iid}) and names them open/reopen/update/close/merge. Mapping by shape
     * rather than by header would be a guess -- the header decides which reader runs,
     * and the reader knows its own provider's names.
     *
     * @return null when the payload is not a recognizable change request; an IGNORE
     *         intent when it is one whose action the preview lane does not act on
     */
    static @Nullable PreviewEvent previewEventOf(@Nullable String event, @Nullable Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            return null;
        }
        return GITLAB_MERGE_REQUEST_EVENT.equals(event)
            ? gitlabMergeRequest(map) : githubPullRequest(map);
    }

    private static @Nullable PreviewEvent githubPullRequest(@NonNull Map<?, ?> map) {
        if (!(map.get("pull_request") instanceof Map<?, ?> pr)
                || !(pr.get("head") instanceof Map<?, ?> head)) {
            return null;
        }
        String ref = str(head.get("ref"));
        if (ref.isEmpty()) {
            return null;
        }
        // AIDEV-NOTE: `synchronized` (with the d) is GITEA's word for GitHub's
        // `synchronize` -- HookIssueSynchronized in modules/structs/hook.go. Gitea folds
        // pull_request_sync onto the `pull_request` event and reaches this reader through
        // the GitHub-compatible X-GitHub-Event header it also sends, so without the second
        // spelling a new commit on a Gitea pull request answered 200 and rebuilt nothing:
        // the preview stayed pinned at the sha it was opened with.
        PreviewIntent intent = switch (str(map.get("action"))) {
            case "opened", "reopened", "synchronize", "synchronized" -> PreviewIntent.DEPLOY;
            case "closed" -> PreviewIntent.TEARDOWN;
            default -> PreviewIntent.IGNORE;
        };
        return new PreviewEvent(intent, ref, str(head.get("sha")),
            pr.get("number") instanceof Number number ? number.intValue() : null);
    }

    /**
     * AIDEV-NOTE: {@code update} is NOT GitHub's {@code synchronize}. GitLab fires it for
     * title, description, label, assignee and milestone edits as well as for new commits,
     * and only the commit case carries {@code oldrev} (the source branch's previous head).
     * Redeploying on every {@code update} would rebuild a preview each time somebody
     * retitled the merge request. A {@code merge} is a teardown like {@code close}: the
     * branch is gone either way, and GitHub reaches the same place through {@code closed}.
     * The MR number is {@code iid}, the per-project one humans see -- {@code id} is the
     * instance-wide row id and would name previews after a number nobody recognizes.
     */
    private static @Nullable PreviewEvent gitlabMergeRequest(@NonNull Map<?, ?> map) {
        if (!(map.get("object_attributes") instanceof Map<?, ?> attributes)) {
            return null;
        }
        String ref = str(attributes.get("source_branch"));
        if (ref.isEmpty()) {
            return null;
        }
        PreviewIntent intent = switch (str(attributes.get("action"))) {
            case "open", "reopen" -> PreviewIntent.DEPLOY;
            case "update" -> str(attributes.get("oldrev")).isEmpty()
                ? PreviewIntent.IGNORE : PreviewIntent.DEPLOY;
            case "close", "merge" -> PreviewIntent.TEARDOWN;
            default -> PreviewIntent.IGNORE;
        };
        String sha = attributes.get("last_commit") instanceof Map<?, ?> commit
            ? str(commit.get("id")) : "";
        return new PreviewEvent(intent, ref, sha,
            attributes.get("iid") instanceof Number number ? number.intValue() : null);
    }

    // -- verification and parsing ---------------------------------------------

    private static boolean validateSignature(HttpServerExchange exchange, String body, String secret) {
        // GitHub: X-Hub-Signature-256
        String hubSig = exchange.getRequestHeaders().getFirst(X_HUB_SIGNATURE_256);
        if (hubSig != null) {
            String expected = "sha256=" + SecureTokens.hmacSha256Hex(secret, body);
            return SecureTokens.constantTimeEquals(expected, hubSig);
        }

        // Gitea: X-Gitea-Signature
        String giteaSig = exchange.getRequestHeaders().getFirst(X_GITEA_SIGNATURE);
        if (giteaSig != null) {
            String expected = SecureTokens.hmacSha256Hex(secret, body);
            return SecureTokens.constantTimeEquals(expected, giteaSig);
        }

        // GitLab: X-Gitlab-Token (direct comparison, not HMAC)
        String gitlabToken = exchange.getRequestHeaders().getFirst(X_GITLAB_TOKEN);
        if (gitlabToken != null) {
            return SecureTokens.constantTimeEquals(secret, gitlabToken);
        }

        // No recognized signature header -- reject
        return false;
    }

    /**
     * Provider delivery id when sent, else the body hash: replays fold either way.
     *
     * AIDEV-NOTE: Gitea sends X-GitHub-Delivery as well as its own header (its
     * addDefaultHeaders emits the Gitea, Gogs AND GitHub-compatible sets), so the first
     * branch already answers for it. The Gitea branch matters for a forge or a reverse
     * proxy that forwards only the vendor headers -- without it such a delivery folded on
     * the body hash, which makes two identical retries of one event look like one delivery
     * and two DIFFERENT events with identical bodies look like a replay.
     */
    private static @NonNull String deliveryKeyOf(HttpServerExchange exchange, String body) {
        String github = exchange.getRequestHeaders().getFirst(X_GITHUB_DELIVERY);
        if (github != null && !github.isBlank()) {
            return "gh:" + github.trim();
        }
        String gitlab = exchange.getRequestHeaders().getFirst(X_GITLAB_EVENT_UUID);
        if (gitlab != null && !gitlab.isBlank()) {
            return "gl:" + gitlab.trim();
        }
        String gitea = exchange.getRequestHeaders().getFirst(X_GITEA_DELIVERY);
        if (gitea != null && !gitea.isBlank()) {
            return "gt:" + gitea.trim();
        }
        return "body:" + SecureTokens.sha256Hex(body);
    }

    private static @Nullable String eventOf(HttpServerExchange exchange) {
        String github = exchange.getRequestHeaders().getFirst(X_GITHUB_EVENT);
        if (github != null && !github.isBlank()) {
            return github.trim();
        }
        String gitlab = exchange.getRequestHeaders().getFirst(X_GITLAB_EVENT);
        if (gitlab != null && !gitlab.isBlank()) {
            return gitlab.trim();
        }
        // Gitea's own event header carries the same vocabulary as GitHub's ("push",
        // "pull_request"), so no folding is needed once it is read.
        String gitea = exchange.getRequestHeaders().getFirst(X_GITEA_EVENT);
        return gitea != null && !gitea.isBlank() ? gitea.trim() : null;
    }

    /**
     * The change-request events that drive previews. GitLab's Merge Request Hook is the
     * same lifecycle under another name, so it routes to the same handler -- a GitLab
     * repository used to get pushes but silently no previews at all.
     */
    private static boolean isPullRequestEvent(@Nullable String event) {
        return "pull_request".equals(event) || GITLAB_MERGE_REQUEST_EVENT.equals(event);
    }

    private static @Nullable Object parsePayload(String body) {
        try {
            return new Dry().parse(body);
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    /** The repository identity a payload names, or null when it names none. */
    static @Nullable String payloadRepository(@Nullable Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            return null;
        }
        if (map.get("repository") instanceof Map<?, ?> repo
                && repo.get("full_name") != null) {
            return String.valueOf(repo.get("full_name"));
        }
        if (map.get("project") instanceof Map<?, ?> project
                && project.get("path_with_namespace") != null) {
            return String.valueOf(project.get("path_with_namespace"));
        }
        return null;
    }

    /** The repository the site is bound to: the provider binding, else the URL's path. */
    static @Nullable String boundRepositoryOf(@NonNull Map<String, Object> sourceSettings) {
        String repository = str(sourceSettings.get("repository"));
        if (!repository.isEmpty()) {
            return repository;
        }
        String url = str(sourceSettings.get("repository_url"));
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return null;
        }
        int pathStart = url.indexOf('/', schemeEnd + 3);
        if (pathStart < 0) {
            return null;
        }
        String repoPath = url.substring(pathStart + 1);
        if (repoPath.endsWith(".git")) {
            repoPath = repoPath.substring(0, repoPath.length() - 4);
        }
        return repoPath.isEmpty() ? null : repoPath;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> castSettings(@Nullable Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private static void withScope(@Nullable Datasource datasource, @NonNull Runnable body) {
        if (datasource != null) {
            Db.run(datasource, body);
        } else {
            body.run();
        }
    }

    private static void refuse(HttpServerExchange exchange) {
        sendJson(exchange, 404, REFUSAL_BODY);
    }

    private static void sendJson(HttpServerExchange exchange, int status, String json) {
        exchange.setStatusCode(status);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(json);
    }

    private static @NonNull String str(@Nullable Object value) {
        return value == null ? "" : value.toString().trim();
    }

    /** Test hook: reset the per-IP webhook rate limiter. */
    public static @NonNull RateLimiter limiter() {
        return LIMITER;
    }
}
