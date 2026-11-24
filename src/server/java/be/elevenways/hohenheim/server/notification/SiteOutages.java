package be.elevenways.hohenheim.server.notification;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SiteOutageModel;
import be.elevenways.hohenheim.server.application.ApplicationUpstreams;
import be.elevenways.hohenheim.server.proxy.SiteDispatcher;
import be.elevenways.hohenheim.server.sitetype.SiteAvailability;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The outage watch of the sites apps serve: one "stopped answering" alert when an app that was seen answering has not
 * answered for {@link Alerts#DOWN_AFTER}, and one "answers again" alert once it answered two sweeps in a row.
 *
 * AIDEV-NOTE: the state is one {@link SiteOutageModel} row per site, so a restarted controller repeats nothing and a
 * second sweeper over the same database sends nothing twice: up_seen_at (the observed up an outage needs, the row's
 * first reading; inside an alerted outage the first answer after it), down_since (the first down reading) and
 * down_alerted_at (the once-only latch). No row is a site never seen answering. Every write is one hook-free
 * statement on that table, never on the site: no route reload, no recorded change, nothing a site save can write back
 * over; a write a stale reading could get wrong is guarded by the state it was read with. A STOPPED reading never
 * starts or advances an outage but keeps an alerted one open, because Restart and a crash's automatic restart pass
 * through stopped: an alerted outage a Restart fixes still sends "answers again", and a crash whose restart is refused
 * still alerts. An UNKNOWN reading (a deploy or start under way, no dial evidence yet) is no evidence either way: it
 * neither breaks a down run nor a run of answers, and the time keeps counting. "Two sweeps in a row" means two UP
 * readings with no DOWN reading between them. Only an UP reading, or the site leaving the watch (switched off,
 * trashed, its upstream no longer an app, its app never deployed), ends an alerted outage; leaving the watch deletes
 * the row silently.
 *
 * AIDEV-NOTE: delivery is AT MOST ONCE. The compare-and-set commits before {@link Alerts#trySend} queues the message,
 * the release engine's alert shape: a crash or a queueing failure between the two loses that one message and never
 * repeats it. One transaction around both would hold a write lock across an inline delivery.
 *
 * AIDEV-NOTE: a site is the unit, so an app with two sites sends one message per site; the message names the app,
 * and the address too when the app serves more than one site, so two sites of one app never read the same. Two
 * proxies over one database read their own dispatcher and may disagree; the latches keep each message single, out of
 * scope beyond that.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class SiteOutages {

    private SiteOutages() {
    }

    /**
     * What one sweep read: the watched sites, their apps, their readings and every stored outage row.
     *
     * @param outages outage row by site id, sites outside the watch included
     */
    public record Observation(@NonNull List<Row> sites, @NonNull Map<Integer, Row> applications,
                              @NonNull Map<Integer, SiteAvailability> readings, @NonNull Map<Integer, Row> outages) {
    }

    /**
     * Read every watched site, then act on what was read.
     *
     * @param dispatcher the running proxy's dispatcher, whose verdict the readings take; null where none runs
     */
    public static void sweep(@Nullable SiteDispatcher dispatcher) {
        act(observe(dispatcher));
    }

    /**
     * Read the enabled, untrashed sites whose upstream is an app, in six queries whatever their number: the sites,
     * the outage rows, their apps, the apps' serving releases and the two reads of deploys under way.
     *
     * @param dispatcher as {@link #sweep} takes it
     */
    public static @NonNull Observation observe(@Nullable SiteDispatcher dispatcher) {
        List<Row> sites = Models.get(SiteModel.class).find()
            .where(SiteModel.UPSTREAM_KIND.eq(InstanceUpstreamKind.ID.toString()))
            .where(SiteModel.INSTANCE_ID.isNotNull())
            .where(SiteModel.ENABLED.eq(true))
            .all();
        Map<Integer, Row> outages = new LinkedHashMap<>();
        for (Row outage : Models.get(SiteOutageModel.class).find().all()) {
            outages.put(outage.get(SiteOutageModel.SITE_ID), outage);
        }
        Map<Integer, Row> applications = SiteAvailability.applicationsOf(sites);
        return new Observation(sites, applications, SiteAvailability.of(sites, applications, dispatcher), outages);
    }

    /**
     * Move each watched site's outage on by its reading, then forget the outage of every site the watch no longer
     * reads; in steady state nothing is written.
     */
    public static void act(@NonNull Observation observation) {
        Instant now = Now.instant();
        Map<Integer, Integer> sitesPerApp = new HashMap<>();
        for (Row site : observation.sites()) {
            Integer siteId = site.get(SiteModel.ID);
            if (observation.readings().containsKey(siteId)) {
                sitesPerApp.merge(site.get(SiteModel.INSTANCE_ID), 1, Integer::sum);
            }
        }
        Map<Integer, Row> unwatched = new HashMap<>(observation.outages());
        for (Row site : observation.sites()) {
            Integer siteId = site.get(SiteModel.ID);
            SiteAvailability reading = observation.readings().get(siteId);
            if (reading == null) {
                continue;
            }
            unwatched.remove(siteId);
            Integer applicationId = site.get(SiteModel.INSTANCE_ID);
            Row application = observation.applications().get(applicationId);
            try {
                step(site, observation.outages().get(siteId), application, reading,
                    sitesPerApp.getOrDefault(applicationId, 1) > 1, now);
            } catch (RuntimeException failed) {
                // One site must never end the sweep for the rest.
                Blast.log("SITE OUTAGES: could not judge site", siteId, "-", failed.getMessage());
            }
        }
        forget(new ArrayList<>(unwatched.values()));
    }

    /** One site's move, by its reading and the outage row it was read with (null: never seen answering). */
    private static void step(@NonNull Row site, @Nullable Row outage, @NonNull Row application,
                             @NonNull SiteAvailability reading, boolean named, @NonNull Instant now) {
        Instant upSeen = outage == null ? null : outage.get(SiteOutageModel.UP_SEEN_AT);
        Instant downSince = outage == null ? null : outage.get(SiteOutageModel.DOWN_SINCE);
        Instant alerted = outage == null ? null : outage.get(SiteOutageModel.DOWN_ALERTED_AT);
        switch (reading) {
            case NOT_DEPLOYED -> {
                if (outage != null) {
                    forget(List.of(outage));
                }
            }
            case STOPPED -> {
                if (outage != null && alerted == null && downSince != null) {
                    unalerted(outage).assign(SiteOutageModel.DOWN_SINCE, null).updateAll();
                }
            }
            case UNKNOWN -> {
            }
            case UP -> {
                if (outage == null) {
                    seenAnswering(site, now);
                } else if (alerted != null) {
                    answered(site, outage, application, named, upSeen, downSince, now);
                } else if (downSince != null) {
                    unalerted(outage).assign(SiteOutageModel.DOWN_SINCE, null).updateAll();
                }
            }
            case DOWN -> {
                if (outage == null || upSeen == null) {
                    return;
                }
                if (downSince == null) {
                    unalerted(outage).and(SiteOutageModel.DOWN_SINCE.isNull())
                        .assign(SiteOutageModel.DOWN_SINCE, now).updateAll();
                } else if (alerted != null) {
                    // A down reading breaks a run of answers inside an alerted outage: it starts over.
                    if (upSeen.isAfter(downSince)) {
                        asRead(outage).assign(SiteOutageModel.UP_SEEN_AT, downSince).updateAll();
                    }
                } else if (Duration.between(downSince, now).compareTo(Alerts.DOWN_AFTER) >= 0) {
                    stoppedAnswering(site, outage, application, named, downSince, now);
                }
            }
        }
    }

    /** The first UP reading of a site: its row, inserted once whoever else read the same. */
    private static void seenAnswering(@NonNull Row site, @NonNull Instant now) {
        SiteOutageModel outages = Models.get(SiteOutageModel.class);
        Row outage = outages.createEmptyRow();
        outage.set(SiteOutageModel.SITE_ID, site.get(SiteModel.ID));
        outage.set(SiteOutageModel.UP_SEEN_AT, now);
        outages.insertAllIfAbsent(List.of(outage), SiteOutageModel.SITE_ID);
    }

    /** An UP reading inside an alerted outage: the first one starts a run, the second in a row ends the outage. */
    private static void answered(@NonNull Row site, @NonNull Row outage, @NonNull Row application, boolean named,
                                 @Nullable Instant upSeen, @Nullable Instant downSince, @NonNull Instant now) {
        if (downSince == null || upSeen == null || !upSeen.isAfter(downSince)) {
            asRead(outage).assign(SiteOutageModel.UP_SEEN_AT, now).updateAll();
            return;
        }
        int won = asRead(outage)
            .assign(SiteOutageModel.DOWN_SINCE, null).assign(SiteOutageModel.DOWN_ALERTED_AT, null).updateAll();
        if (won == 0) {
            return;
        }
        String name = nameOf(application);
        Microcopy subject = named
            ? HohenheimMicrocopy.ALERT.of("site_recovered_subject_at").withArg("name", name)
                .withArg("address", addressOf(site))
            : HohenheimMicrocopy.ALERT.of("site_recovered_subject").withArg("name", name);
        Alerts.trySend(NotificationEvents.SITE_RECOVERED, Alerts.about(SiteModel.MODEL_ID, site.get(SiteModel.ID)),
            subject, HohenheimMicrocopy.ALERT.of("site_recovered_body")
                .withArg("duration", Duration.between(downSince, upSeen)));
    }

    /** A site down for {@link Alerts#DOWN_AFTER}: the latch is taken once, and only its taker sends. */
    private static void stoppedAnswering(@NonNull Row site, @NonNull Row outage, @NonNull Row application,
                                         boolean named, @NonNull Instant downSince, @NonNull Instant now) {
        int won = asRead(outage).assign(SiteOutageModel.DOWN_ALERTED_AT, now).updateAll();
        if (won == 0) {
            return;
        }
        int applicationId = application.get(InstanceModel.ID);
        Microcopy detail = ApplicationUpstreams.resolve(applicationId).upstream() == null
            ? HohenheimMicrocopy.ALERT.of("site_down_nothing_serving")
            : HohenheimMicrocopy.ALERT.of("site_down_refused");
        String name = nameOf(application);
        Microcopy subject = named
            ? HohenheimMicrocopy.ALERT.of("site_down_subject_at").withArg("name", name)
                .withArg("address", addressOf(site))
            : HohenheimMicrocopy.ALERT.of("site_down_subject").withArg("name", name);
        Alerts.trySend(NotificationEvents.SITE_DOWN, Alerts.about(SiteModel.MODEL_ID, site.get(SiteModel.ID)), subject,
            HohenheimMicrocopy.ALERT.of("site_down_body").withArg("name", name).withArg("since", downSince)
                .withArg("detail", detail));
    }

    private static @NonNull String nameOf(@NonNull Row application) {
        return String.valueOf((Object) application.get(InstanceModel.NAME));
    }

    /** @return the site's first address, its path after it, or the site's name while it has none */
    private static @NonNull String addressOf(@NonNull Row site) {
        Row domain = Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.SITE_ID.eq(site.get(SiteModel.ID)))
            .orderBy(SiteDomainModel.ID, SortOrder.ASC)
            .first();
        if (domain == null) {
            return String.valueOf((Object) site.get(SiteModel.NAME));
        }
        String path = domain.get(SiteDomainModel.PATH);
        return domain.get(SiteDomainModel.HOSTNAME) + (path == null || path.isBlank() ? "" : path);
    }

    /** Delete these outage rows, silently, in one statement; none is no statement. */
    private static void forget(@NonNull List<Row> outages) {
        if (outages.isEmpty()) {
            return;
        }
        List<Integer> ids = new ArrayList<>(outages.size());
        for (Row outage : outages) {
            ids.add(outage.get(SiteOutageModel.ID));
        }
        Models.get(SiteOutageModel.class).find().where(SiteOutageModel.ID.in(ids)).delete();
    }

    /** The one-row update every move of a stored outage starts from. */
    private static @NonNull QueryBuilder<Row> write(@NonNull Row outage) {
        return Models.get(SiteOutageModel.class).find().where(SiteOutageModel.ID.eq(outage.get(SiteOutageModel.ID)));
    }

    /** {@link #write} of an outage that is not alerted, so a stale reading never clears an alerted one. */
    private static @NonNull QueryBuilder<Row> unalerted(@NonNull Row outage) {
        return write(outage).and(SiteOutageModel.DOWN_ALERTED_AT.isNull());
    }

    /**
     * {@link #write} of an outage still exactly as it was read, so a second sweeper acting on a stale reading moves
     * nothing another one moved meanwhile: the compare-and-set of every move a stale reading could get wrong.
     */
    private static @NonNull QueryBuilder<Row> asRead(@NonNull Row outage) {
        return write(outage).and(SiteOutageModel.UP_SEEN_AT.asRead(outage))
            .and(SiteOutageModel.DOWN_SINCE.asRead(outage)).and(SiteOutageModel.DOWN_ALERTED_AT.asRead(outage));
    }
}
