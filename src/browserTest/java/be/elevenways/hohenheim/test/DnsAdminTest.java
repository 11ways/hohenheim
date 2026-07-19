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

    @Test
    @Order(1)
    void zonesAppearInTheSidebarAndListRenders() {
        navigateToApp("/admin/dns-zones");
        waitForHydration();
        assertThat(page.locator("pl-app-sidebar a[href='/admin/dns-zones']").count()).isEqualTo(1);
        assertThat(page.locator("body").textContent()).contains("DNS Zones");

        navigateToApp("/admin/dns-zones/new");
        waitForHydration();
        assertThat(page.locator("body").textContent())
            .contains("Zone origin")
            .contains("The domain this zone is authoritative for")
            .contains("Role")
            .contains("Primary zones are edited here")
            .contains("Owning peer");
    }

    @Test
    @Order(2)
    void creatingAZoneNormalizesTheOrigin() throws Exception {
        var response = postForm("/admin/dns-zones/new",
            "origin=Admin-Zone.Example.&soa_primary_ns=&soa_contact="
            + "&default_ttl=3600&negative_ttl=300&soa_refresh=7200&soa_retry=3600&soa_expire=1209600"
            + "&enabled=on");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row zone = Models.get(DnsZoneModel.class).findByOrigin("admin-zone.example");
        assertThat(zone).isNotNull();
        assertThat((Boolean) zone.get(DnsZoneModel.ENABLED)).isTrue();
        zoneId = zone.get(DnsZoneModel.ID);
    }

    @Test
    @Order(3)
    void invalidOriginsAreRefusedWithAViolation() throws Exception {
        var response = postForm("/admin/dns-zones/new",
            "origin=*.bad-origin&default_ttl=3600&negative_ttl=300"
            + "&soa_refresh=7200&soa_retry=3600&soa_expire=1209600");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("bare domain");
        assertThat(Models.get(DnsZoneModel.class).findByOrigin("*.bad-origin")).isNull();
    }

    @Test
    @Order(4)
    void duplicateOriginsAreRefused() throws Exception {
        var response = postForm("/admin/dns-zones/new",
            "origin=admin-zone.example&default_ttl=3600&negative_ttl=300"
            + "&soa_refresh=7200&soa_retry=3600&soa_expire=1209600");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("already exists");
    }

    @Test
    @Order(5)
    void recordsCreateThroughTheCodecValidation() throws Exception {
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

        Row zone = Models.get(DnsZoneModel.class).find().where(DnsZoneModel.ID.eq(zoneId)).first();
        assertThat((int) zone.get(DnsZoneModel.SERIAL)).isGreaterThan(1);
    }

    @Test
    @Order(6)
    void cnameExclusivityIsEnforced() throws Exception {
        var response = postForm("/admin/dns-records/new",
            "zone_id=" + zoneId + "&name=www&type=CNAME&value=other.admin-zone.example");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("CNAME");
        assertThat(Models.get(DnsRecordModel.class).findByZoneId(zoneId)).hasSize(1);
    }

    @Test
    @Order(7)
    void theRecordsTabListsRecordsWithEditLinks() {
        navigateToApp("/admin/dns-zones");
        waitForHydration();
        assertThat(page.locator(".cms-row-link[href='/admin/dns-zones/" + zoneId
            + "/page/records']").count()).isEqualTo(1);

        navigateToApp("/admin/dns-zones/" + zoneId + "/page/records");
        waitForHydration();
        assertThat(page.locator("body").textContent()).contains("www").contains("192.0.2.10");
        assertThat(page.locator("a[href='/admin/dns-records/" + recordId + "']").count()).isEqualTo(1);
        assertThat(page.locator("#add-record-link").count()).isEqualTo(1);
    }

    @Test
    @Order(8)
    void theZoneFileTabExportsTheZone() {
        navigateToApp("/admin/dns-zones/" + zoneId + "/page/zonefile");
        waitForHydration();
        String body = page.locator("body").textContent();
        assertThat(body).contains("$ORIGIN admin-zone.example.");
        assertThat(body).contains("192.0.2.10");
        assertThat(body).contains("SOA");
    }

    @Test
    @Order(9)
    void importingAZoneFileReplacesOperatorRecords() throws Exception {
        String zoneText = """
            @ 3600 IN NS ns1.admin-zone.example.
            ns1 3600 IN A 192.0.2.1
            mail 3600 IN MX 10 mx.admin-zone.example.
            _svc._tcp 3600 IN SRV 5 0 8443 www.admin-zone.example.
            """;
        var response = postForm("/admin/dns-zones/" + zoneId + "/zonefile",
            "zone_text=" + URLEncoder.encode(zoneText, StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isIn(302, 303);

        List<Row> records = Models.get(DnsRecordModel.class).findByZoneId(zoneId);
        assertThat(records).hasSize(4);
        assertThat(records.stream().map(r -> (String) r.get(DnsRecordModel.TYPE)))
            .containsExactlyInAnyOrder("NS", "A", "MX", "SRV");
        Row srv = records.stream()
            .filter(r -> DnsRecordModel.TYPE_SRV.equals(r.get(DnsRecordModel.TYPE)))
            .findFirst().orElseThrow();
        assertThat((Integer) srv.get(DnsRecordModel.PORT)).isEqualTo(8443);
    }

    @Test
    @Order(10)
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
