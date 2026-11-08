package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.ApplicationUpstreams;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * Restarts a running workload a site serves once it keeps accepting connections without answering HTTP: a hang its
 * container never reports, since the process is still alive.
 *
 * AIDEV-NOTE: Taverne Tomberg's Node app hung on a dead MongoDB connection on 2026-10-30 and served nothing for hours
 * while its container ran and every status read said running; only a hand restart brought it back. The evidence is
 * deliberately narrow: a connect that is REFUSED is no evidence (a crashed listener ends its container, which the
 * status reconciler handles, and a host the probe cannot reach refuses every connect), a probe lane that could not
 * run is no evidence, and any HTTP answer, a 500 included, means the process answers. Only "connects, then says
 * nothing within the probe's 5 seconds" for {@link #MISSES_BEFORE_RESTART} sweeps in a row counts, and an app that
 * keeps hanging after its restarts is left alone ({@link #RESTARTS_BEFORE_GIVING_UP}) with a crash-loop alert, since
 * a restart is evidently no cure. The counts are per controller process and keyed by controller scope: a controller
 * restart starts them over, which only delays a restart.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class HungWorkloads {

    /** Sweeps in a row a workload may connect without answering before it is restarted. */
    static final int MISSES_BEFORE_RESTART = 3;

    /** Restarts within {@link #RESTART_WINDOW} after which a still-hanging workload is left alone. */
    static final int RESTARTS_BEFORE_GIVING_UP = 3;

    /** The window {@link #RESTARTS_BEFORE_GIVING_UP} counts in. */
    static final Duration RESTART_WINDOW = Duration.ofHours(1);

    /** The path every probe asks: what the site's root request reaches. */
    private static final String PROBE_PATH = "/";

    private static final Map<String, Integer> MISSES = new ConcurrentHashMap<>();

    private static final Map<String, Deque<Instant>> RESTARTS = new ConcurrentHashMap<>();

    /** What restarts a hung workload. */
    @FunctionalInterface
    public interface Restart {

        /** Restarts the workload; called under its claim, so it must queue the restart rather than run it here. */
        void restart(int instanceId, int serverId, @NonNull String name);
    }

    private final @NonNull InstanceService instances;
    private final @NonNull IntFunction<PublishedPortProbe> probes;
    private final @NonNull Restart restart;

    public HungWorkloads() {
        this(new InstanceService());
    }

    private HungWorkloads(@NonNull InstanceService instances) {
        this(instances, PublishedPortProbe::forServer, (id, server, name) -> InstanceStatusReconciler
            .redeployInBackground(instances, id, server, name, Set.of(InstanceModel.STATUS_RUNNING)));
    }

    /**
     * @param probes  the probe lane of a host, by server id
     * @param restart what restarts a workload found hung
     */
    public HungWorkloads(@NonNull InstanceService instances, @NonNull IntFunction<PublishedPortProbe> probes,
                         @NonNull Restart restart) {
        this.instances = instances;
        this.probes = probes;
        this.restart = restart;
    }

    /** @return the ids of the workloads this sweep restarted */
    public @NonNull List<Integer> sweep() {
        Map<Integer, Integer> served = servedWorkloads();
        Set<String> keys = new HashSet<>();
        served.keySet().forEach(id -> keys.add(key(id)));
        MISSES.keySet().retainAll(keys);
        List<Integer> restarted = new ArrayList<>();
        served.forEach((instanceId, port) -> {
            try {
                if (check(instanceId, port)) {
                    restarted.add(instanceId);
                }
            } catch (RuntimeException failed) {
                // One record must never end the sweep for the rest.
                Blast.log("HUNG WORKLOADS: could not check instance", instanceId, "-", failed.getMessage());
            }
        });
        return restarted;
    }

    /**
     * Every workload an enabled instance site with a routable domain forwards plain HTTP to, with the port the proxy
     * dials: the proxy's own resolution, so the probe asks exactly what visitors reach.
     */
    private static @NonNull Map<Integer, Integer> servedWorkloads() {
        List<Row> sites = Models.get(SiteModel.class).find()
            .where(SiteModel.UPSTREAM_KIND.eq(InstanceUpstreamKind.ID.toString()))
            .where(SiteModel.INSTANCE_ID.isNotNull())
            .where(SiteModel.ENABLED.eq(true))
            .all();
        Set<Integer> routable = routableSites(sites);
        Map<Integer, Integer> served = new LinkedHashMap<>();
        for (Row site : sites) {
            Integer applicationId = site.get(SiteModel.INSTANCE_ID);
            if (applicationId == null || !routable.contains((Integer) site.get(SiteModel.ID))
                    || !"http".equals(InstanceUpstreamKind.schemeOf(site))) {
                continue;
            }
            Integer consumer = ApplicationReleases.consumerInstanceOf(applicationId);
            URI upstream = ApplicationUpstreams.resolve(applicationId).upstream();
            if (consumer != null && upstream != null && upstream.getPort() > 0) {
                served.putIfAbsent(consumer, upstream.getPort());
            }
        }
        return served;
    }

    /** @return the ids of the sites holding a domain with a hostname, the ones the route table serves */
    private static @NonNull Set<Integer> routableSites(@NonNull List<Row> sites) {
        List<Integer> ids = new ArrayList<>();
        sites.forEach(site -> ids.add(site.get(SiteModel.ID)));
        Set<Integer> routable = new HashSet<>();
        if (ids.isEmpty()) {
            return routable;
        }
        for (Row domain : Models.get(SiteDomainModel.class).find().where(SiteDomainModel.SITE_ID.in(ids)).all()) {
            String hostname = domain.get(SiteDomainModel.HOSTNAME);
            if (hostname != null && !hostname.isBlank()) {
                routable.add(domain.get(SiteDomainModel.SITE_ID));
            }
        }
        return routable;
    }

    /** @return whether this check restarted the workload */
    private boolean check(int instanceId, int port) {
        String key = key(instanceId);
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        if (instance == null || !restartable(instance)) {
            MISSES.remove(key);
            return false;
        }
        int serverId = ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID));
        // Probed holding no claim: a remote probe takes seconds, and a person's operation must not be refused for it.
        String failure = unanswered(this.probes.apply(serverId), port);
        if (failure == null) {
            MISSES.remove(key);
            return false;
        }
        if (MISSES.merge(key, 1, Integer::sum) < MISSES_BEFORE_RESTART) {
            return false;
        }
        boolean[] restarted = {false};
        // The decision is taken under the claim, on the record as it is now: an operation that started meanwhile is
        // that operation's, and the count waits for the next sweep.
        this.instances.operations().runIfIdle(instanceId, () -> restarted[0] = restartUnderClaim(instanceId, failure));
        return restarted[0];
    }

    private boolean restartUnderClaim(int instanceId, @NonNull String failure) {
        String key = key(instanceId);
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        if (instance == null || !restartable(instance)) {
            MISSES.remove(key);
            return false;
        }
        MISSES.remove(key);
        String name = String.valueOf((Object) instance.get(InstanceModel.NAME));
        if (!roomForAnotherRestart(key)) {
            Blast.log("HUNG WORKLOADS:", name, "keeps hanging after its restarts; automatic restarts suspended");
            InstanceConsoles.alertCrashLoop(instanceId, name);
            return false;
        }
        Blast.log("HUNG WORKLOADS:", name, "accepts connections but answers nothing -", failure, "- restarting");
        History.event(Models.get(InstanceModel.class), instanceId,
            Change.fact(HohenheimActivityAction.RESTARTED_UNANSWERED,
                Map.of(HohenheimActivityAction.REASON_KEY, failure)));
        this.restart.restart(instanceId,
            ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID)), name);
        return true;
    }

    /** @return whether a restart fits the window, recording it when it does */
    private static boolean roomForAnotherRestart(@NonNull String key) {
        Instant now = Now.instant();
        Deque<Instant> recent = RESTARTS.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && recent.getFirst().isBefore(now.minus(RESTART_WINDOW))) {
                recent.removeFirst();
            }
            if (recent.size() >= RESTARTS_BEFORE_GIVING_UP) {
                return false;
            }
            recent.addLast(now);
            return true;
        }
    }

    private static boolean restartable(@NonNull Row instance) {
        return InstanceModel.STATUS_RUNNING.equals(instance.get(InstanceModel.STATUS))
            && InstanceModel.CRASH_RESTART.equals(instance.get(InstanceModel.CRASH_POLICY));
    }

    /** @return why the port connects but answers no HTTP, null when it answers, refuses or could not be asked */
    private static @Nullable String unanswered(@NonNull PublishedPortProbe lane, int port) {
        if (!lane.accepts(port)) {
            return null;
        }
        try {
            return lane.httpStatus(port, PROBE_PATH) > 0 ? null : "no HTTP answer within 5 seconds";
        } catch (IOException couldNotAsk) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** @return the controller-scoped key of a workload, so another controller's record of the same id never counts */
    private static @NonNull String key(int instanceId) {
        return ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
    }
}
