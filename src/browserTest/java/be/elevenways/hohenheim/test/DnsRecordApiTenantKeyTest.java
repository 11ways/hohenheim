package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.dns.DnsRecordDto;
import be.elevenways.hohenheim.dns.DnsRecordListResponse;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static be.elevenways.hohenheim.test.ApiSupport.form;
import static be.elevenways.hohenheim.test.ApiSupport.idOf;
import static be.elevenways.hohenheim.test.ApiSupport.user;
import static be.elevenways.hohenheim.test.DnsFixtures.createZone;
import static be.elevenways.hohenheim.test.DnsFixtures.record;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DNS record API reached by a key that is no admin key: it writes exactly where its owner could through the
 * /manage tenant gate (hostname authority, a per-record edit grant), reads only what that owner may read, and never
 * tells it whether a zone or another owner's record exists.
 */
class DnsRecordApiTenantKeyTest extends HohenheimHttpTestBase {

    private static final String ZONE = "keyapi.test";
    private static final String HIDDEN_ZONE = "keyapi-hidden.test";
    private static final String RECORDS = "/api/dns/zones/" + ZONE + "/records";
    private static final String DIGEST = "A1B2".repeat(16);
    private static final Pattern KEY = Pattern.compile("\"key\"\\s*:\\s*\"([^\"]+)\"");

    private static String tenantKey;
    private static String adminKey;
    private static int foreignAddressId;
    private static int foreignLeafId;
    private static int grantedId;

    @BeforeAll
    static void seed() {
        int zoneId = createZone(ZONE);
        int hiddenZoneId = createZone(HIDDEN_ZONE);

        // The tenant's site serves the mail host; another site serves www in the same zone.
        UUID tenantId = user("dns-key-tenant@keyapi.test", "DNS Key Tenant");
        int ownSite = site("Key Mail", "key-mail", "mx." + ZONE);
        site("Key Web", "key-web", "www." + ZONE);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, ownSite,
            HohenheimCapabilities.MANAGE, true);
        GrantService.createDirectGrant(GrantSubjectType.USER, tenantId, "hohenheim.manage.access", true);

        // Other owners' rows, authored by the operator: an address, an attribute leaf of www that ends in
        // a name the tenant never answers for, an apex policy row, and one row the tenant is granted edit on.
        foreignAddressId = record(zoneId, "www", DnsRecordModel.TYPE_A, "192.0.2.80");
        foreignLeafId = record(zoneId, "_x.www", DnsRecordModel.TYPE_TXT, "www's own");
        record(zoneId, "_dmarc", DnsRecordModel.TYPE_TXT, "v=DMARC1; p=reject");
        grantedId = record(zoneId, "_domainkey", DnsRecordModel.TYPE_TXT, "v=DKIM1; p=old");
        record(hiddenZoneId, "secret", DnsRecordModel.TYPE_A, "192.0.2.99");
        DnsZoneStore.INSTANCE.reload();
        RecordGrants.grant(GrantSubjectType.USER, tenantId, DnsRecordModel.MODEL_ID, grantedId,
            HohenheimCapabilities.EDIT, true);

