package be.elevenways.hohenheim.server.proxy;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;

/** Carries the proxy listener's trusted client-IP decision to downstream route handlers. */
public final class ResolvedClientIp {

    private static final AttachmentKey<String> KEY = AttachmentKey.create(String.class);

    private ResolvedClientIp() {}

    public static void attach(HttpServerExchange exchange, String clientIp) {
        exchange.putAttachment(KEY, clientIp);
    }

    public static String get(HttpServerExchange exchange) {
        String value = exchange.getAttachment(KEY);
        return value != null ? value : exchange.getSourceAddress().getAddress().getHostAddress();
    }
}
