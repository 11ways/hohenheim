package be.elevenways.hohenheim.server.proxy.auth;

import be.elevenways.hohenheim.server.proxy.ProxyScheme;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.session.SessionStore;
import be.elevenways.zenit.common.session.SessionToken;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Cookie + proxy-session helpers operating purely at the Undertow exchange level (no Zenit
 * Conduit). Cookies are HttpOnly, Path=/, SameSite=Lax, and Secure over HTTPS, with no Domain.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class ProxySessionSupport {

    public static @Nullable String readSessionCookie(HttpServerExchange exchange) {
        return cookieValue(exchange, ProxyAuthKeys.SESSION_COOKIE);
    }

    public static @Nullable String readPersistentCookie(HttpServerExchange exchange) {
        return cookieValue(exchange, ProxyAuthKeys.PERSISTENT_COOKIE);
    }

    /**
     * AIDEV-NOTE: takes the SESSION, never a String. The cookie carries Session.token() (the
     * secret); Session.id() is the storage identity and authenticates nobody -- writing it here
     * used to compile, send a cookie, and log the visitor out again on the very next request.
     *
     * @throws IllegalStateException when the session carries no token
     */
    public static void writeSessionCookie(HttpServerExchange exchange, Session session) {
        SessionToken token = session.token();
        if (token == null) {
            throw new IllegalStateException("Cannot write a proxy session cookie for session "
                + session.id() + ": this context never held its secret");
        }
        exchange.setResponseCookie(baseCookie(exchange, ProxyAuthKeys.SESSION_COOKIE, token.secret()));
    }

    public static void clearSessionCookie(HttpServerExchange exchange) {
        Cookie cookie = baseCookie(exchange, ProxyAuthKeys.SESSION_COOKIE, "");
        cookie.setMaxAge(0);
        exchange.setResponseCookie(cookie);
    }

    public static void writePersistentCookie(HttpServerExchange exchange, String value, int maxAgeSeconds) {
        Cookie cookie = baseCookie(exchange, ProxyAuthKeys.PERSISTENT_COOKIE, value);
        cookie.setMaxAge(maxAgeSeconds);
        exchange.setResponseCookie(cookie);
    }

    public static void clearPersistentCookie(HttpServerExchange exchange) {
        Cookie cookie = baseCookie(exchange, ProxyAuthKeys.PERSISTENT_COOKIE, "");
        cookie.setMaxAge(0);
        exchange.setResponseCookie(cookie);
    }

    /**
     * The valid, authenticated session for this site, or null. Enforces per-site isolation: a
     * session minted for another site is rejected, and a session with no SUBJECT (e.g. a pending
     * login) is not yet authenticated.
     */
    public static @Nullable Session authenticatedSession(HttpServerExchange exchange,
                                                          SessionStore store, int siteId) {
        String presented = readSessionCookie(exchange);
        if (presented == null || presented.isEmpty()) {
            return null;
        }

        // The wire boundary: the proxy cookie is the presented secret, never a stored id.
        Session session = store.get(SessionToken.of(presented));
        if (session == null) {
            return null;
        }

        Integer sessionSite = session.get(ProxyAuthKeys.SITE_ID);
        if (sessionSite == null || sessionSite.intValue() != siteId) {
            return null;
        }

        return session.get(ProxyAuthKeys.SUBJECT) != null ? session : null;
    }

    private static @Nullable String cookieValue(HttpServerExchange exchange, String name) {
        Cookie cookie = exchange.getRequestCookie(name);
        return cookie != null ? cookie.getValue() : null;
    }

    /**
     * AIDEV-NOTE: Secure rides {@link ProxyScheme}, never the raw request scheme -- the raw
     * scheme is correct only while hohenheim terminates TLS itself and silently drops Secure
     * the moment it sits behind a terminator, letting the session cookie travel in cleartext.
     */
    private static Cookie baseCookie(HttpServerExchange exchange, String name, String value) {
        Cookie cookie = new CookieImpl(name, value);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSameSiteMode("Lax");
        if (ProxyScheme.isEffectivelyHttps(exchange)) {
            cookie.setSecure(true);
        }
        return cookie;
    }

    private ProxySessionSupport() {}
}
