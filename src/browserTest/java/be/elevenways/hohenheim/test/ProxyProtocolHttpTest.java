package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.proxy.ProxyProtocolV2;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.zenit.common.Zenit;
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
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static be.elevenways.hohenheim.test.ProxyTestSupport.addDomain;
import static be.elevenways.hohenheim.test.ProxyTestSupport.setupSite;
import static org.assertj.core.api.Assertions.assertThat;

/** The plain HTTP listener resolves PROXY v2 identity exactly like the TLS one. */
class ProxyProtocolHttpTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static boolean initialized;
    private ProxyServer proxy;
    private HttpServer backend;
    private final Map<String, String> seenHeaders = new ConcurrentHashMap<>();

    @BeforeAll
    static void boot() throws Exception {
        if (initialized) return;
        initialized = true;
        HohenheimEndpoints.init();
    }

    @AfterEach
    void cleanup() {
        if (proxy != null) proxy.stop();
        if (backend != null) backend.stop(0);
        Zenit.SETTINGS_VALUES.setValue(
            HohenheimSettings.Proxy.PROXY_PROTOCOL_TRUSTED_SOURCES, List.of());
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.IPV6_ADDRESS, "");
    }

    @Test
    void trustedHeaderBecomesTheForwardedClientIdentity() throws Exception {
        APP.renewDatabase();
        Zenit.SETTINGS_VALUES.setValue(
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
        APP.renewDatabase();
        Zenit.SETTINGS_VALUES.setValue(
            HohenheimSettings.Proxy.PROXY_PROTOCOL_TRUSTED_SOURCES, List.of("127.0.0.1/32"));
        startBackendAndProxy("direct.example.test", null);

        String response = ProxyTestSupport.rawRequest(
            proxy.getHttpAddress().getPort(), "direct.example.test", "/");

        assertThat(response).contains("200 OK").contains("backend");
        assertThat(seenHeaders.get("x-real-ip")).isEqualTo("127.0.0.1");
    }

    @Test
    void untrustedPeerSendingAHeaderIsDisconnected() throws Exception {
        APP.renewDatabase();
        Zenit.SETTINGS_VALUES.setValue(
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
        APP.renewDatabase();
        Zenit.SETTINGS_VALUES.setValue(
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
        APP.renewDatabase();
        Zenit.SETTINGS_VALUES.setValue(
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

    /**
     * A named IPv6 address serves beside the IPv4 wildcard on the same, fixed port. Java binds "0.0.0.0" as a
     * dual-stack socket on an IPv6 host, which already holds every IPv6 address of the port, so the second bind used
     * to fail with "Address already in use" and take the whole HTTP listener down. Port 0 would hide it: each
     * listener then takes its own ephemeral port.
     */
    @Test
    void aDedicatedIpv6AddressServesBesideTheIpv4Wildcard() throws Exception {
        APP.renewDatabase();
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.IPV6_ADDRESS, "::1");
        int fixedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            fixedPort = probe.getLocalPort();
        }
        startBackendAndProxy("dual.example.test", null, fixedPort);

        // 1. Both listeners bind.
        assertThat(proxy.getHttpState())
            .as("step 1: HTTP runs with the IPv6 address configured")
            .isEqualTo(ProxyServer.State.RUNNING);
        int port = proxy.getHttpAddress().getPort();
        assertThat(port).as("step 1: the configured port").isEqualTo(fixedPort);

        // 2. IPv4 reaches the site and keeps its own client address.
        assertThat(ProxyTestSupport.rawRequestAt("127.0.0.1", port, "GET", "dual.example.test", "/"))
            .as("step 2: IPv4 reaches the site").contains("200 OK").contains("backend");
        assertThat(seenHeaders.get("x-real-ip")).as("step 2: the IPv4 client").isEqualTo("127.0.0.1");

        // 3. The dedicated IPv6 address reaches it on the same port, as itself.
        seenHeaders.clear();
        assertThat(ProxyTestSupport.rawRequestAt("::1", port, "GET", "dual.example.test", "/"))
            .as("step 3: the IPv6 address reaches the site on the same port").contains("200 OK").contains("backend");
        assertThat(InetAddress.getByName(seenHeaders.get("x-real-ip")))
            .as("step 3: the IPv6 client").isEqualTo(InetAddress.getByName("::1"));
    }

    private void startBackendAndProxy(String hostname, String listenOn) throws Exception {
        startBackendAndProxy(hostname, listenOn, 0);
    }

    private void startBackendAndProxy(String hostname, String listenOn, int httpPort) throws Exception {
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

        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, httpPort);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, 0);
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
