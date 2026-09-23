package be.elevenways.hohenheim.server.auth.types;

import be.elevenways.hohenheim.auth.SiteAuthDecision;
import be.elevenways.hohenheim.server.auth.SiteAuthContext;
import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.proxy.ProxyScheme;
import be.elevenways.hohenheim.server.proxy.ResolvedClientIp;
import be.elevenways.hohenheim.server.proxy.auth.CredentialOwner;
import be.elevenways.hohenheim.server.proxy.auth.ProxyAuthKeys;
import be.elevenways.hohenheim.server.proxy.auth.ProxyAuthThrottle;
import be.elevenways.hohenheim.server.proxy.auth.ProxySessionSupport;
import be.elevenways.hohenheim.server.proxy.auth.SessionAuthority;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusClient;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusPermissions;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.session.SessionStore;
import be.elevenways.zenit.server.security.SecureTokens;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Deque;
import java.util.Set;

/**
 * Gates proxied upstreams behind a Proteus realm. Redirect-based flow (faithful to alchemy-acl's
 * ProxiedProteus, which always redirects the browser to Proteus and back to {@code ?proteus=verify}).
 *
 * State machine, per request:
 *   ALLOW            -- a session this gate accepts exists (permission re-checked on the stored claim) -> forward.
 *   VERIFY_PENDING   -- {@code ?proteus=verify}: the sealed pending-login cookie and the returned state must
 *                       match; poll remote_login_result; on success mint a NEW session, set acpl, redirect back.
 *   PERSISTENT       -- an acpl cookie: validate it with Proteus; on success establish + forward.
 *   REDIRECT_TO_LOGIN-- cold start: create a login session, seal rlid + nonce into a cookie, redirect to Proteus.
 *
 * AIDEV-NOTE: the pending login is bound to the INITIATING browser twice over. The cookie carries
 * the rlid and a fresh nonce under a MAC, so it cannot be edited to point at another login; and the
 * return URL handed to Proteus carries a state derived from that nonce, which only the browser that
 * completed the login at Proteus receives. A verify must present both, so an attacker who starts a
 * login and phishes someone into completing it holds the cookie but never the state, and a forged
 * verify link cannot complete a login in a victim's browser (login CSRF). RESIDUAL: if Proteus ever
 * shows the return URL to the initiator (inside the login URL), the state leaks with it; closing that
 * needs a one-time code from Proteus in the redirect.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public class ProteusAuthGate implements SiteAuthGate, CredentialOwner, SessionAuthority {

    /** The query parameter that marks the return from Proteus. */
    static final String VERIFY_PARAM = "proteus";

    /** The query parameter carrying the login's state back from Proteus. */
    static final String STATE_PARAM = "proteus_state";

    private static final Set<String> OWN_PARAMS = Set.of(VERIFY_PARAM, STATE_PARAM);

    /**
     * The per-process key that seals pending logins.
     *
     * AIDEV-NOTE: never stored. A restart invalidates only the logins in flight at that moment
     * (their visitor is sent to Proteus once more); established sessions are unaffected.
     */
    private static final String PENDING_KEY = SecureTokens.randomToken(32);

    private final SessionStore store;
    private final int siteId;
    private final String providerSlug;
    private final int providerId;
    private final @Nullable String requiredPermission;
    private final ProteusClient client;
    private final String authenticator;
    private final int persistentTtlSeconds;
    private final String binding;

    ProteusAuthGate(SiteAuthContext context, ProteusClient client, String authenticator,
                    int persistentTtlSeconds, String binding) {
        this.store = context.sessionStore();
        this.siteId = context.siteId();
        this.providerSlug = context.providerSlug();
        this.providerId = context.providerId();
        this.requiredPermission = context.requiredPermission();
        this.client = client;
        this.authenticator = authenticator;
        this.persistentTtlSeconds = persistentTtlSeconds;
        this.binding = binding;
    }

    @Override
    public @Nullable SiteAuthDecision evaluate(HttpServerExchange exchange) {
        try {
            // ALLOW: a session this gate accepts, its permission re-checked against the stored claim.
            if (ProxySessionSupport.acceptedSession(exchange, store, siteId, this) != null) {
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
                SiteAuthDecision throttled = ProxyAuthThrottle.spend(exchange, siteId);
                if (throttled != null) {
                    return throttled;
                }
                ProteusClient.LoginResult result = client.persistentCookieLoginResult(
                    cookie[0], cookie[1], cookie[2], cookie[0], ResolvedClientIp.get(exchange));
                if (result.success() && result.handle() != null) {
                    if (!permitted(result.permissions())) {
                        return SiteAuthDecision.deny(403, "Forbidden");
                    }
                    establish(exchange, result.handle(), result.permissions());
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

    @Override
    public boolean accepts(@NonNull Session session) {
        return ProxySessionSupport.boundTo(session, providerId, binding)
            && permitted(session.get(ProxyAuthKeys.PERMISSION_CLAIM));
    }

    @Override
    public boolean ownsPersistentCookie() {
        return true;
    }

    private SiteAuthDecision handleVerify(HttpServerExchange exchange) throws IOException, InterruptedException {
        String[] pending = unsealPending(exchange);
        String returned = firstQueryValue(exchange, STATE_PARAM);
        if (pending == null || returned == null
                || !SecureTokens.constantTimeEquals(returned, stateFor(pending[1]))) {
            // Not the browser that started this login, or nothing pending: start over, never adopt.
            ProxySessionSupport.clearPendingLoginCookie(exchange);
            return startLogin(exchange);
        }

        SiteAuthDecision throttled = ProxyAuthThrottle.spend(exchange, siteId);
        if (throttled != null) {
            return throttled;
        }

        // The pending state is single-use whatever Proteus answers.
        ProxySessionSupport.clearPendingLoginCookie(exchange);
        ProteusClient.LoginResult result = client.remoteLoginResult(pending[0]);
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

        establish(exchange, subject, result.permissions());
        issuePersistentCookie(exchange, subject);
        return SiteAuthDecision.redirect(baseUrl(exchange));
    }

    private SiteAuthDecision startLogin(HttpServerExchange exchange) throws IOException, InterruptedException {
        SiteAuthDecision throttled = ProxyAuthThrottle.spend(exchange, siteId);
        if (throttled != null) {
            return throttled;
        }

        String nonce = SecureTokens.randomToken();
        String returnUrl = appendParam(appendParam(baseUrl(exchange), VERIFY_PARAM, "verify"),
            STATE_PARAM, stateFor(nonce));

        ProteusClient.LoginSession ls = client.createLoginSession(authenticator, returnUrl, false);
        if (ls.loginUrl() == null || ls.rlid() == null) {
            return SiteAuthDecision.deny(502, "Login service unavailable");
        }

        ProxySessionSupport.writePendingLoginCookie(exchange, sealPending(ls.rlid(), nonce));
        return SiteAuthDecision.redirect(ls.loginUrl());
    }

    private void establish(HttpServerExchange exchange, String subject, @Nullable Object permissionClaim) {
        ProxySessionSupport.establish(exchange, store, siteId, providerSlug, providerId, binding, subject,
            permissionClaim);
    }

    /** Auto-register a persistent remember-me cookie. Best-effort: a failure never fails the login. */
    private void issuePersistentCookie(HttpServerExchange exchange, String handle) {
        try {
            String identifier = SecureTokens.randomToken();
            String token = SecureTokens.randomToken();
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

    /** The state the return URL carries for a login started with this nonce. */
    private String stateFor(String nonce) {
        return SecureTokens.hmacSha256Hex(PENDING_KEY, "state\n" + siteId + "\n" + providerId + "\n" + nonce);
    }

    /** The pending-login cookie value: base64url(rlid) . nonce . mac. */
    private String sealPending(String rlid, String nonce) {
        String encodedRlid = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(rlid.getBytes(StandardCharsets.UTF_8));
        return encodedRlid + "." + nonce + "." + pendingMac(rlid, nonce);
    }

    private String pendingMac(String rlid, String nonce) {
        return SecureTokens.hmacSha256Hex(PENDING_KEY,
            "pending\n" + siteId + "\n" + providerId + "\n" + rlid + "\n" + nonce);
    }

    /** @return the {rlid, nonce} of an intact pending cookie sealed for this gate, or null */
    private @Nullable String[] unsealPending(HttpServerExchange exchange) {
        String sealed = ProxySessionSupport.readPendingLoginCookie(exchange);
        if (sealed == null) {
            return null;
        }
        String[] parts = sealed.split("\\.", 3);
        if (parts.length != 3) {
            return null;
        }
        String rlid;
        try {
            rlid = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        if (rlid.isEmpty() || !SecureTokens.constantTimeEquals(parts[2], pendingMac(rlid, parts[1]))) {
            return null;
        }
        return new String[]{rlid, parts[1]};
    }

    private static boolean isVerifyCallback(HttpServerExchange exchange) {
        Deque<String> values = exchange.getQueryParameters().get(VERIFY_PARAM);
        return values != null && values.contains("verify");
    }

    private static @Nullable String firstQueryValue(HttpServerExchange exchange, String name) {
        Deque<String> values = exchange.getQueryParameters().get(name);
        return values != null ? values.peekFirst() : null;
    }

    /**
     * The public URL of this request without the gate's own parameters: where Proteus sends the
     * browser back to, and where a completed login lands. AIDEV-NOTE: the scheme comes from
     * {@link ProxyScheme}, not the raw request scheme -- behind a TLS terminator the raw
     * scheme would hand the identity provider an http:// return URL.
     */
    private static String baseUrl(HttpServerExchange exchange) {
        String scheme = ProxyScheme.effectiveScheme(exchange);
        String host = exchange.getRequestHeaders().getFirst(Headers.HOST);
        if (host == null) {
            host = exchange.getHostAndPort();
        }
        String query = stripOwnParams(exchange.getQueryString());
        StringBuilder url = new StringBuilder(scheme).append("://").append(host)
            .append(exchange.getRequestPath());
        if (query != null && !query.isEmpty()) {
            url.append('?').append(query);
        }
        return url.toString();
    }

    private static @Nullable String stripOwnParams(@Nullable String query) {
        if (query == null || query.isEmpty()) {
            return query;
        }
        StringBuilder out = new StringBuilder();
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (OWN_PARAMS.contains(equals < 0 ? pair : pair.substring(0, equals))) {
                continue;
            }
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(pair);
        }
        return out.length() == 0 ? null : out.toString();
    }

    /** @param value URL-safe by construction (a fixed word or a hex digest) */
    private static String appendParam(String url, String name, String value) {
        return url + (url.indexOf('?') >= 0 ? '&' : '?') + name + "=" + value;
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
