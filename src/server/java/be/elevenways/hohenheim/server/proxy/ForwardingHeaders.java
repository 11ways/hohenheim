package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.server.proxy.auth.ProxyAuthKeys;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
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

    /**
     * The longest X-Proxied-By chain a request may carry. Every hop appends about ten
     * characters, so this is dozens of proxies deep: longer is a loop between proxies that do
     * not recognise each other, or a client padding the header, and both are refused as 508.
     */
    static final int MAX_PROXIED_BY_LENGTH = 512;

    private ForwardingHeaders() {}

    /**
     * Whether this request already passed through this dispatcher, or carries a hop chain too
     * long to extend.
     *
     * AIDEV-NOTE: the chain used to be REPLACED at every hop, so only a direct self-loop was
     * ever visible: A -> B -> A saw only "B" and forwarded forever. Each hop now appends its id
     * and this checks the whole chain, token by token (exact match, never a substring).
     */
    static boolean isLoop(HeaderMap headers, String instanceId) {
        HeaderValues values = headers.get(X_PROXIED_BY);
        if (values == null) {
            return false;
        }
        int length = 0;
        for (String value : values) {
            length += value.length() + 2;
            for (String hop : value.split(",")) {
                if (hop.trim().equals(instanceId)) {
                    return true;
                }
            }
        }
        return length + instanceId.length() > MAX_PROXIED_BY_LENGTH;
    }

    /**
     * Rewrite the request headers for the upstream: Hohenheim's own credentials out first,
     * then operator rules, then the trust boundary, so an operator rule can never
     * re-introduce a spoofable client-origin header but may still set the upstream's own
     * Authorization.
     */
    static void applyRequestHeaders(HttpServerExchange exchange, RouteEntry entry,
                                    String instanceId, String hostname, String clientIp) {

        HeaderMap requestHeaders = exchange.getRequestHeaders();
        HeaderValues hops = requestHeaders.get(X_PROXIED_BY);
        String chain = hops == null || hops.isEmpty() ? "" : String.join(", ", hops);
        requestHeaders.put(X_PROXIED_BY, chain.isBlank() ? instanceId : chain + ", " + instanceId);

        stripOwnCredentials(requestHeaders, entry);

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
     * Remove the authentication material Hohenheim itself consumed, so an upstream (possibly a
     * tenant's application) never receives a credential that is valid somewhere else.
     *
     * AIDEV-NOTE: the proxy session and pending-login cookies are Hohenheim's on EVERY route and
     * always go. The Authorization header and the acpl remember-me cookie are names an upstream
     * may use for itself, so they go only where a gate on this route claims them
     * ({@link RouteEntry#ownsAuthorizationHeader}, {@link RouteEntry#ownsPersistentCookie}). A
     * verified Basic header forwarded upstream handed the password to the backend; a forwarded
     * acpl handed it a replayable login for the visitor's identity-provider account.
     */
    static void stripOwnCredentials(HeaderMap headers, RouteEntry entry) {
        if (entry.ownsAuthorizationHeader) {
            // Only the Basic scheme is Hohenheim's: a Bearer token on a Basic-gated route is
            // the upstream's own credential and passes (the gate never reads it).
            String authorization = headers.getFirst(Headers.AUTHORIZATION);
            if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
                headers.remove(Headers.AUTHORIZATION);
            }
        }
        HeaderValues cookies = headers.get(Headers.COOKIE);
        if (cookies == null || cookies.isEmpty()) {
            return;
        }
        List<String> kept = new ArrayList<>();
        boolean changed = false;
        for (String value : cookies) {
            StringBuilder rebuilt = new StringBuilder();
            for (String pair : value.split(";")) {
                String trimmed = pair.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int equals = trimmed.indexOf('=');
                String name = (equals < 0 ? trimmed : trimmed.substring(0, equals)).trim();
                if (ProxyAuthKeys.HOHENHEIM_OWNED_COOKIES.contains(name)
                        || (entry.ownsPersistentCookie && ProxyAuthKeys.PERSISTENT_COOKIE.equals(name))) {
                    changed = true;
                    continue;
                }
                if (!rebuilt.isEmpty()) {
                    rebuilt.append("; ");
                }
                rebuilt.append(trimmed);
            }
            if (!rebuilt.isEmpty()) {
                kept.add(rebuilt.toString());
            }
        }
        if (!changed) {
            return;
        }
        headers.remove(Headers.COOKIE);
        for (String value : kept) {
            headers.add(Headers.COOKIE, value);
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
