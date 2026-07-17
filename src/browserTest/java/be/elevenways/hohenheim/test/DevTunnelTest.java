package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.devtunnel.DevTunnelServerHandler;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.sitetype.types.DevNamespaceSiteType;
import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.EndpointRoute;
import be.elevenways.zenit.common.routing.WebSocketEndpoint;
import be.elevenways.zenit.common.websocket.WebSocketHandler;
import be.elevenways.zenit.common.websocket.WebSocketSession;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.devtunnel.DevTunnelClient;
import be.elevenways.zenit.server.http.ZenitHttpServer;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end dev tunnel: a real zenit DevTunnelClient registers over the admin
 * server's WebSocket endpoint and requests entering the real proxy on the
 * wildcard dev domain are served by the client's local target, including
 * WebSocket upgrades and window-limited large bodies.
 */
class DevTunnelTest {

    private static final String TOKEN = "zdev_test_registration_token";
    private static final String BASE = "dev.example.test";

    private static boolean initialized = false;
    private static ZenitHttpServer adminServer;
    private static ProxyServer proxy;
    private static int adminPort;
    private static int proxyPort;

    private final List<DevTunnelClient> clients = new ArrayList<>();
    private final List<HttpServer> targets = new ArrayList<>();

    // A WS echo endpoint the tunneled TARGET server will serve (registries are global).
    @SuppressWarnings("unused")
    private static final WebSocketEndpoint TUNNEL_ECHO = WebSocketEndpoint.builder()
        .identifier(Identifier.of("hohenheimtest", "tunnel_echo"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("tunnel-echo").build())
        .handler(session -> new EchoHandler(session))
        .build();

    private static final class EchoHandler implements WebSocketHandler {
        private final WebSocketSession session;
        EchoHandler(WebSocketSession session) { this.session = session; }
        @Override public void onTextMessage(String message) { session.sendText("echo:" + message); }
    }

    @BeforeAll
    static void boot() throws Exception {
        if (!initialized) {
            initialized = true;
            File db = File.createTempFile("hohenheim-devtunnel", ".db");
            db.delete();
            db.deleteOnExit();
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());

            SiteTypes.boot();
            HohenheimEndpoints.init();
            HohenheimDatabase.init();
            HohenheimTestRuntime.ensureBooted();
            Zenit.getHawkeye().setClientScriptLocation("/cms.js");
        }

        // The targeted wiring HohenheimHandlers.init() would do for this endpoint.
        HohenheimEndpoints.DEV_TUNNEL.setHandlerFactory(DevTunnelServerHandler::new);

        createDevNamespace();

        adminServer = ServerZenitRuntime.createServer(0);
        adminServer.start();
        adminPort = adminServer.getPort();

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        proxy = new ProxyServer();
        proxy.start();
        proxyPort = ((InetSocketAddress) proxy.getHttpListenerInfo().getAddress()).getPort();
    }

