package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsDyndnsCredentialModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
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
 * The stored-secret contract on hohenheim admin forms, walked over the real
 * routes: a {@code secret()} field is never echoed into the rendered form, a
 * blank submit keeps the stored value, an explicit value replaces it, the
 * {@code __clear} companion empties it (specimen: the DNS peer TSIG secret), and
 * the dyndns mint row action is the ONE disclosure of a freshly minted token
 * (specimen: a DNS record's credential).
 */
class SecretFieldFormTest extends HohenheimTestBase {

    // AIDEV-NOTE: the specimen is the TSIG secret rather than the peer api key, which it
    // was until the peer TYPE landed: a nameserver peer's api key is not writable at all
    // (the type's field binding strips it) and a hohenheim peer's may not be cleared, so
    // neither half of that column can carry the generic secret contract any more.
    private static final String STORED_KEY = "peer-tsig-secret-original-value";

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
        int peerId = seedPeer();
        String editPath = "/admin/dns-peers/" + peerId;

        // 1. The edit form still OFFERS the field, but never echoes the stored value.
        assertThat(keyOf(peerId))
            .as("1. the seeded peer holds the stored TSIG secret").isEqualTo(STORED_KEY);
        HttpResponse<String> form = get(editPath);
        assertThat(form.statusCode()).as("1. the edit form must render").isEqualTo(200);
        assertThat(form.body())
            .as("1. the form must still offer the tsig_secret entry")
            .contains("tsig_secret");
        assertThat(form.body())
            .as("1. a secret() field must NEVER echo the stored value into the form")
            .doesNotContain(STORED_KEY);

        // 2. A submit that leaves the secret blank KEEPS the stored value.
        HttpResponse<String> blank = post(editPath, peerBody(""));
        assertThat(blank.statusCode()).as("2. the blank submit must be accepted").isIn(200, 302, 303);
        assertThat(keyOf(peerId))
            .as("2. a blank secret submit must keep the stored value, not wipe it")
            .isEqualTo(STORED_KEY);

        // 3. A submitted value REPLACES the stored one.
        String replacement = "peer-tsig-secret-replacement-value";
        HttpResponse<String> replaced = post(editPath, peerBody(replacement));
        assertThat(replaced.statusCode()).as("3. the replacing submit must be accepted").isIn(200, 302, 303);
        assertThat(keyOf(peerId))
            .as("3. a non-blank secret submit must replace the stored value")
            .isEqualTo(replacement);

        // 4. The __clear companion is the one way to EMPTY a secret.
        HttpResponse<String> cleared = post(editPath, peerBody("") + "&tsig_secret__clear=true");
        assertThat(cleared.statusCode()).as("4. the clearing submit must be accepted").isIn(200, 302, 303);
        assertThat(keyOf(peerId))
            .as("4. the __clear companion must empty the stored secret")
            .isNull();

        // 5. The dyndns mint action discloses the PLAINTEXT token ONCE in the flash
        //    toast, while the credential table stores only its digest.
        int recordId = seedRecord();
        String recordPath = "/admin/dns-records/" + recordId;
        HttpResponse<String> mint = post(recordPath + "/action/dyndns_token", "");
        assertThat(mint.statusCode()).as("5. the mint row action must be accepted").isIn(200, 302, 303);

        HttpResponse<String> afterMint = get(recordPath);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("hdyn_[A-Za-z0-9._-]+")
            .matcher(afterMint.body());
        assertThat(m.find()).as("5. the mint toast must disclose the plaintext token once").isTrue();
        String disclosed = m.group();
        assertThat((String) DynamicDnsService.credentialFor(recordId)
            .get(DnsDyndnsCredentialModel.TOKEN_DIGEST))
            .as("5. the credential stores only the digest of the minted token")
            .isEqualTo(DynamicDnsService.digest(disclosed))
            .doesNotContain(disclosed);

        // 6. The disclosure is consumed: a reload shows the record without the token.
        HttpResponse<String> reload = get(recordPath);
        assertThat(reload.body())
            .as("6. a reload must not re-disclose the minted token")
            .doesNotContain(disclosed);
    }

    // ------------------------------------------------------------------

    private static String keyOf(int peerId) {
        return Models.get(DnsPeerModel.class).findById(peerId).get(DnsPeerModel.TSIG_SECRET);
    }

    /** The full peer form body; only the secret entry varies. */
    private static String peerBody(String tsigSecret) {
        return "name=secret-form-peer&peer_type=nameserver"
            + "&transfer_host=peer.secret-form.example&transfer_port=53"
            + "&tsig_key_name=&tsig_algorithm=hmac-sha256&base_url=&api_key=&enabled=true"
            + "&tsig_secret=" + tsigSecret;
    }

    private int seedPeer() {
        DnsPeerModel peers = Models.get(DnsPeerModel.class);
        Row peer = peers.createEmptyRow();
        peer.set(DnsPeerModel.NAME, "secret-form-peer");
        peer.set(DnsPeerModel.TRANSFER_HOST, "peer.secret-form.example");
        peer.set(DnsPeerModel.TRANSFER_PORT, 53);
        peer.set(DnsPeerModel.TSIG_ALGORITHM, "hmac-sha256");
        peer.set(DnsPeerModel.PEER_TYPE, DnsPeerModel.TYPE_NAMESERVER);
        peer.set(DnsPeerModel.TSIG_SECRET, STORED_KEY);
        peer.set(DnsPeerModel.ENABLED, true);
        peers.save(peer);
        return peer.get(DnsPeerModel.ID);
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
        records.save(record);
        return record.get(DnsRecordModel.ID);
    }
}
