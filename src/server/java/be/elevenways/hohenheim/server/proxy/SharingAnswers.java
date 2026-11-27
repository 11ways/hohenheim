package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.server.upstream.kinds.SiteSharing;
import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.zenit.common.routing.CorsHeaders;
import be.elevenways.zenit.common.routing.CorsPolicy;
import be.elevenways.zenit.server.http.CorsGrants;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What one route tells browsers about its answers, read once from its site's settings ({@link SiteSharing}): which
 * other websites may read them, as zenit's {@link CorsPolicy} decides for its own endpoints, and how long a file may
 * be kept, by the longest declared path covering the request.
 *
 * AIDEV-NOTE: only a successful answer (2xx, 304) is stamped, and a header already on it is left alone: an app's
 * own Access-Control-Allow-Origin or Cache-Control wins, and the address's hand-written response headers are
 * applied after this (ResponseMutations), so they win too. The readers' policy never allows credentials and allows
 * GET and HEAD, the reads a file-serving site answers; its advance question (an OPTIONS preflight) is answered here,
 * before any gate, because a browser sends it without credentials.
 *
 * @param readers the websites that may read the answers, null for none
 * @param keep    the declared paths, the longest first, each with its choice
 * @author Jelle De Loecker
 * @since  0.1.0
 */
record SharingAnswers(@Nullable CorsPolicy readers, @NonNull List<KeepRule> keep) {

    /** A route that tells browsers nothing. */
    static final SharingAnswers NONE = new SharingAnswers(null, List.of());

    private static final HttpString VARY = Headers.VARY;

    /** One declared path and how long a browser may keep what it covers. */
    record KeepRule(@NonNull String path, SiteSharing.@NonNull Keep keep) {

        /** @return whether the rule covers a request path: itself, or below it at a segment boundary */
        boolean covers(@NonNull String requestPath) {
            String bare = this.path.length() > 1 && this.path.endsWith("/")
                ? this.path.substring(0, this.path.length() - 1) : this.path;
            return "/".equals(bare) || RouteEntry.pathCovers(bare, requestPath);
        }
    }

    /** @return what a site's settings tell browsers; {@link #NONE} for settings declaring nothing */
    static @NonNull SharingAnswers of(@Nullable Map<String, Object> settings) {
        if (settings == null) {
            return NONE;
        }
        CorsPolicy readers = switch (SiteSharing.Readers.of(settings.get(SiteSharing.READABLE_BY))) {
            case NOBODY -> null;
            case ANY -> CorsPolicy.publicApi().methods(HttpMethod.GET, HttpMethod.HEAD);
            case LISTED -> listed(settings.get(SiteSharing.READING_WEBSITES));
        };
        List<KeepRule> keep = new ArrayList<>();
        if (settings.get(SiteSharing.BROWSER_KEEP) instanceof Map<?, ?> rules) {
            for (Map.Entry<?, ?> rule : rules.entrySet()) {
                // A choice this build does not know keeps its path decided by the browser, never a shorter path's.
                SiteSharing.Keep choice = Objects.requireNonNullElse(SiteSharing.Keep.of(rule.getValue()),
                    SiteSharing.Keep.DECIDE);
                String path = rule.getKey() == null ? "" : String.valueOf(rule.getKey()).strip();
                if (path.startsWith("/")) {
                    keep.add(new KeepRule(path, choice));
                }
            }
        }
        keep.sort(Comparator.comparingInt((KeepRule rule) -> rule.path().length()).reversed());
        return readers == null && keep.isEmpty() ? NONE : new SharingAnswers(readers, List.copyOf(keep));
    }

    private static @Nullable CorsPolicy listed(@Nullable Object websites) {
        List<String> origins = new ArrayList<>();
        if (websites instanceof List<?> listed) {
            for (Object each : listed) {
                String website = SiteSharing.websiteOf(each == null ? null : String.valueOf(each));
                if (website != null && !origins.contains(website)) {
                    origins.add(website);
                }
            }
        }
        return origins.isEmpty() ? null
            : CorsPolicy.of(origins.toArray(String[]::new)).methods(HttpMethod.GET, HttpMethod.HEAD);
    }

