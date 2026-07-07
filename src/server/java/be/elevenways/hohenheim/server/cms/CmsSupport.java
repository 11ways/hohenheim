package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.render.panel.RecordTabState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Shared helpers for the CMS resources: proxy reload and record-tab strips.
 * Audit writes moved to the framework activity log; mutation-driven reloads
 * ride {@code ProxyReloadHooks}.
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

    /** The record-tab strip for a site: edit form + domains + processes. */
    public static @NonNull List<RecordTabState> siteTabs(@NonNull Object siteId,
                                                                   @NonNull String activeSlug) {
        String base = "/admin/sites/" + siteId;
        List<RecordTabState> tabs = new ArrayList<>();
        tabs.add(new RecordTabState(base, Microcopy.of("cms.record.edit"), "pen", activeSlug.isEmpty()));
        tabs.add(new RecordTabState(base + "/page/domains", Microcopy.of("hohenheim.site.domains"),
            "at", "domains".equals(activeSlug)));
        tabs.add(new RecordTabState(base + "/page/processes", Microcopy.of("hohenheim.site.processes"),
            "microchip", "processes".equals(activeSlug)));
        return tabs;
    }

    /** The record-tab strip for a managed database: details form + restore. */
    public static @NonNull List<RecordTabState> databaseTabs(@NonNull Object databaseId,
                                                                       @NonNull String activeSlug) {
        String base = "/admin/databases/" + databaseId;
        List<RecordTabState> tabs = new ArrayList<>();
        tabs.add(new RecordTabState(base, Microcopy.of("cms.record.edit"), "pen", activeSlug.isEmpty()));
        tabs.add(new RecordTabState(base + "/page/restore", Microcopy.of("hohenheim.database.restore"),
            "upload", "restore".equals(activeSlug)));
        return tabs;
    }
}
