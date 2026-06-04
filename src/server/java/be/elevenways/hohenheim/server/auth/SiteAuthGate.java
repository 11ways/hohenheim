package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.auth.SiteAuthDecision;
import io.undertow.server.HttpServerExchange;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Per-site, long-lived, thread-safe auth gate for proxied upstreams. Created at route-load,
 * reused per request, destroyed on reload -- the exact lifecycle SiteRequestHandler follows.
 *
 * A gate operates ENTIRELY at the Undertow exchange level and never touches the Zenit
 * Conduit/principal pipeline (the proxy port is not a Zenit endpoint): it reads cookies via
 * ProxySessionSupport and reads/writes sessions only through the raw SessionStore string API.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public interface SiteAuthGate {

    /**
     * @return null to allow the request to be forwarded upstream; a decision to send instead.
     */
    @Nullable
    SiteAuthDecision evaluate(HttpServerExchange exchange);

    /** Release any resources held by this gate when its route is torn down. */
    default void destroy() {
    }
}
