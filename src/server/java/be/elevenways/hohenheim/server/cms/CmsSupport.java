package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.common.conduit.Conduit;

import java.util.LinkedHashMap;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

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

    /**
     * THE page title of a record subpage: microcopy key {@code page_title} in the page's
     * OWN scope, carrying the record's name as {@code {$name}}.
     *
     * AIDEV-NOTE: one key name in one place per page, because the alternative is what
     * this replaced -- eleven call sites concatenating an English literal onto a record
     * name, one of which was written AFTER the defect had already been listed. The
     * `{page}_title`-in-a-shared-scope spelling the first three fixed pages used is gone:
     * two pages of the same record type (a zone's records and its zone file) would have
     * had to share one scope, and the whole point of the per-page scope is that each
     * surface owns its own short keys. `page_title` is guarded against reintroduction by
     * PageTitleLocalizationTest.
     *
     * @param scope the page's microcopy scope, e.g. {@code instance_device}
     * @param name the record's own name, never translated (it is user data)
     */
    public static @NonNull String pageTitle(@NonNull Conduit conduit, @NonNull String scope,
                                            @Nullable Object name) {
        return Microcopy.of("page_title").withFilter("scope", scope)
            .withArg("name", String.valueOf(name))
            .resolve(conduit.getLocales(), conduit.getMessageResolver());
    }

    /** The hosting panel's slug during a cms dispatch (subpages render under /admin AND /manage). */
    public static @NonNull String panelSlug(@NonNull Conduit conduit) {
        String slug = conduit.getParameter(CmsEndpoints.PANEL_PARAM);
        return slug != null && !slug.isBlank() ? slug : "admin";
    }

    /** The hosting panel's URL base, e.g. "/admin" or "/manage". */
    public static @NonNull String panelBase(@NonNull Conduit conduit) {
        return "/" + panelSlug(conduit);
    }
}
