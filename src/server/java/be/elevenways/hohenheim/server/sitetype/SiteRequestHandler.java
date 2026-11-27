package be.elevenways.hohenheim.server.sitetype;

import io.undertow.server.HttpServerExchange;
import org.checkerframework.checker.nullness.qual.Nullable;

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
     * Optional response mutation, applied just before response headers commit.
     *
     * @return a mutator for this exchange, or null for no mutation
     */
    default @Nullable ResponseMutator mutateResponse(HttpServerExchange exchange) {
        return null;
    }

    /**
     * Answers this request as the site answers a path that does not exist, so a refused private path looks like a
     * missing one.
     *
     * @return false when the site has no not-found answer of its own Hohenheim may give without passing the request
     *         on (an app behind it); the caller then answers Hohenheim's not-found page
     */
    default boolean answerMissing(HttpServerExchange exchange) {
        return false;
    }

    /**
     * Clean up resources (kill processes, close connections, etc.).
     */
    default void destroy() {}
}
