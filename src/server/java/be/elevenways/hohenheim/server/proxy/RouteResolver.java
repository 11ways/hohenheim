package be.elevenways.hohenheim.server.proxy;

import be.elevenways.protoblast.common.Blast;
import io.undertow.server.HttpServerExchange;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Selects the route for a request: exact hostname first, then wildcard, then regex,
 * with per-generation negative and positive caches.
 */
final class RouteResolver {

    // Negative cache for hostnames that don't match any route.
    private static final long NEGATIVE_CACHE_TTL_MS = 5 * 60 * 1000;
    private static final int NEGATIVE_CACHE_MAX = 5000;

    private static final long REGEX_CACHE_TTL_MS = 5 * 60 * 1000;
    // AIDEV-NOTE: the positive regex cache is capped like its negativeCache sibling. Its TTL
    // only evicts on RE-READ, so a spray of distinct Host values that each match a broad
    // operator regex ('.*') would otherwise grow it without bound; at the cap it stops caching
    // and falls back to computeRegexMatches per request (correct, just uncached), exactly the
    // negativeCache stance.
    private static final int REGEX_CACHE_MAX = 5000;

    private static final Pattern NAMED_GROUP_PATTERN =
        Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9_]*)>");

    private RouteResolver() {}

    static RouteResolution resolve(HttpServerExchange exchange, String hostname, RouteTable rt) {
        if (hostname.isEmpty()) return new RouteResolution(null, false);

        String listenerIp = extractListenerAddress(exchange);
        String cacheKey = hostname + "|" + (listenerIp != null ? listenerIp : "");
        String requestPath = exchange.getRelativePath();

        // Check negative cache
        Long cachedAt = rt.negativeCache.get(cacheKey);
        if (cachedAt != null) {
            if (System.currentTimeMillis() - cachedAt < NEGATIVE_CACHE_TTL_MS) {
                return new RouteResolution(null, false);
            }
            rt.negativeCache.remove(cacheKey);
        }

        boolean hostnameKnown = false;

        // 1. Exact match: bucket is pre-sorted longest path first
        List<RouteEntry> bucket = rt.exactRoutes.get(hostname);
        if (bucket != null) {
            for (RouteEntry entry : bucket) {
                if (!entry.acceptsListener(listenerIp)) continue;
                hostnameKnown = true;
                if (entry.matchesPath(requestPath)) {
                    return new RouteResolution(new RouteMatch(entry, null), true);
                }
            }
        }

        // 2. Wildcard match: collect every matching route, pick the longest path
        RouteEntry bestWildcard = null;
        for (WildcardRoute wildcardRoute : rt.wildcardRoutes) {
            RouteEntry entry = wildcardRoute.entry();
            if (!entry.acceptsListener(listenerIp)) continue;
            if (!wildcardRoute.pattern().matcher(hostname).matches()) continue;
            hostnameKnown = true;
            if (!entry.matchesPath(requestPath)) continue;
            if (bestWildcard == null || entry.pathLength() > bestWildcard.pathLength()) {
                bestWildcard = entry;
            }
        }
        if (bestWildcard != null) {
            return new RouteResolution(new RouteMatch(bestWildcard, null), true);
        }

        // 3. Regex match: hostname-level positive cache holds ALL matching routes,
        //    path selection stays per-request.
        List<RouteMatch> regexMatches = null;
        CachedRegexMatches cached = rt.regexMatchCache.get(cacheKey);
        if (cached != null) {
            if (System.currentTimeMillis() - cached.cachedAt() < REGEX_CACHE_TTL_MS) {
                regexMatches = cached.matches();
            } else {
                rt.regexMatchCache.remove(cacheKey);
            }
        }
        if (regexMatches == null) {
            regexMatches = computeRegexMatches(rt, hostname, listenerIp);
            if (!regexMatches.isEmpty() && rt.regexMatchCache.size() < REGEX_CACHE_MAX) {
                rt.regexMatchCache.put(cacheKey,
                    new CachedRegexMatches(regexMatches, System.currentTimeMillis()));
            }
        }
        if (!regexMatches.isEmpty()) {
            hostnameKnown = true;
            RouteMatch bestRegex = null;
            for (RouteMatch candidate : regexMatches) {
                if (!candidate.entry().matchesPath(requestPath)) continue;
                if (bestRegex == null
                        || candidate.entry().pathLength() > bestRegex.entry().pathLength()) {
                    bestRegex = candidate;
                }
            }
            if (bestRegex != null) {
                return new RouteResolution(bestRegex, true);
            }
        }

        // 4. No match. Only a fully unknown hostname is negative-cached: a known
        //    hostname with a non-matching path must keep re-checking other paths.
        if (!hostnameKnown && rt.negativeCache.size() < NEGATIVE_CACHE_MAX) {
            rt.negativeCache.put(cacheKey, System.currentTimeMillis());
        }
        return new RouteResolution(null, hostnameKnown);
    }

    /** Every regex route matching this hostname + listener, with capture groups extracted. */
    private static List<RouteMatch> computeRegexMatches(RouteTable rt, String hostname,
                                                        String listenerIp) {
        if (rt.regexRoutes.isEmpty() || isSuspiciousRegexHostname(hostname)) {
            return List.of();
        }

        List<RouteMatch> matches = new ArrayList<>();
        int hostnameDots = countChar(hostname, '.');
        for (RegexRoute regexRoute : rt.regexRoutes) {
            if (!regexRoute.entry().acceptsListener(listenerIp)) {
                continue;
            }

            // A hostname with more dots than the pattern accounts for means the
            // regex is being probed with extra subdomain levels - too broad a match.
            if (countChar(regexRoute.hostnamePattern(), '.') + 1 < hostnameDots) {
                continue;
            }

            Matcher matcher = regexRoute.pattern().matcher(hostname);
            if (matcher.matches()) {
                Map<String, String> groups = extractRegexGroups(matcher, regexRoute.namedGroups());
                // Brute-force protection (faithful to the Node original): a dotted
                // "project" capture means the pattern swallowed a subdomain boundary.
                String project = groups != null ? groups.get("project") : null;
                if (project != null && project.indexOf('.') > -1) {
                    return List.of();
                }
                matches.add(new RouteMatch(regexRoute.entry(), groups));
            }
        }
        return List.copyOf(matches);
    }

    /**
     * Hostnames that only ever appear in brute-force probes never match a
     * regex route (faithful to the Node original's guards): git/gitlab
     * subdomains, doubled/garbled www levels, and "notexist" scanners.
     * Label-anchored on purpose: plain substring checks tripped the git guard
     * for names like {@code digit.example.com}.
     */
    static boolean isSuspiciousRegexHostname(String hostname) {
        return hasLabel(hostname, "git")
            || hasLabel(hostname, "gitlab")
            || hostname.contains("www.www.")
            || hostname.contains("notexist")
            || hasLabel(hostname, "www") && !hostname.startsWith("www.")
            || hostname.contains("wwww");
    }

    /** Whether the hostname contains this exact dot-separated label. */
    private static boolean hasLabel(String hostname, String label) {
        return hostname.equals(label)
            || hostname.startsWith(label + ".")
            || hostname.endsWith("." + label)
            || hostname.contains("." + label + ".");
    }

    private static int countChar(String value, char c) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == c) count++;
        }
        return count;
    }

    /** @return null when the hostname is blank or does not compile as a regex */
    static Pattern compileHostnameRegex(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            return null;
        }

        try {
            return HostnameRegex.compile(hostname);
        } catch (Exception e) {
            Blast.log("SiteDispatcher: invalid regex hostname", hostname, "-", e.getMessage());
            return null;
        }
    }

    private static Map<String, String> extractRegexGroups(Matcher matcher, List<String> namedGroups) {
        Map<String, String> groups = new HashMap<>();
        for (int i = 1; i <= matcher.groupCount(); i++) {
            String value = matcher.group(i);
            if (value != null) {
                groups.put(String.valueOf(i), value);
            }
        }

        for (String name : namedGroups) {
            try {
                String value = matcher.group(name);
                if (value != null) {
                    groups.put(name, value);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        return groups.isEmpty() ? Map.of() : Map.copyOf(groups);
    }

    /** The named capture groups declared in a regex hostname pattern source. */
    static List<String> extractNamedGroups(String patternSource) {
        if (patternSource == null || patternSource.isBlank()) {
            return List.of();
        }

        Matcher matcher = NAMED_GROUP_PATTERN.matcher(patternSource);
        List<String> result = new ArrayList<>();
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return List.copyOf(result);
    }

    private static String extractListenerAddress(HttpServerExchange exchange) {
        InetSocketAddress address = exchange.getDestinationAddress();
        if (address == null) {
            address = exchange.getConnection().getLocalAddress(InetSocketAddress.class);
        }
        return address != null && address.getAddress() != null
            ? address.getAddress().getHostAddress() : null;
    }
}
