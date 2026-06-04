package be.elevenways.hohenheim.server.auth.types;

import be.elevenways.hohenheim.auth.SiteAuthDecision;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.server.auth.SiteAuthContext;
import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.proxy.auth.ProxyAuthKeys;
import be.elevenways.hohenheim.server.proxy.auth.ProxySessionSupport;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.session.SessionStore;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Gates proxied upstreams behind HTTP Basic credentials. The first valid Basic header establishes
 * a proxy session, so the deliberately-expensive Argon2 verify runs once per session, not per
 * request.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public class BasicAuthGate implements SiteAuthGate {

    private static final HttpString WWW_AUTHENTICATE = new HttpString("WWW-Authenticate");
    private static final String CHALLENGE = "Basic realm=\"Restricted\"";

    private final SessionStore store;
    private final int siteId;
    private final String providerSlug;
    private final List<Map<String, Object>> credentials;

    BasicAuthGate(SiteAuthContext context) {
        this.store = context.sessionStore();
        this.siteId = context.siteId();
        this.providerSlug = context.providerSlug();
        this.credentials = BasicAuthProviderType.credentialList(configMap(context));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configMap(SiteAuthContext context) {
        Object raw = context.config().get(SiteAuthProviderModel.CONFIG);
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @Override
    public @Nullable SiteAuthDecision evaluate(HttpServerExchange exchange) {
        // Fast path: an established session for this site bypasses the per-request Argon2 verify.
        if (ProxySessionSupport.authenticatedSession(exchange, store, siteId) != null) {
            return null;
        }

        String header = exchange.getRequestHeaders().getFirst(Headers.AUTHORIZATION);
        String username = BasicAuthProviderType.verify(header, credentials);
        if (username == null) {
            exchange.getResponseHeaders().put(WWW_AUTHENTICATE, CHALLENGE);
            return SiteAuthDecision.deny(401, "Unauthorized");
        }

        Session session = store.create();
        session.set(ProxyAuthKeys.SITE_ID, siteId);
        session.set(ProxyAuthKeys.PROVIDER_SLUG, providerSlug);
        session.set(ProxyAuthKeys.SUBJECT, username);
        store.save(session);
        ProxySessionSupport.writeSessionCookie(exchange, session.id());
        return null;
    }
}
