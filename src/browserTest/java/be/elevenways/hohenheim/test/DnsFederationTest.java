package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.dns.AxfrResponder;
import be.elevenways.hohenheim.server.dns.DnsFederationKeys;
import be.elevenways.hohenheim.server.dns.DnsResponder;
import be.elevenways.hohenheim.server.dns.DnsNotifier;
import be.elevenways.hohenheim.server.dns.DnsServer;
import be.elevenways.hohenheim.server.dns.DnsZoneSnapshot;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.SecondaryZoneService;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.TSIG;
import org.xbill.DNS.Type;
import org.xbill.DNS.ZoneTransferIn;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static be.elevenways.hohenheim.test.DnsFixtures.createZone;
import static be.elevenways.hohenheim.test.DnsFixtures.linkZonePeer;
import static be.elevenways.hohenheim.test.DnsFixtures.record;
import static be.elevenways.hohenheim.test.DnsFixtures.transferPeer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DNS federation over real sockets: primary-side AXFR with TSIG authorization,
 * NOTIFY dispatch, and full secondary replication (a second nameserver's zone
 * pulled into the serving store and answered from it).
 */
class DnsFederationTest {

    private static final String KEY_SECRET = "c2VjcmV0LXRzaWcta2V5LWZvci1ob2hlbmhlaW0tdGVzdA==";
    private static final String KEY_NAME = "xfer-key";

    private static DnsServer primaryServer;
    private static int primaryPort;
    private static int primaryZoneId;

    @BeforeAll
    static void boot() throws Exception {
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();

        // Primary zone this instance owns, plus a peer authorized to pull it.
        primaryZoneId = createZone("primary.example", DnsZoneModel.ROLE_PRIMARY, null);
        record(primaryZoneId, "@", DnsRecordModel.TYPE_NS, "ns1.primary.example");
        record(primaryZoneId, "ns1", DnsRecordModel.TYPE_A, "192.0.2.1");
        record(primaryZoneId, "www", DnsRecordModel.TYPE_A, "192.0.2.10");
        DnsZoneStore.INSTANCE.reload();

        primaryServer = new DnsServer();
        primaryServer.start("127.0.0.1", 0);
        primaryPort = primaryServer.getUdpPort();
        assertThat(primaryPort).isGreaterThan(0);
    }

    @AfterAll
    static void shutdown() {
        if (primaryServer != null) {
            primaryServer.stop();
        }
    }

    @Test
    void authorizedPeerCanAxfrThePrimaryZone() throws Exception {
        int peerId = transferPeer("secondary-peer", "127.0.0.1", primaryPort, KEY_NAME, KEY_SECRET);
        linkZonePeer(primaryZoneId, peerId);

        TSIG key = new TSIG(TSIG.HMAC_SHA256, KEY_NAME + ".", KEY_SECRET);
        ZoneTransferIn xfr = ZoneTransferIn.newAXFR(
            Name.fromString("primary.example."), "127.0.0.1", primaryPort, key);
        xfr.run();
        List<Record> records = xfr.getAXFR();

        assertThat(records).anyMatch(r -> r.getType() == Type.SOA);
        assertThat(records).anyMatch(r -> r.getType() == Type.A
            && r.getName().toString(true).equals("www.primary.example"));
    }

    /**
     * A key produced by transfer-key negotiation authorizes a real AXFR.
     *
     * AIDEV-NOTE: the point is the MATERIAL. The negotiation endpoint proves the two
     * sides store the same string; only a transfer proves that string is usable TSIG
     * material -- a secret minted in the wrong base64 alphabet passes every HTTP
     * assertion and then refuses every transfer.
     */
    @Test
    void aNegotiatedKeyAuthorizesTheTransfer() throws Exception {
        String keyName = DnsFederationKeys.keyNameFor("here", "negotiated-peer");
        String secret = DnsFederationKeys.mintSecret();
        Row peer = DnsFederationKeys.install("negotiated-peer", keyName,
            DnsFederationKeys.ALGORITHM, secret);
        linkZonePeer(primaryZoneId, peer.get(DnsPeerModel.ID));

        TSIG key = new TSIG(TSIG.HMAC_SHA256, keyName + ".", secret);
        ZoneTransferIn xfr = ZoneTransferIn.newAXFR(
            Name.fromString("primary.example."), "127.0.0.1", primaryPort, key);
        xfr.run();

        assertThat(xfr.getAXFR()).anyMatch(r -> r.getType() == Type.A
            && r.getName().toString(true).equals("www.primary.example"));
    }

