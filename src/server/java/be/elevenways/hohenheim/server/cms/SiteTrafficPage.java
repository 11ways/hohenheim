package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * A site's Traffic tab: its counted requests over an hour, a day, a week or a month, against the period before, per
 * step and status class, its response times and its top tables, for whoever manages the site.
 *
 * AIDEV-NOTE: the tab places {@code traffic_read}'s own operation (TrafficReads.READ_SITE) for its viewer, so a
 * customer reads the sites they manage and is refused another's exactly as the tool refuses him. A path leads to the
 * site's access log narrowed to it (the part before an id where the path counts one as {@code :id}).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SiteTrafficPage implements RecordTab.Rendered<Row> {

    @Override public @NonNull Identifier id() { return HohenheimIds.id("site_traffic"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.TRAFFIC_SCREEN.of("traffic_tab"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.TRAFFIC; }
    @Override public @NonNull Icon icon() { return Icon.of("chart-line"); }

    /** A site answering HTTP itself has one, for whoever manages it: a stream or a passthrough counts no requests. */
    @Override
    public boolean visibleFor(@NonNull Row site, @NonNull AccessContext access) {
        return SiteParts.servesHttp(site) && SiteOperationHandlers.reaches(site, access);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row site) {
        Conduit conduit = request.conduit();
        TrafficScreens.declareOn(conduit);
        AccessContext viewer = AccessContext.of(conduit);
        TrafficScreens.Asked asked = TrafficScreens.Asked.of(conduit);
        int siteId = site.get(SiteModel.ID);
        String panel = request.panelSlug();
        TrafficReads.Read read = TrafficScreens.read(TrafficReads.READ_SITE, viewer, site, asked,
            TrafficScreens.SITE_TABLES);
        TrafficScreens.Links links = new TrafficScreens.Links() {
            @Override
            public @Nullable String site(@NonNull String source) {
                return null;
            }

            @Override
            public @Nullable String path(@NonNull String source, @NonNull String path) {
                return SiteAccessLogPage.pathLines(panel, siteId, path);
            }
        };
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, HohenheimMicrocopy.TRAFFIC_SCREEN, site.get(SiteModel.NAME)));
        vars.put("head", recordHead(conduit));
        vars.put("view", TrafficScreens.view(read, asked, TrafficReads.Reach.SITE,
            CmsRoutes.subpage(panel, HohenheimSlugs.SITES, siteId, HohenheimSlugs.Tab.TRAFFIC), links, viewer, null));
        return new RenderTemplateResult(HohenheimTemplateIds.RECORD_TRAFFIC, vars);
    }
}
