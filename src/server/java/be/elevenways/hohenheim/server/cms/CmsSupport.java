package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.protoblast.common.i18n.Microcopy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Shared helpers for the CMS resources: proxy reload and coerced-map copies.
 * Record-tab strips come from the framework (RecordScopedPage.recordTabs);
 * audit writes ride the framework activity log; mutation-driven reloads ride
 * {@code ProxyReloadHooks}.
 */
public final class CmsSupport {

    private CmsSupport() {
    }

    /** Rebuild the proxy routing table from the current configuration. */
    public static void reloadProxy() {
        var proxy = ServerMain.getProxyServer();
        if (proxy != null) {
            proxy.reload();
        }
    }

    /** The coerced maps the CMS hands to persist/update are immutable; copy before staging values. */
    public static @NonNull Map<String, Object> mutable(@NonNull Map<String, Object> coerced) {
        return new LinkedHashMap<>(coerced);
    }

    /** A violation-scoped microcopy message (catalog entries carry {@code scope=violations}). */
    public static @NonNull Microcopy violationText(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }

}
