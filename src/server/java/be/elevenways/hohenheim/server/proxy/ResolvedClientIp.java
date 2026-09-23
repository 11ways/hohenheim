package be.elevenways.hohenheim.server.proxy;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;

/**
 * Carries the proxy listener's trusted client-IP decision to the site gates, access trees and
 * route handlers of the same exchange.
 *
 * AIDEV-NOTE: the dispatcher attaches it BEFORE any site gate runs. It used to be attached only
 * after the gate, so a gate reading the client (the verification throttle, an identity
 * provider's audit field) saw the raw socket peer -- the fronting proxy -- instead of the
 * client that proxy vouched for.
 */
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