    @AfterAll
    static void shutdown() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        if (adminServer != null) {
            adminServer.stop();
            adminServer = null;
        }
    }

    @AfterEach
    void cleanupClients() {
        for (DevTunnelClient client : clients) {
            client.stop();
        }
        clients.clear();
        for (HttpServer target : targets) {
            target.stop(0);
        }
        targets.clear();
    }

    private static void createDevNamespace() {
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Dev namespace");
        site.set(SiteModel.SLUG, "dev-namespace");
        site.set(SiteModel.SITE_TYPE, DevNamespaceSiteType.ID.toString());
        site.set(SiteModel.SETTINGS, Map.of(DevNamespaceSiteType.REGISTRATION_TOKEN_KEY, TOKEN));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);

        var domainModel = Models.get(SiteDomainModel.class);
        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, "*." + BASE);
        domain.set(SiteDomainModel.MATCH_TYPE, "wildcard");
        domainModel.save(domain);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private HttpServer startTarget(byte[] body, AtomicReference<Map<String, String>> headerCapture)
            throws Exception {
        HttpServer target = HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            if (headerCapture != null) {
                headerCapture.set(Map.of(
                    "host", String.valueOf(exchange.getRequestHeaders().getFirst("Host")),
                    "proto", String.valueOf(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto")),
                    "for", String.valueOf(exchange.getRequestHeaders().getFirst("X-Forwarded-For"))));
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        target.start();
        targets.add(target);
        return target;
    }

    private DevTunnelClient register(String name, String token, int localPort) throws Exception {
        DevTunnelClient client = new DevTunnelClient(
            "ws://127.0.0.1:" + adminPort + "/ws/dev-tunnel", token, name, localPort);
        clients.add(client);
        client.start();
        return client;
    }

    private static void awaitRegistered(DevTunnelClient client) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!client.isRegistered() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(client.isRegistered()).as("client registered").isTrue();
    }

    /** Raw HTTP GET through the proxy with an explicit Host header; returns status line + body. */
    private static String[] proxyGet(String host, String path) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            ByteArrayOutputStream all = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(all);
            String response = all.toString(StandardCharsets.UTF_8);
            int headerEnd = response.indexOf("\r\n\r\n");
            String statusLine = response.substring(0, response.indexOf("\r\n"));
            String body = headerEnd >= 0 ? response.substring(headerEnd + 4) : "";
            return new String[] {statusLine, body, response.substring(0, Math.max(headerEnd, 0))};
        }
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    void registeredClientServesProxiedTraffic() throws Exception {
        AtomicReference<Map<String, String>> headers = new AtomicReference<>();
        HttpServer target = startTarget("served through the tunnel".getBytes(StandardCharsets.UTF_8), headers);

        DevTunnelClient client = register("site1", TOKEN, target.getAddress().getPort());
        awaitRegistered(client);
        assertThat(client.getPublicOrigin()).isEqualTo("https://site1." + BASE);

        String[] response = proxyGet("site1." + BASE, "/hello");
        assertThat(response[0]).contains("200");
        assertThat(response[1]).isEqualTo("served through the tunnel");

        // The dev server must see it is behind an HTTP(S)-terminating proxy.
        assertThat(headers.get()).isNotNull();
        assertThat(headers.get().get("host")).isEqualTo("site1." + BASE);
        assertThat(headers.get().get("proto")).isNotEqualTo("null");
        assertThat(headers.get().get("for")).isNotEqualTo("null");

        // Keep-alive reuse: a second request over the same tunnel must work too.
        String[] second = proxyGet("site1." + BASE, "/again");
        assertThat(second[0]).contains("200");
        assertThat(second[1]).isEqualTo("served through the tunnel");
    }

    @Test
    void unclaimedNameRendersTheOfflinePage() throws Exception {
        String[] response = proxyGet("nothing-here." + BASE, "/");
        assertThat(response[0]).contains("503");
        assertThat(response[1]).contains("nothing-here");
    }

    @Test
    void nestedSubdomainsAreNotClaimableNames() throws Exception {
        HttpServer target = startTarget("x".getBytes(StandardCharsets.UTF_8), null);
        DevTunnelClient client = register("nested", TOKEN, target.getAddress().getPort());
        awaitRegistered(client);

        // deeper.nested.<base> matches the wildcard route but is not a single label.
        String[] response = proxyGet("deeper.nested." + BASE, "/");
        assertThat(response[0]).contains("503");
    }

    @Test
    void invalidTokenNeverRegisters() throws Exception {
        HttpServer target = startTarget("x".getBytes(StandardCharsets.UTF_8), null);
        DevTunnelClient client = register("badtoken", "wrong-token", target.getAddress().getPort());

        Thread.sleep(2_000);
        assertThat(client.isRegistered()).isFalse();

        String[] response = proxyGet("badtoken." + BASE, "/");
        assertThat(response[0]).contains("503");
    }

    @Test
    void newerRegistrationReplacesTheOlder() throws Exception {
        HttpServer targetA = startTarget("from A".getBytes(StandardCharsets.UTF_8), null);
        HttpServer targetB = startTarget("from B".getBytes(StandardCharsets.UTF_8), null);

        DevTunnelClient first = register("shared", TOKEN, targetA.getAddress().getPort());
        awaitRegistered(first);

        DevTunnelClient second = register("shared", TOKEN, targetB.getAddress().getPort());
        awaitRegistered(second);

        String[] response = proxyGet("shared." + BASE, "/");
        assertThat(response[0]).contains("200");
        assertThat(response[1]).isEqualTo("from B");
    }

    @Test
    void largeBodyFlowsThroughTheCreditWindow() throws Exception {
        byte[] body = new byte[3 * 1024 * 1024];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i % 249);
        }
        HttpServer target = startTarget(body, null);
        DevTunnelClient client = register("bigbody", TOKEN, target.getAddress().getPort());
        awaitRegistered(client);

        try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET /big HTTP/1.1\r\nHost: bigbody." + BASE
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            ByteArrayOutputStream all = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(all);
            byte[] response = all.toByteArray();
            int headerEnd = indexOfDoubleCrlf(response);
            assertThat(headerEnd).isGreaterThan(0);
            byte[] received = java.util.Arrays.copyOfRange(response, headerEnd, response.length);
            assertThat(received).isEqualTo(body);
        }
    }

    @Test
    void webSocketUpgradesTravelThroughTheTunnel() throws Exception {
        // The target dev server is a real ZenitHttpServer, so it serves the
        // global WS echo endpoint registered above.
        ZenitHttpServer target = new ZenitHttpServer(0);
        target.start();
        try {
            DevTunnelClient client = register("wsdev", TOKEN, target.getPort());
            awaitRegistered(client);

            try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
                socket.setSoTimeout(15_000);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                String key = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes());
                out.write(("GET /tunnel-echo HTTP/1.1\r\n"
                    + "Host: wsdev." + BASE + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String headers = readHeaders(in);
                assertThat(headers).startsWith("HTTP/1.1 101");
                String expectedAccept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest(
                        (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                assertThat(headers).contains("Sec-WebSocket-Accept: " + expectedAccept);

                writeMaskedTextFrame(out, "hello");
                String reply = readTextFrame(in);
                assertThat(reply).isEqualTo("echo:hello");
            }
        } finally {
            target.stop();
        }
    }

    // ------------------------------------------------------------------
    // Minimal WS framing for the upgrade test
    // ------------------------------------------------------------------

    private static String readHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            byte[] soFar = buf.toByteArray();
            if (soFar.length >= 4
                && soFar[soFar.length - 4] == '\r' && soFar[soFar.length - 3] == '\n'
                && soFar[soFar.length - 2] == '\r' && soFar[soFar.length - 1] == '\n') {
                break;
            }
        }
        return buf.toString(StandardCharsets.US_ASCII);
    }

    private static void writeMaskedTextFrame(OutputStream out, String text) throws Exception {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] mask = {0x1F, 0x2E, 0x3D, 0x4C};
        out.write(0x81);                      // FIN + text
        out.write(0x80 | payload.length);     // masked + length (small payloads only)
        out.write(mask);
        for (int i = 0; i < payload.length; i++) {
            out.write(payload[i] ^ mask[i % 4]);
        }
        out.flush();
    }

    private static String readTextFrame(InputStream in) throws Exception {
        int first = in.read();
        assertThat(first & 0x0F).as("text opcode").isEqualTo(1);
        int second = in.read();
        int len = second & 0x7F;
        assertThat(second & 0x80).as("server frames are unmasked").isEqualTo(0);
        if (len == 126) {
            len = (in.read() << 8) | in.read();
        }
        byte[] payload = in.readNBytes(len);
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static int indexOfDoubleCrlf(byte[] data) {
        for (int i = 3; i < data.length; i++) {
            if (data[i - 3] == '\r' && data[i - 2] == '\n' && data[i - 1] == '\r' && data[i] == '\n') {
                return i + 1;
            }
        }
        return -1;
    }
}
