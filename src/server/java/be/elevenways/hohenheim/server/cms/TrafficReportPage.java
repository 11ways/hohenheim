package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.plumage.component.NavTab;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The server's Traffic page for administrators: its Report (every site at once over an hour, a day, a week or a
 * month: the figures, the sites ranked, the steps, the top pages and the slowest ones, referrers, browsers and the
 * names nobody serves) and its Settings, the Traffic group of the settings page.
 *
 * AIDEV-NOTE: the report places {@code traffic_read}'s own every-site operation (TrafficReads.READ_SERVER). Settings is
 * a link to the one settings page opened on the Traffic group (HohenheimSettingsSections.INSTALLATION), never a copy
 * of its form. The board's Activity tab (the live picture) is another builder's: it joins this strip when it lands.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class TrafficReportPage extends PanelPage {

    @Override public @NonNull Identifier id() { return HohenheimIds.id("traffic"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.TRAFFIC; }
    @Override public @NonNull Icon icon() { return Icon.of("chart-line"); }
    @Override public @NonNull NavGroup navGroup() { return NavGroup.DEFAULT; }
    @Override public int navOrder() { return 65; }

    @Override
    public @NonNull Microcopy label() {
        return HohenheimMicrocopy.TRAFFIC_SCREEN.of("traffic_page");
    }

    @Override
    public @Nullable Microcopy description() {
        return HohenheimMicrocopy.TRAFFIC_SCREEN.of("traffic_page_hint");
    }

    /** An administrator's: every site's traffic, the names nobody serves among them. */
    @Override
    public @Nullable Permission requiredPermission() {
        return HohenheimSources.ADMIN_ACCESS;
    }

    @Override
    public @NonNull List<ParameterDefinition<?>> queryParameters() {
        return TrafficScreens.PARAMETERS;
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request) {
        Conduit conduit = request.conduit();
        AccessContext viewer = AccessContext.of(conduit);
        TrafficScreens.Asked asked = TrafficScreens.Asked.of(conduit);
        String panel = request.panelSlug();
        TrafficReads.Read read = TrafficScreens.read(TrafficReads.READ_SERVER, viewer, null, asked,
            TrafficScreens.SERVER_TABLES);
        TrafficScreens.Links links = new TrafficScreens.Links() {
            @Override
            public @Nullable String site(@NonNull String source) {
                Integer siteId = HohenheimTraffic.siteOf(source);
                return siteId == null ? null
                    : CmsRoutes.subpage(panel, HohenheimSlugs.SITES, siteId, HohenheimSlugs.Tab.TRAFFIC).toUrl();
            }

            @Override
            public @Nullable String path(@NonNull String source, @NonNull String path) {
                Integer siteId = HohenheimTraffic.siteOf(source);
                return siteId == null ? null : SiteAccessLogPage.pathLines(panel, siteId, path);
            }
        };
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", label().asString(conduit));
        vars.put("lead", description());
        vars.put("tabs", List.of(
            new NavTab(CmsRoutes.list(panel, slug()).toUrl(), HohenheimMicrocopy.TRAFFIC_SCREEN.of("report_tab"),
                "chart-line", true, false),
            new NavTab(CmsRoutes.settingsAnchor(panel, SettingsPage.DEFAULT_SLUG,
                    HohenheimSettingsSections.INSTALLATION.anchorOf(HohenheimSettings.Traffic.GROUP)).toUrl(),
                HohenheimMicrocopy.TRAFFIC_SCREEN.of("settings_tab"), "gear", false, false)));
        vars.put("view", TrafficScreens.view(read, asked, TrafficReads.Reach.SERVER, CmsRoutes.list(panel, slug()),
            links, viewer));
        return new RenderTemplateResult(HohenheimTemplateIds.TRAFFIC_REPORT, vars);
    }
}
