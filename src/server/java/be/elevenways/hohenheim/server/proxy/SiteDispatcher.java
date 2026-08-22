package be.elevenways.hohenheim.server.proxy;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.auth.SiteAuthDecision;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.net.Hostnames;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.security.BanService;
import be.elevenways.hohenheim.server.security.HohenheimSecurity;
import be.elevenways.hohenheim.server.security.IpLiterals;
import be.elevenways.hohenheim.server.security.ReputationBanPolicy;
import be.elevenways.hohenheim.server.security.ThreatScorer;
import be.elevenways.zenit.common.security.SecurityEventTypes;
import be.elevenways.zenit.server.security.SecurityEvents;
import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.auth.SiteAuthGates;
import be.elevenways.hohenheim.server.source.GitWebhookHandler;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers;
import be.elevenways.hohenheim.server.sitetype.TlsPassthroughProvider;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.session.SessionStore;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.util.Headers;
import io.undertow.util.AttachmentKey;
import io.undertow.util.HttpString;
import io.undertow.util.StatusCodes;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Routes incoming proxy requests to site-type handlers based on hostname matching.
 * Handles loop detection, request header injection, HSTS, force-SSL, custom headers,
 * path-based routing, default site fallback, and IP reputation tracking.
 *
 * <p>The pipeline's stages live in package collaborators: {@link RouteResolver} selects the
 * route, {@link AccessListGate} enforces the access list, {@link ForwardingHeaders} rewrites
 * the upstream request, {@link ResponseMutations} rewrites the response and
 * {@link UpstreamProxyClient} dials the backend.
 */
public class SiteDispatcher implements HttpHandler {

