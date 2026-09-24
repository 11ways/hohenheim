package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.server.preview.PreviewBranches;
import be.elevenways.hohenheim.server.application.ApplicationDeploys;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.WorkspaceBuilds;
import be.elevenways.hohenheim.server.instance.WorkspaceKind;
import be.elevenways.hohenheim.server.preview.PreviewDeployments;
import be.elevenways.hohenheim.source.GitRefNames;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.dry.Dry;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.http.RateLimiter;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.security.SecureTokens;
import io.undertow.io.IoCallback;
import io.undertow.io.Receiver;
import io.undertow.io.Sender;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xnio.IoUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Handles git webhook requests on the proxy port; intercepted by SiteDispatcher before
 * hostname-based routing, which never runs the zenit conduit chain -- so the signature
 * IS the authentication and core's RateLimiter is driven directly (the hashed-bearer
 * precedent).
 *
 * Security shape: every refusal on the way to signature verification -- unknown segment,
 * unknown application, missing secret, wrong signature -- is the SAME 404, so a
 * probe learns nothing about which applications exist or which carry secrets. A delivery id is
 * claimed BEFORE any action (the replay ledger), a payload that names a repository must
 * name the bound one, and only a branch-matching push deploys -- an event carrying no
 * {@code ref} is not a push and is ignored rather than deployed.
 */
public class GitWebhookHandler {

    /** The route this handler answers -- public so a page can DISPLAY it without re-typing it. */
    public static final String PREFIX = "/api/webhooks/git/";
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

    /** Payloads larger than this are refused, whether or not they announce their length. */
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    /** How much of a refused body is read and discarded before the connection is cut anyway. */
    private static final long MAX_DRAIN_BYTES = 8L * 1024 * 1024;

    /** How long a refused request may take to finish sending before the connection is cut. */
    private static final long DRAIN_MILLIS = 10_000;

