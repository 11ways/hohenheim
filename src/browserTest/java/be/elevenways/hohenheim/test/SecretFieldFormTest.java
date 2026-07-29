package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stored-secret contract on a hohenheim bearer credential, walked over the
 * real admin routes: a {@code secret()} field is never echoed into the rendered
 * form, a blank submit keeps the stored value, an explicit value replaces it,
 * the {@code __clear} companion empties it, and the mint row action is the ONE
 * disclosure of a freshly generated token.
 */
class SecretFieldFormTest extends HohenheimTestBase {

    private static final String STORED_TOKEN = "hdyn_secretform01.originaltokenvalue";

    private String baseUrl() {
        return "http://localhost:" + getServerPort();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client().send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .GET()
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client().send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Test
    void secretFieldSurvivesABlankSubmitAndIsNeverEchoed() throws Exception {
        int recordId = seedRecord();
        String editPath = "/admin/dns-records/" + recordId;

        // 1. The stored bearer credential is HASHED at rest: the seed wrote the
        //    plaintext, the write hook stored only its digest.
        String storedDigest = DynamicDnsService.digest(STORED_TOKEN);
        assertThat(tokenOf(recordId))
            .as("1. the seeded record must hold the digest, never the recoverable plaintext")
            .isEqualTo(storedDigest);

        // 2. The edit form still OFFERS the field, but echoes NEITHER plaintext nor digest.
        HttpResponse<String> form = get(editPath);
        assertThat(form.statusCode()).as("2. the edit form must render").isEqualTo(200);
        assertThat(form.body())
            .as("2. the form must still offer the dyndns_token entry")
            .contains("dyndns_token");
        assertThat(form.body())
            .as("2. a secret() field must NEVER echo the plaintext OR its digest into the form")
            .doesNotContain(STORED_TOKEN)
            .doesNotContain(storedDigest);

        // 3. A submit that leaves the secret blank KEEPS the stored value.
        HttpResponse<String> blank = post(editPath, recordBody(""));
        assertThat(blank.statusCode()).as("3. the blank submit must be accepted").isIn(200, 302, 303);
        assertThat(tokenOf(recordId))
            .as("3. a blank secret submit must keep the stored value, not wipe it")
            .isEqualTo(storedDigest);

        // 4. A submitted value REPLACES the stored one and is itself hashed at rest.
        String replacement = "hdyn_secretform02.replacementtokenvalue";
        HttpResponse<String> replaced = post(editPath, recordBody(replacement));
        assertThat(replaced.statusCode()).as("4. the replacing submit must be accepted").isIn(200, 302, 303);
        assertThat(tokenOf(recordId))
            .as("4. a non-blank secret submit must replace the stored value, hashed")
            .isEqualTo(DynamicDnsService.digest(replacement));

        // 5. The __clear companion is the one way to EMPTY a secret.
        HttpResponse<String> cleared = post(editPath, recordBody("") + "&dyndns_token__clear=true");
        assertThat(cleared.statusCode()).as("5. the clearing submit must be accepted").isIn(200, 302, 303);
        assertThat(tokenOf(recordId))
            .as("5. the __clear companion must empty the stored secret")
            .isNull();

        // 6. Minting discloses the PLAINTEXT token ONCE in the flash toast, while the
        //    record stores only its digest.
        HttpResponse<String> mint = post(editPath + "/action/dyndns_token", "");
        assertThat(mint.statusCode()).as("6. the mint row action must be accepted").isIn(200, 302, 303);

        HttpResponse<String> afterMint = get(editPath);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("hdyn_[A-Za-z0-9._-]+")
            .matcher(afterMint.body());
        assertThat(m.find()).as("6. the mint toast must disclose the plaintext token once").isTrue();
        String disclosed = m.group();
        assertThat(tokenOf(recordId))
            .as("6. the record must store only the digest of the minted token")
            .isEqualTo(DynamicDnsService.digest(disclosed))
            .doesNotContain(disclosed);

        // 7. The disclosure is consumed: a reload shows the record without the token.
        HttpResponse<String> reload = get(editPath);
        assertThat(reload.body())
            .as("7. a reload must not re-disclose the minted token")
            .doesNotContain(disclosed);
    }

    // ------------------------------------------------------------------

    private static String tokenOf(int recordId) {
        return Models.get(DnsRecordModel.class).findById(recordId).get(DnsRecordModel.DYNDNS_TOKEN);
    }

    /** The full record form body; only the secret entry varies. */
    private String recordBody(String token) {
        Row record = Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.NAME.eq("home")).first();
        return "zone_id=" + record.get(DnsRecordModel.ZONE_ID)
            + "&name=home&type=A&value=192.0.2.10&ttl=3600&enabled=true&dynamic=true"
            + "&dyndns_token=" + token;
    }

    private int seedRecord() {
        DnsZoneModel zones = Models.get(DnsZoneModel.class);
        Row zone = zones.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, "secret-form.example");
        zone.set(DnsZoneModel.SOA_PRIMARY_NS, "ns1.secret-form.example");
        zone.set(DnsZoneModel.SOA_CONTACT, "hostmaster@secret-form.example");
        zone.set(DnsZoneModel.ENABLED, true);
        zones.save(zone);

        DnsRecordModel records = Models.get(DnsRecordModel.class);
        Row record = records.createEmptyRow();
        record.set(DnsRecordModel.ZONE_ID, zone.get(DnsZoneModel.ID));
        record.set(DnsRecordModel.NAME, "home");
        record.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_A);
        record.set(DnsRecordModel.VALUE, "192.0.2.10");
        record.set(DnsRecordModel.TTL, 3600);
        record.set(DnsRecordModel.ENABLED, true);
        record.set(DnsRecordModel.DYNAMIC, true);
        record.set(DnsRecordModel.DYNDNS_TOKEN, STORED_TOKEN);
        records.save(record);
        return record.get(DnsRecordModel.ID);
    }
}
