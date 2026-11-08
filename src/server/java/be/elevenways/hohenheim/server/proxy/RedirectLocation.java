package be.elevenways.hohenheim.server.proxy;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE one way the proxy answers a redirect it was configured with: a redirect site and a site's own redirects.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class RedirectLocation {

    private RedirectLocation() {
    }

    /**
     * Ends the exchange with {@code status} and a Location of {@code target}.
     *
     * @param appendedPath a RAW path appended to the target (an encoded %0a or %3F stays encoded), null for none
     * @param keepQuery    whether the request's query string follows the target
     */
    public static void send(@NonNull HttpServerExchange exchange, int status, @NonNull String target,
                            @Nullable String appendedPath, boolean keepQuery) {
        // Control characters never reach the header, and a browser would drop a tab or newline anyway, joining what
        // surrounds it: they go before the fold below judges the location.
        String location = (target + (appendedPath == null ? "" : appendedPath)).replaceAll("[\\x00-\\x1F\\x7F]", "");
        // A same-site target plus a request's own path may read as //host or /\host, which a browser follows off the
        // site: such a location is folded back to one slash, so a path redirect never leaves its site.
        if (location.startsWith("/")) {
            location = "/" + location.replaceFirst("^[/\\\\]+", "");
        }
        String query = exchange.getQueryString();
        if (keepQuery && query != null && !query.isEmpty()) {
            location += (location.contains("?") ? "&" : "?") + query;
        }
        exchange.setStatusCode(status);
        exchange.getResponseHeaders().put(Headers.LOCATION, location);
        exchange.endExchange();
    }
}
