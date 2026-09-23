package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.proxy.auth.ProxyAuthThrottle;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static be.elevenways.hohenheim.test.ProxyTestSupport.addDomain;
import static be.elevenways.hohenheim.test.ProxyTestSupport.bootRuntime;
import static be.elevenways.hohenheim.test.ProxyTestSupport.httpPort;
import static be.elevenways.hohenheim.test.ProxyTestSupport.rawRequest;
import static be.elevenways.hohenheim.test.ProxyTestSupport.setupSite;
import static be.elevenways.hohenheim.test.ProxyTestSupport.startProxy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the upstream receives of Hohenheim's own authentication material and hop chain: the
 * proxy session cookies never, a Basic header only where no Hohenheim gate consumed it, an
 * application's own cookies and bearer tokens always; and the X-Proxied-By chain grows by one
 * hop per proxy so a loop through another proxy is caught, not just a direct self-loop.
 */
class ForwardedCredentialsTest {

    private static ProxyServer proxy;
    private static HttpServer upstream;
    private static final AtomicReference<String> seenAuthorization = new AtomicReference<>();
    private static final AtomicReference<String> seenCookie = new AtomicReference<>();
    private static final AtomicReference<String> seenProxiedBy = new AtomicReference<>();

    @BeforeAll
    static void boot() throws Exception {
        bootRuntime();
        ProxyAuthThrottle.clearForTests();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            seenAuthorization.set(ex.getRequestHeaders().getFirst("Authorization"));
            seenCookie.set(ex.getRequestHeaders().getFirst("Cookie"));
            seenProxiedBy.set(ex.getRequestHeaders().getFirst("X-Proxied-By"));
            byte[] body = "upstream-ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
    }