    /**
     * Answers a browser's advance question for a read another website may make: 204 with the asking website, GET and
     * HEAD and the headers it asks to send.
     *
     * @return whether the exchange was answered; false leaves the request to the site as any other
     */
    boolean answerPreflight(@NonNull HttpServerExchange exchange) {
        CorsPolicy policy = this.readers;
        HeaderMap asked = exchange.getRequestHeaders();
        String origin = asked.getFirst(CorsHeaders.ORIGIN);
        String method = asked.getFirst(CorsHeaders.REQUEST_METHOD);
        if (policy == null || !Methods.OPTIONS.equals(exchange.getRequestMethod()) || origin == null
                || origin.isBlank() || method == null
                || !policy.allowsMethod(HttpMethod.received(method.strip()))) {
            return false;
        }
        String allowed = CorsGrants.allowedOrigin(policy, origin);
        if (allowed == null) {
            return false;
        }
        HeaderMap headers = exchange.getResponseHeaders();
        headers.put(new HttpString(CorsHeaders.ALLOW_ORIGIN), allowed);
        headers.put(new HttpString(CorsHeaders.ALLOW_METHODS), CorsGrants.allowedMethods(policy));
        String wanted = asked.getFirst(CorsHeaders.REQUEST_HEADERS);
        if (wanted != null && !wanted.isBlank()) {
            headers.put(new HttpString(CorsHeaders.ALLOW_HEADERS), wanted.strip());
        }
        headers.put(new HttpString(CorsHeaders.MAX_AGE), String.valueOf(policy.maxAge().toSeconds()));
        if (!policy.allowsAnyOrigin()) {
            vary(headers);
        }
        exchange.setStatusCode(204);
        exchange.endExchange();
        return true;
    }

    /**
     * Stamps a successful answer as it commits: the reading website and, for a list, Vary; how long it may be kept.
     *
     * @param publicPath the path the visitor asked, before a route's strip_path
     */
    void stamp(@NonNull HttpServerExchange exchange, @NonNull String publicPath) {
        int status = exchange.getStatusCode();
        if (!(status >= 200 && status < 300) && status != 304) {
            return;
        }
        HeaderMap headers = exchange.getResponseHeaders();
        CorsPolicy policy = this.readers;
        HttpString allowOrigin = new HttpString(CorsHeaders.ALLOW_ORIGIN);
        if (policy != null && !headers.contains(allowOrigin)) {
            if (!policy.allowsAnyOrigin()) {
                // The answer differs per asking website, so a shared cache must keep one per website.
                vary(headers);
            }
            String origin = exchange.getRequestHeaders().getFirst(CorsHeaders.ORIGIN);
            String allowed = origin == null || origin.isBlank() ? null : CorsGrants.allowedOrigin(policy, origin);
            if (allowed != null) {
                headers.put(allowOrigin, allowed);
            }
        }
        if (headers.contains(Headers.CACHE_CONTROL)) {
            return;
        }
        for (KeepRule rule : this.keep) {
            if (rule.covers(publicPath)) {
                String directive = rule.keep().directive();
                if (directive != null) {
                    headers.put(Headers.CACHE_CONTROL, directive);
                }
                return;
            }
        }
    }

    private static void vary(@NonNull HeaderMap headers) {
        HeaderValues present = headers.get(VARY);
        if (present != null) {
            for (String value : present) {
                for (String part : value.split(",")) {
                    String named = part.strip();
                    if (named.equals("*") || named.equalsIgnoreCase(CorsHeaders.ORIGIN)) {
                        return;
                    }
                }
            }
        }
        headers.add(VARY, CorsHeaders.ORIGIN);
    }
}
