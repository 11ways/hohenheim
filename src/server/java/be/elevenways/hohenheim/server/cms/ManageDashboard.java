package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionWidget;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.DashboardPanelPeer;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.RecordsWidget;
import be.elevenways.zenit.widget.common.builtin.SectionWidget;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The /manage landing dashboard: anything of THIS principal's needing attention
 * first, then their instances.
 *
 * AIDEV-NOTE: the attention items are a TENANT-SCOPED collection, never
 * {@code AttentionCollector.collect()} -- that collector is operator inventory by
 * content (host names, "removed host #N", admin-route links). Everything here is
 * derived from records inside the principal's own capability scope, links into
 * /manage routes, and never names a host (the panel blanks hosts everywhere).
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
public final class ManageDashboard extends DashboardPanelPeer {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "manage_dashboard"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("dashboard").withFilter("scope", "manage"); }
    @Override public @NonNull String slug() { return "dashboard"; }
    @Override public @NonNull Icon icon() { return Icon.LAYOUT_DASH; }
    @Override public int navOrder() { return 1; }

    @Override
    public @NonNull WidgetTree widgets(@NonNull AccessContext accessContext) {
        List<WidgetInstance> widgets = new ArrayList<>();
        widgets.add(section(new WidgetInstance(AttentionWidget.ID, Map.of())
            .withData(tenantAttention(accessContext))));
        widgets.add(section(new WidgetInstance(RecordsWidget.ID, Map.of(
            "title", HohenheimWidgetCopy.localized("your_instances", "manage_dashboard"),
            // The instance DEFAULT source carries the per-principal VIEW scope
            // (ManagePanel overrides it), so this list is tenant-correct with
            // nothing declared here.
            "source", "hohenheim.instance",
            "limit", 10))));
        return new WidgetTree(widgets);
    }

    /**
     * The declared preconditions a tenant can act on: instances whose next deploy
     * the placement gate will refuse. Asks the deploy lane's OWN gate
     * ({@code HostAdmission.instancePlacementRefusal}) behind its own predicate,
     * exactly like the instance overview, but the rendered sentence is this
     * panel's HOST-FREE one -- every placement refusal interpolates the host's
     * name, which is operator inventory.
     */
    private static @NonNull List<AttentionItem> tenantAttention(@NonNull AccessContext accessContext) {
        List<AttentionItem> items = new ArrayList<>();
        Criteria scope = HohenheimAccess.instanceScope(accessContext, HohenheimAccess.VIEW);
        var find = Models.get(InstanceModel.class).find()
            .where(InstanceModel.DELETED_AT.isNull());
        if (scope != null) {
            find = find.where(scope);
        }
        for (Row instance : find.all()) {
            Integer id = instance.get(InstanceModel.ID);
            try {
                InstanceKindHandler handler = InstanceKinds.getHandler(instance.get(InstanceModel.KIND));
                if (handler == null || !OwnedInstances.isPlacementGated(handler, instance)) {
                    continue;
                }
                Integer serverId = instance.get(InstanceModel.SERVER_ID);
                Microcopy refusal = HostAdmission.instancePlacementRefusal(
                    serverId == null ? 0 : serverId, handler.isolation(),
                    instance.get(InstanceModel.QUOTA_BUCKET));
                if (refusal == null) {
                    continue;
                }
                items.add(new AttentionItem("warning", "triangle-exclamation",
                    Microcopy.of("blocked_instance").withFilter("scope", "manage_dashboard")
                        .withArg("instance", String.valueOf(instance.get(InstanceModel.NAME))),
                    Microcopy.of("blocked_instance_detail").withFilter("scope", "manage_dashboard"),
                    CmsRoutes.subpage(ManagePanel.SLUG, "instances", id, InstanceOverviewPage.SLUG)));
            } catch (RuntimeException failed) {
                // A broken host/kind record must never kill the landing page, but
                // silence is not visibility either -- the log names the row.
                Blast.log("manage_dashboard.attention_failed", failed, "instance", id);
            }
        }
        return items;
    }

    private static @NonNull WidgetInstance section(@NonNull WidgetInstance child) {
        return new WidgetInstance(SectionWidget.ID, Map.of("css_class", "hh-dashboard-band"),
            new WidgetTree(List.of(child)));
    }

}
