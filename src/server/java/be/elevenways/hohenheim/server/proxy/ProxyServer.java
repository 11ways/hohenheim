package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.Blast;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.proxy.ProxyHandler;

/**
 * The reverse proxy server that listens on the proxy ports (80/443)
 * and forwards traffic to upstream backends based on hostname.
 */
public class ProxyServer {

    private final SiteDispatcher dispatcher;
    private Undertow httpServer;

    public ProxyServer() {
        this.dispatcher = new SiteDispatcher();
    }

    public SiteDispatcher getDispatcher() {
        return dispatcher;
    }

    /**
     * Load routes from database and start the proxy server.
     */
    public void start() {
        dispatcher.reloadRoutes();

        int httpPort = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTP_PORT);

        HttpHandler proxyHandler = ProxyHandler.builder()
            .setProxyClient(dispatcher)
            .setMaxRequestTime(30000)
            .setNext(ResponseCodeHandler.HANDLE_404)
            .setRewriteHostHeader(false) // preserve original Host header
            .setReuseXForwarded(false)
            .build();

        httpServer = Undertow.builder()
            .addHttpListener(httpPort, "0.0.0.0")
            .setIoThreads(Math.max(2, Runtime.getRuntime().availableProcessors()))
            .setHandler(proxyHandler)
            .build();

        try {
            httpServer.start();
            Blast.log("Proxy server started on port", httpPort,
                      "(" + dispatcher.getExactRouteCount() + " exact,",
                      dispatcher.getWildcardRouteCount() + " wildcard routes)");
        } catch (Exception e) {
            Blast.log("Failed to start proxy server on port", httpPort + ":", e.getMessage());
            Blast.log("(This is normal in development -- port 80 requires root privileges)");
        }
    }

    /**
     * Stop the proxy server.
     */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
            Blast.log("Proxy server stopped");
        }
    }

    /**
     * Reload routes from the database.
     */
    public void reload() {
        dispatcher.reloadRoutes();
    }
}
