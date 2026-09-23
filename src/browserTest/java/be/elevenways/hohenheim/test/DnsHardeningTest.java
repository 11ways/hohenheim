package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsRateLimiter;
import be.elevenways.hohenheim.server.dns.DnsResponder;
import be.elevenways.hohenheim.server.dns.DnsServer;
import be.elevenways.hohenheim.server.dns.DnsTsig;
import be.elevenways.hohenheim.server.dns.DnsZoneFiles;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.SecondaryZoneService;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.TSIG;
import org.xbill.DNS.Type;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static be.elevenways.hohenheim.test.DnsFixtures.createZone;
import static be.elevenways.hohenheim.test.DnsFixtures.record;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The authoritative DNS server's exhaustion and integrity guards: wildcard answers share one
 * rate-limit bucket, a TCP client can neither hold every slot nor hold one forever, an
 * unknown TSIG algorithm fails closed, a zone import is validated and atomic, and no record
 * row can ever land in a secondary (replica) zone.
 */
class DnsHardeningTest {

    private static final String ORIGIN = "dns-hardening.example";

    private static DnsServer server;
    private static int port;
    private static int zoneId;

    @BeforeAll
    static void boot() throws Exception {
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();

        zoneId = createZone(ORIGIN);
        record(zoneId, "www", DnsRecordModel.TYPE_A, "192.0.2.10");
        record(zoneId, "*", DnsRecordModel.TYPE_A, "192.0.2.99");
        DnsZoneStore.INSTANCE.reload();

        server = new DnsServer();
        server.start("127.0.0.1", 0);
        port = server.getUdpPort();
    }

    @AfterAll
    static void shutdown() {
        if (server != null) {
            server.stop();
        }
    }

    private static Message query(String name, int type) throws Exception {
        return Message.newQuery(Record.newRecord(Name.fromString(name + "."), type, DClass.IN));
    }

    /** One query over an ALREADY OPEN connection, so a held slot proves it still serves. */
    private static Message exchange(Socket socket, String name) throws Exception {
        byte[] wire = query(name, Type.A).toWire(65535);
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.writeShort(wire.length);
        out.write(wire);
        out.flush();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        byte[] data = new byte[in.readUnsignedShort()];
        in.readFully(data);
        return new Message(data);
    }

    /** @return true when the server closed the connection without a word */
    private static boolean closedByServer(Socket socket) throws IOException {
        socket.setSoTimeout(10_000);
        try {
            return socket.getInputStream().read() == -1;
        } catch (SocketTimeoutException stillOpen) {
            return false;
        } catch (IOException reset) {
            return true;
        }
    }

    @Test
    void wildcardAnswersShareOneRateLimitBucketPerWildcardOwner() throws Exception {
        DnsResponder responder = new DnsResponder(DnsZoneStore.INSTANCE);

        // 1. Two random labels are both answered by the wildcard, and the answer says so.
        Message q1 = query("a1b2c3." + ORIGIN, Type.A);
        Message q2 = query("z9y8x7." + ORIGIN, Type.A);
        DnsResponder.Answer a1 = responder.answer(q1);
        DnsResponder.Answer a2 = responder.answer(q2);
        assertThat(a1).as("step 1: the wildcard answers").isNotNull();
        assertThat(a1.response().getHeader().getRcode()).as("step 1: a positive answer")
            .isEqualTo(Rcode.NOERROR);
        assertThat(a1.source()).as("step 1: the answer's source is the wildcard owner")
            .isEqualTo(Name.fromString("*." + ORIGIN + "."));

        // 2. So a random-subdomain flood lands in ONE bucket, not one per label.
        assertThat(DnsRateLimiter.keyFor(q1, a1.response(), a1.source()))
            .as("step 2: wildcard answers share the wildcard owner's bucket")
            .isEqualTo(DnsRateLimiter.keyFor(q2, a2.response(), a2.source()));

        // 3. A name with its own node keys on itself, as before.
        Message own = query("www." + ORIGIN, Type.A);
        DnsResponder.Answer ownAnswer = responder.answer(own);
        assertThat(ownAnswer.source()).as("step 3: an exact node is its own source")
            .isEqualTo(Name.fromString("www." + ORIGIN + "."));
        assertThat(DnsRateLimiter.keyFor(own, ownAnswer.response(), ownAnswer.source()))
            .as("step 3: and keeps a bucket of its own")
            .isNotEqualTo(DnsRateLimiter.keyFor(q1, a1.response(), a1.source()));
    }

