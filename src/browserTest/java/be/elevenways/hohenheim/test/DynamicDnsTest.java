package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.hohenheim.server.dns.DynamicDnsService.Status;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dynamic DNS (dyndns2): token minting, the update state machine (good / nochg
 * / badauth / nohost / family / secondary), and the public /nic/update route
 * over real HTTP with the token in HTTP Basic auth.
 */
class DynamicDnsTest extends HohenheimTestBase {

    private final DynamicDnsService service = new DynamicDnsService(DnsZoneStore.INSTANCE);

    @Test
    void updatesAndReportsNochgWhenUnchanged() {
        String token = seedDynamicRecord("dyn-a.example", "home", DnsRecordModel.TYPE_A, "192.0.2.1");
        long before = serialOf("dyn-a.example");

        DynamicDnsService.UpdateResult first = service.update(token, null, "203.0.113.7", "10.0.0.9");
        assertThat(first.status()).isEqualTo(Status.GOOD);
        assertThat(first.ip()).isEqualTo("203.0.113.7");
        assertThat(recordValue("dyn-a.example", "home")).isEqualTo("203.0.113.7");
        assertThat(serialOf("dyn-a.example")).isGreaterThan(before);

        // A no-op update must NOT churn the serial (routers poll constantly).
        long afterFirst = serialOf("dyn-a.example");
        DynamicDnsService.UpdateResult again = service.update(token, null, "203.0.113.7", "10.0.0.9");
        assertThat(again.status()).isEqualTo(Status.NOCHG);
        assertThat(serialOf("dyn-a.example")).isEqualTo(afterFirst);
    }

    @Test
    void fallsBackToTheCallerIpWhenNoMyip() {
        String token = seedDynamicRecord("dyn-caller.example", "home", DnsRecordModel.TYPE_A, "192.0.2.1");
        DynamicDnsService.UpdateResult result = service.update(token, null, null, "198.51.100.42");
        assertThat(result.status()).isEqualTo(Status.GOOD);
        assertThat(result.ip()).isEqualTo("198.51.100.42");
    }

