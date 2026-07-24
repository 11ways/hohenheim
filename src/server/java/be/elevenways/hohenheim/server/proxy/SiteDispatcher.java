package be.elevenways.hohenheim.server.proxy;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.auth.SiteAuthDecision;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.security.BanService;
import be.elevenways.hohenheim.server.security.HohenheimSecurity;
import be.elevenways.hohenheim.server.security.ReputationBanPolicy;
import be.elevenways.hohenheim.server.security.ThreatScorer;
import be.elevenways.zenit.common.security.SecurityEventTypes;
import be.elevenways.zenit.server.security.SecurityEvents;
import be.elevenways.hohenheim.server.auth.SiteAuthContext;
import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.auth.SiteAuthProviderTypeHandler;
import be.elevenways.hohenheim.server.auth.SiteAuthProviders;
import be.elevenways.hohenheim.server.source.GitProvisioner;
import be.elevenways.hohenheim.server.source.GitWebhookHandler;
import be.elevenways.hohenheim.server.sitetype.ResponseMutator;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.sitetype.UpstreamProtocol;
import be.elevenways.hohenheim.server.sitetype.UpstreamTarget;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.tls.UpstreamTrust;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.auth.server.PasswordHasher;
import be.elevenways.zenit.common.session.SessionStore;
import io.undertow.client.ClientCallback;
import io.undertow.client.ClientConnection;
import io.undertow.client.UndertowClient;
import io.undertow.protocols.ssl.UndertowXnioSsl;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ServerConnection;
import io.undertow.server.handlers.proxy.ProxyCallback;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.server.handlers.proxy.ProxyConnection;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.UndertowOptions;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.AttachmentKey;
import io.undertow.util.HttpString;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.Xnio;
import org.xnio.ssl.XnioSsl;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Routes incoming proxy requests to site-type handlers based on hostname matching.
 * Handles loop detection, request header injection, HSTS, force-SSL, custom headers,
 * path-based routing, default site fallback, and IP reputation tracking.
 */
public class SiteDispatcher implements HttpHandler {

    private static final HttpString X_PROXIED_BY = new HttpString("X-Proxied-By");
    private static final HttpString X_FORWARDED_FOR = new HttpString("X-Forwarded-For");
    private static final HttpString X_FORWARDED_HOST = new HttpString("X-Forwarded-Host");
    private static final HttpString X_FORWARDED_PROTO = new HttpString("X-Forwarded-Proto");
    private static final HttpString X_REAL_IP = new HttpString("X-Real-IP");
    private static final HttpString STRICT_TRANSPORT_SECURITY = new HttpString("Strict-Transport-Security");

    // Trusted-remote-proxy authentication. Deliberately a dispatcher-level constant, distinct
    // from the managed-process control key on ManagedProcessSiteHandler: that one authorizes
    // process-control actions, this one authorizes client-IP propagation.
    private static final HttpString X_HOHENHEIM_KEY = new HttpString("X-Hohenheim-Key");

    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    /**
     * Immutable snapshot of all route tables. Swapped atomically via volatile reference
     * so concurrent requests never see a partially-loaded state. A hostname maps to a LIST
     * of entries (one per configured path prefix); selection picks the longest matching path.
     */
    private static final class RouteTable {
        final Map<String, List<RouteEntry>> exactRoutes;
        final List<WildcardRoute> wildcardRoutes;
        final List<RegexRoute> regexRoutes;

        RouteTable(Map<String, List<RouteEntry>> exact, List<WildcardRoute> wildcard,
                   List<RegexRoute> regex) {
            this.exactRoutes = exact;
            this.wildcardRoutes = wildcard;
            this.regexRoutes = regex;
        }
    }

    private volatile RouteTable routes = new RouteTable(Map.of(), List.of(), List.of());

    // Positive cache for regex matches.
    private final ConcurrentHashMap<String, CachedRegexMatches> regexMatchCache = new ConcurrentHashMap<>();

    // Negative cache for hostnames that don't match any route
    private final ConcurrentHashMap<String, Long> negativeCache = new ConcurrentHashMap<>();
    private static final long NEGATIVE_CACHE_TTL_MS = 5 * 60 * 1000;
    private static final int NEGATIVE_CACHE_MAX = 5000;