    @Test
    void aTcpClientCanNeitherHoldEverySlotNorHoldOneForever() throws Exception {
        List<Socket> held = new ArrayList<>();
        try {
            // 1. One client fills its OWN allowance, and every one of those slots serves.
            for (int i = 0; i < DnsServer.TCP_MAX_CONNECTIONS_PER_CLIENT; i++) {
                Socket socket = new Socket("127.0.0.1", port);
                socket.setSoTimeout(5_000);
                held.add(socket);
                assertThat(exchange(socket, "www." + ORIGIN).getHeader().getRcode())
                    .as("step 1: held connection %s answers", i).isEqualTo(Rcode.NOERROR);
            }

            // 2. One more from the same address is closed at once instead of queued.
            try (Socket extra = new Socket("127.0.0.1", port)) {
                assertThat(closedByServer(extra))
                    .as("step 2: the per-client cap refuses the extra connection").isTrue();
            }

            // 3. The held ones still work: the cap refused a newcomer, it evicted nobody.
            assertThat(exchange(held.get(0), "www." + ORIGIN).getHeader().getRcode())
                .as("step 3: a held connection still answers").isEqualTo(Rcode.NOERROR);
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
        }

        // 4. Released slots come back (the server sees each close asynchronously).
        boolean served = false;
        for (int attempt = 0; attempt < 50 && !served; attempt++) {
            try (Socket again = new Socket("127.0.0.1", port)) {
                again.setSoTimeout(2_000);
                served = exchange(again, "www." + ORIGIN).getHeader().getRcode() == Rcode.NOERROR;
            } catch (IOException notYet) {
                Thread.sleep(100);
            }
        }
        assertThat(served).as("step 4: a closed connection gives its slot back").isTrue();

        // 5. A connection that never finishes is closed at the ABSOLUTE deadline, long before
        //    the 30s idle timeout that a byte-dribbling client could re-arm forever.
        server.setTcpConnectionDeadlineMillis(1_000);
        try (Socket lingering = new Socket("127.0.0.1", port)) {
            long started = System.nanoTime();
            lingering.getOutputStream().write(0);
            lingering.getOutputStream().flush();
            assertThat(closedByServer(lingering))
                .as("step 5: the deadline closes a stalled connection").isTrue();
            assertThat((System.nanoTime() - started) / 1_000_000L)
                .as("step 5: at the deadline, not at the idle timeout").isLessThan(9_000L);
        } finally {
            server.setTcpConnectionDeadlineMillis(120_000);
        }
    }

    @Test
    void anUnknownTsigAlgorithmFailsClosedInsteadOfBeingKeyedAsSha256() throws Exception {
        // 1. Every stored name production holds still maps, case and root dot tolerated.
        assertThat(DnsTsig.algorithmName("hmac-sha256")).isEqualTo(TSIG.HMAC_SHA256);
        assertThat(DnsTsig.algorithmName(" HMAC-SHA512 ")).isEqualTo(TSIG.HMAC_SHA512);
        assertThat(DnsTsig.algorithmName("hmac-sha1.")).isEqualTo(TSIG.HMAC_SHA1);
        assertThat(DnsTsig.algorithmName(null)).as("step 1: no algorithm is the default")
            .isEqualTo(TSIG.HMAC_SHA256);
        assertThat(DnsTsig.ALGORITHMS).as("step 1: the accepted set derives from the enum")
            .containsExactlyInAnyOrder("hmac-sha256", "hmac-sha512", "hmac-sha384",
                "hmac-sha224", "hmac-sha1");

        // 2. An unknown one is refused, where it used to be silently keyed as SHA-256.
        assertThatThrownBy(() -> DnsTsig.algorithmName("hmac-md5"))
            .as("step 2: an unknown algorithm is refused").isInstanceOf(IllegalArgumentException.class);
        assertThat(DnsTsig.isSupportedAlgorithm("hmac-md5")).isFalse();

        // 3. A replica whose primary peer stores such an algorithm IGNORES a NOTIFY signed with
        //    the SHA-256 key the old fallback would have accepted.
        String secret = Base64.getEncoder().encodeToString(
            "hardening-notify-secret-32bytes!".getBytes(StandardCharsets.UTF_8));
        DnsPeerModel peers = Models.get(DnsPeerModel.class);
        Row peer = peers.createEmptyRow();
        peer.set(DnsPeerModel.NAME, "hardening-primary");
        peer.set(DnsPeerModel.TRANSFER_HOST, "127.0.0.1");
        peer.set(DnsPeerModel.TRANSFER_PORT, 1);
        peer.set(DnsPeerModel.TSIG_KEY_NAME, "hardening-key");
        peer.set(DnsPeerModel.TSIG_ALGORITHM, "hmac-md5");
        peer.set(DnsPeerModel.TSIG_SECRET, secret);
        peer.set(DnsPeerModel.ENABLED, true);
        peers.save(peer);
        String replica = "notify-hardening.example";
        createZone(replica, DnsZoneModel.ROLE_SECONDARY, peer.get(DnsPeerModel.ID));

        Message notify = query(replica, Type.SOA);
        notify.getHeader().setOpcode(Opcode.NOTIFY);
        new TSIG(TSIG.HMAC_SHA256, Name.fromString("hardening-key."), secret).apply(notify, null);
        byte[] wire = notify.toWire(65535);
        SecondaryZoneService replication = new SecondaryZoneService(DnsZoneStore.INSTANCE);
        try {
            assertThat(replication.onNotify(new Message(wire), wire))
                .as("step 3: an unusable key authorizes no NOTIFY").isFalse();
        } finally {
            replication.stop();
        }
    }