        tenantKey = ApiKeyService.create(tenantId, "dns-key-tenant", List.of("hohenheim.manage.access",
            CapabilityScopes.format(SiteModel.MODEL_ID, HohenheimCapabilities.MANAGE),
            CapabilityScopes.format(DnsRecordModel.MODEL_ID, HohenheimCapabilities.EDIT)), null).plaintext();
        UUID adminId = Models.get(UserModel.class).find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first().get(UserModel.ID);
        adminKey = ApiKeyService.create(adminId, "dns-key-admin", List.of("hohenheim.*"), null).plaintext();
    }

    /** Journey: a mail host's key publishes its records, is refused everywhere else, and learns nothing more. */
    @Test
    void aNonAdminKeyWritesWhereItsOwnerMayAndNowhereElse() throws Exception {
        // 1. Hostname authority: the host itself, its TLSA and an SRV under it all save.
        assertAccepted(post(RECORDS, form("name", "mx", "type", "A", "value", "192.0.2.25", "ttl", "300")),
            "step 1: an A at the managed host");
        int tlsaId = idOf(assertAccepted(post(RECORDS, form("name", "_25._tcp.mx", "type", "TLSA",
            "value", "3 1 1 " + DIGEST)), "step 1: a TLSA under the managed host").body());
        assertThat((String) Models.get(DnsRecordModel.class).findById(tlsaId).get(DnsRecordModel.VALUE))
            .as("step 1: stored in the canonical spelling").isEqualTo("3 1 1 " + DIGEST.toLowerCase(Locale.ROOT));
        int srvId = idOf(assertAccepted(post(RECORDS, form("name", "_submissions._tcp.mx", "type", "SRV",
            "value", "mx." + ZONE + ".", "priority", "0", "weight", "1", "port", "465")),
            "step 1: an SRV under the managed host").body());

        // 2. The list holds exactly what the owner may read: its own names, their attribute leaves and the
        //    granted row; never www, www's leaf or the apex policy.
        HttpResponse<String> listed = tenantGet(RECORDS);
        assertThat(listed.statusCode()).as("step 2: the list answers").isEqualTo(200);
        assertThat(Zenit.DRY.fromJson(listed.body(), DnsRecordListResponse.class).records())
            .extracting(DnsRecordDto::name)
            .as("step 2: the readable rows")
            .containsExactlyInAnyOrder("mx", "_25._tcp.mx", "_submissions._tcp.mx", "_domainkey");

        // 3. Another owner's name refuses with the one neutral answer, even where the name's other rows would
        //    have made an operator's write a duplicate or a CNAME conflict.
        assertRefused(post(RECORDS, form("name", "www", "type", "A", "value", "192.0.2.81")),
            "tenant_record_not_authorized", "step 3: a new row at a foreign name");
        assertRefused(post(RECORDS, form("name", "www", "type", "A", "value", "192.0.2.80")),
            "tenant_record_not_authorized", "step 3: a duplicate of a foreign row");
        assertRefused(post(RECORDS, form("name", "www", "type", "CNAME", "value", "mx." + ZONE + ".")),
            "tenant_record_not_authorized", "step 3: a CNAME beside a foreign row");
        assertRefused(post(RECORDS, form("name", "_25._tcp.www", "type", "TLSA", "value", "3 1 1 " + DIGEST)),
            "tenant_record_not_authorized", "step 3: a TLSA under a foreign host");

        // 4. The type allow-list holds: MX, NS and CAA stay refused on the tenant's own host, and TLSA only
        //    stands at a service-port name.
        assertRefused(post(RECORDS, form("name", "mx", "type", "MX", "value", "mx." + ZONE + ".",
            "priority", "10")), "tenant_record_type", "step 4: MX");
        assertRefused(post(RECORDS, form("name", "mx", "type", "NS", "value", "ns.attacker.example.")),
            "tenant_record_type", "step 4: NS");
        assertRefused(post(RECORDS, form("name", "mx", "type", "CAA", "value", "0 issue \"letsencrypt.org\"")),
            "tenant_record_type", "step 4: CAA");
        assertRefused(post(RECORDS, form("name", "mx", "type", "TLSA", "value", "3 1 1 " + DIGEST)),
            "tenant_tlsa_name", "step 4: a TLSA at the bare host");

        // 5. managed_by stays frozen: a key that is no admin key cannot claim machine ownership.
        assertRefused(post(RECORDS, form("name", "_acme-challenge.mx", "type", "TXT", "value", "token",
            "managed_by", DnsRecordModel.MANAGED_BY_ACME)), "tenant_managed_by_frozen", "step 5: managed_by");
        assertThat(Models.get(DnsRecordModel.class).find().where(DnsRecordModel.NAME.eq("_acme-challenge.mx"))
            .first()).as("step 5: and nothing was written").isNull();

        // 6. Another owner's record is the 404 of a missing one, for update and delete alike.
        HttpResponse<String> missing = tenantPost(RECORDS + "/999999", form("value", "192.0.2.1"));
        for (int foreign : List.of(foreignAddressId, foreignLeafId)) {
            HttpResponse<String> edit = tenantPost(RECORDS + "/" + foreign, form("value", "192.0.2.1"));
            assertThat(edit.statusCode()).as("step 6: update of foreign row %s", foreign).isEqualTo(404);
            assertThat(edit.body()).as("step 6: byte-identical to a missing record").isEqualTo(missing.body());
            assertThat(tenantPost(RECORDS + "/" + foreign + "/delete", "").statusCode())
                .as("step 6: delete of foreign row %s", foreign).isEqualTo(404);
        }
        assertThat((String) Models.get(DnsRecordModel.class).findById(foreignAddressId).get(DnsRecordModel.VALUE))
            .as("step 6: the foreign row is untouched").isEqualTo("192.0.2.80");

        // 7. An unreached zone answers exactly like an unknown one, on read and write.
        HttpResponse<String> unknown = tenantGet("/api/dns/zones/nobody-keyapi.test/records");
        HttpResponse<String> hidden = tenantGet("/api/dns/zones/" + HIDDEN_ZONE + "/records");
        assertThat(hidden.statusCode()).as("step 7: an unreached zone").isEqualTo(404);
        assertThat(hidden.body()).as("step 7: answers as an unknown one").isEqualTo(unknown.body());
        assertThat(tenantPost("/api/dns/zones/" + HIDDEN_ZONE + "/records",
            form("name", "x", "type", "A", "value", "192.0.2.1")).statusCode())
            .as("step 7: and refuses a write the same way").isEqualTo(404);

        // 8. The edit grant is the second lane: the row's value moves, a rename onto a name the tenant does not
        //    serve does not.
        assertAccepted(post(RECORDS + "/" + grantedId, form("value", "v=DKIM1; p=new")),
            "step 8: the granted row's value");
        assertRefused(post(RECORDS + "/" + grantedId, form("name", "takeover")),
            "tenant_record_not_authorized", "step 8: a rename onto a foreign name");
        assertThat((String) Models.get(DnsRecordModel.class).findById(grantedId).get(DnsRecordModel.NAME))
            .as("step 8: the granted row kept its name").isEqualTo("_domainkey");

        // 9. Its own rows update and delete.
        assertAccepted(post(RECORDS + "/" + tlsaId, form("value", "3 1 1 " + "c0".repeat(32))),
            "step 9: the TLSA rotates");
        assertAccepted(post(RECORDS + "/" + srvId + "/delete", ""), "step 9: the SRV deletes");
        assertThat(Models.get(DnsRecordModel.class).findById(srvId)).as("step 9: gone").isNull();

        // 10. An admin key behaves as before: every row, every zone, every type.
        HttpResponse<String> adminList = keyGet(adminKey, RECORDS);
        assertThat(Zenit.DRY.fromJson(adminList.body(), DnsRecordListResponse.class).records())
            .extracting(DnsRecordDto::name).as("step 10: the admin list holds every row")
            .contains("www", "_x.www", "_dmarc", "mx");
        assertAccepted(keyPost(adminKey, "/api/dns/zones/" + HIDDEN_ZONE + "/records",
            form("name", "mail", "type", "MX", "value", "mx." + ZONE + ".", "priority", "10")),
            "step 10: an admin key writes MX in any zone");
    }

    /**
     * Journey: a key that is no admin key spends its own budget on the record API and is refused once it is spent,
     * while an admin key, every DNS peer's, is never limited and another key keeps its own budget.
     */
    @Test
    void theRecordApiLimitsEveryKeyButAnAdminsAndEachKeyApart() throws Exception {
        UUID owner = user("dns-key-budget@keyapi.test", "DNS Key Budget");
        String spent = ApiKeyService.create(owner, "dns-key-budget", List.of("hohenheim.manage.access"), null)
            .plaintext();
        String other = ApiKeyService.create(owner, "dns-key-budget-other", List.of("hohenheim.manage.access"), null)
            .plaintext();
        UUID adminId = Models.get(UserModel.class).find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first().get(UserModel.ID);
        String peer = ApiKeyService.create(adminId, "dns-key-budget-peer", List.of("hohenheim.*"), null).plaintext();
        int budget = HohenheimEndpoints.DNS_RECORD_API_LIMIT.requests();
        Now.Stopwatch spending = Now.stopwatch();

        // 1. Within its budget the key is answered for what it asks (its owner reaches no zone: the 404).
        for (int i = 0; i < budget; i++) {
            assertThat(keyGet(spent, RECORDS).statusCode()).as("step 1: request %s is answered", i + 1)
                .isEqualTo(404);
        }

        // 2. Spent, it is refused on every route of the API, the writes included. The bucket refills continuously,
        //    so the few requests the refill paid for since the budget was spent are answered too, and no more.
        assertThat(answeredUntilRefused(() -> keyGet(spent, RECORDS).statusCode(), spending))
            .as("step 2: the next read is refused once the refill is spent").isTrue();
        Now.Stopwatch refused = Now.stopwatch();
        assertThat(answeredUntilRefused(() -> keyPost(spent, RECORDS,
            form("name", "x", "type", "A", "value", "192.0.2.1")).statusCode(), refused))
            .as("step 2: and so is a write").isTrue();

        // 3. The budget is the key's: another key of the same account is answered.
        assertThat(keyGet(other, RECORDS).statusCode()).as("step 3: another key is answered").isEqualTo(404);

        // 4. An admin key goes past the budget and is never refused for it.
        for (int i = 0; i <= budget + 1; i++) {
            assertThat(keyGet(peer, RECORDS).statusCode()).as("step 4: admin request %s", i + 1).isEqualTo(200);
        }
    }

    /**
     * Sends until the limit refuses, failing on an answer the refill since {@code since} did not pay for.
     *
     * @return true once refused
     */
    private static boolean answeredUntilRefused(ThrowingStatus send, Now.Stopwatch since) throws Exception {
        long tokenNanos = HohenheimEndpoints.DNS_RECORD_API_LIMIT.window().toNanos()
            / HohenheimEndpoints.DNS_RECORD_API_LIMIT.requests();
        int answered = 0;
        int status;
        while ((status = send.status()) != 429) {
            assertThat(status).as("an answered request is answered for what it asks").isEqualTo(404);
            answered++;
            assertThat((long) answered).as("answered only as far as the refill paid")
                .isLessThanOrEqualTo(since.elapsedNanos() / tokenNanos + 1);
        }
        return true;
    }

    /** One request of the journey, answering its status. */
    @FunctionalInterface
    private interface ThrowingStatus {
        int status() throws Exception;
    }

    // --- plumbing ---------------------------------------------------------------------

    /** A static site answering one exact hostname; the DNS tests that judge hostname authority share it. */
    static int site(String name, String slug, String hostname) {
        Model sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, slug);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        Model domains = Models.get(SiteDomainModel.class);
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domain.set(SiteDomainModel.FORCE_SSL, false);
        domains.save(domain);
        return site.get(SiteModel.ID);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return tenantPost(path, body);
    }

    private HttpResponse<String> tenantGet(String path) throws Exception {
        return keyGet(tenantKey, path);
    }

    private HttpResponse<String> tenantPost(String path, String body) throws Exception {
        return keyPost(tenantKey, path, body);
    }

    private static HttpResponse<String> assertAccepted(HttpResponse<String> response, String step) {
        assertThat(response.statusCode()).as("%s is accepted: %s", step, response.body()).isEqualTo(200);
        return response;
    }

    private static void assertRefused(HttpResponse<String> response, String key, String step) {
        assertThat(response.statusCode()).as("%s is refused: %s", step, response.body()).isEqualTo(422);
        Matcher matcher = KEY.matcher(response.body());
        assertThat(matcher.find()).as("%s names its reason: %s", step, response.body()).isTrue();
        assertThat(matcher.group(1)).as("%s refuses with %s", step, key).isEqualTo(key);
    }
}
