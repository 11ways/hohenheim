package be.elevenways.hohenheim.server.proxy.auth;

import be.elevenways.hohenheim.server.proxy.ProxyScheme;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.session.SessionStore;
import be.elevenways.zenit.common.session.SessionToken;
import be.elevenways.zenit.server.security.SecureTokens;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * Cookie + proxy-session helpers operating purely at the Undertow exchange level (no Zenit
 * Conduit). Cookies are HttpOnly, Path=/, SameSite=Lax, and Secure over HTTPS, with no Domain.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class ProxySessionSupport {

    /** How long a started identity-provider login may take before the browser drops its state. */
    public static final int PENDING_LOGIN_MAX_AGE_SECONDS = 15 * 60;

    /**
     * The session a gate established while handling THIS exchange.
     *
     * AIDEV-NOTE: the new cookie only reaches the RESPONSE, so without this a second look at
     * the same request (the access-list loop re-evaluating after a remember-me login, a
     * protected-path leaf after the site gate) saw no session, challenged again and minted
     * another one per pass.
     */
    private static final AttachmentKey<Session> ESTABLISHED = AttachmentKey.create(Session.class);

    public static @Nullable String readSessionCookie(HttpServerExchange exchange) {
        return cookieValue(exchange, ProxyAuthKeys.SESSION_COOKIE);
    }

    public static @Nullable String readPersistentCookie(HttpServerExchange exchange) {
        return cookieValue(exchange, ProxyAuthKeys.PERSISTENT_COOKIE);
    }

    public static @Nullable String readPendingLoginCookie(HttpServerExchange exchange) {
        return cookieValue(exchange, ProxyAuthKeys.PENDING_LOGIN_COOKIE);
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

    public static void writePendingLoginCookie(HttpServerExchange exchange, String sealed) {
        Cookie cookie = baseCookie(exchange, ProxyAuthKeys.PENDING_LOGIN_COOKIE, sealed);
        cookie.setMaxAge(PENDING_LOGIN_MAX_AGE_SECONDS);
        exchange.setResponseCookie(cookie);
    }

    public static void clearPendingLoginCookie(HttpServerExchange exchange) {
        Cookie cookie = baseCookie(exchange, ProxyAuthKeys.PENDING_LOGIN_COOKIE, "");
        cookie.setMaxAge(0);
        exchange.setResponseCookie(cookie);
    }

    /**
     * The valid, authenticated session for this site, or null. Enforces per-site isolation: a
     * session minted for another site is rejected, and a session with no SUBJECT is not
     * authenticated. Whether it satisfies a particular gate is {@link #acceptedSession}.
     */
    public static @Nullable Session authenticatedSession(HttpServerExchange exchange,
                                                          SessionStore store, int siteId) {
        Session established = exchange.getAttachment(ESTABLISHED);
        if (established != null && isAuthenticatedFor(established, siteId)) {
            return established;
        }

        // A request carrying no cookies at all cannot carry a session: answering that from the
        // header skips cookie parsing on the anonymous path, which is most of the traffic a
        // gated site sees before anyone logs in.
        if (exchange.getRequestHeaders().getFirst(Headers.COOKIE) == null) {
            return null;
        }

        String presented = readSessionCookie(exchange);
        if (presented == null || presented.isEmpty()) {
            return null;
        }

        // The wire boundary: the proxy cookie is the presented secret, never a stored id.
        Session session = store.get(SessionToken.of(presented));
        return session != null && isAuthenticatedFor(session, siteId) ? session : null;
    }

    /**
     * The authenticated session for this site that the given gate still accepts, or null.
     */
    public static @Nullable Session acceptedSession(@NonNull HttpServerExchange exchange,
                                                    @NonNull SessionStore store, int siteId,
                                                    @NonNull SessionAuthority authority) {
        Session session = authenticatedSession(exchange, store, siteId);
        return session != null && authority.accepts(session) ? session : null;
    }

    /**
     * Mint a fresh authenticated session, revoke the one the browser presented, and set the cookie.
     *
     * AIDEV-NOTE: ALWAYS a new session id on login (session fixation): whatever session the
     * browser carried in -- a planted one, another site's on the same host, a stale one -- is
     * revoked rather than upgraded, so a token known before the login never authenticates after it.
     *
     * @param binding         the gate's {@link #binding} for this subject under its current config
     * @param permissionClaim the identity provider's permission claim, or null when it has none
     */
    public static @NonNull Session establish(@NonNull HttpServerExchange exchange, @NonNull SessionStore store,
                                             int siteId, @NonNull String providerSlug, int providerId,
                                             @NonNull String binding, @NonNull String subject,
                                             @Nullable Object permissionClaim) {
        String presented = readSessionCookie(exchange);
        if (presented != null && !presented.isEmpty()) {
            Session previous = store.get(SessionToken.of(presented));
            if (previous != null) {
                store.revoke(previous.id());
            }
        }

        Session session = store.create();
        session.set(ProxyAuthKeys.SITE_ID, siteId);
        session.set(ProxyAuthKeys.PROVIDER_SLUG, providerSlug);
        session.set(ProxyAuthKeys.PROVIDER_ID, providerId);
        session.set(ProxyAuthKeys.BINDING, binding);
        session.set(ProxyAuthKeys.SUBJECT, subject);
        session.set(ProxyAuthKeys.PERMISSION_CLAIM, permissionClaim);
        store.save(session);
        writeSessionCookie(exchange, session);
        exchange.putAttachment(ESTABLISHED, session);
        return session;
    }

    /**
     * Whether a session was minted by this provider record under this binding.
     *
     * @param binding the gate's current binding for the session's subject; null never matches
     */
    public static boolean boundTo(@NonNull Session session, int providerId, @Nullable String binding) {
        Integer established = session.get(ProxyAuthKeys.PROVIDER_ID);
        return established != null && established == providerId && binding != null
            && SecureTokens.constantTimeEquals(binding, session.get(ProxyAuthKeys.BINDING));
    }

    /**
     * THE digest a gate binds its sessions to: the configuration facts whose change must end them.
     */
    public static @NonNull String binding(@NonNull String... facts) {
        StringBuilder joined = new StringBuilder();
        for (String fact : facts) {
            // Length-prefixed so no two different fact lists can join to the same text.
            String value = Objects.requireNonNullElse(fact, "");
            joined.append(value.length()).append(':').append(value).append('\n');
        }
        return SecureTokens.sha256Hex(joined.toString());
    }

    private static boolean isAuthenticatedFor(Session session, int siteId) {
        Integer sessionSite = session.get(ProxyAuthKeys.SITE_ID);
        return sessionSite != null && sessionSite.intValue() == siteId
            && session.get(ProxyAuthKeys.SUBJECT) != null;
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
