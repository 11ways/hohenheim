package be.elevenways.hohenheim.server.auth.types;

import be.elevenways.hohenheim.auth.SiteAuthDecision;
import be.elevenways.hohenheim.server.auth.SiteAuthContext;
import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.proxy.auth.ProxyAuthKeys;
import be.elevenways.hohenheim.server.proxy.auth.ProxySessionSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusClient;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusPermissions;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.session.SessionToken;
import be.elevenways.zenit.common.session.SessionStore;
import be.elevenways.hohenheim.server.proxy.ProxyScheme;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Deque;

/**
 * Gates proxied upstreams behind a Proteus realm. Redirect-based flow (faithful to alchemy-acl's
 * ProxiedProteus, which always redirects the browser to Proteus and back to {@code ?proteus=verify}).
 *
 * State machine, per request:
 *   ALLOW            -- an authenticated session exists (permission was checked at login) -> forward.
 *   VERIFY_PENDING   -- {@code ?proteus=verify}: poll remote_login_result with the stored rlid;
 *                       on success establish the session, set the acpl cookie, redirect to afterLogin.
 *   PERSISTENT       -- an acpl cookie: validate it with Proteus; on success establish + forward.
 *   REDIRECT_TO_LOGIN-- cold start: create a login session, store rlid + afterLogin, redirect to Proteus.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public class ProteusAuthGate implements SiteAuthGate {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SessionStore store;
    private final int siteId;
    private final String providerSlug;
    private final int providerId;
    private final @Nullable String requiredPermission;
    private final ProteusClient client;
    private final String authenticator;
    private final int persistentTtlSeconds;

    ProteusAuthGate(SiteAuthContext context, ProteusClient client, String authenticator,
                    int persistentTtlSeconds) {
        this.store = context.sessionStore();
        this.siteId = context.siteId();
        this.providerSlug = context.providerSlug();
        this.providerId = context.providerId();
        this.requiredPermission = context.requiredPermission();
        this.client = client;
        this.authenticator = authenticator;
        this.persistentTtlSeconds = persistentTtlSeconds;
    }

    @Override
    public @Nullable SiteAuthDecision evaluate(HttpServerExchange exchange) {
        try {
            // ALLOW: already authenticated (the permission was enforced at login time).
            if (ProxySessionSupport.authenticatedSession(exchange, store, siteId) != null) {
                return null;
            }

            // VERIFY_PENDING: the browser has returned from Proteus.
            if (isVerifyCallback(exchange)) {
                return handleVerify(exchange);
            }

            // PERSISTENT: an acpl remember-me cookie.
            String acpl = ProxySessionSupport.readPersistentCookie(exchange);
            String[] cookie = acpl != null ? decodeAcpl(acpl) : null;
            if (cookie != null) {
                String ip = exchange.getSourceAddress().getAddress().getHostAddress();
                ProteusClient.LoginResult result = client.persistentCookieLoginResult(
                    cookie[0], cookie[1], cookie[2], cookie[0], ip);
                if (result.success() && result.handle() != null) {
                    if (!permitted(result.permissions())) {
                        return SiteAuthDecision.deny(403, "Forbidden");
                    }
                    establishSession(exchange, result.handle());
                    return null;
                }
                ProxySessionSupport.clearPersistentCookie(exchange);  // stale -> drop and re-login
            }

            // REDIRECT_TO_LOGIN: cold start.
            return startLogin(exchange);

        } catch (Exception e) {
            Blast.log("ProteusAuthGate: error for site", siteId, "-", e.getMessage());
            return SiteAuthDecision.deny(502, "Authentication service unavailable");
        }
    }

    private SiteAuthDecision handleVerify(HttpServerExchange exchange)
            throws java.io.IOException, InterruptedException {
        String presented = ProxySessionSupport.readSessionCookie(exchange);
        Session session = (presented == null || presented.isEmpty())
            ? null : store.get(SessionToken.of(presented));
        String rlid = session != null ? session.get(ProxyAuthKeys.PENDING_RLID) : null;

        if (session == null || rlid == null) {
            return startLogin(exchange);  // no pending login -> start fresh
        }

        ProteusClient.LoginResult result = client.remoteLoginResult(rlid);
        if (!result.success() || !result.finished()) {
            return startLogin(exchange);  // not finished / failed -> start fresh
        }

        String subject = result.handle();
        if (subject == null || subject.isEmpty()) {
            return SiteAuthDecision.deny(502, "Authentication returned no identity");
        }
        if (!permitted(result.permissions())) {
            return SiteAuthDecision.deny(403, "Forbidden");
        }

        // Upgrade the pending session to authenticated.
        session.set(ProxyAuthKeys.SUBJECT, subject);
        session.remove(ProxyAuthKeys.PENDING_RLID);
        String afterLogin = session.get(ProxyAuthKeys.AFTER_LOGIN);
        session.remove(ProxyAuthKeys.AFTER_LOGIN);
        store.save(session);

        issuePersistentCookie(exchange, subject);
        return SiteAuthDecision.redirect(afterLogin != null ? afterLogin : "/");
    }

    private SiteAuthDecision startLogin(HttpServerExchange exchange)
            throws java.io.IOException, InterruptedException {
        String afterLogin = baseUrl(exchange);
        String returnUrl = appendVerify(afterLogin);

        ProteusClient.LoginSession ls = client.createLoginSession(authenticator, returnUrl, false);
        if (ls.loginUrl() == null) {
            return SiteAuthDecision.deny(502, "Login service unavailable");
        }

        Session session = store.create();
        session.set(ProxyAuthKeys.SITE_ID, siteId);
        session.set(ProxyAuthKeys.PROVIDER_SLUG, providerSlug);
        session.set(ProxyAuthKeys.PROVIDER_ID, providerId);
        if (ls.rlid() != null) {
            session.set(ProxyAuthKeys.PENDING_RLID, ls.rlid());
        }
        session.set(ProxyAuthKeys.AFTER_LOGIN, afterLogin);
        store.save(session);
        ProxySessionSupport.writeSessionCookie(exchange, session);

        return SiteAuthDecision.redirect(ls.loginUrl());
    }

    private void establishSession(HttpServerExchange exchange, String subject) {
        Session session = store.create();
        session.set(ProxyAuthKeys.SITE_ID, siteId);
        session.set(ProxyAuthKeys.PROVIDER_SLUG, providerSlug);
        session.set(ProxyAuthKeys.PROVIDER_ID, providerId);
        session.set(ProxyAuthKeys.SUBJECT, subject);
        store.save(session);
        ProxySessionSupport.writeSessionCookie(exchange, session);
    }

    /** Auto-register a persistent remember-me cookie. Best-effort: a failure never fails the login. */
    private void issuePersistentCookie(HttpServerExchange exchange, String handle) {
        try {
            String identifier = randomToken();
            String token = randomToken();
            ProteusClient.PersistentCookieResult reg = client.registerPersistentCookie(handle, identifier, token);
            if (reg.success()) {
                ProxySessionSupport.writePersistentCookie(exchange, encodeAcpl(handle, identifier, token),
                    persistentTtlSeconds);
            }
        } catch (Exception e) {
            Blast.log("ProteusAuthGate: persistent cookie registration failed -", e.getMessage());
        }
    }

    private boolean permitted(@Nullable Object permissionsClaim) {
        if (requiredPermission == null || requiredPermission.isBlank()) {
            return true;  // null required permission = any authenticated identity
        }
        return ProteusPermissions.of(permissionsClaim).hasPermission(requiredPermission);
    }

    private static boolean isVerifyCallback(HttpServerExchange exchange) {
        Deque<String> values = exchange.getQueryParameters().get("proteus");
        return values != null && values.contains("verify");
    }

    /**
     * The public URL Proteus sends the browser back to. AIDEV-NOTE: the scheme comes from
     * {@link ProxyScheme}, not the raw request scheme -- behind a TLS terminator the raw
     * scheme would hand the identity provider an http:// return URL.
     */
    private static String baseUrl(HttpServerExchange exchange) {
        String scheme = ProxyScheme.effectiveScheme(exchange);
        String host = exchange.getRequestHeaders().getFirst(Headers.HOST);
        if (host == null) {
            host = exchange.getHostAndPort();
        }
        String query = stripProteusParam(exchange.getQueryString());
        StringBuilder url = new StringBuilder(scheme).append("://").append(host)
            .append(exchange.getRequestPath());
        if (query != null && !query.isEmpty()) {
            url.append('?').append(query);
        }
        return url.toString();
    }

    private static @Nullable String stripProteusParam(@Nullable String query) {
        if (query == null || query.isEmpty()) {
            return query;
        }
        StringBuilder out = new StringBuilder();
        for (String pair : query.split("&")) {
            if (pair.equals("proteus") || pair.startsWith("proteus=")) {
                continue;
            }
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(pair);
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static String appendVerify(String baseUrl) {
        return baseUrl + (baseUrl.indexOf('?') >= 0 ? '&' : '?') + "proteus=verify";
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encodeAcpl(String handle, String identifier, String token) {
        String encodedHandle = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(handle.getBytes(StandardCharsets.UTF_8));
        return encodedHandle + ":" + identifier + ":" + token;
    }

    private static @Nullable String[] decodeAcpl(String value) {
        String[] parts = value.split(":", 3);
        if (parts.length != 3) {
            return null;
        }
        try {
            String handle = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            return new String[]{handle, parts[1], parts[2]};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
