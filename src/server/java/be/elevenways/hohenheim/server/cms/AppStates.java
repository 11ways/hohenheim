package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.cluster.LinkRole;
import be.elevenways.hohenheim.cluster.LinkState;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.server.cluster.ClusterRecords;
import be.elevenways.hohenheim.server.cluster.LinkedServers;
import be.elevenways.hohenheim.server.cms.AppDirectory.HeldApp;
import be.elevenways.hohenheim.server.cms.AppDirectory.Held;
import be.elevenways.hohenheim.server.cms.AppDirectory.Wording;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.peer.PeerRecords;
import be.elevenways.zenit.server.peer.SharedRow;
import be.elevenways.zenit.server.peer.SharedStates;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The state kind of apps: what this server tells its master of how each of its apps is doing, as the facts its own
 * Apps list draws ({@link AppFacts}), and the master's read of what its servers told.
 *
 * AIDEV-NOTE: one entry per app of the operator's Apps list, keyed as that list keys it, computed without a viewer
 * ({@link AppDirectory#operatorFacts}). It rides core's shared state (SharedStates): told at once after every commit
 * of a shared kind (an app's status is a column of the shared instances), compared at the master's minute check, kept
 * in the master's peer tables under {@link #KIND}. A master reads it with two statements whatever its servers and their
 * apps: the links and the kept state of every one of them.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class AppStates {

    /** The kind the apps' state is offered and kept under. */
    public static final Identifier KIND = HohenheimIds.id("app_state");

    private static final AtomicBoolean DECLARED = new AtomicBoolean();

    private AppStates() {
    }

    /** Offers this server's apps' state to its master, once however often the cluster handlers install. */
    public static void declare() {
        if (DECLARED.compareAndSet(false, true)) {
            SharedStates.declare(KIND, AppStates.class, AppStates::entries);
        }
    }

    /** @return this server's apps as its master keeps them: one entry per app, its name as the title */
    private static @NonNull List<SharedRow> entries() {
        List<SharedRow> entries = new ArrayList<>();
        for (AppFacts facts : AppDirectory.operatorFacts()) {
            entries.add(new SharedRow(facts.key(), null, facts.name(), facts.document()));
        }
        return entries;
    }

    /**
     * The apps of every server this one manages, as each server last told them: a silent server's as it had them when
     * it last answered. A told app this build cannot read is listed as such ({@link HeldApp#unreadable}).
     *
     * @return the apps in the order kept (by server key, then app key), worded for the viewer
     */
    static @NonNull List<HeldApp> held(@NonNull Wording words) {
        Map<String, Held> servers = servers();
        List<HeldApp> apps = new ArrayList<>();
        if (servers.isEmpty()) {
            return apps;
        }
        for (PeerRecords.PeerKept kept : PeerRecords.everyPeer(KIND)) {
            Held held = servers.get(kept.peer());
            if (held != null) {
                apps.add(appOf(kept.record(), held, words));
            }
        }
        return apps;
    }

    /**
     * @param key the app's key in the Apps list: its server's key, then the key its state is kept under
     * @return the linked server's app the key names, as that server last told it; null for a key naming none
     */
    static @Nullable HeldApp held(@NonNull String key, @NonNull Wording words) {
        for (Held held : servers().values()) {
            String prefix = held.peer() + "-";
            if (key.startsWith(prefix)) {
                PeerRecords.KeptRecord kept = PeerRecords.read(held.peer(), KIND, key.substring(prefix.length()));
                return kept == null ? null : appOf(kept, held, words);
            }
        }
        return null;
    }

    /** @return every server this one manages by the key its records are kept under, as this server last heard of it */
    private static @NonNull Map<String, Held> servers() {
        Map<String, Held> servers = new HashMap<>();
        String version = LinkedServers.ownVersion();
        for (Row server : LinkedServers.all()) {
            if (LinkedServers.roleOf(server) == LinkRole.MANAGES) {
                String peer = ClusterRecords.peerKey(server);
                servers.put(peer, new Held(peer, String.valueOf((Object) server.get(LinkedServerModel.NAME)),
                    LinkState.of(server, version) == LinkState.NOT_ANSWERING,
                    server.get(LinkedServerModel.LAST_SEEN_AT)));
            }
        }
        return servers;
    }

    private static @NonNull HeldApp appOf(PeerRecords.@NonNull KeptRecord kept, @NonNull Held held,
                                          @NonNull Wording words) {
        AppFacts facts = AppFacts.of(kept.document());
        return facts == null ? HeldApp.unreadable(kept.key(), kept.title(), held, words)
            : HeldApp.of(facts, held, words);
    }
}
