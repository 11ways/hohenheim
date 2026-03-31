package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import io.undertow.client.ClientCallback;
import io.undertow.client.ClientConnection;
import io.undertow.client.UndertowClient;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ServerConnection;
import io.undertow.server.handlers.proxy.ProxyCallback;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.server.handlers.proxy.ProxyConnection;
import io.undertow.util.Headers;
import org.xnio.IoUtils;
import org.xnio.OptionMap;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Routes incoming proxy requests to upstream backends based on hostname matching.
 * Implements Undertow's ProxyClient for use with ProxyHandler.
 */
public class SiteDispatcher implements ProxyClient {

    private final UndertowClient client = UndertowClient.getInstance();

    // Exact hostname -> backend URI
    private final ConcurrentHashMap<String, URI> exactRoutes = new ConcurrentHashMap<>();

    // Wildcard patterns (*.example.com) -> backend URI
    private final ConcurrentHashMap<String, URI> wildcardRoutes = new ConcurrentHashMap<>();

    // Negative cache for hostnames that don't match any route
    private final ConcurrentHashMap<String, Long> negativeCache = new ConcurrentHashMap<>();
    private static final long NEGATIVE_CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes
    private static final int NEGATIVE_CACHE_MAX = 5000;

    private static final class SiteTarget implements ProxyTarget {
        final URI backendUri;
        final String siteName;

        SiteTarget(URI backendUri, String siteName) {
            this.backendUri = backendUri;
            this.siteName = siteName;
        }
    }

    /**
     * Reload all routes from the database.
     */
    public void reloadRoutes() {
        var ds = HohenheimDatabase.datasource();
        var siteModel = new SiteModel(ds);
        var domainModel = new SiteDomainModel(ds);

        exactRoutes.clear();
        wildcardRoutes.clear();
        negativeCache.clear();

        List<Row> sites = siteModel.findEnabled();

        for (Row site : sites) {
            String siteType = (String) site.get(SiteModel.SITE_TYPE);
            Object siteId = site.get(SiteModel.ID);
            String siteName = (String) site.get(SiteModel.NAME);

            // Get the backend address from site settings
            URI backendUri = resolveBackendUri(site);
            if (backendUri == null) continue;

            // Load domains for this site
            List<Row> domains = domainModel.findBySiteId(String.valueOf(siteId));

            for (Row domain : domains) {
                String hostname = (String) domain.get(SiteDomainModel.HOSTNAME);
                String matchType = (String) domain.get(SiteDomainModel.MATCH_TYPE);

                if (hostname == null || hostname.isEmpty()) continue;

                if ("wildcard".equals(matchType) || hostname.startsWith("*.")) {
                    wildcardRoutes.put(hostname.toLowerCase(), backendUri);
                } else {
                    exactRoutes.put(hostname.toLowerCase(), backendUri);
                }
            }
        }

        Blast.log("SiteDispatcher: loaded", exactRoutes.size(), "exact routes,",
                  wildcardRoutes.size(), "wildcard routes");
    }

    private URI resolveBackendUri(Row site) {
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) site.get(SiteModel.SETTINGS);

        if (settings == null) return null;

        String host = (String) settings.get("forward_host");
        Object portObj = settings.get("forward_port");
        String scheme = (String) settings.getOrDefault("forward_scheme", "http");

        if (host == null || host.isEmpty()) return null;

        int port = portObj instanceof Number ? ((Number) portObj).intValue() : 80;

        try {
            return new URI(scheme, null, host, port, "/", null, null);
        } catch (Exception e) {
            Blast.log("SiteDispatcher: invalid backend URI for site:", site.get(SiteModel.NAME), e.getMessage());
            return null;
        }
    }

    @Override
    public ProxyTarget findTarget(HttpServerExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst(Headers.HOST);
        if (host == null) return null;

        // Strip port
        int colon = host.indexOf(':');
        String hostname = (colon != -1 ? host.substring(0, colon) : host).toLowerCase();

        // Check negative cache
        Long cachedAt = negativeCache.get(hostname);
        if (cachedAt != null) {
            if (System.currentTimeMillis() - cachedAt < NEGATIVE_CACHE_TTL_MS) {
                return null;
            }
            negativeCache.remove(hostname);
        }

        // 1. Exact match
        URI backend = exactRoutes.get(hostname);
        if (backend != null) {
            return new SiteTarget(backend, hostname);
        }

        // 2. Wildcard match (*.example.com)
        for (Map.Entry<String, URI> entry : wildcardRoutes.entrySet()) {
            String pattern = entry.getKey();
            if (matchesWildcard(hostname, pattern)) {
                return new SiteTarget(entry.getValue(), hostname);
            }
        }

        // 3. No match -- add to negative cache
        if (negativeCache.size() < NEGATIVE_CACHE_MAX) {
            negativeCache.put(hostname, System.currentTimeMillis());
        }

        return null;
    }

    private boolean matchesWildcard(String hostname, String pattern) {
        // *.example.com matches sub.example.com but not example.com
        if (!pattern.startsWith("*.")) return false;
        String suffix = pattern.substring(1); // .example.com
        return hostname.endsWith(suffix) && hostname.length() > suffix.length();
    }

    @Override
    public void getConnection(ProxyTarget target, HttpServerExchange exchange,
                              ProxyCallback<ProxyConnection> callback,
                              long timeout, TimeUnit timeUnit) {
        SiteTarget siteTarget = (SiteTarget) target;
        URI uri = siteTarget.backendUri;

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

    public int getExactRouteCount() {
        return exactRoutes.size();
    }

    public int getWildcardRouteCount() {
        return wildcardRoutes.size();
    }
}