    @Test
    void tokenIsStoredRetrievablyNotHashed() {
        // The operator must be able to re-read the update token any time (it
        // lives in a router config), so it is stored as-is, not as a digest.
        String token = seedDynamicRecord("dyn-store.example", "home", DnsRecordModel.TYPE_A, "192.0.2.1");
        int zoneId = Models.get(DnsZoneModel.class).findByOrigin("dyn-store.example").get(DnsZoneModel.ID);
        Row record = Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId)).first();
        assertThat(record.get(DnsRecordModel.DYNDNS_TOKEN)).isEqualTo(token);
        assertThat(token).startsWith("hdyn_");
    }

    @Test
    void wrongTokenIsBadauth() {
        seedDynamicRecord("dyn-bad.example", "home", DnsRecordModel.TYPE_A, "192.0.2.1");
        assertThat(service.update("hdyn_deadbeef01.nope", null, "203.0.113.1", null).status())
            .isEqualTo(Status.BADAUTH);
        assertThat(service.update(null, null, "203.0.113.1", null).status()).isEqualTo(Status.BADAUTH);
    }

    @Test
    void hostnameMismatchIsNohost() {
        String token = seedDynamicRecord("dyn-host.example", "home", DnsRecordModel.TYPE_A, "192.0.2.1");
        assertThat(service.update(token, "elsewhere.example", "203.0.113.1", null).status())
            .isEqualTo(Status.NOHOST);
        // The exact FQDN is accepted (trailing dot tolerated).
        assertThat(service.update(token, "home.dyn-host.example.", "203.0.113.5", null).status())
            .isEqualTo(Status.GOOD);
    }

    @Test
    void ipFamilyMustMatchTheRecordType() {
        String aToken = seedDynamicRecord("dyn-fam.example", "home", DnsRecordModel.TYPE_A, "192.0.2.1");
        // An IPv6 myip on an A record has no usable address of the record's family.
        assertThat(service.update(aToken, null, "2001:db8::1", null).status()).isEqualTo(Status.DNSERR);

        String aaaaToken = seedDynamicRecord("dyn-fam6.example", "home", DnsRecordModel.TYPE_AAAA, "2001:db8::1");
        DynamicDnsService.UpdateResult v6 = service.update(aaaaToken, null, "2001:db8::99", null);
        assertThat(v6.status()).isEqualTo(Status.GOOD);
        assertThat(v6.ip()).isEqualTo("2001:db8:0:0:0:0:0:99");
        // A dual-stack client can send both; the AAAA record picks the v6 one.
        String aaaaToken2 = seedDynamicRecord("dyn-dual.example", "home", DnsRecordModel.TYPE_AAAA, "2001:db8::1");
        assertThat(service.update(aaaaToken2, null, "203.0.113.4,2001:db8::7", null).ip())
            .isEqualTo("2001:db8:0:0:0:0:0:7");
    }

    @Test
    void secondaryZoneRefusesUpdates() {
        String token = seedDynamicRecord("dyn-sec.example", "home", DnsRecordModel.TYPE_A, "192.0.2.1");
        Row zone = Models.get(DnsZoneModel.class).findByOrigin("dyn-sec.example");
        zone.set(DnsZoneModel.ROLE, DnsZoneModel.ROLE_SECONDARY);
        Models.get(DnsZoneModel.class).save(zone);

        assertThat(service.update(token, null, "203.0.113.1", null).status()).isEqualTo(Status.NOTPRIMARY);
    }

    @Test
    void publicNicUpdateRouteWorksWithBasicAuth() throws Exception {
        String token = seedDynamicRecord("dyn-http.example", "home", DnsRecordModel.TYPE_A, "192.0.2.1");

        // No session cookie: the endpoint is public and authenticates by token.
        String basic = Base64.getEncoder().encodeToString(
            ("dyndns:" + token).getBytes(StandardCharsets.UTF_8));
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort()
                + "/nic/update?hostname=home.dyn-http.example&myip=203.0.113.55"))
            .header("Authorization", "Basic " + basic)
            .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().trim()).isEqualTo("good 203.0.113.55");
        assertThat(recordValue("dyn-http.example", "home")).isEqualTo("203.0.113.55");

        // A bogus token over the same public route is badauth, never a redirect to login.
        String badBasic = Base64.getEncoder().encodeToString(
            "dyndns:hdyn_000000000a.x".getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> bad = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + "/nic/update"))
            .header("Authorization", "Basic " + badBasic)
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(bad.statusCode()).isEqualTo(200);
        assertThat(bad.body().trim()).isEqualTo("badauth");
    }

    // ------------------------------------------------------------------

    private String seedDynamicRecord(String origin, String name, String type, String value) {
        DnsZoneModel zones = Models.get(DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, origin);
        zone.set(DnsZoneModel.SOA_PRIMARY_NS, "ns1." + origin);
        zone.set(DnsZoneModel.SOA_CONTACT, "hostmaster@" + origin);
        zone.set(DnsZoneModel.ENABLED, true);
        zones.save(zone);

        String token = DynamicDnsService.mintToken();
        DnsRecordModel records = Models.get(DnsRecordModel.class);
        Row record = records.createEmptyRow();
        record.set(DnsRecordModel.ZONE_ID, zone.get(DnsZoneModel.ID));
        record.set(DnsRecordModel.NAME, name);
        record.set(DnsRecordModel.TYPE, type);
        record.set(DnsRecordModel.VALUE, value);
        record.set(DnsRecordModel.ENABLED, true);
        record.set(DnsRecordModel.DYNAMIC, true);
        record.set(DnsRecordModel.DYNDNS_TOKEN, token);
        records.save(record);
        DnsZoneStore.INSTANCE.reload();
        return token;
    }

    private static long serialOf(String origin) {
        Integer serial = Models.get(DnsZoneModel.class).findByOrigin(origin).get(DnsZoneModel.SERIAL);
        return serial != null ? serial : 0;
    }

    private static String recordValue(String origin, String name) {
        int zoneId = Models.get(DnsZoneModel.class).findByOrigin(origin).get(DnsZoneModel.ID);
        return Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.NAME.eq(name))
            .first().get(DnsRecordModel.VALUE);
    }
}
