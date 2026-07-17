package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.server.tls.DnsTxtPublisher;
import be.elevenways.hohenheim.server.tls.DnsTxtRecord;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.xbill.DNS.Name;

import java.util.List;

/**
 * First-party ACME DNS-01 publisher: writes the TXT value into a hosted zone
 * and swaps the serving snapshot, so wildcard certificates renew without
 * provider credentials or a shell hook. Only the exact published value is
 * removed on cleanup, so concurrent orders for the same name coexist.
 */
public final class InternalDnsTxtPublisher implements DnsTxtPublisher {

    /** TXT values live only for the duration of one ACME order; keep caches short. */
    private static final int ACME_TXT_TTL = 60;

    /** Bounded wait for secondaries to serve a freshly published challenge. */
    private static final long PROPAGATION_TIMEOUT_MS = 60_000;
    private static final long PROPAGATION_POLL_MS = 2_000;

    private final DnsZoneStore store;

    public InternalDnsTxtPublisher() {
        this(DnsZoneStore.INSTANCE);
    }

    public InternalDnsTxtPublisher(@NonNull DnsZoneStore store) {
        this.store = store;
    }

    @Override
    public @NonNull String id() {
        return CertificateModel.DNS_PUBLISHER_INTERNAL;
    }

    /** The snapshot swap is synchronous; the record serves before publish() returns. */
    @Override
    public boolean servesImmediately() {
        return true;
    }

    /** @return true when an enabled hosted zone contains the fqdn */
    public boolean canPublishFor(@NonNull String fqdn) {
        return this.store.findZoneFor(stripDot(fqdn)) != null;
    }

    /** @return true when at least one enabled zone is hosted */
    public boolean hasZones() {
        return !this.store.zones().isEmpty();
    }

    @Override
    public void publish(@NonNull DnsTxtRecord record) throws Exception {
        DnsZoneSnapshot zone = this.requireZone(record.name());
        String owner = this.relativeOwner(zone, record.name());

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

    @Override
    public void cleanup(@NonNull DnsTxtRecord record) throws Exception {
        DnsZoneSnapshot zone = this.requireZone(record.name());
        String owner = this.relativeOwner(zone, record.name());

        Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zone.getZoneId()))
            .and(DnsRecordModel.NAME.eq(owner))
            .and(DnsRecordModel.TYPE.eq(DnsRecordModel.TYPE_TXT))
            .and(DnsRecordModel.VALUE.eq(record.value()))
            .and(DnsRecordModel.MANAGED_BY.eq(DnsRecordModel.MANAGED_BY_ACME))
            .delete();

        this.store.bumpSerialAndReload(zone.getZoneId());
    }

    private @NonNull DnsZoneSnapshot requireZone(@NonNull String recordName) {
        String fqdn = stripDot(recordName);
        DnsZoneSnapshot zone = this.store.findZoneFor(fqdn);
        if (zone == null) {
            throw new IllegalStateException("No enabled hosted DNS zone contains " + fqdn);
        }
        return zone;
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
        String value = name != null ? name.trim().toLowerCase() : "";
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
