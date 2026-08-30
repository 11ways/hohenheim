package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.dns.DnsRecordDto;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.tls.DnsTxtPublisher;
import be.elevenways.hohenheim.server.tls.DnsTxtRecord;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.Type;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * First-party ACME DNS-01 publisher: writes the TXT value into a zone this
 * controller is PRIMARY for, or forwards it to the owning primary over the peer
 * admin channel when the zone is replicated here. Only the exact published value
 * is removed on cleanup, so concurrent orders for the same name coexist.
 */
public final class InternalDnsTxtPublisher implements DnsTxtPublisher {

    /** No enabled zone here contains the name at all. */
    public static final String REFUSAL_NOT_HOSTED = "zone_not_hosted";

    /** The zone is replicated here and no admin channel to its primary is configured. */
    public static final String REFUSAL_NOT_PRIMARY = "zone_not_primary";

    /** TXT values live only for the duration of one ACME order; keep caches short. */
    private static final int ACME_TXT_TTL = 60;

    /** Bounded wait for secondaries to serve a freshly published challenge. */
    private static final long PROPAGATION_TIMEOUT_MS = 60_000;
    private static final long PROPAGATION_POLL_MS = 2_000;

    private final DnsZoneStore store;
    private final @Nullable SecondaryZoneService replication;

    public InternalDnsTxtPublisher() {
        this(DnsZoneStore.INSTANCE, null);
    }

    public InternalDnsTxtPublisher(@NonNull DnsZoneStore store) {
        this(store, null);
    }

    public InternalDnsTxtPublisher(@NonNull DnsZoneStore store,
                                   @Nullable SecondaryZoneService replication) {
        this.store = store;
        this.replication = replication;
    }

    /** Why a name cannot be published here; {@code peer} names the owning primary. */
    public record Refusal(@NonNull String key, @Nullable String peer) {}

    @Override
    public @NonNull String id() {
        return CertificateModel.DNS_PUBLISHER_INTERNAL;
    }

    /** The record serves before publish() returns: locally by snapshot swap, remotely by transfer. */
    @Override
    public boolean servesImmediately() {
        return true;
    }

    /** @return true when the name lands in a zone this controller may write */
    public boolean canPublishFor(@NonNull String fqdn) {
        return refusalFor(fqdn) == null;
    }

    /**
     * @return why this name cannot be published, or null when it can
     *
     * AIDEV-NOTE: this used to be a bare {@code findZoneFor != null}, which merges
     * primaries AND secondaries: a DNS-01 order on a zone this controller only
     * REPLICATES passed the form check, wrote a record row against the replica's zone
     * id (published by nothing, overwritten by the next AXFR) and inflated the replica's
     * stored serial, after which SecondaryZoneService treated it as current and skipped
     * genuine transfers. A replicated zone is publishable only by FORWARDING to its
     * primary, so the peer's admin channel is part of the answer, not an afterthought.
     */
    public @Nullable Refusal refusalFor(@NonNull String fqdn) {
        String name = stripDot(fqdn);
        if (this.store.findPrimaryZoneFor(name) != null) {
            return null;
        }
        DnsZoneSnapshot zone = this.store.findZoneFor(name);
        if (zone == null) {
            return new Refusal(REFUSAL_NOT_HOSTED, null);
        }
        Row peer = owningPeer(zone);
        if (DnsPeerApi.forPeer(peer) != null) {
            return null;
        }
        return new Refusal(REFUSAL_NOT_PRIMARY, peerLabel(zone, peer));
    }

