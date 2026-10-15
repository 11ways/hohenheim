package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Immutable snapshot of all route tables. Swapped atomically via volatile reference
 * so concurrent requests never see a partially-loaded state. A hostname maps to a LIST
 * of entries (one per configured path prefix); selection picks the longest matching path.
 */
final class RouteTable {
    final Map<String, List<RouteEntry>> exactRoutes;
    final List<WildcardRoute> wildcardRoutes;
    final List<RegexRoute> regexRoutes;
    final TlsPassthroughRoutes.Snapshot tlsRoutes;
    final Set<SiteRequestHandler> ownedHandlers;
    final Set<SiteAuthGate> ownedGates;
    final List<RoutingProblem> problems;
    /** Each site's own exact-name routes by its slug, longest path first; previews are never among them. */
    final Map<String, List<RouteEntry>> exactBySite;
    final ConcurrentHashMap<String, CachedRegexMatches> regexMatchCache = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Long> negativeCache = new ConcurrentHashMap<>();
    final AtomicInteger users = new AtomicInteger();
    final AtomicBoolean destructionStarted = new AtomicBoolean();
    volatile boolean retired;

    RouteTable(Map<String, List<RouteEntry>> exact, List<WildcardRoute> wildcard,
               List<RegexRoute> regex, TlsPassthroughRoutes.Snapshot tlsRoutes,
               Set<SiteRequestHandler> ownedHandlers, Set<SiteAuthGate> ownedGates,
               List<RoutingProblem> problems, Map<String, List<RouteEntry>> exactBySite) {
        this.exactRoutes = exact;
        this.wildcardRoutes = wildcard;
        this.regexRoutes = regex;
        this.tlsRoutes = tlsRoutes;
        this.ownedHandlers = ownedHandlers;
        this.ownedGates = ownedGates;
        this.problems = List.copyOf(problems);
        this.exactBySite = exactBySite;
    }

    /** A generation that routes nothing, for boot and shutdown. */
    static RouteTable empty() {
        return new RouteTable(Map.of(), List.of(), List.of(), TlsPassthroughRoutes.emptySnapshot(),
            Set.of(), Set.of(), List.of(), Map.of());
    }

    /**
     * The route the site {@code slug} answers this path with on this listener, through one of its own exact names.
     *
     * @return null when the site serves no exact name here (unknown, disabled, only patterns, or not this listener)
     */
    @Nullable RouteEntry siteRoute(@Nullable String slug, @Nullable String listenerIp, String path) {
        List<RouteEntry> own = slug == null ? null : this.exactBySite.get(slug);
        if (own == null) {
            return null;
        }
        for (RouteEntry entry : own) {
            if (entry.acceptsListener(listenerIp) && entry.matchesPath(path)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Every HTTP route entry across the three tiers, exact first, then wildcard, then regex.
     *
     * AIDEV-NOTE: THE walk over the tiers. Every whole-table question (force-SSL sites, the
     * handler of a site, the route count) iterates this instead of re-spelling three loops.
     */
    List<RouteEntry> entries() {
        List<RouteEntry> entries = new ArrayList<>();
        for (List<RouteEntry> bucket : this.exactRoutes.values()) {
            entries.addAll(bucket);
        }
        for (WildcardRoute route : this.wildcardRoutes) {
            entries.add(route.entry());
        }
        for (RegexRoute route : this.regexRoutes) {
            entries.add(route.entry());
        }
        return entries;
    }
}
