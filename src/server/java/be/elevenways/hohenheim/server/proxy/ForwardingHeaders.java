package be.elevenways.hohenheim.server.proxy;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonicalizes the upstream request headers: custom operator rules, hop-by-hop hygiene,
 * and the four client-origin headers hohenheim owns.
 */
final class ForwardingHeaders {

    static final HttpString X_PROXIED_BY = new HttpString("X-Proxied-By");
    static final HttpString X_FORWARDED_FOR = new HttpString("X-Forwarded-For");
    static final HttpString X_FORWARDED_HOST = new HttpString("X-Forwarded-Host");
    static final HttpString X_FORWARDED_PROTO = ProxyScheme.X_FORWARDED_PROTO;
    static final HttpString X_REAL_IP = new HttpString("X-Real-IP");

    // Trusted-remote-proxy authentication, owned by ProxyScheme. Deliberately distinct from
    // the deleted managed-process control key: that one authorized
    // process-control actions, this one authorizes client-IP and proto propagation.
    private static final HttpString X_HOHENHEIM_KEY = ProxyScheme.X_HOHENHEIM_KEY;

    // AIDEV-NOTE: the client-asserted forwarding family that hohenheim does NOT own. The four
    // it does own (X-Forwarded-Proto/Host, X-Real-IP, X-Forwarded-For) are regenerated below
    // from its own X-Hohenheim-Key-authenticated decision; every other spelling that conveys
    // the same client-origin trust (RFC 7239 Forwarded, the X-Forwarded-* aliases, the CDN
    // client-IP headers, the IIS URL-rewrite pair) is STRIPPED unconditionally -- trusted peer
    // or not, since none is part of hohenheim's federation contract. This is a deny-list by
    // deliberate design: a general reverse proxy must pass arbitrary application headers
    // verbatim (a tenant app defines its own request headers), so an allow-list would silently
    // break tenants; the honest boundary is to enumerate the trust-conveying family and drop it.
    // The residual risk (a future vendor header not listed) is inherent to any reverse proxy and
    // is why this list is ONE named constant the test enumerates -- adding one is a one-line edit.
    private static final HttpString[] STRIPPED_FORWARDING_HEADERS = {
        new HttpString("Forwarded"),
        new HttpString("X-Forwarded-Server"),
        new HttpString("X-Forwarded-Port"),
        new HttpString("X-Forwarded-Prefix"),
        new HttpString("X-Forwarded-Scheme"),
        new HttpString("X-Forwarded-Ssl"),
        new HttpString("True-Client-IP"),
        new HttpString("CF-Connecting-IP"),
        new HttpString("X-Client-IP"),
        new HttpString("X-Original-URL"),
        new HttpString("X-Rewrite-URL"),
    };

    // AIDEV-NOTE: hop-by-hop request headers that must never be forwarded to an upstream
    // (RFC 7230 6.1). Transfer-Encoding and Upgrade are deliberately absent: Undertow owns the
    // upstream wire framing and the WebSocket tunnel, and the websocket_upgrade gate governs
    // Upgrade. Connection is handled separately in sanitizeHopByHopHeaders (its tokens name
    // further headers to drop, and it must survive an upgrade so Undertow can still tunnel).
    private static final HttpString[] HOP_BY_HOP_HEADERS = {
        Headers.KEEP_ALIVE,
        new HttpString("TE"),
        new HttpString("Trailer"),
        new HttpString("Proxy-Connection"),
        Headers.PROXY_AUTHENTICATE,
        Headers.PROXY_AUTHORIZATION,
    };

    private ForwardingHeaders() {}

