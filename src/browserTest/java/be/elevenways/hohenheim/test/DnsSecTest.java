package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.dns.DnsServer;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.DClass;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DNSSEC;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.ExtendedFlags;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.NSECRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.OPTRecord;
import org.xbill.DNS.RRSIGRecord;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DNSSEC online signing: a validating resolver's building blocks verified end
 * to end -- DNSKEY self-signature, RRSIG over an A RRset, the DS the operator
 * lodges, and an NSEC denial-of-existence, all over a real UDP socket.
 */
class DnsSecTest {

    private static DnsServer server;
    private static int port;
    private static DNSKEYRecord dnsKey;

    @BeforeAll
    static void boot() throws Exception {
        File db = File.createTempFile("hohenheim-dnssec", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());

        SiteTypes.boot();
        HohenheimEndpoints.init();
        HohenheimDatabase.init();
        HohenheimTestRuntime.ensureBooted();

        int zoneId = createZone("signed.example");
        record(zoneId, "@", DnsRecordModel.TYPE_NS, "ns1.signed.example");
        record(zoneId, "ns1", DnsRecordModel.TYPE_A, "192.0.2.1");
        record(zoneId, "www", DnsRecordModel.TYPE_A, "192.0.2.10");
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

    @Test
    void apexDnskeyIsSelfSigned() throws Exception {
        Message response = query("signed.example", Type.DNSKEY);
        List<Record> answer = response.getSection(Section.ANSWER);

        RRset keys = new RRset();
        RRSIGRecord sig = null;
        for (Record record : answer) {
            if (record instanceof DNSKEYRecord key) {
                keys.addRR(key);
                dnsKey = key;
            }
            else if (record instanceof RRSIGRecord rrsig && rrsig.getTypeCovered() == Type.DNSKEY) {
                sig = rrsig;
            }
        }
        assertThat(dnsKey).isNotNull();
        assertThat(sig).isNotNull();
        // The DNSKEY RRset validates against itself (a CSK is its own signer).
        DNSSEC.verify(keys, sig, dnsKey);
    }

    @Test
    void positiveAnswerCarriesAValidRrsig() throws Exception {
        DNSKEYRecord key = apexKey();
        Message response = query("www.signed.example", Type.A);
        assertThat(response.getHeader().getRcode()).isEqualTo(Rcode.NOERROR);

        RRset aSet = new RRset();
        RRSIGRecord sig = null;
        for (Record record : response.getSection(Section.ANSWER)) {
            if (record.getType() == Type.A) {
                aSet.addRR(record);
            }
            else if (record instanceof RRSIGRecord rrsig && rrsig.getTypeCovered() == Type.A) {
                sig = rrsig;
            }
        }
        assertThat(sig).as("A answer is signed").isNotNull();
        assertThat(sig.getSigner()).isEqualTo(Name.fromString("signed.example."));
        DNSSEC.verify(aSet, sig, key);
    }

    @Test
    void dsMatchesTheApexKey() throws Exception {
        DNSKEYRecord key = apexKey();
        Row zone = Models.get(DnsZoneModel.class).findByOrigin("signed.example");
        DSRecord ds = new DSRecord(Name.fromString("signed.example."), DClass.IN, 3600,
            DSRecord.Digest.SHA256, key);
        // The stored key tag matches the DS/DNSKEY footprint the registrar will pin.
        assertThat((Integer) zone.get(DnsZoneModel.DNSSEC_KEY_TAG)).isEqualTo(ds.getFootprint());
        assertThat(ds.getAlgorithm()).isEqualTo(DNSSEC.Algorithm.ECDSAP256SHA256);
    }

    @Test
    void nxdomainIsProvenWithASignedNsec() throws Exception {
        DNSKEYRecord key = apexKey();
        Message response = query("absent.signed.example", Type.A);
        assertThat(response.getHeader().getRcode()).isEqualTo(Rcode.NXDOMAIN);

        boolean nsecFound = false;
        List<Record> authority = response.getSection(Section.AUTHORITY);
        for (Record record : authority) {
            if (record instanceof NSECRecord nsec) {
                nsecFound = true;
                // The NSEC's range must actually cover the missing name.
                Name owner = nsec.getName();
                Name next = nsec.getNext();
                Name qname = Name.fromString("absent.signed.example.");
                boolean covers = owner.compareTo(qname) < 0
                    && (qname.compareTo(next) < 0 || next.compareTo(owner) <= 0);
                assertThat(covers).as("NSEC covers the missing name").isTrue();

                RRset nsecSet = new RRset();
                nsecSet.addRR(nsec);
                RRSIGRecord sig = rrsigFor(authority, Type.NSEC, owner);
                assertThat(sig).as("NSEC is signed").isNotNull();
                DNSSEC.verify(nsecSet, sig, key);
            }
        }
        assertThat(nsecFound).as("NXDOMAIN carried an NSEC proof").isTrue();
    }

    @Test
    void unsignedQueryOmitsDnssecRecords() throws Exception {
        // No DO bit: a legacy resolver must not receive RRSIG/NSEC bloat.
        Message response = queryNoDo("www.signed.example", Type.A);
        for (Record record : response.getSection(Section.ANSWER)) {
            assertThat(record.getType()).isNotEqualTo(Type.RRSIG);
        }
    }

    private static DNSKEYRecord apexKey() throws Exception {
        if (dnsKey == null) {
            Message response = query("signed.example", Type.DNSKEY);
            for (Record record : response.getSection(Section.ANSWER)) {
                if (record instanceof DNSKEYRecord key) {
                    dnsKey = key;
                }
            }
        }
        return dnsKey;
    }

    private static RRSIGRecord rrsigFor(List<Record> records, int coveredType, Name owner) {
        for (Record record : records) {
            if (record instanceof RRSIGRecord sig
                    && sig.getTypeCovered() == coveredType && sig.getName().equals(owner)) {
                return sig;
            }
        }
        return null;
    }

    private static Message query(String name, int type) throws Exception {
        Message query = Message.newQuery(Record.newRecord(Name.fromString(name + "."), type, DClass.IN));
        query.addRecord(new OPTRecord(4096, 0, 0, ExtendedFlags.DO), Section.ADDITIONAL);
        return exchange(query);
    }

    private static Message queryNoDo(String name, int type) throws Exception {
        return exchange(Message.newQuery(Record.newRecord(Name.fromString(name + "."), type, DClass.IN)));
    }

    private static Message exchange(Message query) throws Exception {
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

    private static int createZone(String origin) {
        DnsZoneModel zones = Models.get(DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, origin);
        zone.set(DnsZoneModel.SOA_PRIMARY_NS, "ns1." + origin);
        zone.set(DnsZoneModel.SOA_CONTACT, "hostmaster@" + origin);
        zone.set(DnsZoneModel.ENABLED, true);
        zone.set(DnsZoneModel.DNSSEC_ENABLED, true);
        zones.save(zone);
        return zone.get(DnsZoneModel.ID);
    }

    private static void record(int zone, String name, String type, String value) {
        DnsRecordModel records = Models.get(DnsRecordModel.class);
        Row row = records.createEmptyRow();
        row.set(DnsRecordModel.ZONE_ID, zone);
        row.set(DnsRecordModel.NAME, name);
        row.set(DnsRecordModel.TYPE, type);
        row.set(DnsRecordModel.VALUE, value);
        row.set(DnsRecordModel.ENABLED, true);
        records.save(row);
    }
}
