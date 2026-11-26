package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelContributions;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A DNS zone's Queries tab: the questions resolvers asked it over an hour, a day, a week or a month, against the period
 * before, per step and outcome, its most asked names, the names asked that do not exist, and who asks; for an
 * administrator.
 *
 * AIDEV-NOTE: the tab places {@code dns_zone_queries_read}'s own operation (TrafficReads.READ_ZONE), so only an
 * administrator reads it, as the tool. It is contributed to the zones' resource (PanelContributions, staged from this
 * class's load) rather than listed in DnsZoneParts, which another lane owns; a contributed tab stands after the
 * declared ones and before History, where the board draws it.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@BlastAutoLoad
public final class DnsZoneQueriesPage implements RecordTab.Rendered<Row> {

    /** The one tab every zones' resource is given. */
    public static final DnsZoneQueriesPage TAB = new DnsZoneQueriesPage();

    static {
        PanelContributions.INSTANCE.stageRecordTabs(() -> Map.of(DnsZoneModel.MODEL_ID, List.of(TAB)));
    }

    private DnsZoneQueriesPage() {
    }

    @Override public @NonNull Identifier id() { return HohenheimIds.id("dns_zone_queries"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.DNS_ZONE_QUERIES.of("queries_tab"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.QUERIES; }
    @Override public @NonNull Icon icon() { return Icon.of("chart-line"); }

    /** An administrator's only: a zone's questions name every customer's names. */
    @Override
    public boolean visibleFor(@NonNull Row zone, @NonNull AccessContext access) {
        return HohenheimAccess.isAdmin(access);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row zone) {
        Conduit conduit = request.conduit();
        TrafficScreens.declareOn(conduit);
        AccessContext viewer = AccessContext.of(conduit);
        TrafficScreens.Asked asked = TrafficScreens.Asked.of(conduit);
        TrafficReads.Read read = TrafficScreens.read(TrafficReads.READ_ZONE, viewer, zone, asked,
            TrafficScreens.ZONE_TABLES);
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, HohenheimMicrocopy.DNS_ZONE_QUERIES,
            zone.get(DnsZoneModel.ORIGIN)));
        vars.put("head", recordHead(conduit));
        vars.put("view", TrafficScreens.view(read, asked, TrafficReads.Reach.ZONE, CmsRoutes.subpage(
            request.panelSlug(), HohenheimSlugs.DNS_ZONES, zone.get(DnsZoneModel.ID), HohenheimSlugs.Tab.QUERIES),
            TrafficScreens.NOWHERE, viewer));
        return new RenderTemplateResult(HohenheimTemplateIds.RECORD_TRAFFIC, vars);
    }
}
