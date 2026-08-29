package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;

import org.checkerframework.checker.nullness.qual.Nullable;

/** The DNS row fixtures the browser tests share: zones, records, peers and their links. */
final class DnsFixtures {

    private DnsFixtures() {
    }

    /** An enabled zone with the conventional SOA fields and no declared role. */
    static int createZone(String origin) {
        return zone(origin, null, null, false);
    }

    /** An enabled zone in the given role, secondaries naming the peer they pull from. */
    static int createZone(String origin, String role, @Nullable Integer primaryPeerId) {
        return zone(origin, role, primaryPeerId, false);
    }

    /** An enabled DNSSEC-signed zone with the conventional SOA fields and no declared role. */
    static int createSignedZone(String origin) {
        return zone(origin, null, null, true);
    }

    /**
     * The one zone writer.
     *
     * A null role leaves BOTH role columns unwritten rather than writing nulls, so the
     * role-less zone keeps the column defaults it has always been stored with.
     *
     * @return the stored zone's id
     */
    private static int zone(String origin, @Nullable String role,
                            @Nullable Integer primaryPeerId, boolean dnssec) {
        DnsZoneModel zones = Models.get(DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, origin);
        zone.set(DnsZoneModel.SOA_PRIMARY_NS, "ns1." + origin);
        zone.set(DnsZoneModel.SOA_CONTACT, "hostmaster@" + origin);
        if (role != null) {
            zone.set(DnsZoneModel.ROLE, role);
            zone.set(DnsZoneModel.PRIMARY_PEER_ID, primaryPeerId);
        }
        zone.set(DnsZoneModel.ENABLED, true);
        if (dnssec) {
            zone.set(DnsZoneModel.DNSSEC_ENABLED, true);
        }
        zones.save(zone);
        return zone.get(DnsZoneModel.ID);
    }

    /**
     * An enabled record row.
     *
     * @return the stored record's id
     */
    static int record(int zone, String name, String type, String value) {
        DnsRecordModel records = Models.get(DnsRecordModel.class);
        Row row = records.createEmptyRow();
        row.set(DnsRecordModel.ZONE_ID, zone);
        row.set(DnsRecordModel.NAME, name);
        row.set(DnsRecordModel.TYPE, type);
        row.set(DnsRecordModel.VALUE, value);
        row.set(DnsRecordModel.ENABLED, true);
        records.save(row);
        return row.get(DnsRecordModel.ID);
    }

    /**
     * An enabled record row carrying the per-type DATA payload (MX priority, SRV weight and port).
     *
     * @return the stored record's id
     */
    static int record(int zone, String name, String type, String value,
                      @Nullable Integer priority, @Nullable Integer weight, @Nullable Integer port) {
        DnsRecordModel records = Models.get(DnsRecordModel.class);
        Row row = records.createEmptyRow();
        row.set(DnsRecordModel.ZONE_ID, zone);
        row.set(DnsRecordModel.NAME, name);
        row.set(DnsRecordModel.TYPE, type);
        row.set(DnsRecordModel.VALUE, value);
        row.set(DnsRecordModel.DATA, DnsRecordModel.dataFor(type, priority, weight, port));
        row.set(DnsRecordModel.ENABLED, true);
        records.save(row);
        return row.get(DnsRecordModel.ID);
    }

    /** An ACME-managed challenge row, the one an import or a cleanup must reason about. */
    static int acmeRow(int zone) {
        DnsRecordModel records = Models.get(DnsRecordModel.class);
        Row row = records.createEmptyRow();
        row.set(DnsRecordModel.ZONE_ID, zone);
        row.set(DnsRecordModel.NAME, "_acme-challenge");
        row.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_TXT);
        row.set(DnsRecordModel.VALUE, "acme-token");
        row.set(DnsRecordModel.ENABLED, true);
        row.set(DnsRecordModel.MANAGED_BY, DnsRecordModel.MANAGED_BY_ACME);
        records.save(row);
        return row.get(DnsRecordModel.ID);
    }

    /** A nameserver peer reachable for zone transfers, carrying no TSIG material. */
    static int transferPeer(String name, String host, int port) {
        DnsPeerModel peers = Models.get(DnsPeerModel.class);
        Row peer = peers.createEmptyRow();
        peer.set(DnsPeerModel.NAME, name);
        peer.set(DnsPeerModel.TRANSFER_HOST, host);
        peer.set(DnsPeerModel.TRANSFER_PORT, port);
        peer.set(DnsPeerModel.ENABLED, true);
        peers.save(peer);
        return peer.get(DnsPeerModel.ID);
    }

    /** A transfer peer holding an hmac-sha256 TSIG key. */
    static int transferPeer(String name, String host, int port, String keyName, String secret) {
        DnsPeerModel peers = Models.get(DnsPeerModel.class);
        Row peer = peers.createEmptyRow();
        peer.set(DnsPeerModel.NAME, name);
        peer.set(DnsPeerModel.TRANSFER_HOST, host);
        peer.set(DnsPeerModel.TRANSFER_PORT, port);
        peer.set(DnsPeerModel.TSIG_KEY_NAME, keyName);
        peer.set(DnsPeerModel.TSIG_ALGORITHM, "hmac-sha256");
        peer.set(DnsPeerModel.TSIG_SECRET, secret);
        peer.set(DnsPeerModel.ENABLED, true);
        peers.save(peer);
        return peer.get(DnsPeerModel.ID);
    }

    /**
     * A peer addressed over the admin API, or a plain nameserver when the base URL is null.
     *
     * A peer with admin credentials IS a Hohenheim peer; the type is what DnsPeerApi.forPeer
     * keys on, so a credentialed peer must declare it.
     */
    static int apiPeer(String name, @Nullable String baseUrl) {
        DnsPeerModel peers = Models.get(DnsPeerModel.class);
        Row peer = peers.createEmptyRow();
        peer.set(DnsPeerModel.NAME, name);
        peer.set(DnsPeerModel.PEER_TYPE, baseUrl != null
            ? DnsPeerModel.TYPE_HOHENHEIM : DnsPeerModel.TYPE_NAMESERVER);
        peer.set(DnsPeerModel.BASE_URL, baseUrl);
        peer.set(DnsPeerModel.API_KEY, baseUrl != null ? "test-peer-key" : null);
        peer.set(DnsPeerModel.ENABLED, true);
        peers.save(peer);
        return peer.get(DnsPeerModel.ID);
    }

    /** Authorize a peer on a zone; returns the link row id. */
    static int linkZonePeer(int zoneId, int peerId) {
        DnsZonePeerModel links = Models.get(DnsZonePeerModel.class);
        Row link = links.createEmptyRow();
        link.set(DnsZonePeerModel.ZONE_ID, zoneId);
        link.set(DnsZonePeerModel.PEER_ID, peerId);
        links.save(link);
        return link.get(DnsZonePeerModel.ID);
    }
}
