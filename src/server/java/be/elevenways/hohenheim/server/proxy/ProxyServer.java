package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.tls.CertificateStore;
import be.elevenways.hohenheim.server.tls.SniKeyManager;
import be.elevenways.protoblast.common.Blast;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.encoding.ContentEncodingRepository;
import io.undertow.server.handlers.encoding.EncodingHandler;
import io.undertow.server.handlers.encoding.GzipEncodingProvider;
import io.undertow.server.handlers.encoding.DeflateEncodingProvider;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;

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

    private Undertow httpServer;
    private Undertow httpsServer;

    private volatile State httpState = State.STOPPED;
    private volatile State httpsState = State.STOPPED;
    private volatile String httpFailureReason;
    private volatile String httpsFailureReason;

    private static final int IO_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors());

    public ProxyServer() {
        this.certificateStore = new CertificateStore();
        this.acmeService = new AcmeService(certificateStore);
        this.dispatcher = new SiteDispatcher(acmeService);

        // Wrap dispatcher with gzip/deflate compression
        this.handler = new EncodingHandler(dispatcher,
            new ContentEncodingRepository()
                .addEncodingHandler("gzip", new GzipEncodingProvider(), 100)
                .addEncodingHandler("deflate", new DeflateEncodingProvider(), 50));
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
    public void start() {
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

        try {
            Undertow.Builder builder = Undertow.builder()
                .addHttpListener(httpPort, "0.0.0.0")
                .setIoThreads(IO_THREADS)
                .setHandler(handler);

            addIpv6Listener(builder, httpPort, null);
            httpServer = builder.build();

            httpServer.start();
            httpState = State.RUNNING;
            httpFailureReason = null;
            Blast.log("Proxy HTTP listening on port", httpPort,
                      "(" + dispatcher.getExactRouteCount() + " exact,",
                      dispatcher.getWildcardRouteCount() + " wildcard routes)");
        } catch (Exception e) {
            httpState = State.FAILED;
            httpFailureReason = e.getMessage();
            httpServer = null;
            Blast.log("PROXY HTTP STARTUP FAILED on port", httpPort + ":", e.getMessage());
        }
    }

    private void startHttpsListener() {
        if (certificateStore.isEmpty()) {
            dispatcher.setHttpsAvailable(false);
            httpsState = State.STOPPED;
            httpsFailureReason = "No certificates loaded";
            Blast.log("Proxy HTTPS not started: no certificates available");
            return;
        }

        int httpsPort = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTPS_PORT);

        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            SniKeyManager sniKeyManager = new SniKeyManager(certificateStore, dispatcher::isBanned);
            sslContext.init(new KeyManager[]{sniKeyManager}, null, null);

            Undertow.Builder builder = Undertow.builder()
                .addHttpsListener(httpsPort, "0.0.0.0", sslContext)
                .setIoThreads(IO_THREADS)
                .setHandler(handler);

            addIpv6Listener(builder, httpsPort, sslContext);
            httpsServer = builder.build();

            httpsServer.start();
            dispatcher.setHttpsAvailable(true);
            httpsState = State.RUNNING;
            httpsFailureReason = null;
            Blast.log("Proxy HTTPS listening on port", httpsPort,
                      "(" + certificateStore.getCertificateCount() + " certificates)");
        } catch (Exception e) {
            dispatcher.setHttpsAvailable(false);
            httpsState = State.FAILED;
            httpsFailureReason = e.getMessage();
            httpsServer = null;
            Blast.log("PROXY HTTPS STARTUP FAILED on port", httpsPort + ":", e.getMessage());
        }
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

    public void stop() {
        acmeService.stop();
        dispatcher.shutdown();
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        if (httpsServer != null) {
            httpsServer.stop();
            httpsServer = null;
        }
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
    public void reload() {
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

        // Start HTTPS if it wasn't running and we now have certs
        if (httpsState != State.RUNNING && !certificateStore.isEmpty()) {
            Blast.log("Proxy HTTPS starting after certificate reload");
            if (httpsServer != null) {
                httpsServer.stop();
                httpsServer = null;
            }
            startHttpsListener();
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
