package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;

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
    final ConcurrentHashMap<String, CachedRegexMatches> regexMatchCache = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Long> negativeCache = new ConcurrentHashMap<>();
    final AtomicInteger users = new AtomicInteger();
    final AtomicBoolean destructionStarted = new AtomicBoolean();
    volatile boolean retired;

    RouteTable(Map<String, List<RouteEntry>> exact, List<WildcardRoute> wildcard,
               List<RegexRoute> regex, TlsPassthroughRoutes.Snapshot tlsRoutes,
               Set<SiteRequestHandler> ownedHandlers, Set<SiteAuthGate> ownedGates) {
        this.exactRoutes = exact;
        this.wildcardRoutes = wildcard;
        this.regexRoutes = regex;
        this.tlsRoutes = tlsRoutes;
        this.ownedHandlers = ownedHandlers;
        this.ownedGates = ownedGates;
    }
}
