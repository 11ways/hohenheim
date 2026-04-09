package be.elevenways.hohenheim.server.sitetype;

import io.undertow.server.HttpServerExchange;

/**
 * Handles incoming requests for a specific site instance.
 * One handler is created per site and reused across requests.
 * Implementations MUST be thread-safe.
 */
public interface SiteRequestHandler {

    /**
     * Handle an incoming request.
     * Proxy types call forwarder.forwardTo(uri).
     * Direct types (static, redirect) write the response to the exchange.
     */
    void handleRequest(HttpServerExchange exchange, UpstreamForwarder forwarder);

    /**
     * The site ID this handler is bound to, or -1 for anonymous handlers.
     */
    default int getSiteId() { return -1; }

    /**
     * Report health status for the admin UI.
     */
    default SiteHealth getHealth() {
        return SiteHealth.UP;
    }

    /**
     * Clean up resources (kill processes, close connections, etc.).
     */
    default void destroy() {}
}
