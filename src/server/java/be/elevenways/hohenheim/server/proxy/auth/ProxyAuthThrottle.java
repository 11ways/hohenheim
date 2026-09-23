package be.elevenways.hohenheim.server.proxy.auth;

import be.elevenways.hohenheim.auth.SiteAuthDecision;
import be.elevenways.hohenheim.server.proxy.ResolvedClientIp;
import be.elevenways.zenit.common.http.RateLimitKeys;
import be.elevenways.zenit.common.http.RateLimiter;
import be.elevenways.zenit.common.routing.RateLimitPolicy;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;

/**
 * THE per-client budget for proxy-auth verification work: every argon2 verify and every outbound
 * identity-provider call a gate performs for a request without an accepted session spends one token.
 *
 * AIDEV-NOTE: the proxy port is not a Zenit endpoint, so zenit's RateLimitMiddleware (conduit-only)
 * cannot run here; this rides the framework's own bucket store, key derivation and policy type
 * instead of a private counter. The bucket is per site AND per trusted-proxy-resolved client IP:
 * one busy site cannot starve another, and the IP is the one ResolvedClientIp carries, never the
 * raw socket peer (behind a trusted front that is the front itself).
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class ProxyAuthThrottle {

    /** Verification attempts one client may make against one site per window. */
    public static final RateLimitPolicy POLICY = RateLimitPolicy.of(60, Duration.ofMinutes(1))
        .keyBy(RateLimitPolicy.KeyBy.IP)
        .named("hohenheim.proxy_auth");

    private static final RateLimiter LIMITER = new RateLimiter();

    private ProxyAuthThrottle() {
    }

    /**
     * Spend one verification token for this request's client on this site.
     *
     * @return null when the attempt may proceed; otherwise the 429 to send, with Retry-After set
     */
    public static @Nullable SiteAuthDecision spend(@NonNull HttpServerExchange exchange, int siteId) {
        String subject = RateLimitKeys.subject(POLICY, null, ResolvedClientIp.get(exchange));
        String key = RateLimitKeys.of(POLICY.bucketName() + ":" + siteId, subject);
        RateLimiter.Decision decision = LIMITER.tryAcquire(key, POLICY.requests(), POLICY.window());
        if (decision.allowed()) {
            return null;
        }
        exchange.getResponseHeaders().put(Headers.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        return SiteAuthDecision.deny(429, "Too Many Requests");
    }

    /** Forget every bucket, so a test starts from a full budget. */
    public static void clearForTests() {
        LIMITER.clear();
    }
}
