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
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.data.NoticeData;
import be.elevenways.zenit.widget.common.builtin.AlertVariant;
import be.elevenways.zenit.widget.common.builtin.AlertWidget;
import be.elevenways.zenit.widget.common.builtin.ChartWidget;
import be.elevenways.zenit.widget.common.builtin.ColumnsWidget;
import be.elevenways.zenit.widget.common.builtin.RecordsWidget;
import be.elevenways.zenit.widget.common.builtin.SectionWidget;
import be.elevenways.zenit.widget.common.builtin.StatWidget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

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

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "admin");
    }
    /** Role-gated bands: a tile must not link to a resource this install has no route for. */
    @Override
    public @NonNull WidgetTree widgets(@NonNull AccessContext accessContext) {
        boolean proxy = HohenheimRoles.enabled(Role.PROXY);
        boolean firewall = HohenheimRoles.enabled(Role.FIREWALL);

        // AIDEV-NOTE: ONE stat grid, whatever the role mix. The firewall tile used to be a
        // band of its own with its own column count, so a proxy+firewall install rendered
        // four tiles as a 3-wide grid followed by a lone half-width card underneath -- two
        // grids the operator reads as two unrelated groups. Roles decide WHICH tiles exist,
        // never how many grids there are.
        List<WidgetInstance> tiles = new ArrayList<>();
        if (proxy) {
            tiles.add(stat("site", "hohenheim.site", "sites", "globe"));
            tiles.add(stat("certificate", "hohenheim.certificate", "certificates", "lock"));
            tiles.add(stat("access_list", "hohenheim.access_list", "access-lists", "shield-halved"));
        }
        if (firewall) {
            // The active-ban count (event analytics live in spamservice now, so bans are
            // the only security records here).
            tiles.add(new WidgetInstance(StatWidget.ID, Map.of(
                "label", HohenheimWidgetCopy.localized("active_bans", "dashboard"),
                "source", "hohenheim.ban",
                "rules", RuleGroup.and(Rule.of("active", RuleOperator.IS_TRUE)),
                "icon", "ban",
                // StatWidget's stored "link" is a String, so the typed target renders here.
                "link", CmsRoutes.list(ADMIN, "bans").toUrl())));
        }

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
        if (!tiles.isEmpty()) {
            widgets.add(section(new WidgetInstance(ColumnsWidget.ID,
                Map.of("column_count", Math.min(tiles.size(), 4)), new WidgetTree(tiles))));
            // AIDEV-NOTE: the 30-day bans chart used to live beside these and is deliberately
            // gone. On any fleet that is not under attack it is an all-zero series, i.e. ~450px
            // of flat line above the content an operator opened the page for. The count itself
            // stays, as a tile. If the trend is wanted, it belongs on the firewall operator's
            // own overview page, which they open on purpose.
        }
        widgets.add(section(new WidgetTree(recentActivity(accessContext))));
        return new WidgetTree(widgets);
    }

    /**
     * The recent-activity band: the ten latest log rows, under the SAME notice
     * {@code /admin/activity} renders while recording is switched off.
     *
     * AIDEV-NOTE: without it this band shows the generic "no records found" -- which reads
     * as "the fleet was quiet", when the truth is that nothing is being written down. The
     * fact and the sentence come from {@link AdminActivityResource#recordingNotice()}
     * (the framework resource's own), never from a second read of {@code activity.enabled}.
     * The notice is resolved here because {@code NoticeData} is display-ready strings; with
     * no conduit there is no locale chain and no viewer either, so the band is just the list.
     */
    private static @NonNull List<WidgetInstance> recentActivity(@NonNull AccessContext accessContext) {

        List<WidgetInstance> band = new ArrayList<>(2);
        Microcopy notice = AdminActivityResource.recordingNotice();
        Conduit conduit = accessContext.conduit();

        if (notice != null && conduit != null) {
            band.add(new WidgetInstance(AlertWidget.ID,
                    Map.of("variant", AlertVariant.WARNING.token()))
                .withData(NoticeData.of(
                    notice.resolve(conduit.getLocales(), conduit.getMessageResolver()), null)));
        }

        band.add(new WidgetInstance(RecordsWidget.ID, Map.of(
            "title", HohenheimWidgetCopy.localized("recent_activity", "dashboard"),
            "source", CmsSupport.ACTIVITY_SOURCE,
            "sort", "created_at",
            "descending", true,
            "limit", 10)));

        return band;
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
        return section(new WidgetTree(List.of(child)));
    }

    private static @NonNull WidgetInstance section(@NonNull WidgetTree children) {
        return new WidgetInstance(SectionWidget.ID, Map.of("css_class", "hh-dashboard-band"),
            children);
    }

}
