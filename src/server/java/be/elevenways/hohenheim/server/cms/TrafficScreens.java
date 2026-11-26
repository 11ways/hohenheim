package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.server.dns.DnsTraffic;
import be.elevenways.hohenheim.traffic.TrafficView;
import be.elevenways.plumage.chart.ChartPoint;
import be.elevenways.plumage.chart.ChartSeries;
import be.elevenways.plumage.chart.ChartType;
import be.elevenways.plumage.chart.ChartValueKind;
import be.elevenways.plumage.component.NavTab;
import be.elevenways.protoblast.common.http.UserAgentFamily;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.wording.DateStyle;
import be.elevenways.protoblast.common.i18n.wording.Reader;
import be.elevenways.protoblast.common.i18n.wording.Wording;
import be.elevenways.protoblast.common.time.Now;
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
import be.elevenways.zenit.traffic.common.TrafficCopy;
import be.elevenways.zenit.traffic.common.TrafficTable;
import be.elevenways.zenit.traffic.common.TrafficWindow;
import be.elevenways.zenit.traffic.server.HitKeys;
import be.elevenways.zenit.traffic.server.TrafficQuery;
import be.elevenways.zenit.traffic.server.TrafficTotals;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The traffic screens' one builder: the period a screen's address asks, the read it makes through the operation its
 * tool also runs ({@link TrafficReads}), and the view the shared partial draws ({@link TrafficView}).
 *
 * AIDEV-NOTE: a screen never reads the store itself: it places its tool's own operation for its viewer, so who may
 * read it and what it reads are the tool's. The figures, charts and tables come from the span's totals, never from
 * log lines. A period is the address's {@code period} and {@code until} (office time to the minute, left out for
 * now), so Previous and Next are links and every screen's address says what it shows. The sites' rankings are tables
 * with a share bar each, which word a ranking by bytes sent as "1.2 GB" beside its share; the charts declare what
 * their values are (pl-chart's valueKind), so sizes and timings read as the reader reads them. The browsers group
 * their bots into one row that opens to each of them, as the board draws them.
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
     * @param note  the line under everything, null for none
     * @return the screen as its partial draws it
     */
    static @NonNull TrafficView view(TrafficReads.@NonNull Read read, @NonNull Asked asked,
                                     TrafficReads.@NonNull Reach reach, @NonNull BoundEndpoint<?> base,
                                     @NonNull Links links, @NonNull AccessContext viewer, @Nullable Microcopy note) {
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
        List<TrafficView.Chart> paired = new ArrayList<>();
        List<TrafficView.Table> tables = new ArrayList<>();
        Microcopy empty = null;
        String step = query.granularity().storageKey();
        if (read.totals().requests() == 0) {
            empty = zone ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("traffic_empty_zone")
                : HohenheimMicrocopy.TRAFFIC_SCREEN.of("traffic_empty");
        } else {
            Reader reader = OfficeZone.reader(viewer);
            charts.add(new TrafficView.Chart("steps", zone ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("chart_questions")
                    : HohenheimMicrocopy.TRAFFIC_SCREEN.of("chart_requests"),
                (zone ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("chart_questions_lead")
                    : HohenheimMicrocopy.TRAFFIC_SCREEN.of("chart_requests_lead")).withArg("step", step),
                ChartType.BAR, true, false, false, ChartValueKind.NUMBER,
                zone ? series(Objects.requireNonNull(read.outcomes(), "a zone's read has its outcomes"),
                    TrafficScreens::outcomeTone) : series(read.steps(), TrafficScreens::classTone)));
            if (!zone) {
                paired.add(new TrafficView.Chart("sent", HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_sent"),
                    HohenheimMicrocopy.TRAFFIC_SCREEN.of("chart_per_step_lead").withArg("step", step),
                    ChartType.AREA, false, false, true, ChartValueKind.BYTES, series(read.sent(), key -> null)));
                paired.add(new TrafficView.Chart("times", HohenheimMicrocopy.TRAFFIC_SCREEN.of("chart_response_time"),
                    HohenheimMicrocopy.TRAFFIC_SCREEN.of("chart_per_step_lead").withArg("step", step),
                    ChartType.LINE, false, false, true, ChartValueKind.TIMING, series(read.times(), key -> null)));
            }
            DataBuckets byRequests = read.sitesByRequests();
            DataBuckets bySent = read.sitesBySent();
            if (byRequests != null && bySent != null) {
                tables.add(ranking("sites_by_requests", byRequests, false, links));
                tables.add(ranking("sites_by_sent", bySent, true, links));
            }
            for (Map.Entry<TrafficReads.Top, DataTable> listed : read.tables().entrySet()) {
                if (listed.getKey() != TrafficReads.Top.OUTCOMES) {
                    tables.add(table(listed.getKey(), reach, listed.getValue(), read.totals().requests(), links,
                        reader, viewer.messageResolver()));
                }
            }
        }
        return new TrafficView(periods, HohenheimMicrocopy.TRAFFIC_SCREEN.of("span").withArg("from", query.from())
            .withArg("to", query.to()), at(base, asked.window(), query.from()), next,
            zone ? zoneFigures(read) : figures(read, reach), charts, paired, tables, empty, note);
    }

    /** @return the screen for a span of the window ending at a moment, null for now */
    private static @NonNull String at(@NonNull BoundEndpoint<?> base, @NonNull TrafficWindow window,
                                      @Nullable Instant until) {
        return spanAt(base, window, until).toUrl();
    }

    /** @return the screen's address for a span of the window ending at a moment, null for now */
    static @NonNull BoundEndpoint<?> spanAt(@NonNull BoundEndpoint<?> base, @NonNull TrafficWindow window,
                                            @Nullable Instant until) {
        BoundEndpoint<?> target = base.with(PERIOD, window.storageKey());
        if (until != null) {
            target = target.with(UNTIL, until.atZone(OfficeZone.office()).toLocalDateTime()
                .truncatedTo(ChronoUnit.MINUTES).toString());
        }
        return target;
    }

    /** @return a site's or every site's headline figures, against the span before where a count compares */
    private static @NonNull List<TrafficView.Figure> figures(TrafficReads.@NonNull Read read,
                                                              TrafficReads.@NonNull Reach reach) {
        TrafficTotals totals = read.totals();
        TrafficTotals before = read.before();
        boolean counted = before.requests() > 0;
        boolean server = reach == TrafficReads.Reach.SERVER;
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
        if (server) {
            long refused = totals.statusClasses().getOrDefault(StatusClass.CLIENT_ERROR, 0L);
            figures.add(new TrafficView.Figure("refused", HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_refused"),
                count(refused), null, false, totals.requests() == 0 ? null
                    : HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_refused_share")
                        .withArg("share", Percent.share(refused, totals.requests(), 1))));
        }
        long failed = totals.statusClasses().getOrDefault(StatusClass.SERVER_ERROR, 0L);
        figures.add(new TrafficView.Figure("failed", server ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_failed_all")
            : HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_failed"), count(failed), null, false,
            server ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_failed_class") : failedMost(read, failed)));
        Double slowest = totals.slowestMillis();
        figures.add(new TrafficView.Figure("response", HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_response"),
            millis(totals.medianMillis()), null, false, slowest == null ? null
                : HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_response_slowest").withArg("took", duration(slowest))));
        return figures;
    }

    /**
     * @param failed the span's 5xx answers
     * @return when most of them were answered: the step that answered the most, null while none failed
     */
    private static @Nullable Microcopy failedMost(TrafficReads.@NonNull Read read, long failed) {
        if (failed <= 0) {
            return null;
        }
        DataBucket most = null;
        for (DataSeries series : read.steps().series()) {
            if (!StatusClass.SERVER_ERROR.storageKey().equals(series.key())) continue;
            for (DataBucket bucket : series.buckets()) {
                if (bucket.value() != null && (most == null || bucket.value() > most.value())) most = bucket;
            }
        }
        return most == null || most.value() == null ? null
            : HohenheimMicrocopy.TRAFFIC_SCREEN.of("figure_failed_most")
                .withArg("step", read.query().granularity().storageKey())
                .withArg("count", Math.round(most.value())).withArg("at", most.label());
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
            figures.add(new TrafficView.Figure(outcome.token(), outcome.label(), count(asked), null, false, null));
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
     * @param tones the tone a series is drawn in by its key, null for the chart's own colour by ordinal
     * @return the read's series as the chart plots them, each step named as the read names it for the reader
     */
    private static @NonNull List<ChartSeries> series(@NonNull DataBuckets read,
                                                     @NonNull Function<String, @Nullable BadgeVariant> tones) {
        List<ChartSeries> series = new ArrayList<>();
        for (DataSeries each : read.series()) {
            List<ChartPoint> points = new ArrayList<>(each.buckets().size());
            for (DataBucket bucket : each.buckets()) {
                points.add(new ChartPoint(bucket.label(), bucket.value()));
            }
            String key = each.key() != null ? each.key() : "";
            ChartSeries drawn = new ChartSeries(key, Microcopy.literal(each.label() != null ? each.label() : key),
                points);
            BadgeVariant tone = tones.apply(key);
            if (tone != null) {
                drawn.setColorSet(tone.token());
            }
            series.add(drawn);
        }
        return series;
    }

    /** @return the tone of a status class's series, null for a key that names none */
    private static @Nullable BadgeVariant classTone(@NonNull String key) {
        StatusClass statusClass = StorageKeyed.findOrNull(Arrays.asList(StatusClass.values()), key);
        return statusClass == null ? null : tone(statusClass);
    }

    /** @return the tone of a DNS outcome's series, null for a key that names none */
    private static @Nullable BadgeVariant outcomeTone(@NonNull String key) {
        DnsTraffic.Outcome outcome = DnsTraffic.Outcome.forToken(key);
        return outcome == null ? null : tone(outcome);
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

    /** @return the tone a DNS outcome is drawn in: answered green, no such name grey, refused amber, failed red */
    static @NonNull BadgeVariant tone(DnsTraffic.@NonNull Outcome outcome) {
        return switch (outcome) {
            case ANSWERED -> BadgeVariant.SUCCESS;
            case NO_SUCH_NAME -> BadgeVariant.SECONDARY;
            case REFUSED, MALFORMED -> BadgeVariant.WARNING;
            case NOT_IMPLEMENTED, FAILED -> BadgeVariant.DESTRUCTIVE;
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
                figure(sent ? bytes(value) : count(value)), share(value, whole)), List.of()));
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
                                                    @NonNull DataTable table, long requests, @NonNull Links links,
                                                    @NonNull Reader reader, @Nullable MessageResolver resolver) {
        List<Shown> shown = shown(top, reach);
        List<TrafficView.Column> columns = new ArrayList<>(shown.size());
        for (Shown each : shown) {
            columns.add(new TrafficView.Column(head(each, top), isFigure(each), each == Shown.SHARE));
        }
        Function<DataTable.Line, TrafficView.Line> row = line -> {
            List<TrafficView.Cell> cells = new ArrayList<>(shown.size());
            for (Shown each : shown) {
                cells.add(cell(each, top, line, requests, links, reader, resolver));
            }
            List<String> key = new ArrayList<>();
            line.groups().forEach(group -> key.add(group.key()));
            return new TrafficView.Line(String.join(" ", key), cells, List.of());
        };
        List<TrafficView.Line> rows = new ArrayList<>();
        if (top == TrafficReads.Top.BROWSERS) {
            rows.addAll(browsers(table, shown, requests, row));
        } else {
            for (DataTable.Line line : table.lines()) {
                rows.add(row.apply(line));
            }
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

    /**
     * The browsers as the board groups them: every bot in one row ("Bots, 14 kinds") that opens to each of them, every
     * other client its own row, busiest first; the first {@link TrafficReads#TOP_ROWS} of those are listed and the rest
     * join the read's Other row.
     *
     * @param table the span's clients, as many as one read lists (TopRead.MAX_LIMIT), the rest in its Other line
     */
    private static @NonNull List<TrafficView.Line> browsers(@NonNull DataTable table, @NonNull List<Shown> shown,
                                                            long requests,
                                                            @NonNull Function<DataTable.Line, TrafficView.Line> row) {
        record Ranked(long requests, TrafficView.@NonNull Line line) {
        }
        List<TrafficView.Line> bots = new ArrayList<>();
        long botRequests = 0;
        long otherRequests = 0;
        List<Ranked> ranked = new ArrayList<>();
        for (DataTable.Line line : table.lines()) {
            String key = line.groups().getLast().key();
            UserAgentFamily family = HitKeys.agentFamily(key);
            if (RecordFacet.MORE_SLUG.equals(key)) {
                otherRequests += line.records();
            } else if (family != null && family.bot()) {
                bots.add(row.apply(line));
                botRequests += line.records();
            } else if (ranked.size() < TrafficReads.TOP_ROWS) {
                ranked.add(new Ranked(line.records(), row.apply(line)));
            } else {
                otherRequests += line.records();
            }
        }
        if (!bots.isEmpty()) {
            ranked.add(new Ranked(botRequests, summed("bots", shown, HohenheimMicrocopy.TRAFFIC_SCREEN
                .of("bots_group").withArg("count", bots.size()), botRequests, requests, bots)));
        }
        // A stable sort: the read's own order among clients of as many requests.
        ranked.sort(Comparator.comparingLong(Ranked::requests).reversed());
        List<TrafficView.Line> rows = new ArrayList<>(ranked.size() + 1);
        ranked.forEach(each -> rows.add(each.line()));
        if (otherRequests > 0) {
            rows.add(summed(RecordFacet.MORE_SLUG, shown, TrafficCopy.OTHER, otherRequests, requests, List.of()));
        }
        return rows;
    }

    /** @return a row adding other rows up: its name, its requests and their share, no site, path or time */
    private static TrafficView.@NonNull Line summed(@NonNull String key, @NonNull List<Shown> shown,
                                                    @NonNull Microcopy name, long count, long requests,
                                                    @NonNull List<TrafficView.Line> folded) {
        List<TrafficView.Cell> cells = new ArrayList<>(shown.size());
        for (Shown each : shown) {
            cells.add(switch (each) {
                case NAME -> cell(name, false, null);
                case REQUESTS -> figure(count(count));
                case SHARE -> share(count, requests);
                case SITE, TYPE, SENT, MEDIAN, SLOWEST, LAST -> cell(Microcopy.literal(""), false, null);
            });
        }
        return new TrafficView.Line(key, cells, folded);
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
                                                  DataTable.@NonNull Line line, long requests, @NonNull Links links,
                                                  @NonNull Reader reader, @Nullable MessageResolver resolver) {
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
                // As a list of recent moments words them: "Today 14:39", "Tue 24 Nov".
                Double seen = number(line, "last_seen");
                yield figure(Microcopy.literal(seen == null ? ""
                    : Wording.recent(reader, Instant.ofEpochMilli(Math.round(seen)), resolver, DateStyle.SHORT)));
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