    /**
     * Rewrite the request headers for the upstream: operator rules first, then the trust
     * boundary, so an operator rule can never re-introduce a spoofable client-origin header.
     */
    static void applyRequestHeaders(HttpServerExchange exchange, RouteEntry entry,
                                    String instanceId, String hostname, String clientIp) {

        HeaderMap requestHeaders = exchange.getRequestHeaders();
        requestHeaders.put(X_PROXIED_BY, instanceId);

        String sourceIp = exchange.getSourceAddress().getAddress().getHostAddress();
        boolean trustedRemoteProxy = ProxyScheme.isTrustedRemoteProxy(exchange);
        String trustedForwardedFor = trustedRemoteProxy
            ? requestHeaders.getFirst(X_FORWARDED_FOR)
            : null;
        String trustedForwardedHost = trustedRemoteProxy
            ? requestHeaders.getFirst(X_FORWARDED_HOST)
            : null;

        // --- Custom upstream request headers ---
        if (!entry.customHeaders.isEmpty()) {
            for (HeaderRule header : entry.customHeaders) {
                HttpString headerName = new HttpString(header.name());
                if (header.value() == null || header.value().isBlank()) {
                    requestHeaders.remove(headerName);
                } else {
                    requestHeaders.put(headerName, header.value());
                }
            }
        }

        // These headers form one trust boundary and must be canonicalized after custom rules.
        // Undertow's reuseXForwarded behavior then appends the connected peer to the sanitized
        // chain, preserving only a chain authenticated by X-Hohenheim-Key.
        // AIDEV-NOTE: this strip happens BEFORE dispatchToRoute, so the managed-process
        // control API (the deleted X-Hohenheim-Key branch) is unreachable
        // through this listener today. Moving the strip later would make a privileged
        // endpoint publicly reachable -- a deliberate decision, never a side effect.
        requestHeaders.remove(X_HOHENHEIM_KEY);

        // Hop-by-hop hygiene, then strip the client-asserted forwarding family we do not own.
        // Both happen after custom rules and before the four canonical values are regenerated,
        // so an operator rule cannot re-introduce a spoofable trust header on the forward path.
        sanitizeHopByHopHeaders(requestHeaders);
        for (HttpString stripped : STRIPPED_FORWARDING_HEADERS) {
            requestHeaders.remove(stripped);
        }

        requestHeaders.put(X_FORWARDED_PROTO, ProxyScheme.effectiveScheme(exchange));
        requestHeaders.put(X_FORWARDED_HOST,
            trustedForwardedHost != null && !trustedForwardedHost.isBlank()
                ? trustedForwardedHost : hostname);
        requestHeaders.put(X_REAL_IP, clientIp);
        requestHeaders.remove(X_FORWARDED_FOR);
        if (trustedRemoteProxy && trustedForwardedFor != null) {
            requestHeaders.put(X_FORWARDED_FOR, trustedForwardedFor);
        } else if (trustedRemoteProxy && !clientIp.equals(sourceIp)) {
            requestHeaders.put(X_FORWARDED_FOR, clientIp);
        }
    }

    /**
     * Strip hop-by-hop request headers before forwarding: honour {@code Connection: <token>} by
     * dropping every client-named header, then remove the fixed hop-by-hop set. Connection and
     * Upgrade themselves survive a genuine upgrade request so Undertow can still tunnel it.
     */
    private static void sanitizeHopByHopHeaders(HeaderMap headers) {
        boolean upgrade = isUpgradeRequest(headers);
        for (String token : connectionTokens(headers)) {
            if (token.equalsIgnoreCase("close") || token.equalsIgnoreCase("keep-alive")
                    || token.equalsIgnoreCase("upgrade")) {
                continue;
            }
            headers.remove(new HttpString(token));
        }
        for (HttpString hop : HOP_BY_HOP_HEADERS) {
            headers.remove(hop);
        }
        if (!upgrade) {
            headers.remove(Headers.CONNECTION);
        }
    }

    /** A request is an upgrade only when both Upgrade and a {@code Connection: upgrade} token are present. */
    private static boolean isUpgradeRequest(HeaderMap headers) {
        if (!headers.contains(Headers.UPGRADE)) {
            return false;
        }
        for (String token : connectionTokens(headers)) {
            if (token.equalsIgnoreCase("upgrade")) {
                return true;
            }
        }
        return false;
    }

    /** Comma-split, trimmed tokens from every Connection header value on the exchange. */
    private static List<String> connectionTokens(HeaderMap headers) {
        List<String> values = headers.get(Headers.CONNECTION);
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String value : values) {
            for (String part : value.split(",")) {
                String token = part.trim();
                if (!token.isEmpty()) {
                    tokens.add(token);
                }
            }
        }
        return tokens;
    }
}