    @Test
    void axfrWithTheWrongKeyIsRefused() throws Exception {
        int peerId = transferPeer("legit-peer", "127.0.0.1", primaryPort, KEY_NAME, KEY_SECRET);
        linkZonePeer(primaryZoneId, peerId);

        TSIG wrongKey = new TSIG(TSIG.HMAC_SHA256, KEY_NAME + ".",
            "d3Jvbmctc2VjcmV0LXRoYXQtd2lsbC1uZXZlci12ZXJpZnk=");
        ZoneTransferIn xfr = ZoneTransferIn.newAXFR(
            Name.fromString("primary.example."), "127.0.0.1", primaryPort, wrongKey);

        assertThatThrownBy(xfr::run).isInstanceOf(Exception.class);
    }

    @Test
    void notifyIsSentToLinkedSecondaries() throws Exception {
        try (DatagramSocket listener = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            listener.setSoTimeout(5_000);
            int notifyPort = listener.getLocalPort();

            int zoneId = createZone("notify.example", DnsZoneModel.ROLE_PRIMARY, null);
            record(zoneId, "@", DnsRecordModel.TYPE_NS, "ns1.notify.example");
            DnsZoneStore.INSTANCE.reload();
            int peerId = transferPeer("notify-peer", "127.0.0.1", notifyPort, KEY_NAME, KEY_SECRET);
            linkZonePeer(zoneId, peerId);

            new DnsNotifier().notifyZonePeersBlocking(zoneId);

            byte[] buffer = new byte[512];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            listener.receive(packet);
            byte[] data = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
            Message notify = new Message(data);

            assertThat(notify.getHeader().getOpcode()).isEqualTo(Opcode.NOTIFY);
            assertThat(notify.getQuestion().getName().toString(true)).isEqualTo("notify.example");
        }
    }

    @Test
    void secondaryZoneIsReplicatedAndServed() throws Exception {
        // A second nameserver ("the office") serving office.example from a detached store,
        // authorizing our key. This instance secondaries that zone.
        Name officeOrigin = Name.fromString("office.example.");
        List<Record> officeRecords = new ArrayList<>();
        officeRecords.add(new SOARecord(officeOrigin, DClass.IN, 3600,
            Name.fromString("ns1.office.example."), Name.fromString("hostmaster.office.example."),
            7, 7200, 3600, 1209600, 300));
        officeRecords.add(new NSRecord(officeOrigin, DClass.IN, 3600, Name.fromString("ns1.office.example.")));
        officeRecords.add(new ARecord(Name.fromString("www.office.example."), DClass.IN, 3600,
            InetAddress.getByName("198.51.100.20")));

        DnsZoneStore officeStore = DnsZoneStore.createDetached();
        DnsZoneSnapshot officeSnapshot = DnsZoneStore.snapshotFromTransfer(9001, "office.example", officeRecords);
        officeStore.injectPrimarySnapshot(officeSnapshot);

        TSIG officeKey = new TSIG(TSIG.HMAC_SHA256, KEY_NAME + ".", KEY_SECRET);
        Name expectedKeyName = Name.fromString(KEY_NAME + ".");
        AxfrResponder officeAxfr = new AxfrResponder(officeStore,
            (zoneId, requested) -> requested.equals(expectedKeyName) ? officeKey : null);
        DnsServer officeServer = new DnsServer(new DnsResponder(officeStore), officeAxfr);
        officeServer.start("127.0.0.1", 0);
        int officePort = officeServer.getUdpPort();

        try {
            int peerId = transferPeer("office", "127.0.0.1", officePort, KEY_NAME, KEY_SECRET);
            int secondaryZoneId = createZone("office.example", DnsZoneModel.ROLE_SECONDARY, peerId);

            SecondaryZoneService service = new SecondaryZoneService(DnsZoneStore.INSTANCE);
            boolean ok = service.transfer(secondaryZoneId, true);
            assertThat(ok).isTrue();

            // The replicated zone is now in the serving store...
            DnsZoneSnapshot served = DnsZoneStore.INSTANCE.getZone("office.example");
            assertThat(served).isNotNull();
            assertThat(served.getSerial()).isEqualTo(7);

            // ...and answered by this instance's own listener.
            Message response = queryUdp(primaryPort, "www.office.example", Type.A);
            assertThat(response.getSection(Section.ANSWER)).anyMatch(r -> r instanceof ARecord a
                && a.getAddress().getHostAddress().equals("198.51.100.20"));

            // The zone row records a successful transfer.
            Row zone = Models.get(DnsZoneModel.class).findById(secondaryZoneId);
            assertThat(zone.get(DnsZoneModel.TRANSFER_STATUS)).isEqualTo(DnsZoneModel.TRANSFER_OK);
            assertThat(zone.get(DnsZoneModel.SERIAL)).isEqualTo(7);
        }
        finally {
            officeServer.stop();
            DnsZoneStore.INSTANCE.removeSecondarySnapshot("office.example");
        }
    }