    @Test
    void aZoneImportIsValidatedByTheCodecAndReplacesAllOrNothing() throws Exception {
        String origin = "import-hardening.example";
        int importZone = createZone(origin);
        record(importZone, "kept", DnsRecordModel.TYPE_A, "192.0.2.20");
        DnsZoneStore.INSTANCE.reload();
        Row zone = Models.get(DnsZoneModel.class).findById(importZone);
        Integer serialBefore = zone.get(DnsZoneModel.SERIAL);

        // 1. One row the codec refuses (a TTL beyond its ceiling) refuses the WHOLE import,
        //    naming the codec's own reason.
        String bad = "$ORIGIN " + origin + ".\n"
            + "good 300 IN A 192.0.2.21\n"
            + "bad 900000 IN A 192.0.2.22\n";
        Violations refused = catchThrowableOfType(
            () -> DnsZoneFiles.importText(zone, bad, DnsZoneFiles.ApexNsPolicy.KEEP_FILE),
            Violations.class);
        assertThat((Throwable) refused).as("step 1: an invalid row refuses the import").isNotNull();
        assertThat(refused.all().get(0).message().key()).as("step 1: with the codec's reason")
            .isEqualTo("dns_ttl_range");

        // 2. Nothing moved: the old rows are all there, the valid new one never landed, and
        //    the serial did not bump.
        DnsRecordModel records = Models.get(DnsRecordModel.class);
        assertThat(records.find().where(DnsRecordModel.ZONE_ID.eq(importZone))
            .where(DnsRecordModel.NAME.eq("kept")).first())
            .as("step 2: the existing row survived the refused import").isNotNull();
        assertThat(records.find().where(DnsRecordModel.ZONE_ID.eq(importZone))
            .where(DnsRecordModel.NAME.eq("good")).first())
            .as("step 2: no row of the refused import landed").isNull();
        assertThat((Integer) Models.get(DnsZoneModel.class).findById(importZone).get(DnsZoneModel.SERIAL))
            .as("step 2: the serial did not move").isEqualTo(serialBefore);

        // 3. A valid file replaces the operator rows in one go.
        String good = "$ORIGIN " + origin + ".\n" + "good 300 IN A 192.0.2.21\n";
        assertThatCode(() -> DnsZoneFiles.importText(zone, good, DnsZoneFiles.ApexNsPolicy.KEEP_FILE))
            .as("step 3: a valid import goes through").doesNotThrowAnyException();
        assertThat(records.find().where(DnsRecordModel.ZONE_ID.eq(importZone))
            .where(DnsRecordModel.NAME.eq("kept")).first())
            .as("step 3: the replaced row is gone").isNull();
        assertThat(records.find().where(DnsRecordModel.ZONE_ID.eq(importZone))
            .where(DnsRecordModel.NAME.eq("good")).first())
            .as("step 3: the imported row landed").isNotNull();
    }

    @Test
    void aRecordRowCanNeverBeWrittenIntoASecondaryZone() throws Exception {
        DnsRecordModel records = Models.get(DnsRecordModel.class);

        // 1. A create against a replica's zone id is refused for every writer.
        int replica = createZone("replica-rows.example", DnsZoneModel.ROLE_SECONDARY, null);
        Violations refused = catchThrowableOfType(
            () -> record(replica, "www", DnsRecordModel.TYPE_A, "192.0.2.30"), Violations.class);
        assertThat((Throwable) refused).as("step 1: a row in a replica zone is refused").isNotNull();
        assertThat(refused.all().get(0).message().key())
            .as("step 1: as a replica, whose rows come from its primary")
            .isEqualTo("import_secondary_zone");
        assertThat(records.find().where(DnsRecordModel.ZONE_ID.eq(replica)).first())
            .as("step 1: and nothing was stored").isNull();

        // 2. A row written while the zone was primary cannot be edited once it replicates.
        int flipped = createZone("flipped-rows.example");
        int rowId = record(flipped, "www", DnsRecordModel.TYPE_A, "192.0.2.31");
        Models.get(DnsZoneModel.class).find().where(DnsZoneModel.ID.eq(flipped))
            .assign(DnsZoneModel.ROLE, DnsZoneModel.ROLE_SECONDARY).updateAll();
        assertThatThrownBy(() -> {
            Row row = records.findById(rowId);
            row.set(DnsRecordModel.VALUE, "192.0.2.32");
            records.save(row);
        }).as("step 2: an edit inside a replica is refused").isInstanceOf(Violations.class);

        // 3. Removing it stays possible: clearing a stale row never publishes anything.
        assertThatCode(() -> records.delete(records.findById(rowId)))
            .as("step 3: a stale row can still be removed").doesNotThrowAnyException();
    }
}
