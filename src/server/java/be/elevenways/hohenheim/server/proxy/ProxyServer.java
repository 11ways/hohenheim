package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.tls.CertificateStore;
import be.elevenways.hohenheim.server.tls.SniKeyManager;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.session.InMemorySessionStore;
import be.elevenways.zenit.common.session.SessionStore;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.encoding.ContentEncodingRepository;
import io.undertow.server.handlers.encoding.EncodingHandler;
import io.undertow.server.handlers.encoding.GzipEncodingProvider;
import io.undertow.server.handlers.encoding.DeflateEncodingProvider;
import io.undertow.util.Headers;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The reverse proxy server that listens on the proxy ports (80/443)
 * and forwards traffic to upstream backends based on hostname.
 *
 * Manages two Undertow instances: HTTP and HTTPS.
 * HTTP always starts. HTTPS starts only when certificates are available.
 * Each listener has its own state that is independently queryable.
 */
public class ProxyServer {

    public enum State {
        STOPPED,
        RUNNING,
        FAILED
    }

    private final SiteDispatcher dispatcher;
    private final HttpHandler handler;
    private final CertificateStore certificateStore;
    private final AcmeService acmeService;
    private final TlsConnectionIdentities tlsConnectionIdentities;

    // Proxy-auth sessions: a dedicated store, fully separate from the admin Zenit.SESSION_STORE.
    // In-memory by default; a DbSessionStore (backed by site_sessions) is a drop-in here.
    private final SessionStore proxySessionStore;

    private Undertow httpServer;
    private Undertow httpsServer;
    private final List<TlsMultiplexer> httpsMultiplexers = new ArrayList<>();
    private volatile InetSocketAddress publicHttpsAddress;
    private UnixSocketListenerBridge httpSocketBridge;

    private volatile State httpState = State.STOPPED;
    private volatile State httpsState = State.STOPPED;
    private volatile String httpFailureReason;
    private volatile String httpsFailureReason;