    /** @return true when at least one zone here can carry a challenge */
    public boolean hasZones() {
        for (DnsZoneSnapshot zone : this.store.zones()) {
            if (this.store.isPrimary(zone.getOriginString())
                    || DnsPeerApi.forPeer(owningPeer(zone)) != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void publish(@NonNull DnsTxtRecord record) throws Exception {
        Target target = requireTarget(record.name());
        DnsZoneSnapshot zone = target.zone();
        String owner = this.relativeOwner(zone, record.name());

        DnsPeerApi api = target.api();
        if (api != null) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put(DnsRecordModel.NAME.getName(), owner);
            fields.put(DnsRecordModel.TYPE.getName(), DnsRecordModel.TYPE_TXT);
            fields.put(DnsRecordModel.TTL.getName(), String.valueOf(ACME_TXT_TTL));
            fields.put(DnsRecordModel.VALUE.getName(), record.value());
            fields.put(DnsRecordModel.ENABLED.getName(), "true");
            api.createRecord(zone.getOriginString(), fields);
            awaitReplica(zone, record);
            return;
        }

        DnsRecordModel model = Models.get(DnsRecordModel.class);
        Row row = model.createEmptyRow();
        row.set(DnsRecordModel.ZONE_ID, zone.getZoneId());
        row.set(DnsRecordModel.NAME, owner);
        row.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_TXT);
        row.set(DnsRecordModel.TTL, ACME_TXT_TTL);
        row.set(DnsRecordModel.VALUE, record.value());
        row.set(DnsRecordModel.ENABLED, true);
        row.set(DnsRecordModel.MANAGED_BY, DnsRecordModel.MANAGED_BY_ACME);
        model.save(row);

        this.store.bumpSerialAndReload(zone.getZoneId());
        awaitSecondaries(zone.getOriginString());
    }

    @Override
    public void cleanup(@NonNull DnsTxtRecord record) throws Exception {
        Target target = requireTarget(record.name());
        DnsZoneSnapshot zone = target.zone();
        String owner = this.relativeOwner(zone, record.name());

        DnsPeerApi api = target.api();
        if (api != null) {
            String origin = zone.getOriginString();
            for (DnsRecordDto remote : api.listRecords(origin)) {
                if (owner.equals(remote.name())
                        && DnsRecordModel.TYPE_TXT.equalsIgnoreCase(remote.type())
                        && record.value().equals(remote.value())) {
                    api.deleteRecord(origin, remote.id());
                }
            }
            refreshReplica(zone);
            return;
        }

        Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zone.getZoneId()))
            .and(DnsRecordModel.NAME.eq(owner))
            .and(DnsRecordModel.TYPE.eq(DnsRecordModel.TYPE_TXT))
            .and(DnsRecordModel.VALUE.eq(record.value()))
            .and(DnsRecordModel.MANAGED_BY.eq(DnsRecordModel.MANAGED_BY_ACME))
            .delete();

