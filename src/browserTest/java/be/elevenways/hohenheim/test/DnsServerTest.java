package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.dns.DnsServer;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.InternalDnsTxtPublisher;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.tls.DnsTxtRecord;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.OPTRecord;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.Type;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Protocol conformance for the authoritative DNS server, exercised over real
 * UDP and TCP sockets: AA answers, NXDOMAIN vs NODATA with SOA authority,
 * empty non-terminals, wildcard synthesis, CNAME chasing, referrals, EDNS
 * truncation, refusal posture, and the internal ACME TXT publisher.
 */
class DnsServerTest {

    private static final String ORIGIN = "dns-test.example";

    private static DnsServer server;
    private static int port;
    private static int zoneId;

    @BeforeAll
    static void boot() throws Exception {
        SiteTypes.boot();
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();

        zoneId = createZone(ORIGIN);
        record(zoneId, "@", DnsRecordModel.TYPE_NS, "ns1." + ORIGIN, null, null, null);
        record(zoneId, "ns1", DnsRecordModel.TYPE_A, "192.0.2.1", null, null, null);
        record(zoneId, "www", DnsRecordModel.TYPE_A, "192.0.2.10", null, null, null);
        record(zoneId, "www", DnsRecordModel.TYPE_A, "192.0.2.11", null, null, null);
        record(zoneId, "www", DnsRecordModel.TYPE_AAAA, "2001:db8::10", null, null, null);
        record(zoneId, "alias", DnsRecordModel.TYPE_CNAME, "www." + ORIGIN, null, null, null);
        record(zoneId, "ext", DnsRecordModel.TYPE_CNAME, "elsewhere.invalid", null, null, null);
        record(zoneId, "*", DnsRecordModel.TYPE_A, "192.0.2.99", null, null, null);
        record(zoneId, "deep.a.b", DnsRecordModel.TYPE_A, "192.0.2.50", null, null, null);
        record(zoneId, "mail", DnsRecordModel.TYPE_MX, "www." + ORIGIN, 10, null, null);
        record(zoneId, "txt", DnsRecordModel.TYPE_TXT, "hello world", null, null, null);
        record(zoneId, "sub", DnsRecordModel.TYPE_NS, "ns.other.example", null, null, null);
        String filler = "x".repeat(200);
        record(zoneId, "big", DnsRecordModel.TYPE_TXT, filler + "1", null, null, null);
        record(zoneId, "big", DnsRecordModel.TYPE_TXT, filler + "2", null, null, null);
        record(zoneId, "big", DnsRecordModel.TYPE_TXT, filler + "3", null, null, null);
        DnsZoneStore.INSTANCE.reload();

        server = new DnsServer();
        server.start("127.0.0.1", 0);
        port = server.getUdpPort();
        assertThat(port).isGreaterThan(0);
    }

    @AfterAll
    static void shutdown() {
        if (server != null) {
            server.stop();
        }
    }

    private static int createZone(String origin) {
        DnsZoneModel zones = Models.get(DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, origin);
        zone.set(DnsZoneModel.SOA_PRIMARY_NS, "ns1." + origin);
        zone.set(DnsZoneModel.SOA_CONTACT, "hostmaster@" + origin);
        zone.set(DnsZoneModel.ENABLED, true);
        zones.save(zone);
        return zone.get(DnsZoneModel.ID);
    }

    private static void record(int zone, String name, String type, String value,
                               Integer priority, Integer weight, Integer port) {
        DnsRecordModel records = Models.get(DnsRecordModel.class);
        Row row = records.createEmptyRow();
        row.set(DnsRecordModel.ZONE_ID, zone);
        row.set(DnsRecordModel.NAME, name);
        row.set(DnsRecordModel.TYPE, type);
        row.set(DnsRecordModel.VALUE, value);
        row.set(DnsRecordModel.DATA, DnsRecordModel.dataFor(type, priority, weight, port));
        row.set(DnsRecordModel.ENABLED, true);
        records.save(row);
    }

