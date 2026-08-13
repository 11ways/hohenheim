package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.security.BanService;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Unix-socket HTTP front: it refuses to start without trusted proxy keys (an AF_UNIX
 * client has no IP, so every request would count as 127.0.0.1 and IP-based controls die),
 * and with the contract configured the fronting proxy's X-Real-IP drives ban enforcement.
 */
class SocketFrontModeTest {

    private static final String KEY = "socket-front-test-key";

    private ProxyServer proxy;
    private HttpServer upstream;

    @BeforeAll
    static void boot() throws Exception {
        ProxyTestSupport.bootRuntime();
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
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_SOCKET_PATH, "");
    }

    @Test
    @Timeout(30)
    void socketModeRequiresTrustedProxyKeysAndHonoursForwardedIdentity() throws Exception {
        TestDatabases.freshDatabase();
        Path sock = Files.createTempFile("hh-front-sock", ".sock");
        Files.delete(sock);
        sock.toFile().deleteOnExit();

        // Step 1: socket mode WITHOUT trusted proxy keys must refuse to start, loudly.
        // (Pre-fix counterfactual: it started, and every client became 127.0.0.1.)
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_SOCKET_PATH, sock.toString());
        proxy = new ProxyServer();
        proxy.start();
        assertThat(proxy.getHttpState())
            .as("step 1: socket mode without proxy.trusted_proxy_keys must FAIL to start")
            .isEqualTo(ProxyServer.State.FAILED);
        assertThat(proxy.getHttpFailureReason())
            .as("step 1: the refusal names the missing setting")
            .contains("trusted_proxy_keys");
        assertThat(Files.exists(sock))
            .as("step 1: no socket file appears for a refused front")
            .isFalse();
        proxy.stop();

        // Step 2: with the key contract configured the front starts and serves.
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            byte[] body = "socket-front-ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
        Row site = ProxyTestSupport.setupSite("hohenheim:proxy", "Socket Front Site",
            "socket-front-site", Map.of("forward_host", "127.0.0.1",
                "forward_port", upstream.getAddress().getPort()));
        ProxyTestSupport.addDomain(site, "front.sock.test", "exact", null, false);

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, List.of(KEY));
        proxy = new ProxyServer();
        proxy.start();
        assertThat(proxy.getHttpState())
            .as("step 2: socket mode with keys configured starts")
            .isEqualTo(ProxyServer.State.RUNNING);

        String served = unixRequest(sock, "front.sock.test", "/", "X-Hohenheim-Key: " + KEY);
        assertThat(served)
            .as("step 2: an AUTHENTICATED request through the socket front is served")
            .contains("200").contains("socket-front-ok");

        // Step 3: the fronting proxy's forwarded identity drives ban enforcement.
        Row ban = BanService.INSTANCE.createBan("203.0.113.77", "test",
            BanModel.SOURCE_MANUAL, null, null);
        try {
            String refused = unixRequest(sock, "front.sock.test", "/",
                "X-Hohenheim-Key: " + KEY, "X-Real-IP: 203.0.113.77");
            assertThat(refused)
                .as("step 3: a banned forwarded client is refused through the socket front")
                .contains("403");
            String other = unixRequest(sock, "front.sock.test", "/",
                "X-Hohenheim-Key: " + KEY, "X-Real-IP: 203.0.113.78");
            assertThat(other)
                .as("step 3: an unbanned forwarded client is not collateral")
                .contains("200");
        } finally {
            BanService.INSTANCE.lift(ban, "test");
        }

        // Step 4: PER-REQUEST ENFORCEMENT. An unauthenticated request is REFUSED, never
        // silently degraded to the socket peer's 127.0.0.1 -- the bridge's loopback TCP
        // port is reachable by any local account, so accepting one is an ACL bypass.
        // (Pre-fix counterfactual: both of these were served as trusted loopback.)
        assertThat(unixRequest(sock, "front.sock.test", "/"))
            .as("step 4: a request with NO X-Hohenheim-Key is refused")
            .contains("403").doesNotContain("socket-front-ok");
        assertThat(unixRequest(sock, "front.sock.test", "/", "X-Hohenheim-Key: wrong-key",
                "X-Real-IP: 203.0.113.99"))
            .as("step 4: and a request with a WRONG key cannot state a client IP either")
            .contains("403").doesNotContain("socket-front-ok");

        // Step 5: the setting is LIVE, and clearing it fails CLOSED. proxy.trusted_proxy_keys
        // is editable while the front serves; before the per-request half existed, clearing
        // it left the listener running with every client degraded to loopback.
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, List.of());
        assertThat(unixRequest(sock, "front.sock.test", "/", "X-Hohenheim-Key: " + KEY))
            .as("step 5: with the key set cleared, the previously valid key no longer opens"
                + " the front -- the socket listener fails closed instead of degrading")
            .contains("403").doesNotContain("socket-front-ok");

        // Step 6: and restoring the keys restores service, so the gate tracks the setting
        // in both directions rather than latching.
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, List.of(KEY));
        assertThat(unixRequest(sock, "front.sock.test", "/", "X-Hohenheim-Key: " + KEY))
            .as("step 6: restoring the key set restores the authenticated front")
            .contains("200").contains("socket-front-ok");
    }

    private static String unixRequest(Path socket, String host, String path,
                                      String... extraHeaderLines) throws Exception {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            StringBuilder request = new StringBuilder()
                .append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n");
            for (String line : extraHeaderLines) {
                request.append(line).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");
            channel.write(ByteBuffer.wrap(request.toString().getBytes(StandardCharsets.UTF_8)));

            StringBuilder response = new StringBuilder();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) != -1) {
                buffer.flip();
                response.append(StandardCharsets.UTF_8.decode(buffer));
                buffer.clear();
            }
            return response.toString();
        }
    }
}