        this.store.bumpSerialAndReload(zone.getZoneId());
    }

    /** Where a challenge is written: this controller's own zone, or a peer's over the admin channel. */
    private record Target(@NonNull DnsZoneSnapshot zone, @Nullable DnsPeerApi api) {}

    private @NonNull Target requireTarget(@NonNull String recordName) {
        String fqdn = stripDot(recordName);
        DnsZoneSnapshot primary = this.store.findPrimaryZoneFor(fqdn);
        if (primary != null) {
            return new Target(primary, null);
        }
        DnsZoneSnapshot zone = this.store.findZoneFor(fqdn);
        DnsPeerApi api = zone != null ? DnsPeerApi.forPeer(owningPeer(zone)) : null;
        if (zone == null || api == null) {
            Refusal refusal = refusalFor(fqdn);
            throw new IllegalStateException(refusal != null && REFUSAL_NOT_PRIMARY.equals(refusal.key())
                ? "Zone containing " + fqdn + " is replicated from " + refusal.peer()
                    + "; no admin channel to that primary is configured"
                : "No enabled hosted DNS zone contains " + fqdn);
        }
        return new Target(zone, api);
    }

    /**
     * Blocks until this controller's own replica of a forwarded zone serves the challenge
     * value, transferring it out of the primary rather than waiting for a NOTIFY; the
     * transfer is what proves the record left the primary and will reach the CA. The local
     * serial is never bumped from this path -- a replica's serial belongs to its primary.
     */
    private void awaitReplica(@NonNull DnsZoneSnapshot zone, @NonNull DnsTxtRecord record)
            throws Exception {
        String origin = zone.getOriginString();
        Name name = Name.fromString(stripDot(record.name()) + ".");
        long deadline = System.currentTimeMillis() + PROPAGATION_TIMEOUT_MS;
        while (true) {
            refreshReplica(zone);
            if (servesTxt(origin, name, record.value())) {
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            Thread.sleep(PROPAGATION_POLL_MS);
        }
        Blast.log("ACME: replica of", origin, "did not serve the challenge for",
            record.name(), "within the propagation window");
    }

    /** Pulls the forwarded zone again so the local replica reflects the edit just made. */
    private void refreshReplica(@NonNull DnsZoneSnapshot zone) {
        SecondaryZoneService service = replication();
        if (service != null) {
            service.transfer(zone.getZoneId(), true);
        }
    }

    /** @return true when the serving snapshot of the origin carries the exact TXT value */
    private boolean servesTxt(@NonNull String origin, @NonNull Name name, @NonNull String value) {
        DnsZoneSnapshot serving = this.store.getZone(origin);
        List<Record> rrset = serving != null ? serving.getRrset(name, Type.TXT) : null;
        if (rrset == null) {
            return false;
        }
        for (Record entry : rrset) {
            StringBuilder text = new StringBuilder();
            for (Object part : ((TXTRecord) entry).getStrings()) {
                text.append(part);
            }
            if (value.equals(text.toString())) {
                return true;
            }
        }
        return false;
    }

    private @Nullable SecondaryZoneService replication() {
        if (this.replication != null) {
            return this.replication;
        }
        DnsServer server = ServerMain.getDnsServer();
        return server != null ? server.getSecondaryService() : null;
    }

    /** @return the peer row a replicated zone pulls from, or null (a primary has none) */
    private static @Nullable Row owningPeer(@NonNull DnsZoneSnapshot zone) {
        Row row = Models.get(DnsZoneModel.class).findById(zone.getZoneId());
        Integer peerId = row != null ? row.get(DnsZoneModel.PRIMARY_PEER_ID) : null;
        return peerId != null ? Models.get(DnsPeerModel.class).findById(peerId) : null;
    }

    /** Names the owning primary for an operator: the peer's name, else the zone's SOA host. */
    private static @NonNull String peerLabel(@NonNull DnsZoneSnapshot zone, @Nullable Row peer) {
        String name = peer != null ? peer.get(DnsPeerModel.NAME) : null;
        return name != null && !name.isBlank() ? name : zone.getSoa().getHost().toString(true);
    }

    /**
     * Blocks until every secondary of the zone serves the bumped serial, so the
     * CA (which queries the delegated secondaries, not this primary) sees the TXT
     * before validation. Best-effort with a bounded wait; a lagging secondary
     * just means the CA may retry.
     */
    private void awaitSecondaries(@NonNull String originString) {
        DnsZoneSnapshot current = this.store.getZone(originString);
        if (current == null) {
            return;
        }
        List<Row> links = Models.get(DnsZonePeerModel.class).findByZoneId(current.getZoneId());
        if (links.isEmpty()) {
            return; // no secondaries: the local snapshot already serves it
        }

        long requiredSerial = current.getSerial();
        Name origin;
        try {
            origin = Name.fromString(originString + ".");
        }
        catch (Exception e) {
            return;
        }

        DnsPeerModel peerModel = Models.get(DnsPeerModel.class);
        long deadline = System.currentTimeMillis() + PROPAGATION_TIMEOUT_MS;
        for (Row link : links) {
            Integer peerId = link.get(DnsZonePeerModel.PEER_ID);
            Row peer = peerId != null ? peerModel.findById(peerId) : null;
            if (peer == null || !Boolean.TRUE.equals(peer.get(DnsPeerModel.ENABLED))) {
                continue;
            }
            String host = peer.get(DnsPeerModel.TRANSFER_HOST);
            if (host == null || host.isBlank()) {
                continue;
            }
            Integer port = peer.get(DnsPeerModel.TRANSFER_PORT);
            awaitPeerSerial(host.trim(), port != null ? port : 53, origin, requiredSerial, deadline);
        }
    }

    private static void awaitPeerSerial(@NonNull String host, int port, @NonNull Name origin,
                                        long requiredSerial, long deadline) {
        while (System.currentTimeMillis() < deadline) {
            Long serial = DnsSoaProbe.serial(host, port, origin);
            if (serial != null && DnsSoaProbe.serialReached(serial, requiredSerial)) {
                return;
            }
            try {
                Thread.sleep(PROPAGATION_POLL_MS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Blast.log("ACME: secondary", host + ":" + port, "did not reach serial", requiredSerial,
            "for", origin.toString(true), "within the propagation window");
    }

    private @NonNull String relativeOwner(@NonNull DnsZoneSnapshot zone, @NonNull String recordName) {
        String owner = DnsNames.relative(zone.getOriginString(), stripDot(recordName));
        if (owner == null) {
            throw new IllegalStateException("Record name " + recordName
                + " left zone " + zone.getOriginString());
        }
        return owner;
    }

    private static @NonNull String stripDot(@Nullable String name) {
        String value = name != null ? name.trim().toLowerCase(Locale.ROOT) : "";
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
