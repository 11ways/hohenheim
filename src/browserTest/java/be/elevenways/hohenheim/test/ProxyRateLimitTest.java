package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.proxy.auth.ProxyAuthThrottle;
import be.elevenways.hohenheim.server.source.GitWebhookHandler;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RateLimitPolicy;
import be.elevenways.zenit.server.http.ExchangeRateLimits;
import be.elevenways.zenit.server.http.RateLimitMiddleware;
import be.elevenways.zenit.server.http.TrustedProxies;
import io.undertow.server.HttpServerExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The proxy port's per-client budgets over a real listener: the verification budget of a gated
 * site and the git webhook's delivery budget both ride zenit's exchange lane, keyed on the client
 * the dispatcher vouched for. A key-authenticated front's clients keep their own budgets; a peer
 * without the key is keyed on itself whatever client it names.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
class ProxyRateLimitTest {

    private static final String FRONT_KEY = "front-key-rate";

    private ProxyServer proxy;
    private List<String> previousKeys;

    @BeforeEach
    void start() throws Exception {
        ProxyTestSupport.bootRuntime();
        RateLimitMiddleware.limiter().clear();
        this.previousKeys = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, List.of(FRONT_KEY));
    }

    @AfterEach
    void stop() {
        if (this.proxy != null) {
            this.proxy.stop();
        }
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, this.previousKeys);
        RateLimitMiddleware.limiter().clear();
    }

    @Test
    void aBurstFromOneClientIsLimitedWhileOthersAndSpoofersAreKeyedOnThemselves() throws Exception {
        int siteId = gatedSite("limited.test");
        this.proxy = ProxyTestSupport.startProxy();
        int port = ProxyTestSupport.httpPort(this.proxy);
        String guess = "Authorization: " + basic("alice", "wrong");

        // 1. One client behind the authenticated front is verified, then bursts through the rest of
        //    the site's budget. The burst is spent straight from the bucket: the budget refills a
        //    token per second, slower than argon2 verifies.
        assertThat(status(get(port, guess, viaFront("203.0.113.9"))))
            .as("step 1: a verification is answered").isEqualTo(401);
        drain(ProxyAuthThrottle.POLICY, "203.0.113.9", String.valueOf(siteId));

        // 2. The next attempt is refused before any verification, saying when to come back.
        String refused = get(port, guess, viaFront("203.0.113.9"));
        assertThat(status(refused)).as("step 2: the burst is limited").isEqualTo(429);
        assertThat(refused.toLowerCase(Locale.ROOT)).as("step 2: with a Retry-After").contains("retry-after:");

        // 3. Another client behind the same front keeps its own budget.
        assertThat(status(get(port, guess, viaFront("198.51.100.4"))))
            .as("step 3: another client is verified").isEqualTo(401);

        // 4. A peer without the key naming the limited client (X-Real-IP and X-Forwarded-For) is
        //    keyed on its own socket address: it is verified, not refused.
        assertThat(status(get(port, guess, "X-Real-IP: 203.0.113.9", "X-Forwarded-For: 203.0.113.9")))
            .as("step 4: an unauthenticated peer cannot borrow the limited client's key").isEqualTo(401);
        assertThat(status(get(port, guess, "X-Hohenheim-Key: wrong", "X-Real-IP: 203.0.113.9")))
            .as("step 4: nor can a peer with a wrong key").isEqualTo(401);

        // 5. The webhook's delivery budget rides the same lane: a burst from this peer is limited
        //    with a Retry-After, and naming another client in X-Forwarded-For changes nothing.
        String hook = GitWebhookHandler.PREFIX + "999000111";
        assertThat(status(post(port, hook))).as("step 5: a delivery is judged").isEqualTo(404);
        drain(GitWebhookHandler.POLICY, "127.0.0.1", null);
        String limitedHook = post(port, hook, "X-Forwarded-For: 198.51.100.77");
        assertThat(status(limitedHook)).as("step 5: the burst is limited, spoofed header or not").isEqualTo(429);
        assertThat(limitedHook.toLowerCase(Locale.ROOT)).as("step 5: with a Retry-After").contains("retry-after:");

        // 6. A client the front vouches for still delivers.
        assertThat(status(post(port, hook, viaFront("198.51.100.77"))))
            .as("step 6: another client's delivery is judged").isEqualTo(404);
    }

    /** Spends every token {@code clientIp} has left in the policy's family, as a burst of requests would. */
    private static void drain(RateLimitPolicy policy, String clientIp, String scope) {
        HttpServerExchange burst = new HttpServerExchange(null);
        TrustedProxies.vouch(burst, clientIp);
        int spent = 0;
        while (ExchangeRateLimits.tryAcquire(burst, policy, scope).allowed()) {
            assertThat(++spent).as("the burst drains within the budget").isLessThanOrEqualTo(policy.requests());
        }
    }

    /** @return the id of an address site behind a Basic gate, answering {@code hostname} */
    private static int gatedSite(String hostname) {
        var providers = Models.get(SiteAuthProviderModel.class);
        Row provider = providers.createEmptyRow();
        provider.set(SiteAuthProviderModel.NAME, "Rate Gate");
        provider.set(SiteAuthProviderModel.PROVIDER_TYPE, "hohenheim:basic");
        provider.set(SiteAuthProviderModel.CONFIG, new BasicAuthProviderType().normalizeConfigForSave(
            Map.of("credentials", Map.of("alice", "s3cret")), null));
        providers.save(provider);

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("forward_host", "127.0.0.1");
        settings.put("forward_port", 9);
        Row site = ProxyTestSupport.setupSite("hohenheim:address", "Rate Site", "rate-site", settings);
        site.set(SiteModel.AUTH_PROVIDER_ID, provider.get(SiteAuthProviderModel.ID));
        Models.get(SiteModel.class).save(site);
        ProxyTestSupport.addDomain(site, hostname, "exact", null, false);
        return site.get(SiteModel.ID);
    }

    /** The header lines of a request relayed by the key-authenticated front for {@code clientIp}. */
    private static String[] viaFront(String clientIp) {
        return new String[] {"X-Hohenheim-Key: " + FRONT_KEY, "X-Real-IP: " + clientIp};
    }

    private static String get(int port, String authorization, String... headers) throws Exception {
        String[] lines = new String[headers.length + 1];
        lines[0] = authorization;
        System.arraycopy(headers, 0, lines, 1, headers.length);
        return ProxyTestSupport.rawRequest(port, "limited.test", "/", lines);
    }

    private static String post(int port, String path, String... headers) throws Exception {
        String[] lines = new String[headers.length + 1];
        lines[0] = "Content-Length: 0";
        System.arraycopy(headers, 0, lines, 1, headers.length);
        return ProxyTestSupport.rawRequestAs(port, "POST", "limited.test", path, lines);
    }

    private static int status(String response) {
        String[] statusLine = response.split("\n", 2)[0].trim().split(" ");
        return Integer.parseInt(statusLine[1]);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
