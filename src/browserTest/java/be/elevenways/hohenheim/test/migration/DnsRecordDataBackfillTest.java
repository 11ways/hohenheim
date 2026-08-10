package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.model.DnsDyndnsCredentialModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.hohenheim.server.migration.M091_TypedDnsRecordData;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The M091 data step against a simulated pre-M091 install: the doomed flat columns are
 * re-created as fixtures and populated with the live starfleet zone's shape (apex A,
 * wildcard A, an NS RRset, glue A records, a TXT) plus every carrier shape, and the
 * REAL backfill moves exactly the type-specific extras into {@code data} and exactly
 * the ARMED tokens into {@code dns_dyndns_credentials}.
 */
class DnsRecordDataBackfillTest {

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-dns-backfill-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per test class (the RecordScheduleBackfillTest rationale).
        Datasources.register(Datasources.DEFAULT, datasource);
    }

    @AfterAll
    static void tearDown() {
        datasource.close();
    }

    @Test
    void backfillCarriesEveryLiveRecordAndArmsOnlyRealCredentialsJourney() {
        Db.run(datasource, () -> {
            SqlDatasource sql = datasource.unwrap(SqlDatasource.class);

            // 1. Simulate the pre-M091 shape: the flat columns exist again.
            sql.rawUpdate("ALTER TABLE dns_records ADD COLUMN priority INTEGER");
            sql.rawUpdate("ALTER TABLE dns_records ADD COLUMN weight INTEGER");
            sql.rawUpdate("ALTER TABLE dns_records ADD COLUMN port INTEGER");
            sql.rawUpdate("ALTER TABLE dns_records ADD COLUMN dynamic INTEGER NOT NULL DEFAULT 0");
            sql.rawUpdate("ALTER TABLE dns_records ADD COLUMN dyndns_token VARCHAR(96)");

            DnsZoneModel zones = Models.get(DnsZoneModel.class);
            Row zone = zones.createEmptyRow();
            zone.set(DnsZoneModel.ORIGIN, "backfill.example");
            zone.set(DnsZoneModel.SOA_PRIMARY_NS, "ns1.backfill.example");
            zone.set(DnsZoneModel.SOA_CONTACT, "hostmaster@backfill.example");
            zone.set(DnsZoneModel.ENABLED, true);
            zones.save(zone);
            int zoneId = zone.get(DnsZoneModel.ID);

            // 2. The live starfleet shape: 8 records that never used any moved field.
            int apexA = record(zoneId, "@", DnsRecordModel.TYPE_A, "203.0.113.10");
            int wildcard = record(zoneId, "*", DnsRecordModel.TYPE_A, "203.0.113.10");
            int ns1Rr = record(zoneId, "@", DnsRecordModel.TYPE_NS, "ns1.backfill.example.");
            int ns2Rr = record(zoneId, "@", DnsRecordModel.TYPE_NS, "ns2.backfill.example.");
            int ns1A = record(zoneId, "ns1", DnsRecordModel.TYPE_A, "203.0.113.10");
            int ns2A = record(zoneId, "ns2", DnsRecordModel.TYPE_A, "203.0.113.11");
            int txt = record(zoneId, "@", DnsRecordModel.TYPE_TXT, "v=spf1 -all");
            int caa = record(zoneId, "@", DnsRecordModel.TYPE_CAA, "0 issue letsencrypt.org");

            // 3. The carrier shapes, written into the doomed columns raw.
            int mx = record(zoneId, "@", DnsRecordModel.TYPE_MX, "mail.backfill.example.");
            sql.rawUpdate("UPDATE dns_records SET priority = 10 WHERE id = ?", mx);
            int srv = record(zoneId, "_svc._tcp", DnsRecordModel.TYPE_SRV, "host.backfill.example.");
            sql.rawUpdate("UPDATE dns_records SET priority = 5, weight = 0, port = 8443"
                + " WHERE id = ?", srv);

            String hashedToken = "hdyn_backfill-already-hashed-token1";
            int hashedDyn = record(zoneId, "router", DnsRecordModel.TYPE_A, "203.0.113.50");
            sql.rawUpdate("UPDATE dns_records SET dynamic = 1, dyndns_token = ? WHERE id = ?",
                DynamicDnsService.digest(hashedToken), hashedDyn);

            String plainToken = "hdyn_backfill-legacy-plaintext-token";
            int plainDyn = record(zoneId, "cam", DnsRecordModel.TYPE_A, "203.0.113.51");
            sql.rawUpdate("UPDATE dns_records SET dynamic = 1, dyndns_token = ? WHERE id = ?",
                plainToken, plainDyn);

            // A token on a NON-dynamic row never authenticated; carrying it over would ARM it.
            int inert = record(zoneId, "old", DnsRecordModel.TYPE_A, "203.0.113.52");
            sql.rawUpdate("UPDATE dns_records SET dynamic = 0, dyndns_token = ? WHERE id = ?",
                DynamicDnsService.digest("hdyn_backfill-inert-token-value00"), inert);

            // 4. The REAL data step.
            M091_TypedDnsRecordData.backfill(datasource);

            // 5. Every live-shaped record is untouched: same value, data stays null,
            //    no credential appeared.
            DnsRecordModel records = Models.get(DnsRecordModel.class);
            for (int id : new int[] {apexA, wildcard, ns1Rr, ns2Rr, ns1A, ns2A, txt, caa}) {
                Row row = records.findById(id);
                assertThat(row).as("5. record %s survives", id).isNotNull();
                assertThat((Object) row.get(DnsRecordModel.DATA))
                    .as("5. a record without extras migrates with data = null").isNull();
                assertThat(DynamicDnsService.credentialFor(id))
                    .as("5. no credential appears for a non-dynamic record").isNull();
            }
            assertThat((String) records.findById(apexA).get(DnsRecordModel.VALUE))
                .as("5. the apex A still answers its address").isEqualTo("203.0.113.10");

            // 6. MX/SRV extras moved into the type's declared shape.
            assertThat(DnsRecordModel.priorityOf(records.findById(mx)))
                .as("6. the MX priority rode into data").isEqualTo(10);
            assertThat((Object) records.findById(mx).get(DnsRecordModel.DATA))
                .as("6. the MX data map carries ONLY its own key")
                .isEqualTo(Map.of("priority", 10));
            Row srvRow = records.findById(srv);
            assertThat(DnsRecordModel.priorityOf(srvRow)).as("6. SRV priority").isEqualTo(5);
            assertThat(DnsRecordModel.weightOf(srvRow)).as("6. SRV weight").isEqualTo(0);
            assertThat(DnsRecordModel.portOf(srvRow)).as("6. SRV port").isEqualTo(8443);

            // 7. Armed tokens became credential rows; the already-hashed digest survives
            //    verbatim and the legacy plaintext is hashed IN the copy -- both clients
            //    keep working because they still present the same plaintext.
            assertThat((String) DynamicDnsService.credentialFor(hashedDyn)
                .get(DnsDyndnsCredentialModel.TOKEN_DIGEST))
                .as("7. the hashed token's digest is carried verbatim")
                .isEqualTo(DynamicDnsService.digest(hashedToken));
            assertThat((String) DynamicDnsService.credentialFor(plainDyn)
                .get(DnsDyndnsCredentialModel.TOKEN_DIGEST))
                .as("7. the legacy plaintext token is stored as its digest")
                .isEqualTo(DynamicDnsService.digest(plainToken))
                .doesNotContain(plainToken);

            // 8. The inert token did NOT arm.
            assertThat(DynamicDnsService.credentialFor(inert))
                .as("8. a token on a non-dynamic row is dropped, never armed").isNull();
        });
    }

    private static int record(int zoneId, String name, String type, String value) {
        DnsRecordModel records = Models.get(DnsRecordModel.class);
        Row row = records.createEmptyRow();
        row.set(DnsRecordModel.ZONE_ID, zoneId);
        row.set(DnsRecordModel.NAME, name);
        row.set(DnsRecordModel.TYPE, type);
        row.set(DnsRecordModel.VALUE, value);
        row.set(DnsRecordModel.ENABLED, true);
        records.save(row);
        return row.get(DnsRecordModel.ID);
    }
}