    /** The refusal of an oversized body. */
    private static final String TOO_LARGE_BODY = "{\"error\":\"payload too large\"}";

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
        BoundedBody body = new BoundedBody(slug);
        if (exchange.getRequestContentLength() > MAX_BODY_BYTES) {
            // Announced too large: refused before a byte is buffered, then drained.
            body.refuse(exchange);
        }
        exchange.getRequestReceiver().receivePartialBytes(body, body::failed);
    }

    /**
     * The webhook body, read in pieces with the cap enforced WHILE it streams in, and the
     * refusal of an oversized one as 413-then-drain-then-close.
     *
     * AIDEV-NOTE: the cap is enforced while the body streams in, not only against
     * Content-Length: a chunked request announces no length, and receiveFullString without
     * a buffer limit used to hold ALL of it in memory before the size check could run. Only
     * the first MAX_BODY_BYTES are ever held; everything past the cap is counted and thrown
     * away.
     *
     * AIDEV-NOTE: the refusal is written FIRST (with its own Content-Length and Connection:
     * close, the response closed at once), and only then is the rest of the body DRAINED --
     * read and discarded, bounded by MAX_DRAIN_BYTES and DRAIN_MILLIS -- so the connection
     * closes on a fully read request. Closing it straight after the 413 while the client was
     * still sending left unread bytes in the kernel buffer, which makes the close a TCP RESET:
     * a real client on a real network then saw "connection reset" and never read the 413.
     * Deliberately NOT exchange.setMaxEntitySize: that transport cap fires inside Undertow's
     * body conduit and closes the whole CONNECTION before any refusal can be written.
     */
    private static final class BoundedBody implements Receiver.PartialBytesCallback {

        private final String slug;
        private final ByteArrayOutputStream buffered = new ByteArrayOutputStream();
        private boolean refused;
        private long drained;

        BoundedBody(String slug) {
            this.slug = slug;
        }

        @Override
        public synchronized void handle(HttpServerExchange exchange, byte[] message, boolean last) {
            if (this.refused) {
                this.drained += message.length;
                if (!last && this.drained > MAX_DRAIN_BYTES) {
                    // The bound on what a refusal costs: past it the reset is the client's.
                    IoUtils.safeClose(exchange.getConnection());
                }
                // On the last piece the request completes; the response already did, so the
                // exchange ends and the non-persistent connection closes on a drained socket.
                return;
            }
            if (this.buffered.size() + (long) message.length > MAX_BODY_BYTES) {
                refuse(exchange);
                this.drained += message.length;
                return;
            }
            this.buffered.write(message, 0, message.length);
            if (last) {
                String text = this.buffered.toString(StandardCharsets.UTF_8);
                exchange.dispatch(() -> {
                    try {
                        processWebhook(exchange, this.slug, text);
                    } catch (Exception e) {
                        Blast.log("GIT WEBHOOK: error processing webhook for slug", this.slug, "-",
                            e.getMessage());
                        sendJson(exchange, 500, "{\"error\":\"internal error\"}");
                    }
                });
            }
        }

        /** Answer 413 now, without ending the exchange: the drain in handle() ends it. */
        synchronized void refuse(HttpServerExchange exchange) {
            if (this.refused) {
                return;
            }
            this.refused = true;
            byte[] refusal = TOO_LARGE_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.setPersistent(false);
            exchange.setStatusCode(413);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseHeaders().put(Headers.CONNECTION, "close");
            exchange.setResponseContentLength(refusal.length);
            exchange.getResponseSender().send(ByteBuffer.wrap(refusal), new IoCallback() {
                @Override
                public void onComplete(HttpServerExchange done, Sender sender) {
                    sender.close();
                }

                @Override
                public void onException(HttpServerExchange failed, Sender sender, IOException e) {
                    IoUtils.safeClose(failed.getConnection());
                }
            });
            // A client that stalls mid-body is not waited for past the drain window.
            exchange.getIoThread().executeAfter(() -> IoUtils.safeClose(exchange.getConnection()),
                DRAIN_MILLIS, TimeUnit.MILLISECONDS);
        }

        /** The receiver's error lane. */
        synchronized void failed(HttpServerExchange exchange, IOException failure) {
            if (this.refused) {
                // The 413 is already on its way; the drain window closes what is left.
                return;
            }
            exchange.setPersistent(false);
            if (failure instanceof Receiver.RequestToLargeException) {
                refuse(exchange);
                return;
            }
            Blast.log("GIT WEBHOOK: could not read the body for slug", this.slug, "-",
                failure.getMessage());
            exchange.setStatusCode(400);
            exchange.endExchange();
        }
    }

    private static void processWebhook(HttpServerExchange exchange, String slug, String body) {
        if (body.length() > MAX_BODY_BYTES) {
            sendJson(exchange, 413, "{\"error\":\"payload too large\"}");
            return;
        }

        // AIDEV-NOTE: the URL names the APPLICATION, because the application is what has a
        // source, a secret and a deploy. It used to name a site slug and hop to the site's
        // instance to find the secret -- a hop that could not answer at all for an
        // application no site exposed, which is a perfectly ordinary application.
        Row application = applicationOf(slug);

        // Everything up to and including signature verification refuses IDENTICALLY: the
        // response must not reveal whether the application exists or carries a secret.
        Map<String, Object> sourceSettings = application == null ? null
            : ApplicationReleases.storedSettings(application);
        String webhookSecret = sourceSettings != null
            ? str(sourceSettings.get("webhook_secret")) : "";

        if (webhookSecret.isEmpty() || !validateSignature(exchange, body, webhookSecret)) {
            refuse(exchange);
            return;
        }

        int applicationId = application.get(InstanceModel.ID);

        // Replay claim FIRST: a provider retry (same delivery id) must never act twice.
        String deliveryKey = deliveryKeyOf(exchange, body);
        String event = eventOf(exchange);
        Row claimed = WebhookDeliveries.claim(applicationId, deliveryKey, event);
        if (claimed == null) {
            sendJson(exchange, 200, "{\"status\":\"duplicate\"}");
            return;
        }

        // Repository binding: a payload that names a repository must name the one this
        // application is bound to -- a valid signature for the right application is not a
        // licence to act on another repository's events (secret reuse, misconfiguration).
        Object payload = parsePayload(body);
        String payloadRepo = payloadRepository(payload);
        String boundRepo = boundRepositoryOf(sourceSettings);
        if (payloadRepo != null && boundRepo != null && !payloadRepo.equalsIgnoreCase(boundRepo)) {
            WebhookDeliveries.stampAction(claimed, "repository_mismatch");
            sendJson(exchange, 422, "{\"error\":\"repository mismatch\"}");
            return;
        }

        if (isPullRequestEvent(event)) {
            handlePullRequest(exchange, claimed, application, sourceSettings, payload, event);
            return;
        }

        handlePush(exchange, claimed, application, sourceSettings, payload, event);
    }

    // -- push -> deploy --------------------------------------------------------

    private static void handlePush(HttpServerExchange exchange, Row claimed, Row application,
                                   Map<String, Object> sourceSettings, Object payload,
                                   @Nullable String event) {
        int applicationId = application.get(InstanceModel.ID);

        // Branch selection: a push to another branch is not this application's source.
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

        // AIDEV-NOTE: the DELETED discrimination comes BEFORE the branch comparison on
        // purpose. A branch-delete push carries the exact same ref as a commit push
        // (GitHub/Gitea say `"deleted": true`, all three providers zero out `after`),
        // so a delete of the PRODUCTION branch used to pass the equality check below
        // and queue a production deploy of a branch that no longer exists. A deleted
        // ref either tears down its preview or is ignored; it never deploys anything.
        boolean deleted = isDeletedPush(payload);
        String branch = pushedRef.startsWith("refs/heads/")
            ? pushedRef.substring("refs/heads/".length()) : "";
        boolean production = pushedRef.equals("refs/heads/" + configuredBranch);
        if (deleted) {
            if (!production && !branch.isEmpty() && hasLivePreview(applicationId, branch)) {
                queuePreviewTeardown(applicationId, branch, "branch_deleted");
                WebhookDeliveries.stampAction(claimed, "preview_teardown_queued");
                sendJson(exchange, 200, "{\"status\":\"preview_teardown_queued\"}");
                return;
            }
            WebhookDeliveries.stampAction(claimed, "ignored_deleted_ref");
            sendJson(exchange, 200, "{\"status\":\"ignored\",\"reason\":\"deleted ref\"}");
            return;
        }

        // A pushed branch name is the forge's text; one git could read as an option (or
        // that is no ref at all) never reaches a checkout.
        if (!production && !branch.isEmpty() && !GitRefNames.isValid(branch)) {
            WebhookDeliveries.stampAction(claimed, "ignored_invalid_ref");
            sendJson(exchange, 200, "{\"status\":\"ignored\",\"reason\":\"invalid ref\"}");
            return;
        }

        // Per-BRANCH previews: an opt-in pattern set beside previews_enabled. The
        // PRODUCTION branch never gets one (its environment IS production), so a
        // pattern covering it changes nothing -- decided with the lane, 2026-08-10.
        if (!production && !branch.isEmpty()
                && Boolean.TRUE.equals(sourceSettings.get("previews_enabled"))
                && PreviewBranches.matches(
                    PreviewBranches.patternsOf(
                        sourceSettings.get("preview_branches")), branch)) {
            String pushedSha = payload instanceof Map<?, ?> map ? str(map.get("after")) : "";
            Datasource datasource = Db.currentOrDefault();
            JobRunner.startVirtualThread(() -> withScope(datasource, () ->
                PreviewDeployments
                    .deployQuietly(applicationId, branch,
                        pushedSha.isEmpty() ? null : pushedSha, null,
                        DeployTrigger.WEBHOOK)));
            ActivityLog.record(Models.get(InstanceModel.class), applicationId,
                "preview_triggered", "webhook:" + branch);
            WebhookDeliveries.stampAction(claimed, "preview_queued");
            sendJson(exchange, 200, "{\"status\":\"preview_queued\"}");
            return;
        }

        if (!production) {
            WebhookDeliveries.stampAction(claimed, "ignored_branch");
            sendJson(exchange, 200, "{\"status\":\"ignored\",\"reason\":\"branch\"}");
            return;
        }

        if (!Boolean.TRUE.equals(sourceSettings.get("auto_deploy"))) {
            WebhookDeliveries.stampAction(claimed, "ignored_auto_deploy");
            sendJson(exchange, 200, "{\"status\":\"ignored\",\"reason\":\"auto_deploy disabled\"}");
            return;
        }

        // The pushed head gets a PENDING status right away; the deploy's own completion
        // reports the outcome onto the sha it actually checked out.
        String pushedSha = payload instanceof Map<?, ?> map ? str(map.get("after")) : "";
        DeployStatuses.report(sourceSettings, pushedSha.isEmpty() ? null : pushedSha,
            GitProviderClient.StatusState.PENDING, DeployStatuses.CONTEXT_DEPLOY,
            "Deploy queued", null);
        // The build takes minutes; the provider expects an answer in seconds.
        Datasource deployDatasource = Db.currentOrDefault();
        String deployBranch = configuredBranch;
        // Which deploy verb depends on WHAT the record is: an application converges a
        // release, a workspace checks out and builds inside its own container. The branch
        // is on the KIND, not on a name -- a third source-driven kind wires itself here.
        boolean workspace = WorkspaceKind.ID.toString()
            .equals(application.get(InstanceModel.KIND));
        JobRunner.startVirtualThread(() -> withScope(deployDatasource, () -> {
            if (workspace) {
                new WorkspaceBuilds().deployQuietly(applicationId, deployBranch,
                    DeployTrigger.WEBHOOK);
            } else {
                ApplicationDeploys.deployQuietly(applicationId, deployBranch,
                    DeployTrigger.WEBHOOK);
            }
        }));
        WebhookDeliveries.stampAction(claimed, "deploy_queued");
        Blast.log("GIT WEBHOOK: deploy queued for application",
            application.get(InstanceModel.NAME), "(id:", applicationId + ")");
        sendJson(exchange, 200, "{\"status\":\"queued\"}");
    }

    /**
     * The application a webhook URL names.
     *
     * @param slug the last path segment: the application instance id
     * @return the application, or null when the segment names no live application
     */
    private static @Nullable Row applicationOf(@NonNull String slug) {
        int applicationId;
        try {
            applicationId = Integer.parseInt(slug.trim());
        } catch (NumberFormatException notAnId) {
            return null;
        }
        Row application = Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(applicationId))
            .first();
        if (application == null) {
            return null;
        }
        // A source-driven record: one that converges releases, or a workspace that checks
        // its source out inside itself.
        String kind = application.get(InstanceModel.KIND);
        return InstanceKinds.isReleaseManaged(kind) || WorkspaceKind.ID.toString().equals(kind)
            ? application : null;
    }

    /** All three providers zero out {@code after} on a ref delete; GitHub/Gitea also flag it. */
    private static boolean isDeletedPush(@Nullable Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            return false;
        }
        if (Boolean.TRUE.equals(map.get("deleted"))) {
            return true;
        }
        String after = str(map.get("after"));
        return !after.isEmpty() && after.chars().allMatch(c -> c == '0');
    }

    private static boolean hasLivePreview(int applicationId, @NonNull String ref) {
        return Models.get(PreviewDeploymentModel.class).find()
            .where(PreviewDeploymentModel.APPLICATION_ID.eq(applicationId))
            .where(PreviewDeploymentModel.REF.eq(ref))
            .first() != null;
    }

    private static void queuePreviewTeardown(int applicationId, @NonNull String ref,
                                             @NonNull String reason) {
        Datasource datasource = Db.currentOrDefault();
        JobRunner.startVirtualThread(() -> withScope(datasource, () ->
            PreviewDeployments
                .destroyForRefQuietly(applicationId, ref, reason)));
    }

    // -- pull request -> preview ----------------------------------------------

    private static void handlePullRequest(HttpServerExchange exchange, Row claimed,
                                          Row application,
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

        int applicationId = application.get(InstanceModel.ID);
        String ref = previewEvent.ref();
        // A teardown runs no git and must still reach a preview whatever its ref says.
        if (previewEvent.intent() == PreviewIntent.DEPLOY && !GitRefNames.isValid(ref)) {
            WebhookDeliveries.stampAction(claimed, "ignored_invalid_ref");
            sendJson(exchange, 200, "{\"status\":\"ignored\",\"reason\":\"invalid ref\"}");
            return;
        }
        Datasource datasource = Db.currentOrDefault();
        switch (previewEvent.intent()) {
            case DEPLOY -> {
                // The build takes minutes; the provider expects an answer in seconds.
                JobRunner.startVirtualThread(() -> withScope(datasource, () ->
                    PreviewDeployments
                        .deployQuietly(applicationId, ref, previewEvent.sha(),
                            previewEvent.number(), DeployTrigger.WEBHOOK)));
                WebhookDeliveries.stampAction(claimed, "preview_queued");
                ActivityLog.record(Models.get(InstanceModel.class), applicationId,
                    "preview_triggered", "webhook:" + ref);
                sendJson(exchange, 200, "{\"status\":\"preview_queued\"}");
            }
            case TEARDOWN -> {
                JobRunner.startVirtualThread(() -> withScope(datasource, () ->
                    PreviewDeployments
                        .destroyForRefQuietly(applicationId, ref, "pr_closed")));
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

    /** The repository the application is bound to: the provider binding, else the URL's path. */
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
