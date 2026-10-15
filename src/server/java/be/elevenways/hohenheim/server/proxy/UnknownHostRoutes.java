package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.sitetype.UpstreamTarget;
import be.elevenways.hohenheim.site.UnknownHostAnswer;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.http.DefaultPorts;
import be.elevenways.zenit.common.Zenit;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xnio.IoUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The proxy's answer for a hostname no site serves, as {@code proxy.unknown_hosts} chooses it (npmplus's "default
 * site"): the 404 page, a forward to the fallback address, a redirect, one of the operator's sites, or no response.
 *
 * AIDEV-NOTE: an answer whose target is missing or unusable answers the 404 page, never something wider. The SITE
 * answer serves the chosen site only through one of its own EXACT names (RouteTable#siteRoute): a pattern or regex
 * route carries no single name to redirect to HTTPS and no capture groups for an unknown name. Over HTTPS this is
 * reached only when the SNI matched a certificate (a wildcard, a stale name): an SNI no certificate covers never
 * completes its handshake (SniKeyManager answers no alias), whatever the answer.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class UnknownHostRoutes {

    // The forward route, rebuilt only when the fallback address changes.
    private volatile @Nullable ForwardRoute forwardRoute;

    /**
     * Answer the request for an unknown {@code hostname}, or hand back the route that answers it.
     *
     * @return the route to continue the pipeline with, or null when the exchange was answered (or closed) here
     */
    @Nullable RouteEntry answer(@NonNull HttpServerExchange exchange, @NonNull RouteTable routes,
                                @NonNull String hostname, @NonNull String path) {
        UnknownHostAnswer answer = UnknownHostAnswer.fromKeyOrDefault(
            Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS));
        return switch (answer) {
            case NOT_FOUND -> notFound(exchange, hostname);
            case FORWARD -> orNotFound(forwardRoute(targetOf(answer)), exchange, hostname);
            case REDIRECT -> redirect(exchange, hostname, httpUrl(targetOf(answer)));
            case SITE -> orNotFound(routes.siteRoute(targetOf(answer), RouteResolver.extractListenerAddress(exchange),
                path), exchange, hostname);
            case DROP -> {
                IoUtils.safeClose(exchange.getConnection());
                yield null;
            }
        };
    }

    private static @Nullable RouteEntry notFound(@NonNull HttpServerExchange exchange, @NonNull String hostname) {
        ErrorPages.send404(exchange, hostname);
        return null;
    }

    private static @Nullable RouteEntry orNotFound(@Nullable RouteEntry route, @NonNull HttpServerExchange exchange,
                                                   @NonNull String hostname) {
        return route != null ? route : notFound(exchange, hostname);
    }

    /** A temporary redirect: the operator may change the answer, so no browser keeps it. */
    private static @Nullable RouteEntry redirect(@NonNull HttpServerExchange exchange, @NonNull String hostname,
                                                 @Nullable URI target) {
        if (target == null) {
            return notFound(exchange, hostname);
        }
        exchange.setStatusCode(StatusCodes.FOUND);
        exchange.getResponseHeaders().put(Headers.LOCATION, target.toString());
        exchange.endExchange();
        return null;
    }

    private static @Nullable String targetOf(@NonNull UnknownHostAnswer answer) {
        var target = answer.target();
        return target == null ? null : Zenit.SETTINGS_VALUES.getValue(target);
    }

    /** @return a plain proxy to the fallback address, or null when it is not a usable http(s) address */
    private @Nullable RouteEntry forwardRoute(@Nullable String address) {
        ForwardRoute cached = this.forwardRoute;
        if (cached != null && Objects.equals(cached.address(), address)) {
            return cached.entry();
        }
        RouteEntry entry = null;
        URI upstream = upstreamOf(address);
        if (upstream != null) {
            UpstreamTarget target = new UpstreamTarget(upstream, false);
            entry = new RouteEntry((exchange, forwarder) -> forwarder.forwardTo(target), "fallback address", null,
                null, List.of(), null, null, null, false);
        } else if (address != null && !address.isBlank()) {
            Blast.log("SiteDispatcher: the fallback address is not an http(s) address, unknown hosts get a 404:",
                address);
        }
        this.forwardRoute = new ForwardRoute(address, entry);
        return entry;
    }

    /** @return the fallback address as an upstream origin, or null when it is not an absolute http(s) address */
    static @Nullable URI upstreamOf(@Nullable String address) {
        URI parsed = httpUrl(address);
        if (parsed == null) {
            return null;
        }
        try {
            String scheme = parsed.getScheme().toLowerCase(Locale.ROOT);
            return new URI(scheme, null, parsed.getHost(), DefaultPorts.effective(scheme, parsed.getPort()), "/",
                null, null);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** @return the value as an absolute http(s) URL with a host, or null */
    private static @Nullable URI httpUrl(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI parsed = new URI(value.trim());
            String scheme = parsed.getScheme() == null ? null : parsed.getScheme().toLowerCase(Locale.ROOT);
            return ("http".equals(scheme) || "https".equals(scheme)) && parsed.getHost() != null ? parsed : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private record ForwardRoute(@Nullable String address, @Nullable RouteEntry entry) {}
}