    private static Message query(String name, int type) throws Exception {
        return query(name, type, false, false);
    }

    private static Message query(String name, int type, boolean edns, boolean rd) throws Exception {
        Message query = Message.newQuery(Record.newRecord(Name.fromString(name + "."), type, DClass.IN));
        if (!rd) {
            query.getHeader().unsetFlag(Flags.RD);
        }
        if (edns) {
            query.addRecord(new OPTRecord(1232, 0, 0), Section.ADDITIONAL);
        }
        byte[] wire = query.toWire(4096);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(5000);
            socket.send(new DatagramPacket(wire, wire.length, InetAddress.getByName("127.0.0.1"), port));
            byte[] buffer = new byte[4096];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            byte[] data = new byte[response.getLength()];
            System.arraycopy(response.getData(), 0, data, 0, response.getLength());
            return new Message(data);
        }
    }

    private static Message queryTcp(String name, int type) throws Exception {
        Message query = Message.newQuery(Record.newRecord(Name.fromString(name + "."), type, DClass.IN));
        byte[] wire = query.toWire(65535);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeShort(wire.length);
            out.write(wire);
            out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int length = in.readUnsignedShort();
            byte[] data = new byte[length];
            in.readFully(data);
            return new Message(data);
        }
    }

    @Test
    void answersAreAuthoritativeWithTheFullRrset() throws Exception {
        Message response = query("www." + ORIGIN, Type.A);
        assertThat(response.getHeader().getRcode()).isEqualTo(Rcode.NOERROR);
        assertThat(response.getHeader().getFlag(Flags.AA)).isTrue();
        assertThat(response.getHeader().getFlag(Flags.RA)).isFalse();
        List<Record> answers = response.getSection(Section.ANSWER);
        assertThat(answers).hasSize(2);
        assertThat(answers.stream().map(Record::rdataToString))
            .containsExactlyInAnyOrder("192.0.2.10", "192.0.2.11");
    }

    /** The zone-top wildcard covers direct children, so a missing name must sit below an existing node. */
    @Test
    void missingNamesGetNxdomainWithSoaAuthority() throws Exception {
        Message response = query("missing.b." + ORIGIN, Type.A);
        assertThat(response.getHeader().getRcode()).isEqualTo(Rcode.NXDOMAIN);
        assertThat(response.getHeader().getFlag(Flags.AA)).isTrue();
        List<Record> authority = response.getSection(Section.AUTHORITY);
        assertThat(authority).hasSize(1);
        assertThat(authority.get(0)).isInstanceOf(SOARecord.class);
    }

    @Test
    void existingNamesWithoutTheTypeGetNodata() throws Exception {
        Message response = query("www." + ORIGIN, Type.MX);
        assertThat(response.getHeader().getRcode()).isEqualTo(Rcode.NOERROR);
        assertThat(response.getSection(Section.ANSWER)).isEmpty();
        assertThat(response.getSection(Section.AUTHORITY)).hasSize(1);
        assertThat(response.getSection(Section.AUTHORITY).get(0)).isInstanceOf(SOARecord.class);
    }

    @Test
    void emptyNonTerminalsAreNodataNotNxdomain() throws Exception {
        Message response = query("a.b." + ORIGIN, Type.A);
        assertThat(response.getHeader().getRcode()).isEqualTo(Rcode.NOERROR);
        assertThat(response.getSection(Section.ANSWER)).isEmpty();
        assertThat(response.getSection(Section.AUTHORITY)).hasSize(1);
    }

    @Test
    void wildcardSynthesizesTheQueriedOwnerName() throws Exception {
        Message response = query("anything." + ORIGIN, Type.A);
        assertThat(response.getHeader().getRcode()).isEqualTo(Rcode.NOERROR);
        List<Record> answers = response.getSection(Section.ANSWER);
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0).getName().toString()).isEqualTo("anything." + ORIGIN + ".");
        assertThat(answers.get(0).rdataToString()).isEqualTo("192.0.2.99");
    }

    @Test
    void wildcardDoesNotApplyBelowAnExistingName() throws Exception {
        Message response = query("x.a.b." + ORIGIN, Type.A);
        assertThat(response.getHeader().getRcode()).isEqualTo(Rcode.NXDOMAIN);
    }

    @Test
    void cnamesAreChasedWithinTheZone() throws Exception {
        Message response = query("alias." + ORIGIN, Type.A);
        assertThat(response.getHeader().getRcode()).isEqualTo(Rcode.NOERROR);
        List<Record> answers = response.getSection(Section.ANSWER);
        assertThat(answers.stream().filter(r -> r.getType() == Type.CNAME)).hasSize(1);
        assertThat(answers.stream().filter(r -> r.getType() == Type.A)).hasSize(2);
    }

    @Test
    void outOfZoneCnameTargetsStopAtTheCname() throws Exception {
        Message response = query("ext." + ORIGIN, Type.A);
        assertThat(response.getHeader().getRcode()).isEqualTo(Rcode.NOERROR);
        List<Record> answers = response.getSection(Section.ANSWER);
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0).getType()).isEqualTo(Type.CNAME);
    }

    @Test
    void mxAnswersCarryInZoneTargetAddressesAsAdditionalData() throws Exception {
        Message response = query("mail." + ORIGIN, Type.MX);
        assertThat(response.getSection(Section.ANSWER)).hasSize(1);
        List<Record> additional = response.getSection(Section.ADDITIONAL);
        assertThat(additional.stream().filter(r -> r.getType() == Type.A)).hasSize(2);
        assertThat(additional.stream().filter(r -> r.getType() == Type.AAAA)).hasSize(1);
    }

    @Test
    void delegatedChildrenGetAReferralWithoutAa() throws Exception {
        Message response = query("host.sub." + ORIGIN, Type.A);
        assertThat(response.getHeader().getRcode()).isEqualTo(Rcode.NOERROR);
        assertThat(response.getHeader().getFlag(Flags.AA)).isFalse();
        List<Record> authority = response.getSection(Section.AUTHORITY);
        assertThat(authority).hasSize(1);
        assertThat(authority.get(0).getType()).isEqualTo(Type.NS);
    }

    @Test
    void namesOutsideEveryZoneAreRefused() throws Exception {
        Message response = query("www.other.invalid", Type.A);
        assertThat(response.getHeader().getRcode()).isEqualTo(Rcode.REFUSED);
        assertThat(response.getHeader().getFlag(Flags.AA)).isFalse();
    }

    @Test
    void zoneTransfersAreRefused() throws Exception {
        Message response = queryTcp(ORIGIN, Type.AXFR);
        assertThat(response.getHeader().getRcode()).isEqualTo(Rcode.REFUSED);
    }

    @Test
    void recursionDesiredIsEchoedButNeverOffered() throws Exception {
        Message response = query("www." + ORIGIN, Type.A, false, true);
        assertThat(response.getHeader().getFlag(Flags.RD)).isTrue();
        assertThat(response.getHeader().getFlag(Flags.RA)).isFalse();
    }

    @Test
    void soaQueriesAtTheApexCarryTheZoneSerial() throws Exception {
        Row zone = Models.get(DnsZoneModel.class).find().where(DnsZoneModel.ID.eq(zoneId)).first();
        Integer serial = zone.get(DnsZoneModel.SERIAL);
        Message response = query(ORIGIN, Type.SOA);
        List<Record> answers = response.getSection(Section.ANSWER);
        assertThat(answers).hasSize(1);
        assertThat(((SOARecord) answers.get(0)).getSerial()).isEqualTo(serial.longValue());
    }

    @Test
    void oversizedUdpAnswersTruncateAndTcpDeliversInFull() throws Exception {
        Message udp = query("big." + ORIGIN, Type.TXT);
        assertThat(udp.getHeader().getFlag(Flags.TC)).isTrue();

        Message tcp = queryTcp("big." + ORIGIN, Type.TXT);
        assertThat(tcp.getHeader().getFlag(Flags.TC)).isFalse();
        assertThat(tcp.getSection(Section.ANSWER)).hasSize(3);
    }

    @Test
    void ednsRaisesTheUdpPayloadCeilingAndIsEchoed() throws Exception {
        Message response = query("big." + ORIGIN, Type.TXT, true, false);
        assertThat(response.getHeader().getFlag(Flags.TC)).isFalse();
        assertThat(response.getSection(Section.ANSWER)).hasSize(3);
        assertThat(response.getOPT()).isNotNull();
    }

    @Test
    void tcpAnswersMatchUdpAnswers() throws Exception {
        Message response = queryTcp("www." + ORIGIN, Type.A);
        assertThat(response.getHeader().getRcode()).isEqualTo(Rcode.NOERROR);
        assertThat(response.getHeader().getFlag(Flags.AA)).isTrue();
        assertThat(response.getSection(Section.ANSWER)).hasSize(2);
    }

    @Test
    void internalPublisherServesAndCleansUpExactTxtValues() throws Exception {
        InternalDnsTxtPublisher publisher = new InternalDnsTxtPublisher();
        String challengeName = "_acme-challenge.wild." + ORIGIN;
        DnsTxtRecord first = new DnsTxtRecord(challengeName, "digest-value-one");
        DnsTxtRecord second = new DnsTxtRecord(challengeName, "digest-value-two");

        Row before = Models.get(DnsZoneModel.class).find().where(DnsZoneModel.ID.eq(zoneId)).first();
        Integer serialBefore = before.get(DnsZoneModel.SERIAL);

        publisher.publish(first);
        publisher.publish(second);

        Message response = query(challengeName, Type.TXT);
        assertThat(response.getSection(Section.ANSWER)).hasSize(2);
        assertThat(response.getSection(Section.ANSWER).stream()
            .flatMap(r -> ((TXTRecord) r).getStrings().stream().map(String::valueOf)))
            .containsExactlyInAnyOrder("digest-value-one", "digest-value-two");

        publisher.cleanup(first);

        response = query(challengeName, Type.TXT);
        assertThat(response.getSection(Section.ANSWER)).hasSize(1);
        assertThat(((TXTRecord) response.getSection(Section.ANSWER).get(0)).getStrings())
            .containsExactly("digest-value-two");

        publisher.cleanup(second);
        response = query(challengeName, Type.TXT);
        // The zone-top wildcard still matches the name, so this is a
        // wildcard NODATA rather than NXDOMAIN; the TXT values are gone.
        assertThat(response.getHeader().getRcode()).isEqualTo(Rcode.NOERROR);
        assertThat(response.getSection(Section.ANSWER)).isEmpty();

        Row after = Models.get(DnsZoneModel.class).find().where(DnsZoneModel.ID.eq(zoneId)).first();
        assertThat((int) after.get(DnsZoneModel.SERIAL)).isGreaterThan(serialBefore);

        long leftover = Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.MANAGED_BY.eq(DnsRecordModel.MANAGED_BY_ACME))
            .count();
        assertThat(leftover).isZero();
    }

    @Test
    void recordChangesServeAfterASnapshotReload() throws Exception {
        record(zoneId, "fresh", DnsRecordModel.TYPE_A, "192.0.2.77", null, null, null);
        DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);

        Message response = query("fresh." + ORIGIN, Type.A);
        assertThat(response.getSection(Section.ANSWER)).hasSize(1);
        assertThat(response.getSection(Section.ANSWER).get(0).rdataToString()).isEqualTo("192.0.2.77");
    }
}
