package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.dns.DnsRecordDto;
import be.elevenways.hohenheim.dns.DnsZoneHandOverResponse;
import be.elevenways.hohenheim.dns.DnsZoneStateRecord;
import be.elevenways.hohenheim.dns.DnsZoneStateResponse;
import be.elevenways.hohenheim.dns.DnsZoneStateSecondary;
import be.elevenways.hohenheim.model.DnsDyndnsCredentialModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.Record;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A zone moved from the Hohenheim keeping its records to the one holding its copy, both halves: {@link #takeOver} on
 * the copy's server, {@link #state} and {@link #handOver} answered by the owner over the peer API.
 *
 * AIDEV-NOTE: the steps, and why no failure leaves a name unanswered or two servers keeping different records:
 * 1. The taker asks, changing nothing: a copy here, its owner a Hohenheim peer answering the state route (an older
 *    one has none), no DNSSEC (its signing key cannot move yet), the copy current (the owner's serial, transferred,
 *    served), the owner linking this server with the transfer key both hold and an API key to call it back, and no
 *    record tied to the owner (an app's game address, a preview). A refusal changes nothing anywhere.
 * 2. One transaction here, guarded on the copy's role and serial: the zone becomes primary at the owner's serial + 1
 *    with the owner's rows, settings and dynamic DNS digests, the owner (and its other secondaries that are peers
 *    here) linked. The serving view swaps once, after the commit, from the copy to the same records
 *    (DnsZoneStore.reloadNow prunes the copy as it publishes the primary). A failure rolls back: still the copy.
 * 3. The owner hands over ({@link #handOver}): it pulls the zone from here, refuses unless the serial is the new one
 *    and every record its own, puts the pulled copy in its serving view (a copy wins over a primary there), then in
 *    one transaction guarded on its serial turns its zone into a secondary of this server and drops its rows. Any
 *    failure there takes the pulled copy out again: it serves its own rows as before.
 * 4. Whatever the hand-over answered short of ok, the owner is asked its state once more (its state and hand-over
 *    are one at a time there, so the answer is final): a copy of this server finished the move; a primary did not,
 *    and step 2 is undone here, the copy back in the serving view before and after the commit, so that swap is one
 *    too. No answer at all keeps this server primary: both then serve the same records, the owner's rows are never
 *    dropped while this server has none, and the answer says what to check on the owner.
 * 5. Done: the secondaries are notified; both histories name the other server.
 *
 * AIDEV-NOTE: the owner's hand-over keeps within {@link #HAND_OVER_BUDGET}, under the deadline the taker's peer
 * client waits, so a slow pull is refused there rather than finished after the taker stopped waiting.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class DnsZoneMoves {

    /** The longest an owner's hand-over may run before it changes anything, well under the peer client's deadline. */
    static final Duration HAND_OVER_BUDGET = Duration.ofSeconds(9);

    /** One move at a time on the copy's side. */
    private static final Object TAKING = new Object();

    /** One state read or hand-over at a time on the owner's side, so a read after a hand-over sees how it ended. */
    private static final Object HANDING = new Object();

    /** The zone columns a take-over writes, kept to put back when it is undone. */
    private static final List<Field<?, ?>> TAKEN = List.of(DnsZoneModel.ROLE, DnsZoneModel.PRIMARY_PEER_ID,
        DnsZoneModel.SERIAL, DnsZoneModel.SOA_PRIMARY_NS, DnsZoneModel.SOA_CONTACT, DnsZoneModel.DEFAULT_TTL,
        DnsZoneModel.NEGATIVE_TTL, DnsZoneModel.SOA_REFRESH, DnsZoneModel.SOA_RETRY, DnsZoneModel.SOA_EXPIRE,
        DnsZoneModel.TRANSFER_STATUS, DnsZoneModel.TRANSFER_MESSAGE, DnsZoneModel.LAST_CHECKED_AT,
        DnsZoneModel.LAST_TRANSFER_AT, DnsZoneModel.REPLICA_RECORDS);

    private DnsZoneMoves() {
    }

    /** An owner's refusal of a hand-over, in plain words for the taker's administrator; nothing was changed. */
    public static final class Refused extends RuntimeException {
        Refused(@NonNull String message) {
            super(message);
        }
    }

    /**
     * What a take-over did.
     *
     * @param linked    this server's peers linked as secondaries beside the old owner
     * @param unlinked  the old owner's other secondaries that are no peer here; they still copy from the old owner
     * @param confirmed false when the old owner could not be asked whether it gave the zone up
     */
    public record Moved(@NonNull String zone, @NonNull String owner, int serial, int records,
                        @NonNull List<String> linked, @NonNull List<String> unlinked, boolean confirmed) {
    }

    /** @return the zone's whole editable state as stored here, the answer of the state route */
    public static @NonNull DnsZoneStateResponse state(@NonNull Row asked) {
        synchronized (HANDING) {
            Row zone = stored(asked);
            int zoneId = zone.get(DnsZoneModel.ID);
            String role = DnsZoneModel.roleOf(zone);
            boolean secondary = DnsZoneModel.ROLE_SECONDARY.equals(role);
            List<DnsZoneStateRecord> records = new ArrayList<>();
            if (!secondary) {
                List<Row> rows = Models.get(DnsRecordModel.class).find().noCache()
                    .where(DnsRecordModel.ZONE_ID.eq(zoneId)).orderBy(DnsRecordModel.ID, SortOrder.ASC).all();
                Map<Integer, String> digests = new HashMap<>();
                List<Integer> ids = rows.stream().map(row -> (Integer) row.get(DnsRecordModel.ID)).toList();
                if (!ids.isEmpty()) {
                    for (Row credential : Models.get(DnsDyndnsCredentialModel.class).find().noCache()
                            .where(DnsDyndnsCredentialModel.RECORD_ID.in(ids)).all()) {
                        digests.put(credential.get(DnsDyndnsCredentialModel.RECORD_ID),
                            credential.get(DnsDyndnsCredentialModel.TOKEN_DIGEST));
                    }
                }
                for (Row row : rows) {
                    records.add(new DnsZoneStateRecord(DnsPeerApi.recordDto(row), row.get(DnsRecordModel.GENERATED_BY),
                        digests.get(row.get(DnsRecordModel.ID))));
                }
            }
            List<DnsZoneStateSecondary> secondaries = new ArrayList<>();
            for (DnsZonePeers.Linked linked : DnsZonePeers.enabled(zoneId)) {
                Row peer = linked.peer();
                secondaries.add(new DnsZoneStateSecondary(String.valueOf((Object) peer.get(DnsPeerModel.NAME)),
                    peer.get(DnsPeerModel.TSIG_KEY_NAME), peer.get(DnsPeerModel.TRANSFER_HOST),
                    DnsPeerApi.forPeer(peer) != null));
            }
            Row primary = secondary ? Models.get(DnsPeerModel.class).findById(zone.get(DnsZoneModel.PRIMARY_PEER_ID))
                : null;
            return new DnsZoneStateResponse(zone.get(DnsZoneModel.ORIGIN), role,
                primary != null ? primary.get(DnsPeerModel.TSIG_KEY_NAME) : null,
                Objects.requireNonNullElse(zone.get(DnsZoneModel.SERIAL), 0),
                Boolean.TRUE.equals(zone.get(DnsZoneModel.DNSSEC_ENABLED)), true,
                zone.get(DnsZoneModel.SOA_PRIMARY_NS), zone.get(DnsZoneModel.SOA_CONTACT),
                zone.get(DnsZoneModel.DEFAULT_TTL), zone.get(DnsZoneModel.NEGATIVE_TTL),
                zone.get(DnsZoneModel.SOA_REFRESH), zone.get(DnsZoneModel.SOA_RETRY),
                zone.get(DnsZoneModel.SOA_EXPIRE), records, secondaries);
        }
    }

    /**
     * The owner's half (step 3): turns a primary zone into a copy pulling from the peer holding {@code keyName}, once
     * the zone pulled from there is its own at {@code serial}; its rows go in the same transaction.
     *
     * @param fromSerial the serial the taker read, which must still be this zone's
     * @param server     the taker's name for itself, for this zone's history
     * @throws Refused naming why, with nothing changed here
     */
    public static @NonNull DnsZoneHandOverResponse handOver(@NonNull Row asked, @NonNull String keyName,
                                                            int fromSerial, int serial, @NonNull String server) {
        synchronized (HANDING) {
            long started = Now.millis();
            Row zone = stored(asked);
            int zoneId = zone.get(DnsZoneModel.ID);
            String origin = zone.get(DnsZoneModel.ORIGIN);
            Row peer = Models.get(DnsPeerModel.class).findByTsigKeyName(keyName);
            if (peer == null) {
                throw new Refused("no peer here holds the transfer key " + keyName);
            }
            String peerName = String.valueOf((Object) peer.get(DnsPeerModel.NAME));
            int current = Objects.requireNonNullElse(zone.get(DnsZoneModel.SERIAL), 0);
            if (current != fromSerial) {
                throw new Refused(origin + " changed here since its state was read (serial " + current + ", not "
                    + fromSerial + ")");
            }
            if (Boolean.TRUE.equals(zone.get(DnsZoneModel.DNSSEC_ENABLED))) {
                throw new Refused(origin + " is signed with DNSSEC here");
            }
            List<Record> pulled;
            DnsZoneSnapshot copy;
            try {
                pulled = SecondaryZoneService.pull(peer, origin, HAND_OVER_BUDGET);
                copy = DnsZoneStore.snapshotFromTransfer(zoneId, origin, pulled);
            } catch (Exception unpulled) {
                throw new Refused("could not pull " + origin + " from " + peerName + ": " + unpulled.getMessage());
            }
            if (copy.getSerial() != Integer.toUnsignedLong(serial)) {
                throw new Refused(peerName + " serves " + origin + " at serial " + copy.getSerial() + ", not " + serial);
            }
            DnsZoneSnapshot own = DnsZoneStore.INSTANCE.getZone(origin);
            if (own == null || !new HashSet<>(own.allRecordsExceptSoa()).equals(
                    new HashSet<>(copy.allRecordsExceptSoa()))) {
                throw new Refused("the records " + peerName + " serves for " + origin + " differ from the ones here");
            }
            if (Now.millis() - started > HAND_OVER_BUDGET.toMillis()) {
                throw new Refused("pulling " + origin + " from " + peerName + " took too long");
            }
            int peerId = peer.get(DnsPeerModel.ID);
            DnsZoneModel zones = Models.get(DnsZoneModel.class);
            Datasource datasource = zones.getResolvedDatasource();
            // The pulled copy answers from now on: it wins over this zone's own rows, the same records.
            DnsZoneStore.INSTANCE.putSecondarySnapshot(copy);
            boolean handed;
            try {
                handed = datasource.inTransaction(() -> {
                    Instant now = Now.instant();
                    if (zones.find().where(DnsZoneModel.ID.eq(zoneId)).where(DnsZoneModel.SERIAL.eq(fromSerial))
                            .assign(DnsZoneModel.ROLE, DnsZoneModel.ROLE_SECONDARY)
                            .assign(DnsZoneModel.PRIMARY_PEER_ID, peerId).assign(DnsZoneModel.SERIAL, serial)
                            .assign(DnsZoneModel.TRANSFER_STATUS, DnsZoneModel.TRANSFER_OK)
                            .assign(DnsZoneModel.TRANSFER_MESSAGE, null).assign(DnsZoneModel.LAST_TRANSFER_AT, now)
                            .assign(DnsZoneModel.LAST_CHECKED_AT, now)
                            .assign(DnsZoneModel.REPLICA_RECORDS, SecondaryZoneService.serializeReplica(pulled))
                            .updateAll() != 1) {
                        // An edit landed since the serial was checked: nothing else is written.
                        return false;
                    }
                    GeneratedDnsRecords.sweeping(() -> Models.get(DnsRecordModel.class).find()
                        .where(DnsRecordModel.ZONE_ID.eq(zoneId)).delete());
                    Models.get(DnsZonePeerModel.class).find().where(DnsZonePeerModel.ZONE_ID.eq(zoneId))
                        .where(DnsZonePeerModel.PEER_ID.eq(peerId)).delete();
                    History.event(zones, zoneId, Change.fact(HohenheimActivityAction.HANDED_OVER,
                        Map.of(HohenheimActivityAction.SERVER_KEY, server)));
                    serveAfterCommit(datasource, copy);
                    return true;
                });
            } catch (RuntimeException failed) {
                if (!pullsFrom(zoneId, peerId)) {
                    DnsZoneStore.INSTANCE.removeSecondarySnapshot(origin);
                    throw new Refused("the hand-over of " + origin + " failed here: " + failed.getMessage());
                }
                // It committed and failed after: the copy keeps answering, which is what it now is.
                DnsZoneStore.INSTANCE.putSecondarySnapshot(copy);
                handed = true;
            }
            if (!handed) {
                DnsZoneStore.INSTANCE.removeSecondarySnapshot(origin);
                throw new Refused(origin + " changed here during the hand-over");
            }
            Blast.log("DNS: handed", origin, "over to", peerName, "at serial", serial);
            return new DnsZoneHandOverResponse("ok", serial);
        }
    }

    /**
     * The copy's half: takes a secondary zone over from the Hohenheim peer keeping its records, as the class note's
     * steps say.
     *
     * @return what it did; {@code confirmed} false when the owner could not be asked how its hand-over ended
     * @throws Violations in plain words for every refusal, nothing changed; or once the owner refused the copy, with
     *         this zone a copy here again
     */
    public static @NonNull Moved takeOver(@NonNull Row asked) {
        synchronized (TAKING) {
            Row zone = stored(asked);
            int zoneId = zone.get(DnsZoneModel.ID);
            String origin = zone.get(DnsZoneModel.ORIGIN);
            if (!DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
                throw refused(words("dns_take_over_not_secondary", origin, ""));
            }
            Row owner = Models.get(DnsPeerModel.class).findById(zone.get(DnsZoneModel.PRIMARY_PEER_ID));
            String ownerName = owner == null ? "" : String.valueOf((Object) owner.get(DnsPeerModel.NAME));
            DnsPeerApi api = DnsPeerApi.forPeer(owner);
            if (owner == null || api == null) {
                throw refused(words("dns_take_over_owner_unknown", origin, ownerName));
            }
            String keyName = owner.get(DnsPeerModel.TSIG_KEY_NAME);
            String secret = owner.get(DnsPeerModel.TSIG_SECRET);
            if (keyName == null || keyName.isBlank() || secret == null || secret.isBlank()) {
                throw refused(words("dns_take_over_no_key", origin, ownerName));
            }
            DnsZoneStateResponse state;
            try {
                state = api.zoneState(origin);
            } catch (DnsPeerApi.PeerApiException unasked) {
                throw refused(unasked.older()
                    ? words("dns_take_over_older", origin, ownerName)
                    : words(unasked.unreached() ? "dns_take_over_unreachable"
                        : "dns_take_over_unanswered", origin, ownerName)
                        .withArg("reason", String.valueOf(unasked.getMessage())));
            }
            requireMovable(zone, state, keyName, origin, ownerName);
            int serial = Math.addExact(state.serial(), 1);
            DnsZoneSnapshot copy = Objects.requireNonNull(DnsZoneStore.INSTANCE.secondarySnapshot(origin),
                "a current copy is served");
            Map<Field<?, ?>, Object> prior = new LinkedHashMap<>();
            for (Field<?, ?> field : TAKEN) prior.put(field, zone.get(field.getName()));
            Set<Integer> priorLinks = DnsZonePeers.peerIds(zoneId);
            List<String> linked = new ArrayList<>();
            List<String> unlinked = new ArrayList<>();
            if (!take(zoneId, zone, serial, state, owner, keyName, linked, unlinked)) {
                throw refused(words("dns_take_over_behind", origin, ownerName)
                    .withArg("copy", String.valueOf(zone.get(DnsZoneModel.SERIAL)))
                    .withArg("serial", String.valueOf(state.serial())));
            }
            String failure = null;
            try {
                api.handOver(origin, keyName, state.serial(), serial, DnsFederationKeys.localName());
            } catch (DnsPeerApi.PeerApiException failed) {
                failure = String.valueOf(failed.getMessage());
            }
            Boolean handed = failure == null ? Boolean.TRUE : handedOver(api, origin, keyName);
            if (Boolean.FALSE.equals(handed)) {
                undo(zoneId, prior, copy, priorLinks, ownerName);
                throw refused(words("dns_take_over_undone", origin, ownerName).withArg("reason", failure));
            }
            DnsNotifier.INSTANCE.notifyZonePeers(zoneId, DnsZoneStore.INSTANCE.publishedSerial(zoneId));
            Blast.log("DNS: took", origin, "over from", ownerName, "at serial", serial,
                handed == null ? "(unconfirmed)" : "");
            return new Moved(origin, ownerName, serial, state.records().size(), linked, unlinked, handed != null);
        }
    }

    /** @throws Violations for every reason the owner's state forbids the move, in its own sentence */
    private static void requireMovable(@NonNull Row zone, @NonNull DnsZoneStateResponse state,
                                       @NonNull String keyName, @NonNull String origin, @NonNull String owner) {
        if (!state.can_hand_over()) {
            throw refused(words("dns_take_over_older", origin, owner));
        }
        if (!DnsZoneModel.ROLE_PRIMARY.equals(state.role())) {
            throw refused(words("dns_take_over_not_primary", origin, owner));
        }
        if (state.dnssec()) {
            throw refused(words("dns_take_over_dnssec", origin, owner));
        }
        DnsZoneSnapshot copy = DnsZoneStore.INSTANCE.secondarySnapshot(origin);
        Integer local = zone.get(DnsZoneModel.SERIAL);
        if (copy == null || copy.getSerial() != Integer.toUnsignedLong(state.serial())
                || !Objects.equals(local, state.serial())
                || !DnsZoneModel.TRANSFER_OK.equals(zone.get(DnsZoneModel.TRANSFER_STATUS))) {
            throw refused(words("dns_take_over_behind", origin, owner).withArg("copy", String.valueOf(local))
                .withArg("serial", String.valueOf(state.serial())));
        }
        DnsZoneStateSecondary self = selfIn(state, keyName);
        if (self == null || self.transfer_host() == null || self.transfer_host().isBlank()) {
            throw refused(words("dns_take_over_not_linked", origin, owner));
        }
        if (!self.calls_back()) {
            throw refused(words("dns_take_over_no_call_back", origin, owner));
        }
        Set<String> tied = new LinkedHashSet<>();
        int count = 0;
        for (DnsZoneStateRecord record : state.records()) {
            String source = record.generated_by();
            if (source != null && !GeneratedDnsRecords.SOURCE_ACME.equals(source)) {
                tied.add(source);
                count++;
            }
        }
        if (count > 0) {
            throw refused(words("dns_take_over_tied", origin, owner).withArg("count", count)
                .withArg("sources", String.join(", ", tied)));
        }
    }

    /**
     * Step 2: in one transaction, guarded on the copy's role and serial, makes the zone primary here with the owner's
     * rows, settings and dynamic DNS digests, linking the owner and its other secondaries that are peers here.
     *
     * @return false when the copy changed since it was judged, with nothing written
     */
    private static boolean take(int zoneId, @NonNull Row zone, int serial, @NonNull DnsZoneStateResponse state,
                                @NonNull Row owner, @NonNull String keyName, @NonNull List<String> linked,
                                @NonNull List<String> unlinked) {
        DnsZoneModel zones = Models.get(DnsZoneModel.class);
        return zones.getResolvedDatasource().inTransaction(() -> {
            QueryBuilder<?> becomes = zones.find().where(DnsZoneModel.ID.eq(zoneId))
                .where(DnsZoneModel.ROLE.eq(DnsZoneModel.ROLE_SECONDARY))
                .where(DnsZoneModel.SERIAL.eq(zone.get(DnsZoneModel.SERIAL)))
                .assign(DnsZoneModel.ROLE, DnsZoneModel.ROLE_PRIMARY).assign(DnsZoneModel.PRIMARY_PEER_ID, null)
                .assign(DnsZoneModel.SERIAL, serial).assign(DnsZoneModel.TRANSFER_STATUS, null)
                .assign(DnsZoneModel.TRANSFER_MESSAGE, null).assign(DnsZoneModel.LAST_CHECKED_AT, null)
                .assign(DnsZoneModel.LAST_TRANSFER_AT, null).assign(DnsZoneModel.REPLICA_RECORDS, null);
            given(becomes, DnsZoneModel.SOA_PRIMARY_NS, state.soa_primary_ns());
            given(becomes, DnsZoneModel.SOA_CONTACT, state.soa_contact());
            given(becomes, DnsZoneModel.DEFAULT_TTL, state.default_ttl());
            given(becomes, DnsZoneModel.NEGATIVE_TTL, state.negative_ttl());
            given(becomes, DnsZoneModel.SOA_REFRESH, state.soa_refresh());
            given(becomes, DnsZoneModel.SOA_RETRY, state.soa_retry());
            given(becomes, DnsZoneModel.SOA_EXPIRE, state.soa_expire());
            if (becomes.updateAll() != 1) {
                return false;
            }
            DnsRecordModel records = Models.get(DnsRecordModel.class);
            DnsDyndnsCredentialModel credentials = Models.get(DnsDyndnsCredentialModel.class);
            for (DnsZoneStateRecord stated : state.records()) {
                DnsRecordDto wire = stated.record();
                Row row = records.createEmptyRow();
                row.set(DnsRecordModel.ZONE_ID, zoneId);
                row.set(DnsRecordModel.NAME, wire.name());
                row.set(DnsRecordModel.TYPE, wire.type());
                row.set(DnsRecordModel.TTL, wire.ttl());
                row.set(DnsRecordModel.VALUE, wire.value());
                row.set(DnsRecordModel.DATA, DnsRecordModel.dataFor(wire.type(), wire.priority(), wire.weight(),
                    wire.port()));
                row.set(DnsRecordModel.ENABLED, wire.enabled());
                // A certificate proof the owner made itself stays machine-owned, as a forwarded one is stamped.
                String managedBy = GeneratedDnsRecords.SOURCE_ACME.equals(stated.generated_by())
                    ? DnsRecordModel.MANAGED_BY_ACME : wire.managed_by();
                if (managedBy != null && DnsRecordModel.MANAGED_BY_VALUES.contains(managedBy)) {
                    row.set(DnsRecordModel.MANAGED_BY, managedBy);
                }
                records.save(row);
                if (DynamicDnsService.isDigest(stated.dyndns_digest())) {
                    Row credential = credentials.createEmptyRow();
                    credential.set(DnsDyndnsCredentialModel.RECORD_ID, row.get(DnsRecordModel.ID));
                    credential.set(DnsDyndnsCredentialModel.TOKEN_DIGEST, stated.dyndns_digest());
                    credentials.save(credential);
                }
            }
            int ownerId = owner.get(DnsPeerModel.ID);
            DnsPeerMirrors.linkOnce(zoneId, ownerId);
            DnsZoneStateSecondary self = selfIn(state, keyName);
            for (DnsZoneStateSecondary other : state.secondaries()) {
                if (other == self) continue;
                Row peer = peerAt(other.transfer_host(), ownerId);
                if (peer == null) {
                    unlinked.add(other.name());
                } else {
                    DnsPeerMirrors.linkOnce(zoneId, peer.get(DnsPeerModel.ID));
                    linked.add(String.valueOf((Object) peer.get(DnsPeerModel.NAME)));
                }
            }
            History.event(zones, zoneId, Change.fact(HohenheimActivityAction.TAKEN_OVER,
                Map.of(HohenheimActivityAction.SERVER_KEY, String.valueOf((Object) owner.get(DnsPeerModel.NAME)))));
            DnsZoneStore.INSTANCE.reload();
            return true;
        });
    }

    /**
     * Step 4's undo: the copy answers again before the transaction and again after its commit (a reload elsewhere in
     * between may have pruned it), the rows and links this take-over wrote go, and the zone's columns are put back.
     */
    private static void undo(int zoneId, @NonNull Map<Field<?, ?>, Object> prior, @NonNull DnsZoneSnapshot copy,
                             @NonNull Set<Integer> priorLinks, @NonNull String owner) {
        DnsZoneStore.INSTANCE.putSecondarySnapshot(copy);
        DnsZoneModel zones = Models.get(DnsZoneModel.class);
        Datasource datasource = zones.getResolvedDatasource();
        datasource.inTransaction(() -> {
            Models.get(DnsRecordModel.class).find().where(DnsRecordModel.ZONE_ID.eq(zoneId)).delete();
            DnsZonePeerModel links = Models.get(DnsZonePeerModel.class);
            for (Row link : links.findAll(DnsZonePeerModel.ZONE_ID, zoneId)) {
                if (!priorLinks.contains((Integer) link.get(DnsZonePeerModel.PEER_ID))) links.delete(link);
            }
            QueryBuilder<?> back = zones.find().where(DnsZoneModel.ID.eq(zoneId));
            for (Map.Entry<Field<?, ?>, Object> column : prior.entrySet()) back.assign(column.getKey(),
                column.getValue());
            back.updateAll();
            History.event(zones, zoneId, Change.fact(HohenheimActivityAction.TAKE_OVER_UNDONE,
                Map.of(HohenheimActivityAction.SERVER_KEY, owner)));
            serveAfterCommit(datasource, copy);
            return null;
        });
    }

    /**
     * Puts a copy in the serving view once the transaction in flight commits, before the reload that commit runs: the
     * reload then finds the zone a secondary and keeps the copy, whatever another reload pruned meanwhile.
     */
    private static void serveAfterCommit(@NonNull Datasource datasource, @NonNull DnsZoneSnapshot copy) {
        datasource.afterCommit(() -> DnsZoneStore.INSTANCE.putSecondarySnapshot(copy));
        DnsZoneStore.INSTANCE.reload();
    }

    /** @return TRUE when the owner now copies the zone from this server, FALSE when it keeps it, null when unasked */
    private static @Nullable Boolean handedOver(@NonNull DnsPeerApi api, @NonNull String origin,
                                                @NonNull String keyName) {
        try {
            DnsZoneStateResponse now = api.zoneState(origin);
            return DnsZoneModel.ROLE_SECONDARY.equals(now.role()) && keyName.equals(now.primary_key());
        } catch (DnsPeerApi.PeerApiException unasked) {
            return null;
        }
    }

    /** @return the secondary of the owner's state that is this server: the one holding the key the two share */
    private static @Nullable DnsZoneStateSecondary selfIn(@NonNull DnsZoneStateResponse state,
                                                          @NonNull String keyName) {
        for (DnsZoneStateSecondary secondary : state.secondaries()) {
            if (keyName.equals(secondary.key_name())) return secondary;
        }
        return null;
    }

    /** @return the enabled peer here transferring from that address, the old owner never; null for none */
    private static @Nullable Row peerAt(@Nullable String host, int ownerId) {
        if (host == null || host.isBlank()) return null;
        for (Row peer : Models.get(DnsPeerModel.class).findAll(DnsPeerModel.ENABLED, true)) {
            String own = peer.get(DnsPeerModel.TRANSFER_HOST);
            if (!Objects.equals(peer.get(DnsPeerModel.ID), ownerId) && own != null
                    && own.trim().equalsIgnoreCase(host.trim())) {
                return peer;
            }
        }
        return null;
    }

    /** @return whether the zone is now a copy pulling from the peer, as stored */
    private static boolean pullsFrom(int zoneId, int peerId) {
        Row zone = Models.get(DnsZoneModel.class).find().noCache().where(DnsZoneModel.ID.eq(zoneId)).first();
        return zone != null && DnsPeerMirrors.pullsFrom(zone, peerId);
    }

    /** Stages the setting when the owner stated one; a setting it left empty stays as stored here. */
    private static void given(@NonNull QueryBuilder<?> update, @NonNull Field<?, ?> field, @Nullable Object value) {
        if (value != null) update.assign(field, value);
    }

    /** @return the zone as stored now, every column */
    private static @NonNull Row stored(@NonNull Row asked) {
        return Objects.requireNonNull(Models.get(DnsZoneModel.class).find().noCache()
            .where(DnsZoneModel.ID.eq(asked.get(DnsZoneModel.ID))).first(), "an asked zone is stored");
    }

    /** @return a take-over refusal in its own words */
    private static @NonNull Violations refused(@NonNull Microcopy words) {
        return Violations.ofForm(words);
    }

    private static @NonNull Microcopy words(@NonNull String key, @NonNull String zone, @NonNull String owner) {
        return HohenheimMicrocopy.VIOLATIONS.of(key).withArg("zone", zone).withArg("peer", owner);
    }
}
