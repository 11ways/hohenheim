package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.traffic.AppDestinations;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.hohenheim.traffic.ActivityAsk;
import be.elevenways.hohenheim.traffic.AppsSendingView;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.data.DataTable;
import be.elevenways.zenit.common.data.RecordFacet;
import be.elevenways.zenit.common.live.Live;
import be.elevenways.zenit.common.live.LiveFeed;
import be.elevenways.zenit.common.live.LiveRead;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.ColorHue;
import be.elevenways.zenit.server.live.LiveReads;
import be.elevenways.zenit.traffic.common.TrafficFlow;
import be.elevenways.zenit.traffic.common.TrafficTable;
import be.elevenways.zenit.traffic.server.TopRead;
import be.elevenways.zenit.traffic.server.TrafficQuery;
import be.elevenways.zenit.traffic.server.TrafficReader;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * The reads of where apps send data across the server: the live "Apps sending" picture of every host's latest reading
 * (AppDestinations.readings) for one viewer, and traffic_read's apps_sending table over a span, both naming
 * destinations through DestinationNames.
 *
 * AIDEV-NOTE: the picture is one live part ({@link #PART}) per ask (an ActivityAsk spelling: every site's apps, or the
 * one app a site points at), heard on AppDestinations.TOPIC, which every listing announces, so an open page re-reads
 * once a minute through its viewer's own access: an account sees the apps it manages (an administrator every app).
 * A rate is a reading's bytes over the seconds since the reading before: never a second the counters were not read
 * for. Every refusal is the live read's one 404. The TV page draws no such picture yet: a signed-out page's re-read
 * presenting its link's token is answered 401 before any read runs (see the report of 2026-11-27).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class AppsSendingReads {

    /** The app rows a picture draws, the last one holding every app not listed when there are more. */
    public static final int APP_ROWS = 7;

    /** The destination rows a picture draws, the last one holding every destination not listed when more. */
    public static final int PLACE_ROWS = 6;

    /** The key of the row holding every app, or every destination, not listed. */
    static final String MORE = RecordFacet.MORE_SLUG;

    /** The picture of what one ask shows, re-read whenever the hosts were read again. */
    public static final LiveRead<String, AppsSendingView> PART =
        LiveRead.of(HohenheimIds.id("apps_sending"), String.class, AppsSendingView.class)
            .heard(asked -> List.of(LiveFeed.of(AppsSendingReads::mayWatch, AppDestinations.TOPIC)));

    /**
     * Who a read is for: the apps it may show, and whose names it may say.
     *
     * @param apps  whether an app's row may be shown, by the app's id
     * @param names whose names a destination may be shown by
     */
    record Reader(@NonNull IntPredicate apps, DestinationNames.@NonNull Viewer names) {

        /** @return an account's read: the apps it manages, every one for an administrator, names it may view */
        static @NonNull Reader of(@NonNull AccessContext access) {
            boolean admin = HohenheimAccess.isAdmin(access);
            return new Reader(id -> admin || HohenheimAccess.hasInstanceCapability(access, id,
                HohenheimCapabilities.MANAGE), DestinationNames.Viewer.of(access));
        }
    }

    private AppsSendingReads() {
    }

    /** Serves {@link #PART}; called once by HohenheimHandlers. */
    public static void registerPart() {
        LiveReads.serve(PART, AppsSendingReads::read);
    }

    /** @return the part an account's page draws for what it shows, read for that account */
    public static @NonNull Live<AppsSendingView> at(@NonNull AccessContext viewer, @NonNull ActivityAsk shows) {
        return LiveReads.at(viewer, PART, shows.spelled());
    }

    /** @return whether the viewer may hear that the hosts were read again: an account */
    static boolean mayWatch(@NonNull AccessContext access) {
        return access.isAccount();
    }

    /**
     * The part's read for its viewer.
     *
     * @throws DomainRefusal PERMISSION_DENIED for an ask no viewer of this kind may make
     */
    static @NonNull AppsSendingView read(@NonNull AccessContext access, @NonNull String asked) {
        ActivityAsk shows = ActivityAsk.parse(asked);
        if (shows == null || shows.kind() == ActivityAsk.Kind.TV) {
            throw refused();
        }
        if (!access.isAccount()) {
            throw refused();
        }
        return view(shows, Reader.of(access));
    }

    /** @return the picture of every fresh reading for what is shown, rows ranked and cut, destinations named */
    static @NonNull AppsSendingView view(@NonNull ActivityAsk shows, @NonNull Reader reader) {
        if (!AppDestinations.recording()) {
            return AppsSendingView.none(false);
        }
        List<AppDestinations.Reading> readings = AppDestinations.readings();
        Instant at = readings.isEmpty() ? null : readings.getFirst().at();
        long seconds = readings.isEmpty() ? 0 : readings.getFirst().seconds();
        Map<Integer, String> names = new LinkedHashMap<>();
        for (Row app : apps(shows)) {
            int id = app.get(InstanceModel.ID);
            if (AppNetworkReads.counts(app) && reader.apps().test(id)) {
                names.put(id, Objects.requireNonNullElse(app.get(InstanceModel.NAME), ""));
            }
        }
        // Each shown app's bytes a second to each destination, over its own host's reading.
        Map<Integer, Map<String, Double>> sent = new HashMap<>();
        for (AppDestinations.Reading reading : readings) {
            for (TrafficFlow flow : reading.flows()) {
                Integer app = idOf(flow.source());
                if (app == null || !names.containsKey(app) || flow.bytesSent() <= 0) continue;
                sent.computeIfAbsent(app, ignored -> new LinkedHashMap<>())
                    .merge(flow.key(), (double) flow.bytesSent() / reading.seconds(), Double::sum);
            }
        }
        List<Integer> ranked = new ArrayList<>(names.keySet());
        ranked.sort(Comparator.comparingDouble((Integer app) -> -total(sent.get(app)))
            .thenComparing(app -> names.get(app), String.CASE_INSENSITIVE_ORDER));
        boolean cut = ranked.size() > APP_ROWS;
        List<Integer> listed = cut ? ranked.subList(0, APP_ROWS - 1) : ranked;
        // The rows' sends: each listed app's own, then every other app's together.
        List<Map<String, Double>> rows = new ArrayList<>();
        for (Integer app : listed) {
            rows.add(sent.getOrDefault(app, Map.of()));
        }
        if (cut) {
            Map<String, Double> rest = new LinkedHashMap<>();
            for (Integer app : ranked.subList(APP_ROWS - 1, ranked.size())) {
                sent.getOrDefault(app, Map.of()).forEach((key, rate) -> rest.merge(key, rate, Double::sum));
            }
            rows.add(rest);
        }
        List<AppsSendingView.Place> places = places(rows, reader);
        Map<String, Integer> placeOf = new HashMap<>();
        for (int i = 0; i < places.size(); i++) {
            placeOf.put(places.get(i).key(), i);
        }
        List<AppsSendingView.App> apps = new ArrayList<>(rows.size());
        List<AppsSendingView.Flow> flows = new ArrayList<>();
        for (int row = 0; row < rows.size(); row++) {
            boolean more = cut && row == rows.size() - 1;
            String key = more ? MORE : String.valueOf(listed.get(row));
            Microcopy name = more ? HohenheimMicrocopy.TRAFFIC_SCREEN.of("apps_sending_more")
                .withArg("count", ranked.size() - listed.size()) : Microcopy.literal(names.get(listed.get(row)));
            String hue = more ? ColorHue.GRAY.token() : Objects.requireNonNull(ColorHue.forIdentity(key)).token();
            apps.add(new AppsSendingView.App(key, name, hue, perSecond(total(rows.get(row))),
                more ? ranked.size() - listed.size() : 0));
            Map<Integer, Double> toPlaces = new LinkedHashMap<>();
            rows.get(row).forEach((destination, rate) -> {
                Integer place = placeOf.getOrDefault(destination, placeOf.get(MORE));
                if (place != null) toPlaces.merge(place, rate, Double::sum);
            });
            int app = row;
            toPlaces.forEach((place, rate) -> flows.add(new AppsSendingView.Flow(app, place, perSecond(rate))));
        }
        return new AppsSendingView(true, at, seconds, apps, places, flows);
    }

    /** @return the destination rows: the most received a second first, the rest together, each named for the reader */
    private static @NonNull List<AppsSendingView.Place> places(@NonNull List<Map<String, Double>> rows,
                                                             @NonNull Reader reader) {
        Map<String, Double> received = new LinkedHashMap<>();
        for (Map<String, Double> row : rows) {
            row.forEach((key, rate) -> {
                if (AppDestinations.Destination.of(key) != null) received.merge(key, rate, Double::sum);
            });
        }
        List<String> keys = new ArrayList<>(received.keySet());
        keys.sort(Comparator.comparingDouble((String key) -> -received.get(key)).thenComparing(key -> key));
        boolean cut = keys.size() > PLACE_ROWS;
        List<String> listed = cut ? keys.subList(0, PLACE_ROWS - 1) : keys;
        List<AppDestinations.Destination> wheres = new ArrayList<>(listed.size());
        for (String key : listed) {
            wheres.add(Objects.requireNonNull(AppDestinations.Destination.of(key)));
        }
        Map<String, String> names = DestinationNames.of(wheres, reader.names());
        List<AppsSendingView.Place> places = new ArrayList<>(listed.size() + 1);
        for (AppDestinations.Destination where : wheres) {
            places.add(new AppsSendingView.Place(where.key(),
                DestinationNames.shown(where, DestinationNames.nameOf(names, where)),
                DestinationNames.addressOf(where)));
        }
        if (cut) {
            places.add(new AppsSendingView.Place(MORE,
                HohenheimMicrocopy.APP_NETWORK.of("app_network_destinations_other"), ""));
        }
        return places;
    }

    /**
     * traffic_read's apps_sending table: one line per app and destination over the span, the most sent first, each
     * app by its name and each destination by DestinationNames in the caller's words; the last line holds the rest.
     *
     * @throws DomainRefusal SERVICE_UNAVAILABLE while counting is stopped (the process is ending)
     */
    static @NonNull DataTable table(@NonNull TrafficQuery query, @Nullable AccessContext caller,
                                    @NonNull LocaleChain chain, @Nullable MessageResolver resolver) {
        TrafficReader.Collected span = TrafficReads.opened(HohenheimTraffic.Counted.APPS).reader()
            .collected(new TrafficQuery(Set.of(), query.from(), query.to(), query.granularity()), true);
        Map<String, String> apps = new HashMap<>();
        List<Integer> ids = new ArrayList<>();
        for (String source : span.sources()) {
            Integer id = idOf(source);
            if (id != null) ids.add(id);
        }
        if (!ids.isEmpty()) {
            for (Row app : Models.get(InstanceModel.class).find().where(InstanceModel.ID.in(ids)).all()) {
                apps.put(HohenheimTraffic.appSource(app.get(InstanceModel.ID)), app.get(InstanceModel.NAME));
            }
        }
        String removed = words(caller, HohenheimMicrocopy.TRAFFIC_SCREEN.of("apps_sending_removed_app"));
        DataTable table = span.naming(source -> apps.getOrDefault(source, removed))
            .top(new TopRead(TrafficTable.DESTINATIONS, TrafficReads.TOP_ROWS, TopRead.Order.BYTES_SENT, 0, true),
                chain, resolver);
        List<AppDestinations.Destination> wheres = new ArrayList<>();
        for (DataTable.Line line : table.lines()) {
            AppDestinations.Destination where = AppDestinations.Destination.of(line.groups().getLast().key());
            if (where != null) wheres.add(where);
        }
        Map<String, String> names = DestinationNames.of(wheres, DestinationNames.Viewer.of(caller));
        List<DataTable.Line> named = new ArrayList<>(table.lines().size());
        for (DataTable.Line line : table.lines()) {
            DataTable.Group key = line.groups().getLast();
            AppDestinations.Destination where = AppDestinations.Destination.of(key.key());
            if (where == null) {
                named.add(line);
                continue;
            }
            List<DataTable.Group> groups = new ArrayList<>(line.groups());
            groups.set(groups.size() - 1, new DataTable.Group(key.key(), words(caller,
                DestinationNames.shown(where, DestinationNames.nameOf(names, where))) + " "
                + DestinationNames.addressOf(where) + "/" + where.protocol()));
            named.add(new DataTable.Line(groups, line.bucket(), line.records(), line.values()));
        }
        return new DataTable(named);
    }

    /** @return the apps an ask may show: every app by name, or the one a site points at */
    private static @NonNull List<Row> apps(@NonNull ActivityAsk shows) {
        if (shows.kind() != ActivityAsk.Kind.SITE) {
            return Models.get(InstanceModel.class).find().orderBy(InstanceModel.NAME, SortOrder.ASC).all();
        }
        Integer siteId = HohenheimTraffic.siteOf(Objects.requireNonNull(shows.value(), "one site's ask names it"));
        Row site = siteId == null ? null
            : Models.get(SiteModel.class).find().where(SiteModel.ID.eq(siteId)).first();
        Integer app = site == null ? null : site.get(SiteModel.INSTANCE_ID);
        return app == null ? List.of() : Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(app)).all();
    }

    /** @return the app an apps store source names, null for a key this build does not write */
    private static @Nullable Integer idOf(@NonNull String source) {
        try {
            return Integer.valueOf(source);
        } catch (NumberFormatException foreign) {
            return null;
        }
    }

    private static double total(@Nullable Map<String, Double> rates) {
        double sum = 0;
        if (rates != null) {
            for (double rate : rates.values()) sum += rate;
        }
        return sum;
    }

    /** @return a rate in whole bytes a second, at least one for anything sent */
    private static long perSecond(double rate) {
        return rate <= 0 ? 0 : Math.max(1, Math.round(rate));
    }

    private static @NonNull String words(@Nullable AccessContext caller, @NonNull Microcopy copy) {
        return caller != null ? caller.text(copy) : copy.resolve(LocaleChain.empty(), null);
    }

    private static @NonNull DomainRefusal refused() {
        return new DomainRefusal(ZenitRefusalReason.PERMISSION_DENIED, "Not permitted");
    }
}
