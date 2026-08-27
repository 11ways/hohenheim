package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsPeerApi;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

        // Type-specific fields ride the data sub-schema: an MX without its priority is
        // refused by the codec, and one WITH data.priority saves it into the data map.
        var mxNoPriority = postForm("/admin/dns-records/new",
            "zone_id=" + zoneId + "&name=mail&type=MX&value=mx.admin-zone.example.");
        assertThat(mxNoPriority.statusCode()).isEqualTo(200);
        assertThat(mxNoPriority.body()).contains("priority");
        var mxGood = postForm("/admin/dns-records/new",
            "zone_id=" + zoneId + "&name=mail&type=MX&value=mx.admin-zone.example."
            + "&data.priority=10&enabled=on");
        assertThat(mxGood.statusCode()).isIn(200, 302, 303);
        Row mx = Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.TYPE.eq(DnsRecordModel.TYPE_MX)).first();
        assertThat(mx).as("the MX row persisted").isNotNull();
        assertThat(DnsRecordModel.priorityOf(mx))
            .as("the submitted data.priority landed in the data map").isEqualTo(10);
        Models.get(DnsRecordModel.class).delete(mx);

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
        assertThat(page.locator("body").textContent()).contains("DNS zones");
        assertThat(page.locator(".cms-row-link[href='/admin/dns-zones/" + zoneId
            + "/page/records']").count()).isEqualTo(1);

        navigateToApp("/admin/dns-zones/" + zoneId + "/page/records");
        waitForHydration();
        assertThat(page.locator("body").textContent()).contains("www").contains("192.0.2.10");
        // The tab renders the record RESOURCE's own row surface, so the record is reachable
        // twice by design: the title cell links to it, and so does the synthesized Edit row
        // action beside it (the shape every generated list has). The compact row menu
        // carries a COPY of that action for narrow cards (marked data-cms-lane="compact",
        // revealed by a @container rule while the split lane is hidden); it is a register
        // switch, not a third way to reach the record. Every link carries this tab as its
        // return target (a write's fallback is the GLOBAL record list), hence the prefix match.
        assertThat(page.locator("a[href^='/admin/dns-records/" + recordId
            + "?']:not([data-cms-lane])").count())
            .as("the title link plus the synthesized edit action").isEqualTo(2);
        assertThat(page.locator("a[href^='/admin/dns-records/" + recordId
            + "?'][data-cms-lane='compact']").count())
            .as("and the compact register carries its one copy").isEqualTo(1);
        assertThat(page.locator("cms-inline-cell").count())
            .as("and its editable cells are offered in place").isGreaterThan(0);
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
        assertThat(DnsRecordModel.portOf(srv)).isEqualTo(8443);
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
    /**
     * The zone list's record-count column costs ONE query no matter how many zones it shows.
     *
     * AIDEV-NOTE: this is a SHAPE assertion, not a magic number: the same page is rendered
     * with one zone and then with six, and the DnsRecordModel find count must not move. A
     * per-row COUNT(*) inside cellValue -- what this column used to be -- makes the second
     * number five higher, which is exactly the regression a fixed cap would let through on a
     * small fixture.
     */
    @Test
    @Order(3)
    void theZoneListCountsRecordsWithoutAQueryPerRow() throws Exception {
        var zones = Models.get(DnsZoneModel.class);
        List<Row> extra = new ArrayList<>();
        AtomicInteger finds = new AtomicInteger();
        DnsRecordModel.SCHEMA.addBeforeFindHook(ignored -> finds.incrementAndGet());
        try {
            finds.set(0);
            assertThat(adminGet("/admin/dns-zones").statusCode()).isEqualTo(200);
            int withOneZone = finds.get();

            for (int i = 0; i < 5; i++) {
                Row zone = zones.createEmptyRow();
                zone.set(DnsZoneModel.ORIGIN, "count-" + i + ".example");
                zone.set(DnsZoneModel.ENABLED, true);
                zone.set(DnsZoneModel.ROLE, DnsZoneModel.ROLE_PRIMARY);
                zones.save(zone);
                extra.add(zone);
            }

            finds.set(0);
            assertThat(adminGet("/admin/dns-zones").statusCode()).isEqualTo(200);
            int withSixZones = finds.get();

            assertThat(withSixZones)
                .as("the record-count column does not query per zone (1 zone cost "
                    + withOneZone + ", 6 zones cost " + withSixZones + ")")
                .isEqualTo(withOneZone);
        } finally {
            for (Row zone : extra) {
                zones.delete(zone);
            }
        }
    }

    /**
     * The peer type decides which channel a peer must have complete, which fields its
     * form even asks for, and whether the edit-forwarding client exists at all.
     *
     * AIDEV-NOTE: counterfactual for the last step -- before the type existed, "has a
     * base URL and an API key" WAS the definition of a Hohenheim peer, so a peer whose
     * type says nameserver but whose credential columns are still populated would keep
     * forwarding edits. That is the assertion that fails without DnsPeerModel.isHohenheim.
     */
    @Test
    @Order(4)
    void dnsPeerTypeDrivesTheFormAndItsValidation() throws Exception {
        DnsPeerModel peers = Models.get(DnsPeerModel.class);

        // 1. A Hohenheim peer without admin credentials is refused.
        var noCredentials = postForm("/admin/dns-peers/new",
            "name=peer-incomplete&peer_type=hohenheim&transfer_host=&transfer_port="
            + "&tsig_key_name=&tsig_algorithm=&tsig_secret=&base_url=&api_key=&enabled=on");
        assertThat(noCredentials.statusCode()).isEqualTo(200);
        assertThat(noCredentials.body()).contains("admin base URL");
        assertThat(peers.findByName("peer-incomplete")).isNull();

        // 2. A plain nameserver peer with no transfer host is refused too: nothing here
        //    could ever reach it.
        var noHost = postForm("/admin/dns-peers/new",
            "name=peer-hostless&peer_type=nameserver&transfer_host=&transfer_port="
            + "&tsig_key_name=&tsig_algorithm=&tsig_secret=&base_url=&api_key=&enabled=on");
        assertThat(noHost.statusCode()).isEqualTo(200);
        assertThat(noHost.body()).contains("transfer host");
        assertThat(peers.findByName("peer-hostless")).isNull();

        // 3. Both complete shapes are accepted.
        assertThat(postForm("/admin/dns-peers/new",
            "name=peer-hohenheim&peer_type=hohenheim&transfer_host=ns1.peer.example"
            + "&transfer_port=53&tsig_key_name=&tsig_algorithm=&tsig_secret="
            + "&base_url=https%3A%2F%2Fpeer.example&api_key=znit_secret&enabled=on")
            .statusCode()).isIn(200, 302, 303);
        assertThat(postForm("/admin/dns-peers/new",
            "name=peer-nameserver&peer_type=nameserver&transfer_host=ns1.other.example"
            + "&transfer_port=53&tsig_key_name=&tsig_algorithm=&tsig_secret="
            + "&base_url=&api_key=&enabled=on")
            .statusCode()).isIn(200, 302, 303);

        Row hohenheimPeer = peers.findByName("peer-hohenheim");
        Row nameserverPeer = peers.findByName("peer-nameserver");
        assertThat(hohenheimPeer).isNotNull();
        assertThat(nameserverPeer).isNotNull();
        assertThat(DnsPeerModel.isHohenheim(hohenheimPeer)).isTrue();
        assertThat(DnsPeerModel.isHohenheim(nameserverPeer)).isFalse();

        // 4. The forwarding credentials cannot be written onto a nameserver peer: the
        //    field binding strips them from the submit.
        //    AIDEV-NOTE: this asserts the WRITE, not the render. The form renderer
        //    resolves field access without the record, so the inputs still appear on the
        //    page; the type is enforced when the form comes back.
        int nameserverId = nameserverPeer.get(DnsPeerModel.ID);
        assertThat(postForm("/admin/dns-peers/" + nameserverId,
            "name=peer-nameserver&peer_type=nameserver&transfer_host=ns1.other.example"
            + "&transfer_port=53&tsig_key_name=&tsig_algorithm=&tsig_secret="
            + "&base_url=https%3A%2F%2Fsmuggled.example&api_key=znit_smuggled&enabled=on")
            .statusCode()).isIn(200, 302, 303);
        Row afterSmuggle = peers.findById(nameserverId);
        assertThat((String) afterSmuggle.get(DnsPeerModel.BASE_URL))
            .describedAs("a nameserver peer never gains edit-forwarding credentials")
            .isNullOrEmpty();
        assertThat((String) afterSmuggle.get(DnsPeerModel.API_KEY)).isNullOrEmpty();

        // 5. The edit-forwarding client follows the TYPE, not the leftover columns.
        assertThat(DnsPeerApi.forPeer(hohenheimPeer)).isNotNull();
        assertThat(DnsPeerApi.forPeer(nameserverPeer)).isNull();
        hohenheimPeer.set(DnsPeerModel.PEER_TYPE, DnsPeerModel.TYPE_NAMESERVER);
        peers.save(hohenheimPeer);
        assertThat(DnsPeerApi.forPeer(peers.findByName("peer-hohenheim")))
            .describedAs("demoting a peer closes the channel even with credentials stored")
            .isNull();
    }
}
