package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.tls.CertificateStore;
import be.elevenways.hohenheim.server.tls.SniKeyManager;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.setting.SettingDefinition;
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
import java.util.function.Consumer;
import java.util.function.LongSupplier;

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
    private final ConnectionIdentities connectionIdentities;

    // Proxy-auth sessions: a dedicated store, fully separate from the admin Zenit.SESSION_STORE.
    // In-memory by default; a DbSessionStore (backed by site_sessions) is a drop-in here.
    private final SessionStore proxySessionStore;

    private Undertow httpServer;
    private Undertow httpsServer;
    private final List<PublicTcpListener> httpFrontListeners = new ArrayList<>();
    private final List<PublicTcpListener> httpsFrontListeners = new ArrayList<>();
    private volatile InetSocketAddress publicHttpAddress;
    private volatile InetSocketAddress publicHttpsAddress;
    private volatile InetSocketAddress httpTerminationAddress;
    private volatile InetSocketAddress httpsTerminationAddress;
    private UnixSocketListenerBridge httpSocketBridge;

    private volatile State httpState = State.STOPPED;
    private volatile State httpsState = State.STOPPED;
    private volatile String httpFailureReason;
    private volatile String httpsFailureReason;

    // AIDEV-NOTE: bounded restart state, shared by BOTH listeners. Restarts bound the
    // INTERVAL (exponential backoff, 30s doubling to a 1h ceiling), never the attempt
    // count -- a permanently-given-up listener is an outage that never heals, while a
    // permanently-unbindable one must not hot-loop bind() once a minute forever. The
    // supervisor gates on volatile reads and only takes the monitor when an attempt is
    // genuinely due, so a healthy tick can never tear down a listener mid-ACME-install.
    private static final long RESTART_BACKOFF_INITIAL_MILLIS = 30_000;
    private static final long RESTART_BACKOFF_MAX_MILLIS = 60L * 60 * 1000;

    private volatile int httpRestartAttempts;
    private volatile long httpNextRestartAttemptAt;
    private volatile int httpsRestartAttempts;
    private volatile long httpsNextRestartAttemptAt;
    private volatile boolean httpDownAlertSent;
    private volatile boolean httpsDownAlertSent;
    private volatile LongSupplier clock = System::currentTimeMillis;

    private static final int IO_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors());

    public ProxyServer() {
        this.certificateStore = new CertificateStore();
        this.acmeService = new AcmeService(certificateStore);
        this.connectionIdentities = new ConnectionIdentities();
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
            connectionIdentities.restore(exchange);
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
        warnIfForceSslRefusing();

        boolean acmeEnabled = Boolean.TRUE.equals(
            HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED));
        if (acmeEnabled) {
            acmeService.start();
        }
    }

    /** Whether force-SSL routes can currently be answered with a redirect instead of a 503. */
    public boolean isHttpsTerminationAvailable() {
        return httpsTerminationAddress != null;
    }

    /**
     * Names every site that now REFUSES plain HTTP because HTTPS termination is down.
     * The per-request 503 is the enforcement; this is the operator-facing statement of it
     * (the dashboard twin lives in AttentionCollector), because a security control changing
     * behaviour without a log line naming the affected sites is itself the defect.
     */
    private void warnIfForceSslRefusing() {
        if (httpsTerminationAddress != null || httpState != State.RUNNING) return;
        boolean globalForce = Boolean.TRUE.equals(
            HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.FORCE_HTTPS));
        List<String> siteNames = dispatcher.forceSslSiteNames();
        boolean anyRoutes = dispatcher.getExactRouteCount() + dispatcher.getWildcardRouteCount()
            + dispatcher.getRegexRouteCount() > 0;
        if (siteNames.isEmpty() && !(globalForce && anyRoutes)) return;
        Blast.log("PROXY: HTTPS is UNAVAILABLE; force-SSL sites refuse plain HTTP (503):",
            siteNames.isEmpty() ? "(none)" : String.join(", ", siteNames),
            globalForce ? "-- proxy.force_https is on, so EVERY routed site refuses" : "");
    }

    private void startHttpListener() {
        int httpPort = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTP_PORT);
        String socketPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTP_SOCKET_PATH);
        boolean socketMode = socketPath != null && !socketPath.isBlank();
        // A configured PROXY v2 peer set means the public HTTP port must resolve connection
        // identity before Undertow decodes a request. In socket mode the bridge already is
        // that front, and with no configured peer nothing may send a header, so the plain
        // public listener stays hop-free.
        boolean proxyProtocolIngress = !socketMode && !trustedProxyProtocolSources().isEmpty();
        boolean internalOnly = socketMode || proxyProtocolIngress;

        // AIDEV-NOTE: socket mode REFUSES to start without trusted proxy keys. An AF_UNIX
        // peer has no IP address and the bridge does no identity registration, so every
        // request reaches the dispatcher as 127.0.0.1: bans are inert, denied_ips never
        // matches (fail-open), allowed_ips refuses everyone, listen_on is unreachable and
        // threat scoring blames loopback. The ONLY source of client identity in this
        // topology is the fronting proxy's X-Hohenheim-Key + X-Real-IP headers, so that
        // contract must be configured before this front may serve.
        if (socketMode && !hasTrustedProxyKeys()) {
            httpState = State.FAILED;
            recordHttpRestartFailure();
            httpFailureReason = "proxy.http_socket_path requires proxy.trusted_proxy_keys: "
                + "a Unix-socket client has no IP address, so without an authenticated "
                + "fronting proxy every request would count as 127.0.0.1 and IP-based "
                + "controls (bans, access lists, listen_on) would silently stop working";
            Blast.log("PROXY HTTP NOT STARTED:", httpFailureReason);
            return;
        }

        try {
            Undertow.Builder builder = Undertow.builder()
                .addHttpListener(internalOnly ? 0 : httpPort, internalOnly ? "127.0.0.1" : "0.0.0.0")
                .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
                .setIoThreads(IO_THREADS)
                .setHandler(handler);

            if (!internalOnly) addIpv6Listener(builder, httpPort, null);
            httpServer = builder.build();

            httpServer.start();
            InetSocketAddress undertowAddress = (InetSocketAddress) getHttpListenerInfo().getAddress();
            if (socketMode) {
                String permissions = HohenheimSettings.VALUES.getValue(
                    HohenheimSettings.Proxy.HTTP_SOCKET_PERMISSIONS);
                httpSocketBridge = new UnixSocketListenerBridge(
                    Path.of(socketPath.trim()), undertowAddress.getPort(), permissions);
            } else if (proxyProtocolIngress) {
                httpTerminationAddress = undertowAddress;
                startPublicListeners(httpFrontListeners, httpPort,
                    new InternalListenerRouter(() -> httpTerminationAddress),
                    this::handleHttpListenerFailure);
                publicHttpAddress = httpFrontListeners.get(0).getLocalAddress();
            } else {
                publicHttpAddress = undertowAddress;
            }
            httpState = State.RUNNING;
            httpFailureReason = null;
            noteHttpListenerHealthy();
            Blast.log(socketMode ? "Proxy HTTP listening on Unix socket" : "Proxy HTTP listening on port",
                      socketMode ? httpSocketBridge.getSocketPath() : publicHttpAddress.getPort(),
                      proxyProtocolIngress ? "(PROXY v2 ingress," : "(",
                      dispatcher.getExactRouteCount() + " exact,",
                      dispatcher.getWildcardRouteCount() + " wildcard routes)");
        } catch (Exception e) {
            httpState = State.FAILED;
            httpFailureReason = e.getMessage();
            recordHttpRestartFailure();
            stopHttpResources();
            Blast.log("PROXY HTTP STARTUP FAILED:", e.getMessage());
        }
    }

    private synchronized void handleHttpListenerFailure(IOException failure) {
        if (httpState == State.STOPPED) return;
        stopHttpResources();
        httpState = State.FAILED;
        httpFailureReason = failure.getMessage();
        Blast.log("PROXY HTTP LISTENER FAILED:", failure.getMessage());
    }

    /**
     * Binds the public IPv4 listener and, when configured, the extra IPv6 one.
     *
     * @throws IOException when a listener cannot bind; already-bound ones stay in the list
     *                     so the caller's teardown closes them
     */
    private void startPublicListeners(List<PublicTcpListener> target, int port,
                                      ConnectionRouter router,
                                      Consumer<IOException> failureHandler) throws IOException {
        int prologueTimeout = boundedSetting(HohenheimSettings.Proxy.CONNECTION_PROLOGUE_TIMEOUT_SECONDS,
            "proxy.connection_prologue_timeout_seconds", 1, 300) * 1000;
        int maxPending = boundedSetting(HohenheimSettings.Proxy.MAX_PENDING_CONNECTIONS,
            "proxy.max_pending_connections", 1, 100_000);
        int maxConnections = boundedSetting(HohenheimSettings.Proxy.MAX_PUBLIC_CONNECTIONS,
            "proxy.max_public_connections", 1, 1_000_000);

        PublicTcpListener ipv4 = new PublicTcpListener("0.0.0.0", port, prologueTimeout, router,
            ProxyServer::trustedProxyProtocolSources, connectionIdentities, dispatcher::isBanned,
            maxConnections, maxPending, failureHandler);
        ipv4.start();
        target.add(ipv4);

        String ipv6Address = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.IPV6_ADDRESS);
        if (ipv6Address == null || ipv6Address.isBlank()) return;
        int ipv6Port = port == 0 ? ipv4.getLocalAddress().getPort() : port;
        PublicTcpListener ipv6 = new PublicTcpListener(ipv6Address.trim(), ipv6Port, prologueTimeout,
            router, ProxyServer::trustedProxyProtocolSources, connectionIdentities,
            dispatcher::isBanned, maxConnections, maxPending, failureHandler);
        ipv6.start();
        target.add(ipv6);
    }

    /** Whether at least one non-blank X-Hohenheim-Key is configured. */
    private static boolean hasTrustedProxyKeys() {
        List<String> keys = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS);
        if (keys == null) return false;
        for (String key : keys) {
            if (key != null && !key.isBlank()) return true;
        }
        return false;
    }

    private static List<String> trustedProxyProtocolSources() {
        List<String> configured = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Proxy.PROXY_PROTOCOL_TRUSTED_SOURCES);
        return configured != null ? configured : List.of();
    }

    private static int boundedSetting(SettingDefinition<Integer> definition, String path,
                                      int minimum, int maximum) {
        int value = HohenheimSettings.VALUES.getValue(definition);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " must be between " + minimum + " and " + maximum);
        }
        return value;
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
            Exception terminationFailure = null;
            if (!certificateStore.isEmpty()) {
                try {
                    httpsTerminationAddress = startHttpsTermination();
                } catch (Exception e) {
                    terminationFailure = e;
                    if (!dispatcher.hasTlsPassthroughRoutes()) throw e;
                    Blast.log("PROXY HTTPS TERMINATION START FAILED; passthrough remains available:",
                        e.getMessage());
                }
            }

            startPublicListeners(httpsFrontListeners, httpsPort,
                new TlsSniRouter(dispatcher::resolveTlsRoute, () -> httpsTerminationAddress),
                this::handleHttpsListenerFailure);
            publicHttpsAddress = httpsFrontListeners.get(0).getLocalAddress();

            dispatcher.setHttpsAvailable(httpsTerminationAddress != null);
            httpsState = State.RUNNING;
            httpsFailureReason = terminationFailure != null ? terminationFailure.getMessage() : null;
            if (terminationFailure == null) {
                noteHttpsListenerHealthy();
            } else {
                // Partial mode: passthrough listens but termination is down, so force_ssl
                // sites 503. Keep the supervised backoff armed so termination gets retried.
                recordHttpsRestartFailure();
            }
            Blast.log("Proxy HTTPS listening on port", publicHttpsAddress.getPort(),
                "(" + certificateStore.getCertificateCount() + " certificates,",
                dispatcher.hasTlsPassthroughRoutes() ? "passthrough enabled)" : "no passthrough routes)");
        } catch (Exception e) {
            dispatcher.setHttpsAvailable(false);
            httpsState = State.FAILED;
            httpsFailureReason = e.getMessage();
            recordHttpsRestartFailure();
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
        stopHttpResources();
        stopHttpsResources();
        dispatcher.shutdown();
        proxySessionStore.deleteExpired();
        dispatcher.setHttpsAvailable(false);
        httpState = State.STOPPED;
        httpsState = State.STOPPED;
        httpFailureReason = null;
        httpsFailureReason = null;
        // Administrative stop is not a failure: disarm the supervised restart machinery.
        httpRestartAttempts = 0;
        httpNextRestartAttemptAt = 0;
        httpsRestartAttempts = 0;
        httpsNextRestartAttemptAt = 0;
        httpDownAlertSent = false;
        httpsDownAlertSent = false;
        Blast.log("Proxy server stopped");
    }

    /**
     * Reload routes and certificates. Restarts failed listeners immediately: a config-driven
     * reload means something actually changed, so the restart backoff does not apply.
     */
    public synchronized void reload() {
        try {
            reloadListeners(false);
        } finally {
            warnIfForceSslRefusing();
        }
    }

    /**
     * Periodic listener supervision: restarts a dead or degraded listener under the bounded
     * backoff, and raises a one-shot alert once an outage has survived a supervised retry.
     *
     * Gated on volatile reads first -- the monitor (which serializes with the blocking
     * bind + SSLContext work in the start paths) is only taken when a restart is due.
     */
    public void superviseListeners() {
        long now = clock.getAsLong();
        boolean httpDue = httpState == State.FAILED && now >= httpNextRestartAttemptAt;
        boolean httpsDue = (httpsState == State.FAILED
                || (httpsState == State.RUNNING && httpsFailureReason != null))
            && now >= httpsNextRestartAttemptAt;
        if (httpDue || httpsDue) {
            synchronized (this) {
                try {
                    reloadListeners(true);
                } finally {
                    warnIfForceSslRefusing();
                }
            }
        }
        maybeAlertListenerDown();
    }

    /** Fires the listener-down alert ONCE per outage, only after a supervised retry also failed. */
    private void maybeAlertListenerDown() {
        if (!httpDownAlertSent && httpState == State.FAILED && httpRestartAttempts >= 2) {
            httpDownAlertSent = true;
            Alerts.send(NotificationEvents.PROXY_LISTENER_DOWN,
                "Proxy HTTP listener is down", httpFailureReason);
        }
        boolean httpsDown = httpsState == State.FAILED
            || (httpsState == State.RUNNING && httpsFailureReason != null);
        if (!httpsDownAlertSent && httpsDown && httpsRestartAttempts >= 2) {
            httpsDownAlertSent = true;
            Alerts.send(NotificationEvents.PROXY_LISTENER_DOWN,
                "Proxy HTTPS listener is down", httpsFailureReason);
        }
    }

    private void noteHttpListenerHealthy() {
        if (httpDownAlertSent) {
            Blast.log("Proxy HTTP listener recovered after", httpRestartAttempts, "restart attempts");
        }
        httpRestartAttempts = 0;
        httpNextRestartAttemptAt = 0;
        httpDownAlertSent = false;
    }

    private void noteHttpsListenerHealthy() {
        if (httpsDownAlertSent) {
            Blast.log("Proxy HTTPS listener recovered after", httpsRestartAttempts, "restart attempts");
        }
        httpsRestartAttempts = 0;
        httpsNextRestartAttemptAt = 0;
        httpsDownAlertSent = false;
    }

    private void recordHttpRestartFailure() {
        httpRestartAttempts++;
        httpNextRestartAttemptAt = clock.getAsLong() + restartBackoffMillis(httpRestartAttempts);
    }

    private void recordHttpsRestartFailure() {
        httpsRestartAttempts++;
        httpsNextRestartAttemptAt = clock.getAsLong() + restartBackoffMillis(httpsRestartAttempts);
    }

    private static long restartBackoffMillis(int attempts) {
        int shift = Math.min(Math.max(attempts - 1, 0), 12);
        return Math.min(RESTART_BACKOFF_INITIAL_MILLIS << shift, RESTART_BACKOFF_MAX_MILLIS);
    }

    /** Test seam: replaces the clock the restart backoff is computed against. */
    public void setClockForTesting(LongSupplier replacement) {
        this.clock = replacement;
    }

    /** Test seam. */
    public int getHttpRestartAttemptsForTesting() {
        return httpRestartAttempts;
    }

    /** Test seam. */
    public long getHttpNextRestartAttemptAtForTesting() {
        return httpNextRestartAttemptAt;
    }

    /** Test seam. */
    public int getHttpsRestartAttemptsForTesting() {
        return httpsRestartAttempts;
    }

    /** Test seam. */
    public long getHttpsNextRestartAttemptAtForTesting() {
        return httpsNextRestartAttemptAt;
    }

    private void reloadListeners(boolean respectBackoff) {
        long now = clock.getAsLong();
        try {
            certificateStore.loadFromDatabase();
            dispatcher.reloadRoutes();
        } catch (Exception e) {
            Blast.log("PROXY: reload failed:", e.getMessage());
        }

        // Restart HTTP if it was failed
        if (httpState == State.FAILED && (!respectBackoff || now >= httpNextRestartAttemptAt)) {
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
            if (respectBackoff && now < httpsNextRestartAttemptAt) return;
            // NOT cert-gated: this branch fires on ANY reload while certificates or
            // passthrough routes exist and the listener is down. The old log text said
            // "after certificate reload", which cost real diagnosis time in the Aug 04
            // incident by making the recovery read as certificate-event-only.
            Blast.log("Proxy HTTPS was DOWN -- attempting restart",
                "(certificates or passthrough routes are present)");
            stopHttpsResources();
            startHttpsListener();
            return;
        }

        if (wantsTermination && httpsServer == null) {
            if (respectBackoff && now < httpsNextRestartAttemptAt) return;
            try {
                httpsTerminationAddress = startHttpsTermination();
                dispatcher.setHttpsAvailable(true);
                httpsFailureReason = null;
                noteHttpsListenerHealthy();
                Blast.log("Proxy HTTPS termination enabled after reload");
            } catch (Exception e) {
                dispatcher.setHttpsAvailable(false);
                httpsFailureReason = e.getMessage();
                recordHttpsRestartFailure();
                Blast.log("PROXY HTTPS TERMINATION START FAILED:", e.getMessage());
                if (!wantsPassthrough) {
                    stopHttpsResources();
                    httpsState = State.FAILED;
                }
            }
        } else if (!wantsTermination && httpsServer != null) {
            httpsTerminationAddress = null;
            httpsServer.stop();
            httpsServer = null;
            dispatcher.setHttpsAvailable(false);
            httpsFailureReason = null;
            noteHttpsListenerHealthy();
            Blast.log("Proxy HTTPS termination stopped; passthrough remains available");
        } else if (!wantsTermination && httpsFailureReason != null) {
            // Passthrough-only is now the WANTED shape: a lingering termination-failure
            // reason from a deleted certificate would otherwise read as degraded forever
            // and keep the supervisor probing a mode nothing asks for.
            httpsFailureReason = null;
            noteHttpsListenerHealthy();
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

    /** Public HTTPS address; unlike getHttpsListenerInfo this is the one clients reach. */
    public InetSocketAddress getHttpsAddress() {
        return publicHttpsAddress;
    }

    /** Public HTTP address; with PROXY v2 ingress this differs from the internal Undertow listener. */
    public InetSocketAddress getHttpAddress() {
        return publicHttpAddress;
    }

    private void stopHttpResources() {
        for (PublicTcpListener listener : httpFrontListeners) {
            listener.close();
        }
        httpFrontListeners.clear();
        publicHttpAddress = null;
        httpTerminationAddress = null;
        if (httpSocketBridge != null) {
            httpSocketBridge.close();
            httpSocketBridge = null;
        }
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
    }

    private void stopHttpsResources() {
        for (PublicTcpListener listener : httpsFrontListeners) {
            listener.close();
        }
        httpsFrontListeners.clear();
        publicHttpsAddress = null;
        httpsTerminationAddress = null;
        if (httpsServer != null) {
            httpsServer.stop();
            httpsServer = null;
        }
    }

    /**
     * Combined worst-of-both listener state. A dead HTTPS listener used to be invisible
     * here (HTTP-only), which is how a six-day port-443 outage went unnoticed.
     */
    public State getState() {
        if (httpState == State.FAILED || httpsState == State.FAILED) return State.FAILED;
        return httpState;
    }

    /** The reason for {@link #getState()}, including a degraded-termination reason while RUNNING. */
    public String getFailureReason() {
        if (httpState == State.FAILED) return httpFailureReason;
        if (httpsState == State.FAILED) return httpsFailureReason;
        if (httpsState == State.RUNNING && httpsFailureReason != null) return httpsFailureReason;
        return null;
    }
}
