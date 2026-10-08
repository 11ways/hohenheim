package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimWidgets;
import be.elevenways.hohenheim.app.UsageLine;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelDashboard;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.render.action.LinkActionState;
import be.elevenways.zenit.cms.common.resource.HealthTone;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The /manage landing (board Manage-Home): what of THIS principal's needs attention, their apps with the one verdict
 * each, and how much of the capped budgets they hold.
 *
 * AIDEV-NOTE: the attention items are the tenant's own apps' verdicts ({@link AppHealth} through
 * {@link AppDirectory}, the delegated words, which name no host), never {@code AttentionCollector.collect()}: that
 * collector is operator inventory by content (host names, "removed host #N", admin-route links). Before W9b this band
 * asked only the placement gate, so it read "All clear" while the tenant's app page said "Visitors get an error page".
 *
 * AIDEV-NOTE: no Recent band, unlike the board: the activity log is the operator's audit trail, which /manage never
 * shows (ManageHistoryHiddenTest). A tenant-safe history needs its own read first.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
public final class ManageDashboard extends PanelDashboard {

    @Override public @NonNull Identifier id() { return HohenheimIds.id("manage_dashboard"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("dashboard").withFilter("scope", "manage"); }
    @Override public @NonNull String slug() { return "dashboard"; }
    @Override public @NonNull Icon icon() { return Icon.LAYOUT_DASH; }
    @Override public int navOrder() { return 1; }

    @Override
    public @Nullable Microcopy description() {
        return CmsSupport.navHint("manage_dashboard");
    }

    /** Put something online, for a tenant who may start something from the catalog. */
    @Override
    public @NonNull List<LinkActionState> headerLinks(@NonNull PanelRequest request) {
        return AdminDashboard.putOnlineLinks(request, InstanceTemplateParts.offersTenantCatalog(request.access()));
    }

    @Override
    public @NonNull WidgetTree widgets(@NonNull AccessContext accessContext) {
        Panel manage = PanelRegistry.getBySlug(ManagePanel.SLUG);
        List<AppDirectory.App> apps = manage == null ? List.of() : AppDirectory.read(manage, accessContext);
        List<WidgetInstance> widgets = new ArrayList<>();
        widgets.add(AdminDashboard.section(new WidgetInstance(HohenheimWidgets.ATTENTION.id(), Map.of())
            .withData(attention(apps))));
        List<WidgetInstance> lower = new ArrayList<>(2);
        lower.add(new WidgetInstance(HohenheimWidgets.APPS.id(), Map.of())
            .withData(AdminDashboard.summaries(apps, accessContext)));
        List<UsageLine> usage = TenantUsage.of(accessContext);
        if (!usage.isEmpty()) {
            lower.add(new WidgetInstance(HohenheimWidgets.TENANT_USAGE.id(), Map.of()).withData(usage));
        }
        widgets.add(AdminDashboard.section(AdminDashboard.columns(lower)));
        return new WidgetTree(widgets);
    }

    /** Each app whose verdict is not fine, worded as its record page leads with it, linked to that page. */
    static @NonNull List<AttentionItem> attention(@NonNull List<AppDirectory.App> apps) {
        List<AttentionItem> items = new ArrayList<>();
        for (AppDirectory.App app : apps) {
            RecordHealth health = app.health();
            AttentionSeverity severity = severityOf(health.tone());
            if (severity == null) {
                continue;
            }
            items.add(new AttentionItem(severity, severity == AttentionSeverity.ERROR ? "circle-xmark"
                    : "triangle-exclamation", health.headline(), health.detail(), app.target(),
                AttentionItems.action("act_open_app", "name", app.name())));
        }
        return items;
    }

    /** @return the band's severity for a verdict tone, null for a verdict the band leaves out */
    private static @Nullable AttentionSeverity severityOf(@NonNull HealthTone tone) {
        return switch (tone) {
            case BROKEN -> AttentionSeverity.ERROR;
            case ATTENTION -> AttentionSeverity.WARNING;
            // Fine, or not known yet (a probe still to run): nothing for the tenant to act on.
            case OK, UNKNOWN -> null;
        };
    }
}
