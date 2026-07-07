package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.render.panel.RecordTabState;

import java.util.ArrayList;
import java.util.List;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Shared helpers for the CMS resources: audit-log writes and proxy reload.
 */
public final class CmsSupport {

    private CmsSupport() {
    }

    /** Write an audit-log entry attributed to the acting principal. */
    public static void audit(@NonNull AccessContext access, @NonNull String action,
                             @NonNull String resourceType, @Nullable Object resourceId,
                             @Nullable String resourceName) {
        AuditLogModel model = Models.get(AuditLogModel.class);
        Row row = model.createEmptyRow();
        Principal principal = access.principal();
        if (principal != null && !principal.isAnonymous()) {
            row.set(AuditLogModel.USER_ID, (int) principal.id());
            row.set(AuditLogModel.USER_LABEL, principal.displayName());
        }
        row.set(AuditLogModel.ACTION, action);
        row.set(AuditLogModel.RESOURCE_TYPE, resourceType);
        row.set(AuditLogModel.RESOURCE_ID, resourceId != null ? String.valueOf(resourceId) : null);
        row.set(AuditLogModel.RESOURCE_NAME, resourceName);
        model.save(row);
    }

    /** Rebuild the proxy routing table from the current configuration. */
    public static void reloadProxy() {
        var proxy = ServerMain.getProxyServer();
        if (proxy != null) {
            proxy.reload();
        }
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
