package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin flows for hosted DNS: zone CRUD with validation, the records tab,
 * record validation through the codec, zone-file export/import, and the
 * certificate-request page's hosted-DNS option gating.
 */
@TestMethodOrder(OrderAnnotation.class)
class DnsAdminTest extends HohenheimTestBase {

    private static int zoneId;
    private static int recordId;

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Zone creation with validation, record CRUD through the codec, and the records/zone-file tabs. */
    @Test
    @Order(1)
    void zoneAndRecordAdminJourney() throws Exception {
        navigateToApp("/admin/dns-zones/new");
        waitForHydration();
        assertThat(page.locator("body").textContent())
            .contains("Zone origin")
            .contains("The domain this zone is authoritative for")
            .contains("Role")
            .contains("Primary zones are edited here")
            .contains("Owning peer");

        // Creating a zone normalizes the origin.
        var response = postForm("/admin/dns-zones/new",
            "origin=Admin-Zone.Example.&soa_primary_ns=&soa_contact="
            + "&default_ttl=3600&negative_ttl=300&soa_refresh=7200&soa_retry=3600&soa_expire=1209600"
            + "&enabled=on");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row zone = Models.get(DnsZoneModel.class).findByOrigin("admin-zone.example");
        assertThat(zone).isNotNull();
        assertThat((Boolean) zone.get(DnsZoneModel.ENABLED)).isTrue();
        zoneId = zone.get(DnsZoneModel.ID);

        // Invalid origins are refused with a violation.
        var invalidOrigin = postForm("/admin/dns-zones/new",
            "origin=*.bad-origin&default_ttl=3600&negative_ttl=300"
            + "&soa_refresh=7200&soa_retry=3600&soa_expire=1209600");
        assertThat(invalidOrigin.statusCode()).isEqualTo(200);
        assertThat(invalidOrigin.body()).contains("bare domain");
        assertThat(Models.get(DnsZoneModel.class).findByOrigin("*.bad-origin")).isNull();

        // Duplicate origins are refused too.
        var duplicate = postForm("/admin/dns-zones/new",
            "origin=admin-zone.example&default_ttl=3600&negative_ttl=300"
            + "&soa_refresh=7200&soa_retry=3600&soa_expire=1209600");
        assertThat(duplicate.statusCode()).isEqualTo(200);
        assertThat(duplicate.body()).contains("already exists");

        var bad = postForm("/admin/dns-records/new",
            "zone_id=" + zoneId + "&name=www&type=A&value=not-an-ip");
        assertThat(bad.statusCode()).isEqualTo(200);
        assertThat(bad.body()).contains("IPv4");

        var good = postForm("/admin/dns-records/new",
            "zone_id=" + zoneId + "&name=WWW&type=A&value=192.0.2.10&enabled=on");
        assertThat(good.statusCode()).isIn(200, 302, 303);

        List<Row> records = Models.get(DnsRecordModel.class).findByZoneId(zoneId);
        assertThat(records).hasSize(1);
        Row record = records.get(0);
        assertThat((String) record.get(DnsRecordModel.NAME)).isEqualTo("www");
        recordId = record.get(DnsRecordModel.ID);

        Row savedZone = Models.get(DnsZoneModel.class).find().where(DnsZoneModel.ID.eq(zoneId)).first();
        assertThat((int) savedZone.get(DnsZoneModel.SERIAL)).isGreaterThan(1);

        // CNAME exclusivity is enforced.
        var cname = postForm("/admin/dns-records/new",
            "zone_id=" + zoneId + "&name=www&type=CNAME&value=other.admin-zone.example");
        assertThat(cname.statusCode()).isEqualTo(200);
        assertThat(cname.body()).contains("CNAME");
        assertThat(Models.get(DnsRecordModel.class).findByZoneId(zoneId)).hasSize(1);

        // A CNAME at the zone apex is refused: the SOA (and NS/DNSKEY) live there and are
        // SYNTHESIZED in the serving snapshot, not stored rows, so the sibling scan never
        // sees the conflict -- the exclusivity check has to refuse "@" by name.
        var apexCname = postForm("/admin/dns-records/new",
            "zone_id=" + zoneId + "&name=@&type=CNAME&value=other.admin-zone.example");
        assertThat(apexCname.statusCode()).isEqualTo(200);
        assertThat(apexCname.body()).contains("CNAME");
        assertThat(Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.TYPE.eq(DnsRecordModel.TYPE_CNAME)).first())
            .as("no apex CNAME row was stored").isNull();

