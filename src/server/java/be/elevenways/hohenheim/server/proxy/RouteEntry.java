package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A route entry combines the site handler with domain-level configuration.
 */
final class RouteEntry {

    /** Default absolute request lifetime for proxied exchanges. */
    static final int DEFAULT_REQUEST_TIMEOUT_MS = 30_000;

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
    final boolean hasAccessList;
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

        this.path = SiteDispatcher.normalizeRoutePath(
            domain != null ? domain.get(SiteDomainModel.PATH) : null);
        this.stripPath = domain != null && Boolean.TRUE.equals(domain.get(SiteDomainModel.STRIP_PATH));
        this.forceSsl = domain != null && Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL));
        this.requestDelayMs = parsePositiveInt(siteSettings != null ? siteSettings.get("delay") : null);
        this.requestTimeoutMs = parseRequestTimeout(
            siteSettings != null ? siteSettings.get("request_timeout") : null);
        this.hstsEnabled = domain != null && Boolean.TRUE.equals(domain.get(SiteDomainModel.HSTS_ENABLED));
        this.hstsSubdomains = domain != null && Boolean.TRUE.equals(domain.get(SiteDomainModel.HSTS_SUBDOMAINS));
        this.customHeaders = parseHeaderRules(domain != null ? domain.get(SiteDomainModel.CUSTOM_HEADERS) : null);
        this.responseHeaders = parseHeaderRules(domain != null ? domain.get(SiteDomainModel.RESPONSE_HEADERS) : null);
        this.listenOnAddresses = ListenerAddressMatcher.parse(
            domain != null ? domain.get(SiteDomainModel.LISTEN_ON) : null);

        this.hasAccessList = accessList != null;
        if (accessList != null) {
            // AIDEV-NOTE: a NULL/blank satisfy column must never disable the list.
            // hasAccessList() used to be "satisfy != null", so one nullable column
            // switched off ALL of the control (allowed/denied IPs AND basic auth).
            // The ROW is the operator's statement that this site is guarded; an
            // absent satisfy just defaults to the model/DB default ("any").
            String satisfy = accessList.get(AccessListModel.SATISFY);
            this.accessListSatisfy = satisfy != null && !satisfy.isBlank()
                ? satisfy : AccessListModel.SATISFY_ANY;
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
        return hasAccessList;
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
        return ListenerAddressMatcher.matches(listenOnAddresses, listenerIp);
    }

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
}
