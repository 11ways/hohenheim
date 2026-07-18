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
import be.elevenways.zenit.widget.common.builtin.ColumnsWidget;
import be.elevenways.zenit.widget.common.builtin.RecordsWidget;
import be.elevenways.zenit.widget.common.builtin.StatWidget;
import org.checkerframework.checker.nullness.qual.NonNull;

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
            stat("site", "hohenheim.site"),
            stat("certificate", "hohenheim.certificate"),
            stat("access_list", "hohenheim.access_list")));

        List<WidgetInstance> widgets = new ArrayList<>();
        if (Models.get(SiteModel.class).findActive().isEmpty()) {
            widgets.add(new WidgetInstance(OnboardingWidget.ID, Map.of()));
        }
        widgets.add(new WidgetInstance(AttentionWidget.ID, Map.of()));
        widgets.add(new WidgetInstance(ColumnsWidget.ID, Map.of("column_count", 3), stats));
        widgets.add(new WidgetInstance(RecordsWidget.ID, Map.of(
                "source", "zenit.activity",
                "sort", "created_at",
                "descending", true,
                "limit", 10)));
        return new WidgetTree(widgets);
    }

    /** The tile label resolves the model's "plural" microcopy per content locale. */
    private static @NonNull WidgetInstance stat(@NonNull String modelScope, @NonNull String sourceToken) {
        Microcopy plural = Microcopy.of("plural").withFilter("scope", modelScope);
        Map<Locale, String> label = new LinkedHashMap<>();
        for (Locale locale : ContentLocales.get()) {
            label.put(locale, plural.resolve(LocaleChain.of(locale), Zenit.getMessageResolver()));
        }
        return new WidgetInstance(StatWidget.ID, Map.of(
            "label", label,
            "source", sourceToken));
    }
}
