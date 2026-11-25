package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.data.DataBuckets;
import be.elevenways.zenit.common.data.DataTable;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.StorageKeyed;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.time.OfficeZone;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.traffic.common.TrafficMeasure;
import be.elevenways.zenit.traffic.common.TrafficTable;
import be.elevenways.zenit.traffic.common.TrafficWindow;
import be.elevenways.zenit.traffic.server.TopRead;
import be.elevenways.zenit.traffic.server.TrafficQuery;
import be.elevenways.zenit.traffic.server.TrafficReader;
import be.elevenways.zenit.traffic.server.TrafficStore;
import be.elevenways.zenit.traffic.server.TrafficTotals;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * The reads of counted traffic: a site's requests over a span ending at a moment, every site's at once, and a DNS
 * zone's questions, each with the span before for comparison, one line per step and optionally one top table.
 *
 * AIDEV-NOTE: who may read is the record's rule, as for the access log: a site's traffic (visitor addresses in full,
 * the owner's ruling of 2026-11-25) to whoever manages the site (SiteOperationHandlers.reachesSite), a zone's to an
 * administrator only, since a zone is never granted to anyone else (HohenheimGrantPolicy) and its questions name every
 * customer's names. Every site's at once, the names no site serves among them, is an administrator's read too: it
 * names no record, so traffic_read takes it when a call names no site (McpCodeTool.Builder.routeWithoutRecord).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class TrafficReads {

    /** The rows a top table lists before its "other" line. */
    public static final int TOP_ROWS = 20;

    /** What a read lists beside its totals; a site's tables in a site's words, a zone's in a zone's. */
    public enum Top {
        PAGES("pages", TrafficTable.PATHS, false),
        REFUSED_PAGES("refused_pages", TrafficTable.REFUSED_PATHS, false),
        REFERRERS("referrers", TrafficTable.REFERRERS, false),
        BROWSERS("browsers", TrafficTable.AGENTS, false),
        STATUSES("statuses", TrafficTable.STATUSES, false),
        VISITORS("visitors", TrafficTable.VISITORS, false),
        /** The names asked, each with its type. */
        NAMES("names", TrafficTable.PATHS, true),
        /** The names answered "no such name" or refused. */
        MISSING_NAMES("missing_names", TrafficTable.REFUSED_PATHS, true),
        RESOLVERS("resolvers", TrafficTable.VISITORS, true);

        private final @NonNull String token;
        private final @NonNull TrafficTable table;
        private final boolean zone;

        Top(@NonNull String token, @NonNull TrafficTable table, boolean zone) {
            this.token = token;
            this.table = table;
            this.zone = zone;
        }

        /** @return the value the tools spell */
        public @NonNull String token() {
            return this.token;
        }

        /** @return the engine's table it lists */
        public @NonNull TrafficTable table() {
            return this.table;
        }

        /** @return the member a token spells among a site's or a zone's, null for none (fail closed) */
        static @Nullable Top forToken(@Nullable String token, boolean zone) {
            for (Top each : values()) {
                if (each.zone == zone && each.token.equals(token)) return each;
            }
            return null;
        }
    }

    /** The span to read. */
    public static final EnumField PERIOD = periodField();

    /** The moment the span ends. */
    public static final DateTimeField UNTIL = DateTimeField.builder("until")
        .help(HohenheimMicrocopy.HELP.of("traffic_until"))
        .build();

    /** A site's top table to list. */
    public static final EnumField SITE_TOP = topField(false, "traffic_top_site");

    /** A zone's top table to list. */
    public static final EnumField ZONE_TOP = topField(true, "traffic_top_zone");

    /**
     * A read's input.
     *
     * @param period the span's token, null for a day
     * @param until  the span's end, null for now
     * @param top    the top table's token, null for none
     */
    public record ReadInput(@Nullable String period, @Nullable Instant until, @Nullable String top) {
    }

    /**
     * What a read found.
     *
     * @param query          the span read, in its steps
     * @param totals         the span's totals
     * @param requestsBefore the requests of the span just before, as long
     * @param steps          requests per step, one series per status class that answered any
     * @param top            the table listed, null for none
     * @param table          its rows, the last one holding every row not listed; null for none
     */
    public record Read(@NonNull TrafficQuery query, @NonNull TrafficTotals totals, long requestsBefore,
                       @NonNull DataBuckets steps, @Nullable Top top, @Nullable DataTable table) {
    }

    /** Reads a site's requests over a span. */
    public static final Operation<Row, ReadInput, Read> READ_SITE =
        Operation.declare(HohenheimIds.id("read_site_traffic"))
            .happened(OperationSentences.of("read_site_traffic"))
            .label(HohenheimMicrocopy.HOHENHEIM.of("read_site_traffic_label"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("read_site_traffic_description"))
            .icon(Icon.of("chart-line"))
            .one(SiteOperations.SITE)
            // Who may read is the server-attached authorizer: the site's manage reach, the access log's rule.
            .gate(OperationGate.open())
            .input(input(SITE_TOP))
            .result(Read.class)
            .facts(OperationFact.READ_ONLY)
            .register();

    /** Reads every site's requests at once over a span, the names no site serves among them. */
    public static final Operation<Void, ReadInput, Read> READ_SERVER =
        Operation.declare(HohenheimIds.id("read_server_traffic"))
            .happened(OperationSentences.of("read_server_traffic"))
            .label(HohenheimMicrocopy.HOHENHEIM.of("read_server_traffic_label"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("read_server_traffic_description"))
            .icon(Icon.of("chart-line"))
            .noSubject()
            .gate(HohenheimSources.OPERATOR)
            .input(input(SITE_TOP))
            .result(Read.class)
            .facts(OperationFact.READ_ONLY)
            .register();

    /** Reads a DNS zone's questions over a span. */
    public static final Operation<Row, ReadInput, Read> READ_ZONE =
        Operation.declare(HohenheimIds.id("read_dns_zone_queries"))
            .happened(OperationSentences.of("read_dns_zone_queries"))
            .label(HohenheimMicrocopy.HOHENHEIM.of("read_dns_zone_queries_label"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("read_dns_zone_queries_description"))
            .icon(Icon.of("chart-line"))
            .one(DnsOperations.ZONE)
            // Who may read is the server-attached authorizer: an administrator.
            .gate(OperationGate.open())
            .input(input(ZONE_TOP))
            .result(Read.class)
            .facts(OperationFact.READ_ONLY)
            .register();

    static {
        OperationHandlers.attach(READ_SITE)
            .applies(SiteParts::servesHttp)
            .authorize(SiteOperationHandlers.reachesSite())
            .handle(call -> read(HohenheimTraffic.Counted.WEB,
                Set.of(HohenheimTraffic.sourceOf(call.subject().get(SiteModel.ID))),
                Objects.requireNonNull(call.input(), "a read has its input"), false));
        OperationHandlers.attach(READ_SERVER)
            .handle(call -> read(HohenheimTraffic.Counted.WEB, Set.of(),
                Objects.requireNonNull(call.input(), "a read has its input"), false));
        OperationHandlers.attach(READ_ZONE)
            .authorize(DnsOperationHandlers.admin())
            .handle(call -> read(HohenheimTraffic.Counted.DNS,
                Set.of(HohenheimTraffic.zoneSource(call.subject().get(DnsZoneModel.ID))),
                Objects.requireNonNull(call.input(), "a read has its input"), true));
    }

    private TrafficReads() {
    }

    /** @return a read's input: the span and its end, and one of these top tables */
    private static @NonNull OperationInput<ReadInput> input(@NonNull EnumField top) {
        return OperationInput.of(FormSpec.builder().add(PERIOD).add(UNTIL).add(top).build(), ReadInput.class,
            values -> new ReadInput(values.get(PERIOD), values.get(UNTIL), values.get(top)));
    }

    /**
     * @param sources the sources read, empty for every one
     * @throws DomainRefusal SERVICE_UNAVAILABLE while counting is stopped (the process is ending)
     */
    private static @NonNull Read read(HohenheimTraffic.@NonNull Counted counted, @NonNull Set<String> sources,
                                      @NonNull ReadInput input, boolean zone) {
        TrafficStore store = HohenheimTraffic.store(counted);
        if (store == null) {
            throw new DomainRefusal(ZenitRefusalReason.SERVICE_UNAVAILABLE, "traffic counting is stopped");
        }
        TrafficWindow window = input.period() == null ? TrafficWindow.DAY
            : Objects.requireNonNull(StorageKeyed.findOrNull(Arrays.asList(TrafficWindow.values()), input.period()),
                "the period field offers only the windows");
        TrafficQuery query = TrafficQuery.ending(sources, window,
            input.until() != null ? input.until() : Now.instant(), OfficeZone.office());
        TrafficReader reader = store.reader();
        Top top = Top.forToken(input.top(), zone);
        return new Read(query, reader.totals(query), reader.totals(query.before()).requests(),
            reader.series(query, TrafficMeasure.REQUESTS_BY_STATUS, null, null), top,
            top == null ? null : reader.top(query, TopRead.busiest(top.table(), TOP_ROWS), null, null));
    }

    /** @return the period field carrying every window, so no list of them is spelled twice */
    private static @NonNull EnumField periodField() {
        EnumField.Builder builder = EnumField.builder("period").help(HohenheimMicrocopy.HELP.of("traffic_period"))
            .defaultValue(TrafficWindow.DAY.storageKey());
        for (TrafficWindow each : TrafficWindow.values()) {
            builder.value(each.storageKey(), each.storageKey());
        }
        return builder.build();
    }

    /** @return the top field of a site's or a zone's tables */
    private static @NonNull EnumField topField(boolean zone, @NonNull String help) {
        EnumField.Builder builder = EnumField.builder("top").help(HohenheimMicrocopy.HELP.of(help));
        for (Top each : Top.values()) {
            if (each.zone == zone) builder.value(each.token(), each.token());
        }
        return builder.build();
    }
}
