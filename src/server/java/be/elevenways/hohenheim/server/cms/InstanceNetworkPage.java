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
import be.elevenways.protoblast.common.i18n.wording.ByteUnit;
import be.elevenways.protoblast.common.i18n.wording.Wording;
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
import be.elevenways.zenit.common.time.OfficeZone;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.traffic.common.TrafficGranularity;
import be.elevenways.zenit.traffic.common.TrafficMeasure;
import be.elevenways.zenit.traffic.common.TrafficWindow;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * AIDEV-NOTE: the chart plots megabytes, named in its lead: pl-chart words money and hours but no size yet, and one
 * fixed unit keeps a span's steps comparable. A step's label is the reader's own time or date of its start.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstanceNetworkPage implements RecordTab.Rendered<Row> {

    /** The unit the chart plots in. */
    static final ByteUnit CHART_UNIT = ByteUnit.MEGABYTE;

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
            new AppNetworkReads.ReadInput(window.storageKey(), null));
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
                spanLabel(window),
                "?" + PERIOD.getName() + "=" + window.storageKey(), window == chosen));
        }
        TrafficGranularity step = read.query().granularity();
        List<ChartSeries> chart = new ArrayList<>();
        for (DataSeries series : read.steps().series()) {
            TrafficMeasure measure = Objects.requireNonNull(StorageKeyed.findOrNull(
                Arrays.asList(TrafficMeasure.values()), series.key()), "the bytes measure draws its two measures");
            List<ChartPoint> points = new ArrayList<>(series.buckets().size());
            for (DataBucket bucket : series.buckets()) {
                Double value = bucket.value();
                points.add(new ChartPoint(stepLabel(conduit, step, bucket.key()),
                    value == null ? null : value / CHART_UNIT.factor()));
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
            size(read.toVisitors()).asString(conduit), chart,
            chartLead(step).withArg("unit", Wording.unit(CHART_UNIT)),
            read.counted(), sites);
    }

    /** @return a size in the reader's words */
    private static @NonNull Microcopy size(long bytes) {
        return HohenheimMicrocopy.APP_NETWORK.of("app_network_size").withArg("bytes", bytes);
    }

    private static @NonNull Microcopy spanLabel(@NonNull TrafficWindow window) {
        return HohenheimMicrocopy.APP_NETWORK.of(switch (window) {
            case HOUR -> "app_network_span_hour";
            case DAY -> "app_network_span_day";
            case WEEK -> "app_network_span_week";
            case MONTH -> "app_network_span_month";
        });
    }

    /** @return how the chart counts at its step, its unit still to be named */
    private static @NonNull Microcopy chartLead(@NonNull TrafficGranularity step) {
        return HohenheimMicrocopy.APP_NETWORK.of(switch (step) {
            case MINUTE -> "app_network_chart_lead_minute";
            case HOUR -> "app_network_chart_lead_hour";
            case DAY -> "app_network_chart_lead_day";
        });
    }

    /**
     * A step's start as the reader reads it: the time of a minute, the date and time of an hour, the date of a day.
     *
     * AIDEV-NOTE: a chart names its points by their label, so every label of a span differs: a day's span holds its
     * first hour twice (the hour it starts inside and the same hour of the next day), so an hour names its date too.
     */
    private static @NonNull String stepLabel(@NonNull Conduit conduit, @NonNull TrafficGranularity step,
                                             @NonNull String key) {
        Microcopy label = switch (step) {
            case MINUTE -> HohenheimMicrocopy.APP_NETWORK.of("app_network_step_time").withArg("at", startOf(key));
            case HOUR -> HohenheimMicrocopy.APP_NETWORK.of("app_network_step_hour").withArg("at", startOf(key));
            case DAY -> HohenheimMicrocopy.APP_NETWORK.of("app_network_step_day").withArg("day", LocalDate.parse(key));
        };
        return label.asString(conduit);
    }

    /** @return the start of a minute or an hour, whose key is its local date and time on the store's office clock */
    private static @NonNull Instant startOf(@NonNull String key) {
        return LocalDateTime.parse(key).atZone(OfficeZone.office()).toInstant();
    }
}