    @Test
    void unreachablePrimaryMarksTheSecondaryErrored() {
        // Peer points at a port nothing serves.
        int peerId = transferPeer("dead-office", "127.0.0.1", 1, KEY_NAME, KEY_SECRET);
        int zoneId = createZone("dead.example", DnsZoneModel.ROLE_SECONDARY, peerId);

        SecondaryZoneService service = new SecondaryZoneService(DnsZoneStore.INSTANCE);
        boolean ok = service.transfer(zoneId, true);
        assertThat(ok).isFalse();

        // Never-transferred zones RETRY as errors; expiry is reserved for zones
        // whose last successful transfer outlived the SOA expire window.
        Row zone = Models.get(DnsZoneModel.class).findById(zoneId);
        assertThat(zone.get(DnsZoneModel.TRANSFER_STATUS)).isEqualTo(DnsZoneModel.TRANSFER_ERROR);
        assertThat(zone.get(DnsZoneModel.ENABLED)).isTrue();
    }

    @Test
    void deletingOrDisablingASecondaryZoneStopsServingIt() throws Exception {
        int peerId = transferPeer("prune-office", "127.0.0.1", 1, KEY_NAME, KEY_SECRET);
        int zoneId = createZone("prune.example", DnsZoneModel.ROLE_SECONDARY, peerId);

        // Simulate a completed transfer: the snapshot is in the serving view.
        DnsZoneStore.INSTANCE.putSecondarySnapshot(
            DnsZoneStore.snapshotFromTransfer(zoneId, "prune.example", replicaRecords("prune.example", 3)));
        assertThat(DnsZoneStore.INSTANCE.getZone("prune.example")).isNotNull();

        // Disabling the zone prunes it from the serving view on the next reload.
        Row zone = Models.get(DnsZoneModel.class).findById(zoneId);
        zone.set(DnsZoneModel.ENABLED, false);
        Models.get(DnsZoneModel.class).save(zone);
        DnsZoneStore.INSTANCE.reload();
        assertThat(DnsZoneStore.INSTANCE.getZone("prune.example")).isNull();

        // Re-enable, re-install, then delete the row: same pruning.
        zone.set(DnsZoneModel.ENABLED, true);
        Models.get(DnsZoneModel.class).save(zone);
        DnsZoneStore.INSTANCE.putSecondarySnapshot(
            DnsZoneStore.snapshotFromTransfer(zoneId, "prune.example", replicaRecords("prune.example", 3)));
        DnsZoneStore.INSTANCE.reload();
        assertThat(DnsZoneStore.INSTANCE.getZone("prune.example")).isNotNull();

        Models.get(DnsZoneModel.class).find().where(DnsZoneModel.ID.eq(zoneId)).delete();
        DnsZoneStore.INSTANCE.reload();
        assertThat(DnsZoneStore.INSTANCE.getZone("prune.example")).isNull();
    }

