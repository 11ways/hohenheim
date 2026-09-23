package be.elevenways.hohenheim.server.auth.types;

import be.elevenways.hohenheim.auth.SiteAuthDecision;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.BasicCredentials;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.server.auth.SiteAuthContext;
import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.proxy.auth.CredentialOwner;
import be.elevenways.hohenheim.server.proxy.auth.ProxyAuthKeys;
import be.elevenways.hohenheim.server.proxy.auth.ProxyAuthThrottle;
import be.elevenways.hohenheim.server.proxy.auth.ProxySessionSupport;
import be.elevenways.hohenheim.server.proxy.auth.SessionAuthority;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.session.SessionStore;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * Gates proxied upstreams behind HTTP Basic credentials and establishes a proxy session after login.
 *
 * AIDEV-NOTE: a session is bound to the subject's STORED hash, so changing a user's password or
 * deleting the user ends that user's sessions at once, and no other user's.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public class BasicAuthGate implements SiteAuthGate, CredentialOwner, SessionAuthority {

    private static final HttpString WWW_AUTHENTICATE = new HttpString("WWW-Authenticate");

    private final SessionStore store;
    private final int siteId;
    private final String providerSlug;
    private final int providerId;
    private final Map<String, String> credentials;
    private final String challenge;

    BasicAuthGate(SiteAuthContext context) {
        this.store = context.sessionStore();
        this.siteId = context.siteId();
        this.providerSlug = context.providerSlug();
        this.providerId = context.providerId();
        this.credentials = BasicAuthProviderType.credentials(configMap(context));
        this.challenge = "Basic realm=\"" + realmName(this.siteId) + "\"";
    }

    /** The site's name as the Basic realm (quotes stripped); falls back to "Restricted". */
    private static String realmName(int siteId) {
        Row site = Models.get(SiteModel.class).findById(siteId);
        String name = site != null ? site.get(SiteModel.NAME) : null;
        if (name == null || name.isBlank()) {
            return "Restricted";
        }
        return name.replace("\"", "");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configMap(SiteAuthContext context) {
        Object raw = context.config().get(SiteAuthProviderModel.CONFIG);
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @Override
    public @Nullable SiteAuthDecision evaluate(HttpServerExchange exchange) {
        // Fast path: a session this gate still accepts bypasses credential verification.
        if (ProxySessionSupport.acceptedSession(exchange, store, siteId, this) != null) {
            return null;
        }

        // Nothing presented costs nothing: no argon2, no budget, no session.
        String header = exchange.getRequestHeaders().getFirst(Headers.AUTHORIZATION);
        if (BasicCredentials.parse(header) == null) {
            return challenge(exchange);
        }

        SiteAuthDecision throttled = ProxyAuthThrottle.spend(exchange, siteId);
        if (throttled != null) {
            return throttled;
        }

        String username = BasicAuthProviderType.verify(header, credentials);
        String binding = username != null ? bindingFor(username) : null;
        if (binding == null) {
            return challenge(exchange);
        }

        ProxySessionSupport.establish(exchange, store, siteId, providerSlug, providerId, binding, username, null);
        return null;
    }

    @Override
    public boolean accepts(@NonNull Session session) {
        String subject = session.get(ProxyAuthKeys.SUBJECT);
        return subject != null && ProxySessionSupport.boundTo(session, providerId, bindingFor(subject));
    }

    @Override
    public boolean ownsAuthorizationHeader() {
        return true;
    }

    /** The subject's binding under the current credential map, or null when the user is gone. */
    private @Nullable String bindingFor(@NonNull String subject) {
        String stored = credentials.get(subject);
        if (stored == null || stored.isBlank()) {
            return null;
        }
        return ProxySessionSupport.binding(providerSlug, String.valueOf(providerId), subject, stored);
    }

    private SiteAuthDecision challenge(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(WWW_AUTHENTICATE, challenge);
        return SiteAuthDecision.deny(401, "Unauthorized");
    }
}