    private static final HttpString STRICT_TRANSPORT_SECURITY = new HttpString("Strict-Transport-Security");

    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    /**
     * Canonical route path: leading slash enforced, trailing slash stripped,
     * root/empty collapsed to null (= catch-all).
     *
     * <p>AIDEV-NOTE: THE single definition of route-path identity. Domain uniqueness in
     * SiteDomainResource must compare with this exact function, or the editor can accept two
     * rows ("api" and "/api") that collapse to one route here and lose one to first-wins.
     */
    public static @Nullable String normalizeRoutePath(@Nullable String raw) {
        if (raw == null) return null;
        // Iterated to a TRUE fixpoint: single-pass ordering leaves residue for inputs
        // like "/a /" (trailing-slash strip exposes a trailing space the earlier trim
        // already ran past), and a non-fixpoint canonical form is exactly the identity
        // drift the editor/dispatcher agreement cannot afford.
        String path = raw;
        while (true) {
            String before = path;
            path = path.trim();
            if (path.isEmpty()) return null;
            if (!path.startsWith("/")) path = "/" + path;
            while (path.contains("//")) {
                path = path.replace("//", "/");
            }
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.equals("/")) return null;
            if (path.equals(before)) return path;
        }
    }

    private final TlsPassthroughRoutes tlsPassthroughRoutes = new TlsPassthroughRoutes();
    private final Object generationLock = new Object();
    private volatile RouteTable routes = new RouteTable(
        Map.of(), List.of(), List.of(), TlsPassthroughRoutes.emptySnapshot(), Set.of(), Set.of());

    private static final HttpString HOST = Headers.HOST;

    // Threat scoring (hostname-scanning ban tracking; shared with the local
    // SecurityEvents sink so this instance's own events count too) and access logging.
    private final ThreatScorer threatScorer = HohenheimSecurity.scorer();
    private final AccessLog accessLog = new AccessLog();

    private static final String ACME_CHALLENGE_PREFIX = "/.well-known/acme-challenge/";

    // Used when a site's configured auth provider can't be resolved/built: deny everything rather
    // than silently exposing an upstream that was meant to be protected.
    private static final SiteAuthGate FAIL_CLOSED_GATE =
        exchange -> SiteAuthDecision.deny(503, "Authentication unavailable");

    // Proxy handlers per request-timeout value (ProxyHandler fixes maxRequestTime at build
    // time). Sites share one client; a handful of distinct timeouts at most.
    private final UpstreamProxyClient proxyClient;
    private final ConcurrentHashMap<Integer, ProxyHandler> proxyHandlers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService delayScheduler;
    private final ExecutorService retirementExecutor;

    // ACME service for Let's Encrypt challenge responses (nullable)
    private final AcmeService acmeService;

    // Proxy-auth session store (owned by ProxyServer), threaded into per-site auth gates.
    private final SessionStore proxySessionStore;

    private volatile boolean httpsAvailable;

    public SiteDispatcher(AcmeService acmeService, SessionStore proxySessionStore) {
        this.acmeService = acmeService;
        this.proxySessionStore = proxySessionStore;
        this.delayScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "site-dispatch-delay");
            thread.setDaemon(true);
            return thread;
        });
        this.retirementExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "site-dispatch-retirement");
            thread.setDaemon(true);
            return thread;
        });
        this.proxyClient = new UpstreamProxyClient();
    }

    /**
     * @param maxRequestTimeMs absolute exchange lifetime; -1 = unlimited (streaming sites)
     */
    private ProxyHandler proxyHandlerFor(int maxRequestTimeMs) {
        // reuseXForwarded: ProxyHandler appends the connected peer to an existing XFF chain
        // instead of overwriting it -- the dispatcher seeds the chain for trusted remote proxies.
        return proxyHandlers.computeIfAbsent(maxRequestTimeMs, timeout -> ProxyHandler.builder()
            .setProxyClient(proxyClient)
            .setMaxRequestTime(timeout)
            .setNext(ResponseCodeHandler.HANDLE_404)
            .setRewriteHostHeader(false)
            .setReuseXForwarded(true)
            .build());
    }

    /**
     * Reload all routes from the database, creating handlers via the site type system.
     */
    public synchronized void reloadRoutes() {
        var siteModel = Models.get(SiteModel.class);
        var domainModel = Models.get(SiteDomainModel.class);
        var accessListModel = Models.get(AccessListModel.class);
        var authProviderModel = Models.get(SiteAuthProviderModel.class);

        // Build new route maps first, then swap atomically
        Map<String, List<RouteEntry>> newExact = new HashMap<>();
        List<WildcardRoute> newWildcard = new ArrayList<>();
        List<RegexRoute> newRegex = new ArrayList<>();
        Set<SiteRequestHandler> ownedHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<SiteAuthGate> ownedGates = Collections.newSetFromMap(new IdentityHashMap<>());

        // RouteClaims.keyOf -> owning site; duplicates are refused loudly with
        // deterministic first-wins (sites load in name order).
        Map<String, String> claimedRoutes = new HashMap<>();

        List<Row> sites = siteModel.findEnabled();
        Map<Integer, List<Row>> domainsBySite = new HashMap<>();
        for (Row domain : domainModel.find().all()) {
            Integer siteId = domain.get(SiteDomainModel.SITE_ID);
            if (siteId != null) {
                domainsBySite.computeIfAbsent(siteId, ignored -> new ArrayList<>()).add(domain);
            }
        }
        Map<Integer, Row> accessLists = new HashMap<>();
        for (Row accessList : accessListModel.find().all()) {
            accessLists.put(accessList.get(AccessListModel.ID), accessList);
        }
        // One query for every rule in the system, bucketed per list: a route load must not
        // grow a query per guarded site.
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
        for (Row provider : authProviderModel.find().all()) {
            authProviders.put(provider.get(SiteAuthProviderModel.ID), provider);
        }

        for (Row site : sites) {
            String siteTypeStr = site.get(SiteModel.UPSTREAM_KIND);
            Integer siteId = site.get(SiteModel.ID);
            String siteName = site.get(SiteModel.NAME);

            UpstreamKindHandler typeHandler = UpstreamKindHandlers.getHandler(siteTypeStr);
            if (typeHandler == null) {
                Blast.log("SiteDispatcher: unknown site type", siteTypeStr, "for site", siteName);
                continue;
            }
            if (typeHandler instanceof TlsPassthroughProvider) {
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> settings = (Map<String, Object>) site.get(SiteModel.SETTINGS);
            if (settings == null) settings = Map.of();

            List<Row> domains = domainsBySite.getOrDefault(siteId, List.of());
            boolean hasRoutableDomain = domains.stream().anyMatch(domain -> {
                String hostname = domain.get(SiteDomainModel.HOSTNAME);
                return hostname != null && !hostname.isEmpty();
            });
            if (!hasRoutableDomain) {
                Blast.log("SiteDispatcher: site", siteName, "has no routable domain; skipping");
                continue;
            }

            // Load access list if assigned
            Integer accessListId = site.get(SiteModel.ACCESS_LIST_ID);
            Row accessList = accessListId != null ? accessLists.get(accessListId) : null;

            // Build the per-site auth gate (shared across all of this site's domains). Built once
            // here, not per-domain, since auth_provider_id is a site-level concern.
            SiteAuthGate authGate = null;
            String authProviderName = null;
            Integer authProviderId = site.get(SiteModel.AUTH_PROVIDER_ID);
            if (authProviderId != null) {
                Row providerRow = authProviders.get(authProviderId);
                SiteAuthGates.Built built = SiteAuthGates.build(providerRow,
                    providerRow != null ? providerRow.get(SiteAuthProviderModel.REQUIRED_PERMISSION) : null,
                    proxySessionStore, siteId, authProviderId);
                if (built.gate() == null) {
                    // Site wants auth but the provider cannot be built: fail closed, never expose.
                    Blast.log("SiteDispatcher: auth provider", authProviderId, "for site", siteName,
                        "is unusable -", built.refusal(), built.detail());
                    authGate = FAIL_CLOSED_GATE;
                    authProviderName = "(" + built.refusal() + ")";
                } else {
                    authGate = built.gate();
                    authProviderName = providerRow.get(SiteAuthProviderModel.NAME);
                }
            }
            if (authGate != null) ownedGates.add(authGate);

            // Compile the access list's rule tree ONCE per site: the leaves that need an
            // identity build their own gate from the same provider records, narrowed by
            // the leaf's required permission, and the route table owns them from here on.
            AccessRuleTree accessTree = accessList == null ? null : AccessRuleTree.compile(
                accessList.get(AccessListModel.SATISFY),
                rulesByList.getOrDefault(accessListId, List.of()),
                new SiteLeafContext(siteName, siteId, proxySessionStore, authProviders));
            List<SiteAuthGate> treeGates = accessTree != null ? accessTree.gates() : List.of();
            ownedGates.addAll(treeGates);

            // Isolate per-site handler creation so one misconfigured site is skipped with
            // a log line instead of aborting the whole load.
            //
            // AIDEV-NOTE: the git-provisioned branch is GONE with sites.source (phase-0
            // design section 3): a checkout no longer lives beside the site, it lives in
            // the workspace volume or the build context of the instance the site exposes.
            // GitProvisioner's site-directory layout dies with the host-user lane.
            SiteRequestHandler requestHandler;
            try {
                requestHandler = typeHandler.createHandler(site, settings);
            } catch (Exception e) {
                Blast.log("SiteDispatcher: failed to create handler for site", siteName, "-", e.getMessage());
                continue;
            }
            ownedHandlers.add(requestHandler);

            boolean siteRouteAdded = false;
            for (Row domain : domains) {
                String hostname = domain.get(SiteDomainModel.HOSTNAME);
                String matchType = domain.get(SiteDomainModel.MATCH_TYPE);
                if (hostname == null || hostname.isEmpty()) continue;

                // A preview's GENERATED hostname routes to the preview's own instance,
                // never to the site's production handler; access list and auth gate are
                // inherited (protection follows the site, traffic does not).
                SiteRequestHandler domainHandler = requestHandler;
                if (be.elevenways.hohenheim.server.preview.PreviewDomains.SOURCE.equals(
                        domain.get(SiteDomainModel.GENERATED_BY))
                        && domain.get(SiteDomainModel.GENERATED_FOR_ID) != null) {
                    var previewHandler =
                        new be.elevenways.hohenheim.server.preview.PreviewRequestHandler(
                            siteId, domain.get(SiteDomainModel.GENERATED_FOR_ID));
                    ownedHandlers.add(previewHandler);
                    domainHandler = previewHandler;
                }

                RouteEntry entry = new RouteEntry(domainHandler, siteName, domain, accessTree,
                    settings, authGate, authProviderName);

                // HostnamePatterns.effectiveKind is THE tier decision, shared with the
                // write-time overlap scan: a hostname carrying glob characters routes as a
                // wildcard whatever match_type says, and the scan must judge the same tier.
                String kind = HostnamePatterns.effectiveKind(hostname, matchType);

                // AIDEV-NOTE: route identity is RouteClaims.keyOf, THE single spelling
                // shared with the write-time claim registry -- do not re-derive it here.
                // Match type is deliberately NOT part of the key: the tier only decides
                // who WINS a contested route, not whether two rows contest it, so an
                // exact and a wildcard row spelling the same literal hostname are ONE
                // route (a kind-prefixed key once let an unclaimed exact row silently
                // take such a host from the wildcard row that held the claim). Regex
                // matching is case-INSENSITIVE (HostnameRegex), so keyOf folds a regex
                // source to lowercase: "^App\." and "^app\." match the same hosts and
                // are ONE claim, first-wins here and refused by the unique index at
                // write time.
                String claimKey = RouteClaims.keyOf(domain);
                String owner = claimedRoutes.putIfAbsent(claimKey, siteName);
                if (owner != null && !owner.equals(siteName)) {
                    Blast.log("SiteDispatcher: DUPLICATE route", hostname,
                        (entry.path != null ? entry.path : "(all paths)"),
                        "on site", siteName, "-- already claimed by site", owner, "; IGNORING");
                    continue;
                }

                switch (kind) {
                    case SiteDomainModel.MATCH_REGEX -> {
                        Pattern pattern = RouteResolver.compileHostnameRegex(hostname);
                        if (pattern != null) {
                            newRegex.add(new RegexRoute(hostname, pattern,
                                RouteResolver.extractNamedGroups(hostname), entry));
                            siteRouteAdded = true;
                        }
                    }
                    case SiteDomainModel.MATCH_WILDCARD -> {
                        String glob = hostname.toLowerCase(Locale.ROOT);
                        newWildcard.add(new WildcardRoute(WildcardHostname.compile(glob), entry));
                        siteRouteAdded = true;
                    }
                    default -> {
                        newExact.computeIfAbsent(hostname.toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                            .add(entry);
                        siteRouteAdded = true;
                    }
                }
            }
            if (!siteRouteAdded) {
                ownedHandlers.remove(requestHandler);
                try {
                    requestHandler.destroy();
                } catch (RuntimeException failure) {
                    Blast.log("SiteDispatcher: unused handler teardown failed -", failure.getMessage());
                }
                if (authGate != null && ownedGates.remove(authGate)) {
                    try {
                        authGate.destroy();
                    } catch (RuntimeException failure) {
                        Blast.log("SiteDispatcher: unused auth gate teardown failed -", failure.getMessage());
                    }
                }
                for (SiteAuthGate treeGate : treeGates) {
                    if (ownedGates.remove(treeGate)) {
                        try {
                            treeGate.destroy();
                        } catch (RuntimeException failure) {
                            Blast.log("SiteDispatcher: unused access-rule gate teardown failed -",
                                failure.getMessage());
                        }
                    }
                }
            }
        }

        // Longest path first inside each hostname bucket, so selection can take the first match.
        for (List<RouteEntry> bucket : newExact.values()) {
            bucket.sort((a, b) -> Integer.compare(b.pathLength(), a.pathLength()));
        }

        // Most-specific glob first (the SAME measure the TLS/SNI table sorts by): selection
        // keeps the FIRST entry on a path-length tie, so an unsorted list -- built in
        // site-name order -- let a broader pattern (*.com) shadow a narrower one
        // (*.example.com) and made renaming a site change production routing. Pattern-text
        // tie-break keeps equal-specificity ordering deterministic.
        newWildcard.sort(Comparator
            .comparingInt((WildcardRoute route) ->
                WildcardHostname.literalSpecificity(route.entry().hostPattern)).reversed()
            .thenComparing(route -> route.pattern().pattern()));

        Map<String, List<RouteEntry>> frozenExact = new HashMap<>();
        for (Map.Entry<String, List<RouteEntry>> e : newExact.entrySet()) {
            frozenExact.put(e.getKey(), List.copyOf(e.getValue()));
        }
        RouteTable nextHttp = new RouteTable(Map.copyOf(frozenExact), List.copyOf(newWildcard),
            List.copyOf(newRegex), TlsPassthroughRoutes.emptySnapshot(), ownedHandlers, ownedGates);
        TlsPassthroughRoutes.Snapshot tlsSnapshot;
        try {
            tlsSnapshot = tlsPassthroughRoutes.buildSnapshot(sites, domainsBySite);
        } catch (RuntimeException | Error failure) {
            destroyHandlers(nextHttp);
            throw failure;
        }
        RouteTable next = new RouteTable(nextHttp.exactRoutes, nextHttp.wildcardRoutes,
            nextHttp.regexRoutes, tlsSnapshot, ownedHandlers, ownedGates);
        RouteTable previous;
        synchronized (generationLock) {
            previous = this.routes;
            this.routes = next;
            previous.retired = true;
        }
        destroyIfUnused(previous, false);

        Blast.log("SiteDispatcher: loaded", newExact.size(), "exact routes,",
                  newWildcard.size(), "wildcard routes,", newRegex.size(), "regex routes");
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        // Pin the client-facing scheme while the trust-boundary headers are still pristine:
        // this pipeline strips X-Hohenheim-Key and rewrites X-Forwarded-Proto further down.
        ProxyScheme.resolve(exchange);

        // AIDEV-NOTE: SOCKET FRONT MODE FAILS CLOSED, ahead of every other check including
        // ACME. The AF_UNIX peer has no address and the bridge registers no identity, so a
        // request that did not authenticate as the fronting proxy has NO client identity at
        // all -- and silently degrading it to the socket peer means 127.0.0.1, under which
        // bans are inert, denied_ips fail-open, allowed_ips refuses everyone and threat
        // scoring blames loopback. It is not merely degraded either: the bridge's loopback
        // TCP port is reachable by any local account on the host, so an unauthenticated
        // request there is a deliberate identity forgery. Refuse instead of trusting, and
        // read the key set LIVE so clearing proxy.trusted_proxy_keys closes the front
        // rather than quietly widening it.
        if (ProxyScheme.lacksRequiredProxyKey(exchange)) {
            exchange.setStatusCode(StatusCodes.FORBIDDEN);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
            exchange.getResponseSender().send("Forbidden");
            return;
        }

        // AIDEV-NOTE: a proxy-level refusal of an ambiguously framed message (both Content-Length
        // and Transfer-Encoding, RFC 7230 3.3.3) was tried here and REMOVED as redundant: this
        // server's Undertow HttpRequestParser already answers 400 and never invokes this handler
        // for such a request, so the check was unreachable. Proven by a counterfactual test that
        // passed against the un-patched tree; do not re-add without evidence Undertow forwards it.

        // --- ACME HTTP-01 challenge: BEFORE ban enforcement, so certificate
        //     renewal survives a mistaken ban (serving a pending challenge
        //     response is harmless). ---
        String acmePath = exchange.getRelativePath();
        if (acmePath.startsWith(ACME_CHALLENGE_PREFIX) && acmeService != null) {
            String token = acmePath.substring(ACME_CHALLENGE_PREFIX.length());
            String challengeHost = extractHostname(exchange);
            String response = acmeService.getChallengeResponse(token, challengeHost);
            if (response != null) {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                exchange.getResponseSender().send(response);
                return;
            }
        }

        // --- Ban enforcement: reject banned IPs early (plain HTTP; HTTPS is
        //     rejected earlier still, at the TLS handshake, via SniKeyManager). Uses the
        //     effective client IP so bans follow real clients through a trusted remote proxy. ---
        String earlyIp = getClientIp(exchange);
        if (isBanned(earlyIp)) {
            exchange.setStatusCode(403);
            exchange.endExchange();
            return;
        }

        // Not banned: let the reputation policy consider a (throttled, async)
        // spamservice lookup -- an IP abusing hosted apps elsewhere gets a ban
        // row here before it does more damage. Never blocks this request.
        ReputationBanPolicy.INSTANCE.noteRequest(earlyIp);

        // --- Git webhook intercept (before hostname routing) ---
        if (GitWebhookHandler.matches(exchange)) {
            GitWebhookHandler.handle(exchange, earlyIp);
            return;
        }

        // --- Loop detection ---
        String existingProxiedBy = exchange.getRequestHeaders().getFirst(ForwardingHeaders.X_PROXIED_BY);
        if (existingProxiedBy != null && existingProxiedBy.contains(instanceId)) {
            exchange.setStatusCode(508);
            exchange.getResponseSender().send("Loop Detected");
            return;
        }

        String hostname = extractHostname(exchange);

        if (shouldForceHttpsGlobally(exchange)) {
            redirectToHttps(exchange, hostname);
            return;
        }

        RouteTable generation = acquireRoutes();
        AtomicBoolean generationReleased = new AtomicBoolean();
        Runnable releaseGeneration = () -> {
            if (generationReleased.compareAndSet(false, true)) releaseRoutes(generation);
        };
        exchange.addExchangeCompleteListener((completed, next) -> {
            if (completed.isUpgrade()) {
                var connection = completed.getConnection();
                connection.addCloseListener(closed -> releaseGeneration.run());
                if (!connection.isOpen()) releaseGeneration.run();
            } else {
                releaseGeneration.run();
            }
            next.proceed();
        });
        RouteResolution resolution = RouteResolver.resolve(exchange, hostname, generation);
        RouteMatch match = resolution.match();
        RouteEntry entry = match != null ? match.entry() : null;

        if (match != null && match.groups() != null && !match.groups().isEmpty()) {
            exchange.putAttachment(MATCHED_GROUPS, match.groups());
        }
        if (entry != null && entry.hostPattern != null) {
            exchange.putAttachment(MATCHED_HOST_PATTERN, entry.hostPattern);
        }

        // --- Threat scoring: a known hostname with a wrong path is a plain 404,
        //     not a domain-scanning signal. ---
        String clientIp = getClientIp(exchange);
        if (entry != null || resolution.hostnameKnown()) {
            threatScorer.recordHit(clientIp);
        } else {
            int score = threatScorer.recordEvent(clientIp, SecurityEventTypes.DOMAIN_MISS, 1);
            int threshold = HohenheimSettings.VALUES.getValue(
                HohenheimSettings.Security.DOMAIN_MISS_THRESHOLD);
            if (score >= threshold) {
                // Threshold-gated like the old fail2ban log line; the in-process
                // sink (HohenheimSecurity) lands it in security_events. The
                // scorer was already fed above, so the sink skips domain misses.
                String userAgent = exchange.getRequestHeaders().getFirst(Headers.USER_AGENT);
                Map<String, String> detail = new LinkedHashMap<>();
                detail.put("domain", hostname);
                detail.put("path", exchange.getRelativePath());
                if (userAgent != null) {
                    detail.put("ua", userAgent);
                }
                SecurityEvents.report(SecurityEventTypes.DOMAIN_MISS, clientIp, detail);
            }
        }

        // --- Default fallback ---
        if (entry == null) {
            if (resolution.hostnameKnown()) {
                // The hostname IS configured, no path matched: a real 404,
                // never the catch-all fallback redirect.
                ErrorPages.send404(exchange, hostname);
                return;
            }
            String fallback = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.FALLBACK_ADDRESS);
            if (fallback != null && !fallback.isEmpty()) {
                exchange.setStatusCode(302);
                exchange.getResponseHeaders().put(Headers.LOCATION, fallback);
                exchange.endExchange();
            } else {
                ErrorPages.send404(exchange, hostname);
            }
            return;
        }

        // --- Force SSL: redirect while HTTPS termination is up, REFUSE while it is not. ---
        // AIDEV-NOTE: force_ssl is a confidentiality control and fails CLOSED. This used to be
        // gated on httpsAvailable, so the moment the certificate store emptied (last cert
        // deleted, keystore load failure, passthrough-only reload) every force-SSL site
        // silently served cleartext with the checkbox still reading enabled. The ACME HTTP-01
        // path is answered BEFORE this gate, so a Let's Encrypt bootstrap or renewal still
        // completes while the site refuses -- the refusal self-heals. The global force_https
        // setting rides the same gate for MATCHED routes only: an unmatched hostname has no
        // content to protect and keeps its 404/fallback.
        boolean forceSsl = entry.forceSsl || Boolean.TRUE.equals(
            HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.FORCE_HTTPS));
        if (forceSsl && !ProxyScheme.isEffectivelyHttps(exchange)) {
            if (httpsAvailable) {
                redirectToHttps(exchange, hostname);
            } else {
                ErrorPages.sendHttpsRequired(exchange, hostname);
            }
            return;
        }

        // --- Per-site auth provider gate (identity-level; coexists with the IP/basic access list,
        //     which still runs afterwards). The gate may block (Argon2 verify, Proteus HTTP), so
        //     run it -- and the rest of the forwarding pipeline -- on a worker thread, off the I/O
        //     thread. Sites with no provider keep the zero-overhead inline path. ---
        SiteAuthGate gate = entry.authGate;
        if (gate != null) {
            final RouteEntry gatedEntry = entry;
            final String gatedHostname = hostname;
            final String gatedClientIp = clientIp;
            exchange.dispatch(() -> {
                try {
                    SiteAuthDecision decision = gate.evaluate(exchange);
                    if (decision != null) {
                        applySiteAuthDecision(exchange, decision);
                    } else {
                        continueAfterAuth(gatedEntry, exchange, gatedHostname, gatedClientIp);
                    }
                } catch (Exception e) {
                    // A gate must never leave the exchange hanging: fail closed.
                    Blast.log("SiteDispatcher: auth gate error for site", gatedEntry.siteName,
                        "-", e.getMessage());
                    if (!exchange.isResponseStarted()) {
                        applySiteAuthDecision(exchange, SiteAuthDecision.deny(502, "Authentication error"));
                    }
                }
            });
            return;
        }

        continueAfterAuth(entry, exchange, hostname, clientIp);
    }

    /**
     * The request pipeline after the auth gate: access list, path matching, header injection,
     * HSTS, access logging, upstream dispatch. Runs on a worker thread when a gate is present.
     */
    private void continueAfterAuth(RouteEntry entry, HttpServerExchange exchange,
                                   String hostname, String clientIp) {

        ResolvedClientIp.attach(exchange, clientIp);

        // An access list whose tree carries a credential leaf blocks while evaluating it
        // (argon2 verification, an identity provider's HTTP round trip), so it may not run
        // on the I/O thread. Address-only lists keep the zero-overhead inline path.
        if (entry.accessListBlocks() && exchange.isInIoThread()) {
            exchange.dispatch(() -> continueAfterAuth(entry, exchange, hostname, clientIp));
            return;
        }

        // --- Access list enforcement ---
        if (entry.hasAccessList() && !AccessListGate.allows(exchange, entry, clientIp)) {
            return;
        }

        // --- Path matching (selection already guaranteed a match; kept as a safety net) ---
        if (entry.path != null) {
            String requestPath = exchange.getRelativePath();
            if (!entry.matchesPath(requestPath)) {
                exchange.setStatusCode(404);
                exchange.getResponseSender().send("Not Found");
                return;
            }
            if (entry.stripPath) {
                String stripped = requestPath.substring(entry.path.length());
                if (stripped.isEmpty()) stripped = "/";
                exchange.setRelativePath(stripped);
                exchange.setRequestPath(stripped);
                // AIDEV-NOTE: ProxyHandler builds the upstream request from requestURI,
                // not requestPath -- without this line strip_path silently forwards the
                // unstripped path.
                exchange.setRequestURI(stripped);
            }
        }

        // --- Inject proxy headers (custom rules, hop-by-hop hygiene, trust boundary) ---
        ForwardingHeaders.applyRequestHeaders(exchange, entry, instanceId, hostname, clientIp);

        // RFC 6797 7.2: HSTS header MUST NOT be emitted over non-secure transport.
        if (entry.hstsEnabled && ProxyScheme.isEffectivelyHttps(exchange)) {
            String hstsValue = "max-age=31536000";
            if (entry.hstsSubdomains) hstsValue += "; includeSubDomains";
            exchange.getResponseHeaders().put(STRICT_TRANSPORT_SECURITY, hstsValue);
        }

        // --- Access logging ---
        accessLog.logAccess(exchange, hostname, clientIp);

        dispatchToRoute(entry, exchange);
    }

    /** Apply a non-null gate decision: redirect the browser, or deny with a status + body. */
    static void applySiteAuthDecision(HttpServerExchange exchange, SiteAuthDecision decision) {
        if (decision instanceof SiteAuthDecision.Redirect redirect) {
            exchange.setStatusCode(302);
            exchange.getResponseHeaders().put(Headers.LOCATION, redirect.url());
            exchange.endExchange();
        } else if (decision instanceof SiteAuthDecision.Deny deny) {
            exchange.setStatusCode(deny.statusCode());
            exchange.getResponseSender().send(deny.body());
        }
    }

    /** Named + numbered regex-host capture groups from the active route, if any. */
    public static final AttachmentKey<Map<String, String>> MATCHED_GROUPS =
        AttachmentKey.create(Map.class);

    /** The matched domain row's configured hostname pattern (exact, glob or regex source). */
    public static final AttachmentKey<String> MATCHED_HOST_PATTERN =
        AttachmentKey.create(String.class);

    /** Set by site types that want upstream Location redirects rewritten to the public host. */
    public static final AttachmentKey<Boolean> REWRITE_LOCATION =
        AttachmentKey.create(Boolean.class);

    // -----------------------------------------------------------------------
    // Resolution
    // -----------------------------------------------------------------------

    /**
     * The request's routing host: the Host header minus its port, case-folded and minus the
     * FQDN root dot.
     *
     * AIDEV-NOTE: the root-dot fold is the request half of SiteDomainModel.canonicalHostname
     * and may not be dropped from either side. Keeping the dot here made {@code victim.test.}
     * a route table entry of its own that no stored claim intersected, while the TLS
     * handshake for that same request could only ever carry {@code victim.test} in SNI
     * (RFC 6066 forbids the trailing dot) -- so the connection got the victim's certificate
     * and the raider's upstream, with no browser warning. Folding both sides means the two
     * spellings resolve to ONE route, which is also what DNS says they are.
     */
    private String extractHostname(HttpServerExchange exchange) {
        return Hostnames.fromHostHeader(exchange.getRequestHeaders().getFirst(HOST));
    }

    /** Public seam over {@link RouteResolver#isSuspiciousRegexHostname}. */
    public static boolean isSuspiciousRegexHostname(String hostname) {
        return RouteResolver.isSuspiciousRegexHostname(hostname);
    }

    private void dispatchToRoute(RouteEntry entry, HttpServerExchange exchange) {
        exchange.addResponseCommitListener(ex -> ResponseMutations.apply(entry, ex));

        ProxyHandler timedProxyHandler = proxyHandlerFor(entry.requestTimeoutMs);
        Runnable dispatch = () -> entry.handler.handleRequest(exchange, upstream -> {
            exchange.putAttachment(UpstreamProxyClient.UPSTREAM_URI, upstream);
            // Only the proxy path may commit early: ProxyHandler copies the upstream status
            // and headers before it acquires the response channel, so a flush can never
            // publish a half-built response. Handlers that build their OWN response (a
            // redirect's 302 + Location, a static file, an error page) must not be committed
            // out from under them.
            EagerResponseCommit.install(exchange);
            try {
                timedProxyHandler.handleRequest(exchange);
            } catch (Exception e) {
                ErrorPages.send502(exchange, e.getMessage());
            }
        });

        if (entry.requestDelayMs <= 0) {
            dispatch.run();
            return;
        }

        exchange.dispatch();
        delayScheduler.schedule(() -> {
            if (!exchange.isComplete()) {
                exchange.dispatch(dispatch);
            }
        }, entry.requestDelayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Pre-resolution global redirect, kept gated on availability on purpose: for MATCHED
     * routes the entry-level gate above fails closed when HTTPS is down, and an UNMATCHED
     * hostname has no content to protect, so it keeps its 404/fallback instead of a 503.
     */
    private boolean shouldForceHttpsGlobally(HttpServerExchange exchange) {
        return httpsAvailable
            && !ProxyScheme.isEffectivelyHttps(exchange)
            && Boolean.TRUE.equals(HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.FORCE_HTTPS));
    }

    public void setHttpsAvailable(boolean httpsAvailable) {
        this.httpsAvailable = httpsAvailable;
    }

    public boolean isHttpsAvailable() {
        return httpsAvailable;
    }

    /**
     * Distinct site names holding at least one force-SSL route, in name order -- the sites
     * that REFUSE plain HTTP while HTTPS termination is unavailable.
     */
    public List<String> forceSslSiteNames() {
        RouteTable rt = this.routes;
        Set<String> names = new TreeSet<>();
        for (List<RouteEntry> bucket : rt.exactRoutes.values()) {
            for (RouteEntry entry : bucket) {
                if (entry.forceSsl) names.add(entry.siteName);
            }
        }
        for (WildcardRoute route : rt.wildcardRoutes) {
            if (route.entry().forceSsl) names.add(route.entry().siteName);
        }
        for (RegexRoute route : rt.regexRoutes) {
            if (route.entry().forceSsl) names.add(route.entry().siteName);
        }
        return List.copyOf(names);
    }

    /** The proxy-auth session store, shared with every per-site auth gate. */
    SessionStore proxySessionStore() {
        return this.proxySessionStore;
    }

    private void redirectToHttps(HttpServerExchange exchange, String hostname) {
        String hostHeader = exchange.getRequestHeaders().getFirst(HOST);
        String authority = hostname;
        int httpsPort = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTPS_PORT);

        if (hostHeader != null && !hostHeader.isBlank()) {
            authority = hostHeader.replaceFirst(":\\d+$", "");
        }

        if (httpsPort > 0 && httpsPort != 443) {
            authority = authority + ":" + httpsPort;
        }

        String redirectUrl = "https://" + authority + exchange.getRelativePath();
        String query = exchange.getQueryString();
        if (query != null && !query.isEmpty()) {
            redirectUrl += "?" + query;
        }

        exchange.setStatusCode(301);
        exchange.getResponseHeaders().put(Headers.LOCATION, redirectUrl);
        exchange.endExchange();
    }

    private static void destroyHandlers(RouteTable rt) {
        for (SiteRequestHandler handler : rt.ownedHandlers) {
            try {
                handler.destroy();
            } catch (RuntimeException failure) {
                Blast.log("SiteDispatcher: handler teardown failed -", failure.getMessage());
            }
        }
        for (SiteAuthGate gate : rt.ownedGates) {
            try {
                gate.destroy();
            } catch (RuntimeException failure) {
                Blast.log("SiteDispatcher: auth gate teardown failed -", failure.getMessage());
            }
        }
    }

    private RouteTable acquireRoutes() {
        synchronized (generationLock) {
            RouteTable generation = routes;
            generation.users.incrementAndGet();
            return generation;
        }
    }

    private void releaseRoutes(RouteTable generation) {
        if (generation.users.decrementAndGet() == 0 && generation.retired) {
            destroyIfUnused(generation, true);
        }
    }

    private void destroyIfUnused(RouteTable generation, boolean asynchronous) {
        if (generation.users.get() != 0 || !generation.destructionStarted.compareAndSet(false, true)) return;
        if (!asynchronous) {
            destroyHandlers(generation);
            return;
        }
        try {
            retirementExecutor.execute(() -> destroyHandlers(generation));
        } catch (RejectedExecutionException ignored) {
            destroyHandlers(generation);
        }
    }

    // -----------------------------------------------------------------------
    // IP reputation
    // -----------------------------------------------------------------------

    /**
     * The effective client IP: the socket peer, unless a trusted remote proxy (valid
     * X-Hohenheim-Key) forwarded the original client in X-Real-IP.
     */
    private String getClientIp(HttpServerExchange exchange) {
        String sourceIp = exchange.getSourceAddress().getAddress().getHostAddress();
        if (isTrustedRemoteProxy(exchange)) {
            String realIp = exchange.getRequestHeaders().getFirst(ForwardingHeaders.X_REAL_IP);
            byte[] address = IpLiterals.parse(realIp != null ? realIp.trim() : null);
            if (address != null) {
                try {
                    return InetAddress.getByAddress(address).getHostAddress();
                } catch (UnknownHostException ignored) {
                    // IpLiterals only returns the two lengths accepted by InetAddress.
                }
            }
        }
        return sourceIp;
    }

    private boolean isTrustedRemoteProxy(HttpServerExchange exchange) {
        return ProxyScheme.isTrustedRemoteProxy(exchange);
    }

    /**
     * Whether this source IP is currently banned. Ban ROWS are the only
     * enforcement truth (the scorer is purely a trigger that CREATES rows via
     * BanService.autoBan), so every refused IP has an auditable, liftable ban
     * row. Used both at the HTTP stage and, for HTTPS, at the TLS handshake
     * stage (via {@link be.elevenways.hohenheim.server.tls.SniKeyManager}) to
     * drop bad IPs before a certificate is served.
     */
    public boolean isBanned(String ip) {
        return BanService.INSTANCE.isBanned(ip);
    }

    /** Test seam over {@link UpstreamProxyClient}'s trusted upstream SSL context. */
    public static void overrideTrustedUpstreamSslContextForTests(SSLContext context) {
        UpstreamProxyClient.overrideTrustedUpstreamSslContextForTests(context);
    }

    // -----------------------------------------------------------------------
    // Stats
    // -----------------------------------------------------------------------

    public int getExactRouteCount() {
        int count = 0;
        for (List<RouteEntry> bucket : routes.exactRoutes.values()) {
            count += bucket.size();
        }
        return count;
    }

    public int getWildcardRouteCount() {
        return routes.wildcardRoutes.size();
    }

    public int getRegexRouteCount() {
        return routes.regexRoutes.size();
    }

    public TlsPassthroughRoutes.Decision resolveTlsRoute(String serverName, String listenerIp) {
        RouteTable generation = routes;
        return tlsPassthroughRoutes.resolve(generation.tlsRoutes, serverName, listenerIp);
    }

    public boolean hasTlsPassthroughRoutes() {
        RouteTable generation = routes;
        return tlsPassthroughRoutes.hasPassthroughRoutes(generation.tlsRoutes);
    }

    /**
     * Find the active request handler for a given site ID.
     * Returns null if the site is not currently loaded.
     */
    public SiteRequestHandler findHandlerBySiteId(int siteId) {
        RouteTable rt = this.routes;

        for (List<RouteEntry> bucket : rt.exactRoutes.values()) {
            for (RouteEntry entry : bucket) {
                if (entry.handler.getSiteId() == siteId) return entry.handler;
            }
        }
        for (WildcardRoute route : rt.wildcardRoutes) {
            if (route.entry().handler.getSiteId() == siteId) return route.entry().handler;
        }
        for (RegexRoute route : rt.regexRoutes) {
            if (route.entry().handler.getSiteId() == siteId) return route.entry().handler;
        }

        return null;
    }

    /**
     * Full teardown, called from ProxyServer.stop(): destroys every routed handler
     * (reaping managed child processes) before stopping the internal schedulers.
     */
    public void shutdown() {
        RouteTable previous;
        synchronized (generationLock) {
            previous = routes;
            routes = new RouteTable(Map.of(), List.of(), List.of(), TlsPassthroughRoutes.emptySnapshot(),
                Set.of(), Set.of());
            previous.retired = true;
        }
        destroyIfUnused(previous, false);
        retirementExecutor.shutdown();
        try {
            while (!retirementExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                Blast.log("SiteDispatcher: waiting for retired route handlers to stop");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        delayScheduler.shutdownNow();
    }

    /**
     * What an access-rule leaf may ask of the site it guards. Provider leaves build their
     * gate through the SHARED factory, so a leaf and a site-level provider agree on what
     * "unbuildable" means -- and a leaf that cannot build one denies rather than degrading
     * into "no identity required".
     */
    private record SiteLeafContext(String siteName, int siteId, SessionStore sessionStore,
                                   Map<Integer, Row> providers) implements AccessRuleTree.LeafContext {

        @Override
        public String realm() {
            return siteName != null && !siteName.isBlank() ? siteName : "Restricted";
        }

        @Override
        public SiteAuthGate gateFor(int providerId, String requiredPermission) {
            SiteAuthGates.Built built = SiteAuthGates.build(providers.get(providerId),
                requiredPermission, sessionStore, siteId, providerId);
            if (built.gate() == null) {
                Blast.log("SiteDispatcher: access rule on site", siteName,
                    "names auth provider", providerId, "which is unusable -",
                    built.refusal(), built.detail());
            }
            return built.gate();
        }
    }
}
