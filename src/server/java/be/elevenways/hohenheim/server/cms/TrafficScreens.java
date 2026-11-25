package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.server.dns.DnsTraffic;
import be.elevenways.hohenheim.traffic.TrafficView;
import be.elevenways.plumage.chart.ChartPoint;
import be.elevenways.plumage.chart.ChartSeries;
import be.elevenways.plumage.chart.ChartType;
import be.elevenways.plumage.component.NavTab;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.wording.DateStyle;
import be.elevenways.protoblast.common.i18n.wording.Reader;
import be.elevenways.protoblast.common.i18n.wording.Wording;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.protoblast.common.value.Percent;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.DataBucket;
import be.elevenways.zenit.common.data.DataBuckets;
import be.elevenways.zenit.common.data.DataSeries;
import be.elevenways.zenit.common.data.DataTable;
import be.elevenways.zenit.common.data.RecordFacet;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.orm.field.StorageKeyed;
import be.elevenways.zenit.common.routing.BoundEndpoint;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.common.routing.MalformedQuery;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.time.OfficeZone;
import be.elevenways.zenit.common.ui.BadgeVariant;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.traffic.common.StatusClass;
import be.elevenways.zenit.traffic.common.TrafficGranularity;
import be.elevenways.zenit.traffic.common.TrafficTable;
import be.elevenways.zenit.traffic.common.TrafficWindow;
import be.elevenways.zenit.traffic.server.TrafficQuery;
import be.elevenways.zenit.traffic.server.TrafficTotals;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The traffic screens' one builder: the period a screen's address asks, the read it makes through the operation its
 * tool also runs ({@link TrafficReads}), and the view the shared partial draws ({@link TrafficView}).
 *
 * AIDEV-NOTE: a screen never reads the store itself: it places its tool's own operation for its viewer, so who may
 * read it and what it reads are the tool's. The figures, charts and tables come from the span's totals, never from
 * log lines. A period is the address's {@code period} and {@code until} (office time to the minute, left out for
 * now), so Previous and Next are links and every screen's address says what it shows. The sites' rankings are tables
 * with a share bar each, not bar charts: pl-chart words its values as numbers or money, and a ranking by bytes sent
 * must read "1.2 GB".
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class TrafficScreens {

    static final ParameterDefinition<String> PERIOD = HohenheimParams.TRAFFIC_PERIOD;
    static final ParameterDefinition<String> UNTIL = HohenheimParams.TRAFFIC_UNTIL;

    /** The query parameters every traffic screen reads. */
    static final List<ParameterDefinition<?>> PARAMETERS = List.of(PERIOD, UNTIL);

    /** The tables a site's Traffic tab lists. */
    static final Set<TrafficReads.Top> SITE_TABLES = Set.of(TrafficReads.Top.PAGES, TrafficReads.Top.REFERRERS,
        TrafficReads.Top.REFUSED_PAGES, TrafficReads.Top.BROWSERS, TrafficReads.Top.STATUSES,
        TrafficReads.Top.VISITORS);

    /** The tables the server's Traffic report lists. */
    static final Set<TrafficReads.Top> SERVER_TABLES = Set.of(TrafficReads.Top.SITES, TrafficReads.Top.PAGES,
        TrafficReads.Top.SLOWEST_PAGES, TrafficReads.Top.REFERRERS, TrafficReads.Top.BROWSERS,
        TrafficReads.Top.UNSERVED_NAMES);

    /** The tables a DNS zone's Queries tab lists, its outcomes for the figures. */
    static final Set<TrafficReads.Top> ZONE_TABLES = Set.of(TrafficReads.Top.NAMES, TrafficReads.Top.MISSING_NAMES,
        TrafficReads.Top.RESOLVERS, TrafficReads.Top.OUTCOMES);

    /** What a cell of a top table shows of its line. */
    private enum Shown {
        /** The line's key: a path, a referrer, a client, an address, a name. */
        NAME,
        /** The site a per-site line belongs to. */
        SITE,
        /** A DNS name's type. */
        TYPE,
        REQUESTS,
        /** The line's share of the span's requests, as a bar. */
        SHARE,
        SENT,
        MEDIAN,
        SLOWEST,
        /** When the line was last asked. */
        LAST
    }

    /** Where a screen's rows lead; a source is the read's key of a site. */
    interface Links {

        /** @return the Traffic tab of the site a source names, null for none */
        @Nullable String site(@NonNull String source);

        /** @return that site's access log narrowed to a path, null for none */
        @Nullable String path(@NonNull String source, @NonNull String path);
    }

    /** No row leads anywhere. */
    static final Links NOWHERE = new Links() {
        @Override
        public @Nullable String site(@NonNull String source) {
            return null;
        }

        @Override
        public @Nullable String path(@NonNull String source, @NonNull String path) {
            return null;
        }
    };

    /**
     * What a screen's address asks.
     *
     * @param window the span's window
     * @param until  where it ends, null for now
     */
    record Asked(@NonNull TrafficWindow window, @Nullable Instant until) {

        /** @return what the conduit's address asks; a period that does not read is a day, a moment now */
        static @NonNull Asked of(@NonNull Conduit conduit) {
            TrafficWindow window = TrafficReads.windowOf(conduit.getParameter(PERIOD));
            String until = conduit.getParameter(UNTIL);
            Instant end = null;
            if (until != null && !until.isBlank()) {
                try {
                    end = LocalDateTime.parse(until.trim()).atZone(OfficeZone.office()).toInstant();
                } catch (DateTimeParseException unreadable) {
                    end = null;
                }
            }
            return new Asked(window,
                end != null && end.isBefore(Now.instant()) ? end : null);
        }
    }

    private TrafficScreens() {
    }

    /** Declares the screens' query parameters on a record tab's conduit, which no entry declares for it. */
    static void declareOn(@NonNull Conduit conduit) {
        Endpoint.declareQueryOn(conduit, MalformedQuery.ABSENT, PARAMETERS);
    }

    /**
     * Reads a screen's span through its tool's operation, for the screen's viewer.
     *
     * @param subject the site or zone read, null for every site's read
     */
    static <S> TrafficReads.@NonNull Read read(@NonNull Operation<S, TrafficReads.ReadInput, TrafficReads.Read> read,
                                              @NonNull AccessContext viewer, @Nullable S subject,
                                              @NonNull Asked asked, @NonNull Set<TrafficReads.Top> tables) {
        OperationRequest<S, TrafficReads.ReadInput, TrafficReads.Read> request =
            OperationRequest.of(read, CmsPlacementSurface.ADMIN_ACTION).caller(viewer)
                .preset(new TrafficReads.ReadInput(asked.window().storageKey(), asked.until(), null))
                .attachment(TrafficReads.SCREEN_TABLES, tables);
        if (subject != null) {
            request.subjects(List.of(subject));
        }
        return OperationPipeline.invoke(request).value();
    }

    /**
     * @param base  the screen without a period, which the switch, Previous and Next extend
     * @param links where the rows lead
     * @return the screen as its partial draws it
     */
    static @NonNull TrafficView view(TrafficReads.@NonNull Read read, @NonNull Asked asked,
                                     TrafficReads.@NonNull Reach reach, @NonNull BoundEndpoint<?> base,
                                     @NonNull Links links, @NonNull AccessContext viewer) {
        TrafficQuery query = read.query();
        List<NavTab> periods = new ArrayList<>();
        for (TrafficWindow window : TrafficWindow.values()) {
            periods.add(new NavTab(at(base, window, asked.until()), TrafficReads.periodLabel(window), "",
                window == asked.window(), false));
        }
        Instant later = query.after().to();
        String next = asked.until() == null ? null
            : at(base, asked.window(), later.isBefore(Now.instant()) ? later : null);
        boolean zone = reach == TrafficReads.Reach.ZONE;
        List<TrafficView.Chart> charts = new ArrayList<>();
        List<TrafficView.Table> tables = new ArrayList<>();
        Microcopy empty = null;
        if (read.totals().requests() == 0) {
            empty = zone ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("traffic_empty_zone")
                : HohenheimMicrocopy.TRAFFIC_SCREEN.of("traffic_empty");
        } else {
            Reader reader = OfficeZone.reader(viewer);
            charts.add(new TrafficView.Chart("steps", zone ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("chart_questions")
                    : HohenheimMicrocopy.TRAFFIC_SCREEN.of("chart_requests"),
                (zone ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("chart_questions_lead")
                    : HohenheimMicrocopy.TRAFFIC_SCREEN.of("chart_requests_lead"))
                    .withArg("step", query.granularity().storageKey()),
                ChartType.BAR, true, false, false, series(read.steps(), query.granularity(), reader, true)));
            if (!zone) {
                charts.add(new TrafficView.Chart("times", HohenheimMicrocopy.TRAFFIC_SCREEN.of("chart_response_time"),
                    HohenheimMicrocopy.TRAFFIC_SCREEN.of("chart_response_time_lead")
                        .withArg("step", query.granularity().storageKey()),
                    ChartType.LINE, false, false, true, series(read.times(), query.granularity(), reader, false)));
            }
            DataBuckets byRequests = read.sitesByRequests();
            DataBuckets bySent = read.sitesBySent();
            if (byRequests != null && bySent != null) {
                tables.add(ranking("sites_by_requests", byRequests, false, links));
                tables.add(ranking("sites_by_sent", bySent, true, links));
            }
            for (Map.Entry<TrafficReads.Top, DataTable> listed : read.tables().entrySet()) {
                if (listed.getKey() != TrafficReads.Top.OUTCOMES) {
                    tables.add(table(listed.getKey(), reach, listed.getValue(), read.totals().requests(), links));
                }
            }
        }
        return new TrafficView(periods, HohenheimMicrocopy.TRAFFIC_SCREEN.of("span").withArg("from", query.from())
            .withArg("to", query.to()), at(base, asked.window(), query.from()), next,
            zone ? zoneFigures(read) : figures(read, reach), charts, tables, empty);
    }

    /** @return the screen for a span of the window ending at a moment, null for now */
    private static @NonNull String at(@NonNull BoundEndpoint<?> base, @NonNull TrafficWindow window,
                                      @Nullable Instant until) {
        BoundEndpoint<?> target = base.with(PERIOD, window.storageKey());
        if (until != null) {
            target = target.with(UNTIL, until.atZone(OfficeZone.office()).toLocalDateTime()
                .truncatedTo(ChronoUnit.MINUTES).toString());
        }
        return target.toUrl();
    }

    /** @return a site's or every site's headline figures, against the span before where a count compares */
    private static @NonNull List<TrafficView.Figure> figures(TrafficReads.@NonNull Read read,
                                                              TrafficReads.@NonNull Reach reach) {
        TrafficTotals totals = read.totals();
        TrafficTotals before = read.before();
        boolean counted = before.requests() > 0;
        List<TrafficView.Figure> figures = new ArrayList<>();
        figures.add(compared("requests", HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_requests"),
            count(totals.requests()), totals.requests(), before.requests(), counted
                ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("before_count").withArg("count", before.requests())
                : HohenheimMicrocopy.TRAFFIC_SCREEN.of("nothing_before")));
        figures.add(compared("sent", HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_sent"), bytes(totals.bytesSent()),
            totals.bytesSent(), before.bytesSent(), counted
                ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("before_bytes").withArg("bytes", before.bytesSent())
                : HohenheimMicrocopy.TRAFFIC_SCREEN.of("nothing_before")));
        figures.add(new TrafficView.Figure("visitors", HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_visitors"),
            count(totals.visitors()), null, false,
            HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_visitors_by_address")));
        if (reach == TrafficReads.Reach.SERVER) {
            long refused = totals.statusClasses().getOrDefault(StatusClass.CLIENT_ERROR, 0L);
            figures.add(new TrafficView.Figure("refused", HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_refused"),
                count(refused), null, false, totals.requests() == 0 ? null
                    : HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_refused_share")
                        .withArg("share", Percent.share(refused, totals.requests(), 1))));
        }
        figures.add(new TrafficView.Figure("failed", HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_failed"),
            count(totals.statusClasses().getOrDefault(StatusClass.SERVER_ERROR, 0L)), null, false, null));
        Double slowest = totals.slowestMillis();
        figures.add(new TrafficView.Figure("response", HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_response"),
            millis(totals.medianMillis()), null, false, slowest == null ? null
                : HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_response_slowest").withArg("took", duration(slowest))));
        return figures;
    }

    /** @return a zone's headline figures: its questions against the span before, and each outcome's count */
    private static @NonNull List<TrafficView.Figure> zoneFigures(TrafficReads.@NonNull Read read) {
        TrafficTotals totals = read.totals();
        TrafficTotals before = read.before();
        DataTable outcomes = read.tables().get(TrafficReads.Top.OUTCOMES);
        List<TrafficView.Figure> figures = new ArrayList<>();
        figures.add(compared("questions", HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_questions"),
            count(totals.requests()), totals.requests(), before.requests(), before.requests() > 0
                ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("before_count").withArg("count", before.requests())
                : HohenheimMicrocopy.TRAFFIC_SCREEN.of("nothing_before")));
        for (DnsTraffic.Outcome outcome : List.of(DnsTraffic.Outcome.ANSWERED, DnsTraffic.Outcome.NO_SUCH_NAME,
                DnsTraffic.Outcome.REFUSED)) {
            long asked = 0;
            for (DataTable.Line line : outcomes == null ? List.<DataTable.Line>of() : outcomes.lines()) {
                if (DnsTraffic.Outcome.ofStatus(line.groups().getLast().key()) == outcome) {
                    asked = line.records();
                }
            }
            figures.add(new TrafficView.Figure(BlastString.lower(outcome.name()), outcome.label(), count(asked),
                null, false, null));
        }
        return figures;
    }

    /** @return a figure with its change against the span before, none when nothing was counted then */
    private static TrafficView.@NonNull Figure compared(@NonNull String key, @NonNull Microcopy label,
                                                        @NonNull Microcopy value, long now, long then,
                                                        @NonNull Microcopy description) {
        if (then <= 0) {
            return new TrafficView.Figure(key, label, value, null, false, description);
        }
        long change = Math.round((now - then) * 100.0 / then);
        Microcopy trend = change >= 0 ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("trend_more")
            : HohenheimMicrocopy.TRAFFIC_SCREEN.of("trend_less");
        return new TrafficView.Figure(key, label, value, trend.withArg("change", Percent.of(Math.abs(change))),
            change >= 0, description);
    }

    /**
     * @param byClass whether the series are status classes, each drawn in its class's tone
     * @return the read's series as the chart plots them, each step named as the reader names it, whole numbers
     */
    private static @NonNull List<ChartSeries> series(@NonNull DataBuckets read, @NonNull TrafficGranularity step,
                                                     @NonNull Reader reader, boolean byClass) {
        List<ChartSeries> series = new ArrayList<>();
        for (DataSeries each : read.series()) {
            List<ChartPoint> points = new ArrayList<>(each.buckets().size());
            for (DataBucket bucket : each.buckets()) {
                Double value = bucket.value();
                points.add(new ChartPoint(stepName(bucket, step, reader),
                    value == null ? null : (double) Math.round(value)));
            }
            String key = each.key() != null ? each.key() : "";
            ChartSeries drawn = new ChartSeries(key, Microcopy.literal(each.label() != null ? each.label() : key),
                points);
            StatusClass statusClass = byClass ? StorageKeyed.findOrNull(Arrays.asList(StatusClass.values()), key)
                : null;
            if (statusClass != null) {
                drawn.setColorSet(tone(statusClass).token());
            }
            series.add(drawn);
        }
        return series;
    }

    /** @return the tone a status class is drawn in: answered green, sent elsewhere blue, refused amber, failed red */
    static @NonNull BadgeVariant tone(@NonNull StatusClass statusClass) {
        return switch (statusClass) {
            case SUCCESS -> BadgeVariant.SUCCESS;
            case REDIRECTION -> BadgeVariant.INFO;
            case CLIENT_ERROR -> BadgeVariant.WARNING;
            case SERVER_ERROR -> BadgeVariant.DESTRUCTIVE;
            case INFORMATIONAL, OTHER -> BadgeVariant.SECONDARY;
        };
    }

    /** @return a step as the reader names it: a minute or an hour by its time, a day by its weekday and date */
    private static @NonNull String stepName(@NonNull DataBucket bucket, @NonNull TrafficGranularity step,
                                            @NonNull Reader reader) {
        return switch (step) {
            case MINUTE, HOUR -> bucket.label();
            case DAY -> Wording.date(reader, LocalDate.parse(bucket.key()), DateStyle.WEEKDAY_NUMERIC);
        };
    }

    /** @return a ranking of sites as a table: each site, its count and its share as a bar */
    private static TrafficView.@NonNull Table ranking(@NonNull String key, @NonNull DataBuckets ranked, boolean sent,
                                                      @NonNull Links links) {
        List<DataBucket> buckets = ranked.series().isEmpty() ? List.of() : ranked.series().getFirst().buckets();
        double whole = 0;
        for (DataBucket bucket : buckets) {
            whole += bucket.value() == null ? 0 : bucket.value();
        }
        List<TrafficView.Line> rows = new ArrayList<>();
        for (DataBucket bucket : buckets) {
            long value = bucket.value() == null ? 0 : Math.round(bucket.value());
            rows.add(new TrafficView.Line(bucket.key(), List.of(
                cell(Microcopy.literal(bucket.label()), false, links.site(bucket.key())),
                figure(sent ? bytes(value) : count(value)), share(value, whole))));
        }
        return new TrafficView.Table(key, sent ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_sites_by_sent")
                : HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_sites_by_requests"),
            ranked.omitted() > 0 ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_more_sites")
                .withArg("count", ranked.omitted()) : null,
            List.of(new TrafficView.Column(HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_site"), false, false),
                new TrafficView.Column(sent ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_sent")
                    : HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_requests"), true, false),
                new TrafficView.Column(HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_share"), false, true)),
            rows, HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_empty"));
    }

    /** @return a top table as its screen lays it out */
    private static TrafficView.@NonNull Table table(TrafficReads.@NonNull Top top, TrafficReads.@NonNull Reach reach,
                                                    @NonNull DataTable table, long requests, @NonNull Links links) {
        List<Shown> shown = shown(top, reach);
        List<TrafficView.Column> columns = new ArrayList<>(shown.size());
        for (Shown each : shown) {
            columns.add(new TrafficView.Column(head(each, top), isFigure(each), each == Shown.SHARE));
        }
        List<TrafficView.Line> rows = new ArrayList<>();
        for (DataTable.Line line : table.lines()) {
            List<TrafficView.Cell> cells = new ArrayList<>(shown.size());
            for (Shown each : shown) {
                cells.add(cell(each, top, line, requests, links));
            }
            List<String> key = new ArrayList<>();
            line.groups().forEach(group -> key.add(group.key()));
            rows.add(new TrafficView.Line(String.join(" ", key), cells));
        }
        // A table whose only line holds what none of its rows names (the slowest pages below their least
        // requests) lists nothing.
        boolean listed = table.lines().stream()
            .anyMatch(line -> !RecordFacet.MORE_SLUG.equals(line.groups().getLast().key()));
        return new TrafficView.Table(top.token(), title(top, reach), lead(top), columns,
            listed ? rows : List.of(), top == TrafficReads.Top.SLOWEST_PAGES
                ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_slowest_pages_empty")
                    .withArg("least", TrafficReads.SLOWEST_LEAST_REQUESTS)
                : HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_empty"));
    }

    /** @return the columns of a table, as the board lays it out */
    private static @NonNull List<Shown> shown(TrafficReads.@NonNull Top top, TrafficReads.@NonNull Reach reach) {
        boolean perSite = top.perSite(reach);
        return switch (top) {
            case PAGES -> perSite ? List.of(Shown.SITE, Shown.NAME, Shown.REQUESTS)
                : List.of(Shown.NAME, Shown.REQUESTS, Shown.SHARE, Shown.SENT, Shown.MEDIAN);
            case SLOWEST_PAGES -> perSite ? List.of(Shown.SITE, Shown.NAME, Shown.MEDIAN, Shown.SLOWEST)
                : List.of(Shown.NAME, Shown.REQUESTS, Shown.MEDIAN, Shown.SLOWEST);
            case REFUSED_PAGES -> perSite ? List.of(Shown.SITE, Shown.NAME, Shown.REQUESTS)
                : List.of(Shown.NAME, Shown.REQUESTS, Shown.SHARE, Shown.SENT);
            case REFERRERS, BROWSERS, STATUSES, RESOLVERS -> List.of(Shown.NAME, Shown.REQUESTS, Shown.SHARE);
            case VISITORS -> List.of(Shown.NAME, Shown.REQUESTS, Shown.SENT);
            case UNSERVED_NAMES -> List.of(Shown.NAME, Shown.REQUESTS, Shown.LAST);
            case NAMES, MISSING_NAMES -> List.of(Shown.NAME, Shown.TYPE, Shown.REQUESTS);
            case SITES, OUTCOMES -> throw new IllegalArgumentException("The " + top.token() + " read is no table");
        };
    }

    /** @return a column's head; the name column says what the table's keys are */
    private static @NonNull Microcopy head(@NonNull Shown shown, TrafficReads.@NonNull Top top) {
        return switch (shown) {
            case NAME -> switch (top) {
                case PAGES, SLOWEST_PAGES, REFUSED_PAGES -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_path");
                case REFERRERS -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_referrer");
                case BROWSERS -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_browser");
                case STATUSES -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_status");
                case VISITORS -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_visitor");
                case UNSERVED_NAMES -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_asked_for");
                case NAMES, MISSING_NAMES -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_name");
                case RESOLVERS -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_resolver");
                case SITES, OUTCOMES -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_site");
            };
            case SITE -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_site");
            case TYPE -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_type");
            case REQUESTS -> top.listedOn(TrafficReads.Reach.ZONE) ? HohenheimMicrocopy.TRAFFIC_SCREEN.of(
                "column_questions") : HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_requests");
            case SHARE -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_share");
            case SENT -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_sent");
            case MEDIAN -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_median");
            case SLOWEST -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_slowest");
            case LAST -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("column_last");
        };
    }

    /** @return whether a column's cells are figures, right-aligned */
    private static boolean isFigure(@NonNull Shown shown) {
        return switch (shown) {
            case REQUESTS, SENT, MEDIAN, SLOWEST, LAST -> true;
            case NAME, SITE, TYPE, SHARE -> false;
        };
    }

    /** @return one cell of a table's line; the "other" line names no site and no time, and leads nowhere */
    private static TrafficView.@NonNull Cell cell(@NonNull Shown shown, TrafficReads.@NonNull Top top,
                                                  DataTable.@NonNull Line line, long requests, @NonNull Links links) {
        DataTable.Group key = line.groups().getLast();
        boolean other = RecordFacet.MORE_SLUG.equals(key.key());
        String source = line.groups().size() > 1 ? line.groups().getFirst().key() : "";
        boolean asked = top.table() == TrafficTable.PATHS || top.table() == TrafficTable.REFUSED_PATHS;
        boolean dns = top.listedOn(TrafficReads.Reach.ZONE);
        return switch (shown) {
            case NAME -> {
                if (other) yield cell(Microcopy.literal(key.label()), false, null);
                if (key.missing() && top == TrafficReads.Top.REFERRERS) {
                    yield cell(HohenheimMicrocopy.TRAFFIC_SCREEN.of("no_referrer"), false, null);
                }
                boolean words = top == TrafficReads.Top.BROWSERS || top == TrafficReads.Top.STATUSES
                    || top == TrafficReads.Top.REFERRERS || key.missing();
                yield cell(Microcopy.literal(dns && asked ? DnsTraffic.Asked.of(key.label()).name() : key.label()),
                    !words, asked && !dns ? links.path(source, key.key()) : null);
            }
            case SITE -> cell(Microcopy.literal(other ? "" : line.groups().getFirst().label()), false,
                other ? null : links.site(source));
            case TYPE -> cell(Microcopy.literal(other ? "" : DnsTraffic.Asked.of(key.label()).type()), false, null);
            case REQUESTS -> figure(count(line.records()));
            case SHARE -> share(line.records(), requests);
            case SENT -> {
                Double sent = number(line, "bytes_sent");
                yield figure(bytes(sent == null ? 0 : Math.round(sent)));
            }
            case MEDIAN -> figure(other ? Microcopy.literal("") : millis(number(line, "median_ms")));
            case SLOWEST -> figure(other ? Microcopy.literal("") : millis(number(line, "slowest_ms")));
            case LAST -> {
                Double seen = number(line, "last_seen");
                yield figure(seen == null ? Microcopy.literal("") : HohenheimMicrocopy.TRAFFIC_SCREEN.of("last_seen")
                    .withArg("at", Instant.ofEpochMilli(Math.round(seen))));
            }
        };
    }

    private static TrafficView.@NonNull Cell cell(@NonNull Microcopy text, boolean mono, @Nullable String href) {
        return new TrafficView.Cell(text, mono, false, null, 0, href);
    }

    private static TrafficView.@NonNull Cell figure(@NonNull Microcopy text) {
        return new TrafficView.Cell(text, false, true, null, 0, null);
    }

    /** @return a share cell: the part of the whole as a bar out of 100, its percentage the words a reader hears */
    private static TrafficView.@NonNull Cell share(double part, double whole) {
        int percent = whole <= 0 ? 0 : (int) Math.min(100, Math.round(part * 100 / whole));
        Microcopy words = HohenheimMicrocopy.TRAFFIC_SCREEN.of("share").withArg("share", Percent.of(percent));
        return new TrafficView.Cell(words, false, false, percent, 100, null);
    }

    private static @Nullable Double number(DataTable.@NonNull Line line, @NonNull String value) {
        DataTable.Value found = line.values().get(value);
        return found == null ? null : found.number();
    }

    /** @return a table's title on its screen: every site's top pages say they go across sites */
    private static @NonNull Microcopy title(TrafficReads.@NonNull Top top, TrafficReads.@NonNull Reach reach) {
        return top == TrafficReads.Top.PAGES && top.perSite(reach)
            ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_pages_across_sites") : top.label();
    }

    /** @return the sentence under a table's title, null for none */
    private static @Nullable Microcopy lead(TrafficReads.@NonNull Top top) {
        return switch (top) {
            case PAGES -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_pages_lead");
            case SLOWEST_PAGES -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_slowest_pages_lead")
                .withArg("least", TrafficReads.SLOWEST_LEAST_REQUESTS);
            case REFERRERS -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_referrers_lead");
            case UNSERVED_NAMES -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_unserved_names_lead");
            case MISSING_NAMES -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_missing_names_lead");
            case RESOLVERS -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_resolvers_lead");
            case REFUSED_PAGES, BROWSERS, STATUSES, VISITORS, SITES, NAMES, OUTCOMES -> null;
        };
    }

    private static @NonNull Microcopy count(long count) {
        return HohenheimMicrocopy.TRAFFIC_SCREEN.of("traffic_count").withArg("count", count);
    }

    private static @NonNull Microcopy bytes(long bytes) {
        return HohenheimMicrocopy.TRAFFIC_SCREEN.of("bytes").withArg("bytes", bytes);
    }

    private static @NonNull Microcopy millis(@Nullable Double millis) {
        return millis == null ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("no_value")
            : HohenheimMicrocopy.TRAFFIC_SCREEN.of("took").withArg("took", duration(millis));
    }

    private static @NonNull Duration duration(double millis) {
        return Duration.ofNanos(Math.round(millis * 1_000_000));
    }
}
