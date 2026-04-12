package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.source.GitProvisioner;
import be.elevenways.hohenheim.server.source.GitWebhookHandler;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.sitetype.UpstreamTarget;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
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
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.AttachmentKey;
import io.undertow.util.HttpString;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.Xnio;

import java.io.IOException;
import java.io.Writer;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.net.URI;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

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

    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    /**
     * Immutable snapshot of all route tables. Swapped atomically via volatile reference
     * so concurrent requests never see a partially-loaded state.
     */
    private static final class RouteTable {
        final Map<String, RouteEntry> exactRoutes;
        final Map<String, RouteEntry> wildcardRoutes;
        final List<RegexRoute> regexRoutes;

        RouteTable(Map<String, RouteEntry> exact, Map<String, RouteEntry> wildcard,
                   List<RegexRoute> regex) {
            this.exactRoutes = exact;
            this.wildcardRoutes = wildcard;
            this.regexRoutes = regex;
        }
    }

    private volatile RouteTable routes = new RouteTable(Map.of(), Map.of(), List.of());

    // Positive cache for regex matches.
    private final ConcurrentHashMap<String, CachedRegexMatch> regexMatchCache = new ConcurrentHashMap<>();

    // Negative cache for hostnames that don't match any route
    private final ConcurrentHashMap<String, Long> negativeCache = new ConcurrentHashMap<>();
    private static final long NEGATIVE_CACHE_TTL_MS = 5 * 60 * 1000;
    private static final int NEGATIVE_CACHE_MAX = 5000;

    private static final long REGEX_CACHE_TTL_MS = 5 * 60 * 1000;
    private static final HttpString HOST = Headers.HOST;
    private static final Pattern NAMED_GROUP_PATTERN = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9_]*)>");

    // IP reputation: tracks domain miss count per IP
    private final ConcurrentHashMap<String, IpReputation> ipReputation = new ConcurrentHashMap<>();
    private static final int IP_REPUTATION_REJECT_THRESHOLD = 100;

    private static final String ACME_CHALLENGE_PREFIX = "/.well-known/acme-challenge/";

    // Shared proxy handler for proxy-type sites
    private final ProxyHandler proxyHandler;

    private final ScheduledExecutorService delayScheduler;

    // ACME service for Let's Encrypt challenge responses (nullable)
    private final AcmeService acmeService;

    private volatile boolean httpsAvailable;

    /**
     * A route entry combines the site handler with domain-level configuration.
     */
    private static final class RouteEntry {
        final SiteRequestHandler handler;
        final String siteName;
        final String path;
        final boolean stripPath;
        final boolean forceSsl;
        final int requestDelayMs;
        final boolean hstsEnabled;
        final boolean hstsSubdomains;
        final List<HeaderRule> customHeaders;
        final List<String> listenOnAddresses;

        // Access list enforcement
        final String[] allowedIps;
        final String[] deniedIps;
        final String basicAuthUser;
        final String basicAuthPass;
        final String accessListSatisfy;

        RouteEntry(SiteRequestHandler handler, String siteName, Row domain, Row accessList,
                   Map<String, Object> siteSettings) {
            this.handler = handler;
            this.siteName = siteName;

            String p = domain != null ? domain.get(SiteDomainModel.PATH) : null;
            this.path = (p != null && !p.isEmpty()) ? p : null;
            this.stripPath = domain != null && Boolean.TRUE.equals(domain.get(SiteDomainModel.STRIP_PATH));
            this.forceSsl = domain != null && Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL));
            this.requestDelayMs = parsePositiveInt(siteSettings != null ? siteSettings.get("delay") : null);
            this.hstsEnabled = domain != null && Boolean.TRUE.equals(domain.get(SiteDomainModel.HSTS_ENABLED));
            this.hstsSubdomains = domain != null && Boolean.TRUE.equals(domain.get(SiteDomainModel.HSTS_SUBDOMAINS));
            this.customHeaders = parseHeaderRules(domain != null ? domain.get(SiteDomainModel.CUSTOM_HEADERS) : null);
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

    private record RegexRoute(String hostnamePattern, Pattern pattern, List<String> namedGroups,
                              RouteEntry entry) {}

    private record CachedRegexMatch(RouteEntry entry, Map<String, String> groups, long cachedAt) {}

    private record RouteMatch(RouteEntry entry, Map<String, String> groups) {}

    private static final class IpReputation {
        final AtomicInteger hits = new AtomicInteger(0);
        final AtomicInteger misses = new AtomicInteger(0);
        final AtomicLong lastMissTime = new AtomicLong(0);
    }

    public SiteDispatcher(AcmeService acmeService) {
        this.acmeService = acmeService;
        this.delayScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "site-dispatch-delay");
            thread.setDaemon(true);
            return thread;
        });
        DispatchingProxyClient proxyClient = new DispatchingProxyClient();
        this.proxyHandler = ProxyHandler.builder()
            .setProxyClient(proxyClient)
            .setMaxRequestTime(30000)
            .setNext(ResponseCodeHandler.HANDLE_404)
            .setRewriteHostHeader(false)
            .setReuseXForwarded(false)
            .build();
    }

    /**
     * Reload all routes from the database, creating handlers via the site type system.
     */
    public void reloadRoutes() {
        var ds = HohenheimDatabase.datasource();
        var siteModel = new SiteModel(ds);
        var domainModel = new SiteDomainModel(ds);
        var accessListModel = new AccessListModel(ds);

        // Build new route maps first, then swap atomically
        Map<String, RouteEntry> newExact = new HashMap<>();
        Map<String, RouteEntry> newWildcard = new HashMap<>();
        List<RegexRoute> newRegex = new ArrayList<>();

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

            // Check for git provisioning
            SiteRequestHandler requestHandler;
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
            List<Row> domains = domainModel.findBySiteId(siteId);

            for (Row domain : domains) {
                String hostname = domain.get(SiteDomainModel.HOSTNAME);
                String matchType = domain.get(SiteDomainModel.MATCH_TYPE);
                if (hostname == null || hostname.isEmpty()) continue;

                RouteEntry entry = new RouteEntry(requestHandler, siteName, domain, accessList, settings);

                if ("regex".equals(matchType)) {
                    Pattern pattern = compileHostnameRegex(hostname);
                    if (pattern != null) {
                        newRegex.add(new RegexRoute(hostname, pattern,
                            extractNamedGroups(hostname), entry));
                    }
                } else if ("wildcard".equals(matchType) || hostname.startsWith("*.")) {
                    newWildcard.put(hostname.toLowerCase(), entry);
                } else {
                    newExact.put(hostname.toLowerCase(), entry);
                }
            }
        }

        // Destroy old handlers, then swap in the new routes atomically
        destroyHandlers();
        this.routes = new RouteTable(Map.copyOf(newExact), Map.copyOf(newWildcard), List.copyOf(newRegex));
        negativeCache.clear();
        regexMatchCache.clear();

        Blast.log("SiteDispatcher: loaded", newExact.size(), "exact routes,",
                  newWildcard.size(), "wildcard routes,", newRegex.size(), "regex routes");
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

        // --- IP reputation enforcement: reject known-bad IPs early ---
        String earlyIp = exchange.getSourceAddress().getAddress().getHostAddress();
        IpReputation rep = ipReputation.get(earlyIp);
        if (rep != null && rep.misses.get() >= IP_REPUTATION_REJECT_THRESHOLD && rep.hits.get() == 0) {
            exchange.setStatusCode(403);
            exchange.endExchange();
            return;
        }

        // --- ACME HTTP-01 challenge ---
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

        RouteMatch match = resolveEntry(exchange, hostname);
        RouteEntry entry = match != null ? match.entry() : null;

        if (match != null && match.groups() != null && !match.groups().isEmpty()) {
            exchange.putAttachment(MATCHED_GROUPS, match.groups());
        }

        // --- IP reputation tracking ---
        String clientIp = getClientIp(exchange);
        if (entry != null) {
            trackHit(clientIp);
        } else {
            trackMiss(clientIp, hostname);
        }

        // --- Default fallback ---
        if (entry == null) {
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

        // --- Access list enforcement ---
        if (entry.hasAccessList() && !checkAccessList(exchange, entry, clientIp)) {
            return;
        }

        // --- Path matching ---
        if (entry.path != null) {
            String requestPath = exchange.getRelativePath();
            if (!requestPath.startsWith(entry.path)) {
                exchange.setStatusCode(404);
                exchange.getResponseSender().send("Not Found");
                return;
            }
            if (entry.stripPath) {
                String stripped = requestPath.substring(entry.path.length());
                if (stripped.isEmpty()) stripped = "/";
                exchange.setRelativePath(stripped);
                exchange.setRequestPath(stripped);
            }
        }

        // --- Inject proxy headers ---
        HeaderMap requestHeaders = exchange.getRequestHeaders();
        requestHeaders.put(X_PROXIED_BY, instanceId);

        if (!requestHeaders.contains(X_FORWARDED_FOR)) {
            requestHeaders.put(X_FORWARDED_FOR, clientIp);
        } else {
            String existing = requestHeaders.getFirst(X_FORWARDED_FOR);
            requestHeaders.put(X_FORWARDED_FOR, existing + ", " + clientIp);
        }
        if (!requestHeaders.contains(X_FORWARDED_PROTO)) {
            requestHeaders.put(X_FORWARDED_PROTO, exchange.getRequestScheme());
        }
        if (!requestHeaders.contains(X_REAL_IP)) {
            requestHeaders.put(X_REAL_IP, clientIp);
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
        logAccess(exchange, hostname, clientIp);

        dispatchToRoute(entry, exchange);
    }

    private static final AttachmentKey<UpstreamTarget> UPSTREAM_URI =
        AttachmentKey.create(UpstreamTarget.class);

    /** Named + numbered regex-host capture groups from the active route, if any. */
    public static final AttachmentKey<Map<String, String>> MATCHED_GROUPS =
        AttachmentKey.create(Map.class);

    // -----------------------------------------------------------------------
    // Resolution
    // -----------------------------------------------------------------------

    private String extractHostname(HttpServerExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst(HOST);
        if (host == null) return "";
        int colon = host.indexOf(':');
        return (colon != -1 ? host.substring(0, colon) : host).toLowerCase();
    }

    private RouteMatch resolveEntry(HttpServerExchange exchange, String hostname) {
        if (hostname.isEmpty()) return null;

        String listenerIp = extractListenerAddress(exchange);
        String cacheKey = hostname + "|" + (listenerIp != null ? listenerIp : "");

        // Check negative cache
        Long cachedAt = negativeCache.get(cacheKey);
        if (cachedAt != null) {
            if (System.currentTimeMillis() - cachedAt < NEGATIVE_CACHE_TTL_MS) {
                return null;
            }
            negativeCache.remove(cacheKey);
        }

        // Take a consistent snapshot of the route table
        RouteTable rt = this.routes;

        // 1. Exact match
        RouteEntry entry = rt.exactRoutes.get(hostname);
        if (entry != null && entry.acceptsListener(listenerIp)) return new RouteMatch(entry, null);

        // 2. Wildcard match
        for (Map.Entry<String, RouteEntry> e : rt.wildcardRoutes.entrySet()) {
            RouteEntry wildcardEntry = e.getValue();
            if (wildcardEntry.acceptsListener(listenerIp) && matchesWildcard(hostname, e.getKey())) {
                return new RouteMatch(wildcardEntry, null);
            }
        }

        // 3. Regex match (with positive cache)
        CachedRegexMatch cachedMatch = regexMatchCache.get(cacheKey);
        if (cachedMatch != null) {
            if (System.currentTimeMillis() - cachedMatch.cachedAt() < REGEX_CACHE_TTL_MS) {
                return new RouteMatch(cachedMatch.entry(), cachedMatch.groups());
            }
            regexMatchCache.remove(cacheKey);
        }

        for (RegexRoute regexRoute : rt.regexRoutes) {
            if (!regexRoute.entry().acceptsListener(listenerIp)) {
                continue;
            }

            Matcher matcher = regexRoute.pattern().matcher(hostname);
            if (matcher.matches()) {
                Map<String, String> groups = extractRegexGroups(matcher, regexRoute.namedGroups());
                CachedRegexMatch positiveMatch = new CachedRegexMatch(regexRoute.entry(), groups,
                    System.currentTimeMillis());
                regexMatchCache.put(cacheKey, positiveMatch);
                return new RouteMatch(regexRoute.entry(), groups);
            }
        }

        // 4. No match
        if (negativeCache.size() < NEGATIVE_CACHE_MAX) {
            negativeCache.put(cacheKey, System.currentTimeMillis());
        }
        return null;
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
                sendAuthChallenge(exchange);
                return false;
            }
        } else {
            // "any": pass if either passes (or if only one is configured)
            if (hasIpRules && hasAuth) {
                if (!ipAllowed && !authPassed) {
                    sendAuthChallenge(exchange);
                    return false;
                }
            } else if (hasIpRules && !ipAllowed) {
                exchange.setStatusCode(403);
                exchange.getResponseSender().send("Forbidden");
                return false;
            } else if (hasAuth && !authPassed) {
                sendAuthChallenge(exchange);
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
                byte[] ruleAddr = java.net.InetAddress.getByName(parts[0]).getAddress();
                byte[] clientAddr = java.net.InetAddress.getByName(clientIp).getAddress();
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
            String decoded = new String(java.util.Base64.getDecoder().decode(authHeader.substring(6)));
            int colon = decoded.indexOf(':');
            if (colon < 0) return false;
            String user = decoded.substring(0, colon);
            String pass = decoded.substring(colon + 1);
            return MessageDigest.isEqual(
                    entry.basicAuthUser.getBytes(StandardCharsets.UTF_8),
                    user.getBytes(StandardCharsets.UTF_8))
                && MessageDigest.isEqual(
                    entry.basicAuthPass.getBytes(StandardCharsets.UTF_8),
                    pass.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private void sendAuthChallenge(HttpServerExchange exchange) {
        exchange.setStatusCode(401);
        exchange.getResponseHeaders().put(new HttpString("WWW-Authenticate"), "Basic realm=\"Restricted\"");
        exchange.getResponseSender().send("Unauthorized");
    }

    private boolean matchesWildcard(String hostname, String pattern) {
        if (!pattern.startsWith("*.")) return false;
        String suffix = pattern.substring(1);
        return hostname.endsWith(suffix) && hostname.length() > suffix.length();
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
        Runnable dispatch = () -> entry.handler.handleRequest(exchange, upstream -> {
            exchange.putAttachment(UPSTREAM_URI, upstream);
            try {
                proxyHandler.handleRequest(exchange);
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

    private boolean shouldForceHttpsGlobally(HttpServerExchange exchange) {
        return httpsAvailable
            && "http".equals(exchange.getRequestScheme())
            && Boolean.TRUE.equals(HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.FORCE_HTTPS));
    }

    public void setHttpsAvailable(boolean httpsAvailable) {
        this.httpsAvailable = httpsAvailable;
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
        Set<SiteRequestHandler> handlers = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (RouteEntry entry : rt.exactRoutes.values()) {
            handlers.add(entry.handler);
        }
        for (RouteEntry entry : rt.wildcardRoutes.values()) {
            handlers.add(entry.handler);
        }
        for (RegexRoute route : rt.regexRoutes) {
            handlers.add(route.entry().handler);
        }
        for (SiteRequestHandler handler : handlers) {
            handler.destroy();
        }
    }

    // -----------------------------------------------------------------------
    // IP reputation
    // -----------------------------------------------------------------------

    private String getClientIp(HttpServerExchange exchange) {
        return exchange.getSourceAddress().getAddress().getHostAddress();
    }

    private void trackHit(String ip) {
        ipReputation.computeIfAbsent(ip, k -> new IpReputation()).hits.incrementAndGet();
    }

    private void trackMiss(String ip, String hostname) {
        IpReputation rep = ipReputation.computeIfAbsent(ip, k -> new IpReputation());
        int misses = rep.misses.incrementAndGet();
        rep.lastMissTime.set(System.currentTimeMillis());

        boolean logEnabled = HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.LOG_DOMAIN_MISSES);
        int threshold = HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.DOMAIN_MISS_THRESHOLD);

        if (logEnabled && misses >= threshold) {
            // Strip newlines to prevent log injection
            String safeHost = hostname.replace("\n", "").replace("\r", "");
            String safeIp = ip.replace("\n", "").replace("\r", "");
            String line = Instant.now() + " DOMAIN_MISS ip=" + safeIp
                + " domain=" + safeHost + " misses=" + misses;
            Blast.log(line);
            logDomainMissToFile(line);
        }

        // Evict old entries if the map grows too large
        if (ipReputation.size() > 50000) {
            long cutoff = System.currentTimeMillis() - 3600_000;
            ipReputation.entrySet().removeIf(e -> e.getValue().lastMissTime.get() < cutoff);
        }
    }

    private void logAccess(HttpServerExchange exchange, String hostname, String clientIp) {
        boolean logToFile = Boolean.TRUE.equals(
            HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.ACCESS_TO_FILE));

        if (!logToFile) return;

        // Register a completion listener to log after the response
        exchange.addExchangeCompleteListener((ex, next) -> {
            try {
                String logPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.ACCESS_PATH);
                if (logPath == null || logPath.isEmpty()) { next.proceed(); return; }

                int status = ex.getStatusCode();
                String method = ex.getRequestMethod().toString();
                String path = ex.getRelativePath();
                String query = ex.getQueryString();
                String ua = ex.getRequestHeaders().getFirst(Headers.USER_AGENT);
                long size = ex.getResponseBytesSent();

                // Combined log format
                String line = clientIp + " - - [" + Instant.now() + "] \""
                    + method + " " + path + (query != null && !query.isEmpty() ? "?" + query : "")
                    + " " + ex.getProtocol() + "\" " + status + " " + size
                    + " \"" + (hostname != null ? hostname : "-") + "\""
                    + " \"" + (ua != null ? ua : "-") + "\"";

                appendToLogFile(logPath, line);
            } catch (Exception ignored) {
                // Don't let logging break request handling
            }
            next.proceed();
        });
    }

    private void logDomainMissToFile(String line) {
        String logPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.DOMAIN_MISSES_LOG_PATH);
        if (logPath == null || logPath.isEmpty()) return;
        appendToLogFile(logPath, line);
    }

    private static void appendToLogFile(String logPath, String line) {
        try {
            java.io.File logFile = new java.io.File(logPath);
            java.io.File parent = logFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            try (Writer writer = new BufferedWriter(new FileWriter(logFile, true))) {
                writer.write(line);
                writer.write('\n');
            }
        } catch (IOException ignored) {
            // Don't let log writing failures break request handling
        }
    }

    // -----------------------------------------------------------------------
    // Proxy forwarding
    // -----------------------------------------------------------------------

    private class DispatchingProxyClient implements ProxyClient {

        private final UndertowClient client = UndertowClient.getInstance();
        private final org.xnio.ssl.XnioSsl insecureSsl = createInsecureSsl();

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
            URI uri = upstreamTarget.uri();
            org.xnio.ssl.XnioSsl ssl = upstreamTarget.ignoreCertificates() ? insecureSsl : null;

            client.connect(new ClientCallback<ClientConnection>() {
                @Override
                public void completed(ClientConnection connection) {
                    ServerConnection serverConn = exchange.getConnection();
                    serverConn.addCloseListener(sc -> IoUtils.safeClose(connection));

                    String path = uri.getPath();
                    if (path == null || path.isEmpty()) path = "/";

                    callback.completed(exchange, new ProxyConnection(connection, path));
                }

                @Override
                public void failed(IOException e) {
                    callback.failed(exchange);
                }
            }, uri, exchange.getIoThread(), ssl,
               exchange.getConnection().getByteBufferPool(),
               OptionMap.EMPTY);
        }

        private org.xnio.ssl.XnioSsl createInsecureSsl() {
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[]{new X509TrustManager() {
                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }

                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
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
        return routes.exactRoutes.size();
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

        for (RouteEntry entry : rt.exactRoutes.values()) {
            if (entry.handler.getSiteId() == siteId) return entry.handler;
        }
        for (RouteEntry entry : rt.wildcardRoutes.values()) {
            if (entry.handler.getSiteId() == siteId) return entry.handler;
        }
        for (RegexRoute route : rt.regexRoutes) {
            if (route.entry().handler.getSiteId() == siteId) return route.entry().handler;
        }

        return null;
    }

    /**
     * Shut down internal executors. Called from ProxyServer.stop().
     */
    public void shutdown() {
        delayScheduler.shutdownNow();
    }
}
