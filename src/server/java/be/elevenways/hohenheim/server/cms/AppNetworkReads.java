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
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.zenit.common.data.DataBuckets;
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
import java.util.Objects;
import java.util.Set;

/**
 * The read of an app's network use: what it received and sent over a span ending at a moment, against the span before,
 * one line per step, and what each of its sites answered visitors over the same span.
 *
 * AIDEV-NOTE: the bytes are the apps store's (AppCounters, from the app's own counters: everything it received and
 * sent, visitors and other services together); a site's are the web store's, the proxy's count of its answers. Who may
 * read is the app's manage capability, for the tab and the tool alike, so a customer reads the apps he manages. Where
 * the outgoing connections go is not read yet: no destination source on the host is in place (per-app nft sets with
 * per-element counters are the candidate), so nothing here guesses one.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class AppNetworkReads {

    /**
     * A read's input: the traffic reads' span arguments, without their top table, as the form names them.
     *
     * @param period the span's token, null for a day
     * @param until  the span's end, null for now
     */
    public record ReadInput(@Nullable String period, @Nullable Instant until) {
    }

    /**
     * What one site of the app answered over the span.
     *
     * @param bytesSent what it answered visitors through the proxy
     */
    public record SiteTraffic(int siteId, @NonNull String name, long requests, long bytesSent) {
    }

    /**
     * What a read found.
     *
     * @param query  the span read, in its steps
     * @param totals the app's bytes over the span
     * @param before its bytes over the span just before, as long
     * @param steps  received and sent per step, one series each, received first (TrafficMeasure.BYTES)
     * @param sites  the app's sites, busiest first
     */
    public record Read(@NonNull TrafficQuery query, @NonNull TrafficTotals totals, @NonNull TrafficTotals before,
                       @NonNull DataBuckets steps, @NonNull List<SiteTraffic> sites) {

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

    /** Reads an app's network use over a span, with the traffic reads' span arguments and no top table. */
    public static final Operation<Row, ReadInput, Read> READ =
        Operation.declare(HohenheimIds.id("read_app_network"))
            .happened(OperationSentences.of("read_app_network"))
            .label(HohenheimMicrocopy.HOHENHEIM.of("read_app_network_label"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("read_app_network_description"))
            .icon(Icon.of("network-wired"))
            .one(InstanceOperations.INSTANCE)
            .gate(InstanceOperations.gate(HohenheimCapabilities.MANAGE))
            .input(OperationInput.of(FormSpec.builder().add(TrafficReads.PERIOD).add(TrafficReads.UNTIL).build(),
                ReadInput.class, values -> new ReadInput(values.get(TrafficReads.PERIOD),
                    values.get(TrafficReads.UNTIL))))
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
     * Reads the app's bytes and its sites' answers over the span the input names.
     *
     * @param caller whose words the steps are named in, null for the catalog's default
     * @throws DomainRefusal SERVICE_UNAVAILABLE while counting is stopped (the process is ending)
     */
    static @NonNull Read read(@NonNull Row app, @NonNull ReadInput input, @Nullable AccessContext caller) {
        TrafficStore apps = TrafficReads.opened(HohenheimTraffic.Counted.APPS);
        TrafficStore web = TrafficReads.opened(HohenheimTraffic.Counted.WEB);
        int appId = app.get(InstanceModel.ID);
        TrafficQuery query = TrafficReads.query(Set.of(HohenheimTraffic.appSource(appId)), input.period(),
            input.until());
        TrafficReader reader = apps.reader();
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
        return new Read(query, reader.totals(query), reader.totals(query.before()),
            reader.series(query, TrafficMeasure.BYTES, caller != null ? caller.locales() : null,
                caller != null ? caller.messageResolver() : null), sites);
    }
}
