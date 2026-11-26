package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.dns.DnsPeerMirrorResponse;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.HohenheimSystemPurposes;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunnerGroup;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.bootstrap.RuntimeJobs;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A DNS peer that mirrors every zone: this host, as primary, links every zone it is primary for to the peer and tells
 * the peer the full list; the peer, as secondary, keeps exactly those as secondary zones pulling from this host.
 *
 * AIDEV-NOTE: the request is SIGNED with the TSIG secret the two negotiated ({@link #signature}), over the key name,
 * the moment it was signed and the list, and the receiving half ({@link #adopt}) accepts it only within
 * {@link #SIGNATURE_WINDOW}: an admin API key alone, or an old list replayed, mirrors nothing. The receiver removes only
 * secondary zones that pull from that same peer, and never touches a zone it holds as primary or replicates from
 * someone else (a conflict, which it reports). The sending half runs after a mirroring peer is saved, after a zone
 * appears, disappears or changes its role or enablement, and every 10 minutes ({@code MirrorDnsPeers}), one round at a
 * time, so a peer that was unreachable catches up and two rounds never negotiate two different keys.
 *
 * AIDEV-NOTE: a peer that does NOT mirror everything gets a copy of one zone the moment it is linked as that zone's
 * secondary ({@link #copyOn}, received by {@link #adoptOne}): add-only, on a route of its own, signed with a purpose of
 * its own ({@link #addSignature}), so neither request can be replayed as the other and an older receiver never reads
 * one zone as the whole list.
 *
 * AIDEV-NOTE: switching mirroring off, or a zone leaving the mirror set, revokes nothing here: the zone-peer links
 * stay, and a working secondary keeps working until the operator deletes the peer or the zone. A mirroring round only
 * ever ADDS links; it cannot tell the ones it made from the ones an operator made.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class DnsPeerMirrors {

    /** How far a signed request's moment may lie from the receiver's clock, either way. */
    static final Duration SIGNATURE_WINDOW = Duration.ofMinutes(5);

    /** The first line of an add-only request's signature; a mirror request's first line is a key name, never this. */
    private static final String ADD_PURPOSE = "hohenheim-peer-mirror-add";

    /** Mirror rounds run here, one at a time and coalesced. */
    private static final JobRunnerGroup.Member MIRRORING =
        RuntimeJobs.GROUP.member("dns-mirror", JobRunnerGroup.StopMode.DRAIN, 1);

    /** Whether a round is queued and has not started yet; a request while one is queued adds nothing. */
    private static final AtomicBoolean QUEUED = new AtomicBoolean();

    /** A write outside a transaction that asked for a round: requested once the write itself has landed. */
    private static final ThreadLocal<Boolean> AFTER_WRITE = new ThreadLocal<>();

    /** Serializes every round, the scheduled task's included. */
    private static final Object ROUND = new Object();

    private static volatile boolean installed;

    private DnsPeerMirrors() {
    }

    /** Install the triggers on the peer and zone models; idempotent, called at the MODULES boot stage. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        DnsPeerModel.SCHEMA.addBeforeWriteHook(context -> {
            Row peer = context.getRow();
            if (peer != null && changed(Models.get(DnsPeerModel.class), peer, DnsPeerModel.MIRRORS_ALL_ZONES,
                    DnsPeerModel.ENABLED, DnsPeerModel.PEER_TYPE, DnsPeerModel.BASE_URL, DnsPeerModel.API_KEY)
                    && DnsPeerModel.mirrorsAllZones(peer)) {
                requestAfterWrite();
            }
        });
        DnsPeerModel.SCHEMA.addAfterSaveHook(context -> drainAfterWrite());
        DnsZoneModel.SCHEMA.addBeforeWriteHook(context -> {
            Row zone = context.getRow();
            if (zone != null && changed(Models.get(DnsZoneModel.class), zone, DnsZoneModel.ORIGIN,
                    DnsZoneModel.ROLE, DnsZoneModel.ENABLED)) {
                requestAfterWrite();
            }
        });
        DnsZoneModel.SCHEMA.addAfterSaveHook(context -> drainAfterWrite());
        DnsZoneModel.SCHEMA.addAfterRemoveHook(context -> requestAfterCommit());
    }

    /** Whether the write in flight creates the row or changes any of {@code fields} from what is stored. */
    private static boolean changed(@NonNull Model model, @NonNull Row row, @NonNull Field<?, ?>... fields) {
        Row stored = StoredRows.of(model, row);
        if (stored == null) {
            return true;
        }
        for (Field<?, ?> field : fields) {
            if (!Objects.equals(row.afterWrite(field, stored), stored.get(field))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Asked before a write: a round once its transaction commits, or, with none, once the write has landed (the after-
     * save hook), never before, or the round would read what the write is about to replace.
     */
    private static void requestAfterWrite() {
        Datasource datasource = Models.get(DnsPeerModel.class).getResolvedDatasource();
        if (datasource.hasActiveTransaction()) {
            datasource.afterCommit(DnsPeerMirrors::request);
        } else {
            AFTER_WRITE.set(Boolean.TRUE);
        }
    }

    private static void drainAfterWrite() {
        if (Boolean.TRUE.equals(AFTER_WRITE.get())) {
            AFTER_WRITE.remove();
            requestAfterCommit();
        }
    }

    /** Ask for a mirror round once the write in flight commits, or now when none is in flight. */
    public static void requestAfterCommit() {
        Datasource datasource = Models.get(DnsPeerModel.class).getResolvedDatasource();
        if (datasource.hasActiveTransaction()) {
            datasource.afterCommit(DnsPeerMirrors::request);
        } else {
            request();
        }
    }

    private static void request() {
        if (!QUEUED.compareAndSet(false, true)) {
            return;
        }
        MIRRORING.get().runAsync(() -> ExecutionIdentity.runAsSystem(HohenheimSystemPurposes.DNS_MIRROR, () -> {
            QUEUED.set(false);
            mirrorAll();
        }));
    }

    /**
     * Waits until no mirror round is queued or running; a test's settle before it reads what a round wrote.
     *
     * @return false when a round was still in flight at the timeout
     */
    public static boolean awaitIdle(@NonNull Duration timeout) {
        return MIRRORING.awaitIdle(timeout);
    }

    /** Brings every mirroring peer up to date, one at a time; a peer that fails is logged and left for the next round. */
    public static void mirrorAll() {
        synchronized (ROUND) {
            for (Row peer : Models.get(DnsPeerModel.class).find().all()) {
                if (!DnsPeerModel.mirrorsAllZones(peer)) {
                    continue;
                }
                try {
                    DnsPeerMirrorResponse answer = mirror(peer);
                    if (!answer.created().isEmpty() || !answer.removed().isEmpty()) {
                        Blast.log("DNS: peer", peer.get(DnsPeerModel.NAME), "mirrors this host's zones: created",
                            answer.created(), "removed", answer.removed(), "left alone", answer.conflicts());
                    }
                } catch (RuntimeException failed) {
                    Blast.log("DNS: could not bring peer", peer.get(DnsPeerModel.NAME), "up to date -",
                        failed.getMessage());
                }
            }
        }
    }

    /**
     * Brings one peer up to date: a transfer key negotiated when it has none, every enabled primary zone linked to it,
     * and the peer told the full list, signed with that key.
     *
     * @return what the peer created, removed and left alone
     * @throws DnsPeerApi.PeerApiException when the peer cannot be reached, refuses, or confirms another key name
     * @throws IllegalArgumentException when the peer is not a Hohenheim peer with credentials
     */
    public static @NonNull DnsPeerMirrorResponse mirror(@NonNull Row peer) {
        synchronized (ROUND) {
            DnsPeerApi api = clientOf(peer);
            String keyName = keyNameOf(peer, api);
            String secret = Objects.requireNonNull(peer.get(DnsPeerModel.TSIG_SECRET), "a keyed peer holds its secret");
            int peerId = peer.get(DnsPeerModel.ID);
            List<String> origins = new ArrayList<>();
            for (Row zone : Models.get(DnsZoneModel.class).findEnabled()) {
                if (DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
                    continue;
                }
                origins.add(zone.get(DnsZoneModel.ORIGIN));
                linkOnce(zone.get(DnsZoneModel.ID), peerId);
            }
            long signedAt = Now.instant().getEpochSecond();
            return api.mirrorZones(keyName, signedAt, signature(secret, keyName, signedAt, origins), origins);
        }
    }

    /**
     * Whether linking the peer as a secondary of the zone makes the peer keep its copy ({@link #copyOn}): a primary
     * zone, an enabled Hohenheim peer this instance holds credentials of, which does not mirror every zone already.
     */
    public static boolean copiesTo(@NonNull Row zone, @NonNull Row peer) {
        return copiesTo(DnsZoneModel.roleOf(zone), peer);
    }

    /** {@link #copiesTo(Row, Row)} for a zone of {@code role}, before it is written. */
    public static boolean copiesTo(@Nullable Object role, @NonNull Row peer) {
        return !DnsZoneModel.ROLE_SECONDARY.equals(role)
            && Boolean.TRUE.equals(peer.get(DnsPeerModel.ENABLED)) && !DnsPeerModel.mirrorsAllZones(peer)
            && DnsPeerApi.forPeer(peer) != null;
    }
    /**
     * Asks a Hohenheim peer to keep a copy of one zone pulling from this instance, a transfer key negotiated first
     * when the two share none; with {@code check} it only asks whether it could, which changes nothing there.
     *
     * @return the peer's answer: the origin under conflicts when it holds that zone of its own or from someone else
     * @throws DnsPeerApi.PeerApiException when the peer cannot be reached, refuses, or confirms another key name
     * @throws IllegalArgumentException when the peer is not a Hohenheim peer with credentials
     */
    public static @NonNull DnsPeerMirrorResponse copyOn(@NonNull Row peer, @NonNull String origin, boolean check) {
        synchronized (ROUND) {
            DnsPeerApi api = clientOf(peer);
            String keyName = keyNameOf(peer, api);
            String secret = Objects.requireNonNull(peer.get(DnsPeerModel.TSIG_SECRET), "a keyed peer holds its secret");
            long signedAt = Now.instant().getEpochSecond();
            return api.mirrorAdd(keyName, signedAt, addSignature(secret, keyName, signedAt, origin, check), origin,
                check);
        }
    }

    /** @throws IllegalArgumentException when the peer is not a Hohenheim peer with credentials */
    private static @NonNull DnsPeerApi clientOf(@NonNull Row peer) {
        DnsPeerApi api = DnsPeerApi.forPeer(peer);
        if (api == null) {
            throw new IllegalArgumentException("peer " + peer.get(DnsPeerModel.NAME)
                + " is no Hohenheim peer with credentials, so it cannot be told which zones to keep");
        }
        return api;
    }

    /**
     * @return the transfer key name the peer and this instance share, negotiated now when they share none
     * @throws DnsPeerApi.PeerApiException when the negotiation fails or the peer confirms another key name
     */
    private static @NonNull String keyNameOf(@NonNull Row peer, @NonNull DnsPeerApi api) {
        String keyName = peer.get(DnsPeerModel.TSIG_KEY_NAME);
        String secret = peer.get(DnsPeerModel.TSIG_SECRET);
        if (keyName != null && !keyName.isBlank() && secret != null && !secret.isBlank()) {
            return keyName;
        }
        DnsFederationKeys.Negotiated negotiated = DnsFederationKeys.negotiate(peer, api);
        if (!negotiated.stored()) {
            throw new DnsPeerApi.PeerApiException("The peer confirmed another transfer key name", null, null);
        }
        return negotiated.keyName();
    }

    /**
     * THE signature of a mirror request, both halves: HMAC-SHA256 under the shared TSIG secret over the key name, the
     * moment it was signed and the list, one per line.
     */
    public static @NonNull String signature(@NonNull String secret, @NonNull String keyName, long signedAt,
                                            @NonNull List<String> origins) {
        return SecureTokens.hmacSha256Hex(secret, keyName + "\n" + signedAt + "\n" + String.join("\n", origins));
    }

    /**
     * THE signature of an add-only request, both halves: HMAC-SHA256 under the shared TSIG secret over a purpose line
     * no mirror request starts with, the key name, the moment, whether it only checks, and the origin.
     */
    public static @NonNull String addSignature(@NonNull String secret, @NonNull String keyName, long signedAt,
                                               @NonNull String origin, boolean check) {
        return SecureTokens.hmacSha256Hex(secret, ADD_PURPOSE + "\n" + keyName + "\n" + signedAt + "\n"
            + (check ? "check" : "add") + "\n" + origin);
    }

    /** Links the zone to the peer unless it already is: the link is what lets the peer pull it and be notified. */
    static void linkOnce(int zoneId, int peerId) {
        DnsZonePeerModel links = Models.get(DnsZonePeerModel.class);
        if (links.find().where(DnsZonePeerModel.ZONE_ID.eq(zoneId)).where(DnsZonePeerModel.PEER_ID.eq(peerId))
                .first() != null) {
            return;
        }
        Row link = links.createEmptyRow();
        link.set(DnsZonePeerModel.ZONE_ID, zoneId);
        link.set(DnsZonePeerModel.PEER_ID, peerId);
        links.save(link);
    }

    /**
     * The receiving half: keeps exactly {@code origins} as secondary zones pulling from the peer that holds
     * {@code keyName}, once the request proves it holds that key's secret.
     *
     * @return what was created, removed and left alone (an origin that is no zone name among the latter), or null when
     *         no peer here holds that key, the signature does not verify or it was signed outside the window
     */
    public static @Nullable DnsPeerMirrorResponse adopt(@NonNull String keyName, long signedAt,
                                                        @NonNull String signature, @NonNull List<String> origins) {
        Row peer = Models.get(DnsPeerModel.class).findByTsigKeyName(keyName);
        String secret = peer == null ? null : peer.get(DnsPeerModel.TSIG_SECRET);
        if (secret == null || secret.isBlank()
                || Math.abs(Now.instant().getEpochSecond() - signedAt) > SIGNATURE_WINDOW.toSeconds()
                || !SecureTokens.constantTimeEquals(signature(secret, keyName, signedAt, origins), signature)) {
            return null;
        }
        int peerId = peer.get(DnsPeerModel.ID);
        DnsZoneModel zones = Models.get(DnsZoneModel.class);
        Set<String> wanted = new LinkedHashSet<>();
        List<String> conflicts = new ArrayList<>();
        for (String origin : origins) {
            String normalized = DnsNames.normalizeOrigin(origin);
            if (normalized != null) {
                wanted.add(normalized);
            } else {
                conflicts.add(origin);
            }
        }
        List<String> created = new ArrayList<>();
        for (String origin : wanted) {
            Row existing = zones.findByOrigin(origin);
            if (existing == null) {
                createCopy(zones, origin, peerId);
                created.add(origin);
            } else if (!pullsFrom(existing, peerId)) {
                conflicts.add(origin);
            }
        }
        List<String> removed = new ArrayList<>();
        for (Row zone : zones.findSecondaries()) {
            String origin = zone.get(DnsZoneModel.ORIGIN);
            if (pullsFrom(zone, peerId) && !wanted.contains(origin)) {
                zones.delete(zone);
                removed.add(origin);
            }
        }
        if (!created.isEmpty() || !removed.isEmpty()) {
            DnsZoneStore.INSTANCE.reload();
        }
        return new DnsPeerMirrorResponse("ok", created, removed, conflicts);
    }

    /**
     * The receiving half of {@link #copyOn}: keeps a copy of {@code origin} pulling from the peer that holds
     * {@code keyName}, once the request proves it holds that key's secret; every other zone is left as it is.
     *
     * @param check only answer whether the copy could be made, changing nothing
     * @return created holding the origin when the copy was made now, conflicts holding it when a zone of that origin
     *         is kept here of its own or copied from someone else, neither when it already copies it from that peer
     *         (or, checking, could be made); null when no peer here holds that key, the signature does not verify or
     *         it was signed outside the window
     * @throws IllegalStateException when this host knows no address to transfer from that peer, so a copy would never
     *         be filled
     */
    public static @Nullable DnsPeerMirrorResponse adoptOne(@NonNull String keyName, long signedAt,
                                                           @NonNull String signature, @NonNull String origin,
                                                           boolean check) {
        Row peer = Models.get(DnsPeerModel.class).findByTsigKeyName(keyName);
        String secret = peer == null ? null : peer.get(DnsPeerModel.TSIG_SECRET);
        if (secret == null || secret.isBlank()
                || Math.abs(Now.instant().getEpochSecond() - signedAt) > SIGNATURE_WINDOW.toSeconds()
                || !SecureTokens.constantTimeEquals(addSignature(secret, keyName, signedAt, origin, check),
                    signature)) {
            return null;
        }
        String host = peer.get(DnsPeerModel.TRANSFER_HOST);
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("this Hohenheim knows no transfer address of "
                + peer.get(DnsPeerModel.NAME) + ", so its copy could never be filled: give that peer a transfer host");
        }
        int peerId = peer.get(DnsPeerModel.ID);
        String normalized = DnsNames.normalizeOrigin(origin);
        DnsZoneModel zones = Models.get(DnsZoneModel.class);
        Row existing = normalized == null ? null : zones.findByOrigin(normalized);
        if (normalized == null || (existing != null && !pullsFrom(existing, peerId))) {
            return new DnsPeerMirrorResponse("ok", List.of(), List.of(), List.of(origin));
        }
        if (existing != null || check) {
            return new DnsPeerMirrorResponse("ok", List.of(), List.of(), List.of());
        }
        createCopy(zones, normalized, peerId);
        DnsZoneStore.INSTANCE.reload();
        Blast.log("DNS: peer", peer.get(DnsPeerModel.NAME), "made this host keep a copy of", normalized);
        return new DnsPeerMirrorResponse("ok", List.of(normalized), List.of(), List.of());
    }

    /** Creates a secondary zone of the origin pulling from the peer; the caller reloads the serving view. */
    private static void createCopy(@NonNull DnsZoneModel zones, @NonNull String origin, int peerId) {
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, origin);
        zone.set(DnsZoneModel.ROLE, DnsZoneModel.ROLE_SECONDARY);
        zone.set(DnsZoneModel.PRIMARY_PEER_ID, peerId);
        zone.set(DnsZoneModel.ENABLED, true);
        zones.save(zone);
    }

    /** @return whether the zone is a copy pulling from the peer */
    static boolean pullsFrom(@NonNull Row zone, int peerId) {
        return DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))
            && Objects.equals(zone.get(DnsZoneModel.PRIMARY_PEER_ID), peerId);
    }
}
