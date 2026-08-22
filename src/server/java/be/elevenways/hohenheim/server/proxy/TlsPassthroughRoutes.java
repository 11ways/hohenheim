package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers;
import be.elevenways.hohenheim.server.sitetype.TlsPassthroughProvider;
import be.elevenways.hohenheim.server.sitetype.TlsPassthroughTarget;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Atomic pre-TLS routing snapshot spanning terminating and passthrough hostnames. */
public final class TlsPassthroughRoutes {

    public enum Action { TERMINATE, PASSTHROUGH, REJECT }

    public record Decision(Action action, @Nullable TlsPassthroughTarget target) {
        public Decision {
            if (action == Action.PASSTHROUGH && target == null) {
                throw new IllegalArgumentException("A passthrough decision requires a target");
            }
            if (action != Action.PASSTHROUGH && target != null) {
                throw new IllegalArgumentException("Only passthrough decisions may carry a target");
            }
        }

        static Decision terminate() { return new Decision(Action.TERMINATE, null); }
        static Decision reject() { return new Decision(Action.REJECT, null); }
        static Decision passthrough(TlsPassthroughTarget target) {
            return new Decision(Action.PASSTHROUGH, target);
        }
    }

    private record Route(String siteName, List<String> listenOn, Decision decision) {
        boolean accepts(String listenerIp) {
            return ListenerAddressMatcher.matches(listenOn, listenerIp);
        }
    }

    private record RouteGroup(String kind, String hostname, List<Route> declarations) {}
    private record PatternRoute(Pattern pattern, int specificity, List<Route> routes) {}
    static record Snapshot(Map<String, List<Route>> exact, List<PatternRoute> wildcard,
                           List<PatternRoute> regex, int passthroughCount) {}

    static Snapshot emptySnapshot() {
        return new Snapshot(Map.of(), List.of(), List.of(), 0);
    }

    /** Builds the pre-TLS half of one immutable dispatcher route generation. */
    Snapshot buildSnapshot(List<Row> sites, Map<Integer, List<Row>> domainsBySite) {
        Map<String, RouteGroup> groups = new HashMap<>();
        int passthroughCount = 0;

        for (Row site : sites) {
            String siteName = site.get(SiteModel.NAME);
            UpstreamKindHandler handler = UpstreamKindHandlers.getHandler(site.get(SiteModel.UPSTREAM_KIND));
            if (handler == null) continue;

            @SuppressWarnings("unchecked")
            Map<String, Object> settings = (Map<String, Object>) site.get(SiteModel.SETTINGS);
            if (settings == null) settings = Map.of();

            boolean passthrough = handler instanceof TlsPassthroughProvider;
            Decision siteDecision = Decision.terminate();
            if (passthrough) {
                siteDecision = passthroughSiteDecision(site, siteName,
                    (TlsPassthroughProvider) handler, settings);
            }

            for (Row domain : domainsBySite.getOrDefault(site.get(SiteModel.ID), List.of())) {
                String hostname = domain.get(SiteDomainModel.HOSTNAME);
                if (hostname == null || hostname.isBlank()) continue;
                hostname = hostname.trim();
                Decision decision = siteDecision;
                if (passthrough && !passthroughDomainIsValid(domain, siteName, hostname)) {
                    decision = Decision.reject();
                }

                String matchType = domain.get(SiteDomainModel.MATCH_TYPE);
                // HostnamePatterns.effectiveKind is THE tier decision -- the same one the
                // HTTP route table and the write-time overlap scan use. A local re-spelling
                // here is the second-copy-of-the-tier shape that produced the takeover.
                String kind = HostnamePatterns.effectiveKind(hostname, matchType);
                if (!SiteDomainModel.MATCH_REGEX.equals(kind)) {
                    hostname = hostname.toLowerCase(Locale.ROOT);
                }
                List<String> listenOn = ListenerAddressMatcher.parse(
                    domain.get(SiteDomainModel.LISTEN_ON));
                Route route = new Route(siteName, listenOn, decision);
                String key = kind + "|" + hostname;
                String routeHostname = hostname;
                RouteGroup group = groups.computeIfAbsent(key,
                    ignored -> new RouteGroup(kind, routeHostname, new ArrayList<>()));
                group.declarations().add(route);
            }
        }

        Map<String, List<Route>> exact = new HashMap<>();
        List<PatternRoute> wildcard = new ArrayList<>();
        List<PatternRoute> regex = new ArrayList<>();
        for (RouteGroup group : groups.values()) {
            List<Route> routes = resolveConflicts(group);
            switch (group.kind()) {
                case "regex" -> {
                    try {
                        regex.add(new PatternRoute(HostnameRegex.compile(group.hostname()),
                            group.hostname().length(), routes));
                        passthroughCount += countPassthrough(routes);
                    } catch (Exception e) {
                        Blast.log("TLS routing: invalid regex", group.hostname(), "-", e.getMessage());
                    }
                }
                case "wildcard" -> {
                    wildcard.add(new PatternRoute(WildcardHostname.compile(group.hostname()),
                        WildcardHostname.literalSpecificity(group.hostname()), routes));
                    passthroughCount += countPassthrough(routes);
                }
                default -> {
                    exact.put(group.hostname(), routes);
                    passthroughCount += countPassthrough(routes);
                }
            }
        }

        Comparator<PatternRoute> specificity = Comparator
            .comparingInt(PatternRoute::specificity).reversed()
            .thenComparing(route -> route.pattern().pattern());
        wildcard.sort(specificity);
        regex.sort(Comparator.comparing(route -> route.pattern().pattern()));
        Snapshot result = new Snapshot(Map.copyOf(exact), List.copyOf(wildcard),
            List.copyOf(regex), passthroughCount);
        Blast.log("TLS routing: loaded", passthroughCount, "passthrough routes");
        return result;
    }

