package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.text.Csv;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.DataBucket;
import be.elevenways.zenit.common.data.DataSeries;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.time.OfficeZone;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The server's Traffic page for administrators: its Report (every site at once over an hour, a day, a week or a
 * month: the figures, the sites ranked, the steps, the top pages and the slowest ones, referrers, browsers and the
 * names nobody serves) with its steps as a file to download, and its Settings, the Traffic group of the settings page.
 *
 * AIDEV-NOTE: the report places {@code traffic_read}'s own every-site operation (TrafficReads.READ_SERVER). Settings is
 * a link to the one settings page opened on the Traffic group (HohenheimSettingsSections.INSTALLATION), never a copy
 * of its form. The tabs are TrafficActivityPage.tabs, the one list of them.
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
        List<ParameterDefinition<?>> parameters = new ArrayList<>(TrafficScreens.PARAMETERS);
        parameters.add(HohenheimParams.TRAFFIC_DOWNLOAD);
        return parameters;
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request) {
        Conduit conduit = request.conduit();
        AccessContext viewer = AccessContext.of(conduit);
        TrafficScreens.Asked asked = TrafficScreens.Asked.of(conduit);
        String panel = request.panelSlug();
        if (conduit.getParameter(HohenheimParams.TRAFFIC_DOWNLOAD) != null) {
            return download(TrafficScreens.read(TrafficReads.READ_SERVER, viewer, null, asked, Set.of()), viewer);
        }
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
        vars.put("tabs", TrafficActivityPage.tabs(panel, TrafficActivityPage.Tab.REPORT));
        vars.put("view", TrafficScreens.view(read, asked, TrafficReads.Reach.SERVER, CmsRoutes.list(panel, slug()),
            links, viewer, null));
        vars.put("downloadUrl", TrafficScreens.spanAt(CmsRoutes.list(panel, slug()), asked.window(), asked.until())
            .with(HohenheimParams.TRAFFIC_DOWNLOAD, "steps").toUrl());
        return new RenderTemplateResult(HohenheimTemplateIds.TRAFFIC_REPORT, vars);
    }

    /**
     * The span's steps as a spreadsheet's file: each step's start (office time), its requests per status class, the
     * bytes sent and the median and slowest 5 percent response times, every site added up.
     */
    private static @NonNull ActionResult<Object> download(TrafficReads.@NonNull Read read,
                                                         @NonNull AccessContext viewer) {
        List<List<String>> records = new ArrayList<>();
        List<String> head = new ArrayList<>();
        head.add(viewer.text(HohenheimMicrocopy.TRAFFIC_SCREEN.of("download_step")));
        List<DataSeries> columns = new ArrayList<>(read.steps().series());
        for (DataSeries series : columns) {
            head.add(series.label());
        }
        head.add(viewer.text(HohenheimMicrocopy.TRAFFIC_SCREEN.of("download_sent_bytes")));
        head.add(viewer.text(HohenheimMicrocopy.TRAFFIC_SCREEN.of("download_median_ms")));
        head.add(viewer.text(HohenheimMicrocopy.TRAFFIC_SCREEN.of("download_slowest_ms")));
        records.add(head);
        columns.addAll(read.sent().series());
        columns.addAll(read.times().series());
        List<DataBucket> steps = read.sent().series().getFirst().buckets();
        for (int step = 0; step < steps.size(); step++) {
            List<String> record = new ArrayList<>(columns.size() + 1);
            record.add(steps.get(step).key());
            for (DataSeries series : columns) {
                Double value = series.buckets().get(step).value();
                record.add(value == null ? "" : value == Math.rint(value) ? String.valueOf(Math.round(value))
                    : String.valueOf(value));
            }
            records.add(record);
        }
        byte[] body = (Csv.BOM + Csv.CSV.forSpreadsheets().write(records)).getBytes(StandardCharsets.UTF_8);
        String name = "traffic-" + fileTime(read.query().from()) + "-to-" + fileTime(read.query().to()) + ".csv";
        return HandlerSupport.downloadStream(Csv.CSV_MEDIA_TYPE, name, new ByteArrayInputStream(body), body.length);
    }

    /** @return a moment as a file name holds it: office time to the minute, {@code 2026-11-25T14-40} */
    private static @NonNull String fileTime(@NonNull Instant at) {
        return at.atZone(OfficeZone.office()).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES).toString()
            .replace(':', '-');
    }
}
