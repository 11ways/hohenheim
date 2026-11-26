package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.dns.DnsTraffic;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.key.IdentityKey;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.util.BlastCollection;
import be.elevenways.zenit.common.data.DataBuckets;
import be.elevenways.zenit.common.data.DataSeries;
import be.elevenways.zenit.common.data.DataTable;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.OrmConstants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.StorageKeyed;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.time.OfficeZone;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.traffic.common.StatusClass;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The reads of counted traffic: a site's requests over a span ending at a moment, every site's at once, and a DNS
 * zone's questions, each with the span before for comparison, its steps, its response times and the top tables asked.
 *
 * AIDEV-NOTE: ONE read path per subject: the tools ask one table ({@code top}), a screen asks every table it draws at
 * once ({@link #SCREEN_TABLES}, an attachment no tool argument spells), and both get the same span read once
 * (TrafficReader.Collected), so a screen's cost is one read of its span, one of the span before, and one more for the
 * names nobody serves. Who may read is the record's rule, as for the access log: a site's traffic (visitor addresses
 * in full, the owner's ruling of 2026-11-25) to whoever manages the site (SiteOperationHandlers.reachesSite), a zone's
 * to an administrator only, since a zone is never granted to anyone else (HohenheimGrantPolicy) and its questions name
 * every customer's names. Every site's at once, the names no site serves among them, is an administrator's read too:
 * it names no record, so traffic_read takes it when a call names no site (McpCodeTool.Builder.routeWithoutRecord).
 * Every row is a name a person reads: a site by its name (one query for the sites a read lists), the source of the
 * names nobody serves and a removed site in the caller's words, a client family and a catch-all in the caller's
 * words, a zone's outcome by DnsTraffic's words.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class TrafficReads {

    /** The rows a top table lists before its "other" line. */
    public static final int TOP_ROWS = 20;

    /** The least requests a page needs in the span to stand among the slowest (the board's "at least 50"). */
    public static final int SLOWEST_LEAST_REQUESTS = 50;

    /** What a read covers: one site, every site at once, or one DNS zone. */
    public enum Reach {
        SITE,
        SERVER,
        ZONE
    }

    /** What a read lists beside its totals; a site's tables in a site's words, a zone's in a zone's. */
    public enum Top {
        PAGES("pages", TrafficTable.PATHS, Reach.SITE, TopRead.Order.REQUESTS),
        /** The pages answered 4xx: refused or not found. */
        REFUSED_PAGES("refused_pages", TrafficTable.REFUSED_PATHS, Reach.SITE, TopRead.Order.REQUESTS),
        /** The pages of at least {@link #SLOWEST_LEAST_REQUESTS} requests, the slowest 5 percent first. */
        SLOWEST_PAGES("slowest_pages", TrafficTable.PATHS, Reach.SITE, TopRead.Order.SLOWEST),
        REFERRERS("referrers", TrafficTable.REFERRERS, Reach.SITE, TopRead.Order.REQUESTS),
        BROWSERS("browsers", TrafficTable.AGENTS, Reach.SITE, TopRead.Order.REQUESTS),
        STATUSES("statuses", TrafficTable.STATUSES, Reach.SITE, TopRead.Order.REQUESTS),
        VISITORS("visitors", TrafficTable.VISITORS, Reach.SITE, TopRead.Order.REQUESTS),
        /** Every site ranked by requests and by bytes sent: two rankings, no table. */
        SITES("sites", null, Reach.SERVER, TopRead.Order.REQUESTS),
        /** The host names asked that no site serves, each with when it was last asked. */
        UNSERVED_NAMES("unserved_names", TrafficTable.HOSTS, Reach.SERVER, TopRead.Order.REQUESTS),
        /** The names asked, each with its type. */
        NAMES("names", TrafficTable.PATHS, Reach.ZONE, TopRead.Order.REQUESTS),
        /** The names answered "no such name" or refused. */
        MISSING_NAMES("missing_names", TrafficTable.REFUSED_PATHS, Reach.ZONE, TopRead.Order.REQUESTS),
        RESOLVERS("resolvers", TrafficTable.VISITORS, Reach.ZONE, TopRead.Order.REQUESTS),
        /** The questions per outcome: answered, no such name, refused. */
        OUTCOMES("outcomes", TrafficTable.STATUSES, Reach.ZONE, TopRead.Order.REQUESTS);

        private final @NonNull String token;
        private final @Nullable TrafficTable table;
        private final @NonNull Reach reach;
        private final TopRead.@NonNull Order order;

        Top(@NonNull String token, @Nullable TrafficTable table, @NonNull Reach reach, TopRead.@NonNull Order order) {
            this.token = token;
            this.table = table;
            this.reach = reach;
            this.order = order;
        }

        /** @return the value the tools spell */
        public @NonNull String token() {
            return this.token;
        }

        /** @return the engine's table it lists, null for the sites' rankings */
        public @Nullable TrafficTable table() {
            return this.table;
        }

        /** @return its words: the title its screen gives the table, the same words in a tool's choice */
        public @NonNull Microcopy label() {
            return switch (this) {
                case PAGES -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_pages");
                case REFUSED_PAGES -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_refused_pages");
                case SLOWEST_PAGES -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_slowest_pages");
                case REFERRERS -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_referrers");
                case BROWSERS -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_browsers");
                case STATUSES -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_statuses");
                case VISITORS -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_visitors");
                case SITES -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_sites_by_requests");
                case UNSERVED_NAMES -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_unserved_names");
                case NAMES -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_names");
                case MISSING_NAMES -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_missing_names");
                case RESOLVERS -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_resolvers");
                case OUTCOMES -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("table_outcomes");
            };
        }

        /** @return whether a read of this reach lists it: every site's read lists a site's tables too */
        public boolean listedOn(@NonNull Reach read) {
            return this.reach == read || (read == Reach.SERVER && this.reach == Reach.SITE);
        }

        /** @return whether every site's read lists it one row per site and key, as "top pages across sites" */
        public boolean perSite(@NonNull Reach read) {
            return read == Reach.SERVER
                && (this.table == TrafficTable.PATHS || this.table == TrafficTable.REFUSED_PATHS);
        }

        /**
         * @return the member a token spells among those a read's field offers, null for none
         * @throws Violations naming {@code top} for a member of every site's read asked of one site
         */
        static @Nullable Top forToken(@Nullable String token, @NonNull Reach read) {
            for (Top each : values()) {
                if (!each.token.equals(token) || (each.reach == Reach.ZONE) != (read == Reach.ZONE)) continue;
                if (!each.listedOn(read)) {
                    throw Violations.ofField("top", token,
                        HohenheimMicrocopy.TRAFFIC_SCREEN.of("traffic_top_every_site"));
                }
                return each;
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

    /** A site's or every site's top table to list. */
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
     * The tables a screen lists beside {@code top}, read in the same pass: a placement's attachment, which no tool
     * argument spells; absent for a tool's call.
     */
    public static final IdentityKey<Set<Top>> SCREEN_TABLES = IdentityKey.create("hohenheim_traffic_screen_tables");

    /**
     * What a read found.
     *
     * @param query  the span read, in its steps
     * @param totals the span's totals
     * @param before the totals of the span just before, as long
     * @param steps  requests per step, one series per status class that answered any
     * @param times  the median and the slowest 5 percent per step, in milliseconds
     * @param top    the table a tool asked for, null for none
     * @param tables every table asked ({@code top} and {@code also}), in their declared order; the sites' rankings
     *               are no table and stand in {@code sitesByRequests} and {@code sitesBySent}
     */
    public record Read(@NonNull TrafficQuery query, @NonNull TrafficTotals totals, @NonNull TrafficTotals before,
                       @NonNull DataBuckets steps, @NonNull DataBuckets times, @Nullable Top top,
                       @NonNull Map<Top, DataTable> tables, @Nullable DataBuckets sitesByRequests,
                       @Nullable DataBuckets sitesBySent) {

        public Read {
            EnumMap<Top, DataTable> copied = new EnumMap<>(Top.class);
            copied.putAll(tables);
            tables = Collections.unmodifiableMap(copied);
        }
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
                Objects.requireNonNull(call.input(), "a read has its input"), call.attachment(SCREEN_TABLES),
                Reach.SITE, call.access()));
        OperationHandlers.attach(READ_SERVER)
            .handle(call -> read(HohenheimTraffic.Counted.WEB, Set.of(),
                Objects.requireNonNull(call.input(), "a read has its input"), call.attachment(SCREEN_TABLES),
                Reach.SERVER, call.access()));
        OperationHandlers.attach(READ_ZONE)
            .authorize(DnsOperationHandlers.admin())
            .handle(call -> read(HohenheimTraffic.Counted.DNS,
                Set.of(HohenheimTraffic.zoneSource(call.subject().get(DnsZoneModel.ID))),
                Objects.requireNonNull(call.input(), "a read has its input"), call.attachment(SCREEN_TABLES),
                Reach.ZONE, call.access()));
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
     * @param screen  the tables a screen lists beside the input's top, null for a tool's call
     * @param caller  whose words the tables, the series and the sites' names are in, null for the catalog's default
     * @throws DomainRefusal SERVICE_UNAVAILABLE while counting is stopped (the process is ending)
     * @throws IllegalArgumentException for a screen asking a table its reach does not list
     */
    private static @NonNull Read read(HohenheimTraffic.@NonNull Counted counted, @NonNull Set<String> sources,
                                      @NonNull ReadInput input, @Nullable Set<Top> screen, @NonNull Reach reach,
                                      @Nullable AccessContext caller) {
        TrafficStore store = HohenheimTraffic.store(counted);
        if (store == null) {
            throw new DomainRefusal(ZenitRefusalReason.SERVICE_UNAVAILABLE, "traffic counting is stopped");
        }
        TrafficWindow window = input.period() == null ? TrafficWindow.DAY
            : Objects.requireNonNull(StorageKeyed.findOrNull(Arrays.asList(TrafficWindow.values()), input.period()),
                "the period field offers only the windows");
        TrafficQuery query = TrafficQuery.ending(sources, window,
            input.until() != null ? input.until() : Now.instant(), OfficeZone.office());
        Top top = Top.forToken(input.top(), reach);
        Set<Top> asked = EnumSet.noneOf(Top.class);
        if (top != null) asked.add(top);
        for (Top also : screen == null ? Set.<Top>of() : screen) {
            if (!also.listedOn(reach)) {
                throw new IllegalArgumentException("A " + reach + " read lists no " + also.token() + " table");
            }
            asked.add(also);
        }
        TrafficReader reader = store.reader();
        TrafficReader.Collected span = reader.collected(query, asked.stream().anyMatch(each -> each.table != null));
        if (reach == Reach.SERVER) {
            span = span.naming(siteNames(span.sources(), caller)::get);
        }
        // Every label is a name the caller reads: a family's, a catch-all's in the caller's words, never a key.
        LocaleChain chain = caller != null ? caller.locales() : LocaleChain.empty();
        MessageResolver resolver = caller != null ? caller.messageResolver() : null;
        DataBuckets steps = span.series(TrafficMeasure.REQUESTS_BY_STATUS, chain, resolver);
        if (reach == Reach.ZONE) {
            steps = outcomeClasses(steps, caller);
        }
        Map<Top, DataTable> tables = new EnumMap<>(Top.class);
        for (Top each : asked) {
            TrafficTable table = each.table;
            if (table == null) continue;
            TopRead listed = new TopRead(table, TOP_ROWS, each.order,
                each.order == TopRead.Order.SLOWEST ? SLOWEST_LEAST_REQUESTS : 0, each.perSite(reach));
            DataTable found = each == Top.UNSERVED_NAMES
                ? reader.top(new TrafficQuery(Set.of(HohenheimTraffic.UNSERVED), query.from(), query.to(),
                    query.granularity()), listed, chain, resolver)
                : span.top(listed, chain, resolver);
            tables.put(each, each == Top.OUTCOMES ? outcomes(found, caller) : found);
        }
        boolean sites = asked.contains(Top.SITES);
        return new Read(query, span.totals(), reader.totals(query.before()), steps,
            span.series(TrafficMeasure.RESPONSE_TIME, chain, resolver), top, tables,
            sites ? span.rank(TrafficMeasure.REQUESTS, TOP_ROWS, chain, resolver) : null,
            sites ? span.rank(TrafficMeasure.BYTES_SENT, TOP_ROWS, chain, resolver) : null);
    }

    /**
     * @return each source's name: a site's own, the names nobody serves and a site removed since in the caller's
     *         words; one query for the sites among them
     */
    private static @NonNull Map<String, String> siteNames(@NonNull Set<String> sources,
                                                          @Nullable AccessContext caller) {
        Map<String, String> names = new HashMap<>();
        List<Integer> ids = new ArrayList<>();
        for (String source : sources) {
            Integer site = HohenheimTraffic.siteOf(source);
            if (site != null) {
                ids.add(site);
            } else if (HohenheimTraffic.UNSERVED.equals(source)) {
                names.put(source, words(caller, HohenheimMicrocopy.TRAFFIC_SCREEN.of("traffic_unserved_source")));
            }
        }
        for (List<Integer> chunk : BlastCollection.batches(ids, OrmConstants.KEY_CHUNK)) {
            for (Row site : Models.get(SiteModel.class).find().where(SiteModel.ID.in(chunk)).all()) {
                names.put(HohenheimTraffic.sourceOf(site.get(SiteModel.ID)), site.get(SiteModel.NAME));
            }
        }
        String removed = words(caller, HohenheimMicrocopy.TRAFFIC_SCREEN.of("traffic_removed_site"));
        for (Integer id : ids) {
            names.putIfAbsent(HohenheimTraffic.sourceOf(id), removed);
        }
        return names;
    }

    /** @return a zone's series per status class, each named by the DNS outcomes it holds */
    private static @NonNull DataBuckets outcomeClasses(@NonNull DataBuckets steps, @Nullable AccessContext caller) {
        List<DataSeries> named = new ArrayList<>(steps.series().size());
        for (DataSeries series : steps.series()) {
            StatusClass statusClass = StorageKeyed.findOrNull(Arrays.asList(StatusClass.values()), series.key());
            named.add(statusClass == null ? series : new DataSeries(series.key(),
                words(caller, DnsTraffic.classWords(statusClass)), series.buckets(), series.currency()));
        }
        return new DataBuckets(named, steps.omitted());
    }

    /** @return a zone's statuses table, each row named by the DNS outcome its status stands for */
    private static @NonNull DataTable outcomes(@NonNull DataTable statuses, @Nullable AccessContext caller) {
        List<DataTable.Line> named = new ArrayList<>(statuses.lines().size());
        for (DataTable.Line line : statuses.lines()) {
            DataTable.Group status = line.groups().getLast();
            DnsTraffic.Outcome outcome = DnsTraffic.Outcome.ofStatus(status.key());
            if (outcome == null) {
                named.add(line);
                continue;
            }
            List<DataTable.Group> groups = new ArrayList<>(line.groups());
            groups.set(groups.size() - 1, new DataTable.Group(status.key(), words(caller, outcome.label())));
            named.add(new DataTable.Line(groups, line.bucket(), line.records(), line.values()));
        }
        return new DataTable(named);
    }

    private static @NonNull String words(@Nullable AccessContext caller, @NonNull Microcopy copy) {
        return caller != null ? caller.text(copy) : copy.resolve(LocaleChain.empty(), null);
    }

    /** @return a window's words, as the screens' period switch and a tool's choice both show it */
    public static @NonNull Microcopy periodLabel(@NonNull TrafficWindow window) {
        return switch (window) {
            case HOUR -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("period_hour");
            case DAY -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("period_day");
            case WEEK -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("period_week");
            case MONTH -> HohenheimMicrocopy.TRAFFIC_SCREEN.of("period_month");
        };
    }

    /** @return the period field carrying every window, so no list of them is spelled twice */
    private static @NonNull EnumField periodField() {
        EnumField.Builder builder = EnumField.builder("period").help(HohenheimMicrocopy.HELP.of("traffic_period"))
            .defaultValue(TrafficWindow.DAY.storageKey());
        for (TrafficWindow each : TrafficWindow.values()) {
            builder.value(each.storageKey(), each.storageKey(), periodLabel(each));
        }
        return builder.build();
    }

    /** @return the top field of a site's and every site's tables, or of a zone's */
    private static @NonNull EnumField topField(boolean zone, @NonNull String help) {
        EnumField.Builder builder = EnumField.builder("top").help(HohenheimMicrocopy.HELP.of(help));
        for (Top each : Top.values()) {
            if ((each.reach == Reach.ZONE) == zone) builder.value(each.token(), each.token(), each.label());
        }
        return builder.build();
    }
}