    Decision resolve(Snapshot snapshot, String serverName, String listenerIp) {
        if (serverName == null || serverName.isBlank()) return Decision.terminate();
        String hostname = serverName.toLowerCase(Locale.ROOT);
        List<Route> exact = snapshot.exact().get(hostname);
        if (exact != null) {
            Decision decision = firstAccepting(exact, listenerIp);
            if (decision != null) return decision;
        }
        Decision wildcard = resolvePatterns(snapshot.wildcard(), hostname, listenerIp);
        if (wildcard != null) return wildcard;
        Decision regex = resolvePatterns(snapshot.regex(), hostname, listenerIp);
        if (regex != null) return regex;
        return Decision.terminate();
    }

    boolean hasPassthroughRoutes(Snapshot snapshot) {
        return snapshot.passthroughCount() > 0;
    }

    private static int countPassthrough(List<Route> routes) {
        int count = 0;
        for (Route route : routes) {
            if (route.decision().action() == Action.PASSTHROUGH) count++;
        }
        return count;
    }

    private static Decision passthroughSiteDecision(Row site, String siteName,
                                                     TlsPassthroughProvider provider,
                                                     Map<String, Object> settings) {
        if (site.get(SiteModel.ACCESS_LIST_ID) != null || site.get(SiteModel.AUTH_PROVIDER_ID) != null) {
            Blast.log("TLS passthrough: site", siteName,
                "has HTTP-only access control configured; rejecting its SNI routes");
            return Decision.reject();
        }
        try {
            return Decision.passthrough(provider.createTlsPassthroughTarget(site, settings));
        } catch (Exception e) {
            Blast.log("TLS passthrough: invalid target for site", siteName, "-", e.getMessage(),
                "; rejecting its SNI routes");
            return Decision.reject();
        }
    }

    private static List<Route> resolveConflicts(RouteGroup group) {
        List<Route> declarations = group.declarations();
        List<Route> result = new ArrayList<>(declarations);
        for (int i = 0; i < declarations.size(); i++) {
            for (int j = i + 1; j < declarations.size(); j++) {
                Route first = declarations.get(i);
                Route second = declarations.get(j);
                List<String> overlap = ListenerAddressMatcher.intersection(
                    first.listenOn(), second.listenOn());
                if (overlap == null || compatible(first.decision(), second.decision())) continue;
                Blast.log("TLS routing: conflicting SNI route", group.hostname(), "on sites",
                    first.siteName(), "and", second.siteName(), "; rejecting the overlapping listeners");
                result.add(new Route(first.siteName() + " / " + second.siteName(),
                    overlap, Decision.reject()));
            }
        }
        result.sort(Comparator
            .comparingInt((Route route) -> ListenerAddressMatcher.specificity(route.listenOn())).reversed()
            .thenComparingInt(route -> route.decision().action() == Action.REJECT ? 0 : 1)
            .thenComparing(Route::siteName));
        return List.copyOf(result);
    }

    private static boolean compatible(Decision first, Decision second) {
        if (first.action() == Action.TERMINATE && second.action() == Action.TERMINATE) return true;
        return first.action() == Action.PASSTHROUGH
            && second.action() == Action.PASSTHROUGH
            && first.target().equals(second.target());
    }

    private static @Nullable Decision firstAccepting(List<Route> routes, String listenerIp) {
        for (Route route : routes) {
            if (route.accepts(listenerIp)) return route.decision();
        }
        return null;
    }

    /** Conflicting overlapping declarations at the same precedence fail closed. */
    private static @Nullable Decision resolvePatterns(List<PatternRoute> candidates,
                                                       String hostname, String listenerIp) {
        Decision resolved = null;
        for (PatternRoute candidate : candidates) {
            if (!candidate.pattern().matcher(hostname).matches()) continue;
            Decision decision = firstAccepting(candidate.routes(), listenerIp);
            if (decision == null) continue;
            if (resolved != null && !compatible(resolved, decision)) {
                return Decision.reject();
            }
            resolved = decision;
        }
        return resolved;
    }

    private static boolean passthroughDomainIsValid(Row domain, String siteName, String hostname) {
        String path = domain.get(SiteDomainModel.PATH);
        boolean invalid = path != null && !path.isBlank() && !"/".equals(path.trim());
        invalid |= Boolean.TRUE.equals(domain.get(SiteDomainModel.STRIP_PATH));
        invalid |= domain.get(SiteDomainModel.CERTIFICATE_ID) != null;
        invalid |= Boolean.TRUE.equals(domain.get(SiteDomainModel.HSTS_ENABLED));
        invalid |= Boolean.TRUE.equals(domain.get(SiteDomainModel.HSTS_SUBDOMAINS));
        invalid |= !isEmpty(domain.get(SiteDomainModel.CUSTOM_HEADERS));
        invalid |= !isEmpty(domain.get(SiteDomainModel.RESPONSE_HEADERS));
        if (invalid) {
            Blast.log("TLS passthrough: domain", hostname, "on site", siteName,
                "uses HTTP/TLS-termination-only options; rejecting its SNI route");
        }
        return !invalid;
    }

    private static boolean isEmpty(@Nullable Map<String, String> values) {
        return values == null || values.isEmpty();
    }

}
