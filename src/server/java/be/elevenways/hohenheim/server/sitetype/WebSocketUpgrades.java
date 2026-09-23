package be.elevenways.hohenheim.server.sitetype;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * THE refusal of an upgrade request on a site whose {@code websocket_upgrade} setting is off,
 * shared by every forwarding handler.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class WebSocketUpgrades {

    private WebSocketUpgrades() {}

    /**
     * Whether a {@code websocket_upgrade=false} site must refuse this exchange.
     *
     * AIDEV-NOTE: this fires on ANY upgrade attempt (the mere presence of an Upgrade header),
     * not just an exact {@code Upgrade: websocket} spelling -- a comma-list
     * ({@code h2c, websocket}) or a duplicate whose first value is not "websocket" would
     * otherwise slip past and let a 101 tunnel regardless of the setting, since the actual
     * tunnel is established by Undertow on the 101, not by our string match.
     */
    public static boolean refuses(@NonNull HttpServerExchange exchange, boolean websocketEnabled) {
        return !websocketEnabled && exchange.getRequestHeaders().contains(Headers.UPGRADE);
    }

    /**
     * Refuse the exchange with 403 when {@link #refuses} says so.
     *
     * @return true when the exchange was answered and the caller must stop
     */
    public static boolean refuse(@NonNull HttpServerExchange exchange, boolean websocketEnabled) {
        if (!refuses(exchange, websocketEnabled)) {
            return false;
        }
        exchange.setStatusCode(403);
        exchange.getResponseSender().send("WebSocket upgrades disabled for this site");
        return true;
    }
}
