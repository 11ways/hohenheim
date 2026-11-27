package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.runtime.WorkloadLane;
import be.elevenways.hohenheim.server.traffic.AppDestinations;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.zenit.common.data.DataBuckets;
import be.elevenways.zenit.common.data.DataTable;
import be.elevenways.zenit.common.data.RecordFacet;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.traffic.common.TrafficMeasure;
import be.elevenways.zenit.traffic.common.TrafficTable;
import be.elevenways.zenit.traffic.server.TopRead;
import be.elevenways.zenit.traffic.server.TrafficQuery;
import be.elevenways.zenit.traffic.server.TrafficReader;
import be.elevenways.zenit.traffic.server.TrafficStore;
import be.elevenways.zenit.traffic.server.TrafficTotals;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The read of an app's network use: what it received and sent over a span ending at a moment, against the span before,
 * one line per step, what each of its sites answered visitors over the same span, and, when asked, where its
 * outgoing connections went.
 *
 * AIDEV-NOTE: the bytes are the apps store's (AppCounters, from the app's own counters: everything it received and
 * sent, visitors and other services together); a site's are the web store's, the proxy's count of its answers; the
 * destinations are the apps store's destinations table (AppDestinations, from the kernel's per-network flow
 * counters), addresses in full (the owner's ruling). Who may read is the app's manage capability, for the tab and the
 * tool alike, so a customer reads the apps he manages. A destination names a record (another app, a database, a
 * stream site) only to a caller who may view that record; to anyone else it is its kind and its address, so a
 * customer never learns the name of another customer's app on the same server.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class AppNetworkReads {

    /**
     * A read's input: the traffic reads' span arguments and the app's top table, as the form names them.
     *
     * @param period the span's token, null for a day
     * @param until  the span's end, null for now
     * @param top    the top table's token, null for none
     */
    public record ReadInput(@Nullable String period, @Nullable Instant until, @Nullable String top) {
    }

    /**
     * What one site of the app answered over the span.
     *
     * @param bytesSent what it answered visitors through the proxy
     */
    public record SiteTraffic(int siteId, @NonNull String name, long requests, long bytesSent) {
    }

    /**
     * What the app moved with one destination, or with every one not listed.
     *
     * @param connections the connections it opened
     */
    public record Moved(long connections, long bytesSent, long bytesReceived) {
    }

    /**
     * One destination of the app over the span.
     *
     * @param name     the record it names in the caller's view, null when it names none or one the caller may not view
     * @param lastSeen the last minute its counters grew
     */
    public record Destination(AppDestinations.@NonNull Destination where, @Nullable String name,
                              @NonNull Moved moved, @Nullable Instant lastSeen) {
    }

    /**
     * Where the app's connections went over the span, the most sent first.
     *
     * @param other what the destinations not listed moved together, null when every one is listed
     */
    public record Destinations(@NonNull List<Destination> listed, @Nullable Moved other) {

        /** @return whether anything was counted */
        public boolean counted() {
            return !this.listed.isEmpty() || this.other != null;
        }
    }

    /**
     * What a read found.
     *
     * @param query        the span read, in its steps
     * @param totals       the app's bytes over the span
     * @param before       its bytes over the span just before, as long
     * @param steps        received and sent per step, one series each, received first (TrafficMeasure.BYTES)
     * @param sites        the app's sites, busiest first
     * @param destinations where its connections went, null when the input asked no destinations
     */
    public record Read(@NonNull TrafficQuery query, @NonNull TrafficTotals totals, @NonNull TrafficTotals before,
                       @NonNull DataBuckets steps, @NonNull List<SiteTraffic> sites,
                       @Nullable Destinations destinations) {

        /** @return what the app's sites answered visitors over the span */
        public long toVisitors() {
            long sent = 0;
            for (SiteTraffic site : this.sites) {
                sent += site.bytesSent();
            }
            return sent;
        }

        /** @return whether the app's counters grew at all over the span */
        public boolean counted() {
            return this.totals.bytesSent() > 0 || this.totals.bytesReceived() > 0;
        }
    }

    /** Reads an app's network use over a span, with the traffic reads' span arguments and its own top table. */
    public static final Operation<Row, ReadInput, Read> READ =
        Operation.declare(HohenheimIds.id("read_app_network"))
            .happened(OperationSentences.of("read_app_network"))
            .label(HohenheimMicrocopy.HOHENHEIM.of("read_app_network_label"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("read_app_network_description"))
            .icon(Icon.of("network-wired"))
            .one(InstanceOperations.INSTANCE)
            .gate(InstanceOperations.gate(HohenheimCapabilities.MANAGE))
            .input(OperationInput.of(FormSpec.builder().add(TrafficReads.PERIOD).add(TrafficReads.UNTIL)
                    .add(TrafficReads.APP_TOP).build(),
                ReadInput.class, values -> new ReadInput(values.get(TrafficReads.PERIOD),
                    values.get(TrafficReads.UNTIL), values.get(TrafficReads.APP_TOP))))
            .result(Read.class)
            .facts(OperationFact.READ_ONLY)
            .register();

    static {
        OperationHandlers.attach(READ)
            .applies(AppNetworkReads::counts)
            .handle(call -> read(call.subject(), Objects.requireNonNull(call.input(), "a read has its input"),
                call.access()));
    }

    private AppNetworkReads() {
    }

    /** @return whether the app's driver reads its counters (the stats lane), so it has network use to read */
    static boolean counts(@NonNull Row app) {
        return InstanceKinds.offers(app, WorkloadLane.LIVE_STATS);
    }

    /**
     * Reads the app's bytes, its sites' answers and, when asked, its destinations over the span the input names.
     *
     * @param caller whose words the steps are named in and whose view the destinations' names are in; null for the
     *     catalog's default words and no record names
     * @throws DomainRefusal SERVICE_UNAVAILABLE while counting is stopped (the process is ending)
     */
    static @NonNull Read read(@NonNull Row app, @NonNull ReadInput input, @Nullable AccessContext caller) {
        TrafficStore apps = TrafficReads.opened(HohenheimTraffic.Counted.APPS);
        TrafficStore web = TrafficReads.opened(HohenheimTraffic.Counted.WEB);
        int appId = app.get(InstanceModel.ID);
        TrafficQuery query = TrafficReads.query(Set.of(HohenheimTraffic.appSource(appId)), input.period(),
            input.until());
        TrafficReads.Top top = TrafficReads.Top.forToken(input.top(), TrafficReads.Reach.APP);
        TrafficReader.Collected span = apps.reader().collected(query, top != null);
        List<SiteTraffic> sites = new ArrayList<>();
        for (Row site : Models.get(SiteModel.class).find().where(SiteModel.INSTANCE_ID.eq(appId))
                .orderBy(SiteModel.ID, SortOrder.ASC).all()) {
            int siteId = site.get(SiteModel.ID);
            TrafficTotals served = web.reader().totals(new TrafficQuery(Set.of(HohenheimTraffic.sourceOf(siteId)),
                query.from(), query.to(), query.granularity()));
            sites.add(new SiteTraffic(siteId, Objects.requireNonNullElse(site.get(SiteModel.NAME), ""),
                served.requests(), served.bytesSent()));
        }
        sites.sort(Comparator.comparingLong(SiteTraffic::requests).reversed());
        Destinations destinations = top == TrafficReads.Top.DESTINATIONS
            ? destinations(span.top(new TopRead(TrafficTable.DESTINATIONS, TrafficReads.TOP_ROWS,
                TopRead.Order.BYTES_SENT, 0, false), null, null), caller)
            : null;
        return new Read(query, span.totals(), apps.reader().totals(query.before()),
            span.series(TrafficMeasure.BYTES, caller != null ? caller.locales() : null,
                caller != null ? caller.messageResolver() : null), sites, destinations);
    }

    /** @return the destinations table's lines, each named in the caller's view; one query per model named */
    private static @NonNull Destinations destinations(@NonNull DataTable table, @Nullable AccessContext caller) {
        List<AppDestinations.Destination> wheres = new ArrayList<>();
        List<DataTable.Line> lines = new ArrayList<>();
        Moved other = null;
        for (DataTable.Line line : table.lines()) {
            String key = line.groups().getLast().key();
            if (RecordFacet.MORE_SLUG.equals(key)) {
                other = moved(line);
                continue;
            }
            AppDestinations.Destination where = AppDestinations.Destination.of(key);
            if (where != null) {
                wheres.add(where);
                lines.add(line);
            }
        }
        Map<String, String> names = DestinationNames.of(wheres, DestinationNames.Viewer.of(caller));
        List<Destination> listed = new ArrayList<>(wheres.size());
        for (int at = 0; at < wheres.size(); at++) {
            AppDestinations.Destination where = wheres.get(at);
            DataTable.Value seen = lines.get(at).values().get("last_seen");
            Double millis = seen == null ? null : seen.number();
            listed.add(new Destination(where, DestinationNames.nameOf(names, where),
                moved(lines.get(at)), millis == null ? null : Instant.ofEpochMilli(Math.round(millis))));
        }
        return new Destinations(listed, other);
    }

    private static @NonNull Moved moved(DataTable.@NonNull Line line) {
        return new Moved(line.records(), amount(line, "bytes_sent"), amount(line, "bytes_received"));
    }

    private static long amount(DataTable.@NonNull Line line, @NonNull String value) {
        DataTable.Value found = line.values().get(value);
        Double number = found == null ? null : found.number();
        return number == null ? 0 : Math.round(number);
    }
}
