package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.auth.SiteAuthDecision;
import be.elevenways.hohenheim.server.proxy.ResolvedClientIp;
import be.elevenways.hohenheim.server.proxy.auth.ProxyAuthThrottle;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The proxy-auth verification budget: per site, per trusted-proxy-resolved client IP, answered
 * with a 429 and Retry-After once spent.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
class ProxyAuthThrottleTest {

    @Test
    void oneClientSpendsItsOwnBudgetPerSite() {
        ProxyAuthThrottle.clearForTests();
        int budget = ProxyAuthThrottle.POLICY.requests();

        // 1. A client may verify up to the budget.
        for (int attempt = 1; attempt <= budget; attempt++) {
            assertThat(ProxyAuthThrottle.spend(exchangeFrom("203.0.113.9"), 1))
                .as("step 1: attempt %d of %d proceeds", attempt, budget).isNull();
        }

        // 2. The next attempt is refused with a 429 that says when to come back.
        HttpServerExchange refused = exchangeFrom("203.0.113.9");
        SiteAuthDecision decision = ProxyAuthThrottle.spend(refused, 1);
        assertThat(decision).as("step 2: over budget is refused").isInstanceOf(SiteAuthDecision.Deny.class);
        assertThat(((SiteAuthDecision.Deny) decision).statusCode()).as("step 2: as 429").isEqualTo(429);
        assertThat(refused.getResponseHeaders().getFirst(Headers.RETRY_AFTER))
            .as("step 2: with a Retry-After").isNotBlank();

        // 3. Another client, and the same client on another site, keep their own budgets.
        assertThat(ProxyAuthThrottle.spend(exchangeFrom("198.51.100.4"), 1))
            .as("step 3: another client is not affected").isNull();
        assertThat(ProxyAuthThrottle.spend(exchangeFrom("203.0.113.9"), 2))
            .as("step 3: another site is not affected").isNull();
        ProxyAuthThrottle.clearForTests();
    }

    /** An exchange whose resolved client IP is the given one, as the dispatcher attaches it. */
    private static HttpServerExchange exchangeFrom(String clientIp) {
        HttpServerExchange exchange = new HttpServerExchange(null);
        ResolvedClientIp.attach(exchange, clientIp);
        return exchange;
    }
}
