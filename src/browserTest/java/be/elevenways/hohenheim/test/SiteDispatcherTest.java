package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static be.elevenways.hohenheim.test.ProxyTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dispatcher-level tests: trusted-remote-proxy client-IP propagation
 * (X-Hohenheim-Key) and full glob hostname routing.
 */
class SiteDispatcherTest {

    private static boolean initialized = false;
    private ProxyServer proxy;
    private HttpServer upstream;
    private final List<HttpServer> markerUpstreams = new ArrayList<>();

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) return;
        initialized = true;
        bootRuntime();
    }

    @AfterEach
    void cleanup() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        if (upstream != null) {
            upstream.stop(0);
            upstream = null;
        }
        for (HttpServer marker : markerUpstreams) {
            marker.stop(0);
        }
        markerUpstreams.clear();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, List.of());
    }

    private record ProxyHeaders(String realIp, String forwardedFor, String forwardedProto,
                                String forwardedHost, String hohenheimKey) {}

    /** Start an upstream that records the forwarding trust-boundary headers. */
    private int startCapturingUpstream(AtomicReference<ProxyHeaders> captured) throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            captured.set(new ProxyHeaders(
                ex.getRequestHeaders().getFirst("X-Real-IP"),
                ex.getRequestHeaders().getFirst("X-Forwarded-For"),
                ex.getRequestHeaders().getFirst("X-Forwarded-Proto"),
                ex.getRequestHeaders().getFirst("X-Forwarded-Host"),
                ex.getRequestHeaders().getFirst("X-Hohenheim-Key")));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
        return upstream.getAddress().getPort();
    }

    /** Start an upstream that copies the FULL inbound request header map for assertions. */
    private int startHeaderCapturingUpstream(
            AtomicReference<com.sun.net.httpserver.Headers> captured) throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            com.sun.net.httpserver.Headers copy = new com.sun.net.httpserver.Headers();
            copy.putAll(ex.getRequestHeaders());
            captured.set(copy);
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
        return upstream.getAddress().getPort();
    }

    /**
     * Finding 1: the forwarding trust boundary is wider than the four canonicalized names. An
     * anonymous client's RFC 7239 Forwarded / X-Forwarded-* aliases / CDN client-IP headers /
     * IIS URL-rewrite pair must NOT survive to the upstream, or a tenant framework trusts a
     * forged origin. Asserts ABSENCE at the upstream, not the presence of the four we keep.
     */
    @Test
    void forwardedHeaderAliasFamilyIsStrippedAtUpstream() throws Exception {
        resetDatabase();
        AtomicReference<com.sun.net.httpserver.Headers> captured = new AtomicReference<>();
        int upstreamPort = startHeaderCapturingUpstream(captured);
        setupSiteWithDomain("hohenheim:address", "alias.test", "exact",
            Map.of("forward_host", "127.0.0.1", "forward_port", upstreamPort));
        proxy = startProxy();

        // Each alias carries a DISTINCTIVE forged value; the security property is that no forged
        // value survives to the upstream -- whether the header is stripped outright or (for the
        // ones Undertow's own X-Forwarded support regenerates, e.g. -Port/-Server) overwritten
        // with the true connection value. Asserting forged-value absence is robust to which of
        // the two happens per header.
        Map<String, String> forged = new java.util.LinkedHashMap<>();
        forged.put("Forwarded", "for=1.2.3.4;proto=https;host=evil.test");
        forged.put("X-Forwarded-Port", "8443");
        forged.put("X-Forwarded-Prefix", "/admin");
        forged.put("X-Forwarded-Server", "evil.test");
        forged.put("X-Forwarded-Scheme", "https");
        forged.put("X-Forwarded-Ssl", "on");
        forged.put("True-Client-IP", "1.2.3.4");
        forged.put("CF-Connecting-IP", "1.2.3.4");
        forged.put("X-Client-IP", "1.2.3.4");
        forged.put("X-Original-URL", "/admin/secret");
        forged.put("X-Rewrite-URL", "/admin/secret");

        List<String> headerLines = new ArrayList<>();
        forged.forEach((name, value) -> headerLines.add(name + ": " + value));
        String response = rawRequest(httpPort(proxy), "alias.test", "/",
            headerLines.toArray(new String[0]));

        assertThat(response).contains("200");
        com.sun.net.httpserver.Headers h = captured.get();
        assertThat(h).as("upstream was reached").isNotNull();
        forged.forEach((name, value) ->
            assertThat(h.getFirst(name)).as("forged forwarding value survived to upstream: " + name)
                .isNotEqualTo(value));
        // The forged host must not have leaked into the host hohenheim regenerates either.
        assertThat(h.getFirst("X-Forwarded-Host")).isEqualTo("alias.test");
        assertThat(h.getFirst("X-Forwarded-Proto")).isEqualTo("http");
    }

    /**
     * Finding 2: hop-by-hop request headers (RFC 7230 6.1) must not reach an upstream, and a
     * Connection: <token> must drop the client-named header. Asserts each is absent upstream.
     */
    @Test
    void hopByHopHeadersAreNotForwardedToUpstream() throws Exception {
        resetDatabase();
        AtomicReference<com.sun.net.httpserver.Headers> captured = new AtomicReference<>();
        int upstreamPort = startHeaderCapturingUpstream(captured);
        setupSiteWithDomain("hohenheim:address", "hop.test", "exact",
            Map.of("forward_host", "127.0.0.1", "forward_port", upstreamPort));
        proxy = startProxy();

        String response = rawRequest(httpPort(proxy), "hop.test", "/",
            "Connection: X-Secret-Hop",
            "X-Secret-Hop: leak-me",
            "Proxy-Authorization: Basic c3B5Cg==",
            "TE: trailers",
            "Keep-Alive: timeout=5");

        assertThat(response).contains("200");
        com.sun.net.httpserver.Headers h = captured.get();
        assertThat(h).as("upstream was reached").isNotNull();
        assertThat(h.getFirst("X-Secret-Hop")).as("Connection-named header dropped").isNull();
        assertThat(h.getFirst("Proxy-Authorization")).isNull();
        assertThat(h.getFirst("TE")).isNull();
        assertThat(h.getFirst("Keep-Alive")).isNull();
    }

    /**
     * Finding 4: with websocket_upgrade=false the gate must refuse ANY upgrade attempt, not just
     * the exact "Upgrade: websocket" spelling. A comma-list whose first value is not "websocket"
     * slipped past the old exact-match check and let the upstream's 101 tunnel anyway.
     */
    @Test
    void websocketDisabledRefusesAnyUpgradeSpelling() throws Exception {
        resetDatabase();
        int markerPort = startMarkerUpstream("forwarded-anyway");
        setupSiteWithDomain("hohenheim:address", "nows.test", "exact",
            Map.of("forward_host", "127.0.0.1", "forward_port", markerPort,
                "websocket_upgrade", false));
        proxy = startProxy();

        String response = rawRequest(httpPort(proxy), "nows.test", "/",
            "Connection: Upgrade",
            "Upgrade: h2c, websocket");

        assertThat(response).contains("403").doesNotContain("forwarded-anyway");
    }

    @Test
    void trustedKeyPropagatesTheRealClientIp() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS,
            List.of("front-key-1", "front-key-2"));

        AtomicReference<ProxyHeaders> captured = new AtomicReference<>();
        int upstreamPort = startCapturingUpstream(captured);

        setupSiteWithDomain("hohenheim:address", "trusted.test", "exact",
            Map.of("forward_host", "127.0.0.1", "forward_port", upstreamPort));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "trusted.test", "/",
            "X-Hohenheim-Key: front-key-2",
            "X-Real-IP: 203.0.113.9");

        assertThat(response).contains("200");
        assertThat(captured.get().realIp()).isEqualTo("203.0.113.9");
        // The real client leads, the directly-connected trusted proxy is appended as the hop.
        assertThat(captured.get().forwardedFor().replace(" ", ""))
            .isEqualTo("203.0.113.9,127.0.0.1");
        assertThat(captured.get().hohenheimKey()).isNull();
    }

    @Test
    void untrustedForwardingHeadersAreRejectedAndRegenerated() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS,
            List.of("front-key-1"));

        AtomicReference<ProxyHeaders> captured = new AtomicReference<>();
        int upstreamPort = startCapturingUpstream(captured);

        setupSiteWithDomain("hohenheim:address", "spoof.test", "exact",
            Map.of("forward_host", "127.0.0.1", "forward_port", upstreamPort));

        proxy = startProxy();

        String response = rawRequest(httpPort(proxy), "spoof.test", "/",
            "X-Hohenheim-Key: wrong-key",
            "X-Real-IP: 203.0.113.9",
            "X-Forwarded-For: 198.51.100.1, 203.0.113.9",
            "X-Forwarded-Proto: https",
            "X-Forwarded-Host: attacker.test");

        assertThat(response).contains("200");
        assertThat(captured.get()).isEqualTo(new ProxyHeaders(
            "127.0.0.1", "127.0.0.1", "http", "spoof.test", null));
    }

    @Test
    void trustedProxyCannotInstallANonLiteralClientIdentity() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS,
            List.of("front-key-1"));
        AtomicReference<ProxyHeaders> captured = new AtomicReference<>();
        int upstreamPort = startCapturingUpstream(captured);
        setupSiteWithDomain("hohenheim:address", "invalid-ip.test", "exact",
            Map.of("forward_host", "127.0.0.1", "forward_port", upstreamPort));
        proxy = startProxy();

        String response = rawRequest(httpPort(proxy), "invalid-ip.test", "/",
            "X-Hohenheim-Key: front-key-1", "X-Real-IP: attacker.example");

        assertThat(response).contains("200");
        assertThat(captured.get().realIp()).isEqualTo("127.0.0.1");
        assertThat(captured.get().forwardedFor()).isEqualTo("127.0.0.1");
    }

    @Test
    void trustedRequestAppendsToExistingForwardedFor() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS,
            List.of("front-key-1"));

        AtomicReference<ProxyHeaders> captured = new AtomicReference<>();
        int upstreamPort = startCapturingUpstream(captured);

        setupSiteWithDomain("hohenheim:address", "chain.test", "exact",
            Map.of("forward_host", "127.0.0.1", "forward_port", upstreamPort));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "chain.test", "/",
            "X-Hohenheim-Key: front-key-1",
            "X-Real-IP: 203.0.113.9",
            "X-Forwarded-For: 198.51.100.1, 203.0.113.9",
            "X-Forwarded-Proto: https",
            "X-Forwarded-Host: attacker.test");

        assertThat(response).contains("200");
        assertThat(captured.get().realIp()).isEqualTo("203.0.113.9");
        assertThat(captured.get().forwardedFor().replace(" ", ""))
            .isEqualTo("198.51.100.1,203.0.113.9,127.0.0.1");
        assertThat(captured.get().forwardedProto()).isEqualTo("https");
        assertThat(captured.get().forwardedHost()).isEqualTo("attacker.test");
        assertThat(captured.get().hohenheimKey()).isNull();
    }

    @Test
    void websocketUpgradeDefaultsToEnabledWhenSettingIsAbsent() throws Exception {
        resetDatabase();
        setupSiteWithDomain("hohenheim:address", "websocket-default.test", "exact",
            Map.of("forward_host", "127.0.0.1", "forward_port", 1));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "websocket-default.test", "/",
            "Connection: Upgrade",
            "Upgrade: websocket",
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
            "Sec-WebSocket-Version: 13");

        assertThat(response).contains("503").doesNotContain("403");
    }

    /** Serve a fixed marker body so an assertion can name WHICH site answered. */
    private int startMarkerUpstream(String marker) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = marker.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        markerUpstreams.add(server);
        return server.getAddress().getPort();
    }

    /**
     * Route identity across match types: an exact and a wildcard row spelling the SAME
     * literal hostname are one contested route (the runtime backstop must agree with
     * RouteClaims.keyOf), while an exact host inside a COVERING wildcard stays the legal
     * nginx-style carve-out.
     */
    @Test
    void exactAndWildcardSpellingsOfOneHostnameAreOneContestedRoute() throws Exception {
        resetDatabase();

        int wildcardUpstream = startMarkerUpstream("owned-by-wildcard-site");
        int exactUpstream = startMarkerUpstream("owned-by-exact-site");
        int carveExactUpstream = startMarkerUpstream("owned-by-carve-exact");
        int carveWildcardUpstream = startMarkerUpstream("owned-by-carve-wildcard");

        // 1. A live site claims "shadow.test" as a metachar-free WILDCARD literal. Sites
        //    load in NAME order, so the "aaa" prefix makes this the first claimant the
        //    dispatcher sees.
        Row wildcardSite = setupSite("hohenheim:address", "aaa-shadow-wildcard",
            "aaa-shadow-wildcard",
            Map.of("forward_host", "127.0.0.1", "forward_port", wildcardUpstream));
        addDomain(wildcardSite, "shadow.test", "wildcard", null, false);
        assertThat((String) Models.get(SiteDomainModel.class).find()
                .where(SiteDomainModel.SITE_ID.eq(wildcardSite.get(SiteModel.ID))).first()
                .get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 1: the wildcard-literal row holds the live route claim").isNotNull();

        // 2. A second live site holds an EXACT row on the same literal hostname, planted
        //    in the legacy shape the M045 backfill leaves behind: live but UNCLAIMED (the
        //    set-based update bypasses the write-time claim guard exactly like pre-claim
        //    data predates it). This is the shape the dispatcher backstop exists for.
        Row exactSite = setupSite("hohenheim:address", "bbb-shadow-exact", "bbb-shadow-exact",
            Map.of("forward_host", "127.0.0.1", "forward_port", exactUpstream));
        addDomain(exactSite, "shadow-staging.test", "exact", null, false);
        Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.SITE_ID.eq(exactSite.get(SiteModel.ID)))
            .assign(SiteDomainModel.HOSTNAME, "shadow.test")
            .assign(SiteDomainModel.LIVE_ROUTE_KEY, null)
            .updateAll();
        Row planted = Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.SITE_ID.eq(exactSite.get(SiteModel.ID))).first();
        assertThat((String) planted.get(SiteDomainModel.HOSTNAME))
            .as("step 2: the legacy exact row spells the contested hostname")
            .isEqualTo("shadow.test");
        assertThat((String) planted.get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 2: the legacy row is live but unclaimed, the backfill loser shape")
            .isNull();

        // 3. A genuine carve-out pair on ANOTHER domain family: an exact host plus a
        //    COVERING wildcard. Different canonical hostnames, so two legal routes.
        Row carveExactSite = setupSite("hohenheim:address", "ccc-carve-exact", "ccc-carve-exact",
            Map.of("forward_host", "127.0.0.1", "forward_port", carveExactUpstream));
        addDomain(carveExactSite, "app.carve.test", "exact", null, false);
        Row carveWildcardSite = setupSite("hohenheim:address", "ddd-carve-wildcard",
            "ddd-carve-wildcard",
            Map.of("forward_host", "127.0.0.1", "forward_port", carveWildcardUpstream));
        addDomain(carveWildcardSite, "*.carve.test", "wildcard", null, false);

        proxy = startProxy();

        // 4. THE contested route: both spellings resolve to the same requests, so they
        //    are ONE route and only the FIRST claimant may serve it. Before the fix the
        //    kind-prefixed runtime key saw two distinct routes, silently added both, and
        //    the exact tier handed the host to the site that lost the claim.
        assertThat(rawRequest(httpPort(proxy), "shadow.test", "/"))
            .as("step 4: the contested literal hostname is served by the first claimant, "
                + "not silently taken over by the unclaimed exact row")
            .contains("owned-by-wildcard-site");

        // 5. The carve-out exact host still beats its covering wildcard: different
        //    canonical hostnames never contest each other.
        assertThat(rawRequest(httpPort(proxy), "app.carve.test", "/"))
            .as("step 5: the exact carve-out wins its own hostname inside the wildcard")
            .contains("owned-by-carve-exact");

        // 6. Every other host under the wildcard still routes to the wildcard site.
        assertThat(rawRequest(httpPort(proxy), "other.carve.test", "/"))
            .as("step 6: the covering wildcard serves the rest of its family")
            .contains("owned-by-carve-wildcard");
    }

    @Test
    void globHostnamesRouteThroughTheDispatcher() throws Exception {
        resetDatabase();

        AtomicReference<ProxyHeaders> captured = new AtomicReference<>();
        int upstreamPort = startCapturingUpstream(captured);

        setupSiteWithDomain("hohenheim:address", "eu?.wild.test", "wildcard",
            Map.of("forward_host", "127.0.0.1", "forward_port", upstreamPort));

        proxy = startProxy();

        assertThat(rawRequest(httpPort(proxy), "eu1.wild.test", "/")).contains("200");
        // ? is exactly one character: a two-character suffix must miss.
        assertThat(rawRequest(httpPort(proxy), "eu12.wild.test", "/")).contains("404");
    }
}
