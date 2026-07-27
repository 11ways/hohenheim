package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.proxy.SiteDispatcher;
import be.elevenways.hohenheim.server.tls.UpstreamTrust;
import be.elevenways.zenit.common.orm.datasource.Row;
import com.sun.net.httpserver.HttpsConfigurator;
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

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static be.elevenways.hohenheim.test.ProxyTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upstream wire-protocol behavior: validated HTTPS upstreams (trust + hostname),
 * prior-knowledge h2c upstreams, gRPC trailer forwarding, per-site request
 * timeouts, and the gRPC bypass of the compression layer.
 */
class UpstreamProtocolTest {

    private static boolean initialized = false;
    private ProxyServer proxy;
    private HttpsServer httpsUpstream;
    private Undertow undertowUpstream;
    private com.sun.net.httpserver.HttpServer plainUpstream;

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) return;
        initialized = true;
        bootRuntime();
    }

    @AfterEach
    void cleanup() {
        SiteDispatcher.overrideTrustedUpstreamSslContextForTests(null);
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
        resetDatabase();
        KeyPair keyPair = generateKeyPair();
        int port = startHttpsUpstream(keyPair, selfSignedCert(keyPair, "127.0.0.1", true));

        setupSiteWithDomain("hohenheim:proxy", "untrusted-tls.test", "exact",
            httpsSettings(port, false));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "untrusted-tls.test", "/");
        assertThat(response).doesNotContain("tls-upstream");
        // A failed upstream TLS handshake surfaces as ProxyHandler's 503 (no connection).
        assertThat(response).contains("503");
    }

    @Test
    void httpsUpstreamWithTrustedAuthorityAndMatchingHostWorks() throws Exception {
        resetDatabase();
        KeyPair keyPair = generateKeyPair();
        X509Certificate cert = selfSignedCert(keyPair, "127.0.0.1", true);
        int port = startHttpsUpstream(keyPair, cert);

        SiteDispatcher.overrideTrustedUpstreamSslContextForTests(UpstreamTrust.contextTrusting(cert));
        setupSiteWithDomain("hohenheim:proxy", "trusted-tls.test", "exact",
            httpsSettings(port, false));

        proxy = startProxy();
        assertThat(rawRequest(httpPort(proxy), "trusted-tls.test", "/"))
            .contains("200")
            .contains("tls-upstream");
    }

    @Test
    void httpsUpstreamWithTrustedAuthorityButWrongHostIsRejected() throws Exception {
        resetDatabase();
        KeyPair keyPair = generateKeyPair();
        // Chain will be trusted, but the SAN names a different host than the dial target.
        X509Certificate cert = selfSignedCert(keyPair, "other.internal", false);
        int port = startHttpsUpstream(keyPair, cert);

        SiteDispatcher.overrideTrustedUpstreamSslContextForTests(UpstreamTrust.contextTrusting(cert));
        setupSiteWithDomain("hohenheim:proxy", "wronghost-tls.test", "exact",
            httpsSettings(port, false));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "wronghost-tls.test", "/");
        assertThat(response).doesNotContain("tls-upstream");
        assertThat(response).contains("503");
    }

    @Test
    void ignoreCertificatesStillBypassesValidation() throws Exception {
        resetDatabase();
        KeyPair keyPair = generateKeyPair();
        int port = startHttpsUpstream(keyPair, selfSignedCert(keyPair, "unrelated.host", false));

        setupSiteWithDomain("hohenheim:proxy", "ignored-tls.test", "exact",
            httpsSettings(port, true));

        proxy = startProxy();
        assertThat(rawRequest(httpPort(proxy), "ignored-tls.test", "/"))
            .contains("200")
            .contains("tls-upstream");
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
        resetDatabase();
        AtomicReference<String> seenProtocol = new AtomicReference<>();
        int port = startH2cUpstream(seenProtocol);

        setupSiteWithDomain("hohenheim:proxy", "h2c.test", "exact", h2Settings(port, 0));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "h2c.test", "/hello");
        assertThat(response).contains("200");
        assertThat(response).contains("h2-upstream-payload");
        assertThat(seenProtocol.get()).isEqualTo("HTTP/2.0");
    }

    @Test
    void h2DownstreamGetsGrpcTrailersFromH2cUpstream() throws Exception {
        resetDatabase();
        AtomicReference<String> seenProtocol = new AtomicReference<>();
        int upstreamPort = startH2cUpstream(seenProtocol);

        setupSiteWithDomain("hohenheim:proxy", "grpc.test", "exact", h2Settings(upstreamPort, 0));

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
        resetDatabase();
        // Same streaming upstream, dialed with plain HTTP/1.1: the chunked response
        // carries the trailers, and the downstream chunked response must too.
        int port = startH2cUpstream(null);

        setupSiteWithDomain("hohenheim:proxy", "h1trailers.test", "exact",
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
        resetDatabase();

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

        setupSiteWithDomain("hohenheim:proxy", "reqtrailers.test", "exact",
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
                Thread.sleep(delayMs);
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

    @Test
    void shortRequestTimeoutKillsSlowExchangesButZeroMeansUnlimited() throws Exception {
        resetDatabase();
        int port = startSlowUpstream(3000);

        Row limited = setupSite("hohenheim:proxy", "Limited Site", "limited-site", Map.of(
            "forward_host", "127.0.0.1", "forward_port", port, "request_timeout", 1));
        addDomain(limited, "limited.test", "exact", null, false);

        Row unlimited = setupSite("hohenheim:proxy", "Unlimited Site", "unlimited-site", Map.of(
            "forward_host", "127.0.0.1", "forward_port", port, "request_timeout", 0));
        addDomain(unlimited, "unlimited.test", "exact", null, false);

        proxy = startProxy();
        int proxyPort = httpPort(proxy);

        // 1s limit against a 3s upstream: a definite 504, never the payload.
        String killed = rawRequest(proxyPort, "limited.test", "/");
        assertThat(killed).doesNotContain("slow-upstream");
        assertThat(killed).contains("504");

        // 0 = unlimited: the same slow upstream completes.
        assertThat(rawRequest(proxyPort, "unlimited.test", "/"))
            .contains("200")
            .contains("slow-upstream");
    }

    // -------------------------------------------------------------------
    // Streamed response headers
    // -------------------------------------------------------------------

    /**
     * h2c upstream that commits its response headers immediately and only writes the
     * body {@code delayMs} later -- the shape of every long-lived streaming gRPC
     * service (NetBird's SignalExchange sends a registration header on stream open,
     * then nothing until a peer message arrives).
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
                        Thread.sleep(delayMs);
                        var buffer = java.nio.ByteBuffer.wrap(
                            "stream-payload".getBytes(StandardCharsets.UTF_8));
                        while (buffer.hasRemaining()) channel.write(buffer);
                        channel.shutdownWrites();
                        while (!channel.flush()) {
                            Thread.onSpinWait();
                        }
                        exchange.endExchange();
                    } catch (IOException | InterruptedException e) {
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
        resetDatabase();
        long bodyDelayMs = 4000;
        int upstreamPort = startHeadersThenDelayedBodyUpstream(bodyDelayMs);

        setupSiteWithDomain("hohenheim:proxy", "stream.test", "exact",
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

    // -------------------------------------------------------------------
    // Compression bypass
    // -------------------------------------------------------------------

    @Test
    void grpcRequestsBypassCompressionWhileNormalRequestsStillCompress() throws Exception {
        resetDatabase();
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

        setupSiteWithDomain("hohenheim:proxy", "encoding.test", "exact",
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
        Date now = new Date();
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
