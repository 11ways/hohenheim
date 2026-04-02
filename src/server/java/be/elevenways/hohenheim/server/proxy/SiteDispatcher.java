package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import io.undertow.client.ClientCallback;
import io.undertow.client.ClientConnection;
import io.undertow.client.UndertowClient;
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
import io.undertow.util.HttpString;
import org.xnio.IoUtils;
import org.xnio.OptionMap;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

    // Exact hostname -> route entry
    private final ConcurrentHashMap<String, RouteEntry> exactRoutes = new ConcurrentHashMap<>();

    // Wildcard patterns (*.example.com) -> route entry
    private final ConcurrentHashMap<String, RouteEntry> wildcardRoutes = new ConcurrentHashMap<>();

    // Negative cache for hostnames that don't match any route
    private final ConcurrentHashMap<String, Long> negativeCache = new ConcurrentHashMap<>();
    private static final long NEGATIVE_CACHE_TTL_MS = 5 * 60 * 1000;
    private static final int NEGATIVE_CACHE_MAX = 5000;

    // IP reputation: tracks domain miss count per IP
    private final ConcurrentHashMap<String, IpReputation> ipReputation = new ConcurrentHashMap<>();

    private static final String ACME_CHALLENGE_PREFIX = "/.well-known/acme-challenge/";

    // Shared proxy handler for proxy-type sites
    private final ProxyHandler proxyHandler;

    // ACME service for Let's Encrypt challenge responses (nullable)
    private final AcmeService acmeService;

    /**
     * A route entry combines the site handler with domain-level configuration.
     */
    private static final class RouteEntry {
        final SiteRequestHandler handler;
        final String siteName;
        final String path;
        final boolean stripPath;
        final boolean forceSsl;
        final boolean hstsEnabled;
        final boolean hstsSubdomains;
        @SuppressWarnings("unchecked")
        final Map<String, Object> customHeaders;

        // Access list enforcement
        final String[] allowedIps;
        final String[] deniedIps;
        final String basicAuthUser;
        final String basicAuthPass;
        final String accessListSatisfy;

        RouteEntry(SiteRequestHandler handler, String siteName, Row domain, Row accessList) {
            this.handler = handler;
            this.siteName = siteName;

            String p = domain != null ? domain.get(SiteDomainModel.PATH) : null;
            this.path = (p != null && !p.isEmpty()) ? p : null;
            this.stripPath = domain != null && Boolean.TRUE.equals(domain.get(SiteDomainModel.STRIP_PATH));
            this.forceSsl = domain != null && Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL));
            this.hstsEnabled = domain != null && Boolean.TRUE.equals(domain.get(SiteDomainModel.HSTS_ENABLED));
            this.hstsSubdomains = domain != null && Boolean.TRUE.equals(domain.get(SiteDomainModel.HSTS_SUBDOMAINS));

            Object ch = domain != null ? domain.get(SiteDomainModel.CUSTOM_HEADERS) : null;
            this.customHeaders = (ch instanceof Map) ? (Map<String, Object>) ch : null;

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
    }

    private static final class IpReputation {
        final AtomicInteger hits = new AtomicInteger(0);
        final AtomicInteger misses = new AtomicInteger(0);
        final AtomicLong lastMissTime = new AtomicLong(0);
    }

    public SiteDispatcher(AcmeService acmeService) {
        this.acmeService = acmeService;
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

        // Destroy existing handlers
        for (RouteEntry entry : exactRoutes.values()) entry.handler.destroy();
        for (RouteEntry entry : wildcardRoutes.values()) entry.handler.destroy();

        exactRoutes.clear();
        wildcardRoutes.clear();
        negativeCache.clear();

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

            SiteRequestHandler requestHandler = typeHandler.createHandler(site, settings);
            List<Row> domains = domainModel.findBySiteId(siteId);

            for (Row domain : domains) {
                String hostname = domain.get(SiteDomainModel.HOSTNAME);
                String matchType = domain.get(SiteDomainModel.MATCH_TYPE);
                if (hostname == null || hostname.isEmpty()) continue;

                RouteEntry entry = new RouteEntry(requestHandler, siteName, domain, accessList);

                if ("wildcard".equals(matchType) || hostname.startsWith("*.")) {
                    wildcardRoutes.put(hostname.toLowerCase(), entry);
                } else {
                    exactRoutes.put(hostname.toLowerCase(), entry);
                }
            }
        }

        Blast.log("SiteDispatcher: loaded", exactRoutes.size(), "exact routes,",
                  wildcardRoutes.size(), "wildcard routes");
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

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

        // --- Loop detection ---
        String existingProxiedBy = exchange.getRequestHeaders().getFirst(X_PROXIED_BY);
        if (existingProxiedBy != null && existingProxiedBy.contains(instanceId)) {
            exchange.setStatusCode(508);
            exchange.getResponseSender().send("Loop Detected");
            return;
        }

        String hostname = extractHostname(exchange);
        RouteEntry entry = resolveEntry(hostname);

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
        if (entry.forceSsl && "http".equals(exchange.getRequestScheme())) {
            String redirectUrl = "https://" + hostname + exchange.getRelativePath();
            String query = exchange.getQueryString();
            if (query != null && !query.isEmpty()) redirectUrl += "?" + query;
            exchange.setStatusCode(301);
            exchange.getResponseHeaders().put(Headers.LOCATION, redirectUrl);
            exchange.endExchange();
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

        // --- Custom response headers ---
        if (entry.customHeaders != null) {
            HeaderMap responseHeaders = exchange.getResponseHeaders();
            for (Map.Entry<String, Object> h : entry.customHeaders.entrySet()) {
                responseHeaders.put(new HttpString(h.getKey()), String.valueOf(h.getValue()));
            }
        }

        // --- HSTS ---
        if (entry.hstsEnabled) {
            String hstsValue = "max-age=31536000";
            if (entry.hstsSubdomains) hstsValue += "; includeSubDomains";
            exchange.getResponseHeaders().put(STRICT_TRANSPORT_SECURITY, hstsValue);
        }

        // --- Dispatch to handler ---
        entry.handler.handleRequest(exchange, uri -> {
            exchange.putAttachment(UPSTREAM_URI, uri);
            try {
                proxyHandler.handleRequest(exchange);
            } catch (Exception e) {
                ErrorPages.send502(exchange, e.getMessage());
            }
        });
    }

    private static final io.undertow.util.AttachmentKey<URI> UPSTREAM_URI =
        io.undertow.util.AttachmentKey.create(URI.class);

    // -----------------------------------------------------------------------
    // Resolution
    // -----------------------------------------------------------------------

    private String extractHostname(HttpServerExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst(Headers.HOST);
        if (host == null) return "";
        int colon = host.indexOf(':');
        return (colon != -1 ? host.substring(0, colon) : host).toLowerCase();
    }

    private RouteEntry resolveEntry(String hostname) {
        if (hostname.isEmpty()) return null;

        // Check negative cache
        Long cachedAt = negativeCache.get(hostname);
        if (cachedAt != null) {
            if (System.currentTimeMillis() - cachedAt < NEGATIVE_CACHE_TTL_MS) {
                return null;
            }
            negativeCache.remove(hostname);
        }

        // 1. Exact match
        RouteEntry entry = exactRoutes.get(hostname);
        if (entry != null) return entry;

        // 2. Wildcard match
        for (Map.Entry<String, RouteEntry> e : wildcardRoutes.entrySet()) {
            if (matchesWildcard(hostname, e.getKey())) {
                return e.getValue();
            }
        }

        // 3. No match
        if (negativeCache.size() < NEGATIVE_CACHE_MAX) {
            negativeCache.put(hostname, System.currentTimeMillis());
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
            return entry.basicAuthUser.equals(user) && entry.basicAuthPass.equals(pass);
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

    private void logDomainMissToFile(String line) {
        String logPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.DOMAIN_MISSES_LOG_PATH);
        if (logPath == null || logPath.isEmpty()) return;
        try {
            java.io.File logFile = new java.io.File(logPath);
            java.io.File parent = logFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            try (var writer = new FileWriter(logFile, true)) {
                writer.write(line + "\n");
            }
        } catch (IOException e) {
            // Don't let log writing failures break request handling
        }
    }

    // -----------------------------------------------------------------------
    // Proxy forwarding
    // -----------------------------------------------------------------------

    private class DispatchingProxyClient implements ProxyClient {

        private final UndertowClient client = UndertowClient.getInstance();

        @Override
        public ProxyTarget findTarget(HttpServerExchange exchange) {
            URI uri = exchange.getAttachment(UPSTREAM_URI);
            return uri != null ? new SimpleTarget(uri) : null;
        }

        @Override
        public void getConnection(ProxyTarget target, HttpServerExchange exchange,
                                  ProxyCallback<ProxyConnection> callback,
                                  long timeout, TimeUnit timeUnit) {
            SimpleTarget simpleTarget = (SimpleTarget) target;
            URI uri = simpleTarget.uri;

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
            }, uri, exchange.getIoThread(),
               exchange.getConnection().getByteBufferPool(),
               OptionMap.EMPTY);
        }
    }

    private static final class SimpleTarget implements ProxyClient.ProxyTarget {
        final URI uri;
        SimpleTarget(URI uri) { this.uri = uri; }
    }

    // -----------------------------------------------------------------------
    // Stats
    // -----------------------------------------------------------------------

    public int getExactRouteCount() {
        return exactRoutes.size();
    }

    public int getWildcardRouteCount() {
        return wildcardRoutes.size();
    }
}
