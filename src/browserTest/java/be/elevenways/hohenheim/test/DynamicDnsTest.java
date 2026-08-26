package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsDyndnsCredentialModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.hohenheim.server.dns.DynamicDnsService.Status;
import be.elevenways.hohenheim.server.cms.DnsRecordResource;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.microcopy.server.DefaultCatalogLoader;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dynamic DNS (dyndns2): credential minting/revocation, the update state machine
 * (good / nochg / badauth / nohost / family / secondary), and the public
 * /nic/update route over real HTTP with the token in HTTP Basic auth.
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

    /** Journey: mint stores only the digest, a re-mint rotates, and revoke kills the token. */
    @Test
    void credentialLifecycleStoresDigestsRotatesAndRevokes() {
        String token = seedDynamicRecord("dyn-store.example", "home", DnsRecordModel.TYPE_A, "192.0.2.1");
        int recordId = recordId("dyn-store.example", "home");

        // 1. The token is a bearer credential, so only its digest is at rest: a DB read
        //    cannot recover a working token. The client still presents the plaintext,
        //    which hashes to the stored digest, so authentication is unaffected.
        Row credential = DynamicDnsService.credentialFor(recordId);
        assertThat(credential).as("1. minting created the credential row").isNotNull();
        String stored = credential.get(DnsDyndnsCredentialModel.TOKEN_DIGEST);
        assertThat(token).as("1. the minted token is a plaintext hdyn_ token").startsWith("hdyn_");
        assertThat(stored)
            .as("1. the stored value is a digest, never the recoverable plaintext")
            .startsWith("sha256:")
            .isEqualTo(DynamicDnsService.digest(token))
            .doesNotContain(token);
        assertThat(service.update(token, null, "203.0.113.8", null).status())
            .as("1. the digest still admits the plaintext its holder presents")
            .isEqualTo(Status.GOOD);

        // 2. A re-mint ROTATES: one credential row per record, the old token dies.
        String rotated = DynamicDnsService.mintFor(recordId);
        assertThat(Models.get(DnsDyndnsCredentialModel.class).find()
            .where(DnsDyndnsCredentialModel.RECORD_ID.eq(recordId)).all())
            .as("2. re-minting keeps ONE credential row").hasSize(1);
        assertThat(service.update(token, null, "203.0.113.9", null).status())
            .as("2. the previous token no longer authenticates").isEqualTo(Status.BADAUTH);
        assertThat(service.update(rotated, null, "203.0.113.9", null).status())
            .as("2. the rotated token does").isEqualTo(Status.GOOD);

        // 3. Revoke deletes the row; the token dies with it.
        assertThat(DynamicDnsService.revokeFor(recordId))
            .as("3. revoke reports the credential it deleted").isTrue();
        assertThat(DynamicDnsService.credentialFor(recordId))
            .as("3. the credential row is gone").isNull();
        assertThat(service.update(rotated, null, "203.0.113.10", null).status())
            .as("3. a revoked token answers badauth").isEqualTo(Status.BADAUTH);
    }

    /** The credential dies with its RECORD too, whatever deletes the record. */
    @Test
    void credentialDiesWithItsRecord() {
        String token = seedDynamicRecord("dyn-cascade.example", "home", DnsRecordModel.TYPE_A, "192.0.2.1");
        int recordId = recordId("dyn-cascade.example", "home");
        assertThat(DynamicDnsService.credentialFor(recordId)).isNotNull();

        DnsRecordModel records = Models.get(DnsRecordModel.class);
        records.delete(records.findById(recordId));

        assertThat(DynamicDnsService.credentialFor(recordId))
            .as("deleting the record cascades onto its credential").isNull();
        assertThat(service.update(token, null, "203.0.113.11", null).status())
            .as("the orphaned token answers badauth").isEqualTo(Status.BADAUTH);
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

        // The valid token presented ONLY in the query string is rejected: a DNS-write
        // credential must never travel in the URL (logs, Referer). Same token that just
        // succeeded over Basic auth, now with no Authorization header at all.
        HttpResponse<String> queryToken = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort()
                + "/nic/update?hostname=home.dyn-http.example&myip=203.0.113.66&token="
                + token))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(queryToken.statusCode()).isEqualTo(200);
        assertThat(queryToken.body().trim())
            .as("the ?token= query fallback is gone; the credential must ride Basic auth")
            .isEqualTo("badauth");
        assertThat(recordValue("dyn-http.example", "home"))
            .as("and the query-token attempt changed nothing")
            .isEqualTo("203.0.113.55");
    }

    /**
     * The mint action's hint names THIS installation's update URL, in every locale.
     *
     * AIDEV-NOTE: it used to read "point a dyndns2 client at &lt;host&gt;/nic/update", a
     * literal placeholder the operator was expected to translate into their own hostname.
     * The URL is now an argument resolved from {@code network.main_url} -- the declared
     * home of this installation's public URL -- and the path from the endpoint itself.
     */
    @Test
    void theDyndnsHintNamesThisInstallationsUpdateUrl() {

        String before = ServerSettings.VALUES.getValue(ServerSettings.Network.MAIN_URL);

        try {
            // 1. Configured: the description carries the absolute URL a router is pointed at.
            ServerSettings.VALUES.setValue(ServerSettings.Network.MAIN_URL,
                "https://panel.example.test/");
            assertThat(dyndnsHint("en"))
                .as("step 1: the operator reads the URL of the machine they are looking at")
                .contains("https://panel.example.test/nic/update");

            // 2. And the placeholder nobody could act on is gone, in BOTH locales.
            assertThat(dyndnsHint("en"))
                .as("step 2: no literal placeholder survives in English")
                .doesNotContain("<host>");
            assertThat(dyndnsHint("nl"))
                .as("step 2: nor in Dutch, which carries the same argument")
                .contains("https://panel.example.test/nic/update")
                .doesNotContain("<host>");

            // 3. FALSIFIED: with no main_url the hint degrades to the endpoint's own path
            //    rather than fabricating a host out of whatever header arrived.
            ServerSettings.VALUES.setValue(ServerSettings.Network.MAIN_URL, null);
            assertThat(dyndnsHint("en"))
                .as("step 3: the path alone is still true; a guessed host would not be")
                .contains("/nic/update")
                .doesNotContain("<host>", "https://panel.example.test");
        } finally {
            ServerSettings.VALUES.setValue(ServerSettings.Network.MAIN_URL, before);
        }
    }

    /** The dyndns token row action's description, resolved in one locale. */
    private static String dyndnsHint(String tag) {

        Microcopy description = new DnsRecordResource().rowActions().stream()
            .filter(action -> action.id().equals(Identifier.of("hohenheim", "dyndns_token")))
            .findFirst()
            .orElseThrow()
            .description();

        assertThat(description).as("the mint action declares a description").isNotNull();
        return description.resolve(LocaleChain.ofTags(tag), new DefaultCatalogLoader());
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

        DnsRecordModel records = Models.get(DnsRecordModel.class);
        Row record = records.createEmptyRow();
        record.set(DnsRecordModel.ZONE_ID, zone.get(DnsZoneModel.ID));
        record.set(DnsRecordModel.NAME, name);
        record.set(DnsRecordModel.TYPE, type);
        record.set(DnsRecordModel.VALUE, value);
        record.set(DnsRecordModel.ENABLED, true);
        records.save(record);
        String token = DynamicDnsService.mintFor(record.get(DnsRecordModel.ID));
        DnsZoneStore.INSTANCE.reload();
        return token;
    }

    private static int recordId(String origin, String name) {
        int zoneId = Models.get(DnsZoneModel.class).findByOrigin(origin).get(DnsZoneModel.ID);
        return Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.NAME.eq(name))
            .first().get(DnsRecordModel.ID);
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
