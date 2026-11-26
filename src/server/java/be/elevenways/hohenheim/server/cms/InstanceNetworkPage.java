package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.instance.AppNetworkView;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.plumage.chart.ChartPoint;
import be.elevenways.plumage.chart.ChartSeries;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.DataBucket;
import be.elevenways.zenit.common.data.DataSeries;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StorageKeyed;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.common.routing.MalformedQuery;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.traffic.common.TrafficGranularity;
import be.elevenways.zenit.traffic.common.TrafficMeasure;
import be.elevenways.zenit.traffic.common.TrafficWindow;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An app's Network tab: what it received and sent over a span per step, and what its sites answered visitors, read
 * through the read an assistant's {@code app_network_read} makes (AppNetworkReads).
 *
 * AIDEV-NOTE: the chart plots the bytes themselves and says they are sizes (pl-chart's valueKind), so every tick and
 * reading words them as the reader reads a size. A step is named as the traffic engine names it for the reader, a span
 * as the traffic screens name it (TrafficReads.periodLabel).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstanceNetworkPage implements RecordTab.Rendered<Row> {

    private static final ParameterDefinition<String> PERIOD = ParameterDefinition.builder(String.class)
        .name(TrafficReads.PERIOD.getName()).stringResolver(value -> value).build();

    @Override public @NonNull Identifier id() { return HohenheimIds.id("instance_network"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.INSTANCE.of("network_tab"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.NETWORK; }
    @Override public @NonNull Icon icon() { return Icon.of("network-wired"); }

    /** Where the app's driver reads its counters, for whoever manages the app: the read's own gate. */
    @Override
    public boolean visibleFor(@NonNull Row app, @NonNull AccessContext access) {
        return AppNetworkReads.counts(app) && HohenheimAccess.reachesRecord(access, InstanceModel.MODEL_ID,
            app.get(InstanceModel.ID), HohenheimCapabilities.MANAGE);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row app) {
        Conduit conduit = request.conduit();
        Endpoint.declareQueryOn(conduit, MalformedQuery.ABSENT, List.of(PERIOD));
        TrafficWindow window = TrafficReads.windowOf(conduit.getParameter(PERIOD));
        AppNetworkReads.Read read = AppNetworkReads.read(app,
            new AppNetworkReads.ReadInput(window.storageKey(), null), AccessContext.of(conduit));
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, HohenheimMicrocopy.APP_NETWORK, app.get(InstanceModel.NAME)));
        vars.put("head", recordHead(conduit));
        vars.put("view", view(conduit, read, window));
        return new RenderTemplateResult(HohenheimTemplateIds.INSTANCE_NETWORK, vars);
    }

    private static @NonNull AppNetworkView view(@NonNull Conduit conduit, AppNetworkReads.@NonNull Read read,
                                                @NonNull TrafficWindow chosen) {
        List<AppNetworkView.Span> spans = new ArrayList<>();
        for (TrafficWindow window : TrafficWindow.values()) {
            spans.add(new AppNetworkView.Span(window.storageKey(),
                TrafficReads.periodLabel(window),
                "?" + PERIOD.getName() + "=" + window.storageKey(), window == chosen));
        }
        List<ChartSeries> chart = new ArrayList<>();
        for (DataSeries series : read.steps().series()) {
            TrafficMeasure measure = Objects.requireNonNull(StorageKeyed.findOrNull(
                Arrays.asList(TrafficMeasure.values()), series.key()), "the bytes measure draws its two measures");
            List<ChartPoint> points = new ArrayList<>(series.buckets().size());
            for (DataBucket bucket : series.buckets()) {
                points.add(new ChartPoint(bucket.label(), bucket.value()));
            }
            chart.add(new ChartSeries(series.key(), measure.label(), points));
        }
        List<AppNetworkView.Site> sites = new ArrayList<>();
        for (AppNetworkReads.SiteTraffic site : read.sites()) {
            sites.add(new AppNetworkView.Site(String.valueOf(site.siteId()), site.name(),
                HohenheimMicrocopy.APP_NETWORK.of("app_network_requests").withArg("count", site.requests()),
                size(site.bytesSent())));
        }
        return new AppNetworkView(spans,
            HohenheimMicrocopy.APP_NETWORK.of("app_network_range").withArg("from", read.query().from())
                .withArg("until", read.query().to()),
            size(read.totals().bytesReceived()).asString(conduit), size(read.totals().bytesSent()).asString(conduit),
            size(read.toVisitors()).asString(conduit), chart, chartLead(read.query().granularity()),
            read.counted(), sites);
    }

    /** @return a size in the reader's words */
    private static @NonNull Microcopy size(long bytes) {
        return HohenheimMicrocopy.APP_NETWORK.of("app_network_size").withArg("bytes", bytes);
    }

    /** @return how the chart counts at its step */
    private static @NonNull Microcopy chartLead(@NonNull TrafficGranularity step) {
        return HohenheimMicrocopy.APP_NETWORK.of(switch (step) {
            case MINUTE -> "app_network_chart_lead_minute";
            case HOUR -> "app_network_chart_lead_hour";
            case DAY -> "app_network_chart_lead_day";
        });
    }
}
