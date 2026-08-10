package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.hohenheim.server.dns.DynamicDnsService.Status;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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
    void tokenIsStoredHashedNotInPlaintext() {
        // The token is a bearer credential, so only its digest is at rest: a DB read
        // cannot recover a working token. The client still presents the plaintext,
        // which hashes to the stored digest, so authentication is unaffected.
        String token = seedDynamicRecord("dyn-store.example", "home", DnsRecordModel.TYPE_A, "192.0.2.1");
        int zoneId = Models.get(DnsZoneModel.class).findByOrigin("dyn-store.example").get(DnsZoneModel.ID);
        Row record = Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId)).first();
        String stored = record.get(DnsRecordModel.DYNDNS_TOKEN);
        assertThat(token).as("the minted token is a plaintext hdyn_ token").startsWith("hdyn_");
        assertThat(stored)
            .as("the stored value is a digest, never the recoverable plaintext")
            .startsWith("sha256:")
            .isEqualTo(DynamicDnsService.digest(token))
            .doesNotContain(token);
        // The digest still admits the plaintext its holder presents.
        assertThat(service.update(token, null, "203.0.113.8", null).status()).isEqualTo(Status.GOOD);
    }

    @Test
    void existingPlaintextTokenSurvivesTheHashingSweep() {
        // A record configured BEFORE hashing holds its token in plaintext -- the shape
        // every existing install has on disk. bypassBehaviours skips the write hook,
        // the only way to reproduce it now.
        String token = seedDynamicRecord("dyn-sweep.example", "home", DnsRecordModel.TYPE_A, "192.0.2.1");
        int zoneId = Models.get(DnsZoneModel.class).findByOrigin("dyn-sweep.example").get(DnsZoneModel.ID);
        int recordId = Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId)).first().get(DnsRecordModel.ID);
        Models.get(DnsRecordModel.class).find().where(DnsRecordModel.ID.eq(recordId))
            .assign(DnsRecordModel.DYNDNS_TOKEN, token).bypassBehaviours().updateAll();
        assertThat(Models.get(DnsRecordModel.class).findById(recordId).get(DnsRecordModel.DYNDNS_TOKEN))
            .as("the pre-migration datasource holds the token in plaintext").isEqualTo(token);

        // The sweep rewrites the plaintext to its digest, in place, and is a no-op on rerun.
        assertThat(DynamicDnsService.hashStoredTokens())
            .as("the sweep rewrites exactly the one record holding plaintext").isGreaterThanOrEqualTo(1);
        String stored = Models.get(DnsRecordModel.class).findById(recordId).get(DnsRecordModel.DYNDNS_TOKEN);
        assertThat(stored).as("the sweep replaces the plaintext with its digest")
            .isEqualTo(DynamicDnsService.digest(token)).doesNotContain(token);

        // The configured client keeps working: the same plaintext still authenticates.
        assertThat(service.update(token, null, "203.0.113.9", null).status())
            .as("an existing token still authenticates after the sweep").isEqualTo(Status.GOOD);
        // A wrong token is refused.
        assertThat(service.update("hdyn_wrongtoken00.nope", null, "203.0.113.9", null).status())
            .isEqualTo(Status.BADAUTH);
    }

    @Test
    void operatorTypedTokensMustBeMintedShapeAndStrong() {
        seedDynamicRecord("dyn-adopt.example", "home", DnsRecordModel.TYPE_A, "192.0.2.1");
        DnsRecordModel records = Models.get(DnsRecordModel.class);
        int zoneId = Models.get(DnsZoneModel.class).findByOrigin("dyn-adopt.example").get(DnsZoneModel.ID);
        Row record = records.find().where(DnsRecordModel.ZONE_ID.eq(zoneId)).first();
        String storedDigest = record.get(DnsRecordModel.DYNDNS_TOKEN);

        // 1. A weak operator-typed value is refused with the specific violation:
        //    its fast digest would be dictionary-recoverable from a copied DB.
        record.set(DnsRecordModel.DYNDNS_TOKEN, "my-router-password");
        Violations weak = catchThrowableOfType(() -> records.save(record), Violations.class);
        assertThat((Object) weak)
            .as("1. adopting a weak dyndns token must throw Violations").isNotNull();
        Violation violation = weak.all().get(0);
        assertThat(violation.fieldName())
            .as("1. the violation targets the dyndns_token field")
            .isEqualTo("dyndns_token");
        assertThat(violation.message().key())
            .as("1. the violation carries the weak_dyndns_token message")
            .isEqualTo("weak_dyndns_token");
        assertThat(violation.value())
            .as("1. the violation must never echo the credential")
            .isEqualTo("");

        // 2. A strong value WITHOUT the hdyn_ marker is refused too: authenticate()
        //    requires the marker, so storing it would be silently dead weight.
        record.set(DnsRecordModel.DYNDNS_TOKEN, "markerless-0123456789abcdefghijklmnopqrs");
        assertThatThrownBy(() -> records.save(record))
            .as("2. a markerless token can never authenticate and must be refused")
            .isInstanceOf(Violations.class)
            .hasMessageContaining("weak_dyndns_token");

        // 3. A marker-shaped strong value is adopted (hashed) and the stored value
        //    it replaced is untouched by the refused attempts.
        assertThat(Models.get(DnsRecordModel.class).findById(record.get(DnsRecordModel.ID))
            .get(DnsRecordModel.DYNDNS_TOKEN))
            .as("3. the refused saves must leave the stored digest untouched")
            .isEqualTo(storedDigest);
        String adopted = "hdyn_operator-0123456789abcdefghijklmn";
        Row fresh = Models.get(DnsRecordModel.class).findById(record.get(DnsRecordModel.ID));
        fresh.set(DnsRecordModel.DYNDNS_TOKEN, adopted);
        records.save(fresh);
        assertThat(Models.get(DnsRecordModel.class).findById(record.get(DnsRecordModel.ID))
            .get(DnsRecordModel.DYNDNS_TOKEN))
            .as("3. a strong hdyn_ token must be adopted as its digest")
            .isEqualTo(DynamicDnsService.digest(adopted));
    }

    @Test
    void sha256PrefixedLegacyPlaintextIsSweptNotMisclassified() {
        // A legacy plaintext token that happens to start with "sha256:" must be
        // hashed by the sweep like any other plaintext, never treated as already
        // migrated because of its prefix.
        seedDynamicRecord("dyn-tricky.example", "home", DnsRecordModel.TYPE_A, "192.0.2.1");
        int zoneId = Models.get(DnsZoneModel.class).findByOrigin("dyn-tricky.example").get(DnsZoneModel.ID);
        int recordId = Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId)).first().get(DnsRecordModel.ID);
        String tricky = "sha256:legacy-router-token";
        Models.get(DnsRecordModel.class).find().where(DnsRecordModel.ID.eq(recordId))
            .assign(DnsRecordModel.DYNDNS_TOKEN, tricky).bypassBehaviours().updateAll();

        assertThat(DynamicDnsService.hashStoredTokens())
            .as("the sweep must rewrite the sha256:-prefixed plaintext token")
            .isGreaterThanOrEqualTo(1);
        assertThat(Models.get(DnsRecordModel.class).findById(recordId).get(DnsRecordModel.DYNDNS_TOKEN))
            .as("the stored value must become the full digest of the tricky plaintext")
            .isEqualTo(DynamicDnsService.digest(tricky))
            .doesNotContain("legacy-router-token");
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
