package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionWidget;
import be.elevenways.hohenheim.OnboardingWidget;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.i18n.Locale;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.setting.ContentLocales;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.DashboardPanelPeer;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.common.orm.query.rules.Rule;
import be.elevenways.zenit.common.orm.query.rules.RuleGroup;
import be.elevenways.zenit.common.orm.query.rules.RuleOperator;
import be.elevenways.zenit.widget.common.builtin.ChartWidget;
import be.elevenways.zenit.widget.common.builtin.ColumnsWidget;
import be.elevenways.zenit.widget.common.builtin.RecordsWidget;
import be.elevenways.zenit.widget.common.builtin.SectionWidget;
import be.elevenways.zenit.widget.common.builtin.StatWidget;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * The /admin landing dashboard: entity-count stat tiles plus the most
 * recent activity-log entries.
 */
public final class AdminDashboard extends DashboardPanelPeer {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "dashboard"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("dashboard").withFilter("scope", "admin"); }
    @Override public @NonNull String slug() { return "dashboard"; }
    @Override public @NonNull Icon icon() { return Icon.LAYOUT_DASH; }
    @Override public int navOrder() { return 1; }

    @Override
    public @NonNull WidgetTree widgets(@NonNull AccessContext accessContext) {
        WidgetTree stats = new WidgetTree(List.of(
            stat("site", "hohenheim.site", "sites", "globe"),
            stat("certificate", "hohenheim.certificate", "certificates", "lock"),
            stat("access_list", "hohenheim.access_list", "access-lists", "shield-halved")));

        // Security band: active-ban and events-today counts plus a per-day
        // event chart. The "today" rule is stamped per render (widgets() runs
        // per request), so it never goes stale.
        WidgetTree securityStats = new WidgetTree(List.of(
            new WidgetInstance(StatWidget.ID, Map.of(
                "label", localized("active_bans", "dashboard"),
                "source", "hohenheim.ban",
                "rules", RuleGroup.and(Rule.of("active", RuleOperator.IS_TRUE)),
                "icon", "ban",
                "link", "/admin/bans")),
            new WidgetInstance(StatWidget.ID, Map.of(
                "label", localized("events_today", "dashboard"),
                "source", "hohenheim.security_event",
                "rules", RuleGroup.and(Rule.of("day", RuleOperator.EQUALS,
                    LocalDate.now(ZoneOffset.UTC).toString())),
                "icon", "shield-halved",
                "link", "/admin/security-events"))));

        List<WidgetInstance> widgets = new ArrayList<>();
        if (Models.get(SiteModel.class).findActive().isEmpty()) {
            widgets.add(section(new WidgetInstance(OnboardingWidget.ID, Map.of())));
        }
        widgets.add(section(new WidgetInstance(AttentionWidget.ID, Map.of())
            .withData(AttentionCollector.collect())));
        widgets.add(section(new WidgetInstance(ColumnsWidget.ID, Map.of("column_count", 3), stats)));
        widgets.add(section(new WidgetInstance(ColumnsWidget.ID, Map.of("column_count", 2), securityStats)));
        widgets.add(section(new WidgetInstance(ChartWidget.ID, Map.of(
            "title", localized("security_events_30d", "dashboard"),
            "source", "hohenheim.security_event",
            "date_field", "last_at",
            "days", 30,
            "type", "area",
            "label", localized("plural", "security_event")))));
        widgets.add(section(new WidgetInstance(RecordsWidget.ID, Map.of(
                "title", localized("recent_activity", "dashboard"),
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
            "label", localized("plural", modelScope),
            "source", sourceToken,
            "icon", icon,
            "link", "/admin/" + resourceSlug));
    }

    private static @NonNull WidgetInstance section(@NonNull WidgetInstance child) {
        return new WidgetInstance(SectionWidget.ID, Map.of("css_class", "hh-dashboard-band"),
            new WidgetTree(List.of(child)));
    }

    private static @NonNull Map<Locale, String> localized(@NonNull String key, @NonNull String scope) {
        Microcopy copy = Microcopy.of(key).withFilter("scope", scope);
        Map<Locale, String> label = new LinkedHashMap<>();
        for (Locale locale : ContentLocales.get()) {
            label.put(locale, copy.resolve(LocaleChain.of(locale), Zenit.getMessageResolver()));
        }
        return label;
    }
}
