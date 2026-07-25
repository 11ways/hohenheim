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

    @Test
    void trustedKeyPropagatesTheRealClientIp() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS,
            List.of("front-key-1", "front-key-2"));

        AtomicReference<ProxyHeaders> captured = new AtomicReference<>();
        int upstreamPort = startCapturingUpstream(captured);

        setupSiteWithDomain("hohenheim:proxy", "trusted.test", "exact",
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

        setupSiteWithDomain("hohenheim:proxy", "spoof.test", "exact",
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
        setupSiteWithDomain("hohenheim:proxy", "invalid-ip.test", "exact",
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

        setupSiteWithDomain("hohenheim:proxy", "chain.test", "exact",
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
        setupSiteWithDomain("hohenheim:proxy", "websocket-default.test", "exact",
            Map.of("forward_host", "127.0.0.1", "forward_port", 1));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "websocket-default.test", "/",
            "Connection: Upgrade",
            "Upgrade: websocket",
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
            "Sec-WebSocket-Version: 13");

        assertThat(response).contains("503").doesNotContain("403");
    }

    @Test
    void globHostnamesRouteThroughTheDispatcher() throws Exception {
        resetDatabase();

        AtomicReference<ProxyHeaders> captured = new AtomicReference<>();
        int upstreamPort = startCapturingUpstream(captured);

        setupSiteWithDomain("hohenheim:proxy", "eu?.wild.test", "wildcard",
            Map.of("forward_host", "127.0.0.1", "forward_port", upstreamPort));

        proxy = startProxy();

        assertThat(rawRequest(httpPort(proxy), "eu1.wild.test", "/")).contains("200");
        // ? is exactly one character: a two-character suffix must miss.
        assertThat(rawRequest(httpPort(proxy), "eu12.wild.test", "/")).contains("404");
    }
}
