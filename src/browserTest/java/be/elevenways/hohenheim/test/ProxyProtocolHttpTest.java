package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.proxy.ProxyProtocolV2;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static be.elevenways.hohenheim.test.ProxyTestSupport.addDomain;
import static be.elevenways.hohenheim.test.ProxyTestSupport.bootRuntime;
import static be.elevenways.hohenheim.test.ProxyTestSupport.resetDatabase;
import static be.elevenways.hohenheim.test.ProxyTestSupport.setupSite;
import static org.assertj.core.api.Assertions.assertThat;

/** The plain HTTP listener resolves PROXY v2 identity exactly like the TLS one. */
class ProxyProtocolHttpTest {

    private static boolean initialized;
    private ProxyServer proxy;
    private HttpServer backend;
    private final Map<String, String> seenHeaders = new ConcurrentHashMap<>();

    @BeforeAll
    static void boot() throws Exception {
        if (initialized) return;
        initialized = true;
        bootRuntime();
    }

    @AfterEach
    void cleanup() {
        if (proxy != null) proxy.stop();
        if (backend != null) backend.stop(0);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Proxy.PROXY_PROTOCOL_TRUSTED_SOURCES, List.of());
    }

    @Test
    void trustedHeaderBecomesTheForwardedClientIdentity() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Proxy.PROXY_PROTOCOL_TRUSTED_SOURCES, List.of("127.0.0.1/32"));
        startBackendAndProxy("ingress.example.test", null);

        String response = requestThroughProxyProtocol("ingress.example.test", "/",
            new InetSocketAddress(InetAddress.getByName("198.51.100.23"), 40001),
            new InetSocketAddress(InetAddress.getByName("203.0.113.7"), 80));

        assertThat(response).contains("200 OK").contains("backend");
        assertThat(seenHeaders.get("x-real-ip")).isEqualTo("198.51.100.23");
        assertThat(seenHeaders.get("x-forwarded-for")).contains("198.51.100.23");
    }

    @Test
    void directClientsStayValidWhileTheIngressFrontIsActive() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Proxy.PROXY_PROTOCOL_TRUSTED_SOURCES, List.of("127.0.0.1/32"));
        startBackendAndProxy("direct.example.test", null);

        String response = ProxyTestSupport.rawRequest(
            proxy.getHttpAddress().getPort(), "direct.example.test", "/");

        assertThat(response).contains("200 OK").contains("backend");
        assertThat(seenHeaders.get("x-real-ip")).isEqualTo("127.0.0.1");
    }

    @Test
    void untrustedPeerSendingAHeaderIsDisconnected() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Proxy.PROXY_PROTOCOL_TRUSTED_SOURCES, List.of("203.0.113.0/24"));
        startBackendAndProxy("refused.example.test", null);

        String response = requestThroughProxyProtocol("refused.example.test", "/",
            new InetSocketAddress(InetAddress.getByName("198.51.100.23"), 40001),
            new InetSocketAddress(InetAddress.getByName("203.0.113.7"), 80));

        assertThat(response).isEmpty();
        assertThat(seenHeaders).isEmpty();
    }

    @Test
    void proxiedDestinationDrivesListenOnMatching() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Proxy.PROXY_PROTOCOL_TRUSTED_SOURCES, List.of("127.0.0.1/32"));
        startBackendAndProxy("bound.example.test", "203.0.113.7");

        String matching = requestThroughProxyProtocol("bound.example.test", "/",
            new InetSocketAddress(InetAddress.getByName("198.51.100.23"), 40001),
            new InetSocketAddress(InetAddress.getByName("203.0.113.7"), 80));
        assertThat(matching).contains("200 OK").contains("backend");

        seenHeaders.clear();
        String otherListener = requestThroughProxyProtocol("bound.example.test", "/",
            new InetSocketAddress(InetAddress.getByName("198.51.100.23"), 40002),
            new InetSocketAddress(InetAddress.getByName("203.0.113.9"), 80));
        assertThat(otherListener).doesNotContain("200 OK");
        assertThat(seenHeaders).isEmpty();
    }

    @Test
    void withoutTrustedPeersThePublicPortStaysTheUndertowListener() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Proxy.PROXY_PROTOCOL_TRUSTED_SOURCES, List.of());
        startBackendAndProxy("plain.example.test", null);

        assertThat(proxy.getHttpAddress())
            .isEqualTo(proxy.getHttpListenerInfo().getAddress());

        String response = ProxyTestSupport.rawRequest(
            proxy.getHttpAddress().getPort(), "plain.example.test", "/",
            "X-Real-IP: 198.51.100.99");
        assertThat(response).contains("200 OK");
        // An untrusted client cannot dictate the forwarded identity.
        assertThat(seenHeaders.get("x-real-ip")).isEqualTo("127.0.0.1");
    }

    private void startBackendAndProxy(String hostname, String listenOn) throws Exception {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (!values.isEmpty()) seenHeaders.put(name.toLowerCase(Locale.ROOT), values.get(0));
            });
            byte[] body = "backend".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        backend.start();

        Row site = setupSite("hohenheim:address", "Ingress " + hostname,
            hostname.replaceAll("[^a-z0-9]+", "-"), Map.of(
                "forward_host", "127.0.0.1", "forward_port", backend.getAddress().getPort()));
        addDomain(site, hostname, "exact", null, false);
        if (listenOn != null) {
            Row domain = Models.get(SiteDomainModel.class).find()
                .where(SiteDomainModel.HOSTNAME.eq(hostname)).first();
            domain.set(SiteDomainModel.LISTEN_ON, listenOn);
            Models.get(SiteDomainModel.class).save(domain);
        }

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, 0);
        proxy = new ProxyServer();
        proxy.start();
    }

    private String requestThroughProxyProtocol(String host, String path, InetSocketAddress source,
                                               InetSocketAddress destination) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxy.getHttpAddress().getPort())) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(ProxyProtocolV2.encode(source, destination));
            out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            StringBuilder response = new StringBuilder();
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }
            } catch (java.net.SocketTimeoutException ignored) {
                // A refused connection may be killed without a clean EOF.
            }
            return response.toString();
        }
    }
}