        // The zone list carries the sidebar entry and the row link into the records tab.
        navigateToApp("/admin/dns-zones");
        waitForHydration();
        assertThat(page.locator("pl-app-sidebar a[href='/admin/dns-zones']").count()).isEqualTo(1);
        assertThat(page.locator("body").textContent()).contains("DNS Zones");
        assertThat(page.locator(".cms-row-link[href='/admin/dns-zones/" + zoneId
            + "/page/records']").count()).isEqualTo(1);

        navigateToApp("/admin/dns-zones/" + zoneId + "/page/records");
        waitForHydration();
        assertThat(page.locator("body").textContent()).contains("www").contains("192.0.2.10");
        assertThat(page.locator("a[href='/admin/dns-records/" + recordId + "']").count()).isEqualTo(1);
        assertThat(page.locator("#add-record-link").count()).isEqualTo(1);

        navigateToApp("/admin/dns-zones/" + zoneId + "/page/zonefile");
        waitForHydration();
        String exported = page.locator("body").textContent();
        assertThat(exported).contains("$ORIGIN admin-zone.example.");
        assertThat(exported).contains("192.0.2.10");
        assertThat(exported).contains("SOA");

        // Importing a zone file replaces the operator records.
        String zoneText = """
            $ORIGIN admin-zone.example.
            $TTL 3600
            @ 3600 IN NS ns1.admin-zone.example.
            ns1 3600 IN A 192.0.2.1
            mail 3600 IN MX 10 mx.admin-zone.example.
            _svc._tcp 3600 IN SRV 5 0 8443 www.admin-zone.example.
            """;
        var imported = postForm("/admin/dns-zones/" + zoneId + "/zonefile",
            "zone_text=" + URLEncoder.encode(zoneText, StandardCharsets.UTF_8));
        assertThat(imported.statusCode()).isIn(302, 303);

        List<Row> importedRecords = Models.get(DnsRecordModel.class).findByZoneId(zoneId);
        assertThat(importedRecords).hasSize(4);
        assertThat(importedRecords.stream().map(r -> (String) r.get(DnsRecordModel.TYPE)))
            .containsExactlyInAnyOrder("NS", "A", "MX", "SRV");
        Row srv = importedRecords.stream()
            .filter(r -> DnsRecordModel.TYPE_SRV.equals(r.get(DnsRecordModel.TYPE)))
            .findFirst().orElseThrow();
        assertThat((Integer) srv.get(DnsRecordModel.PORT)).isEqualTo(8443);
    }

    /** The certificate-request page only offers hosted DNS when a DNS server is serving. */
    @Test
    @Order(2)
    void certificateRequestOffersHostedDnsOnlyWhenServing() {
        navigateToApp("/admin/certificates-request");
        waitForHydration();
        // Item children portal into the overlay popup at hydration, so the
        // option's disabled state is asserted inside the open popup. No DNS
        // server runs in this test boot, so hosted DNS renders disabled.
        page.click("pl-select[name='dns_mode'] .pl-select-field");
        page.waitForSelector("he-bottom .pl-select-popup[data-open]");
        var internal = page.locator(
            "he-bottom .pl-select-popup[data-open] div[role='option'][data-value='internal']");
        assertThat(internal.count()).isEqualTo(1);
        assertThat(internal.getAttribute("aria-disabled")).isEqualTo("true");
        page.keyboard().press("Escape");
    }
}
