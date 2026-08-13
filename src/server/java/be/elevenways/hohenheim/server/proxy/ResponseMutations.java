package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.server.sitetype.ResponseMutator;
import be.elevenways.hohenheim.server.sitetype.UpstreamTarget;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Rewrites the response just before its headers commit: domain response-header rules,
 * backend-leaking Location redirects, then the handler's own mutator.
 */
final class ResponseMutations {

    private ResponseMutations() {}

    /**
     * Applied just before response headers commit: domain response-header rules, upstream
     * Location rewrite, then the handler's optional {@link ResponseMutator} seam.
     */
    static void apply(RouteEntry entry, HttpServerExchange exchange) {
        HeaderMap headers = exchange.getResponseHeaders();

        for (HeaderRule rule : entry.responseHeaders) {
            HttpString name = new HttpString(rule.name());
            if (rule.value() == null || rule.value().isBlank()) {
                headers.remove(name);
            } else {
                headers.put(name, rule.value());
            }
        }

        UpstreamTarget upstream = exchange.getAttachment(UpstreamProxyClient.UPSTREAM_URI);
        if (upstream != null && Boolean.TRUE.equals(exchange.getAttachment(SiteDispatcher.REWRITE_LOCATION))) {
            String location = headers.getFirst(Headers.LOCATION);
            if (location != null) {
                String rewritten = rewriteLocation(location, upstream.uri(), exchange);
                if (rewritten != null) {
                    headers.put(Headers.LOCATION, rewritten);
                }
            }
        }

        ResponseMutator mutator = entry.handler.mutateResponse(exchange);
        if (mutator != null) {
            mutator.mutate(exchange);
        }
    }

    /**
     * Rewrite an upstream redirect to the public scheme + authority so backend host:port never
     * leaks. Only absolute Locations whose host:port match the upstream are touched.
     *
     * AIDEV-NOTE: The public authority comes from the live request's Host header, NOT the route's
     * configured hostname -- wildcard/regex routes match many hostnames and the pattern itself
     * would produce a broken URL.
     *
     * @return the rewritten Location, or null when no rewrite applies
     */
    private static String rewriteLocation(String location, URI upstream, HttpServerExchange exchange) {
        URI parsed;
        try {
            parsed = new URI(location);
        } catch (URISyntaxException e) {
            return null;
        }

        // Relative redirects never leak the backend.
        if (!parsed.isAbsolute() || parsed.getHost() == null) {
            return null;
        }
        if (!parsed.getHost().equalsIgnoreCase(upstream.getHost())
                || effectivePort(parsed) != effectivePort(upstream)) {
            return null;
        }

        String authority = exchange.getRequestHeaders().getFirst(Headers.HOST);
        if (authority == null || authority.isBlank()) {
            return null;
        }

        StringBuilder rewritten = new StringBuilder(ProxyScheme.effectiveScheme(exchange))
            .append("://").append(authority);
        if (parsed.getRawPath() != null) {
            rewritten.append(parsed.getRawPath());
        }
        if (parsed.getRawQuery() != null) {
            rewritten.append('?').append(parsed.getRawQuery());
        }
        if (parsed.getRawFragment() != null) {
            rewritten.append('#').append(parsed.getRawFragment());
        }
        return rewritten.toString();
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
