package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.server.page.ResourcePageEndpoints;
import be.elevenways.zenit.common.conduit.Conduit;

import java.util.LinkedHashMap;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Shared helpers for the CMS resources: proxy reload and coerced-map copies.
 * Record-tab strips come from the framework (RecordScopedPage.recordTabs);
 * mutation-driven reloads ride {@code ProxyReloadHooks}.
 *
 * AIDEV-NOTE: this used to state "audit writes ride the framework activity log" as an
 * invariant. It is a CONVENTION, not a guarantee: an action whose whole effect is a
 * {@code updateAll()} or a daemon call writes no activity row at all, however it is
 * wrapped (ActivityLog.withAction only RENAMES rows the model hooks already write).
 * An action that must be accountable records itself explicitly.
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

    /** The hosting panel's slug during a cms dispatch (subpages render under /admin AND /manage). */
    public static @NonNull String panelSlug(@NonNull Conduit conduit) {
        String slug = conduit.getParameter(ResourcePageEndpoints.PANEL_PARAM);
        return slug != null && !slug.isBlank() ? slug : "admin";
    }

    /** The hosting panel's URL base, e.g. "/admin" or "/manage". */
    public static @NonNull String panelBase(@NonNull Conduit conduit) {
        return "/" + panelSlug(conduit);
    }
}
