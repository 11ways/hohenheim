package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.proxy.ExchangeIdleTimeout;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.proxy.SiteDispatcher;
import be.elevenways.hohenheim.server.tls.UpstreamTrust;
import be.elevenways.hohenheim.server.upstream.TenantUpstreams;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.time.Pace;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.test.support.OutboundFixture;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.client.ClientCallback;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientExchange;
import io.undertow.client.ClientRequest;
import io.undertow.client.UndertowClient;
import io.undertow.server.DefaultByteBufferPool;
import io.undertow.server.protocol.http.HttpAttachments;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import io.undertow.util.StringReadChannelListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;
import org.xnio.XnioWorker;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static be.elevenways.hohenheim.test.ProxyTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upstream wire-protocol behavior: validated HTTPS upstreams (trust + hostname),
 * prior-knowledge h2c upstreams, gRPC trailer forwarding, per-site request
 * timeouts, and the gRPC bypass of the compression layer.
 */
class UpstreamProtocolTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static boolean initialized = false;
    private ProxyServer proxy;
    private HttpsServer httpsUpstream;
    private Undertow undertowUpstream;
    private com.sun.net.httpserver.HttpServer plainUpstream;
    /** The SNI names the HTTPS upstream stub was offered, one entry per name per exchange. */
    private final List<String> upstreamServerNames = new CopyOnWriteArrayList<>();
    /** Ends the streaming upstream's withheld body: completed once the test is done measuring it. */
    private final CompletableFuture<Void> streamBodyReleased = new CompletableFuture<>();

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) return;
        initialized = true;
        HohenheimEndpoints.init();
    }

    @AfterEach
    void cleanup() {
        SiteDispatcher.overrideTrustedUpstreamSslContextForTests(null);
        streamBodyReleased.complete(null);
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        if (httpsUpstream != null) {
            httpsUpstream.stop(0);
            httpsUpstream = null;
        }
        if (undertowUpstream != null) {
            undertowUpstream.stop();
            undertowUpstream = null;
        }
        if (plainUpstream != null) {
            plainUpstream.stop(0);
            plainUpstream = null;
        }
    }

    // -------------------------------------------------------------------
    // HTTPS upstream validation
    // -------------------------------------------------------------------

    private int startHttpsUpstream(KeyPair keyPair, X509Certificate cert) throws Exception {
        char[] password = "test".toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("key", keyPair.getPrivate(), password, new Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);

        httpsUpstream = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpsUpstream.setHttpsConfigurator(new HttpsConfigurator(context));
        httpsUpstream.createContext("/", ex -> {
            if (ex instanceof HttpsExchange tls && tls.getSSLSession() instanceof ExtendedSSLSession session) {
                for (SNIServerName name : session.getRequestedServerNames()) {
                    upstreamServerNames.add(((SNIHostName) name).getAsciiName());
                }
            }
            byte[] body = "tls-upstream".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        httpsUpstream.start();
        return httpsUpstream.getAddress().getPort();
    }

    private static Map<String, Object> httpsSettings(int port, boolean ignoreCertificates) {
        return Map.of(
            "forward_scheme", "https",
            "forward_host", "127.0.0.1",
            "forward_port", port,
            "ignore_certificates", ignoreCertificates);
    }

    @Test
    void httpsUpstreamWithUntrustedCertificateIsRejected() throws Exception {
        APP.renewDatabase();
        KeyPair keyPair = generateKeyPair();
        int port = startHttpsUpstream(keyPair, selfSignedCert(keyPair, "127.0.0.1", true));

        setupSiteWithDomain("hohenheim:address", "untrusted-tls.test", "exact",
            httpsSettings(port, false));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "untrusted-tls.test", "/");
        assertThat(response).doesNotContain("tls-upstream");
        // A failed upstream TLS handshake surfaces as ProxyHandler's 503 (no connection).
        assertThat(response).contains("503");
    }

    @Test
    void httpsUpstreamWithTrustedAuthorityAndMatchingHostWorks() throws Exception {
        APP.renewDatabase();
        KeyPair keyPair = generateKeyPair();
        X509Certificate cert = selfSignedCert(keyPair, "127.0.0.1", true);
        int port = startHttpsUpstream(keyPair, cert);

        SiteDispatcher.overrideTrustedUpstreamSslContextForTests(UpstreamTrust.contextTrusting(cert));
        setupSiteWithDomain("hohenheim:address", "trusted-tls.test", "exact",
            httpsSettings(port, false));

        proxy = startProxy();
        assertThat(rawRequest(httpPort(proxy), "trusted-tls.test", "/"))
            .contains("200")
            .contains("tls-upstream");
    }

    @Test
    void httpsUpstreamWithTrustedAuthorityButWrongHostIsRejected() throws Exception {
        APP.renewDatabase();
        KeyPair keyPair = generateKeyPair();
        // Chain will be trusted, but the SAN names a different host than the dial target.
        X509Certificate cert = selfSignedCert(keyPair, "other.internal", false);
        int port = startHttpsUpstream(keyPair, cert);

        SiteDispatcher.overrideTrustedUpstreamSslContextForTests(UpstreamTrust.contextTrusting(cert));
        setupSiteWithDomain("hohenheim:address", "wronghost-tls.test", "exact",
            httpsSettings(port, false));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "wronghost-tls.test", "/");
        assertThat(response).doesNotContain("tls-upstream");
        assertThat(response).contains("503");
    }

    @Test
    void ignoreCertificatesStillBypassesValidation() throws Exception {
        APP.renewDatabase();
        KeyPair keyPair = generateKeyPair();
        int port = startHttpsUpstream(keyPair, selfSignedCert(keyPair, "unrelated.host", false));

        setupSiteWithDomain("hohenheim:address", "ignored-tls.test", "exact",
            httpsSettings(port, true));

        proxy = startProxy();
        assertThat(rawRequest(httpPort(proxy), "ignored-tls.test", "/"))
            .contains("200")
            .contains("tls-upstream");
    }

    @Test
    void aTenantHttpsUpstreamIsDialedAtItsVettedAddressUnderItsOwnName() throws Exception {
        APP.renewDatabase();
        String upstreamHost = "tenant-upstream.test";
        KeyPair keyPair = generateKeyPair();
        X509Certificate cert = selfSignedCert(keyPair, upstreamHost, false);
        int port = startHttpsUpstream(keyPair, cert);
        SiteDispatcher.overrideTrustedUpstreamSslContextForTests(UpstreamTrust.contextTrusting(cert));

        // 1. A tenant-owned site forwards over TLS to a NAME, so every request is vetted.
        Row site = setupSite("hohenheim:address", "Tenant TLS", "tenant-tls", Map.of(
            "forward_scheme", "https",
            "forward_host", upstreamHost,
            "forward_port", 443));
        addDomain(site, "tenant-tls.test", "exact", null, false);
        UUID owner = ApiSupport.user("tenant-tls@hohenheim.local");
        RecordGrants.grant(GrantSubjectType.USER, owner, SiteModel.MODEL_ID, site.get(SiteModel.ID),
            HohenheimCapabilities.MANAGE, true);
        assertThat(TenantUpstreams.publicOnly(site)).as("step 1: the site is tenant-owned").isTrue();

        proxy = startProxy();
        try (OutboundFixture upstream = OutboundFixture.route(upstreamHost, port)) {
            // 2. The name exists only in zenit's outbound network: the system resolver knows no such
            //    host, so the answer can only come from dialing the address the guard vetted.
            assertThat(rawRequest(httpPort(proxy), "tenant-tls.test", "/"))
                .as("step 2: the proxy reached the upstream through the vetted address")
                .contains("200")
                .contains("tls-upstream");

            // 3. TLS still named the configured host: SNI carried it and the certificate, which names
            //    only that host, was accepted.
            assertThat(upstreamServerNames).as("step 3: SNI named the upstream host, not the address")
                .containsExactly(upstream.host());
        }
    }

    @Test
    void aTenantUpstreamFallsThroughARefusingAddressAndEveryNetworkJudgesItsOwn() throws Exception {
        APP.renewDatabase();
        String upstreamHost = "tenant-plain.test";
        int first = startPlainUpstream("plain-one", "http://" + upstreamHost + "/landing");
        com.sun.net.httpserver.HttpServer second = com.sun.net.httpserver.HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0), 0);
        second.createContext("/", ex -> {
            byte[] body = "plain-two".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        second.start();
        try {
            Row site = setupSite("hohenheim:address", "Tenant Plain", "tenant-plain", Map.of(
                "forward_host", upstreamHost,
                "forward_port", 80));
            addDomain(site, "tenant-plain.front.test", "exact", null, false);
            UUID owner = ApiSupport.user("tenant-plain@hohenheim.local");
            RecordGrants.grant(GrantSubjectType.USER, owner, SiteModel.MODEL_ID, site.get(SiteModel.ID),
                HohenheimCapabilities.MANAGE, true);
            assertThat(TenantUpstreams.publicOnly(site)).as("step 1: the site is tenant-owned").isTrue();
            proxy = startProxy();

            int refusing = FreePorts.tcpAndUdp();
            try (OutboundFixture upstream = OutboundFixture.route(upstreamHost, refusing, first)) {
                // 2. The name's first vetted address refuses the connection: the proxy dials the next
                //    vetted one instead of answering 503.
                assertThat(upstream.publicAddresses()).as("step 2: the name has two addresses").hasSize(2);
                assertThat(rawRequest(httpPort(proxy), "tenant-plain.front.test", "/"))
                    .as("step 2: the second vetted address answered")
                    .contains("200")
                    .contains("plain-one");

                // 3. The upstream's redirect names its own host, which the proxy still recognises
                //    beside the address it dialed: the visitor is sent to the public host.
                assertThat(rawRequest(httpPort(proxy), "tenant-plain.front.test", "/go"))
                    .as("step 3: the upstream redirect is rewritten to the public host")
                    .contains("Location: http://tenant-plain.front.test/landing");
            }

            // 4. The next fixture routes the same name to another upstream at once: the verdict
            //    remembered under the first network is not answered under this one.
            try (OutboundFixture next = OutboundFixture.route(upstreamHost, second.getAddress().getPort())) {
                assertThat(rawRequest(httpPort(proxy), "tenant-plain.front.test", "/"))
                    .as("step 4: the new network's own address answered")
                    .contains("200")
                    .contains("plain-two");
            }
        } finally {
            second.stop(0);
        }
    }

    /** A cleartext upstream answering {@code body}, and a redirect to {@code location} on /go. */
    private int startPlainUpstream(String body, String location) throws IOException {
        plainUpstream = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        plainUpstream.createContext("/", ex -> {
            if (ex.getRequestURI().getPath().equals("/go")) {
                ex.getResponseHeaders().add("Location", location);
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        plainUpstream.start();
        return plainUpstream.getAddress().getPort();
    }

    // -------------------------------------------------------------------
    // HTTP/2 upstreams
    // -------------------------------------------------------------------

    /**
     * h2c upstream that records the protocol it actually received and sends gRPC-style
     * trailers. The response is STREAMED (no content-length) like a real gRPC server:
     * with a fixed-length response Undertow folds the trailer supplier into the initial
     * HEADERS frame instead of emitting a trailer frame.
     */
    private int startH2cUpstream(AtomicReference<String> seenProtocol) {
        undertowUpstream = Undertow.builder()
            .addHttpListener(0, "127.0.0.1")
            .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
            .setHandler(exchange -> {
                if (seenProtocol != null) {
                    seenProtocol.set(exchange.getProtocol().toString());
                }
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/grpc");
                HeaderMap trailers = new HeaderMap();
                trailers.put(new HttpString("grpc-status"), "0");
                exchange.putAttachment(HttpAttachments.RESPONSE_TRAILER_SUPPLIER, () -> trailers);
                exchange.dispatch(() -> {
                    try {
                        var channel = exchange.getResponseChannel();
                        var buffer = java.nio.ByteBuffer.wrap(
                            "h2-upstream-payload".getBytes(StandardCharsets.UTF_8));
                        while (buffer.hasRemaining()) channel.write(buffer);
                        channel.shutdownWrites();
                        while (!channel.flush()) {
                            Thread.onSpinWait();
                        }
                        exchange.endExchange();
                    } catch (IOException e) {
                        exchange.endExchange();
                    }
                });
            })
            .build();
        undertowUpstream.start();
        return ((InetSocketAddress) undertowUpstream.getListenerInfo().get(0).getAddress()).getPort();
    }

    private static Map<String, Object> h2Settings(int port, int requestTimeoutSeconds) {
        return Map.of(
            "forward_host", "127.0.0.1",
            "forward_port", port,
            "upstream_protocol", "h2",
            "request_timeout", requestTimeoutSeconds);
    }

    @Test
    void http1DownstreamReachesH2cUpstream() throws Exception {
        APP.renewDatabase();
        AtomicReference<String> seenProtocol = new AtomicReference<>();
        int port = startH2cUpstream(seenProtocol);

        setupSiteWithDomain("hohenheim:address", "h2c.test", "exact", h2Settings(port, 0));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "h2c.test", "/hello");
        assertThat(response).contains("200");
        assertThat(response).contains("h2-upstream-payload");
        assertThat(seenProtocol.get()).isEqualTo("HTTP/2.0");
    }

    @Test
    void h2DownstreamGetsGrpcTrailersFromH2cUpstream() throws Exception {
        APP.renewDatabase();
        AtomicReference<String> seenProtocol = new AtomicReference<>();
        int upstreamPort = startH2cUpstream(seenProtocol);

        setupSiteWithDomain("hohenheim:address", "grpc.test", "exact", h2Settings(upstreamPort, 0));

        proxy = startProxy();
        int proxyPort = httpPort(proxy);

        XnioWorker worker = Xnio.getInstance().createWorker(
            OptionMap.create(Options.WORKER_IO_THREADS, 2));
        DefaultByteBufferPool pool = new DefaultByteBufferPool(true, 8192);
        ClientConnection connection = null;
        try {
            connection = UndertowClient.getInstance()
                .connect(URI.create("h2c-prior://127.0.0.1:" + proxyPort), worker, pool,
                    OptionMap.EMPTY)
                .get();

            CompletableFuture<Object[]> result = new CompletableFuture<>();
            ClientRequest request = new ClientRequest();
            request.setMethod(Methods.GET);
            request.setPath("/grpc.test.Service/Call");
            request.getRequestHeaders()
                .put(Headers.HOST, "grpc.test")
                .put(Headers.CONTENT_TYPE, "application/grpc");

            connection.sendRequest(request, new ClientCallback<ClientExchange>() {
                @Override
                public void completed(ClientExchange exchange) {
                    exchange.setResponseListener(new ClientCallback<ClientExchange>() {
                        @Override
                        public void completed(ClientExchange response) {
                            // Undertow's h2 client never surfaces incoming trailers on its
                            // own; hook the channel like any real gRPC-capable client would.
                            var channel = response.getResponseChannel();
                            if (channel instanceof io.undertow.protocols.http2.Http2StreamSourceChannel h2) {
                                h2.setTrailersHandler(map ->
                                    response.putAttachment(HttpAttachments.RESPONSE_TRAILERS, map));
                            }
                            new StringReadChannelListener(pool) {
                                @Override
                                protected void stringDone(String body) {
                                    result.complete(new Object[]{
                                        response.getResponse().getResponseCode(),
                                        body,
                                        response.getAttachment(HttpAttachments.RESPONSE_TRAILERS)});
                                }

                                @Override
                                protected void error(IOException e) {
                                    result.completeExceptionally(e);
                                }
                            }.setup(response.getResponseChannel());
                        }

                        @Override
                        public void failed(IOException e) {
                            result.completeExceptionally(e);
                        }
                    });
                }

                @Override
                public void failed(IOException e) {
                    result.completeExceptionally(e);
                }
            });

            Object[] outcome = result.get(10, TimeUnit.SECONDS);
            assertThat(outcome[0]).isEqualTo(200);
            assertThat(outcome[1]).isEqualTo("h2-upstream-payload");
            assertThat(seenProtocol.get()).isEqualTo("HTTP/2.0");

            HeaderMap trailers = (HeaderMap) outcome[2];
            assertThat(trailers).isNotNull();
            assertThat(trailers.getFirst(new HttpString("grpc-status"))).isEqualTo("0");
        } finally {
            if (connection != null) connection.close();
            worker.shutdownNow();
        }
    }

    @Test
    void http1UpstreamTrailersReachHttp1Downstream() throws Exception {
        APP.renewDatabase();
        // Same streaming upstream, dialed with plain HTTP/1.1: the chunked response
        // carries the trailers, and the downstream chunked response must too.
        int port = startH2cUpstream(null);

        setupSiteWithDomain("hohenheim:address", "h1trailers.test", "exact",
            Map.of("forward_host", "127.0.0.1", "forward_port", port, "request_timeout", 0));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "h1trailers.test", "/call",
            "TE: trailers");
        assertThat(response).contains("200");
        assertThat(response).contains("h2-upstream-payload");
        // The trailer line appears after the terminal chunk.
        assertThat(response).contains("grpc-status: 0");
    }

    @Test
    void requestTrailersAreForwardedToTheUpstream() throws Exception {
        APP.renewDatabase();

        CompletableFuture<Object[]> upstreamSaw = new CompletableFuture<>();
        undertowUpstream = Undertow.builder()
            .addHttpListener(0, "127.0.0.1")
            .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
            .setHandler(exchange -> exchange.getRequestReceiver().receiveFullString((ex, body) -> {
                HeaderMap requestTrailers = ex.getAttachment(HttpAttachments.REQUEST_TRAILERS);
                upstreamSaw.complete(new Object[]{body, requestTrailers});
                ex.getResponseSender().send("ok");
            }))
            .build();
        undertowUpstream.start();
        int upstreamPort = ((InetSocketAddress) undertowUpstream.getListenerInfo().get(0)
            .getAddress()).getPort();

        setupSiteWithDomain("hohenheim:address", "reqtrailers.test", "exact",
            h2Settings(upstreamPort, 0));

        proxy = startProxy();
        int proxyPort = httpPort(proxy);

        XnioWorker worker = Xnio.getInstance().createWorker(
            OptionMap.create(Options.WORKER_IO_THREADS, 2));
        DefaultByteBufferPool pool = new DefaultByteBufferPool(true, 8192);
        ClientConnection connection = null;
        try {
            connection = UndertowClient.getInstance()
                .connect(URI.create("h2c-prior://127.0.0.1:" + proxyPort), worker, pool,
                    OptionMap.EMPTY)
                .get();

            CompletableFuture<Integer> status = new CompletableFuture<>();
            ClientRequest request = new ClientRequest();
            request.setMethod(Methods.POST);
            request.setPath("/upload");
            request.getRequestHeaders().put(Headers.HOST, "reqtrailers.test");

            connection.sendRequest(request, new ClientCallback<ClientExchange>() {
                @Override
                public void completed(ClientExchange exchange) {
                    // Outgoing request trailers: the h2 client's TrailersProducer reads
                    // the RESPONSE_TRAILERS attachment at the final frame.
                    HeaderMap outgoing = new HeaderMap();
                    outgoing.put(new HttpString("x-client-note"), "hello");
                    exchange.putAttachment(HttpAttachments.RESPONSE_TRAILERS, outgoing);
                    try {
                        var channel = exchange.getRequestChannel();
                        var buffer = java.nio.ByteBuffer.wrap(
                            "request-body".getBytes(StandardCharsets.UTF_8));
                        while (buffer.hasRemaining()) channel.write(buffer);
                        channel.shutdownWrites();
                        while (!channel.flush()) {
                            Thread.onSpinWait();
                        }
                    } catch (IOException e) {
                        status.completeExceptionally(e);
                        return;
                    }
                    exchange.setResponseListener(new ClientCallback<ClientExchange>() {
                        @Override
                        public void completed(ClientExchange response) {
                            status.complete(response.getResponse().getResponseCode());
                        }

                        @Override
                        public void failed(IOException e) {
                            status.completeExceptionally(e);
                        }
                    });
                }

                @Override
                public void failed(IOException e) {
                    status.completeExceptionally(e);
                }
            });

            assertThat(status.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            Object[] seen = upstreamSaw.get(10, TimeUnit.SECONDS);
            assertThat(seen[0]).isEqualTo("request-body");
            HeaderMap trailers = (HeaderMap) seen[1];
            assertThat(trailers).isNotNull();
            assertThat(trailers.getFirst(new HttpString("x-client-note"))).isEqualTo("hello");
        } finally {
            if (connection != null) connection.close();
            worker.shutdownNow();
        }
    }

    // -------------------------------------------------------------------
    // Request timeouts
    // -------------------------------------------------------------------

    private int startSlowUpstream(long delayMs) throws Exception {
        plainUpstream = com.sun.net.httpserver.HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0), 0);
        plainUpstream.createContext("/", ex -> {
            try {
                Pace.sleep(Duration.ofMillis(delayMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "slow-upstream".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        plainUpstream.start();
        return plainUpstream.getAddress().getPort();
    }

    /**
     * AIDEV-NOTE: the upstream delay is 1500ms, not 3000ms, and the number is load-bearing.
     * rawRequest reads with a 5000ms socket timeout, so a 3000ms upstream left only 2s of
     * slack -- and the unlimited half read an EMPTY response (a full-suite failure that
     * passed in isolation) whenever the machine overshot that. 1500ms is still comfortably
     * past the 1s request_timeout the limited half proves, and load can only push the
     * upstream LATER, never earlier, so the kill stays certain while the slack triples.
     */
    @Test
    void shortRequestTimeoutKillsSlowExchangesButZeroMeansUnlimited() throws Exception {
        APP.renewDatabase();
        int port = startSlowUpstream(1500);

        Row limited = setupSite("hohenheim:address", "Limited Site", "limited-site", Map.of(
            "forward_host", "127.0.0.1", "forward_port", port, "request_timeout", 1));
        addDomain(limited, "limited.test", "exact", null, false);

        Row unlimited = setupSite("hohenheim:address", "Unlimited Site", "unlimited-site", Map.of(
            "forward_host", "127.0.0.1", "forward_port", port, "request_timeout", 0));
        addDomain(unlimited, "unlimited.test", "exact", null, false);

        proxy = startProxy();
        int proxyPort = httpPort(proxy);

        // 1s limit against a 1.5s upstream: a definite 504, never the payload.
        String killed = rawRequest(proxyPort, "limited.test", "/");
        assertThat(killed).doesNotContain("slow-upstream");
        assertThat(killed).contains("504");

        // 0 = unlimited: the same slow upstream completes.
        assertThat(rawRequest(proxyPort, "unlimited.test", "/"))
            .contains("200")
            .contains("slow-upstream");
    }

    // -------------------------------------------------------------------
    // Idle timeout
    // -------------------------------------------------------------------

    /** The gap between two trickled bytes: well inside the 1 s idle time the journey sets. */
    private static final Duration TRICKLE = Duration.ofMillis(300);

    /** What a raw HTTP/1.1 client read: the bytes, whether the connection ended (EOF or reset), and when. */
    private record RawAnswer(String text, boolean ended, long millis) {

        String statusLine() {
            int end = this.text.indexOf("\r\n");
            return end < 0 ? this.text : this.text.substring(0, end);
        }
    }

    /**
     * A raw HTTP/1.1 upstream, one request per connection, whose path picks its behaviour: {@code /silent} never
     * answers, {@code /started} sends its headers and one chunk and then nothing, {@code /trickle-down} sends five
     * chunks one {@link #TRICKLE} apart ({@code /trickle-long} eight), {@code /trickle-up} and {@code /large} read the
     * whole body first, {@code /slow} answers after 1.5 s, {@code /upgrade} switches protocols and speaks again after
     * 1.5 s of silence.
     *
     * AIDEV-NOTE: raw sockets, not Undertow, because the journey must see the PROXY close its upstream connection: a
     * blocking read here returns -1 the moment it does, where a server framework only notices on its next write.
     */
    private static final class RawUpstream implements AutoCloseable {

        private final ServerSocket server;
        private final Map<String, CompletableFuture<Void>> closed = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> dials = new ConcurrentHashMap<>();
        private final AtomicLong firstBodyByteAt = new AtomicLong();

        RawUpstream() throws IOException {
            this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(this::accept, "raw-upstream-accept");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return this.server.getLocalPort();
        }

        /** @return completed once the proxy closed the connection that asked for {@code path} */
        CompletableFuture<Void> closedFor(String path) {
            return this.closed.computeIfAbsent(path, any -> new CompletableFuture<>());
        }

        /** @return how many connections asked for {@code path} */
        int dialsFor(String path) {
            AtomicInteger count = this.dials.get(path);
            return count == null ? 0 : count.get();
        }

        private void accept() {
            while (!this.server.isClosed()) {
                try {
                    Socket socket = this.server.accept();
                    Thread worker = new Thread(() -> serve(socket), "raw-upstream-conn");
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException stopped) {
                    return;
                }
            }
        }

        private void serve(Socket socket) {
            try (socket) {
                // Past Undertow's 10 s drain, so a held connection sees the proxy's final close.
                socket.setSoTimeout(20_000);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                String head = readHead(in);
                String path = head.substring(head.indexOf(' ') + 1, head.indexOf(' ', head.indexOf(' ') + 1));
                this.dials.computeIfAbsent(path, any -> new AtomicInteger()).incrementAndGet();
                if (path.startsWith("/silent")) {
                    awaitClose(in, path);
                } else if (path.startsWith("/started")) {
                    out.write(CHUNKED_OK);
                    writeChunk(out, "part-one");
                    awaitClose(in, path);
                } else if (path.startsWith("/trickle-down") || path.startsWith("/trickle-long")) {
                    out.write(CHUNKED_OK);
                    int chunks = path.startsWith("/trickle-long") ? 8 : 5;
                    for (int index = 1; index <= chunks; index++) {
                        if (index > 1) {
                            Pace.sleep(TRICKLE);
                        }
                        writeChunk(out, "t" + index);
                    }
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } else if (path.startsWith("/trickle-up") || path.startsWith("/large")) {
                    long length = contentLength(head);
                    long read = 0;
                    byte[] buffer = new byte[65536];
                    while (read < length) {
                        int count = in.read(buffer);
                        if (count < 0) {
                            break;
                        }
                        if (read == 0 && count > 0) {
                            this.firstBodyByteAt.compareAndSet(0, Now.monotonicNanos());
                        }
                        read += count;
                    }
                    answer(out, "got-" + read);
                } else if (path.startsWith("/slow")) {
                    Pace.sleep(Duration.ofMillis(1500));
                    answer(out, "slow-done");
                } else if (path.startsWith("/upgrade")) {
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Pace.sleep(Duration.ofMillis(1500));
                    out.write("tunnel-alive".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    awaitClose(in, path);
                } else if (path.startsWith("/events")) {
                    // An event stream of keep-alive comments, one per TRICKLE: eight, or (/events-long) until the
                    // proxy hangs up, which completes closedFor.
                    out.write(EVENTS_OK);
                    int events = path.startsWith("/events-long") ? 100 : 8;
                    try {
                        for (int index = 1; index <= events; index++) {
                            if (index > 1) {
                                Pace.sleep(TRICKLE);
                            }
                            writeChunk(out, ": k" + index + "\n\n");
                        }
                        out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    } catch (IOException hungUp) {
                        closedFor(path).complete(null);
                    }
                }
            } catch (IOException | InterruptedException | RuntimeException ended) {
                // The proxy hung up or the test is over; the futures already say what the journey needs.
            }
        }

        private void awaitClose(InputStream in, String path) throws IOException {
            try {
                while (in.read() >= 0) {
                    // Drain whatever the proxy still sends until it closes.
                }
            } catch (SocketTimeoutException stillOpen) {
                return;
            } catch (IOException reset) {
                // A reset is a close too.
            }
            closedFor(path).complete(null);
        }

        private static String readHead(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int next = in.read();
                if (next < 0) {
                    throw new IOException("closed before the request head ended");
                }
                head.write(next);
                matched = next == "\r\n\r\n".charAt(matched) ? matched + 1 : (next == '\r' ? 1 : 0);
            }
            return head.toString(StandardCharsets.US_ASCII);
        }

        private static long contentLength(String head) {
            for (String line : head.split("\r\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    return Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            return 0;
        }

        /** The head of a 200 whose body follows in chunks. */
        private static final byte[] CHUNKED_OK =
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

        /** The head of an event stream, its events following in chunks. */
        private static final byte[] EVENTS_OK = ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
            + "Transfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.US_ASCII);

        private static void writeChunk(OutputStream out, String text) throws IOException {
            String chunk = Integer.toHexString(text.length()) + "\r\n" + text + "\r\n";
            out.write(chunk.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        private static void answer(OutputStream out, String body) throws IOException {
            out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n" + body)
                .getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        @Override
        public void close() throws IOException {
            this.server.close();
        }
    }

    /** One raw HTTP/1.1 exchange: a GET, or a POST whose {@code trickled} body bytes go one per {@link #TRICKLE}. */
    private static RawAnswer raw(int port, String host, String path, int trickled) throws Exception {
        return raw(port, host, path, trickled, "");
    }

    /** {@link #raw}, with further request header lines ({@code "Name: value\r\n"} each). */
    private static RawAnswer raw(int port, String host, String path, int trickled, String headers) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            Now.Stopwatch took = Now.stopwatch();
            OutputStream out = socket.getOutputStream();
            StringBuilder head = new StringBuilder(trickled > 0 ? "POST " : "GET ").append(path)
                .append(" HTTP/1.1\r\nHost: ").append(host).append("\r\nConnection: close\r\n").append(headers);
            if (trickled > 0) {
                head.append("Content-Length: ").append(trickled).append("\r\n");
            }
            out.write(head.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            for (int index = 0; index < trickled; index++) {
                if (index > 0) {
                    Pace.sleep(TRICKLE);
                }
                out.write('x');
                out.flush();
            }
            ByteArrayOutputStream seen = new ByteArrayOutputStream();
            boolean ended = true;
            try {
                InputStream in = socket.getInputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) >= 0) {
                    seen.write(buffer, 0, count);
                }
            } catch (SocketTimeoutException stillOpen) {
                ended = false;
            } catch (IOException reset) {
                // A reset ends the connection as an EOF does.
            }
            return new RawAnswer(seen.toString(StandardCharsets.US_ASCII), ended, took.elapsedNanos() / 1_000_000);
        }
    }

    /** @return the status and body of an h2c stream, or "reset" when it failed before its end */
    private static CompletableFuture<String> h2Stream(ClientConnection connection, DefaultByteBufferPool pool,
                                                      String host, String path) {
        CompletableFuture<String> result = new CompletableFuture<>();
        ClientRequest request = new ClientRequest();
        request.setMethod(Methods.GET);
        request.setPath(path);
        request.getRequestHeaders().put(Headers.HOST, host);
        connection.sendRequest(request, new ClientCallback<ClientExchange>() {
            @Override
            public void completed(ClientExchange exchange) {
                exchange.setResponseListener(new ClientCallback<ClientExchange>() {
                    @Override
                    public void completed(ClientExchange response) {
                        new StringReadChannelListener(pool) {
                            @Override
                            protected void stringDone(String body) {
                                result.complete(response.getResponse().getResponseCode() + " " + body);
                            }

                            @Override
                            protected void error(IOException e) {
                                result.complete("reset");
                            }
                        }.setup(response.getResponseChannel());
                    }

                    @Override
                    public void failed(IOException e) {
                        result.complete("reset");
                    }
                });
            }

            @Override
            public void failed(IOException e) {
                result.complete("reset");
            }
        });
        return result;
    }

    /**
     * An idle timeout ends an exchange on which nothing moved, in either direction and on both protocols, closes its
     * upstream, and leaves alone what keeps moving, what sets no idle time and what was upgraded; the request timeout
     * still ends the whole exchange as before. Guards (pass before the change): steps 6, 7 and 8.
     *
     * AIDEV-NOTE: the upstream closes are asked last (step 9). Undertow's ClientConnection.close is a CLEAN close:
     * with a request in flight its shutdown reaches no FIN, so it drains until io.undertow.max-drain-time (10 s) and
     * then closes for good. Undertow's own request timeout closes its upstream the same way.
     */
    @Test
    void anIdleTimeoutCutsASilentExchangeAndClosesItsUpstreamJourney() throws Exception {
        APP.renewDatabase();
        try (RawUpstream upstream = new RawUpstream()) {
            int port = upstream.port();
            setupSiteWithDomain("hohenheim:address", "idle.test", "exact", Map.of("forward_host", "127.0.0.1",
                "forward_port", port, "request_timeout", 0, "idle_timeout", 1, "websocket_upgrade", true));
            setupSiteWithDomain("hohenheim:address", "unset.test", "exact", Map.of("forward_host", "127.0.0.1",
                "forward_port", port, "request_timeout", 0));
            setupSiteWithDomain("hohenheim:address", "total.test", "exact", Map.of("forward_host", "127.0.0.1",
                "forward_port", port, "request_timeout", 1, "idle_timeout", 3));
            proxy = startProxy();
            int proxyPort = httpPort(proxy);

            // 1. HTTP/1.1, a silent upstream, nothing answered yet: the exchange is answered with the request timeout's
            //    own status (step 7 compares). Its upstream closes in step 9.
            // 2. The response started, then silence: the client's connection ends after the part it got, and the
            //    upstream is closed (step 9). Both are asked together.
            CompletableFuture<RawAnswer> silent = CompletableFuture.supplyAsync(() -> rawQuietly(proxyPort,
                "idle.test", "/silent/1", 0));
            CompletableFuture<RawAnswer> started = CompletableFuture.supplyAsync(() -> rawQuietly(proxyPort,
                "idle.test", "/started/2", 0));
            RawAnswer idleCut = silent.get(6, TimeUnit.SECONDS);
            assertThat(idleCut.statusLine()).as("step 1: answered by the cut").startsWith("HTTP/1.1 5");
            assertThat(idleCut.millis()).as("step 1: after the idle time, not a later limit").isLessThan(3000);
            RawAnswer startedCut = started.get(6, TimeUnit.SECONDS);
            assertThat(startedCut.text()).as("step 2: the part sent before the silence arrived")
                .startsWith("HTTP/1.1 200").contains("part-one");
            assertThat(startedCut.ended()).as("step 2: the connection ended, no read timed out").isTrue();
            assertThat(startedCut.text()).as("step 2: never the end of a chunked body").doesNotContain("\r\n0\r\n");

            // 3. A response trickling a chunk every 300 ms for 1.2 s is never cut.
            assertThat(raw(proxyPort, "idle.test", "/trickle-down/3", 0).text())
                .as("step 3: a moving response completes").startsWith("HTTP/1.1 200").contains("t5");

            // 4. Neither is an upload trickling a byte every 300 ms.
            assertThat(raw(proxyPort, "idle.test", "/trickle-up/4", 5).text())
                .as("step 4: a moving upload completes").startsWith("HTTP/1.1 200").contains("got-5");

            // 5. HTTP/2: the silent stream is reset alone, a sibling that keeps moving on the same connection
            //    completes; the silent stream's upstream closes in step 9.
            XnioWorker worker = Xnio.getInstance().createWorker(OptionMap.create(Options.WORKER_IO_THREADS, 2));
            DefaultByteBufferPool pool = new DefaultByteBufferPool(true, 8192);
            ClientConnection connection = null;
            try {
                connection = UndertowClient.getInstance()
                    .connect(URI.create("h2c-prior://127.0.0.1:" + proxyPort), worker, pool, OptionMap.EMPTY)
                    .get();
                CompletableFuture<String> quiet = h2Stream(connection, pool, "idle.test", "/started/5");
                CompletableFuture<String> moving = h2Stream(connection, pool, "idle.test", "/trickle-down/5");
                assertThat(quiet.get(6, TimeUnit.SECONDS)).as("step 5: the silent stream is reset")
                    .isEqualTo("reset");
                assertThat(moving.get(6, TimeUnit.SECONDS)).as("step 5: its sibling completes")
                    .isEqualTo("200 t1t2t3t4t5");
            } finally {
                if (connection != null) {
                    connection.close();
                }
                worker.shutdownNow();
            }

            // 6. No idle setting: an exchange silent for 1.5 s completes and installs no idle state; every exchange
            //    above left none behind either.
            long installed = ExchangeIdleTimeout.installedStates();
            assertThat(raw(proxyPort, "unset.test", "/slow/6", 0).text())
                .as("step 6: unset cuts nothing").startsWith("HTTP/1.1 200").contains("slow-done");
            assertThat(ExchangeIdleTimeout.installedStates()).as("step 6: and installs no idle state")
                .isEqualTo(installed);
            assertThat(ExchangeIdleTimeout.liveStates()).as("step 6: no armed idle timer outlives its exchange")
                .isZero();

            // 7. The request timeout still ends the whole exchange: a silent one at 1 s, before its 3 s idle time,
            //    with the status the idle cut gave, and a moving one too (its last chunk would come at 2.1 s).
            CompletableFuture<RawAnswer> totalSilent = CompletableFuture.supplyAsync(() -> rawQuietly(proxyPort,
                "total.test", "/silent/7", 0));
            CompletableFuture<RawAnswer> totalMoving = CompletableFuture.supplyAsync(() -> rawQuietly(proxyPort,
                "total.test", "/trickle-long/7", 0));
            RawAnswer timedOut = totalSilent.get(6, TimeUnit.SECONDS);
            assertThat(timedOut.millis()).as("step 7: the request timeout, not the idle one").isLessThan(2500);
            assertThat(idleCut.statusLine()).as("step 7: the idle cut answers as the request timeout does")
                .isEqualTo(timedOut.statusLine());
            assertThat(totalMoving.get(6, TimeUnit.SECONDS).text()).as("step 7: a moving exchange is ended too")
                .contains("t1").doesNotContain("t8");

            // 8. An upgraded connection is not cut: its tunnel speaks again after 1.5 s of silence.
            try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
                socket.setSoTimeout(4000);
                socket.getOutputStream().write(("GET /upgrade/8 HTTP/1.1\r\nHost: idle.test\r\nConnection: Upgrade\r\n"
                    + "Upgrade: websocket\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                InputStream in = socket.getInputStream();
                ByteArrayOutputStream seen = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while (!seen.toString(StandardCharsets.US_ASCII).contains("tunnel-alive")
                        && (count = in.read(buffer)) >= 0) {
                    seen.write(buffer, 0, count);
                }
                assertThat(seen.toString(StandardCharsets.US_ASCII)).as("step 8: switched")
                    .startsWith("HTTP/1.1 101")
                    .as("step 8: and the silent tunnel still carries bytes").contains("tunnel-alive");
            }

            // 9. Every cut closed its upstream, within Undertow's drain bound, and the retry Undertow starts after
            //    closing an idempotent request's upstream never dialed again.
            for (String path : List.of("/silent/1", "/started/2", "/started/5")) {
                upstream.closedFor(path).get(15, TimeUnit.SECONDS);
                assertThat(upstream.dialsFor(path)).as("step 9: %s was dialed once", path).isEqualTo(1);
            }

            // 10. An event stream, as an MCP client's notification stream: kept open on purpose, it outlives the 1 s
            //     request timeout while its keep-alives move (eight, 2.1 s), where an ordinary exchange is ended (step
            //     7); a silent one is still cut once it said nothing for the request timeout.
            String accepts = "Accept: text/event-stream\r\n";
            RawAnswer kept = raw(proxyPort, "total.test", "/events/10", 0, accepts);
            assertThat(kept.text()).as("step 10: every keep-alive arrived").startsWith("HTTP/1.1 200")
                .contains(": k8").contains("\r\n0\r\n");
            assertThat(kept.millis()).as("step 10: past the request timeout").isGreaterThan(1500);
            RawAnswer quietStream = raw(proxyPort, "total.test", "/started/10", 0, accepts);
            assertThat(quietStream.ended()).as("step 10: a silent stream is cut").isTrue();
            assertThat(quietStream.millis()).as("step 10: after the request timeout of silence").isLessThan(2500);

            // 11. A client hanging up its event stream is no proxy failure: nothing is logged at ERROR for it, and the
            //     upstream is closed at its next keep-alive. An ordinary exchange the request timeout ends is the
            //     control: Undertow logs that one at ERROR, so the capture hears what it would.
            List<LogRecord> severe = new CopyOnWriteArrayList<>();
            Handler capture = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                        severe.add(record);
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            Logger undertow = Logger.getLogger("io.undertow");
            undertow.addHandler(capture);
            try {
                raw(proxyPort, "total.test", "/silent/11", 0);
                Poll.until("step 11 control: a timed out exchange is logged at ERROR", Duration.ofSeconds(5),
                    () -> severe.stream().anyMatch(each -> String.valueOf(each.getMessage()).contains("/silent/11")));
                try (Socket socket = new Socket("127.0.0.1", proxyPort)) {
                    socket.setSoTimeout(5000);
                    socket.getOutputStream().write(("GET /events-long/11 HTTP/1.1\r\nHost: total.test\r\n" + accepts
                        + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    InputStream in = socket.getInputStream();
                    ByteArrayOutputStream seen = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int count;
                    while (!seen.toString(StandardCharsets.US_ASCII).contains(": k2")
                            && (count = in.read(buffer)) >= 0) {
                        seen.write(buffer, 0, count);
                    }
                    assertThat(seen.toString(StandardCharsets.US_ASCII)).as("step 11: the stream ran")
                        .contains(": k2");
                }
                upstream.closedFor("/events-long/11").get(15, TimeUnit.SECONDS);
                assertThat(severe).as("step 11: no proxy failure logged for the hung up stream")
                    .extracting(each -> String.valueOf(each.getMessage())).noneMatch(message ->
                        message.contains("/events-long/11"));
            } finally {
                undertow.removeHandler(capture);
            }
        }
    }

    /** {@link #raw}, for an async step; a failure surfaces through the future. */
    private static RawAnswer rawQuietly(int port, String host, String path, int trickled) {
        try {
            return raw(port, host, path, trickled);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A large upload streams through: the upstream reads its first byte before the client wrote its last. */
    @Test
    void aLargeUploadStreamsThroughUnbuffered() throws Exception {
        APP.renewDatabase();
        try (RawUpstream upstream = new RawUpstream()) {
            setupSiteWithDomain("hohenheim:address", "large.test", "exact", Map.of("forward_host", "127.0.0.1",
                "forward_port", upstream.port(), "request_timeout", 0));
            proxy = startProxy();
            long size = 100L * 1024 * 1024;
            try (Socket socket = new Socket("127.0.0.1", httpPort(proxy))) {
                socket.setSoTimeout(20_000);
                OutputStream out = socket.getOutputStream();
                out.write(("POST /large HTTP/1.1\r\nHost: large.test\r\nConnection: close\r\nContent-Length: " + size
                    + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                byte[] block = new byte[1024 * 1024];
                for (long sent = 0; sent < size; sent += block.length) {
                    out.write(block);
                }
                out.flush();
                long lastSentAt = Now.monotonicNanos();
                String answer = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                // 1. Every byte arrived.
                assertThat(answer).as("step 1: the upstream read the whole body").contains("got-" + size);
                // 2. And it arrived while the client was still sending: the proxy holds no copy of the body.
                assertThat(upstream.firstBodyByteAt.get()).as("step 2: the first byte reached the upstream first")
                    .isPositive().isLessThan(lastSentAt);
            }
        }
    }

    // -------------------------------------------------------------------
    // Streamed response headers
    // -------------------------------------------------------------------

    /**
     * h2c upstream that commits its response headers immediately and withholds the body
     * until the test released it ({@link #streamBodyReleased}), {@code delayMs} at most --
     * the shape of every long-lived streaming gRPC service (NetBird's SignalExchange sends
     * a registration header on stream open, then nothing until a peer message arrives).
     */
    private int startHeadersThenDelayedBodyUpstream(long delayMs) {
        undertowUpstream = Undertow.builder()
            .addHttpListener(0, "127.0.0.1")
            .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
            .setHandler(exchange -> {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/grpc");
                exchange.getResponseHeaders().put(new HttpString("x-stream-open"), "1");
                exchange.dispatch(() -> {
                    try {
                        var channel = exchange.getResponseChannel();
                        // Commit the HEADERS frame with no body yet.
                        while (!channel.flush()) {
                            Thread.onSpinWait();
                        }
                        try {
                            Pace.await(streamBodyReleased, Duration.ofMillis(delayMs));
                        } catch (TimeoutException bounded) {
                            // Never released: the body follows at its bound all the same.
                        }
                        var buffer = java.nio.ByteBuffer.wrap(
                            "stream-payload".getBytes(StandardCharsets.UTF_8));
                        while (buffer.hasRemaining()) channel.write(buffer);
                        channel.shutdownWrites();
                        while (!channel.flush()) {
                            Thread.onSpinWait();
                        }
                        exchange.endExchange();
                    } catch (IOException | InterruptedException | ExecutionException e) {
                        exchange.endExchange();
                    }
                });
            })
            .build();
        undertowUpstream.start();
        return ((InetSocketAddress) undertowUpstream.getListenerInfo().get(0).getAddress()).getPort();
    }

    /** Milliseconds until the response HEADERS arrive, measured with an h2c client. */
    private static long millisToResponseHeaders(int port, String host, String path) throws Exception {
        XnioWorker worker = Xnio.getInstance().createWorker(
            OptionMap.create(Options.WORKER_IO_THREADS, 2));
        DefaultByteBufferPool pool = new DefaultByteBufferPool(true, 8192);
        ClientConnection connection = null;
        try {
            connection = UndertowClient.getInstance()
                .connect(URI.create("h2c-prior://127.0.0.1:" + port), worker, pool, OptionMap.EMPTY)
                .get();

            CompletableFuture<Long> headersAt = new CompletableFuture<>();
            ClientRequest request = new ClientRequest();
            request.setMethod(Methods.GET);
            request.setPath(path);
            request.getRequestHeaders()
                .put(Headers.HOST, host)
                .put(Headers.CONTENT_TYPE, "application/grpc");

            long start = System.nanoTime();
            connection.sendRequest(request, new ClientCallback<ClientExchange>() {
                @Override
                public void completed(ClientExchange exchange) {
                    exchange.setResponseListener(new ClientCallback<ClientExchange>() {
                        @Override
                        public void completed(ClientExchange response) {
                            headersAt.complete((System.nanoTime() - start) / 1_000_000);
                        }

                        @Override
                        public void failed(IOException e) {
                            headersAt.completeExceptionally(e);
                        }
                    });
                }

                @Override
                public void failed(IOException e) {
                    headersAt.completeExceptionally(e);
                }
            });
            return headersAt.get(20, TimeUnit.SECONDS);
        } finally {
            if (connection != null) connection.close();
            worker.shutdownNow();
        }
    }

    /**
     * A streaming response must hand its headers to the client as soon as the upstream
     * sends them, not when the first body byte shows up. gRPC clients read the opening
     * metadata to consider the stream established: holding the HEADERS frame until data
     * arrives deadlocks any stream that is idle by design (NetBird's signal service sat
     * for 50s and then gave up with "didn't receive a registration header").
     */
    @Test
    void streamedResponseHeadersReachTheClientBeforeTheBody() throws Exception {
        APP.renewDatabase();
        long bodyDelayMs = 4000;
        int upstreamPort = startHeadersThenDelayedBodyUpstream(bodyDelayMs);

        setupSiteWithDomain("hohenheim:address", "stream.test", "exact",
            h2Settings(upstreamPort, 0));

        proxy = startProxy();

        // Control: the upstream itself commits headers immediately, so a slow
        // measurement below can only be the proxy holding them back.
        long directMs = millisToResponseHeaders(upstreamPort, "stream.test", "/direct");
        assertThat(directMs)
            .as("fixture sanity: the upstream commits its headers before the body")
            .isLessThan(bodyDelayMs / 2);

        long proxiedMs = millisToResponseHeaders(httpPort(proxy), "stream.test", "/proxied");
        assertThat(proxiedMs)
            .as("the proxy must forward response headers on arrival, not on first body byte")
            .isLessThan(bodyDelayMs / 2);
    }

    /** Records the path of every request the upstream actually receives. */
    private final java.util.concurrent.BlockingQueue<String> upstreamSawPaths =
        new java.util.concurrent.LinkedBlockingQueue<>();

    /**
     * Upstream that reports each request the moment its headers arrive, whether or not a
     * request body follows -- a bidirectional gRPC service is invoked on stream open
     * (NetBird's SignalExchange registers the peer from the request metadata alone).
     */
    private int startRecordingUpstream() {
        undertowUpstream = Undertow.builder()
            .addHttpListener(0, "127.0.0.1")
            .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
            .setHandler(exchange -> {
                upstreamSawPaths.add(exchange.getRequestPath());
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/grpc");
                exchange.setStatusCode(200);
                exchange.endExchange();
            })
            .build();
        undertowUpstream.start();
        return ((InetSocketAddress) undertowUpstream.getListenerInfo().get(0).getAddress()).getPort();
    }

    /**
     * Opens an h2c stream with HEADERS only: no END_STREAM, no DATA -- exactly how a
     * bidirectional gRPC client starts a stream before it has a message to send -- and
     * keeps it open until the upstream reports a request.
     *
     * Hand-rolled because neither Undertow client can express it: they write the HEADERS
     * frame lazily, on the first body write, so a body-less request never reaches the wire
     * at all. That same laziness is what the proxy trips over, which is why this test needs
     * to own the bytes. HPACK here is literal-without-indexing (0x00) and never Huffman,
     * which every HTTP/2 implementation must accept.
     *
     * @return the path the upstream saw while the stream was still open, null when it saw none within 5 s
     */
    private String sendRawH2cHeadersOnly(int port, String host, String path) throws Exception {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setTcpNoDelay(true);
            var out = socket.getOutputStream();

            out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            writeFrame(out, 0x4, 0x0, 0, new byte[0]);                  // empty SETTINGS
            writeFrame(out, 0x4, 0x1, 0, new byte[0]);                  // SETTINGS ACK

            var hpack = new java.io.ByteArrayOutputStream();
            writeLiteralHeader(hpack, ":method", "POST");
            writeLiteralHeader(hpack, ":path", path);
            writeLiteralHeader(hpack, ":scheme", "http");
            writeLiteralHeader(hpack, ":authority", host);
            writeLiteralHeader(hpack, "content-type", "application/grpc");
            writeLiteralHeader(hpack, "te", "trailers");
            // END_HEADERS (0x4) only: END_STREAM is deliberately NOT set.
            writeFrame(out, 0x1, 0x4, 1, hpack.toByteArray());
            out.flush();

            return upstreamSawPaths.poll(5, TimeUnit.SECONDS);
        }
    }

    private static void writeFrame(java.io.OutputStream out, int type, int flags, int streamId,
                                   byte[] payload) throws IOException {
        out.write(new byte[] {
            (byte) (payload.length >>> 16), (byte) (payload.length >>> 8), (byte) payload.length,
            (byte) type, (byte) flags,
            (byte) (streamId >>> 24), (byte) (streamId >>> 16),
            (byte) (streamId >>> 8), (byte) streamId});
        out.write(payload);
    }

    private static void writeLiteralHeader(java.io.ByteArrayOutputStream out, String name,
                                           String value) {
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        byte[] v = value.getBytes(StandardCharsets.US_ASCII);
        if (n.length > 126 || v.length > 126) {
            throw new IllegalArgumentException("test helper only encodes short header fields");
        }
        out.write(0x00);                // literal header field, never indexed, new name
        out.write(n.length);            // 7-bit prefix length, no Huffman
        out.write(n, 0, n.length);
        out.write(v.length);
        out.write(v, 0, v.length);
    }

    /**
     * A bidirectional stream must reach the upstream as soon as its headers are sent, even
     * though the client has not sent a single body byte. Undertow's proxy relays the request
     * body with a transfer that writes nothing when no bytes are buffered, and nothing else
     * flushes the upstream request channel, so the upstream never saw the request at all --
     * the client then waited forever on opening metadata that could not exist. NetBird's
     * signal stream died this way (proven on the wire: the upstream connection carried
     * SETTINGS and GOAWAY but never a HEADERS frame) while a body-less curl POST, which
     * half-closes immediately and completes the request, succeeded.
     */
    @Test
    void requestHeadersReachTheUpstreamWithoutARequestBody() throws Exception {
        APP.renewDatabase();
        int upstreamPort = startRecordingUpstream();

        setupSiteWithDomain("hohenheim:address", "bidi.test", "exact",
            h2Settings(upstreamPort, 0));

        proxy = startProxy();

        // Control: the upstream sees a headers-only stream when spoken to directly, so a
        // miss below can only mean the proxy withheld it.
        upstreamSawPaths.clear();
        assertThat(sendRawH2cHeadersOnly(upstreamPort, "bidi.test", "/direct"))
            .as("fixture sanity: a headers-only stream reaches the upstream directly")
            .isEqualTo("/direct");

        upstreamSawPaths.clear();
        assertThat(sendRawH2cHeadersOnly(httpPort(proxy), "bidi.test", "/proxied"))
            .as("the proxy must forward the request headers before any body arrives")
            .isEqualTo("/proxied");
    }

    /**
     * The streaming adapter claims EVERY upstream connection, so the header-only commit
     * must also hold when the upstream side speaks HTTP/1 (the default protocol).
     */
    @Test
    void requestHeadersReachAnHttp1UpstreamWithoutARequestBody() throws Exception {
        APP.renewDatabase();
        int upstreamPort = startRecordingUpstream();

        setupSiteWithDomain("hohenheim:address", "bidi-h1.test", "exact", Map.of(
            "forward_host", "127.0.0.1",
            "forward_port", upstreamPort,
            "request_timeout", 0));

        proxy = startProxy();

        upstreamSawPaths.clear();
        assertThat(sendRawH2cHeadersOnly(httpPort(proxy), "bidi-h1.test", "/proxied-h1"))
            .as("an h1 upstream must see a headers-only request before any body arrives")
            .isEqualTo("/proxied-h1");
    }

    // -------------------------------------------------------------------
    // Compression bypass
    // -------------------------------------------------------------------

    @Test
    void grpcRequestsBypassCompressionWhileNormalRequestsStillCompress() throws Exception {
        APP.renewDatabase();
        plainUpstream = com.sun.net.httpserver.HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = "x".repeat(2048).getBytes(StandardCharsets.UTF_8);
        plainUpstream.createContext("/", ex -> {
            ex.getResponseHeaders().set("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        plainUpstream.start();
        int port = plainUpstream.getAddress().getPort();

        setupSiteWithDomain("hohenheim:address", "encoding.test", "exact",
            Map.of("forward_host", "127.0.0.1", "forward_port", port));

        proxy = startProxy();
        int proxyPort = httpPort(proxy);

        // Control: a plain request with Accept-Encoding gets compressed by the proxy.
        String compressed = rawRequest(proxyPort, "encoding.test", "/",
            "Accept-Encoding: gzip");
        assertThat(compressed.toLowerCase()).contains("content-encoding: gzip");

        // A gRPC-content request must bypass the compression layer entirely.
        String grpc = rawRequest(proxyPort, "encoding.test", "/",
            "Accept-Encoding: gzip",
            "Content-Type: application/grpc");
        assertThat(grpc.toLowerCase()).doesNotContain("content-encoding: gzip");
        assertThat(grpc).contains("200");
    }

    // -------------------------------------------------------------------
    // Certificate helpers
    // -------------------------------------------------------------------

    static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /**
     * @param san the subject alternative name; an IP SAN when ipSan is true
     */
    static X509Certificate selfSignedCert(KeyPair keyPair, String san, boolean ipSan)
            throws Exception {
        Date now = new Date(Now.millis());
        Date until = new Date(now.getTime() + 365L * 86400000);

        org.bouncycastle.asn1.x500.X500Name issuer =
            new org.bouncycastle.asn1.x500.X500Name("CN=" + san);

        org.bouncycastle.cert.X509v3CertificateBuilder builder =
            new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                issuer, BigInteger.valueOf(System.nanoTime()),
                now, until, issuer, keyPair.getPublic());

        org.bouncycastle.asn1.x509.GeneralName name =
            new org.bouncycastle.asn1.x509.GeneralName(
                ipSan ? org.bouncycastle.asn1.x509.GeneralName.iPAddress
                      : org.bouncycastle.asn1.x509.GeneralName.dNSName,
                san);
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false,
            new org.bouncycastle.asn1.x509.GeneralNames(name));
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.basicConstraints, true,
            new org.bouncycastle.asn1.x509.BasicConstraints(true));

        org.bouncycastle.operator.ContentSigner signer =
            new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.getPrivate());

        return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .getCertificate(builder.build(signer));
    }
}
