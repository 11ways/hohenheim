package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.dns.DnsRecordDto;
import be.elevenways.hohenheim.dns.DnsRecordListResponse;
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
import be.elevenways.hohenheim.server.dns.InternalDnsTxtPublisher;
import be.elevenways.hohenheim.server.dns.SecondaryZoneService;
import be.elevenways.hohenheim.server.tls.DnsTxtRecord;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.Type;
import org.xbill.DNS.ZoneTransferIn;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            DnsFederationKeys.ALGORITHM, secret, "127.0.0.1", primaryPort).peer();
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

            new DnsNotifier().notifyZonePeersBlocking(zoneId,
                DnsZoneStore.INSTANCE.publishedSerial(zoneId));

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

    /**
     * A DNS-01 challenge on a zone this instance only REPLICATES, with no admin channel
     * to its primary: refused outright rather than written into the replica's row set.
     *
     * AIDEV-NOTE: the write used to land against the SECONDARY's zone id, which serves
     * nothing (the primary rebuild skips secondaries) and inflated the replica's stored
     * serial, after which the transfer check treated the replica as current.
     */
    @Test
    void acmeChallengeOnASecondaryWithoutAnAdminChannelIsRefused() throws Exception {
        String origin = "unowned.example";
        int peerId = transferPeer("unowned-office", "127.0.0.1", 1, KEY_NAME, KEY_SECRET);
        int zoneId = createZone(origin, DnsZoneModel.ROLE_SECONDARY, peerId);
        DnsZoneStore.INSTANCE.putSecondarySnapshot(
            DnsZoneStore.snapshotFromTransfer(zoneId, origin, replicaRecords(origin, 3)));
        try {
            InternalDnsTxtPublisher publisher = new InternalDnsTxtPublisher();
            String challengeName = "_acme-challenge." + origin;
            Integer serialBefore = Models.get(DnsZoneModel.class).findById(zoneId)
                .get(DnsZoneModel.SERIAL);

            // 1. The form check refuses it, naming the peer it is replicated from.
            assertThat(publisher.canPublishFor(challengeName))
                .describedAs("a replicated zone with no admin channel is not publishable")
                .isFalse();
            var refusal = publisher.refusalFor(challengeName);
            assertThat(refusal).isNotNull();
            assertThat(refusal.key()).isEqualTo(InternalDnsTxtPublisher.REFUSAL_NOT_PRIMARY);
            assertThat(refusal.peer()).isEqualTo("unowned-office");

            // 2. Publishing anyway is refused rather than silently written.
            assertThatThrownBy(() -> publisher.publish(new DnsTxtRecord(challengeName, "token")))
                .isInstanceOf(IllegalStateException.class);

            // 3. Nothing was written and the replica's serial is untouched, so the next
            //    genuine transfer is not suppressed by an inflated local serial.
            assertThat(Models.get(DnsRecordModel.class).find()
                .where(DnsRecordModel.ZONE_ID.eq(zoneId)).count())
                .describedAs("no record row was written against the replica")
                .isZero();
            assertThat((Integer) Models.get(DnsZoneModel.class).findById(zoneId)
                .get(DnsZoneModel.SERIAL)).isEqualTo(serialBefore);
        }
        finally {
            DnsZoneStore.INSTANCE.removeSecondarySnapshot(origin);
        }
    }

    /**
     * A DNS-01 challenge on a replicated zone whose primary IS a Hohenheim peer with an
     * admin key: created on the primary over the admin channel, transferred back here,
     * and removed on both ends by cleanup.
     */
    @Test
    void acmeChallengeOnASecondaryIsForwardedToTheOwningPrimary() throws Exception {
        String origin = "forward.example";
        RemotePrimary primary = new RemotePrimary(origin);
        SecondaryZoneService replication = new SecondaryZoneService(DnsZoneStore.INSTANCE);
        try {
            int peerId = hohenheimPeer("forward-office", primary.baseUrl(), primary.dnsPort());
            int zoneId = createZone(origin, DnsZoneModel.ROLE_SECONDARY, peerId);
            assertThat(replication.transfer(zoneId, true))
                .describedAs("the replica is established before the challenge").isTrue();

            InternalDnsTxtPublisher publisher =
                new InternalDnsTxtPublisher(DnsZoneStore.INSTANCE, replication);
            String challengeName = "_acme-challenge." + origin;
            DnsTxtRecord challenge = new DnsTxtRecord(challengeName, "forwarded-token");

            // 1. A peer with an admin channel makes the replicated zone publishable.
            assertThat(publisher.canPublishFor(challengeName)).isTrue();

            publisher.publish(challenge);

            // 2. The TXT landed on the PRIMARY, through the admin channel, carrying the same
            //    machine-ownership stamp a locally issued challenge gets. Unstamped it reads
            //    as hand-authored there, and the primary's zone-file import replaces exactly
            //    the unstamped rows -- it would delete the challenge the CA is about to read.
            assertThat(primary.records()).anyMatch(record -> "_acme-challenge".equals(record.name())
                && DnsRecordModel.TYPE_TXT.equals(record.type())
                && "forwarded-token".equals(record.value())
                && DnsRecordModel.MANAGED_BY_ACME.equals(record.managed_by()));

            // 3. Our own replica transferred it and now serves it -- which is what proves
            //    the CA will see it -- while our zone row still holds no local records.
            assertThat(txtValues(origin, challengeName)).contains("forwarded-token");
            assertThat(Models.get(DnsRecordModel.class).find()
                .where(DnsRecordModel.ZONE_ID.eq(zoneId)).count())
                .describedAs("a forwarded challenge writes no local row").isZero();

            // 4. Cleanup removes it on the primary and the replica follows.
            publisher.cleanup(challenge);
            assertThat(primary.records()).noneMatch(record -> "forwarded-token".equals(record.value()));
            assertThat(txtValues(origin, challengeName)).doesNotContain("forwarded-token");
        }
        finally {
            replication.stop();
            primary.stop();
            DnsZoneStore.INSTANCE.removeSecondarySnapshot(origin);
        }
    }

    /** The TXT values the serving snapshot of the origin answers for the name. */
    private static List<String> txtValues(String origin, String name) throws Exception {
        DnsZoneSnapshot snapshot = DnsZoneStore.INSTANCE.getZone(origin);
        List<Record> rrset = snapshot != null
            ? snapshot.getRrset(Name.fromString(name + "."), Type.TXT) : null;
        List<String> values = new ArrayList<>();
        for (Record record : rrset != null ? rrset : List.<Record>of()) {
            values.add(String.join("", ((TXTRecord) record).getStrings()));
        }
        return values;
    }

    /** A Hohenheim peer reachable BOTH over the admin API and for zone transfers. */
    private static int hohenheimPeer(String name, String baseUrl, int dnsPort) {
        int peerId = DnsFixtures.apiPeer(name, baseUrl);
        Row peer = Models.get(DnsPeerModel.class).findById(peerId);
        peer.set(DnsPeerModel.TRANSFER_HOST, "127.0.0.1");
        peer.set(DnsPeerModel.TRANSFER_PORT, dnsPort);
        peer.set(DnsPeerModel.TSIG_KEY_NAME, KEY_NAME);
        peer.set(DnsPeerModel.TSIG_ALGORITHM, "hmac-sha256");
        peer.set(DnsPeerModel.TSIG_SECRET, KEY_SECRET);
        Models.get(DnsPeerModel.class).save(peer);
        return peerId;
    }

    /**
     * The owning primary of a replicated zone: a detached zone store served over a real
     * loopback nameserver, plus a stub of this app's own record API that edits it. Two
     * Hohenheim instances cannot share one database, so the admin channel's SERVER half
     * is stubbed while its client half, the AXFR and the serving store stay real.
     */
    private static final class RemotePrimary {

        private final String origin;
        private final Name originName;
        private final DnsZoneStore store = DnsZoneStore.createDetached();
        private final Map<Integer, DnsRecordDto> records = new LinkedHashMap<>();
        private final DnsServer dns;
        private final HttpServer http;
        private int nextId = 1;
        private long serial = 10;

        RemotePrimary(String origin) throws Exception {
            this.origin = origin;
            this.originName = Name.fromString(origin + ".");
            add(new DnsRecordDto(nextId++, "@", DnsRecordModel.TYPE_NS, 3600,
                "ns1." + origin, null, null, null, true, null));
            rebuild();

            TSIG key = new TSIG(TSIG.HMAC_SHA256, KEY_NAME + ".", KEY_SECRET);
            Name expectedKeyName = Name.fromString(KEY_NAME + ".");
            this.dns = new DnsServer(new DnsResponder(this.store),
                new AxfrResponder(this.store,
                    (zoneId, requested) -> requested.equals(expectedKeyName) ? key : null));
            this.dns.start("127.0.0.1", 0);

            this.http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.http.createContext("/", this::handle);
            this.http.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + this.http.getAddress().getPort();
        }

        int dnsPort() {
            return this.dns.getUdpPort();
        }

        synchronized List<DnsRecordDto> records() {
            return List.copyOf(this.records.values());
        }

        void stop() {
            this.http.stop(0);
            this.dns.stop();
        }

        private synchronized void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response;
            try {
                if ("GET".equals(exchange.getRequestMethod())) {
                    response = Zenit.DRY.toJson(
                        new DnsRecordListResponse(this.origin, (int) this.serial, records()));
                }
                else if (path.endsWith("/delete")) {
                    String[] segments = path.split("/");
                    this.records.remove(Integer.valueOf(segments[segments.length - 2]));
                    rebuild();
                    response = "{}";
                }
                else {
                    Map<String, String> fields = form(body);
                    add(new DnsRecordDto(nextId++, fields.get("name"), fields.get("type"),
                        fields.get("ttl") != null ? Integer.valueOf(fields.get("ttl")) : null,
                        fields.get("value"), null, null, null, true,
                        fields.get(DnsRecordModel.MANAGED_BY.getName())));
                    rebuild();
                    response = "{}";
                }
            }
            catch (Exception e) {
                exchange.sendResponseHeaders(500, 0);
                exchange.close();
                return;
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private void add(DnsRecordDto record) {
            this.records.put(record.id(), record);
        }

        /** Recompiles the served zone, advancing the serial the way a real edit does. */
        private void rebuild() throws Exception {
            this.serial++;
            List<Record> zone = new ArrayList<>();
            zone.add(new SOARecord(this.originName, DClass.IN, 3600,
                Name.fromString("ns1." + this.origin + "."),
                Name.fromString("hostmaster." + this.origin + "."),
                this.serial, 7200, 3600, 1209600, 300));
            for (DnsRecordDto record : this.records.values()) {
                Name owner = "@".equals(record.name())
                    ? this.originName : Name.fromString(record.name(), this.originName);
                long ttl = record.ttl() != null ? record.ttl() : 3600;
                zone.add(DnsRecordModel.TYPE_TXT.equals(record.type())
                    ? new TXTRecord(owner, DClass.IN, ttl, record.value())
                    : new NSRecord(owner, DClass.IN, ttl, Name.fromString(record.value() + ".")));
            }
            this.store.injectPrimarySnapshot(
                DnsZoneStore.snapshotFromTransfer(9002, this.origin, zone));
        }

        private static Map<String, String> form(String body) {
            Map<String, String> fields = new LinkedHashMap<>();
            for (String pair : body.split("&")) {
                int split = pair.indexOf('=');
                if (split > 0) {
                    fields.put(URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
                }
            }
            return fields;
        }
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
