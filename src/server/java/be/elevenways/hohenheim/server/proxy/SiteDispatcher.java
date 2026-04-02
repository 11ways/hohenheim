package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
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

import java.io.IOException;
import java.net.URI;
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

        RouteEntry(SiteRequestHandler handler, String siteName, Row domain) {
            this.handler = handler;
            this.siteName = siteName;

            String p = domain != null ? (String) domain.get(SiteDomainModel.PATH) : null;
            this.path = (p != null && !p.isEmpty()) ? p : null;

            Boolean sp = domain != null ? (Boolean) domain.get(SiteDomainModel.STRIP_PATH) : null;
            this.stripPath = Boolean.TRUE.equals(sp);

            Boolean fs = domain != null ? (Boolean) domain.get(SiteDomainModel.FORCE_SSL) : null;
            this.forceSsl = Boolean.TRUE.equals(fs);

            Boolean he = domain != null ? (Boolean) domain.get(SiteDomainModel.HSTS_ENABLED) : null;
            this.hstsEnabled = Boolean.TRUE.equals(he);

            Boolean hs = domain != null ? (Boolean) domain.get(SiteDomainModel.HSTS_SUBDOMAINS) : null;
            this.hstsSubdomains = Boolean.TRUE.equals(hs);

            Object ch = domain != null ? domain.get(SiteDomainModel.CUSTOM_HEADERS) : null;
            this.customHeaders = (ch instanceof Map) ? (Map<String, Object>) ch : null;
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

        // Destroy existing handlers
        for (RouteEntry entry : exactRoutes.values()) entry.handler.destroy();
        for (RouteEntry entry : wildcardRoutes.values()) entry.handler.destroy();

        exactRoutes.clear();
        wildcardRoutes.clear();
        negativeCache.clear();

        List<Row> sites = siteModel.findEnabled();

        for (Row site : sites) {
            String siteTypeStr = (String) site.get(SiteModel.SITE_TYPE);
            Object siteId = site.get(SiteModel.ID);
            String siteName = (String) site.get(SiteModel.NAME);

            SiteTypeHandler typeHandler = SiteTypes.getHandler(siteTypeStr);
            if (typeHandler == null) {
                Blast.log("SiteDispatcher: unknown site type", siteTypeStr, "for site", siteName);
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> settings = (Map<String, Object>) site.get(SiteModel.SETTINGS);
            if (settings == null) settings = Map.of();

            SiteRequestHandler requestHandler = typeHandler.createHandler(site, settings);
            List<Row> domains = domainModel.findBySiteId(((Number) siteId).intValue());

            for (Row domain : domains) {
                String hostname = (String) domain.get(SiteDomainModel.HOSTNAME);
                String matchType = (String) domain.get(SiteDomainModel.MATCH_TYPE);
                if (hostname == null || hostname.isEmpty()) continue;

                RouteEntry entry = new RouteEntry(requestHandler, siteName, domain);

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
                exchange.setStatusCode(404);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
                exchange.getResponseSender().send("<!DOCTYPE html><html><body><h1>404</h1><p>No site configured for this domain.</p></body></html>");
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

        // --- Custom headers ---
        if (entry.customHeaders != null) {
            for (Map.Entry<String, Object> h : entry.customHeaders.entrySet()) {
                requestHeaders.put(new HttpString(h.getKey()), String.valueOf(h.getValue()));
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
                exchange.setStatusCode(502);
                exchange.getResponseSender().send("Bad Gateway: " + e.getMessage());
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
            Blast.log("DOMAIN_MISS ip=" + ip + " hostname=" + hostname + " misses=" + misses);
        }

        // Evict old entries if the map grows too large
        if (ipReputation.size() > 50000) {
            long cutoff = System.currentTimeMillis() - 3600_000;
            ipReputation.entrySet().removeIf(e -> e.getValue().lastMissTime.get() < cutoff);
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
