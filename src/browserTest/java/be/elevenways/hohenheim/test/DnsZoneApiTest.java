package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.dns.DelegationVerdict;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DelegationCheck;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DnsNameservers;
import be.elevenways.hohenheim.server.dns.DnsZoneFiles;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The DNS half of the migration lane: a zone created through the API is the form's own
 * create (declared nameservers seeded at the apex), a zone-file import substitutes the
 * controller's nameservers for the provider's unless told to keep them and never does so
 * silently, and the declared set is a yardstick the delegation check reads.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DnsZoneApiTest extends HohenheimTestBase {

    private static final String ORIGIN = "zone-api-a.test";
    private static final List<String> DECLARED = List.of("ns1.zone-api.test", "ns2.zone-api.test");

    private static String keyAdmin;
    private static String keyNarrow;
    private static List<String> previousDeclared;

    /** Filled by the create journey, consumed by the import journey. */
    private static Integer zoneId;

    @BeforeAll
    static void seed() {
        previousDeclared = DnsNameservers.declared();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Dns.NAMESERVERS, DECLARED);
        int adminId = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first().get(UserModel.ID);
        keyAdmin = ApiKeyService.create(adminId, "zone-api-admin", List.of("hohenheim.*"), null)
            .plaintext();
        keyNarrow = ApiKeyService.create(adminId, "zone-api-narrow", List.of("shortlink.*"), null)
            .plaintext();
    }

    @AfterAll
    static void cleanUp() {
        DnsZoneModel zones = Models.get(DnsZoneModel.class);
        for (Row zone : zones.find().where(DnsZoneModel.ORIGIN.endsWith("zone-api-a.test")).all()) {
            // DnsZoneCascades take the records with the zone on every delete lane.
            zones.delete(zone.get(DnsZoneModel.ID));
        }
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Dns.NAMESERVERS, previousDeclared);
        DnsZoneStore.INSTANCE.reload();
    }

    // -- fixtures --------------------------------------------------------------

    private static String form(String... pairs) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return body.toString();
    }

    private static int idOf(String json) {
        Matcher matcher = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(json);
        assertThat(matcher.find()).as("the response carries an id: " + json).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static String codeOf(String json) {
        Matcher matcher = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).as("the refusal carries a code: " + json).isTrue();
        return matcher.group(1);
    }

    private static List<String> apexNs(int zone) {
        return Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zone))
            .where(DnsRecordModel.NAME.eq(DnsNames.APEX))
            .where(DnsRecordModel.TYPE.eq(DnsRecordModel.TYPE_NS))
            .all().stream().map(row -> (String) row.get(DnsRecordModel.VALUE)).sorted().toList();
    }

    private static List<String> owners(int zone, String type) {
        return Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zone))
            .where(DnsRecordModel.TYPE.eq(type))
            .all().stream().map(row -> (String) row.get(DnsRecordModel.NAME)).sorted().toList();
    }

    private static int serialOf(int zone) {
        Integer serial = Models.get(DnsZoneModel.class).findById(zone).get(DnsZoneModel.SERIAL);
        return serial == null ? 0 : serial;
    }

    private static Row zone(int zone) {
        return Models.get(DnsZoneModel.class).findById(zone);
    }

    private static int acmeRow(int zone) {
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

    /** A provider export the way afraid.org hands it out: its own SOA and NS set. */
    private static String providerExport(boolean withApexNs) {
        StringBuilder text = new StringBuilder();
        text.append("$ORIGIN ").append(ORIGIN).append(".\n$TTL 3600\n");
        text.append("@ IN SOA ns1.afraid.org. dnsadmin.afraid.org. 2604070003 86400 7200 2419200 3600\n");
        if (withApexNs) {
            text.append("@ IN NS ns1.afraid.org.\n@ IN NS ns2.afraid.org.\n");
        }
        text.append("@ IN A 192.0.2.1\nwww IN A 192.0.2.2\n@ IN MX 10 mail.").append(ORIGIN).append(".\n");
        return text.toString();
    }

    // -- the journeys ----------------------------------------------------------

    /** A created primary zone carries the declared nameservers; the declared set is a yardstick. */
    @Test
    @Order(1)
    void aCreatedPrimaryZoneIsSeededWithTheDeclaredNameservers() throws Exception {
        // 1. The create is the form's: the origin lands canonical and the apex NS rows are
        //    the declared set, written once by the resource's own persist.
        HttpResponse<String> created = keyPost(keyAdmin, "/api/v1/dns/zones", form(
            "origin", "Zone-Api-A.test.", "soa_contact", "hostmaster@" + ORIGIN));
        assertThat(created.statusCode()).as("step 1: the zone is created: " + created.body())
            .isEqualTo(200);
        zoneId = idOf(created.body());
        assertThat((Object) zone(zoneId).get(DnsZoneModel.ORIGIN))
            .as("step 1: the origin is canonical").isEqualTo(ORIGIN);
        assertThat(apexNs(zoneId)).as("step 1: the apex NS rows are the declared set")
            .containsExactlyElementsOf(DECLARED);
        assertThat(created.body()).as("step 1: the projection names them")
            .contains("ns1.zone-api.test").contains("ns2.zone-api.test")
            .contains("\"role\":\"primary\"");

        // 2. The list read enumerates it.
        HttpResponse<String> listed = keyGet(keyAdmin, "/api/v1/dns/zones");
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).as("step 2: the list carries the zone").contains("\"" + ORIGIN + "\"");

        // 3. The declared set is the yardstick the delegation check reads: agreement is
        //    silent, a name on either side alone is a named finding, an undeclared
        //    controller judges nothing.
        assertThat(DnsNameservers.compareWithDeclared(List.of("NS2.zone-api.test", "ns1.zone-api.test")))
            .as("step 3: the same set, whatever the case or order, is no finding").isEmpty();
        List<DelegationCheck.Finding> findings =
            DnsNameservers.compareWithDeclared(List.of("ns1.zone-api.test", "ns9.other.test"));
        assertThat(findings).as("step 3: one finding per disagreeing name").hasSize(2);
        assertThat(findings).extracting(DelegationCheck.Finding::verdict)
            .containsOnly(DelegationVerdict.APEX_UNDECLARED);
        assertThat(findings).extracting(DelegationCheck.Finding::subject)
            .as("step 3: each side of the disagreement is named")
            .containsExactlyInAnyOrder("ns9.other.test served but not declared",
                "ns2.zone-api.test declared but not served");
        assertThat(DelegationVerdict.MATCHES.worseOf(DelegationVerdict.APEX_UNDECLARED))
            .as("step 3: the finding outranks a clean verdict and carries a severity")
            .isEqualTo(DelegationVerdict.APEX_UNDECLARED);
        assertThat(DelegationVerdict.APEX_UNDECLARED.severity()).isEqualTo("warning");
        assertThat(DelegationVerdict.forToken("apex_undeclared")).isEqualTo(DelegationVerdict.APEX_UNDECLARED);

        // 4. A stranger key is refused by name and writes nothing; a key narrowed away
        //    from the admin permission never reaches the form.
        HttpResponse<String> stranger = keyPost(keyAdmin, "/api/v1/dns/zones", form(
            "origin", "stranger-zone-api-a.test", "colour", "red"));
        assertThat(stranger.statusCode()).as("step 4: a stranger key is a typed refusal").isEqualTo(422);
        assertThat(codeOf(stranger.body())).isEqualTo("zenit.coercion.unknown_field");
        assertThat(keyPost(keyNarrow, "/api/v1/dns/zones", form("origin", "narrow-zone-api-a.test"))
            .statusCode()).as("step 4: the narrowed key is shut out").isEqualTo(403);
        assertThat(keyGet(keyNarrow, "/api/v1/dns/zones").statusCode())
            .as("step 4: the list is admin-only too").isEqualTo(403);
        assertThat(Models.get(DnsZoneModel.class).find()
                .where(DnsZoneModel.ORIGIN.endsWith("-zone-api-a.test")).count())
            .as("step 4: neither refused create wrote a row").isZero();
    }

    /** An import substitutes the declared nameservers for the provider's, unless told to keep them. */
    @Test
    @Order(2)
    void importReplacesTheForeignApexNsSetUnlessToldToKeepIt() throws Exception {
        int acmeId = acmeRow(zoneId);
        int serialBefore = serialOf(zoneId);

        // 1. The provider export carries afraid's NS set and SOA: the NS rows become the
        //    declared set, the SOA is reported rather than silently dropped, the ACME row
        //    survives, the serial moves.
        HttpResponse<String> imported = keyPost(keyAdmin, "/api/v1/dns/zones/" + zoneId + "/import",
            form("zone_text", providerExport(true)));
        assertThat(imported.statusCode()).as("step 1: the import lands: " + imported.body())
            .isEqualTo(200);
        assertThat(apexNs(zoneId)).as("step 1: the foreign NS set was replaced by the declared one")
            .containsExactlyElementsOf(DECLARED);
        assertThat(owners(zoneId, DnsRecordModel.TYPE_A)).as("step 1: the data rows landed")
            .containsExactly("@", "www");
        assertThat(Models.get(DnsRecordModel.class).findById(acmeId))
            .as("step 1: the ACME-managed row is kept").isNotNull();
        assertThat(serialOf(zoneId)).as("step 1: the serial was bumped").isGreaterThan(serialBefore);
        assertThat(imported.body()).as("step 1: the response names what it did not take verbatim")
            .contains("SOA ignored: ns1.afraid.org dnsadmin.afraid.org serial 2604070003")
            .contains("apex NS ns1.afraid.org, ns2.afraid.org replaced by the declared")
            .contains("\"nameservers\":[\"ns1.zone-api.test\",\"ns2.zone-api.test\"]");

        // 2. keep_ns keeps the file's set exactly, and says so by writing no nameservers.
        HttpResponse<String> kept = keyPost(keyAdmin, "/api/v1/dns/zones/" + zoneId + "/import",
            form("zone_text", providerExport(true), "keep_ns", "on"));
        assertThat(kept.statusCode()).isEqualTo(200);
        assertThat(apexNs(zoneId)).as("step 2: the file's apex NS rows are kept")
            .containsExactly("ns1.afraid.org", "ns2.afraid.org");
        assertThat(kept.body()).contains("\"nameservers\":[]").doesNotContain("replaced by");

        // 3. With nothing declared, the default policy refuses a file that carries a
        //    foreign NS set (there is nothing to put in its place) and leaves the rows
        //    untouched, while a file without apex NS rows imports with nothing to replace.
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Dns.NAMESERVERS, List.of());
        try {
            int serialBeforeRefusal = serialOf(zoneId);
            HttpResponse<String> refused = keyPost(keyAdmin, "/api/v1/dns/zones/" + zoneId + "/import",
                form("zone_text", providerExport(true)));
            assertThat(refused.statusCode()).as("step 3: refused, not silently degraded").isEqualTo(422);
            assertThat(codeOf(refused.body())).isEqualTo("import_nameservers_undeclared");
            assertThat(apexNs(zoneId)).as("step 3: the rows are untouched by a refusal")
                .containsExactly("ns1.afraid.org", "ns2.afraid.org");
            assertThat(serialOf(zoneId)).as("step 3: nor did the serial move").isEqualTo(serialBeforeRefusal);
            HttpResponse<String> bare = keyPost(keyAdmin, "/api/v1/dns/zones/" + zoneId + "/import",
                form("zone_text", providerExport(false)));
            assertThat(bare.statusCode()).as("step 3: a file without apex NS has nothing to replace")
                .isEqualTo(200);
            assertThat(apexNs(zoneId)).as("step 3: which leaves the zone with no apex NS at all").isEmpty();
            assertThat(bare.body()).contains("\"nameservers\":[]");
        } finally {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Dns.NAMESERVERS, DECLARED);
        }

        // 4. The doors and the refusals every caller shares: blank text, a narrowed key,
        //    an unknown zone, and a secondary zone whose rows belong to its primary.
        HttpResponse<String> blank = keyPost(keyAdmin, "/api/v1/dns/zones/" + zoneId + "/import",
            form("zone_text", "   "));
        assertThat(blank.statusCode()).isEqualTo(422);
        assertThat(codeOf(blank.body())).as("step 4: an empty paste is named").isEqualTo("import_empty");
        assertThat(keyPost(keyNarrow, "/api/v1/dns/zones/" + zoneId + "/import",
            form("zone_text", providerExport(true))).statusCode())
            .as("step 4: the narrowed key is shut out").isEqualTo(403);
        assertThat(keyPost(keyAdmin, "/api/v1/dns/zones/99000001/import",
            form("zone_text", providerExport(true))).statusCode())
            .as("step 4: an unknown zone is 404").isEqualTo(404);
        Row secondary = Models.get(DnsZoneModel.class).createEmptyRow();
        secondary.set(DnsZoneModel.ORIGIN, "secondary-zone-api-a.test");
        secondary.set(DnsZoneModel.ROLE, DnsZoneModel.ROLE_SECONDARY);
        secondary.set(DnsZoneModel.ENABLED, false);
        Models.get(DnsZoneModel.class).save(secondary);
        assertThatThrownBy(() -> DnsZoneFiles.importText(secondary, providerExport(true)))
            .as("step 4: the panel's own import call refuses a secondary the same way")
            .isInstanceOf(Violations.class);
        HttpResponse<String> onSecondary = keyPost(keyAdmin,
            "/api/v1/dns/zones/" + secondary.get(DnsZoneModel.ID) + "/import",
            form("zone_text", providerExport(true)));
        assertThat(onSecondary.statusCode()).isEqualTo(422);
        assertThat(codeOf(onSecondary.body())).isEqualTo("import_secondary_zone");
    }
}
