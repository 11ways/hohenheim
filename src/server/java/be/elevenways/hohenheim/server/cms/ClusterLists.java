package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.cluster.LinkRole;
import be.elevenways.hohenheim.cluster.LinkState;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cluster.ClusterRecords;
import be.elevenways.hohenheim.server.cluster.LinkedServers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.wording.Reader;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.HealthTone;
import be.elevenways.zenit.cms.common.resource.HeldRecords;
import be.elevenways.zenit.cms.common.resource.HeldRecords.Holder;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.peer.PeerRecordModel;
import be.elevenways.zenit.common.routing.RouteScope;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.peer.KeptRow;
import be.elevenways.zenit.server.peer.PeerRecords;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * THE Hohenheim wiring of a list of every server's things ({@link HeldRecords}): which servers this one manages, who
 * is shown their things, the words the lists share, and how a silent or unreadable server's thing reads.
 *
 * AIDEV-NOTE: the master decides who sees a linked server's things, and today it can decide that only by
 * permission: a thing's owners and grants are the linked server's own rows (RecordOwners over zenit-auth grants, no
 * shared kind), so a viewer who is no administrator sees none of them until grants follow the master. Never widen this
 * with a rule of its own.
 * AIDEV-NOTE: every read here is one per request whatever the servers and their things (the links, each kind's kept
 * rows), memoized on the request, so a list's statements stay flat as rows and servers grow.
 * AIDEV-NOTE: localized content: a server's name is content; every sentence is APP_LIST copy.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class ClusterLists {

    private static final IdentifierKey<Map<String, Holder>> SERVERS = IdentifierKey.of("hohenheim", "cluster_servers");

    private ClusterLists() {
    }

    /**
     * The declaration of a list's things of every server this one manages: shown to an administrator while it manages
     * one, its own things under its own name, in the lists' shared words.
     *
     * @param things the list's own word for its things in the notice ("Its sites are shown as..."), an APP_LIST key
     * @param count  the count line's word for its things ("15 sites"), an APP_LIST key taking {@code count}
     */
    static <T> HeldRecords.@NonNull Builder<T> declare(@NonNull Identifier id,
                                                       @NonNull Function<AccessContext, List<T>> records,
                                                       @NonNull Function<T, @Nullable Holder> holder,
                                                       @NonNull Function<T, String> key, @NonNull String things,
                                                       @NonNull String count) {
        return HeldRecords.of(id, ClusterLists::shows, records, holder, key)
            .ownName(LinkedServers::ownName)
            // What a linked server tells lands in its kept records and states and in its link's last seen.
            .heardFrom(PeerRecordModel.MODEL_ID, LinkedServerModel.MODEL_ID)
            .words(new HeldRecords.Words(HohenheimMicrocopy.APP_LIST.of("server_column"),
                HohenheimMicrocopy.APP_LIST.of("server_all"),
                held -> HohenheimMicrocopy.APP_LIST.of("server_silent_option").withArg("server", held.name()),
                (held, access) -> notice(held, things, access.getReader()),
                (listed, servers) -> HohenheimMicrocopy.APP_LIST.of("list_on_servers")
                    .withArg("records", HohenheimMicrocopy.APP_LIST.of(count).withArg("count", listed))
                    .withArg("servers", HohenheimMicrocopy.APP_LIST.of("list_server_count")
                        .withArg("count", servers))));
    }

    /** {@link #declare} of a row list whose held things are kept rows ({@link #rows}). */
    static HeldRecords.@NonNull Builder<Row> declareRows(@NonNull Identifier id,
                                                         @NonNull Function<AccessContext, List<Row>> records,
                                                         @NonNull String things, @NonNull String count) {
        return declare(id, records, ClusterLists::holderOf,
            row -> row instanceof KeptRow kept ? kept.listKey() : String.valueOf((Object) row.get("id")), things,
            count);
    }

    /**
     * @return whether this viewer is shown the things of the servers this one manages: an administrator, while it
     *         manages one
     */
    static boolean shows(@NonNull AccessContext access) {
        return HohenheimAccess.isAdmin(access) && !servers().isEmpty();
    }

    /** @return every server this one manages by the key its records are kept under, read once per request */
    static @NonNull Map<String, Holder> servers() {
        Conduit conduit = RouteScope.currentConduit();
        return conduit == null ? readServers() : conduit.memo(SERVERS, ClusterLists::readServers);
    }

    private static @NonNull Map<String, Holder> readServers() {
        Map<String, Holder> servers = new HashMap<>();
        String version = LinkedServers.ownVersion();
        for (Row server : LinkedServers.all()) {
            if (LinkedServers.roleOf(server) == LinkRole.MANAGES) {
                String peer = ClusterRecords.peerKey(server);
                servers.put(peer, new Holder(peer, String.valueOf((Object) server.get(LinkedServerModel.NAME)),
                    LinkState.of(server, version) == LinkState.NOT_ANSWERING,
                    server.get(LinkedServerModel.LAST_SEEN_AT)));
            }
        }
        return Map.copyOf(servers);
    }

    /** @return the server a kept row lives on, null for this server's own row or a server it no longer manages */
    static @Nullable Holder holderOf(@NonNull Row row) {
        return row instanceof KeptRow kept ? servers().get(kept.peer()) : null;
    }

    /**
     * Every kept row of a kind of the servers this one manages, revived as rows of {@code model}, read once per
     * request ({@link PeerRecords#rows}); an unreadable one is listed too ({@link KeptRow#readable}).
     */
    static @NonNull List<KeptRow> rows(@NonNull Model model, @NonNull Identifier kind) {
        Supplier<List<KeptRow>> read = () -> {
            Map<String, Holder> servers = servers();
            List<KeptRow> rows = new ArrayList<>();
            if (servers.isEmpty()) {
                return rows;
            }
            for (KeptRow row : PeerRecords.rows(model, kind)) {
                if (servers.containsKey(row.peer())) {
                    rows.add(row);
                }
            }
            return List.copyOf(rows);
        };
        Conduit conduit = RouteScope.currentConduit();
        return conduit == null ? read.get() : conduit.memo(IdentifierKey.of("hohenheim", "cluster_kept_"
            + kind.toString().replaceAll("[^a-z0-9_]", "_")), read);
    }

    /** @return the kept rows of a kind by the server they live on and their key there, read once per request */
    static @NonNull Map<String, KeptRow> byKey(@NonNull Model model, @NonNull Identifier kind) {
        Map<String, KeptRow> keyed = new LinkedHashMap<>();
        for (KeptRow row : rows(model, kind)) {
            keyed.put(row.listKey(), row);
        }
        return keyed;
    }

    /** @return the kept rows of a kind grouped by the server they live on and one of their values */
    static @NonNull Map<String, List<KeptRow>> grouped(@NonNull Model model, @NonNull Identifier kind,
                                                       @NonNull String field) {
        Map<String, List<KeptRow>> grouped = new HashMap<>();
        for (KeptRow row : rows(model, kind)) {
            grouped.computeIfAbsent(row.peer() + "-" + row.get(field), any -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    /**
     * The state the servers this one manages told of a state kind's things, by the server and the thing's key there,
     * read and parsed once per request; a document that is no JSON object is kept as null, so its thing reads as
     * unreadable (containsKey true), never as untold.
     */
    static @NonNull Map<String, @Nullable Map<?, ?>> told(@NonNull Identifier kind) {
        Supplier<Map<String, @Nullable Map<?, ?>>> read = () -> {
            Map<String, Holder> servers = servers();
            Map<String, @Nullable Map<?, ?>> told = new HashMap<>();
            if (servers.isEmpty()) {
                return told;
            }
            for (PeerRecords.PeerKept kept : PeerRecords.everyPeer(kind)) {
                if (servers.containsKey(kept.peer())) {
                    Object document;
                    try {
                        document = Zenit.DRY.parseJsonTree(kept.record().document());
                    } catch (RuntimeException unreadable) {
                        document = null;
                    }
                    told.put(kept.peer() + "-" + kept.record().key(), document instanceof Map<?, ?> map ? map : null);
                }
            }
            return told;
        };
        Conduit conduit = RouteScope.currentConduit();
        return conduit == null ? read.get() : conduit.memo(IdentifierKey.of("hohenheim", "cluster_told_"
            + kind.toString().replaceAll("[^a-z0-9_]", "_")), read);
    }

    /** @return the key of a server's thing among another kind's kept rows of the same server */
    static @NonNull String sibling(@NonNull KeptRow row, @Nullable Object key) {
        return row.peer() + "-" + key;
    }

    /**
     * @return a silent server's thing's state cell: when its server last answered, as one plain line and no badge,
     *         since what it told is no longer current
     */
    static @NonNull StateLineCell lastSeen(@NonNull Holder server, @NonNull Reader reader) {
        Microcopy line = server.lastSeen() == null ? HohenheimMicrocopy.APP_LIST.of("held_never_seen")
            : HohenheimMicrocopy.APP_LIST.of("held_last_seen").withArg("at", server.lastSeen())
                .withArg("day", AppDirectory.recentDay(reader, server.lastSeen()));
        return StateLineCell.line(HealthTone.SILENT.token(), line);
    }

    /** @return a silent server's thing's verdict: no state of its own, only that its server does not answer */
    static @NonNull RecordHealth silent(@NonNull Holder server) {
        return RecordHealth.silent(HohenheimMicrocopy.APP_LIST.of("held_silent").withArg("server", server.name()));
    }

    /** @return the verdict of a thing whose told record or state this build cannot read, saying so */
    static @NonNull RecordHealth unreadable(@NonNull Holder server) {
        return new RecordHealth(HealthTone.UNKNOWN, unreadableWords(server), null, List.of(), null);
    }

    /** @return the state cell of a thing whose told record or state this build cannot read, saying so */
    static @NonNull StateLineCell unreadableLine(@NonNull Holder server) {
        return StateLineCell.line(HealthTone.UNKNOWN.token(), unreadableWords(server));
    }

    private static @NonNull Microcopy unreadableWords(@NonNull Holder server) {
        return HohenheimMicrocopy.APP_LIST.of("held_unreadable_told").withArg("server", server.name());
    }

    /** @return the notice's sentence about one silent server, naming the list's things */
    private static @NonNull Microcopy notice(@NonNull Holder server, @NonNull String things, @NonNull Reader reader) {
        return server.lastSeen() == null ? HohenheimMicrocopy.APP_LIST.of("held_silent")
            .withArg("server", server.name()) : HohenheimMicrocopy.APP_LIST.of(things)
            .withArg("server", server.name()).withArg("since", server.lastSeen())
            .withArg("day", AppDirectory.recentDay(reader, server.lastSeen()));
    }
}
