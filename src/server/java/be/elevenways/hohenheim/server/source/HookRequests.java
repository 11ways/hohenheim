package be.elevenways.hohenheim.server.source;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The exchange handling of the inbound build and git hooks.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class HookRequests {

    private HookRequests() {
    }

    /** @return whether the request is a POST under {@code prefix} */
    static boolean isPostTo(@NonNull HttpServerExchange exchange, @NonNull String prefix) {
        return "POST".equals(exchange.getRequestMethod().toString()) && exchange.getRequestPath().startsWith(prefix);
    }

    static void sendJson(@NonNull HttpServerExchange exchange, int status, @NonNull String json) {
        exchange.setStatusCode(status);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(json);
    }
}
