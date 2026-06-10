package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, "");
    }

    /** Start an upstream that records X-Real-IP and X-Forwarded-For of each request. */
    private int startCapturingUpstream(AtomicReference<String> realIp,
                                       AtomicReference<String> forwardedFor) throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            realIp.set(ex.getRequestHeaders().getFirst("X-Real-IP"));
            forwardedFor.set(ex.getRequestHeaders().getFirst("X-Forwarded-For"));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
        return upstream.getAddress().getPort();
    }

    @Test
    void trustedKeyPropagatesTheRealClientIp() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS,
            "front-key-1, front-key-2");

        AtomicReference<String> realIp = new AtomicReference<>();
        AtomicReference<String> forwardedFor = new AtomicReference<>();
        int upstreamPort = startCapturingUpstream(realIp, forwardedFor);

        setupSiteWithDomain("hohenheim:proxy", "trusted.test", "exact",
            Map.of("forward_host", "127.0.0.1", "forward_port", upstreamPort));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "trusted.test", "/",
            "X-Hohenheim-Key: front-key-2",
            "X-Real-IP: 203.0.113.9");

        assertThat(response).contains("200");
        assertThat(realIp.get()).isEqualTo("203.0.113.9");
        // The real client leads, the directly-connected trusted proxy is appended as the hop.
        assertThat(forwardedFor.get().replace(" ", "")).isEqualTo("203.0.113.9,127.0.0.1");
    }

    @Test
    void untrustedSpoofedRealIpIsOverwritten() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, "front-key-1");

        AtomicReference<String> realIp = new AtomicReference<>();
        AtomicReference<String> forwardedFor = new AtomicReference<>();
        int upstreamPort = startCapturingUpstream(realIp, forwardedFor);

        setupSiteWithDomain("hohenheim:proxy", "spoof.test", "exact",
            Map.of("forward_host", "127.0.0.1", "forward_port", upstreamPort));

        proxy = startProxy();

        // Wrong key: the spoofed X-Real-IP must NOT survive to the upstream.
        String response = rawRequest(httpPort(proxy), "spoof.test", "/",
            "X-Hohenheim-Key: wrong-key",
            "X-Real-IP: 203.0.113.9");

        assertThat(response).contains("200");
        assertThat(realIp.get()).isEqualTo("127.0.0.1");

        // No key at all: same.
        rawRequest(httpPort(proxy), "spoof.test", "/", "X-Real-IP: 198.51.100.7");
        assertThat(realIp.get()).isEqualTo("127.0.0.1");
    }

    @Test
    void trustedRequestAppendsToExistingForwardedFor() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, "front-key-1");

        AtomicReference<String> realIp = new AtomicReference<>();
        AtomicReference<String> forwardedFor = new AtomicReference<>();
        int upstreamPort = startCapturingUpstream(realIp, forwardedFor);

        setupSiteWithDomain("hohenheim:proxy", "chain.test", "exact",
            Map.of("forward_host", "127.0.0.1", "forward_port", upstreamPort));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "chain.test", "/",
            "X-Hohenheim-Key: front-key-1",
            "X-Real-IP: 203.0.113.9",
            "X-Forwarded-For: 203.0.113.9");

        assertThat(response).contains("200");
        assertThat(realIp.get()).isEqualTo("203.0.113.9");
        assertThat(forwardedFor.get().replace(" ", "")).isEqualTo("203.0.113.9,127.0.0.1");
    }

    @Test
    void globHostnamesRouteThroughTheDispatcher() throws Exception {
        resetDatabase();

        AtomicReference<String> realIp = new AtomicReference<>();
        AtomicReference<String> forwardedFor = new AtomicReference<>();
        int upstreamPort = startCapturingUpstream(realIp, forwardedFor);

        setupSiteWithDomain("hohenheim:proxy", "eu?.wild.test", "wildcard",
            Map.of("forward_host", "127.0.0.1", "forward_port", upstreamPort));

        proxy = startProxy();

        assertThat(rawRequest(httpPort(proxy), "eu1.wild.test", "/")).contains("200");
        // ? is exactly one character: a two-character suffix must miss.
        assertThat(rawRequest(httpPort(proxy), "eu12.wild.test", "/")).contains("404");
    }
}