    private static final long REGEX_CACHE_TTL_MS = 5 * 60 * 1000;
    private static final HttpString HOST = Headers.HOST;
    private static final Pattern NAMED_GROUP_PATTERN = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9_]*)>");

    // Threat scoring (hostname-scanning ban tracking; shared with the local
    // SecurityEvents sink so this instance's own events count too) and access logging.
    private final ThreatScorer threatScorer = HohenheimSecurity.scorer();
    private final AccessLog accessLog = new AccessLog();

    // Trusted remote-proxy keys, re-parsed from settings at most every 10s (read per request).
    // AIDEV-NOTE: initial readAt must be 0, not Long.MIN_VALUE -- "now - MIN_VALUE" overflows
    // negative and the staleness check would never fire.
    private static final long TRUSTED_KEYS_TTL_MS = 10_000;
    private volatile long trustedKeysReadAt = 0;
    private volatile Set<String> trustedProxyKeys = Set.of();

    private static final String ACME_CHALLENGE_PREFIX = "/.well-known/acme-challenge/";

    // Used when a site's configured auth provider can't be resolved/built: deny everything rather
    // than silently exposing an upstream that was meant to be protected.
    private static final SiteAuthGate FAIL_CLOSED_GATE =
        exchange -> SiteAuthDecision.deny(503, "Authentication unavailable");

    // Proxy handlers per request-timeout value (ProxyHandler fixes maxRequestTime at build
    // time). Sites share one client; a handful of distinct timeouts at most.
    private final DispatchingProxyClient proxyClient;
    private final ConcurrentHashMap<Integer, ProxyHandler> proxyHandlers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService delayScheduler;

    // ACME service for Let's Encrypt challenge responses (nullable)
    private final AcmeService acmeService;

    // Proxy-auth session store (owned by ProxyServer), threaded into per-site auth gates.
    private final SessionStore proxySessionStore;

    private volatile boolean httpsAvailable;

    /**
     * A route entry combines the site handler with domain-level configuration.
     */
    private static final class RouteEntry {
        final SiteRequestHandler handler;
        final String siteName;
        final @Nullable String hostPattern;
        final String path;
        final boolean stripPath;
        final boolean forceSsl;
        final int requestDelayMs;
        final int requestTimeoutMs;
        final boolean hstsEnabled;
        final boolean hstsSubdomains;
        final List<HeaderRule> customHeaders;
        final List<HeaderRule> responseHeaders;
        final List<String> listenOnAddresses;

        // Per-site auth provider gate (identity-level; null when the site has no provider).
        final @Nullable SiteAuthGate authGate;
        final @Nullable String authProviderName;

        // Access list enforcement
        final String[] allowedIps;
        final String[] deniedIps;
        final String basicAuthUser;
        final String basicAuthPass;
        final String accessListSatisfy;

        RouteEntry(SiteRequestHandler handler, String siteName, Row domain, Row accessList,
                   Map<String, Object> siteSettings, @Nullable SiteAuthGate authGate,
                   @Nullable String authProviderName) {
            this.handler = handler;
            this.siteName = siteName;
            this.hostPattern = domain != null ? domain.get(SiteDomainModel.HOSTNAME) : null;
            this.authGate = authGate;
            this.authProviderName = authProviderName;

            this.path = normalizePath(domain != null ? domain.get(SiteDomainModel.PATH) : null);
            this.stripPath = domain != null && Boolean.TRUE.equals(domain.get(SiteDomainModel.STRIP_PATH));
            this.forceSsl = domain != null && Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL));
            this.requestDelayMs = parsePositiveInt(siteSettings != null ? siteSettings.get("delay") : null);
            this.requestTimeoutMs = parseRequestTimeout(
                siteSettings != null ? siteSettings.get("request_timeout") : null);
            this.hstsEnabled = domain != null && Boolean.TRUE.equals(domain.get(SiteDomainModel.HSTS_ENABLED));
            this.hstsSubdomains = domain != null && Boolean.TRUE.equals(domain.get(SiteDomainModel.HSTS_SUBDOMAINS));
            this.customHeaders = parseHeaderRules(domain != null ? domain.get(SiteDomainModel.CUSTOM_HEADERS) : null);
            this.responseHeaders = parseHeaderRules(domain != null ? domain.get(SiteDomainModel.RESPONSE_HEADERS) : null);
            this.listenOnAddresses = parseListenOnAddresses(domain != null ? domain.get(SiteDomainModel.LISTEN_ON) : null);

            if (accessList != null) {
                this.accessListSatisfy = accessList.get(AccessListModel.SATISFY);
                this.basicAuthUser = accessList.get(AccessListModel.BASIC_AUTH_USER);
                this.basicAuthPass = accessList.get(AccessListModel.BASIC_AUTH_PASS);
                String allowed = accessList.get(AccessListModel.ALLOWED_IPS);
                this.allowedIps = allowed != null ? allowed.split("\\s+") : null;
                String denied = accessList.get(AccessListModel.DENIED_IPS);
                this.deniedIps = denied != null ? denied.split("\\s+") : null;
            } else {
                this.accessListSatisfy = null;
                this.basicAuthUser = null;
                this.basicAuthPass = null;
                this.allowedIps = null;
                this.deniedIps = null;
            }
        }

        boolean hasAccessList() {
            return accessListSatisfy != null;
        }

        /**
         * Canonical path prefix: leading slash enforced, trailing slash stripped,
         * root/empty collapsed to null (= catch-all).
         */
        static @Nullable String normalizePath(String raw) {
            if (raw == null) return null;
            String path = raw.trim();
            if (path.isEmpty() || path.equals("/")) return null;
            if (!path.startsWith("/")) path = "/" + path;
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return path;
        }

        /**
         * Prefix match with segment boundaries: /api matches /api and /api/x, never /apix.
         */
        boolean matchesPath(String requestPath) {
            if (path == null) return true;
            return requestPath.equals(path) || requestPath.startsWith(path + "/");
        }

        int pathLength() {
            return path == null ? 0 : path.length();
        }

        boolean acceptsListener(String listenerIp) {
            if (listenOnAddresses.isEmpty() || listenerIp == null || listenerIp.isBlank()) {
                return true;
            }

            String normalized = normalizeListenerIp(listenerIp);
            for (String candidate : listenOnAddresses) {
                if ("any".equals(candidate)) {
                    return true;
                }

                String normalizedCandidate = normalizeListenerIp(candidate);
                if (normalized.equals(normalizedCandidate)) {
                    return true;
                }
            }

            return false;
        }
    }

    private record HeaderRule(String name, String value) {}

    /** A glob hostname route with its pattern compiled once at load time. */
    private record WildcardRoute(Pattern pattern, RouteEntry entry) {}

    private record RegexRoute(String hostnamePattern, Pattern pattern, List<String> namedGroups,
                              RouteEntry entry) {}

    /** All regex routes whose pattern matched this hostname, with their capture groups. */
    private record CachedRegexMatches(List<RouteMatch> matches, long cachedAt) {}

    private record RouteMatch(RouteEntry entry, Map<String, String> groups) {}

    /** hostnameKnown distinguishes wrong-path 404s from true domain misses. */
    private record Resolution(@Nullable RouteMatch match, boolean hostnameKnown) {}

    public SiteDispatcher(AcmeService acmeService, SessionStore proxySessionStore) {
        this.acmeService = acmeService;
        this.proxySessionStore = proxySessionStore;
        this.delayScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "site-dispatch-delay");
            thread.setDaemon(true);
            return thread;
        });
        this.proxyClient = new DispatchingProxyClient();
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
    public void reloadRoutes() {
        var ds = HohenheimDatabase.datasource();
        var siteModel = Models.get(SiteModel.class);
        var domainModel = Models.get(SiteDomainModel.class);
        var accessListModel = Models.get(AccessListModel.class);
        var authProviderModel = Models.get(SiteAuthProviderModel.class);

        // Build new route maps first, then swap atomically
        Map<String, List<RouteEntry>> newExact = new HashMap<>();
        List<WildcardRoute> newWildcard = new ArrayList<>();
        List<RegexRoute> newRegex = new ArrayList<>();

        // (match kind + hostname + path + listeners) -> owning site; duplicates are refused
        // loudly with deterministic first-wins (sites load in name order).
        Map<String, String> claimedRoutes = new HashMap<>();

        List<Row> sites = siteModel.findEnabled();

        for (Row site : sites) {
            String siteTypeStr = site.get(SiteModel.SITE_TYPE);
            Integer siteId = site.get(SiteModel.ID);
            String siteName = site.get(SiteModel.NAME);

            SiteTypeHandler typeHandler = SiteTypes.getHandler(siteTypeStr);
            if (typeHandler == null) {
                Blast.log("SiteDispatcher: unknown site type", siteTypeStr, "for site", siteName);
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> settings = (Map<String, Object>) site.get(SiteModel.SETTINGS);
            if (settings == null) settings = Map.of();

            // Load access list if assigned
            Integer accessListId = site.get(SiteModel.ACCESS_LIST_ID);
            Row accessList = accessListId != null ? accessListModel.findById(accessListId) : null;

            // Build the per-site auth gate (shared across all of this site's domains). Built once
            // here, not per-domain, since auth_provider_id is a site-level concern.
            SiteAuthGate authGate = null;
            String authProviderName = null;
            Integer authProviderId = site.get(SiteModel.AUTH_PROVIDER_ID);
            if (authProviderId != null) {
                Row providerRow = authProviderModel.findById(authProviderId);
                if (providerRow == null) {
                    // Site wants auth but the provider record is gone: fail closed, never expose.
                    Blast.log("SiteDispatcher: auth provider", authProviderId, "missing for site", siteName);
                    authGate = FAIL_CLOSED_GATE;
                    authProviderName = "(missing provider)";
                } else {
                    String providerType = providerRow.get(SiteAuthProviderModel.PROVIDER_TYPE);
                    SiteAuthProviderTypeHandler providerHandler = SiteAuthProviders.getHandler(providerType);
                    if (providerHandler == null) {
                        Blast.log("SiteDispatcher: unknown auth provider type", providerType,
                            "for site", siteName);
                        authGate = FAIL_CLOSED_GATE;
                        authProviderName = "(unknown type)";
                    } else {
                        try {
                            String requiredPermission = providerRow.get(SiteAuthProviderModel.REQUIRED_PERMISSION);
                            authGate = providerHandler.createGate(new SiteAuthContext(
                                providerRow, requiredPermission, proxySessionStore, siteId, providerType));
                            authProviderName = providerRow.get(SiteAuthProviderModel.NAME);
                        } catch (Exception e) {
                            // createGate must be pure; if it throws anyway, fail closed.
                            Blast.log("SiteDispatcher: failed to build auth gate for site", siteName,
                                "-", e.getMessage());
                            authGate = FAIL_CLOSED_GATE;
                            authProviderName = "(misconfigured)";
                        }
                    }
                }
            }

            // A site without domains never enters the route table; creating its
            // handler anyway would spawn processes nothing routes to and nothing
            // ever destroys (destroyHandlers only walks route entries).
            List<Row> domains = domainModel.findBySiteId(siteId);
            boolean hasRoutableDomain = domains.stream().anyMatch(domain -> {
                String hostname = domain.get(SiteDomainModel.HOSTNAME);
                return hostname != null && !hostname.isEmpty();
            });
            if (!hasRoutableDomain) {
                Blast.log("SiteDispatcher: site", siteName, "has no routable domain; skipping");
                continue;
            }

            // Check for git provisioning. Isolate per-site handler creation so one
            // misconfigured site (e.g. a dangling system_user_id, which now fails
            // closed) is skipped with a log line instead of aborting the whole load.
            SiteRequestHandler requestHandler;
            try {
                String source = site.get(SiteModel.SOURCE);
                if ("git".equals(source)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> sourceSettings = (Map<String, Object>) site.get(SiteModel.SOURCE_SETTINGS);
                    if (sourceSettings != null) {
                        requestHandler = GitProvisioner.createHandler(site, typeHandler, settings, sourceSettings, siteId);
                    } else {
                        requestHandler = typeHandler.createHandler(site, settings);
                    }
                } else {
                    requestHandler = typeHandler.createHandler(site, settings);
                }
            } catch (Exception e) {
                Blast.log("SiteDispatcher: failed to create handler for site", siteName, "-", e.getMessage());
                continue;
            }

            for (Row domain : domains) {
                String hostname = domain.get(SiteDomainModel.HOSTNAME);
                String matchType = domain.get(SiteDomainModel.MATCH_TYPE);
                if (hostname == null || hostname.isEmpty()) continue;

                RouteEntry entry = new RouteEntry(requestHandler, siteName, domain, accessList,
                    settings, authGate, authProviderName);

                String kind;
                if ("regex".equals(matchType)) {
                    kind = "regex";
                } else if ("wildcard".equals(matchType) || WildcardHostname.isWildcard(hostname)) {
                    kind = "wildcard";
                } else {
                    kind = "exact";
                }

                String claimKey = kind + "|" + hostname.toLowerCase() + "|"
                    + (entry.path != null ? entry.path : "") + "|" + entry.listenOnAddresses;
                String owner = claimedRoutes.putIfAbsent(claimKey, siteName);
                if (owner != null && !owner.equals(siteName)) {
                    Blast.log("SiteDispatcher: DUPLICATE route", hostname,
                        (entry.path != null ? entry.path : "(all paths)"),
                        "on site", siteName, "-- already claimed by site", owner, "; IGNORING");
                    continue;
                }

                switch (kind) {
                    case "regex" -> {
                        Pattern pattern = compileHostnameRegex(hostname);
                        if (pattern != null) {
                            newRegex.add(new RegexRoute(hostname, pattern,
                                extractNamedGroups(hostname), entry));
                        }
                    }
                    case "wildcard" -> {
                        String glob = hostname.toLowerCase();
                        newWildcard.add(new WildcardRoute(WildcardHostname.compile(glob), entry));
                    }
                    default -> newExact
                        .computeIfAbsent(hostname.toLowerCase(), k -> new ArrayList<>())
                        .add(entry);
                }
            }
        }

        // Longest path first inside each hostname bucket, so selection can take the first match.
        for (List<RouteEntry> bucket : newExact.values()) {
            bucket.sort((a, b) -> Integer.compare(b.pathLength(), a.pathLength()));
        }

        // Destroy old handlers, then swap in the new routes atomically
        destroyHandlers();
        Map<String, List<RouteEntry>> frozenExact = new HashMap<>();
        for (Map.Entry<String, List<RouteEntry>> e : newExact.entrySet()) {
            frozenExact.put(e.getKey(), List.copyOf(e.getValue()));
        }
        this.routes = new RouteTable(Map.copyOf(frozenExact), List.copyOf(newWildcard), List.copyOf(newRegex));
        negativeCache.clear();
        regexMatchCache.clear();

        Blast.log("SiteDispatcher: loaded", newExact.size(), "exact routes,",
                  newWildcard.size(), "wildcard routes,", newRegex.size(), "regex routes");
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

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
            GitWebhookHandler.handle(exchange);
            return;
        }

        // --- Loop detection ---
        String existingProxiedBy = exchange.getRequestHeaders().getFirst(X_PROXIED_BY);
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

        Resolution resolution = resolveEntry(exchange, hostname);
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

        // --- Force SSL redirect ---
        if (httpsAvailable && entry.forceSsl && "http".equals(exchange.getRequestScheme())) {
            redirectToHttps(exchange, hostname);
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

        // --- Access list enforcement ---
        if (entry.hasAccessList() && !checkAccessList(exchange, entry, clientIp)) {
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

        // --- Inject proxy headers ---
        HeaderMap requestHeaders = exchange.getRequestHeaders();
        requestHeaders.put(X_PROXIED_BY, instanceId);

        String sourceIp = exchange.getSourceAddress().getAddress().getHostAddress();

        // AIDEV-NOTE: X-Forwarded-For is owned by Undertow's ProxyHandler (reuseXForwarded=true):
        // it appends the connected peer to an existing chain, or seeds the chain with the peer.
        // Anything written here that ISN'T an existing chain gets overwritten, so the dispatcher
        // only seeds the resolved real client when a trusted remote proxy supplied one.
        if (!clientIp.equals(sourceIp) && !requestHeaders.contains(X_FORWARDED_FOR)) {
            requestHeaders.put(X_FORWARDED_FOR, clientIp);
        }
        if (!requestHeaders.contains(X_FORWARDED_PROTO)) {
            requestHeaders.put(X_FORWARDED_PROTO, exchange.getRequestScheme());
        }

        // AIDEV-NOTE: X-Real-IP is overwritten for untrusted peers (Node parity) -- keeping a
        // client-supplied value would let anyone spoof their IP to the upstream. Only a trusted
        // remote proxy's value survives, and clientIp already IS that value then.
        if (isTrustedRemoteProxy(exchange)) {
            if (!requestHeaders.contains(X_REAL_IP)) {
                requestHeaders.put(X_REAL_IP, clientIp);
            }
        } else {
            requestHeaders.put(X_REAL_IP, sourceIp);
        }

        if (!requestHeaders.contains(X_FORWARDED_HOST)) {
            requestHeaders.put(X_FORWARDED_HOST, hostname);
        }

        // --- Custom upstream request headers ---
        if (!entry.customHeaders.isEmpty()) {
            for (HeaderRule header : entry.customHeaders) {
                HttpString headerName = new HttpString(header.name());
                if (header.value() == null || header.value().isBlank()) {
                    requestHeaders.remove(headerName);
                } else {
                    requestHeaders.put(headerName, header.value());
                }
            }
        }

        // RFC 6797 §7.2: HSTS header MUST NOT be emitted over non-secure transport.
        if (entry.hstsEnabled && "https".equals(exchange.getRequestScheme())) {
            String hstsValue = "max-age=31536000";
            if (entry.hstsSubdomains) hstsValue += "; includeSubDomains";
            exchange.getResponseHeaders().put(STRICT_TRANSPORT_SECURITY, hstsValue);
        }

        // --- Access logging ---
        accessLog.logAccess(exchange, hostname, clientIp);

        dispatchToRoute(entry, exchange);
    }

    /** Apply a non-null gate decision: redirect the browser, or deny with a status + body. */
    private void applySiteAuthDecision(HttpServerExchange exchange, SiteAuthDecision decision) {
        if (decision instanceof SiteAuthDecision.Redirect redirect) {
            exchange.setStatusCode(302);
            exchange.getResponseHeaders().put(Headers.LOCATION, redirect.url());
            exchange.endExchange();
        } else if (decision instanceof SiteAuthDecision.Deny deny) {
            exchange.setStatusCode(deny.statusCode());
            exchange.getResponseSender().send(deny.body());
        }
    }

    private static final AttachmentKey<UpstreamTarget> UPSTREAM_URI =
        AttachmentKey.create(UpstreamTarget.class);

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

    private String extractHostname(HttpServerExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst(HOST);
        if (host == null) return "";
        int colon = host.indexOf(':');
        return (colon != -1 ? host.substring(0, colon) : host).toLowerCase();
    }

    private Resolution resolveEntry(HttpServerExchange exchange, String hostname) {
        if (hostname.isEmpty()) return new Resolution(null, false);

        String listenerIp = extractListenerAddress(exchange);
        String cacheKey = hostname + "|" + (listenerIp != null ? listenerIp : "");
        String requestPath = exchange.getRelativePath();

        // Check negative cache
        Long cachedAt = negativeCache.get(cacheKey);
        if (cachedAt != null) {
            if (System.currentTimeMillis() - cachedAt < NEGATIVE_CACHE_TTL_MS) {
                return new Resolution(null, false);
            }
            negativeCache.remove(cacheKey);
        }

        // Take a consistent snapshot of the route table
        RouteTable rt = this.routes;
        boolean hostnameKnown = false;

        // 1. Exact match: bucket is pre-sorted longest path first
        List<RouteEntry> bucket = rt.exactRoutes.get(hostname);
        if (bucket != null) {
            for (RouteEntry entry : bucket) {
                if (!entry.acceptsListener(listenerIp)) continue;
                hostnameKnown = true;
                if (entry.matchesPath(requestPath)) {
                    return new Resolution(new RouteMatch(entry, null), true);
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
            return new Resolution(new RouteMatch(bestWildcard, null), true);
        }

        // 3. Regex match: hostname-level positive cache holds ALL matching routes,
        //    path selection stays per-request.
        List<RouteMatch> regexMatches = null;
        CachedRegexMatches cached = regexMatchCache.get(cacheKey);
        if (cached != null) {
            if (System.currentTimeMillis() - cached.cachedAt() < REGEX_CACHE_TTL_MS) {
                regexMatches = cached.matches();
            } else {
                regexMatchCache.remove(cacheKey);
            }
        }
        if (regexMatches == null) {
            regexMatches = computeRegexMatches(rt, hostname, listenerIp);
            if (!regexMatches.isEmpty()) {
                regexMatchCache.put(cacheKey,
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
                return new Resolution(bestRegex, true);
            }
        }

        // 4. No match. Only a fully unknown hostname is negative-cached: a known
        //    hostname with a non-matching path must keep re-checking other paths.
        if (!hostnameKnown && negativeCache.size() < NEGATIVE_CACHE_MAX) {
            negativeCache.put(cacheKey, System.currentTimeMillis());
        }
        return new Resolution(null, hostnameKnown);
    }

    /** Every regex route matching this hostname + listener, with capture groups extracted. */
    private List<RouteMatch> computeRegexMatches(RouteTable rt, String hostname, String listenerIp) {
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
            // regex is being probed with extra subdomain levels — too broad a match.
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
     */
    public static boolean isSuspiciousRegexHostname(String hostname) {
        return hostname.contains("git.")
            || hostname.contains("gitlab.")
            || hostname.contains("www.www.")
            || hostname.contains("notexist")
            || hostname.contains(".www")
            || hostname.contains("wwww");
    }

    private static int countChar(String value, char c) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == c) count++;
        }
        return count;
    }

    /**
     * Check the access list. Returns true if the request is allowed, false if blocked.
     * When blocked, the response (401 or 403) is already sent.
     */
    private boolean checkAccessList(HttpServerExchange exchange, RouteEntry entry, String clientIp) {
        boolean ipAllowed = checkIpAccess(entry, clientIp);
        boolean authPassed = checkBasicAuth(exchange, entry);

        boolean hasIpRules = entry.allowedIps != null || entry.deniedIps != null;
        boolean hasAuth = entry.basicAuthUser != null;

        if ("all".equals(entry.accessListSatisfy)) {
            // Both must pass (if configured)
            if (hasIpRules && !ipAllowed) {
                exchange.setStatusCode(403);
                exchange.getResponseSender().send("Forbidden");
                return false;
            }
            if (hasAuth && !authPassed) {
                sendAuthChallenge(exchange, entry);
                return false;
            }
        } else {
            // "any": pass if either passes (or if only one is configured)
            if (hasIpRules && hasAuth) {
                if (!ipAllowed && !authPassed) {
                    sendAuthChallenge(exchange, entry);
                    return false;
                }
            } else if (hasIpRules && !ipAllowed) {
                exchange.setStatusCode(403);
                exchange.getResponseSender().send("Forbidden");
                return false;
            } else if (hasAuth && !authPassed) {
                sendAuthChallenge(exchange, entry);
                return false;
            }
        }

        return true;
    }

    private boolean checkIpAccess(RouteEntry entry, String clientIp) {
        // Deny takes priority
        if (entry.deniedIps != null) {
            for (String denied : entry.deniedIps) {
                if (matchesIp(clientIp, denied)) return false;
            }
        }
        // If allow list exists, IP must be in it
        if (entry.allowedIps != null) {
            for (String allowed : entry.allowedIps) {
                if (matchesIp(clientIp, allowed)) return true;
            }
            return false; // Not in allow list
        }
        return true; // No allow list = allow all
    }

    private boolean matchesIp(String clientIp, String rule) {
        if (rule == null || rule.isEmpty()) return false;

        if (rule.contains("/")) {
            // CIDR notation
            try {
                String[] parts = rule.split("/");
                byte[] ruleAddr = InetAddress.getByName(parts[0]).getAddress();
                byte[] clientAddr = InetAddress.getByName(clientIp).getAddress();
                int prefixLen = Integer.parseInt(parts[1]);

                if (ruleAddr.length != clientAddr.length) return false;

                int fullBytes = prefixLen / 8;
                int remainBits = prefixLen % 8;

                for (int i = 0; i < fullBytes; i++) {
                    if (ruleAddr[i] != clientAddr[i]) return false;
                }
                if (remainBits > 0) {
                    int mask = 0xFF << (8 - remainBits);
                    if ((ruleAddr[fullBytes] & mask) != (clientAddr[fullBytes] & mask)) return false;
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        return clientIp.equals(rule);
    }

    private boolean checkBasicAuth(HttpServerExchange exchange, RouteEntry entry) {
        if (entry.basicAuthUser == null) return true;

        String authHeader = exchange.getRequestHeaders().getFirst(Headers.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Basic ")) return false;

        try {
            String decoded = new String(Base64.getDecoder().decode(authHeader.substring(6)));
            int colon = decoded.indexOf(':');
            if (colon < 0) return false;
            String user = decoded.substring(0, colon);
            String pass = decoded.substring(colon + 1);
            boolean passwordMatches = entry.basicAuthPass != null
                && (entry.basicAuthPass.startsWith("$argon2")
                    ? PasswordHasher.verify(pass, entry.basicAuthPass)
                    : MessageDigest.isEqual(
                        entry.basicAuthPass.getBytes(StandardCharsets.UTF_8),
                        pass.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    entry.basicAuthUser.getBytes(StandardCharsets.UTF_8),
                    user.getBytes(StandardCharsets.UTF_8))
                && passwordMatches;
        } catch (Exception e) {
            return false;
        }
    }

    private void sendAuthChallenge(HttpServerExchange exchange, RouteEntry entry) {
        String realm = entry.siteName != null && !entry.siteName.isBlank()
            ? entry.siteName.replace("\"", "")
            : "Restricted";
        exchange.setStatusCode(401);
        exchange.getResponseHeaders().put(new HttpString("WWW-Authenticate"), "Basic realm=\"" + realm + "\"");
        exchange.getResponseSender().send("Unauthorized");
    }

    private Pattern compileHostnameRegex(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            return null;
        }

        String source = hostname.trim();
        int flags = Pattern.CASE_INSENSITIVE;

        if (source.startsWith("/") && source.length() > 1) {
            int lastSlash = source.lastIndexOf('/');
            if (lastSlash > 0) {
                String flagSection = source.substring(lastSlash + 1);
                source = source.substring(1, lastSlash);
                if (flagSection.contains("i")) {
                    flags |= Pattern.CASE_INSENSITIVE;
                }
            }
        }

        try {
            return Pattern.compile(source, flags);
        } catch (Exception e) {
            Blast.log("SiteDispatcher: invalid regex hostname", hostname, "-", e.getMessage());
            return null;
        }
    }

    /** Default absolute request lifetime for proxied exchanges. */
    static final int DEFAULT_REQUEST_TIMEOUT_MS = 30_000;

    /**
     * request_timeout site setting in seconds: absent/negative = 30s default,
     * 0 = unlimited (streaming/gRPC/WebSocket sites).
     */
    private static int parseRequestTimeout(Object value) {
        if (value instanceof Number number) {
            int seconds = number.intValue();
            if (seconds == 0) return -1;
            if (seconds > 0) return seconds * 1000;
        }
        return DEFAULT_REQUEST_TIMEOUT_MS;
    }

    private static int parsePositiveInt(Object value) {
        if (value instanceof Integer integer && integer > 0) {
            return integer;
        }

        if (value instanceof Number number) {
            int intValue = number.intValue();
            return Math.max(intValue, 0);
        }

        return 0;
    }

    private static List<HeaderRule> parseHeaderRules(Object value) {
        if (value == null) {
            return List.of();
        }

        List<HeaderRule> result = new ArrayList<>();

        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.add(new HeaderRule(String.valueOf(entry.getKey()),
                        entry.getValue() != null ? String.valueOf(entry.getValue()) : ""));
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object name = map.get("name");
                    if (name != null && !String.valueOf(name).isBlank()) {
                        Object headerValue = map.get("value");
                        result.add(new HeaderRule(String.valueOf(name),
                            headerValue != null ? String.valueOf(headerValue) : ""));
                    }
                }
            }
        }

        return List.copyOf(result);
    }

    private static List<String> parseListenOnAddresses(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        String[] parts = value.split("[,\\s]+");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                result.add(part.trim().toLowerCase());
            }
        }
        return List.copyOf(result);
    }

    private static String normalizeListenerIp(String value) {
        if (value == null) {
            return "";
        }

        String normalized = value.trim().toLowerCase();
        if (normalized.startsWith("::ffff:")) {
            normalized = normalized.substring("::ffff:".length());
        }
        return normalized;
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

    private static List<String> extractNamedGroups(String patternSource) {
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



    private String extractListenerAddress(HttpServerExchange exchange) {
        InetSocketAddress address = exchange.getDestinationAddress();
        if (address == null) {
            address = exchange.getConnection().getLocalAddress(InetSocketAddress.class);
        }
        return address != null && address.getAddress() != null ? address.getAddress().getHostAddress() : null;
    }

    private void dispatchToRoute(RouteEntry entry, HttpServerExchange exchange) {
        exchange.addResponseCommitListener(ex -> applyResponseMutations(entry, ex));

        ProxyHandler timedProxyHandler = proxyHandlerFor(entry.requestTimeoutMs);
        Runnable dispatch = () -> entry.handler.handleRequest(exchange, upstream -> {
            exchange.putAttachment(UPSTREAM_URI, upstream);
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
     * Applied just before response headers commit: domain response-header rules, upstream
     * Location rewrite, then the handler's optional {@link ResponseMutator} seam.
     */
    private void applyResponseMutations(RouteEntry entry, HttpServerExchange exchange) {
        HeaderMap headers = exchange.getResponseHeaders();

        for (HeaderRule rule : entry.responseHeaders) {
            HttpString name = new HttpString(rule.name());
            if (rule.value() == null || rule.value().isBlank()) {
                headers.remove(name);
            } else {
                headers.put(name, rule.value());
            }
        }

        UpstreamTarget upstream = exchange.getAttachment(UPSTREAM_URI);
        if (upstream != null && Boolean.TRUE.equals(exchange.getAttachment(REWRITE_LOCATION))) {
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

        String authority = exchange.getRequestHeaders().getFirst(HOST);
        if (authority == null || authority.isBlank()) {
            return null;
        }

        StringBuilder rewritten = new StringBuilder(exchange.getRequestScheme())
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

    private boolean shouldForceHttpsGlobally(HttpServerExchange exchange) {
        return httpsAvailable
            && "http".equals(exchange.getRequestScheme())
            && Boolean.TRUE.equals(HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.FORCE_HTTPS));
    }

    public void setHttpsAvailable(boolean httpsAvailable) {
        this.httpsAvailable = httpsAvailable;
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

    private void destroyHandlers() {
        RouteTable rt = this.routes;
        Set<SiteRequestHandler> handlers = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<SiteAuthGate> gates = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<RouteEntry> bucket : rt.exactRoutes.values()) {
            for (RouteEntry entry : bucket) {
                handlers.add(entry.handler);
                if (entry.authGate != null) gates.add(entry.authGate);
            }
        }
        for (WildcardRoute route : rt.wildcardRoutes) {
            handlers.add(route.entry().handler);
            if (route.entry().authGate != null) gates.add(route.entry().authGate);
        }
        for (RegexRoute route : rt.regexRoutes) {
            handlers.add(route.entry().handler);
            if (route.entry().authGate != null) gates.add(route.entry().authGate);
        }
        for (SiteRequestHandler handler : handlers) {
            handler.destroy();
        }
        for (SiteAuthGate gate : gates) {
            gate.destroy();
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
            String realIp = exchange.getRequestHeaders().getFirst(X_REAL_IP);
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }
        return sourceIp;
    }

    private boolean isTrustedRemoteProxy(HttpServerExchange exchange) {
        String key = exchange.getRequestHeaders().getFirst(X_HOHENHEIM_KEY);
        return key != null && trustedProxyKeys().contains(key);
    }

    private Set<String> trustedProxyKeys() {
        long now = System.currentTimeMillis();
        if (now - trustedKeysReadAt >= TRUSTED_KEYS_TTL_MS) {
            trustedKeysReadAt = now;
            String raw = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS);
            if (raw == null || raw.isBlank()) {
                trustedProxyKeys = Set.of();
            } else {
                Set<String> keys = new HashSet<>();
                for (String part : raw.split(",")) {
                    if (!part.isBlank()) {
                        keys.add(part.trim());
                    }
                }
                trustedProxyKeys = Set.copyOf(keys);
            }
        }
        return trustedProxyKeys;
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

    // -----------------------------------------------------------------------
    // Proxy forwarding
    // -----------------------------------------------------------------------

    /**
     * Test-only override for the trusted upstream SSL context, so HTTPS-upstream tests can
     * trust their own self-signed authority without weakening production validation.
     */
    private static volatile SSLContext trustedSslContextOverride;

    public static void overrideTrustedUpstreamSslContextForTests(SSLContext context) {
        trustedSslContextOverride = context;
    }

    private class DispatchingProxyClient implements ProxyClient {

        private final UndertowClient client = UndertowClient.getInstance();
        private final XnioSsl insecureSsl = createInsecureSsl();
        private final XnioSsl trustedSsl = createTrustedSsl();

        @Override
        public ProxyTarget findTarget(HttpServerExchange exchange) {
            UpstreamTarget target = exchange.getAttachment(UPSTREAM_URI);
            return target != null ? new SimpleTarget(target) : null;
        }

        @Override
        public void getConnection(ProxyTarget target, HttpServerExchange exchange,
                                  ProxyCallback<ProxyConnection> callback,
                                  long timeout, TimeUnit timeUnit) {
            SimpleTarget simpleTarget = (SimpleTarget) target;
            UpstreamTarget upstreamTarget = simpleTarget.target;
            URI uri = dialUri(upstreamTarget);
            XnioSsl ssl = sslFor(upstreamTarget);

            // AIDEV-NOTE: Undertow's https/h2 providers force JDK endpoint identification
            // (the SAN hostname check) unless the caller overrides the option -- so
            // ignore_certificates must disable it too, or a trust-all context still
            // rejects certs whose SAN doesn't match the dial host.
            OptionMap connectOptions = upstreamTarget.ignoreCertificates()
                ? OptionMap.create(UndertowOptions.ENDPOINT_IDENTIFICATION_ALGORITHM, "")
                : OptionMap.EMPTY;

            client.connect(new ClientCallback<ClientConnection>() {
                @Override
                public void completed(ClientConnection connection) {
                    ServerConnection serverConn = exchange.getConnection();
                    serverConn.addCloseListener(sc -> IoUtils.safeClose(connection));

                    String path = uri.getPath();
                    if (path == null || path.isEmpty()) path = "/";

                    // Capture response trailers (e.g. grpc-status) that the stock Undertow
                    // h2 client would otherwise drop. Applied to EVERY upstream connection:
                    // the hook only engages on HTTP/2 response channels, so h1 connections
                    // pass through untouched and no protocol combination is left out.
                    callback.completed(exchange, new ProxyConnection(
                        new TrailerCapturingClientConnection(connection), path));
                }

                @Override
                public void failed(IOException e) {
                    callback.failed(exchange);
                }
            }, uri, exchange.getIoThread(), ssl,
               exchange.getConnection().getByteBufferPool(),
               connectOptions);
        }

        /**
         * The URI to hand Undertow's client, with the scheme mapped to the wire protocol:
         * h2c-prior (prior-knowledge cleartext HTTP/2) or h2 (ALPN) for H2 upstreams.
         */
        private URI dialUri(UpstreamTarget target) {
            URI uri = target.uri();
            String dialScheme = target.protocol().dialScheme(uri.getScheme());
            if (dialScheme.equals(uri.getScheme())) {
                return uri;
            }
            try {
                return new URI(dialScheme, uri.getUserInfo(), uri.getHost(), uri.getPort(),
                    uri.getPath(), uri.getQuery(), uri.getFragment());
            } catch (URISyntaxException e) {
                return uri;
            }
        }

        /**
         * TLS upstreams always get an SSL provider; Undertow rejects an https/h2 dial with a
         * null XnioSsl outright, so "no ssl" is only correct for cleartext upstreams.
         */
        private @Nullable XnioSsl sslFor(UpstreamTarget target) {
            if (!"https".equalsIgnoreCase(target.uri().getScheme())) {
                return null;
            }
            if (target.ignoreCertificates()) {
                return insecureSsl;
            }
            SSLContext override = trustedSslContextOverride;
            if (override != null) {
                return new UndertowXnioSsl(Xnio.getInstance(), OptionMap.EMPTY, override);
            }
            return trustedSsl;
        }

        private XnioSsl createTrustedSsl() {
            return new UndertowXnioSsl(Xnio.getInstance(), OptionMap.EMPTY,
                UpstreamTrust.defaultContext());
        }

        private XnioSsl createInsecureSsl() {
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[]{new X509TrustManager() {
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }
                }}, new SecureRandom());

                return new UndertowXnioSsl(Xnio.getInstance(), OptionMap.EMPTY, context);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to initialize insecure proxy SSL", e);
            }
        }
    }

    private static final class SimpleTarget implements ProxyClient.ProxyTarget {
        final UpstreamTarget target;
        SimpleTarget(UpstreamTarget target) { this.target = target; }
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
        destroyHandlers();
        delayScheduler.shutdownNow();
    }
}