    @AfterAll
    static void stop() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        if (upstream != null) {
            upstream.stop(0);
            upstream = null;
        }
    }

    @Test
    @Timeout(60)
    void hohenheimsOwnCredentialsNeverReachTheUpstream() throws Exception {
        Map<String, Object> forward = Map.of("forward_host", "127.0.0.1",
            "forward_port", upstream.getAddress().getPort());

        var providerModel = Models.get(SiteAuthProviderModel.class);
        Row provider = providerModel.createEmptyRow();
        provider.set(SiteAuthProviderModel.NAME, "Forwarding Basic");
        provider.set(SiteAuthProviderModel.PROVIDER_TYPE, BasicAuthProviderType.ID.toString());
        provider.set(SiteAuthProviderModel.CONFIG, new BasicAuthProviderType().normalizeConfigForSave(
            Map.of("credentials", Map.of("alice", "s3cret")), null));
        providerModel.save(provider);

        Row gated = setupSite("hohenheim:address", "Forwarding Gated", "forwarding-gated", forward);
        gated.set(SiteModel.AUTH_PROVIDER_ID, provider.get(SiteAuthProviderModel.ID));
        Models.get(SiteModel.class).save(gated);
        addDomain(gated, "gated.creds.test", "exact", null, false);
        Row open = setupSite("hohenheim:address", "Forwarding Open", "forwarding-open", forward);
        addDomain(open, "open.creds.test", "exact", null, false);

        int port = proxyPort();

        // Step 1: the verified Basic header and every Hohenheim-owned cookie stop at the proxy;
        // the application's own cookies (acpl included: no Proteus gate owns it here) pass.
        String admitted = rawRequest(port, "gated.creds.test", "/",
            "Authorization: " + basic("alice", "s3cret"),
            "Cookie: hh_site_session_id=forged; app=1; hh_site_login=pending; acpl=theirs");
        assertThat(admitted).as("step 1: the gate admitted the request").contains("upstream-ok");
        assertThat(seenAuthorization.get())
            .as("step 1: the Basic credential the gate consumed is not forwarded").isNull();
        assertThat(seenCookie.get())
            .as("step 1: only the application's cookies reach the upstream")
            .isEqualTo("app=1; acpl=theirs");

        // Step 2: riding the minted session, the session cookie itself is stripped too.
        String session = sessionCookie(admitted);
        assertThat(rawRequest(port, "gated.creds.test", "/", "Cookie: " + session + "; app=2"))
            .as("step 2: the session admits").contains("upstream-ok");
        assertThat(seenCookie.get()).as("step 2: the session cookie stays at the proxy")
            .isEqualTo("app=2");

        // Step 3: a bearer token is the upstream's own credential and passes a Basic-gated route.
        assertThat(rawRequest(port, "gated.creds.test", "/", "Cookie: " + session,
            "Authorization: Bearer app-token"))
            .as("step 3: the session admits").contains("upstream-ok");
        assertThat(seenAuthorization.get())
            .as("step 3: a non-Basic Authorization is never Hohenheim's").isEqualTo("Bearer app-token");
        assertThat(seenCookie.get()).as("step 3: nothing but the session cookie was sent").isNull();

        // Step 4: on a route no Hohenheim gate guards, a Basic header is the upstream's, but a
        // proxy session cookie is still Hohenheim's and still stripped.
        assertThat(rawRequest(port, "open.creds.test", "/", "Authorization: " + basic("bob", "pw"),
            "Cookie: hh_site_session_id=leak; theme=dark"))
            .as("step 4: the open site serves").contains("upstream-ok");
        assertThat(seenAuthorization.get()).as("step 4: the upstream's own Basic realm is untouched")
            .isEqualTo(basic("bob", "pw"));
        assertThat(seenCookie.get()).as("step 4: the proxy session cookie never leaves")
            .isEqualTo("theme=dark");
    }

    @Test
    @Timeout(60)
    void theHopChainGrowsAndALoopAnywhereInItIsRefused() throws Exception {
        Row site = setupSite("hohenheim:address", "Loop Site", "loop-site",
            Map.of("forward_host", "127.0.0.1", "forward_port", upstream.getAddress().getPort()));
        addDomain(site, "loop.creds.test", "exact", null, false);
        int port = proxyPort();

        // Step 1: this proxy APPENDS itself to the chain an earlier proxy started.
        assertThat(rawRequest(port, "loop.creds.test", "/", "X-Proxied-By: edge-proxy"))
            .as("step 1: a foreign chain passes").contains("upstream-ok");
        String chain = seenProxiedBy.get();
        assertThat(chain).as("step 1: the earlier hop is kept, not replaced")
            .startsWith("edge-proxy, ");
        String self = chain.substring("edge-proxy, ".length());
        assertThat(self).as("step 1: exactly one hop was added").doesNotContain(",").isNotBlank();

        // Step 2: this proxy's id ANYWHERE in the chain is a loop (A -> B -> A), not only first.
        assertThat(rawRequest(port, "loop.creds.test", "/",
            "X-Proxied-By: edge-proxy, " + self + ", other-proxy"))
            .as("step 2: a loop through another proxy answers 508").contains("508");

        // Step 3: an id that merely CONTAINS ours is a different proxy.
        assertThat(rawRequest(port, "loop.creds.test", "/", "X-Proxied-By: x" + self + "x"))
            .as("step 3: matching is per hop, never a substring").contains("upstream-ok");

        // Step 4: a chain too long to extend is refused instead of growing without bound.
        assertThat(rawRequest(port, "loop.creds.test", "/", "X-Proxied-By: " + "p".repeat(600)))
            .as("step 4: an oversized chain answers 508").contains("508");
    }

    /** The one proxy this class runs, started on first use and reloaded after new fixtures. */
    private static int proxyPort() {
        if (proxy == null) {
            proxy = startProxy();
        } else {
            proxy.getDispatcher().reloadRoutes();
        }
        return httpPort(proxy);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** The name=value pair of the proxy session cookie a response set. */
    private static String sessionCookie(String response) {
        for (String line : response.split("\n")) {
            if (line.regionMatches(true, 0, "Set-Cookie:", 0, 11) && line.contains("hh_site_session_id=")) {
                return line.substring(11).trim().split(";", 2)[0];
            }
        }
        throw new AssertionError("no proxy session cookie in:\n" + response);
    }
}