    @Test
    void persistedReplicaIsServedAgainAfterRestartWithoutThePrimary() throws Exception {
        // A secondary whose primary is unreachable, but which carries the
        // replica text persisted by an earlier successful transfer.
        int peerId = transferPeer("persist-office", "127.0.0.1", 1, KEY_NAME, KEY_SECRET);
        int zoneId = createZone("persist.example", DnsZoneModel.ROLE_SECONDARY, peerId);

        StringBuilder replica = new StringBuilder();
        for (Record record : replicaRecords("persist.example", 5)) {
            replica.append(record).append('\n');
        }
        Row zone = Models.get(DnsZoneModel.class).findById(zoneId);
        zone.set(DnsZoneModel.REPLICA_RECORDS, replica.toString());
        zone.set(DnsZoneModel.LAST_TRANSFER_AT, java.time.Instant.now());
        Models.get(DnsZoneModel.class).save(zone);

        // A fresh service (as after a process restart) restores it without any AXFR.
        SecondaryZoneService service = new SecondaryZoneService(DnsZoneStore.INSTANCE);
        service.restorePersistedReplicas();
        try {
            DnsZoneSnapshot served = DnsZoneStore.INSTANCE.getZone("persist.example");
            assertThat(served).isNotNull();
            assertThat(served.getSerial()).isEqualTo(5);

            Message response = queryUdp(primaryPort, "www.persist.example", Type.A);
            assertThat(response.getSection(Section.ANSWER)).anyMatch(r -> r instanceof ARecord a
                && a.getAddress().getHostAddress().equals("198.51.100.20"));
        }
        finally {
            DnsZoneStore.INSTANCE.removeSecondarySnapshot("persist.example");
        }
    }

    @Test
    void unauthenticatedNotifyIsIgnoredWhenThePeerHasATsigKey() throws Exception {
        int peerId = transferPeer("spoof-office", "127.0.0.1", 1, KEY_NAME, KEY_SECRET);
        createZone("spoof.example", DnsZoneModel.ROLE_SECONDARY, peerId);

        SecondaryZoneService service = new SecondaryZoneService(DnsZoneStore.INSTANCE);
        Name origin = Name.fromString("spoof.example.");

        // Unsigned NOTIFY: dropped.
        Message spoofed = Message.newQuery(Record.newRecord(origin, Type.SOA, DClass.IN));
        spoofed.getHeader().setOpcode(Opcode.NOTIFY);
        byte[] spoofedWire = spoofed.toWire(Message.MAXLENGTH);
        assertThat(service.onNotify(new Message(spoofedWire), spoofedWire)).isFalse();

        // The same NOTIFY signed with the peer's TSIG key: accepted.
        Message signed = Message.newQuery(Record.newRecord(origin, Type.SOA, DClass.IN));
        signed.getHeader().setOpcode(Opcode.NOTIFY);
        new TSIG(TSIG.HMAC_SHA256, KEY_NAME + ".", KEY_SECRET).apply(signed, null);
        byte[] signedWire = signed.toWire(Message.MAXLENGTH);
        assertThat(service.onNotify(new Message(signedWire), signedWire)).isTrue();
    }

    /** SOA + NS + www A record set for a synthetic replicated zone. */
    private static List<Record> replicaRecords(String origin, long serial) throws Exception {
        Name originName = Name.fromString(origin + ".");
        List<Record> records = new ArrayList<>();
        records.add(new SOARecord(originName, DClass.IN, 3600,
            Name.fromString("ns1." + origin + "."), Name.fromString("hostmaster." + origin + "."),
            serial, 7200, 3600, 1209600, 300));
        records.add(new NSRecord(originName, DClass.IN, 3600, Name.fromString("ns1." + origin + ".")));
        records.add(new ARecord(Name.fromString("www." + origin + "."), DClass.IN, 3600,
            InetAddress.getByName("198.51.100.20")));
        return records;
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static Message queryUdp(int port, String name, int type) throws Exception {
        Message query = Message.newQuery(Record.newRecord(Name.fromString(name + "."), type, DClass.IN));
        byte[] wire = query.toWire(4096);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(5_000);
            socket.send(new DatagramPacket(wire, wire.length, InetAddress.getByName("127.0.0.1"), port));
            byte[] buffer = new byte[4096];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            byte[] data = new byte[response.getLength()];
            System.arraycopy(response.getData(), 0, data, 0, response.getLength());
            return new Message(data);
        }
    }
}
