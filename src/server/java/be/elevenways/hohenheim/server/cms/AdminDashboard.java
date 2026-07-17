package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionWidget;
import be.elevenways.hohenheim.OnboardingWidget;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.i18n.Locale;
import be.elevenways.protoblast.common.i18n.Microcopy;
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

/**
 * The /admin landing dashboard: entity-count stat tiles plus the most
 * recent activity-log entries.
 */
public final class AdminDashboard extends DashboardPanelPeer {

    private static final Locale EN = Locale.of("en");

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "dashboard"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("dashboard").withFilter("scope", "admin"); }
    @Override public @NonNull String slug() { return "dashboard"; }
    @Override public @NonNull Icon icon() { return Icon.LAYOUT_DASH; }
    @Override public int navOrder() { return 1; }

    @Override
    public @NonNull WidgetTree widgets(@NonNull AccessContext accessContext) {
        WidgetTree stats = new WidgetTree(List.of(
            stat("Sites", "hohenheim.site"),
            stat("Certificates", "hohenheim.certificate"),
            stat("Access Lists", "hohenheim.access_list")));

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

    private static @NonNull WidgetInstance stat(@NonNull String label, @NonNull String sourceToken) {
        return new WidgetInstance(StatWidget.ID, Map.of(
            "label", Map.of(EN, label),
            "source", sourceToken));
    }
}
