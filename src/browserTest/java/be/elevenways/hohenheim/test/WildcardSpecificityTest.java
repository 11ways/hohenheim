package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wildcard tier orders candidates by glob specificity, not site-name order: with
 * *.com on site "alpha" and *.example.com on site "zeta", every *.example.com host must be
 * served by zeta (pre-fix it went to alpha because the list was built in site-name order
 * and ties on path length keep the first entry).
 */
class WildcardSpecificityTest {

    private static ProxyServer proxy;
    private static HttpServer broadUpstream;
    private static HttpServer narrowUpstream;

    @BeforeAll
    static void boot() throws Exception {
        ProxyTestSupport.bootRuntime();
    }

    @AfterAll
    static void stop() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        if (broadUpstream != null) broadUpstream.stop(0);
        if (narrowUpstream != null) narrowUpstream.stop(0);
    }

    @Test
    @Timeout(30)
    void narrowerWildcardWinsRegardlessOfSiteName() throws Exception {
        broadUpstream = respondingWith("broad-wildcard");
        narrowUpstream = respondingWith("narrow-wildcard");

        // "Alpha" sorts before "Zeta", so pre-fix the broad pattern was consulted first.
        Row alpha = ProxyTestSupport.setupSite("hohenheim:proxy", "Alpha Broad", "alpha-broad",
            Map.of("forward_host", "127.0.0.1",
                   "forward_port", broadUpstream.getAddress().getPort()));
        ProxyTestSupport.addDomain(alpha, "*.com", "wildcard", null, false);
        Row zeta = ProxyTestSupport.setupSite("hohenheim:proxy", "Zeta Narrow", "zeta-narrow",
            Map.of("forward_host", "127.0.0.1",
                   "forward_port", narrowUpstream.getAddress().getPort()));
        ProxyTestSupport.addDomain(zeta, "*.example.com", "wildcard", null, false);

        proxy = ProxyTestSupport.startProxy();
        int port = ProxyTestSupport.httpPort(proxy);

        assertThat(ProxyTestSupport.rawRequest(port, "foo.example.com", "/"))
            .as("a *.example.com host is served by the NARROWER pattern's site")
            .contains("narrow-wildcard").doesNotContain("broad-wildcard");

        assertThat(ProxyTestSupport.rawRequest(port, "bar.com", "/"))
            .as("a host only the broad pattern matches still reaches the broad site")
            .contains("broad-wildcard");
    }

    private static HttpServer respondingWith(String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        return server;
    }
}
