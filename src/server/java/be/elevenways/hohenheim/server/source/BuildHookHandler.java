package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.model.BuildHookModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.HohenheimSystemPurposes;
import be.elevenways.hohenheim.server.application.ApplicationDeploys;
import be.elevenways.hohenheim.server.application.DeployStartGate;
import be.elevenways.hohenheim.server.proxy.ProxyScheme;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.http.RateLimitKeys;
import be.elevenways.zenit.common.http.RateLimiter;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RateLimitPolicy;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.ExchangeRateLimits;
import be.elevenways.zenit.server.http.RateLimitMiddleware;
import io.undertow.server.HttpServerExchange;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.Deque;

/**
 * Answers a build hook's POST on the proxy port; intercepted by SiteDispatcher before hostname routing, like the git
 * webhook, so the URL's token IS the authentication and the budgets are zenit's exchange lane.
 *
 * Security shape: an unknown token, a revoked one and one whose application is gone or no longer deploys answer the
 * SAME 404, so a probe learns nothing about which hooks exist. A POST that did not reach the proxy over HTTPS is
 * refused before its token is looked up. The client's budget is spent before the token is looked up; the hook's
 * per-client budget once the token named a live hook; the application's pending-deploy cap and the hook's budget
 * across all clients only for a POST that is about to be accepted, so a refused POST never drains the hook for its
 * other callers. The body is never read: the only input is the optional {@code branch} query parameter, which must
 * name the application's declared branch.
 *
 * AIDEV-NOTE: no replay ledger (WebhookDeliveries) here. A forge delivery carries an id a retry repeats; a hook POST
 * carries nothing, and every POST is a deliberate request for a fresh deploy, so a repeat is bounded by
 * {@link #HOOK_POLICY}, {@link #HOOK_TOTAL_POLICY} and {@link BuildHooks#PENDING_PER_APPLICATION} instead. The deploy
 * queues on the application's operation lock like every other, and asks once more whether the hook is live, under
 * the token it was accepted with, when it gets the lock (BuildHooks.admit).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class BuildHookHandler {

    /** The route a hook answers -- public so the hook's URL and its preview are built from it, never re-typed. */
    public static final String PREFIX = "/api/hooks/build/";

    /** The optional query parameter naming the branch to deploy. */
    public static final String BRANCH_PARAM = "branch";

    /** Hook POSTs one client may attempt per window, spent before the token is looked up. */
    public static final RateLimitPolicy POLICY = RateLimitPolicy.of(30, Duration.ofMinutes(1))
        .keyBy(RateLimitPolicy.KeyBy.IP)
        .named("hohenheim.build_hook");

    /**
     * Deploys one hook may start per window from one client, spent once the token named a live hook: a CI loop
     * queues ten deploys at most.
     */
    public static final RateLimitPolicy HOOK_POLICY = RateLimitPolicy.of(10, Duration.ofMinutes(10))
        .keyBy(RateLimitPolicy.KeyBy.IP)
        .named("hohenheim.build_hook.trigger");

    /**
     * Deploys one hook may start per window from every client together, spent only for a POST that is accepted: a
     * leaked URL used from many addresses still queues no more than this.
     */
    public static final RateLimitPolicy HOOK_TOTAL_POLICY = RateLimitPolicy.of(30, Duration.ofHours(1))
        .named("hohenheim.build_hook.total");

    /** No token is longer than this; a longer segment is refused before any lookup. */
    private static final int MAX_TOKEN_LENGTH = 128;

    /** One constant refusal for every token that names no live hook. */
    private static final String REFUSAL_BODY = "{\"error\":\"not found\"}";

    private BuildHookHandler() {
    }

    /** @return whether the request is a POST to a hook's URL */
    public static boolean matches(@NonNull HttpServerExchange exchange) {
        return HookRequests.isPostTo(exchange, PREFIX);
    }

    /**
     * Spends the client's budget and refuses cleartext, then answers the hook on a worker thread: its lookup and
     * deploy touch the database.
     */
    public static void handle(@NonNull HttpServerExchange exchange) {
        RateLimiter.Decision budget = ExchangeRateLimits.tryAcquire(exchange, POLICY, null);
        if (!budget.allowed()) {
            limited(exchange, budget.retryAfterSeconds());
            return;
        }
        // AIDEV-NOTE: the token IS the credential, so a hook never answers over plain HTTP, not even with a 404: a
        // CI misconfigured to http learns it at once instead of deploying while its token crosses the wire in clear.
        // Asked before the lookup, for every token alike, so the refusal tells nothing about which hooks exist.
        if (!ProxyScheme.isEffectivelyHttps(exchange)) {
            HookRequests.sendJson(exchange, 403, "{\"error\":\"https required\"}");
            return;
        }
        String token = exchange.getRequestPath().substring(PREFIX.length());
        if (token.isEmpty() || token.contains("/") || token.length() > MAX_TOKEN_LENGTH) {
            refuse(exchange);
            return;
        }
        exchange.dispatch(() -> {
            try {
                answer(exchange, token);
            } catch (RuntimeException failed) {
                Blast.log("BUILD HOOK: could not answer a hook POST -", failed.getMessage());
                HookRequests.sendJson(exchange, 500, "{\"error\":\"internal error\"}");
            }
        });
    }

    private static void answer(@NonNull HttpServerExchange exchange, @NonNull String token) {
        Row hook = BuildHooks.live(token);
        if (hook == null) {
            refuse(exchange);
            return;
        }
        RateLimiter.Decision budget = ExchangeRateLimits.tryAcquire(exchange, HOOK_POLICY,
            String.valueOf(hook.get(BuildHookModel.ID)));
        if (!budget.allowed()) {
            limited(exchange, budget.retryAfterSeconds());
            return;
        }
        String branch = branchOf(exchange);
        // A live hook is SYSTEM work: the token was the gate, and the deploy it starts (on a thread of its own) carries
        // the declaration with it while naming the hook as its principal. Before this point the proxy thread holds no
        // identity, which every gate refuses.
        ExecutionIdentity.runAsSystem(HohenheimSystemPurposes.BUILD_HOOK, () -> trigger(exchange, hook, branch));
    }

    private static void trigger(@NonNull HttpServerExchange exchange, @NonNull Row hook, @Nullable String branch) {
        int applicationId = hook.get(BuildHookModel.INSTANCE_ID);
        Row application = Models.get(InstanceModel.class).findById(applicationId);
        if (application == null) {
            refuse(exchange);
            return;
        }
        if (branch != null && !BuildHooks.allowsBranch(application, branch)) {
            HookRequests.sendJson(exchange, 422, "{\"error\":\"branch not allowed\"}");
            return;
        }
        DeployStartGate start = BuildHooks.admit(hook);
        if (start == null) {
            // The application already holds as many accepted hook deploys as it may; one has to start first.
            limited(exchange, BuildHooks.PENDING_RETRY_AFTER.toSeconds());
            return;
        }
        RateLimiter.Decision total = spendHookTotal(hook.get(BuildHookModel.ID));
        if (!total.allowed()) {
            start.settled();
            limited(exchange, total.retryAfterSeconds());
            return;
        }
        boolean queued = false;
        try {
            // The build takes minutes; the caller expects an answer in seconds. The gate re-checks the hook under
            // the application's lock and stamps when it last started a deploy only if it does.
            ApplicationDeploys.deployInBackground(applicationId, branch, BuildHooks.attribution(hook), start);
            queued = true;
        } catch (Violations refused) {
            HookRequests.sendJson(exchange, 409, "{\"error\":\"not deployable now\"}");
            return;
        } finally {
            if (!queued) {
                // Nothing was accepted: the deploy settled its own gate, and the shared budget gets its token back.
                RateLimitMiddleware.limiter().refund(hookTotalKey(hook.get(BuildHookModel.ID)), total,
                    HOOK_TOTAL_POLICY.requests(), HOOK_TOTAL_POLICY.window());
            }
        }
        Blast.log("BUILD HOOK: deploy queued for application", application.get(InstanceModel.NAME),
            "(id:", applicationId + ") by hook", hook.get(BuildHookModel.NAME));
        HookRequests.sendJson(exchange, 202, "{\"status\":\"queued\"}");
    }

    /**
     * Spends one deploy from the hook's budget shared by every client.
     *
     * AIDEV-NOTE: zenit's raw-exchange lane (ExchangeRateLimits) budgets only the CLIENT, so this spends the shared
     * bucket store directly: the family is the policy scoped to the hook, and every caller is the one subject
     * RateLimitKeys spells for no address, which makes the hook alone the key. No security event is reported: the
     * clients of a spent hook are not known to be the abusers. A subject-keyed exchange budget belongs in zenit.
     *
     * @return the decision; a refused one must be answered without queueing anything
     */
    public static RateLimiter.@NonNull Decision spendHookTotal(@NonNull Object hookId) {
        return RateLimitMiddleware.limiter().tryAcquire(hookTotalKey(hookId), HOOK_TOTAL_POLICY.requests(),
            HOOK_TOTAL_POLICY.window());
    }

    /** @return the bucket key of the hook's budget shared by every client */
    private static @NonNull String hookTotalKey(@NonNull Object hookId) {
        return RateLimitKeys.of(RateLimitKeys.clientFamily(HOOK_TOTAL_POLICY, String.valueOf(hookId)),
            RateLimitKeys.clientSubject(null));
    }

    /** Answers a refused budget: 429 with when to retry. */
    private static void limited(@NonNull HttpServerExchange exchange, long retryAfterSeconds) {
        ExchangeRateLimits.stampRetryAfter(exchange, retryAfterSeconds);
        HookRequests.sendJson(exchange, 429, "{\"error\":\"rate limited\"}");
    }

    /** @return the trimmed {@code branch} query parameter, null when absent or blank */
    private static @Nullable String branchOf(@NonNull HttpServerExchange exchange) {
        Deque<String> values = exchange.getQueryParameters().get(BRANCH_PARAM);
        return values == null || values.isEmpty() ? null : Texts.trimmedOrNull(values.getFirst());
    }

    private static void refuse(@NonNull HttpServerExchange exchange) {
        HookRequests.sendJson(exchange, 404, REFUSAL_BODY);
    }
}
