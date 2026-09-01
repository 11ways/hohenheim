package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.dns.DelegationVerdict;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.server.cms.DnsRecordResource;
import be.elevenways.hohenheim.server.cms.DnsZonePeerResource;
import be.elevenways.hohenheim.server.cms.DnsZoneResource;
import be.elevenways.hohenheim.server.dns.DelegationCheck;
import be.elevenways.hohenheim.server.dns.DnsZoneFiles;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.edit.InputType;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every DNS admin surface must report what is TRUE for the zone's role: a replica authors
 * no rows locally, so anything counted, exported or offered off {@code dns_records} lied
 * about it; a primary replicates OUTWARD, which nothing on the list said; and a record's
 * type-specific rdata is what tells two otherwise identical rows apart.
 */
class DnsSurfaceHonestyTest extends HohenheimTestBase {

    @Test
    void aReplicaZoneReportsWhatItActuallyServes() throws Exception {
        String origin = "honesty-replica.example";
        int peerId = DnsFixtures.transferPeer("honesty-peer", "192.0.2.10", 53);
        int replicaId = DnsFixtures.createZone(origin, DnsZoneModel.ROLE_SECONDARY, peerId);

        DnsZoneResource zones = new DnsZoneResource();
        ColumnSpec countColumn = column(zones, "record_count");
        Row replica = Models.get(DnsZoneModel.class).findById(replicaId);

        // 1. Nothing transferred yet: a replica that serves nothing honestly counts nothing.
        assertThat(zones.cellValue(replica, countColumn))
            .as("step 1: an untransferred replica serves no records")
            .isEqualTo(0L);

        DnsZoneStore.INSTANCE.putSecondarySnapshot(
            DnsZoneStore.snapshotFromTransfer(replicaId, origin, replicaRecords(origin)));
        try {
            // 2. Once the transfer landed, the count is the SERVED snapshot's -- the zone
            //    will always hold zero dns_records rows, which is what used to be shown.
            assertThat(zones.cellValue(replica, countColumn))
                .as("step 2: a serving replica counts the records it answers with")
                .isEqualTo(2L);

            // 3. And the zone file exports those same records, not a lone SOA.
            String exported = DnsZoneFiles.export(replica);
            assertThat(exported)
                .as("step 3: the export carries the replicated records")
                .contains("www." + origin + ".")
                .contains("ns1." + origin + ".")
                .contains("SOA");

            // 4. Importing INTO a replica is refused: its records belong to its primary.
            //    The panel therefore never offers the form (DnsZoneFilePage.importable).
            assertThatThrownBy(() -> DnsZoneFiles.importText(replica,
                    "www 3600 IN A 203.0.113.9\n"))
                .as("step 4: an import into a replica fails closed")
                .isInstanceOf(Violations.class);
        }
        finally {
            DnsZoneStore.INSTANCE.removeSecondarySnapshot(origin);
        }

        // 5. The outbound column is the mirror image: a replica replicates to nobody.
        assertThat(zones.cellValue(replica, column(zones, "secondaries")))
            .as("step 5: a replica shows no outbound state")
            .isNull();
    }

    @Test
    void aPrimaryZoneReportsItsOwnRowsAndItsOutboundReplication() {
        String origin = "honesty-primary.example";
        int zoneId = DnsFixtures.createZone(origin, DnsZoneModel.ROLE_PRIMARY, null);
        DnsFixtures.record(zoneId, "www", DnsRecordModel.TYPE_A, "198.51.100.1");

        DnsZoneResource zones = new DnsZoneResource();
        Row zone = Models.get(DnsZoneModel.class).findById(zoneId);

        // 1. A primary still counts the rows it authors.
        assertThat(zones.cellValue(zone, column(zones, "record_count")))
            .as("step 1: a primary counts its stored records")
            .isEqualTo(1L);

        // 2. With no secondary linked, the outbound column says exactly that rather than
        //    leaving the reader with the blank transfer-status cell a primary always had.
        assertThat(microcopyKey(zones.cellValue(zone, column(zones, "secondaries"))))
            .as("step 2: an unreplicated primary names its lack of secondaries")
            .isEqualTo("secondaries_none");

        // 3. A linked but never probed secondary counts toward the total and NOT toward
        //    the current tally: an unprobed peer is the one that silently stopped pulling.
        int peerId = DnsFixtures.transferPeer("honesty-outbound", "192.0.2.11", 53);
        DnsFixtures.linkZonePeer(zoneId, peerId);
        Microcopy linked = (Microcopy) zones.cellValue(zone, column(zones, "secondaries"));
        assertThat(linked.key())
            .as("step 3: a linked primary summarizes its secondaries")
            .isEqualTo("secondaries_current");
        assertThat(linked.args().get("total"))
            .as("step 3: the link is counted")
            .isEqualTo(1);
        assertThat(linked.args().get("current"))
            .as("step 3: an unprobed link is not counted as current")
            .isEqualTo(0);
    }

