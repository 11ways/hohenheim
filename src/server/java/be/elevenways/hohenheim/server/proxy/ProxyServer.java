package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.Blast;
import io.undertow.Undertow;

/**
 * The reverse proxy server that listens on the proxy ports (80/443)
 * and forwards traffic to upstream backends based on hostname.
 *
 * Has an explicit lifecycle: STOPPED -> RUNNING or FAILED.
 * A failed proxy does not bring down the admin UI -- the admin UI
 * is how you diagnose and fix the configuration. But the failure
 * is never silent: the state is always queryable and always logged.
 */
public class ProxyServer {

    public enum State {
        /** Not yet started. */
        STOPPED,
        /** Listening and routing traffic. */
        RUNNING,
        /** Start was attempted but failed. The reason is in {@link #getFailureReason()}. */
        FAILED
    }

    private final SiteDispatcher dispatcher;
    private Undertow httpServer;
    private volatile State state = State.STOPPED;
    private volatile String failureReason;

    public ProxyServer() {
        this.dispatcher = new SiteDispatcher();
    }

    public SiteDispatcher getDispatcher() {
        return dispatcher;
    }

    public State getState() {
        return state;
    }

    public String getFailureReason() {
        return failureReason;
    }

    /**
     * Load routes from database and start the proxy listener.
     *
     * On success, transitions to RUNNING.
     * On failure, transitions to FAILED with the reason stored.
     * Never throws -- the admin UI must remain operational regardless.
     */
    public void start() {
        try {
            dispatcher.reloadRoutes();

            int httpPort = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTP_PORT);

            httpServer = Undertow.builder()
                .addHttpListener(httpPort, "0.0.0.0")
                .setIoThreads(Math.max(2, Runtime.getRuntime().availableProcessors()))
                .setHandler(dispatcher)
                .build();

            httpServer.start();
            state = State.RUNNING;
            failureReason = null;
            Blast.log("Proxy server listening on port", httpPort,
                      "(" + dispatcher.getExactRouteCount() + " exact,",
                      dispatcher.getWildcardRouteCount() + " wildcard routes)");
        } catch (Exception e) {
            state = State.FAILED;
            failureReason = e.getMessage();
            httpServer = null;
            Blast.log("PROXY STARTUP FAILED:", e.getMessage());
        }
    }

    /**
     * Stop the proxy server. Transitions to STOPPED.
     */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
            Blast.log("Proxy server stopped");
        }
        state = State.STOPPED;
        failureReason = null;
    }

    /**
     * Reload routes from the database. If the proxy is FAILED, attempts to start it again.
     */
    public void reload() {
        if (state == State.FAILED) {
            Blast.log("Proxy was in FAILED state -- attempting restart after route reload");
            start();
        } else {
            dispatcher.reloadRoutes();
        }
    }
}
