package be.elevenways.hohenheim.server.cluster;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.cluster.LinkRole;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.thread.JobRunnerGroup;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.bootstrap.RuntimeJobs;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.lease.Lease;
import be.elevenways.zenit.common.orm.lease.LeaseKeys;
import be.elevenways.zenit.common.orm.lease.Leases;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.server.peer.PeerRecords;
import be.elevenways.zenit.server.peer.PeerSync;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The master's own record of what every server it manages has: when each is synced, and what the master keeps of how
 * the last sync went.
 *
 * AIDEV-NOTE: the record itself is core's (PeerRecords, keyed by the linked server's id as text), invisible to every
 * model of this app by construction. A sync is asked right after a server is added, on each nudge it sends, and by
 * the minute check after a good status answer; it always runs in the background, outside any transaction, holding
 * the server's own lease ({@link #SYNC_LEASES}) so two processes over one database never sync one server at once, and
 * coalesced per server in this process ({@link Coalesced}): a nudge during a sync causes exactly one more after it.
 * A server that does not answer is not retried: the minute check heals it.
 *
 * AIDEV-NOTE: what is kept of the outcome lives on the linked server's row: IN_STEP_SINCE (set when a sync leaves every
 * kind in step after none was, cleared once a kind went back), WENT_BACK (the kinds whose generation went below the
 * kept one: that server's database was restored; core applies nothing for them until the link is removed),
 * RESTORED_AT (the one restored alert's compare-and-set mark) and SKIPPED (the kinds one build shares and the other
 * does not). A kind the far end kept changing during every transfer is left for the next round and changes none of
 * them. Nothing of a document is ever logged: a failed sync is logged by its exception's class alone.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class ClusterRecords {

    /** The pool syncs run on: a runtime member, so a stopping runtime lets a running sync end. */
    private static final JobRunnerGroup.Member SYNCS =
        RuntimeJobs.GROUP.member("hh-cluster-syncs", JobRunnerGroup.StopMode.DRAIN, LinkedServers.CHECKS_AT_ONCE);

    /** How long a sync waits for another process's sync of the same server before it leaves it to the next ask. */
    private static final int LEASE_WAIT_SECONDS = 60;

    /** The family of the leases a sync holds, one per linked server, on every process over this database. */
    private static final LeaseKeys SYNC_LEASES = LeaseKeys.declare(HohenheimIds.id("cluster_sync"));

    private static final Map<UUID, Coalesced> ASKED = new ConcurrentHashMap<>();

    private ClusterRecords() {
    }

    /** Asks a sync of a server this server manages, in the background; a nudge during one causes exactly one more. */
    public static void request(@NonNull UUID server) {
        ASKED.computeIfAbsent(server, id -> new Coalesced(SYNCS, () -> sync(id))).ask();
    }

    /**
     * Waits for every sync asked in this process to end, for tests.
     *
     * @return false when one still ran after the timeout
     */
    public static boolean awaitIdle(@NonNull Duration timeout) {
        return SYNCS.awaitIdle(timeout);
    }

    /** @return the key the record of a linked server is kept under */
    public static @NonNull String peerKey(@NonNull Row server) {
        return String.valueOf((Object) server.get(LinkedServerModel.ID));
    }

    /** @return every kind this server keeps a record of for that server, with how many records it keeps of each */
    public static @NonNull Map<Identifier, Long> counts(@NonNull Row server) {
        return PeerRecords.counts(peerKey(server));
    }

    /** Forgets everything kept of a linked server; the step that removes its row calls it. */
    static void forget(@NonNull UUID server) {
        PeerRecords.forget(server.toString());
        ASKED.remove(server);
    }

    /** @return the model ids a stored comma list names, in order */
    public static @NonNull List<Identifier> kinds(@Nullable String stored) {
        List<Identifier> kinds = new ArrayList<>();
        String text = Texts.trimmedOrNull(stored);
        if (text == null) {
            return kinds;
        }
        for (String part : text.split(",")) {
            Identifier kind = Identifier.tryParse(part.trim());
            if (kind != null) {
                kinds.add(kind);
            }
        }
        return kinds;
    }

    /** One sync of one server, holding its lease; a server gone or no longer managed from here is forgotten. */
    private static void sync(@NonNull UUID id) {
        Lease lease = Leases.of(Models.get(LinkedServerModel.class).getResolvedDatasource())
            .acquire(SYNC_LEASES.key(id), LEASE_WAIT_SECONDS);
        if (lease == null) {
            // Another process syncs this server: once it is done, one more sync here takes what it may have missed.
            request(id);
            return;
        }
        try {
            Row server = LinkedServers.byId(id);
            if (server == null || LinkedServers.roleOf(server) != LinkRole.MANAGES) {
                forget(id);
                return;
            }
            if (LinkedServers.stopsSending(server)) {
                return;
            }
            PeerSync outcome = PeerRecords.sync(peerKey(server), new LinkWire(server.get(LinkedServerModel.BASE_URL),
                server.get(LinkedServerModel.LINK_KEY)));
            if (LinkedServers.byId(id) == null) {
                // Removed while the sync transferred: what it applied goes with the row.
                forget(id);
                return;
            }
            kept(server, outcome);
        } finally {
            lease.release();
        }
    }

    /** Stores what the master keeps of a sync's outcome, and alerts a restore once. */
    private static void kept(@NonNull Row server, @NonNull PeerSync outcome) {
        List<String> wentBack = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        boolean moving = false;
        for (PeerSync.ModelSync model : outcome.models()) {
            switch (model.outcome()) {
                case WENT_BACK -> wentBack.add(model.model().toString());
                case SKIPPED -> {
                    switch (Objects.requireNonNull(model.skipped(), "a skipped kind names why")) {
                        case NOT_DECLARED_HERE, NOT_DECLARED_THERE -> skipped.add(model.model().toString());
                        case KEPT_MOVING -> moving = true;
                    }
                }
                case TAKEN, UNCHANGED -> {
                }
            }
        }
        Instant now = Now.instant();
        Instant inStepSince = server.get(LinkedServerModel.IN_STEP_SINCE);
        Instant since = !wentBack.isEmpty() ? null : moving || inStepSince != null ? inStepSince : now;
        Models.get(LinkedServerModel.class).find().where(LinkedServerModel.ID.eq(server.get(LinkedServerModel.ID)))
            .assign(LinkedServerModel.IN_STEP_SINCE, since)
            .assign(LinkedServerModel.WENT_BACK, wentBack.isEmpty() ? null : String.join(",", wentBack))
            .assign(LinkedServerModel.SKIPPED, skipped.isEmpty() ? null : String.join(",", skipped))
            .updateAll();
        if (wentBack.isEmpty()) {
            return;
        }
        int won = Models.get(LinkedServerModel.class).find()
            .where(LinkedServerModel.ID.eq(server.get(LinkedServerModel.ID)))
            .and(LinkedServerModel.RESTORED_AT.isNull())
            .assign(LinkedServerModel.RESTORED_AT, now)
            .updateAll();
        if (won == 0) {
            return;
        }
        String name = String.valueOf((Object) server.get(LinkedServerModel.NAME));
        Alerts.trySend(NotificationEvents.LINKED_SERVER_RESTORED,
            Alerts.about(LinkedServerModel.MODEL_ID, server.get(LinkedServerModel.ID)),
            HohenheimMicrocopy.ALERT.of("linked_server_restored_subject").withArg("name", name),
            HohenheimMicrocopy.ALERT.of("linked_server_restored_body").withArg("name", name)
                .withArg("count", wentBack.size()));
    }
}
