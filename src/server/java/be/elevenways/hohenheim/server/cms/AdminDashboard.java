package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionWidget;
import be.elevenways.hohenheim.OnboardingChecklistWidget;
import be.elevenways.hohenheim.OnboardingStep;
import be.elevenways.hohenheim.OnboardingWidget;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimRoles.Role;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.DashboardPanelPeer;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.rules.Rule;
import be.elevenways.zenit.common.orm.query.rules.RuleGroup;
import be.elevenways.zenit.common.orm.query.rules.RuleOperator;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.ChartWidget;
import be.elevenways.zenit.widget.common.builtin.ColumnsWidget;
import be.elevenways.zenit.widget.common.builtin.RecordsWidget;
import be.elevenways.zenit.widget.common.builtin.SectionWidget;
import be.elevenways.zenit.widget.common.builtin.StatWidget;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The /admin landing dashboard: entity-count stat tiles plus the most
 * recent activity-log entries.
 */
public final class AdminDashboard extends DashboardPanelPeer {

    /** The dashboard is the OPERATOR surface; every tile links into the admin panel. */
    private static final String ADMIN = "admin";

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "dashboard"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("dashboard").withFilter("scope", "admin"); }
    @Override public @NonNull String slug() { return "dashboard"; }
    @Override public @NonNull Icon icon() { return Icon.LAYOUT_DASH; }
    @Override public int navOrder() { return 1; }

    /** Role-gated bands: a tile must not link to a resource this install has no route for. */
    @Override
    public @NonNull WidgetTree widgets(@NonNull AccessContext accessContext) {
        boolean proxy = HohenheimRoles.enabled(Role.PROXY);
        boolean firewall = HohenheimRoles.enabled(Role.FIREWALL);

        WidgetTree stats = new WidgetTree(List.of(
            stat("site", "hohenheim.site", "sites", "globe"),
            stat("certificate", "hohenheim.certificate", "certificates", "lock"),
            stat("access_list", "hohenheim.access_list", "access-lists", "shield-halved")));

        // Security band: the active-ban count (event analytics live in
        // spamservice now, so bans are the only security records here).
        WidgetTree securityStats = new WidgetTree(List.of(
            new WidgetInstance(StatWidget.ID, Map.of(
                "label", HohenheimWidgetCopy.localized("active_bans", "dashboard"),
                "source", "hohenheim.ban",
                "rules", RuleGroup.and(Rule.of("active", RuleOperator.IS_TRUE)),
                "icon", "ban",
                // StatWidget's stored "link" is a String, so the typed target renders here.
                "link", CmsRoutes.list(ADMIN, "bans").toUrl()))));

        List<WidgetInstance> widgets = new ArrayList<>();

        // The readiness checklist leads, and RETIRES ITSELF: no dismissed flag, it is simply
        // absent once every step is done. Before this the only onboarding was the site CTA,
        // and nothing anywhere said a host must be preflighted and admitted before any
        // instance can deploy -- so the first session's natural arc (create -> deploy ->
        // silence) had no visible way forward.
        List<OnboardingStep> onboarding = OnboardingCollector.collect();
        if (OnboardingCollector.hasWork(onboarding)) {
            widgets.add(section(new WidgetInstance(OnboardingChecklistWidget.ID, Map.of())
                .withData(onboarding)));
        }
        if (proxy && Models.get(SiteModel.class).findActive().isEmpty()) {
            widgets.add(section(new WidgetInstance(OnboardingWidget.ID, Map.of())));
        }
        widgets.add(section(new WidgetInstance(AttentionWidget.ID, Map.of())
            .withData(AttentionCollector.collect())));
        if (proxy) {
            widgets.add(section(new WidgetInstance(ColumnsWidget.ID, Map.of("column_count", 3), stats)));
        }
        if (firewall) {
            widgets.add(section(new WidgetInstance(ColumnsWidget.ID, Map.of("column_count", 2), securityStats)));
            // AIDEV-NOTE: the 30-day bans chart used to live here and is deliberately gone.
            // On any fleet that is not under attack it is an all-zero series, i.e. ~450px of
            // flat line above the content an operator opened the page for. The count itself
            // stays, as the tile right above. If the trend is wanted, it belongs on the
            // firewall operator's own overview page, which they open on purpose.
        }
        widgets.add(section(new WidgetInstance(RecordsWidget.ID, Map.of(
                "title", HohenheimWidgetCopy.localized("recent_activity", "dashboard"),
                "source", "zenit.activity",
                "sort", "created_at",
                "descending", true,
                "limit", 10))));
        return new WidgetTree(widgets);
    }

    /** The tile label resolves the model's "plural" microcopy per content locale. */
    private static @NonNull WidgetInstance stat(@NonNull String modelScope, @NonNull String sourceToken,
                                                @NonNull String resourceSlug, @NonNull String icon) {
        return new WidgetInstance(StatWidget.ID, Map.of(
            "label", HohenheimWidgetCopy.localized("plural", modelScope),
            "source", sourceToken,
            "icon", icon,
            "link", CmsRoutes.list(ADMIN, resourceSlug).toUrl()));
    }

    private static @NonNull WidgetInstance section(@NonNull WidgetInstance child) {
        return new WidgetInstance(SectionWidget.ID, Map.of("css_class", "hh-dashboard-band"),
            new WidgetTree(List.of(child)));
    }

}