    private static final int IO_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors());

    public ProxyServer() {
        this.certificateStore = new CertificateStore();
        this.acmeService = new AcmeService(certificateStore);
        this.tlsConnectionIdentities = new TlsConnectionIdentities();
        long sessionTtl = HohenheimSettings.VALUES.getValue(HohenheimSettings.ProxyAuth.SESSION_TTL_SECONDS);
        this.proxySessionStore = new InMemorySessionStore(sessionTtl);
        this.dispatcher = new SiteDispatcher(acmeService, proxySessionStore);

        // Wrap dispatcher with gzip/deflate compression. gRPC exchanges bypass the
        // encoding layer entirely: gRPC does its own message compression and a
        // Content-Encoding-wrapped h2 body corrupts the length-prefixed frames.
        EncodingHandler encodingHandler = new EncodingHandler(dispatcher,
            new ContentEncodingRepository()
                .addEncodingHandler("gzip", new GzipEncodingProvider(), 100)
                .addEncodingHandler("deflate", new DeflateEncodingProvider(), 50));
        this.handler = exchange -> {
            tlsConnectionIdentities.restore(exchange);
            String contentType = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
            if (contentType != null && contentType.startsWith("application/grpc")) {
                dispatcher.handleRequest(exchange);
            } else {
                encodingHandler.handleRequest(exchange);
            }
        };
    }

    public SiteDispatcher getDispatcher() {
        return dispatcher;
    }

    public CertificateStore getCertificateStore() {
        return certificateStore;
    }

    public AcmeService getAcmeService() {
        return acmeService;
    }

    public State getHttpState() {
        return httpState;
    }

    public State getHttpsState() {
        return httpsState;
    }

    public String getHttpFailureReason() {
        return httpFailureReason;
    }

    public String getHttpsFailureReason() {
        return httpsFailureReason;
    }

    /**
     * Load certificates and routes, then start both listeners.
     * HTTP always starts. HTTPS starts only if certificates are loaded.
     * Never throws -- the admin UI must remain operational.
     */
    public synchronized void start() {
        try {
            certificateStore.loadFromDatabase();
            dispatcher.reloadRoutes();
            dispatcher.setHttpsAvailable(false);
        } catch (Exception e) {
            Blast.log("PROXY: failed to load routes/certificates:", e.getMessage());
        }

        startHttpListener();
        startHttpsListener();

        boolean acmeEnabled = Boolean.TRUE.equals(
            HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED));
        if (acmeEnabled) {
            acmeService.start();
        }
    }

    private void startHttpListener() {
        int httpPort = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTP_PORT);
        String socketPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTP_SOCKET_PATH);
        boolean socketMode = socketPath != null && !socketPath.isBlank();

        try {
            Undertow.Builder builder = Undertow.builder()
                .addHttpListener(socketMode ? 0 : httpPort, socketMode ? "127.0.0.1" : "0.0.0.0")
                .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
                .setIoThreads(IO_THREADS)
                .setHandler(handler);

            if (!socketMode) addIpv6Listener(builder, httpPort, null);
            httpServer = builder.build();

            httpServer.start();
            if (socketMode) {
                int loopbackPort = ((InetSocketAddress) getHttpListenerInfo().getAddress()).getPort();
                String permissions = HohenheimSettings.VALUES.getValue(
                    HohenheimSettings.Proxy.HTTP_SOCKET_PERMISSIONS);
                httpSocketBridge = new UnixSocketListenerBridge(
                    Path.of(socketPath.trim()), loopbackPort, permissions);
            }
            httpState = State.RUNNING;
            httpFailureReason = null;
            Blast.log(socketMode ? "Proxy HTTP listening on Unix socket" : "Proxy HTTP listening on port",
                      socketMode ? httpSocketBridge.getSocketPath() : httpPort,
                      "(" + dispatcher.getExactRouteCount() + " exact,",
                      dispatcher.getWildcardRouteCount() + " wildcard routes)");
        } catch (Exception e) {
            httpState = State.FAILED;
            httpFailureReason = e.getMessage();
            if (httpSocketBridge != null) {
                httpSocketBridge.close();
                httpSocketBridge = null;
            }
            if (httpServer != null) httpServer.stop();
            httpServer = null;
            Blast.log("PROXY HTTP STARTUP FAILED:", e.getMessage());
        }
    }

    private void startHttpsListener() {
        if (certificateStore.isEmpty() && !dispatcher.hasTlsPassthroughRoutes()) {
            dispatcher.setHttpsAvailable(false);
            httpsState = State.STOPPED;
            httpsFailureReason = "No certificates loaded";
            Blast.log("Proxy HTTPS not started: no certificates available");
            return;
        }

        int httpsPort = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTPS_PORT);

        try {
            InetSocketAddress terminationAddress = null;
            Exception terminationFailure = null;
            if (!certificateStore.isEmpty()) {
                try {
                    terminationAddress = startHttpsTermination();
                } catch (Exception e) {
                    terminationFailure = e;
                    if (!dispatcher.hasTlsPassthroughRoutes()) throw e;
                    Blast.log("PROXY HTTPS TERMINATION START FAILED; passthrough remains available:",
                        e.getMessage());
                }
            }
            int helloTimeoutSeconds = HohenheimSettings.VALUES.getValue(
                HohenheimSettings.Proxy.TLS_CLIENT_HELLO_TIMEOUT_SECONDS);
            if (helloTimeoutSeconds < 1 || helloTimeoutSeconds > 300) {
                throw new IllegalArgumentException(
                    "proxy.tls_client_hello_timeout_seconds must be between 1 and 300");
            }
            int maxPendingHandshakes = HohenheimSettings.VALUES.getValue(
                HohenheimSettings.Proxy.TLS_MAX_PENDING_HANDSHAKES);
            if (maxPendingHandshakes < 1 || maxPendingHandshakes > 100_000) {
                throw new IllegalArgumentException(
                    "proxy.tls_max_pending_handshakes must be between 1 and 100000");
            }
            int maxConnections = HohenheimSettings.VALUES.getValue(
                HohenheimSettings.Proxy.TLS_MAX_CONNECTIONS);
            if (maxConnections < 1 || maxConnections > 1_000_000) {
                throw new IllegalArgumentException(
                    "proxy.tls_max_connections must be between 1 and 1000000");
            }

            TlsMultiplexer ipv4 = new TlsMultiplexer("0.0.0.0", httpsPort,
                helloTimeoutSeconds * 1000, terminationAddress, dispatcher::resolveTlsRoute,
                () -> HohenheimSettings.VALUES.getValue(
                    HohenheimSettings.Proxy.PROXY_PROTOCOL_TRUSTED_SOURCES),
                tlsConnectionIdentities, dispatcher::isBanned, maxConnections, maxPendingHandshakes,
                this::handleHttpsListenerFailure);
            ipv4.start();
            httpsMultiplexers.add(ipv4);
            publicHttpsAddress = ipv4.getLocalAddress();

            String ipv6Address = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.IPV6_ADDRESS);
            if (ipv6Address != null && !ipv6Address.isBlank()) {
                int ipv6Port = httpsPort == 0 ? publicHttpsAddress.getPort() : httpsPort;
                TlsMultiplexer ipv6 = new TlsMultiplexer(ipv6Address.trim(), ipv6Port,
                    helloTimeoutSeconds * 1000, terminationAddress, dispatcher::resolveTlsRoute,
                    () -> HohenheimSettings.VALUES.getValue(
                        HohenheimSettings.Proxy.PROXY_PROTOCOL_TRUSTED_SOURCES),
                    tlsConnectionIdentities, dispatcher::isBanned, maxConnections, maxPendingHandshakes,
                    this::handleHttpsListenerFailure);
                ipv6.start();
                httpsMultiplexers.add(ipv6);
            }

            dispatcher.setHttpsAvailable(terminationAddress != null);
            httpsState = State.RUNNING;
            httpsFailureReason = terminationFailure != null ? terminationFailure.getMessage() : null;
            Blast.log("Proxy HTTPS multiplexer listening on port", publicHttpsAddress.getPort(),
                "(" + certificateStore.getCertificateCount() + " certificates,",
                dispatcher.hasTlsPassthroughRoutes() ? "passthrough enabled)" : "no passthrough routes)");
        } catch (Exception e) {
            dispatcher.setHttpsAvailable(false);
            httpsState = State.FAILED;
            httpsFailureReason = e.getMessage();
            stopHttpsResources();
            Blast.log("PROXY HTTPS STARTUP FAILED on port", httpsPort + ":", e.getMessage());
        }
    }

    private InetSocketAddress startHttpsTermination() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        SniKeyManager sniKeyManager = new SniKeyManager(certificateStore, dispatcher::isBanned);
        sslContext.init(new KeyManager[]{sniKeyManager}, null, null);

        Undertow server = Undertow.builder()
            .addHttpsListener(0, "127.0.0.1", sslContext)
            .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
            .setIoThreads(IO_THREADS)
            .setHandler(handler)
            .build();
        try {
            server.start();
        } catch (Exception e) {
            server.stop();
            throw e;
        }
        httpsServer = server;
        return (InetSocketAddress) getHttpsListenerInfo().getAddress();
    }

    private synchronized void handleHttpsListenerFailure(IOException failure) {
        if (httpsState == State.STOPPED) return;
        stopHttpsResources();
        dispatcher.setHttpsAvailable(false);
        httpsState = State.FAILED;
        httpsFailureReason = failure.getMessage();
        Blast.log("PROXY HTTPS LISTENER FAILED:", failure.getMessage());
    }

    private static void addIpv6Listener(Undertow.Builder builder, int port, SSLContext sslContext) {
        String ipv6Address = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.IPV6_ADDRESS);
        if (ipv6Address == null || ipv6Address.isBlank()) return;

        if (sslContext != null) {
            builder.addHttpsListener(port, ipv6Address.trim(), sslContext);
        } else {
            builder.addHttpListener(port, ipv6Address.trim());
        }
    }

    public synchronized void stop() {
        acmeService.stop();
        if (httpSocketBridge != null) {
            httpSocketBridge.close();
            httpSocketBridge = null;
        }
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        stopHttpsResources();
        dispatcher.shutdown();
        proxySessionStore.deleteExpired();
        dispatcher.setHttpsAvailable(false);
        httpState = State.STOPPED;
        httpsState = State.STOPPED;
        httpFailureReason = null;
        httpsFailureReason = null;
        Blast.log("Proxy server stopped");
    }

    /**
     * Reload routes and certificates. Restarts failed listeners.
     */
    public synchronized void reload() {
        try {
            certificateStore.loadFromDatabase();
            dispatcher.reloadRoutes();
        } catch (Exception e) {
            Blast.log("PROXY: reload failed:", e.getMessage());
        }

        // Restart HTTP if it was failed
        if (httpState == State.FAILED) {
            Blast.log("Proxy HTTP was FAILED -- attempting restart");
            startHttpListener();
        }

        boolean wantsTermination = !certificateStore.isEmpty();
        boolean wantsPassthrough = dispatcher.hasTlsPassthroughRoutes();
        if (!wantsTermination && !wantsPassthrough) {
            if (httpsState != State.STOPPED || publicHttpsAddress != null) {
                stopHttpsResources();
                dispatcher.setHttpsAvailable(false);
                httpsState = State.STOPPED;
                httpsFailureReason = "No certificates or TLS passthrough routes loaded";
                Blast.log("Proxy HTTPS stopped: no terminating or passthrough routes remain");
            }
            return;
        }

        if (httpsState != State.RUNNING) {
            Blast.log("Proxy HTTPS starting after certificate reload");
            stopHttpsResources();
            startHttpsListener();
            return;
        }

        if (wantsTermination && httpsServer == null) {
            try {
                InetSocketAddress terminationAddress = startHttpsTermination();
                for (TlsMultiplexer multiplexer : httpsMultiplexers) {
                    multiplexer.setTerminationAddress(terminationAddress);
                }
                dispatcher.setHttpsAvailable(true);
                httpsFailureReason = null;
                Blast.log("Proxy HTTPS termination enabled after certificate reload");
            } catch (Exception e) {
                dispatcher.setHttpsAvailable(false);
                httpsFailureReason = e.getMessage();
                Blast.log("PROXY HTTPS TERMINATION START FAILED:", e.getMessage());
                if (!wantsPassthrough) {
                    stopHttpsResources();
                    httpsState = State.FAILED;
                }
            }
        } else if (!wantsTermination && httpsServer != null) {
            for (TlsMultiplexer multiplexer : httpsMultiplexers) {
                multiplexer.setTerminationAddress(null);
            }
            httpsServer.stop();
            httpsServer = null;
            dispatcher.setHttpsAvailable(false);
            httpsFailureReason = null;
            Blast.log("Proxy HTTPS termination stopped; passthrough remains available");
        }
    }

    /**
     * Get listener info for discovering bound ports (useful in tests with port 0).
     */
    public Undertow.ListenerInfo getHttpListenerInfo() {
        if (httpServer == null) return null;
        var listeners = httpServer.getListenerInfo();
        return listeners.isEmpty() ? null : listeners.get(0);
    }

    public Undertow.ListenerInfo getHttpsListenerInfo() {
        if (httpsServer == null) return null;
        var listeners = httpsServer.getListenerInfo();
        return listeners.isEmpty() ? null : listeners.get(0);
    }

    /** Public HTTPS multiplexer address; unlike getHttpsListenerInfo this is reachable by clients. */
    public InetSocketAddress getHttpsAddress() {
        return publicHttpsAddress;
    }

    private void stopHttpsResources() {
        for (TlsMultiplexer multiplexer : httpsMultiplexers) {
            multiplexer.close();
        }
        httpsMultiplexers.clear();
        publicHttpsAddress = null;
        if (httpsServer != null) {
            httpsServer.stop();
            httpsServer = null;
        }
    }

    // Backwards-compatible accessors for dashboard
    public State getState() {
        if (httpState == State.RUNNING) return State.RUNNING;
        if (httpState == State.FAILED) return State.FAILED;
        return State.STOPPED;
    }

    public String getFailureReason() {
        if (httpState == State.FAILED) return httpFailureReason;
        return null;
    }
}
