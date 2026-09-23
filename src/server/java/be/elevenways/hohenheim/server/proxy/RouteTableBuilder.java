package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.auth.SiteAuthDecision;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.auth.SiteAuthGates;
import be.elevenways.hohenheim.server.preview.PreviewDomains;
import be.elevenways.hohenheim.server.preview.PreviewRequestHandler;
import be.elevenways.hohenheim.server.sitetype.FaultedSiteHandler;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.TlsPassthroughProvider;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.session.SessionStore;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds one route generation from the database: loads the rows, builds every site's handler
 * and gates, compiles the access lists and protected-path guards, and assembles the three
 * hostname tiers plus the pre-TLS snapshot.
 *
 * AIDEV-NOTE: a builder is single-use and OWNS everything it creates until {@link #build}
 * returns the table that owns it instead. A throw anywhere mid-build (a gate factory, the TLS
 * snapshot) destroys every handler and gate created so far before it propagates -- those
 * hold processes, sockets and bridges, and the old in-line reload leaked them on exactly
 * that path.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
final class RouteTableBuilder {

    /**
     * Used when a site's configured auth provider can't be resolved or built: deny everything
     * rather than silently exposing an upstream that was meant to be protected.
     */
    private static final SiteAuthGate FAIL_CLOSED_GATE =
        exchange -> SiteAuthDecision.deny(503, "Authentication unavailable");

    private final SessionStore sessionStore;
    private final TlsPassthroughRoutes tlsPassthroughRoutes;

    private final Map<String, List<RouteEntry>> exact = new HashMap<>();
    private final List<WildcardRoute> wildcard = new ArrayList<>();
    private final List<RegexRoute> regex = new ArrayList<>();
    private final Set<SiteRequestHandler> ownedHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<SiteAuthGate> ownedGates = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<RoutingProblem> problems = new ArrayList<>();

    // RouteClaims.keyOf -> owning site; duplicates are refused loudly with deterministic
    // first-wins (sites load in name order).
    private final Map<String, String> claimedRoutes = new HashMap<>();

    private boolean used;

    RouteTableBuilder(@NonNull SessionStore sessionStore, @NonNull TlsPassthroughRoutes tlsPassthroughRoutes) {
        this.sessionStore = sessionStore;
        this.tlsPassthroughRoutes = tlsPassthroughRoutes;
    }

    /** The rows one generation is built from, each bucketed with ONE query per table. */
    private record Inputs(List<Row> sites, Map<Integer, List<Row>> domainsBySite,
                          Map<Integer, Row> accessLists, Map<Integer, List<Row>> rulesByList,
                          Map<Integer, Row> authProviders, Map<Integer, List<Row>> protectedPathsBySite) {

        static Inputs load() {
            List<Row> sites = Models.get(SiteModel.class).findEnabled();
            Map<Integer, List<Row>> domainsBySite = new HashMap<>();
            for (Row domain : Models.get(SiteDomainModel.class).find().all()) {
                Integer siteId = domain.get(SiteDomainModel.SITE_ID);
                if (siteId != null) {
                    domainsBySite.computeIfAbsent(siteId, ignored -> new ArrayList<>()).add(domain);
                }
            }
            Map<Integer, Row> accessLists = new HashMap<>();
            for (Row accessList : Models.get(AccessListModel.class).find().all()) {
                accessLists.put(accessList.get(AccessListModel.ID), accessList);
            }
            // One query for every rule in the system, bucketed per list: a route load must
            // not grow a query per guarded site.
            Map<Integer, List<Row>> rulesByList = new HashMap<>();
            for (Row rule : Models.get(AccessRuleModel.class).find()
                    .orderBy(AccessRuleModel.SORT, SortOrder.ASC)
                    .orderBy(AccessRuleModel.ID, SortOrder.ASC).all()) {
                Integer listId = rule.get(AccessRuleModel.ACCESS_LIST_ID);
                if (listId != null) {
                    rulesByList.computeIfAbsent(listId, ignored -> new ArrayList<>()).add(rule);
                }
            }
            Map<Integer, Row> authProviders = new HashMap<>();
            for (Row provider : Models.get(SiteAuthProviderModel.class).find().all()) {
                authProviders.put(provider.get(SiteAuthProviderModel.ID), provider);
            }
            // Protected paths bucketed per site, same one-query discipline as the rules.
            Map<Integer, List<Row>> protectedPathsBySite = new HashMap<>();
            for (Row guarded : Models.get(ProtectedPathModel.class).find()
                    .orderBy(ProtectedPathModel.PATH, SortOrder.ASC).all()) {
                Integer guardedSiteId = guarded.get(ProtectedPathModel.SITE_ID);
                if (guardedSiteId != null) {
                    protectedPathsBySite.computeIfAbsent(guardedSiteId, ignored -> new ArrayList<>())
                        .add(guarded);
                }
            }
            return new Inputs(sites, domainsBySite, accessLists, rulesByList, authProviders,
                protectedPathsBySite);
        }
    }

    /**
     * Build the generation.
     *
     * @throws IllegalStateException when the builder was already used
     */
    @NonNull RouteTable build() {
        if (this.used) {
            throw new IllegalStateException("A RouteTableBuilder builds one generation");
        }
        this.used = true;
        try {
            return buildUnguarded();
        } catch (RuntimeException | Error failure) {
            destroy(this.ownedHandlers, this.ownedGates);
            throw failure;
        }
    }

    private RouteTable buildUnguarded() {
        Inputs inputs = Inputs.load();
        for (Row site : inputs.sites()) {
            addSite(site, inputs);
        }

        // Longest path first inside each hostname bucket, so selection can take the first match.
        Map<String, List<RouteEntry>> frozenExact = new HashMap<>();
        for (Map.Entry<String, List<RouteEntry>> bucket : this.exact.entrySet()) {
            List<RouteEntry> sorted = new ArrayList<>(bucket.getValue());
            sorted.sort((a, b) -> Integer.compare(b.pathLength(), a.pathLength()));
            frozenExact.put(bucket.getKey(), List.copyOf(sorted));
        }

        // Most-specific glob first (the SAME measure the TLS/SNI table sorts by): selection
        // keeps the FIRST entry on a path-length tie, so an unsorted list -- built in
        // site-name order -- let a broader pattern (*.com) shadow a narrower one
        // (*.example.com) and made renaming a site change production routing. Pattern-text
        // tie-break keeps equal-specificity ordering deterministic.
        this.wildcard.sort(Comparator
            .comparingInt((WildcardRoute route) ->
                WildcardHostname.literalSpecificity(route.entry().hostPattern)).reversed()
            .thenComparing(route -> route.pattern().pattern()));

        TlsPassthroughRoutes.Snapshot tlsSnapshot =
            this.tlsPassthroughRoutes.buildSnapshot(inputs.sites(), inputs.domainsBySite());

        Blast.log("SiteDispatcher: loaded", frozenExact.size(), "exact routes,",
            this.wildcard.size(), "wildcard routes,", this.regex.size(), "regex routes",
            this.problems.isEmpty() ? "" : "(" + this.problems.size() + " routing problems)");
        return new RouteTable(Map.copyOf(frozenExact), List.copyOf(this.wildcard),
            List.copyOf(this.regex), tlsSnapshot, this.ownedHandlers, this.ownedGates, this.problems);
    }

    /** Build one site's handler, gates and guards and add each of its domains to its tier. */
    private void addSite(Row site, Inputs inputs) {
        String siteTypeStr = site.get(SiteModel.UPSTREAM_KIND);
        Integer siteId = site.get(SiteModel.ID);
        String siteName = site.get(SiteModel.NAME);

        UpstreamKindHandler typeHandler = UpstreamKindHandlers.getHandler(siteTypeStr);
        if (typeHandler == null) {
            Blast.log("SiteDispatcher: unknown site type", siteTypeStr, "for site", siteName);
            problem(siteId, siteName, RoutingProblem.Reason.UNKNOWN_KIND, siteTypeStr);
            return;
        }
        if (typeHandler instanceof TlsPassthroughProvider) {
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) site.get(SiteModel.SETTINGS);
        if (settings == null) settings = Map.of();

        List<Row> domains = inputs.domainsBySite().getOrDefault(siteId, List.of());
        boolean hasRoutableDomain = domains.stream().anyMatch(domain -> {
            String hostname = domain.get(SiteDomainModel.HOSTNAME);
            return hostname != null && !hostname.isEmpty();
        });
        if (!hasRoutableDomain) {
            Blast.log("SiteDispatcher: site", siteName, "has no routable domain; skipping");
            return;
        }

        // Load access list if assigned
        Integer accessListId = site.get(SiteModel.ACCESS_LIST_ID);
        Row accessList = accessListId != null ? inputs.accessLists().get(accessListId) : null;

        // The per-site auth gate, shared across all of this site's domains.
        SiteGate siteGate = siteGate(site, siteId, siteName, inputs);
        SiteAuthGate authGate = siteGate.gate();

        // Compile the access list's rule tree ONCE per site: the leaves that need an identity
        // build their own gate from the same provider records, narrowed by the leaf's required
        // permission, and the route table owns them from here on.
        SiteLeafContext leafContext = new SiteLeafContext(siteName, siteId, this.sessionStore,
            inputs.authProviders());
        List<SiteAuthGate> treeGates = new ArrayList<>();
        AccessRuleTree accessTree = null;
        if (accessList != null) {
            accessTree = AccessRuleTree.compile(accessList.get(AccessListModel.SATISFY),
                inputs.rulesByList().getOrDefault(accessListId, List.of()), leafContext);
            own(treeGates, accessTree.gates());
        }
        List<RouteEntry.PathGuard> pathGuards = pathGuards(siteId, siteName, inputs, leafContext,
            treeGates);

        // Isolate per-site handler creation so one misconfigured site is skipped and recorded
        // instead of aborting the whole load.
        //
        // AIDEV-NOTE: the git-provisioned branch is GONE with sites.source (phase-0 design
        // section 3): a checkout no longer lives beside the site, it lives in the workspace
        // volume or the build context of the instance the site exposes. GitProvisioner's
        // site-directory layout dies with the host-user lane.
        SiteRequestHandler requestHandler;
        try {
            requestHandler = typeHandler.createHandler(site, settings);
        } catch (Exception e) {
            Blast.log("SiteDispatcher: failed to create handler for site", siteName, "-", e.getMessage());
            problem(siteId, siteName, RoutingProblem.Reason.HANDLER_FAILED, e.getMessage());
            releaseUnused(null, authGate, treeGates);
            return;
        }
        this.ownedHandlers.add(requestHandler);
        if (requestHandler instanceof FaultedSiteHandler faulted) {
            problem(siteId, siteName, RoutingProblem.Reason.HANDLER_FAULTED, faulted.reason());
        }

        boolean siteRouteAdded = false;
        for (Row domain : domains) {
            siteRouteAdded |= addDomain(domain, siteId, siteName, requestHandler, accessTree,
                pathGuards, settings, authGate, siteGate.providerName());
        }
        if (!siteRouteAdded) {
            releaseUnused(requestHandler, authGate, treeGates);
        }
    }

    /** A site's own gate and the provider name the route shows for it; both null without one. */
    private record SiteGate(@Nullable SiteAuthGate gate, @Nullable String providerName) {
    }

    /**
     * The site-level auth gate: none without a provider, the fail-closed gate when the provider
     * cannot be built.
     */
    private SiteGate siteGate(Row site, Integer siteId, String siteName, Inputs inputs) {
        Integer authProviderId = site.get(SiteModel.AUTH_PROVIDER_ID);
        if (authProviderId == null) {
            return new SiteGate(null, null);
        }
        Row providerRow = inputs.authProviders().get(authProviderId);
        SiteAuthGates.Built built = SiteAuthGates.build(providerRow,
            providerRow != null ? providerRow.get(SiteAuthProviderModel.REQUIRED_PERMISSION) : null,
            this.sessionStore, siteId, authProviderId);
        SiteGate result;
        if (built.gate() == null) {
            // Site wants auth but the provider cannot be built: fail closed, never expose.
            Blast.log("SiteDispatcher: auth provider", authProviderId, "for site", siteName,
                "is unusable -", built.refusal(), built.detail());
            problem(siteId, siteName, RoutingProblem.Reason.AUTH_UNAVAILABLE, built.refusal());
            result = new SiteGate(FAIL_CLOSED_GATE, "(" + built.refusal() + ")");
        } else {
            result = new SiteGate(built.gate(), providerRow.get(SiteAuthProviderModel.NAME));
        }
        this.ownedGates.add(result.gate());
        return result;
    }

    /**
     * Protected-path guards: one compiled tree per DISTINCT list this site guards with, longest
     * prefix first so the tab and the enforcement agree on order. A dangling list id fails
     * CLOSED -- a folder the operator believes guarded must refuse, never silently open.
     */
    private List<RouteEntry.PathGuard> pathGuards(Integer siteId, String siteName, Inputs inputs,
                                                  SiteLeafContext leafContext,
                                                  List<SiteAuthGate> treeGates) {
        List<Row> guardedRows = inputs.protectedPathsBySite().get(siteId);
        if (guardedRows == null) {
            return List.of();
        }
        List<RouteEntry.PathGuard> compiledGuards = new ArrayList<>();
        Map<Integer, AccessRuleTree> guardTreesByList = new HashMap<>();
        for (Row guarded : guardedRows) {
            String guardPath = SiteDispatcher.normalizeRoutePath(guarded.get(ProtectedPathModel.PATH));
            if (guardPath == null) {
                // "/" folds to null (= everything); that policy belongs on the site's own
                // access list, and validation refuses storing it.
                Blast.log("SiteDispatcher: protected path without a usable prefix on site",
                    siteName, "- skipped");
                continue;
            }
            Integer guardListId = guarded.get(ProtectedPathModel.ACCESS_LIST_ID);
            AccessRuleTree guardTree = guardListId == null ? null : guardTreesByList.get(guardListId);
            if (guardTree == null) {
                Row guardList = guardListId != null ? inputs.accessLists().get(guardListId) : null;
                if (guardList == null) {
                    Blast.log("SiteDispatcher: protected path", guardPath, "on site",
                        siteName, "names a missing access list - failing closed");
                    guardTree = AccessRuleTree.denyAll();
                } else {
                    guardTree = AccessRuleTree.compile(guardList.get(AccessListModel.SATISFY),
                        inputs.rulesByList().getOrDefault(guardListId, List.of()), leafContext);
                    own(treeGates, guardTree.gates());
                }
                if (guardListId != null) {
                    guardTreesByList.put(guardListId, guardTree);
                }
            }
            compiledGuards.add(new RouteEntry.PathGuard(guardPath, guardTree));
        }
        compiledGuards.sort(Comparator.comparingInt(
            (RouteEntry.PathGuard guard) -> guard.path().length()).reversed());
        return List.copyOf(compiledGuards);
    }

    /** Add one domain row to its tier; false when it contributed no route. */
    private boolean addDomain(Row domain, Integer siteId, String siteName,
                              SiteRequestHandler requestHandler, @Nullable AccessRuleTree accessTree,
                              List<RouteEntry.PathGuard> pathGuards, Map<String, Object> settings,
                              @Nullable SiteAuthGate authGate, @Nullable String authProviderName) {
        String hostname = domain.get(SiteDomainModel.HOSTNAME);
        String matchType = domain.get(SiteDomainModel.MATCH_TYPE);
        if (hostname == null || hostname.isEmpty()) {
            return false;
        }

        // A preview's GENERATED hostname routes to the preview's own instance, never to the
        // site's production handler; access list and auth gate are inherited (protection
        // follows the site, traffic does not).
        SiteRequestHandler domainHandler = requestHandler;
        if (PreviewDomains.SOURCE.equals(domain.get(SiteDomainModel.GENERATED_BY))
                && domain.get(SiteDomainModel.GENERATED_FOR_ID) != null) {
            PreviewRequestHandler previewHandler =
                new PreviewRequestHandler(siteId, domain.get(SiteDomainModel.GENERATED_FOR_ID));
            this.ownedHandlers.add(previewHandler);
            domainHandler = previewHandler;
        }

        RouteEntry entry = new RouteEntry(domainHandler, siteName, domain, accessTree,
            pathGuards, settings, authGate, authProviderName);

        // HostnamePatterns.effectiveKind is THE tier decision, shared with the write-time
        // overlap scan: a hostname carrying glob characters routes as a wildcard whatever
        // match_type says, and the scan must judge the same tier.
        String kind = HostnamePatterns.effectiveKind(hostname, matchType);

        // AIDEV-NOTE: route identity is RouteClaims.keyOf, THE single spelling shared with the
        // write-time claim registry -- do not re-derive it here. Match type is deliberately NOT
        // part of the key: the tier only decides who WINS a contested route, not whether two
        // rows contest it, so an exact and a wildcard row spelling the same literal hostname
        // are ONE route (a kind-prefixed key once let an unclaimed exact row silently take
        // such a host from the wildcard row that held the claim). Regex matching is
        // case-INSENSITIVE (HostnameRegex), so keyOf folds a regex source to lowercase:
        // "^App\." and "^app\." match the same hosts and are ONE claim, first-wins here and
        // refused by the unique index at write time.
        String claimKey = RouteClaims.keyOf(domain);
        String owner = this.claimedRoutes.putIfAbsent(claimKey, siteName);
        if (owner != null && !owner.equals(siteName)) {
            String route = hostname + " " + (entry.path != null ? entry.path : "(all paths)");
            Blast.log("SiteDispatcher: DUPLICATE route", route, "on site", siteName,
                "-- already claimed by site", owner, "; IGNORING");
            problem(siteId, siteName, RoutingProblem.Reason.DUPLICATE_ROUTE,
                route + " is already claimed by site " + owner);
            if (domainHandler != requestHandler && this.ownedHandlers.remove(domainHandler)) {
                destroy(List.of(domainHandler), List.of());
            }
            return false;
        }

        switch (kind) {
            case SiteDomainModel.MATCH_REGEX -> {
                Pattern pattern = RouteResolver.compileHostnameRegex(hostname);
                if (pattern == null) {
                    return false;
                }
                this.regex.add(new RegexRoute(hostname, pattern,
                    RouteResolver.extractNamedGroups(hostname), entry));
            }
            case SiteDomainModel.MATCH_WILDCARD -> {
                String glob = hostname.toLowerCase(Locale.ROOT);
                this.wildcard.add(new WildcardRoute(WildcardHostname.compile(glob), entry));
            }
            default -> this.exact.computeIfAbsent(hostname.toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                .add(entry);
        }
        return true;
    }

    /** Take ownership of gates a tree built, remembering them as this site's too. */
    private void own(List<SiteAuthGate> treeGates, List<SiteAuthGate> built) {
        treeGates.addAll(built);
        this.ownedGates.addAll(built);
    }

    /** Destroy what a site built when none of its domains produced a route. */
    private void releaseUnused(@Nullable SiteRequestHandler handler, @Nullable SiteAuthGate authGate,
                               List<SiteAuthGate> treeGates) {
        List<SiteRequestHandler> handlers = new ArrayList<>();
        if (handler != null && this.ownedHandlers.remove(handler)) {
            handlers.add(handler);
        }
        List<SiteAuthGate> gates = new ArrayList<>();
        if (authGate != null && this.ownedGates.remove(authGate)) {
            gates.add(authGate);
        }
        for (SiteAuthGate treeGate : treeGates) {
            if (this.ownedGates.remove(treeGate)) {
                gates.add(treeGate);
            }
        }
        destroy(handlers, gates);
    }

    private void problem(@Nullable Integer siteId, @Nullable String siteName,
                         RoutingProblem.Reason reason, @Nullable String detail) {
        this.problems.add(new RoutingProblem(siteId != null ? siteId : -1,
            siteName != null ? siteName : "", reason, detail));
    }

    /** Destroy handlers and gates, each failure logged and never stopping the rest. */
    static void destroy(Collection<SiteRequestHandler> handlers, Collection<SiteAuthGate> gates) {
        for (SiteRequestHandler handler : handlers) {
            try {
                handler.destroy();
            } catch (RuntimeException failure) {
                Blast.log("SiteDispatcher: handler teardown failed -", failure.getMessage());
            }
        }
        for (SiteAuthGate gate : gates) {
            try {
                gate.destroy();
            } catch (RuntimeException failure) {
                Blast.log("SiteDispatcher: auth gate teardown failed -", failure.getMessage());
            }
        }
    }

    /**
     * What an access-rule leaf may ask of the site it guards. Provider leaves build their gate
     * through the SHARED factory, so a leaf and a site-level provider agree on what
     * "unbuildable" means -- and a leaf that cannot build one denies rather than degrading into
     * "no identity required".
     */
    private record SiteLeafContext(String siteName, int siteId, SessionStore sessionStore,
                                   Map<Integer, Row> providers) implements AccessRuleTree.LeafContext {

        @Override
        public String realm() {
            return siteName != null && !siteName.isBlank() ? siteName : "Restricted";
        }

        /**
         * AIDEV-NOTE: a leaf with NO permission of its own inherits the provider record's
         * required permission, exactly as the site-level gate does. It used to pass the blank
         * leaf value through, so the same provider demanded "staff" as the site's gate and
         * nothing at all as a rule leaf. A leaf that names a permission still replaces the
         * record's (SiteAuthContext carries one permission).
         */
        @Override
        public SiteAuthGate gateFor(int providerId, String requiredPermission) {
            Row provider = providers.get(providerId);
            String permission = requiredPermission != null && !requiredPermission.isBlank()
                ? requiredPermission
                : provider != null ? provider.get(SiteAuthProviderModel.REQUIRED_PERMISSION) : null;
            SiteAuthGates.Built built = SiteAuthGates.build(provider, permission, sessionStore,
                siteId, providerId);
            if (built.gate() == null) {
                Blast.log("SiteDispatcher: access rule on site", siteName,
                    "names auth provider", providerId, "which is unusable -",
                    built.refusal(), built.detail());
            }
            return built.gate();
        }
    }
}
