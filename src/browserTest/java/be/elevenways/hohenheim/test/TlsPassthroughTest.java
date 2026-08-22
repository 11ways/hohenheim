package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.proxy.ProxyProtocolV2;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.proxy.TlsClientHelloReader;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static be.elevenways.hohenheim.test.ProxyTestSupport.addDomain;
import static be.elevenways.hohenheim.test.ProxyTestSupport.bootRuntime;
import static be.elevenways.hohenheim.test.ProxyTestSupport.resetDatabase;
import static be.elevenways.hohenheim.test.ProxyTestSupport.setupSite;
import static org.assertj.core.api.Assertions.assertThat;

/** Wire-level coverage for mixed TLS termination, SNI passthrough, and PROXY v2 identity. */
class TlsPassthroughTest {

    private static boolean initialized;
    private ProxyServer proxy;
    private ServerSocket rawBackend;
    private HttpServer httpBackend;

    private record Captured(ProxyProtocolV2.Header proxyHeader, String serverName,
                            byte[] hello, byte[] payload) {}

    @BeforeAll
    static void boot() throws Exception {
        if (initialized) return;
        initialized = true;
        bootRuntime();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (proxy != null) proxy.stop();
        if (rawBackend != null) rawBackend.close();
        if (httpBackend != null) httpBackend.stop(0);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Proxy.PROXY_PROTOCOL_TRUSTED_SOURCES, List.of());
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Proxy.CONNECTION_PROLOGUE_TIMEOUT_SECONDS, 5);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Proxy.MAX_PUBLIC_CONNECTIONS, 10_000);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Proxy.MAX_PENDING_CONNECTIONS, 1024);
    }

    @Test
    void passthroughStartsWithoutCertificatesAndPreservesFragmentedClientHello() throws Exception {
        resetDatabase();
        rawBackend = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        CompletableFuture<Captured> captured = captureOneConnection(true);
        setupPassthrough("raw.example.test", "exact", rawBackend.getLocalPort(), true);
        startProxy();

        assertThat(proxy.getHttpsState()).isEqualTo(ProxyServer.State.RUNNING);
        byte[] hello = clientHello("raw.example.test", true);
        try (Socket client = new Socket("127.0.0.1", proxy.getHttpsAddress().getPort())) {
            client.setSoTimeout(5_000);
            for (byte value : hello) {
                client.getOutputStream().write(value);
                client.getOutputStream().flush();
            }
            client.getOutputStream().write("PING".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            assertThat(client.getInputStream().readNBytes(4))
                .containsExactly("PONG".getBytes(StandardCharsets.US_ASCII));
        }

        Captured result = captured.get(5, TimeUnit.SECONDS);
        assertThat(result.serverName()).isEqualTo("raw.example.test");
        assertThat(result.hello()).containsExactly(hello);
        assertThat(result.payload()).containsExactly("PING".getBytes(StandardCharsets.US_ASCII));
        assertThat(result.proxyHeader().command()).isEqualTo(ProxyProtocolV2.Command.PROXY);
        assertThat(((InetSocketAddress) result.proxyHeader().sourceAddress()).getAddress())
            .isEqualTo(InetAddress.getByName("127.0.0.1"));
    }

    @Test
    void trustedIngressProxyHeaderIsPreservedAndUntrustedIngressIsRejected() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Proxy.PROXY_PROTOCOL_TRUSTED_SOURCES, List.of("127.0.0.1/32"));
        rawBackend = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        CompletableFuture<Captured> captured = captureOneConnection(true);
        setupPassthrough("proxied.example.test", "exact", rawBackend.getLocalPort(), true);
        startProxy();

        InetSocketAddress source = new InetSocketAddress(InetAddress.getByName("203.0.113.9"), 51337);
        InetSocketAddress destination = new InetSocketAddress(InetAddress.getByName("198.51.100.8"), 443);
        byte[] hello = clientHello("proxied.example.test", false);
        try (Socket client = new Socket("127.0.0.1", proxy.getHttpsAddress().getPort())) {
            client.setSoTimeout(5_000);
            client.getOutputStream().write(ProxyProtocolV2.encode(source, destination));
            client.getOutputStream().write(hello);
            client.getOutputStream().write("PING".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            assertThat(client.getInputStream().readNBytes(4))
                .containsExactly("PONG".getBytes(StandardCharsets.US_ASCII));
        }
        Captured accepted = captured.get(5, TimeUnit.SECONDS);
        assertThat(accepted.proxyHeader().sourceAddress()).isEqualTo(source);
        assertThat(accepted.proxyHeader().destinationAddress()).isEqualTo(destination);

        proxy.stop();
        proxy = null;
        rawBackend.close();
        rawBackend = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        rawBackend.setSoTimeout(1_000);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Proxy.PROXY_PROTOCOL_TRUSTED_SOURCES, List.of());
        resetDatabase();
        setupPassthrough("rejected.example.test", "exact", rawBackend.getLocalPort(), true);
        startProxy();

        try (Socket client = new Socket("127.0.0.1", proxy.getHttpsAddress().getPort())) {
            client.setSoTimeout(2_000);
            client.getOutputStream().write(ProxyProtocolV2.encode(source, destination));
            client.getOutputStream().write(clientHello("rejected.example.test", false));
            client.getOutputStream().flush();
            assertThat(client.getInputStream().read()).isEqualTo(-1);
        }
        assertThat(CompletableFuture.supplyAsync(() -> {
            try {
                rawBackend.accept().close();
                return true;
            } catch (Exception e) {
                return false;
            }
        }).get(2, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void exactTerminatingRouteWinsOverBroaderPassthroughWildcard() throws Exception {
        resetDatabase();
        installCertificate("control.example.test");
        httpBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpBackend.createContext("/", exchange -> {
            byte[] body = "terminated".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        httpBackend.start();

        Row control = setupSite("hohenheim:address", "Control", "control",
            Map.of("forward_host", "127.0.0.1", "forward_port", httpBackend.getAddress().getPort()));
        addDomain(control, "control.example.test", "exact", null, false);
        rawBackend = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        rawBackend.setSoTimeout(1_000);
        setupPassthrough("*.example.test", "wildcard", rawBackend.getLocalPort(), false);
        startProxy();

        SSLContext trustAll = trustAllContext();
        try (SSLSocket client = (SSLSocket) trustAll.getSocketFactory()
                .createSocket("127.0.0.1", proxy.getHttpsAddress().getPort())) {
            SSLParameters parameters = client.getSSLParameters();
            parameters.setServerNames(List.of(new SNIHostName("control.example.test")));
            client.setSSLParameters(parameters);
            client.setSoTimeout(5_000);
            client.startHandshake();
            client.getOutputStream().write(("GET / HTTP/1.1\r\n"
                + "Host: control.example.test\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            String response = new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(response).contains("200 OK").contains("terminated");
        }

        assertThat(CompletableFuture.supplyAsync(() -> {
            try {
                rawBackend.accept().close();
                return true;
            } catch (Exception e) {
                return false;
            }
        }).get(2, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void clientHelloTimeoutIsAnAbsoluteDeadlineNotAnIdleTimeout() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Proxy.CONNECTION_PROLOGUE_TIMEOUT_SECONDS, 1);
        rawBackend = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        rawBackend.setSoTimeout(1_500);
        setupPassthrough("slow.example.test", "exact", rawBackend.getLocalPort(), false);
        startProxy();

        byte[] hello = clientHello("slow.example.test", false);
        try (Socket client = new Socket("127.0.0.1", proxy.getHttpsAddress().getPort())) {
            client.setSoTimeout(3_000);
            for (int i = 0; i < 6; i++) {
                try {
                    client.getOutputStream().write(hello[i]);
                    client.getOutputStream().flush();
                } catch (Exception closed) {
                    break;
                }
                Thread.sleep(250);
            }
            assertThat(client.getInputStream().read()).isEqualTo(-1);
        }
        assertThat(backendAccepted()).isFalse();
    }

    @Test
    void completedClientHelloRemovesTheHandshakeDeadlineFromTheRelay() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Proxy.CONNECTION_PROLOGUE_TIMEOUT_SECONDS, 1);
        rawBackend = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        CompletableFuture<Void> backend = CompletableFuture.runAsync(() -> {
            try (Socket socket = rawBackend.accept()) {
                socket.setSoTimeout(5_000);
                BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                TlsClientHelloReader.read(input);
                for (int i = 0; i < 2; i++) {
                    assertThat(input.readNBytes(4)).containsExactly(
                        ("PING" + i).substring(0, 4).getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write("PONG".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        setupPassthrough("long.example.test", "exact", rawBackend.getLocalPort(), false);
        startProxy();

        try (Socket client = new Socket("127.0.0.1", proxy.getHttpsAddress().getPort())) {
            client.setSoTimeout(5_000);
            client.getOutputStream().write(clientHello("long.example.test", false));
            client.getOutputStream().write("PING".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            assertThat(client.getInputStream().readNBytes(4)).containsExactly(
                "PONG".getBytes(StandardCharsets.US_ASCII));
            Thread.sleep(1_250);
            client.getOutputStream().write("PING".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            assertThat(client.getInputStream().readNBytes(4)).containsExactly(
                "PONG".getBytes(StandardCharsets.US_ASCII));
        }
        backend.get(5, TimeUnit.SECONDS);
    }

    @Test
    void activeConnectionLimitRejectsExcessTlsClients() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.MAX_PUBLIC_CONNECTIONS, 1);
        rawBackend = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        CompletableFuture<Void> accepted = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<Void> backend = CompletableFuture.runAsync(() -> {
            try (Socket socket = rawBackend.accept()) {
                TlsClientHelloReader.read(new BufferedInputStream(socket.getInputStream()));
                accepted.complete(null);
                release.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        setupPassthrough("capacity.example.test", "exact", rawBackend.getLocalPort(), false);
        startProxy();

        try (Socket first = new Socket("127.0.0.1", proxy.getHttpsAddress().getPort())) {
            first.getOutputStream().write(clientHello("capacity.example.test", false));
            first.getOutputStream().flush();
            accepted.get(5, TimeUnit.SECONDS);
            try (Socket excess = new Socket("127.0.0.1", proxy.getHttpsAddress().getPort())) {
                excess.setSoTimeout(3_000);
                excess.getOutputStream().write(clientHello("capacity.example.test", false));
                excess.getOutputStream().flush();
                assertThat(excess.getInputStream().read()).isEqualTo(-1);
            }
            release.complete(null);
        }
        backend.get(5, TimeUnit.SECONDS);
    }

    @Test
    void pendingHandshakeLimitRejectsExcessSlowClients() throws Exception {
        resetDatabase();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.MAX_PENDING_CONNECTIONS, 1);
        rawBackend = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        setupPassthrough("pending.example.test", "exact", rawBackend.getLocalPort(), false);
        startProxy();

        byte[] hello = clientHello("pending.example.test", false);
        try (Socket slow = new Socket("127.0.0.1", proxy.getHttpsAddress().getPort())) {
            slow.getOutputStream().write(hello[0]);
            slow.getOutputStream().flush();
            Thread.sleep(100);
            try (Socket excess = new Socket("127.0.0.1", proxy.getHttpsAddress().getPort())) {
                excess.setSoTimeout(3_000);
                excess.getOutputStream().write(hello);
                excess.getOutputStream().flush();
                assertThat(excess.getInputStream().read()).isEqualTo(-1);
            }
        }
    }

    @Test
    void equivalentRegexRoutesWithDifferentTargetsFailClosed() throws Exception {
        resetDatabase();
        rawBackend = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        rawBackend.setSoTimeout(1_000);
        try (ServerSocket secondBackend = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            secondBackend.setSoTimeout(1_000);
            setupPassthrough("conflict\\.example\\.test", "regex", rawBackend.getLocalPort(), false);
            setupPassthrough("/conflict\\.example\\.test/i", "regex", secondBackend.getLocalPort(), false);
            startProxy();

            try (Socket client = new Socket("127.0.0.1", proxy.getHttpsAddress().getPort())) {
                client.setSoTimeout(3_000);
                client.getOutputStream().write(clientHello("conflict.example.test", false));
                client.getOutputStream().flush();
                assertThat(client.getInputStream().read()).isEqualTo(-1);
            }
            assertThat(backendAccepted()).isFalse();
            assertThat(CompletableFuture.supplyAsync(() -> {
                try {
                    secondBackend.accept().close();
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }).get(3, TimeUnit.SECONDS)).isFalse();
        }
    }

    @Test
    void invalidPassthroughRegexDoesNotKeepAnOtherwiseUnusedTlsListenerRunning() throws Exception {
        resetDatabase();
        rawBackend = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        setupPassthrough("[invalid", "regex", rawBackend.getLocalPort(), false);
        startProxy();

        assertThat(proxy.getHttpsState()).isEqualTo(ProxyServer.State.STOPPED);
        assertThat(proxy.getHttpsAddress()).isNull();
    }

    @Test
    void passthroughTargetCannotLoopBackIntoThePublicTlsListener() throws Exception {
        resetDatabase();
        int publicPort;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            publicPort = reservation.getLocalPort();
        }
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, publicPort);
        setupPassthrough("loop.example.test", "exact", publicPort, false);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        proxy = new ProxyServer();
        proxy.start();

        try (Socket client = new Socket("127.0.0.1", publicPort)) {
            client.setSoTimeout(3_000);
            client.getOutputStream().write(clientHello("loop.example.test", false));
            client.getOutputStream().flush();
            assertThat(client.getInputStream().read()).isEqualTo(-1);
        }
        assertThat(proxy.getHttpsState()).isEqualTo(ProxyServer.State.RUNNING);
    }

    @Test
    void unreachableExactPassthroughNeverFallsThroughToWildcard() throws Exception {
        resetDatabase();
        int unusedPort;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            unusedPort = reservation.getLocalPort();
        }
        setupPassthrough("exact.example.test", "exact", unusedPort, false);
        rawBackend = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        rawBackend.setSoTimeout(1_500);
        setupPassthrough("*.example.test", "wildcard", rawBackend.getLocalPort(), false);
        startProxy();

        try (Socket client = new Socket("127.0.0.1", proxy.getHttpsAddress().getPort())) {
            client.setSoTimeout(3_000);
            client.getOutputStream().write(clientHello("exact.example.test", false));
            client.getOutputStream().flush();
            assertThat(client.getInputStream().read()).isEqualTo(-1);
        }
        assertThat(backendAccepted()).isFalse();
    }

    @Test
    void trustedProxyIdentityIsRestoredBeforeTerminatedHttpSecurityAndForwarding() throws Exception {
        resetDatabase();
        installCertificate("identity.example.test");
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Proxy.PROXY_PROTOCOL_TRUSTED_SOURCES, List.of("127.0.0.1"));
        AtomicReference<String> realIp = new AtomicReference<>();
        httpBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpBackend.createContext("/", exchange -> {
            realIp.set(exchange.getRequestHeaders().getFirst("X-Real-IP"));
            byte[] body = "identity".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        httpBackend.start();
        Row site = setupSite("hohenheim:address", "Identity", "identity", Map.of(
            "forward_host", "127.0.0.1", "forward_port", httpBackend.getAddress().getPort()));
        addDomain(site, "identity.example.test", "exact", null, false);
        startProxy();

        InetSocketAddress source = new InetSocketAddress(InetAddress.getByName("203.0.113.44"), 50123);
        InetSocketAddress destination = new InetSocketAddress(InetAddress.getByName("198.51.100.8"), 443);
        Socket plain = new Socket("127.0.0.1", proxy.getHttpsAddress().getPort());
        plain.getOutputStream().write(ProxyProtocolV2.encode(source, destination));
        plain.getOutputStream().flush();
        try (SSLSocket client = (SSLSocket) trustAllContext().getSocketFactory()
                .createSocket(plain, "identity.example.test", proxy.getHttpsAddress().getPort(), true)) {
            SSLParameters parameters = client.getSSLParameters();
            parameters.setServerNames(List.of(new SNIHostName("identity.example.test")));
            client.setSSLParameters(parameters);
            client.setSoTimeout(5_000);
            client.startHandshake();
            client.getOutputStream().write(("GET / HTTP/1.1\r\nHost: identity.example.test\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            assertThat(new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .contains("200 OK").contains("identity");
        }
        assertThat(realIp.get()).isEqualTo("203.0.113.44");
    }

    private void startProxy() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, 0);
        proxy = new ProxyServer();
        proxy.start();
    }

    private void setupPassthrough(String hostname, String matchType, int port, boolean proxyProtocol) {
        Row site = setupSite("hohenheim:tls_passthrough", "TLS " + hostname,
            hostname.replaceAll("[^a-z0-9]+", "-"), Map.of(
                "forward_host", "127.0.0.1",
                "forward_port", port,
                "proxy_protocol_v2", proxyProtocol));
        addDomain(site, hostname, matchType, null, false);
    }

    private CompletableFuture<Captured> captureOneConnection(boolean expectProxy) {
        return CompletableFuture.supplyAsync(() -> {
            try (Socket socket = rawBackend.accept()) {
                socket.setSoTimeout(5_000);
                BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                Optional<ProxyProtocolV2.Header> header = ProxyProtocolV2.readOptional(input);
                if (expectProxy && header.isEmpty()) throw new AssertionError("missing PROXY v2 header");
                TlsClientHelloReader.Result hello = TlsClientHelloReader.read(input);
                byte[] payload = input.readNBytes(4);
                socket.getOutputStream().write("PONG".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                return new Captured(header.orElse(null), hello.serverName().orElse(null),
                    hello.replayBytes(), payload);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private boolean backendAccepted() throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                rawBackend.accept().close();
                return true;
            } catch (Exception e) {
                return false;
            }
        }).get(3, TimeUnit.SECONDS);
    }

    private static void installCertificate(String hostname) throws Exception {
        KeyPair keyPair = TlsCertificateTest.generateKeyPair();
        X509Certificate certificate = TlsCertificateTest.generateSelfSignedCert(keyPair, hostname);
        var model = Models.get(CertificateModel.class);
        Row row = model.createEmptyRow();
        row.set(CertificateModel.NICE_NAME, "TLS mux test");
        row.set(CertificateModel.PROVIDER, "custom");
        row.set(CertificateModel.STATUS, "active");
        row.set(CertificateModel.CERTIFICATE_PEM, TlsCertificateTest.certToPem(certificate));
        row.set(CertificateModel.PRIVATE_KEY_PEM, TlsCertificateTest.keyToPem(keyPair));
        model.save(row);
    }

    private static SSLContext trustAllContext() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        return context;
    }

    private static byte[] clientHello(String hostname, boolean includeAlpn) throws Exception {
        byte[] host = hostname.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream extensions = new ByteArrayOutputStream();
        ByteArrayOutputStream sni = new ByteArrayOutputStream();
        writeShort(sni, host.length + 3);
        sni.write(0);
        writeShort(sni, host.length);
        sni.write(host);
        writeShort(extensions, 0);
        writeShort(extensions, sni.size());
        sni.writeTo(extensions);
        if (includeAlpn) {
            byte[] protocol = "h2".getBytes(StandardCharsets.US_ASCII);
            writeShort(extensions, 16);
            writeShort(extensions, protocol.length + 3);
            writeShort(extensions, protocol.length + 1);
            extensions.write(protocol.length);
            extensions.write(protocol);
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeShort(body, 0x0303);
        body.write(new byte[32]);
        body.write(0);
        writeShort(body, 2);
        writeShort(body, 0x1301);
        body.write(1);
        body.write(0);
        writeShort(body, extensions.size());
        extensions.writeTo(body);

        byte[] bodyBytes = body.toByteArray();
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        handshake.write(1);
        writeMedium(handshake, bodyBytes.length);
        handshake.write(bodyBytes);
        byte[] handshakeBytes = handshake.toByteArray();

        ByteArrayOutputStream record = new ByteArrayOutputStream();
        record.write(22);
        writeShort(record, 0x0303);
        writeShort(record, handshakeBytes.length);
        record.write(handshakeBytes);
        return record.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream output, int value) {
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void writeMedium(ByteArrayOutputStream output, int value) {
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }
}
