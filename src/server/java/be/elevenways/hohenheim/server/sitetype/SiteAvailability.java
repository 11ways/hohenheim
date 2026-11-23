package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceStatus;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.proxy.SiteDispatcher;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether the app a site serves answers its visitors right now, as the outage watch reads it: one reading per app
 * site, from the app's runtime row and the running proxy's own verdict.
 *
 * AIDEV-NOTE: UP and DOWN both derive from {@link SiteDispatcher#healthOf}, the verdict the dashboard band and the app
 * page read, so a site is never up here and down there. What differs is on purpose: the band calls a never-deployed or
 * stopped app's site down, while this reading says it is not deployed or stopped, which never alerts; and UP needs
 * evidence (a dial that answered since the last route load), where the band reads a route nothing dialled yet as up.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum SiteAvailability {

    /** Its app was never deployed: nothing it could have answered with. */
    NOT_DEPLOYED,

    /** Its app's serving version is stopped: a stop someone asked for, or the moment a restart passes through. */
    STOPPED,

    /** Nothing can be said now: a deploy or start under way, no proxy in this process, no route, no evidence yet. */
    UNKNOWN,

    /** Its upstream is the app's resolved port, and that port answered the last dial. */
    UP,

    /** Its app runs (or failed) and visitors get an error page: nothing serves, or the port refused the last dial. */
    DOWN;

    /**
     * The apps these sites serve, read in one query: an instance that is no app (a workspace, a container), or is
     * gone, is absent.
     *
     * @return app record by instance id
     */
    public static @NonNull Map<Integer, Row> applicationsOf(@NonNull List<Row> sites) {
        Set<Integer> instanceIds = new LinkedHashSet<>();
        for (Row site : sites) {
            Integer instanceId = site.get(SiteModel.INSTANCE_ID);
            if (instanceId != null) {
                instanceIds.add(instanceId);
            }
        }
        Map<Integer, Row> applications = new LinkedHashMap<>();
        if (instanceIds.isEmpty()) {
            return applications;
        }
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.in(new ArrayList<>(instanceIds))).all()) {
            if (InstanceKinds.isReleaseManaged(instance.get(InstanceModel.KIND))) {
                applications.put(instance.get(InstanceModel.ID), instance);
            }
        }
        return applications;
    }

    /**
     * Each app site's reading, in three queries whatever the number of sites (the apps' serving releases, and the
     * deploys under way); the proxy's verdict is read from memory.
     *
     * @param applications {@link #applicationsOf} of these sites
     * @param dispatcher   the running proxy's dispatcher, null where this process runs none (every app site that
     *                     runs then reads UNKNOWN)
     * @return reading by site id; a site whose instance is not in {@code applications} is absent
     */
    public static @NonNull Map<Integer, SiteAvailability> of(@NonNull List<Row> sites,
                                                             @NonNull Map<Integer, Row> applications,
                                                             @Nullable SiteDispatcher dispatcher) {
        Map<Integer, SiteAvailability> readings = new LinkedHashMap<>();
        if (applications.isEmpty()) {
            return readings;
        }
        Map<Integer, Row> runtimes = ApplicationReleases.runtimeRows(new ArrayList<>(applications.values()));
        Set<Integer> deploying = ApplicationReleases.deployingAmong(applications.keySet());
        for (Row site : sites) {
            Integer siteId = site.get(SiteModel.ID);
            Integer applicationId = site.get(SiteModel.INSTANCE_ID);
            if (siteId == null || applicationId == null || !applications.containsKey(applicationId)) {
                continue;
            }
            readings.put(siteId, read(runtimes.get(applicationId), deploying.contains(applicationId), dispatcher,
                siteId));
        }
        return readings;
    }

    /** One site's reading; a status this build does not know reads UNKNOWN, which changes nothing. */
    private static @NonNull SiteAvailability read(@Nullable Row runtime, boolean deploying,
                                                  @Nullable SiteDispatcher dispatcher, int siteId) {
        InstanceStatus status = runtime == null ? null : InstanceStatus.forToken(runtime.get(InstanceModel.STATUS));
        if (status == null) {
            return UNKNOWN;
        }
        return switch (status) {
            case CREATED -> NOT_DEPLOYED;
            case STOPPED -> STOPPED;
            case STARTING, CAPTURING, RESTORING, MIGRATING, DESTROYING -> UNKNOWN;
            case RUNNING, ERROR -> deploying ? UNKNOWN : dispatched(dispatcher, siteId);
        };
    }

    /** What the running proxy says of the site: up only with an answered dial since its last route load. */
    private static @NonNull SiteAvailability dispatched(@Nullable SiteDispatcher dispatcher, int siteId) {
        if (dispatcher == null) {
            return UNKNOWN;
        }
        SiteHealth health = dispatcher.healthOf(siteId);
        if (health == null) {
            return UNKNOWN;
        }
        return switch (health) {
            case UP -> Boolean.TRUE.equals(dispatcher.lastDialAnswered(siteId)) ? UP : UNKNOWN;
            case DOWN -> DOWN;
            case DEGRADED, DEPLOYING, UNKNOWN -> UNKNOWN;
        };
    }
}
