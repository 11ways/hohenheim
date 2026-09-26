package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.validation.Violations;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static be.elevenways.hohenheim.test.ProxyTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Host + path routing: several sites can share one hostname when their path
 * prefixes differ, selection is longest-prefix with segment boundaries, and
 * duplicate claims are refused deterministically.
 */
class PathRoutingTest {

    private static boolean initialized = false;
    private ProxyServer proxy;
    private final List<HttpServer> upstreams = new ArrayList<>();

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
        for (HttpServer upstream : upstreams) {
            upstream.stop(0);
        }
        upstreams.clear();
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.FALLBACK_ADDRESS, "");
    }

    /** Upstream that answers with the given tag and records the path it received. */
    private int startUpstream(String tag, AtomicReference<String> seenPath) throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            if (seenPath != null) {
                seenPath.set(ex.getRequestURI().getPath());
            }
            byte[] body = tag.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
        upstreams.add(upstream);
        return upstream.getAddress().getPort();
    }

    private static Map<String, Object> proxySettings(int port) {
        return Map.of("forward_host", "127.0.0.1", "forward_port", port);
    }

    @Test
    void sameHostnameRoutesByLongestPathPrefix() throws Exception {
        resetDatabase();

        int apiPort = startUpstream("api-upstream", null);
        int apiV2Port = startUpstream("api-v2-upstream", null);
        int rootPort = startUpstream("root-upstream", null);

        Row apiSite = setupSite("hohenheim:address", "Api Site", "api-site", proxySettings(apiPort));
        addDomain(apiSite, "paths.test", "exact", "/api", false);

        Row apiV2Site = setupSite("hohenheim:address", "Api V2 Site", "api-v2-site", proxySettings(apiV2Port));
        addDomain(apiV2Site, "paths.test", "exact", "/api/v2", false);

        Row rootSite = setupSite("hohenheim:address", "Root Site", "root-site", proxySettings(rootPort));
        addDomain(rootSite, "paths.test", "exact", null, false);

        proxy = startProxy();
        int port = httpPort(proxy);

        assertThat(rawRequest(port, "paths.test", "/api/users")).contains("api-upstream");
        assertThat(rawRequest(port, "paths.test", "/api/v2/users")).contains("api-v2-upstream");
        assertThat(rawRequest(port, "paths.test", "/api")).contains("api-upstream");
        assertThat(rawRequest(port, "paths.test", "/")).contains("root-upstream");
        assertThat(rawRequest(port, "paths.test", "/other")).contains("root-upstream");

        // Segment boundary: /apix must NOT match the /api prefix.
        assertThat(rawRequest(port, "paths.test", "/apix")).contains("root-upstream");
    }

    @Test
    void knownHostnameWithoutMatchingPathIs404NotFallback() throws Exception {
        resetDatabase();
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.FALLBACK_ADDRESS,
            "https://fallback.example/");

        int apiPort = startUpstream("api-upstream", null);
        Row apiSite = setupSite("hohenheim:address", "Api Only Site", "api-only-site",
            proxySettings(apiPort));
        addDomain(apiSite, "apionly.test", "exact", "/api", false);

        proxy = startProxy();
        int port = httpPort(proxy);

        // Path matches: proxied.
        assertThat(rawRequest(port, "apionly.test", "/api/x")).contains("api-upstream");

        // Known hostname, wrong path: a real 404, never the catch-all fallback redirect.
        String miss = rawRequest(port, "apionly.test", "/nope");
        assertThat(miss).contains("404");
        assertThat(miss).doesNotContain("fallback.example");

        // Unknown hostname still falls back.
        String unknown = rawRequest(port, "unknown.test", "/nope");
        assertThat(unknown).contains("302");
        assertThat(unknown).contains("fallback.example");
    }

    @Test
    void stripPathRemovesThePrefixBeforeForwarding() throws Exception {
        resetDatabase();

        AtomicReference<String> seenPath = new AtomicReference<>();
        int port = startUpstream("strip-upstream", seenPath);

        Row site = setupSite("hohenheim:address", "Strip Site", "strip-site", proxySettings(port));
        addDomain(site, "strip.test", "exact", "/service", true);

        proxy = startProxy();

        assertThat(rawRequest(httpPort(proxy), "strip.test", "/service/deep/call"))
            .contains("strip-upstream");
        assertThat(seenPath.get()).isEqualTo("/deep/call");

        rawRequest(httpPort(proxy), "strip.test", "/service");
        assertThat(seenPath.get()).isEqualTo("/");
    }

    /**
     * strip_path forwards the client's OWN encoding of the remainder and never a decoded one:
     * a decoded remainder written into the upstream request line let %0d%0a inject headers,
     * %20 split the line, %3F move the query boundary and %2525 decode twice.
     */
    @Test
    void stripPathForwardsTheRawEncodingAndRefusesControlCharacters() throws Exception {
        resetDatabase();

        AtomicReference<String> seenRawPath = new AtomicReference<>();
        AtomicReference<String> seenRawQuery = new AtomicReference<>();
        AtomicReference<String> injected = new AtomicReference<>();
        List<String> hits = new ArrayList<>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            synchronized (hits) {
                hits.add(ex.getRequestURI().getRawPath());
            }
            seenRawPath.set(ex.getRequestURI().getRawPath());
            seenRawQuery.set(ex.getRequestURI().getRawQuery());
            injected.set(ex.getRequestHeaders().getFirst("X-Injected"));
            byte[] body = "raw-upstream".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
        upstreams.add(upstream);

        Row site = setupSite("hohenheim:address", "Raw Strip Site", "raw-strip-site",
            proxySettings(upstream.getAddress().getPort()));
        addDomain(site, "rawstrip.test", "exact", "/service", true);
        proxy = startProxy();
        int port = httpPort(proxy);

        // Step 1: every encoded byte of the remainder reaches the upstream exactly as sent.
        assertThat(rawRequest(port, "rawstrip.test", "/service/a%20b%3Fc%2525d/e?q=1"))
            .as("step 1: the stripped request is served").contains("raw-upstream");
        assertThat(seenRawPath.get())
            .as("step 1: %20, %3F and %2525 stay encoded; the prefix alone is gone")
            .isEqualTo("/a%20b%3Fc%2525d/e");
        assertThat(seenRawQuery.get())
            .as("step 1: the real query is the only query").isEqualTo("q=1");

        // Step 2: an encoded CR/LF is refused before any upstream is dialed.
        int before = hits.size();
        assertThat(rawRequest(port, "rawstrip.test", "/service/a%0d%0aX-Injected:%20yes"))
            .as("step 2: a decoded control character in the path is a 400").contains("400");
        assertThat(rawRequest(port, "rawstrip.test", "/service/a%00b"))
            .as("step 2: so is NUL").contains("400");
        assertThat(hits.size()).as("step 2: nothing reached the upstream").isEqualTo(before);
        assertThat(injected.get()).as("step 2: no header was ever injected").isNull();

        // Step 3: the prefix match is on the canonical path, the cut on the raw one, so an
        // encoded or doubled separator inside the prefix still strips exactly the prefix.
        assertThat(rawRequest(port, "rawstrip.test", "//service/x%2Fy"))
            .as("step 3: a doubled separator still selects the stripped route")
            .contains("raw-upstream");
        assertThat(seenRawPath.get())
            .as("step 3: the remainder keeps its own spelling").isEqualTo("/x%2Fy");
    }

    /**
     * A strip_path upstream thinks it lives at the root, so its redirects lack the prefix; the
     * rewritten Location must put the prefix back instead of sending the browser elsewhere.
     */
    @Test
    void stripPathRedirectsKeepThePrefix() throws Exception {
        resetDatabase();

        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int upstreamPort = upstream.getAddress().getPort();
        upstream.createContext("/", ex -> {
            String location = ex.getRequestURI().getPath().equals("/absolute")
                ? "http://127.0.0.1:" + upstreamPort + "/login?next=1"
                : "/login";
            ex.getResponseHeaders().add("Location", location);
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        upstream.start();
        upstreams.add(upstream);

        Row site = setupSite("hohenheim:address", "Prefix Redirect Site", "prefix-redirect-site",
            proxySettings(upstreamPort));
        addDomain(site, "prefix.test", "exact", "/api", true);
        proxy = startProxy();
        int port = httpPort(proxy);

        // Step 1: a backend-absolute redirect becomes the public host WITH the prefix.
        assertThat(rawRequest(port, "prefix.test", "/api/absolute"))
            .as("step 1: http://backend/login is /api/login on the public side")
            .contains("Location: http://prefix.test/api/login?next=1");

        // Step 2: an origin-relative redirect gets the prefix too.
        assertThat(rawRequest(port, "prefix.test", "/api/relative"))
            .as("step 2: /login is /api/login on the public side")
            .contains("Location: /api/login");
    }

    @Test
    void aSecondLiveSiteCannotTakeAnAlreadyClaimedRoute() throws Exception {
        resetDatabase();

        int firstPort = startUpstream("first-upstream", null);
        int secondPort = startUpstream("second-upstream", null);

        // 1. "A Site" goes live on dup.test/api and claims that route.
        Row first = setupSite("hohenheim:address", "A Site", "a-site", proxySettings(firstPort));
        addDomain(first, "dup.test", "exact", "/api", false);

        // 2. A second LIVE site claiming the identical (hostname, path, listener) tuple is
        //    refused by the write pipeline. This used to be accepted and then resolved
        //    first-wins at the dispatcher, which left the second operator with a site that
        //    was enabled, reachable nowhere, and told nothing.
        Row second = setupSite("hohenheim:address", "B Site", "b-site", proxySettings(secondPort));
        assertThatThrownBy(() -> addDomain(second, "dup.test", "exact", "/api", false))
            .as("step 2: the duplicate route is refused, not silently shadowed")
            .isInstanceOf(Violations.class)
            .hasMessageContaining("route_taken_other_site");

        // 3. The route is the whole tuple, not the hostname: a DIFFERENT path on the same
        //    hostname is a different route and stays legal.
        addDomain(second, "dup.test", "exact", "/other", false);

        // 4. Both live routes resolve to their own site.
        proxy = startProxy();
        assertThat(rawRequest(httpPort(proxy), "dup.test", "/api/x"))
            .as("step 4: the incumbent still owns /api").contains("first-upstream");
        assertThat(rawRequest(httpPort(proxy), "dup.test", "/other/x"))
            .as("step 4: the accepted sibling route reaches the second site")
            .contains("second-upstream");
    }

    @Test
    void wildcardHostsAlsoRouteByPath() throws Exception {
        resetDatabase();

        int apiPort = startUpstream("wild-api", null);
        int rootPort = startUpstream("wild-root", null);

        Row apiSite = setupSite("hohenheim:address", "Wild Api", "wild-api", proxySettings(apiPort));
        addDomain(apiSite, "*.wild.test", "wildcard", "/api", false);

        Row rootSite = setupSite("hohenheim:address", "Wild Root", "wild-root", proxySettings(rootPort));
        addDomain(rootSite, "*.wild.test", "wildcard", null, false);

        proxy = startProxy();
        int port = httpPort(proxy);

        assertThat(rawRequest(port, "a.wild.test", "/api/x")).contains("wild-api");
        assertThat(rawRequest(port, "a.wild.test", "/home")).contains("wild-root");
    }
}
