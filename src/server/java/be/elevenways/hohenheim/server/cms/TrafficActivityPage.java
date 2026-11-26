package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.hohenheim.traffic.ActivityAsk;
import be.elevenways.hohenheim.traffic.ActivityPalette;
import be.elevenways.hohenheim.traffic.ActivityView;
import be.elevenways.plumage.component.NavTab;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The server's Traffic page's Activity tab for administrators: every site's requests live, as dots flying from their
 * visitors to their sites, coloured by how each was answered.
 *
 * AIDEV-NOTE: the picture is the live channel's (TrafficActivityHandler admits every site for an administrator, the
 * server Traffic read's gate); this page only names the sites it may show. {@link #tabs} is the one list of the
 * Traffic page's tabs, which the Report page draws too.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class TrafficActivityPage extends PanelPage {

    /** The Traffic page's tabs that are pages of their own. */
    public enum Tab {
        REPORT,
        ACTIVITY
    }

    @Override public @NonNull Identifier id() { return HohenheimIds.id("traffic_activity"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.TRAFFIC_ACTIVITY; }
    @Override public @NonNull Icon icon() { return Icon.of("chart-line"); }
    @Override public @NonNull NavGroup navGroup() { return NavGroup.DEFAULT; }
    @Override public boolean showInNav() { return false; }
    @Override public @Nullable String standsUnder() { return HohenheimSlugs.TRAFFIC; }

    /** A tab of the Traffic page, whose tabs lead back: its header is the Report tab's, a title with no trail. */
    @Override public boolean showsTrail() { return false; }

    @Override
    public @NonNull Microcopy label() {
        return HohenheimMicrocopy.TRAFFIC_SCREEN.of("traffic_page");
    }

    @Override
    public @Nullable Microcopy description() {
        return HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_page_hint");
    }

    /** An administrator's, as the Report: every site's traffic. */
    @Override
    public @Nullable Permission requiredPermission() {
        return HohenheimSources.ADMIN_ACCESS;
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", this.label().asString(request.conduit()));
        vars.put("lead", this.description());
        vars.put("tabs", tabs(request.panelSlug(), Tab.ACTIVITY));
        vars.put("view", view(ActivityAsk.every(), true, false));
        return new RenderTemplateResult(HohenheimTemplateIds.TRAFFIC_ACTIVITY, vars);
    }

    /** @return the Traffic page's tabs, {@code active} marked: Report, Activity, and Settings (the settings page) */
    public static @NonNull List<NavTab> tabs(@NonNull String panel, @NonNull Tab active) {
        return List.of(
            new NavTab(CmsRoutes.list(panel, HohenheimSlugs.TRAFFIC).toUrl(),
                HohenheimMicrocopy.TRAFFIC_SCREEN.of("report_tab"), "chart-line", active == Tab.REPORT, false),
            new NavTab(CmsRoutes.list(panel, HohenheimSlugs.TRAFFIC_ACTIVITY).toUrl(),
                HohenheimMicrocopy.TRAFFIC_SCREEN.of("activity_tab"), "heart-pulse", active == Tab.ACTIVITY, false),
            new NavTab(CmsRoutes.settingsAnchor(panel, SettingsPage.DEFAULT_SLUG,
                    HohenheimSettingsSections.INSTALLATION.anchorOf(HohenheimSettings.Traffic.GROUP)).toUrl(),
                HohenheimMicrocopy.TRAFFIC_SCREEN.of("settings_tab"), "gear", false, false));
    }

    /**
     * @param ask      what the picture's link asks for: every site, or the one site of {@code ask}'s value
     * @param visitors whether the picture shows visitors' addresses
     * @param strip    whether the last minute's bars stand under the dots (a wall screen)
     * @return the picture's view: its ask, and the names of the sites it shows, one query
     */
    static @NonNull ActivityView view(@NonNull ActivityAsk ask, boolean visitors, boolean strip) {
        Map<String, String> names = new LinkedHashMap<>();
        boolean one = ask.kind() == ActivityAsk.Kind.SITE;
        Integer only = one ? HohenheimTraffic.siteOf(Objects.requireNonNull(ask.value())) : null;
        // One site's ask names that site alone; one this build cannot read names none, never every site.
        List<Row> sites = !one ? Models.get(SiteModel.class).find().orderBy(SiteModel.NAME, SortOrder.ASC).all()
            : only == null ? List.of() : Models.get(SiteModel.class).find().where(SiteModel.ID.eq(only)).all();
        for (Row site : sites) {
            names.put(HohenheimTraffic.sourceOf(site.get(SiteModel.ID)), site.get(SiteModel.NAME));
        }
        return new ActivityView(ask.spelled(), new ArrayList<>(names.keySet()), names, HohenheimTraffic.UNSERVED,
            visitors, strip, ActivityPalette.legend());
    }
}