    @Test
    void aRecordValueCellPrintsTheRdataAResolverWouldShow() {
        int zoneId = DnsFixtures.createZone("honesty-rdata.example", DnsZoneModel.ROLE_PRIMARY, null);
        int mxId = DnsFixtures.record(zoneId, "@", DnsRecordModel.TYPE_MX,
            "aspmx.l.google.com", 10, null, null);
        int srvId = DnsFixtures.record(zoneId, "_sip._tcp", DnsRecordModel.TYPE_SRV,
            "sip.example", 10, 60, 5060);
        int aId = DnsFixtures.record(zoneId, "www", DnsRecordModel.TYPE_A, "198.51.100.1");

        DnsRecordResource records = new DnsRecordResource();
        ColumnSpec valueColumn = column(records, DnsRecordModel.VALUE.getName());
        DnsRecordModel model = Models.get(DnsRecordModel.class);

        // 1. The MX priority leads the target: five rows to Google's mail hosts are five
        //    DIFFERENT records and the list has to say so.
        assertThat(records.cellValue(model.findById(mxId), valueColumn))
            .as("step 1: an MX cell carries its priority")
            .isEqualTo("10 aspmx.l.google.com");

        // 2. The SRV trio, in dig's order.
        assertThat(records.cellValue(model.findById(srvId), valueColumn))
            .as("step 2: an SRV cell carries priority, weight and port")
            .isEqualTo("10 60 5060 sip.example");

        // 3. A type with no declared extras is untouched: this is presentation, and the
        //    stored column keeps the bare value the codec and the filters read.
        assertThat(records.cellValue(model.findById(aId), valueColumn))
            .as("step 3: a plain type renders its value unchanged")
            .isEqualTo("198.51.100.1");
        assertThat(model.findById(mxId).get(DnsRecordModel.VALUE))
            .as("step 3: the stored value is still the bare target")
            .isEqualTo("aspmx.l.google.com");
    }

    @Test
    void delegationFindingsReadAsOneLocalizedLineEach() {
        // 1. The stored line shape has a reader beside its writer, so the panel never
        //    guesses at the token grammar.
        DelegationCheck.Finding written =
            new DelegationCheck.Finding(DelegationVerdict.MISSING_GLUE, "ns1.example");
        DelegationCheck.Finding read = DelegationCheck.Finding.parse(written.line());
        assertThat(read)
            .as("step 1: a stored line parses back to the finding that wrote it")
            .isEqualTo(written);

        // 2. A token this build does not declare is not guessed at.
        assertThat(DelegationCheck.Finding.parse("something_else ns1.example"))
            .as("step 2: an unknown token yields no finding")
            .isNull();

        // 3. Every verdict names itself through Microcopy -- the label is what the panel
        //    renders, so a literal-only vocabulary would be untranslatable by construction.
        for (DelegationVerdict verdict : DelegationVerdict.values()) {
            assertThat(verdict.label().key())
                .as("step 3: " + verdict.token() + " carries a catalog key")
                .isEqualTo(verdict.token());
            assertThat(verdict.label().isLiteral())
                .as("step 3: " + verdict.token() + " is not literal text")
                .isFalse();
        }

        // 4. And the column renders as several lines rather than one input's worth: a
        //    single-line control collapsed every finding into one run-on string.
        assertThat(DnsZoneModel.DELEGATION_DETAIL.getInputType())
            .as("step 4: the detail column renders multiline")
            .isEqualTo(InputType.MULTILINE);
    }

    @Test
    void aZonePeerLinkIsNamedByBothHalvesAndPickedNotTyped() {
        DnsZonePeerResource links = new DnsZonePeerResource();

        // 1. The list carries the zone, so a peer secondarying four zones is four
        //    distinguishable rows instead of four rows called "robbedoes".
        ColumnSpec zoneColumn = column(links, DnsZonePeerModel.ZONE_ID.getName());
        assertThat(zoneColumn.relation())
            .as("step 1: the zone column resolves its title through the relation")
            .isNotNull();

        // 2. And the editor picks that zone as a record, never as a primary key typed into
        //    a numeric stepper.
        FormEntry zoneEntry = null;
        for (FormEntry entry : links.formSpec().entries()) {
            if (DnsZonePeerModel.ZONE_ID.getName().equals(entry.name())) {
                zoneEntry = entry;
            }
        }
        assertThat(zoneEntry)
            .as("step 2: the zone is a relation pick")
            .isInstanceOf(RelationPick.class);
    }

    /** @return the named column of a resource's declared table spec */
    private static ColumnSpec column(be.elevenways.zenit.cms.common.resource.Resource<Row> resource,
                                     String name) {
        for (ColumnSpec column : resource.tableSpec().columns()) {
            if (name.equals(column.name())) {
                return column;
            }
        }
        throw new IllegalStateException("no column '" + name + "' on " + resource.id());
    }

    /** @return the catalog key of a cell that renders as Microcopy */
    private static String microcopyKey(Object cell) {
        return ((Microcopy) cell).key();
    }

    /** A minimal transferred zone: the SOA plus one NS and one A record. */
    private static List<Record> replicaRecords(String origin) throws Exception {
        Name originName = Name.fromString(origin + ".");
        List<Record> records = new ArrayList<>();
        records.add(new SOARecord(originName, DClass.IN, 3600,
            Name.fromString("ns1." + origin + "."), Name.fromString("hostmaster." + origin + "."),
            7, 7200, 3600, 1209600, 300));
        records.add(new NSRecord(originName, DClass.IN, 3600, Name.fromString("ns1." + origin + ".")));
        records.add(new ARecord(Name.fromString("www." + origin + "."), DClass.IN, 3600,
            InetAddress.getByName("198.51.100.20")));
        return records;
    }
}
