package be.elevenways.hohenheim.server.proxy.auth;

import be.elevenways.hohenheim.auth.SiteAuthDecision;
import be.elevenways.zenit.common.http.RateLimiter;
import be.elevenways.zenit.common.routing.RateLimitPolicy;
import be.elevenways.zenit.server.http.ExchangeRateLimits;
import io.undertow.server.HttpServerExchange;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;

/**
 * THE per-client budget for proxy-auth verification work: every argon2 verify and every outbound
 * identity-provider call a gate performs for a request without an accepted session spends one token.
 *
 * AIDEV-NOTE: the proxy port is not a Zenit endpoint, so this rides zenit's exchange lane
 * ({@link ExchangeRateLimits}): the framework's bucket store and key, the client the dispatcher
 * vouched for, never the raw socket peer (behind a trusted front that is the front itself). The
 * bucket is per site as well: one busy site cannot starve another. A token is spent BEFORE the
 * verification and whatever it concludes, so a spent client is refused without verifying anything.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class ProxyAuthThrottle {

    /** Verification attempts one client may make against one site per window. */
    public static final RateLimitPolicy POLICY = RateLimitPolicy.of(60, Duration.ofMinutes(1))
        .keyBy(RateLimitPolicy.KeyBy.IP)
        .named("hohenheim.proxy_auth");

    private ProxyAuthThrottle() {
    }

    /**
     * Spend one verification token for this request's client on this site.
     *
     * @return null when the attempt may proceed; otherwise the 429 to send, with Retry-After set
     */
    public static @Nullable SiteAuthDecision spend(@NonNull HttpServerExchange exchange, int siteId) {
        Long retryAfter = spendFor(exchange, siteId);
        return retryAfter == null ? null : refusal(exchange, retryAfter);
    }

    /**
     * Spend one verification token for this request's client on this site, writing nothing: for a
     * caller that decides only later whether the refusal is the answer (an access-rule leaf).
     *
     * @return null when the attempt may proceed; otherwise the seconds until it may
     */
    public static @Nullable Long spendFor(@NonNull HttpServerExchange exchange, int siteId) {
        RateLimiter.Decision decision = ExchangeRateLimits.tryAcquire(exchange, POLICY, String.valueOf(siteId));
        return decision.allowed() ? null : decision.retryAfterSeconds();
    }

    /** @return the 429 for a spent budget, with Retry-After set on the exchange */
    public static @NonNull SiteAuthDecision refusal(@NonNull HttpServerExchange exchange, long retryAfterSeconds) {
        ExchangeRateLimits.stampRetryAfter(exchange, retryAfterSeconds);
        return SiteAuthDecision.deny(429, "Too Many Requests");
    }
}
