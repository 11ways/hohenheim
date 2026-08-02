package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.data.RecordSourceQuery;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tenant authority over the domain / DNS / certificate tier: a delegated tenant binds
 * hostnames on the sites it manages, is 404 on every other tenant's row through every
 * surface, and cannot reach past the delegated column set or the DNS type allow-list on ANY
 * writer -- including a direct model save, which no form or resource method sees.
 */
@TestMethodOrder(OrderAnnotation.class)
class TenantDomainDnsScopeTest extends HohenheimTestBase {

    private static final String ZONE_ORIGIN = "tenantscope.test";

    private static Integer ownSiteId;
    private static Integer foreignSiteId;
    private static Integer ownDomainId;
    private static Integer foreignDomainId;
    private static Integer foreignCertificateId;
    private static Integer zoneId;
    private static Integer tenantId;
    private static String tenantSession;
    private static String tenantCsrf;
    private static UserPrincipal tenantPrincipal;
    private static UserPrincipal adminPrincipal;

    @BeforeAll
    static void seed() {
        var siteModel = Models.get(SiteModel.class);
        var domainModel = Models.get(SiteDomainModel.class);

        Row own = site(siteModel, "Tenant Owned", "tenant-owned");
        ownSiteId = own.get(SiteModel.ID);
        Row foreign = site(siteModel, "Tenant Foreign", "tenant-foreign");
        foreignSiteId = foreign.get(SiteModel.ID);

        Row ownDomain = domainModel.createEmptyRow();
        ownDomain.set(SiteDomainModel.SITE_ID, ownSiteId);
        ownDomain.set(SiteDomainModel.HOSTNAME, "owned.tenantscope.test");
        ownDomain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domainModel.save(ownDomain);
        ownDomainId = ownDomain.get(SiteDomainModel.ID);

        Row foreignDomain = domainModel.createEmptyRow();
        foreignDomain.set(SiteDomainModel.SITE_ID, foreignSiteId);
        foreignDomain.set(SiteDomainModel.HOSTNAME, "foreign.tenantscope.test");
        foreignDomain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domainModel.save(foreignDomain);
        foreignDomainId = foreignDomain.get(SiteDomainModel.ID);

        var certModel = Models.get(CertificateModel.class);
        Row cert = certModel.createEmptyRow();
        cert.set(CertificateModel.NICE_NAME, "Foreign tenant certificate");
        cert.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
        cert.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        certModel.save(cert);
        foreignCertificateId = cert.get(CertificateModel.ID);

        // The ACME ACCOUNT row: bookkeeping that holds the installation's account key and
        // must be invisible through the certificate source's ACCESS criteria, not merely
        // through its display filter.
        Row account = certModel.createEmptyRow();
        account.set(CertificateModel.NICE_NAME, "ACME account bookkeeping");
        account.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_ACME_ACCOUNT);
        certModel.save(account);

        var zoneModel = Models.get(DnsZoneModel.class);
        Row zone = zoneModel.createEmptyRow();
        zone.set(DnsZoneModel.ORIGIN, ZONE_ORIGIN);
        zone.set(DnsZoneModel.ENABLED, true);
        zone.set(DnsZoneModel.DEFAULT_TTL, 3600);
        zone.set(DnsZoneModel.NEGATIVE_TTL, 300);
        zone.set(DnsZoneModel.SOA_REFRESH, 7200);
        zone.set(DnsZoneModel.SOA_RETRY, 3600);
        zone.set(DnsZoneModel.SOA_EXPIRE, 1209600);
        zoneModel.save(zone);
        zoneId = zone.get(DnsZoneModel.ID);
        DnsZoneStore.INSTANCE.reload();

        Row tenant = AuthModels.users().createEmptyRow();
        tenant.set(UserModel.EMAIL, "tenant-scope@hohenheim.local");
        tenant.set(UserModel.DISPLAY_NAME, "Scope Tenant");
        tenant.set(UserModel.ENABLED, true);
        tenant.set(UserModel.CREATED_AT, Instant.now());
        tenant.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(tenant);
        tenantId = tenant.get(UserModel.ID);
        tenantPrincipal = new UserPrincipal(tenantId, "Scope Tenant");

        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        adminPrincipal = new UserPrincipal(admin.get(UserModel.ID), "Test Admin");

        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, tenantId.longValue());
        tenantCsrf = ZenitAuth.randomToken();
        session.set(CsrfTokens.TOKEN, tenantCsrf);
        Zenit.getSessionStore().save(session);
        tenantSession = session.id();

        RecordGrants.grant("user", tenantId, SiteModel.MODEL_ID, ownSiteId,
            HohenheimAccess.MANAGE, true);
    }

    private static Row site(Model model, String name, String slug) {
        Row row = model.createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.SITE_TYPE, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, true);
        model.save(row);
        return row;
    }

    // --- HTTP helpers -----------------------------------------------------------------

    private String baseUrl() {
        return "http://localhost:" + getServerPort();
    }

    private HttpResponse<String> get(String path, String session) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create(baseUrl() + path));
        if (session != null) {
            request.header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String session, String csrf,
                                      String contentType) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", contentType)
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session)
            .header("X-Csrf-Token", csrf)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> tenantGet(String path) throws Exception {
        return get(path, tenantSession);
    }

    private HttpResponse<String> tenantPost(String path, String body) throws Exception {
        return post(path, body, tenantSession, tenantCsrf, "application/x-www-form-urlencoded");
    }

    private HttpResponse<String> tenantDry(String path, String body) throws Exception {
        return post(path, body, tenantSession, tenantCsrf, "application/dry");
    }

    private static Row domainByHostname(String hostname) {
        return Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq(hostname)).first();
    }

    // --- The journeys -----------------------------------------------------------------

    /**
     * The delegated hostname surface end to end: a tenant sees, binds, edits and unbinds
     * hostnames on the ONE site it manages, and the row it binds claims a live route -- so
     * scoping did not cost the feature.
     */
    @Test
    @Order(1)
    void aTenantBindsEditsAndUnbindsHostnamesOnTheSiteItManages() throws Exception {
        // 1. The delegated list shows exactly the managed site's domains.
        HttpResponse<String> list = tenantGet("/manage/domains");
        assertThat(list.statusCode()).as("the delegated domain list is reachable").isEqualTo(200);
        assertThat(list.body())
            .as("the tenant sees its own hostname and no other tenant's")
            .contains("owned.tenantscope.test")
            .doesNotContain("foreign.tenantscope.test");

        // 2. The legitimate path: bind a new hostname to the managed site.
        assertThat(tenantPost("/manage/domains/new",
            "site_id=" + ownSiteId + "&hostname=new.tenantscope.test&force_ssl=true").statusCode())
            .as("a tenant may bind a hostname to a site it manages").isIn(302, 303);
        Row bound = domainByHostname("new.tenantscope.test");
        assertThat(bound).as("the bound hostname persisted").isNotNull();
        assertThat((Integer) bound.get(SiteDomainModel.SITE_ID)).isEqualTo(ownSiteId);
        assertThat((String) bound.get(SiteDomainModel.MATCH_TYPE))
            .as("a delegated binding is always an exact hostname")
            .isEqualTo(SiteDomainModel.MATCH_EXACT);
        assertThat((String) bound.get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("the row claims a live route, so the dispatcher will serve it")
            .isNotNull();

        // 3. Editing its own row works and keeps the frozen columns untouched.
        assertThat(tenantPost("/manage/domains/" + bound.get(SiteDomainModel.ID),
            "site_id=" + ownSiteId + "&hostname=renamed.tenantscope.test&force_ssl=true")
            .statusCode()).as("a tenant may edit its own binding").isIn(302, 303);
        Row renamed = Models.get(SiteDomainModel.class).findById(bound.get(SiteDomainModel.ID));
        assertThat((String) renamed.get(SiteDomainModel.HOSTNAME)).isEqualTo("renamed.tenantscope.test");

        // 4. And unbinding it works, while another tenant's row cannot even be reached.
        assertThat(tenantPost("/manage/domains/" + foreignDomainId + "/delete", "").statusCode())
            .as("deleting a foreign domain is missing, not forbidden").isEqualTo(404);
        assertThat(Models.get(SiteDomainModel.class).findById(foreignDomainId))
            .as("and the foreign row is still there").isNotNull();
        assertThat(tenantPost("/manage/domains/" + renamed.get(SiteDomainModel.ID) + "/delete", "")
            .statusCode()).as("a tenant may unbind its own hostname").isIn(302, 303);
        assertThat(domainByHostname("renamed.tenantscope.test"))
            .as("the unbound row is gone").isNull();
    }

    /**
     * The domain READ scope, asserted separately from the write journey so a regression in
     * one is never masked by the other: the resource route and the record source are two
     * independent readers and BOTH have to answer missing.
     */
    @Test
    @Order(2)
    void anotherTenantsDomainIsMissingThroughTheResourceAndThroughTheSource() throws Exception {
        // 1. The resource route: missing, not forbidden, and leaking nothing in the body.
        HttpResponse<String> foreign = tenantGet("/manage/domains/" + foreignDomainId);
        assertThat(foreign.statusCode())
            .as("a foreign domain is 404, never 403 -- no existence oracle").isEqualTo(404);
        assertThat(foreign.body())
            .as("the refusal body carries no foreign data")
            .doesNotContain("foreign.tenantscope.test");

        // 2. The record source: a SECOND reader, reached by token and answering no resource
        //    method at all.
        String matchAll = Zenit.DRY.stringify(RecordSourceQuery.matchAll());
        HttpResponse<String> source = tenantDry("/zn/records/hohenheim.site_domain/query", matchAll);
        assertThat(source.statusCode()).as("the domain source is open to a grant holder").isEqualTo(200);
        assertThat(source.body())
            .as("the domain source is scoped by the PARENT SITE's grant")
            .contains("owned.tenantscope.test")
            .doesNotContain("foreign.tenantscope.test");
        assertThat(tenantGet("/zn/records/hohenheim.site_domain/item/" + foreignDomainId).statusCode())
            .as("a foreign domain id resolves as missing through the source too").isEqualTo(404);

        // 3. And the delegated list itself.
        assertThat(tenantGet("/manage/domains").body())
            .as("the scoped list shows only the managed site's domains")
            .doesNotContain("foreign.tenantscope.test");
    }

    /**
     * A forged submit reaches past the rendered form, so the assertions are about STATE: the
     * frozen columns keep their safe values, and a claim on someone else's site or hostname
     * writes nothing at all.
     */
    @Test
    @Order(3)
    void aForgedSubmitCannotSetFrozenColumnsOrClaimAnotherTenantsSite() throws Exception {
        // 1. Every frozen column at once, in one direct POST that never renders a form.
        assertThat(tenantPost("/manage/domains/new",
            "site_id=" + ownSiteId + "&hostname=forged.tenantscope.test"
                + "&match_type=wildcard&listen_on=127.0.0.1&path=/carve"
                + "&strip_path=true&certificate_id=" + foreignCertificateId).statusCode())
            .isIn(302, 303, 422);
        Row forged = domainByHostname("forged.tenantscope.test");
        assertThat(forged).as("the delegated columns still applied").isNotNull();
        assertThat((String) forged.get(SiteDomainModel.MATCH_TYPE))
            .as("a tenant wildcard would claim an unbounded hostname set")
            .isEqualTo(SiteDomainModel.MATCH_EXACT);
        assertThat((String) forged.get(SiteDomainModel.LISTEN_ON))
            .as("a disjoint listener set walks past the route-overlap refusal").isNull();
        assertThat((String) forged.get(SiteDomainModel.PATH))
            .as("a differing path is exempt from the same refusal, one dimension over").isNull();
        assertThat((Integer) forged.get(SiteDomainModel.CERTIFICATE_ID))
            .as("pinning a certificate row is authority over a name the tenant may not hold")
            .isNull();

        // 2. A binding onto a site the tenant does NOT manage writes nothing.
        assertThat(tenantPost("/manage/domains/new",
            "site_id=" + foreignSiteId + "&hostname=stolen.tenantscope.test").statusCode())
            .isIn(200, 302, 303, 404, 422);
        assertThat(domainByHostname("stolen.tenantscope.test"))
            .as("the AccessFunction scopes READS; the site_id a CREATE submits is a "
                + "separate question and the write pipeline is what answers it")
            .isNull();

        // 3. Neither can it MOVE one of its own rows onto another tenant's site.
        assertThat(tenantPost("/manage/domains/" + forged.get(SiteDomainModel.ID),
            "site_id=" + foreignSiteId + "&hostname=forged.tenantscope.test").statusCode())
            .isIn(200, 302, 303, 404, 422);
        assertThat((Integer) Models.get(SiteDomainModel.class)
            .findById(forged.get(SiteDomainModel.ID)).get(SiteDomainModel.SITE_ID))
            .as("a move to a foreign site is the same takeover as a create there")
            .isEqualTo(ownSiteId);

        // 4. And it cannot claim a hostname another tenant's live site already serves.
        assertThat(tenantPost("/manage/domains/new",
            "site_id=" + ownSiteId + "&hostname=foreign.tenantscope.test").statusCode())
            .isIn(200, 302, 303, 422);
        assertThat(Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq("foreign.tenantscope.test"))
            .where(SiteDomainModel.SITE_ID.eq(ownSiteId)).first())
            .as("the owner-scoped route-conflict refusal covers the delegated path")
            .isNull();

        Models.get(SiteDomainModel.class).delete(
            Models.get(SiteDomainModel.class).findById(forged.get(SiteDomainModel.ID)));
    }

    /**
     * The freezes live in the WRITE PIPELINE, so they hold for a caller that never touches a
     * form, a resource method or an endpoint -- the revision-restore / import / direct-save
     * bypass class. The same writes as an ADMIN are the counter-proof that the refusal is
     * tenant-scoped and not a blanket ban.
     */
    @Test
    @Order(4)
    void theFrozenColumnsAreRefusedOnADirectModelSaveAndAllowedForAnAdmin() {
        Model model = Models.get(SiteDomainModel.class);

        // 1. A listener restriction, straight at the model.
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(ownDomainId);
            row.set(SiteDomainModel.LISTEN_ON, "127.0.0.1");
            model.save(row);
        })).as("listen_on is frozen for a tenant on EVERY writer").isInstanceOf(Violations.class);
        assertThat((String) model.findById(ownDomainId).get(SiteDomainModel.LISTEN_ON))
            .as("a refused write leaves the row untouched").isNull();

        // 2. A wildcard match type.
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(ownDomainId);
            row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_WILDCARD);
            row.set(SiteDomainModel.HOSTNAME, "*.tenantscope.test");
            model.save(row);
        })).as("match_type is frozen to exact for a tenant").isInstanceOf(Violations.class);

        // 3. A path carve-out.
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(ownDomainId);
            row.set(SiteDomainModel.PATH, "/carve");
            model.save(row);
        })).as("path is frozen for a tenant").isInstanceOf(Violations.class);

        // 4. A certificate the tenant does not own.
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(ownDomainId);
            row.set(SiteDomainModel.CERTIFICATE_ID, foreignCertificateId);
            model.save(row);
        })).as("certificate_id is frozen for a tenant").isInstanceOf(Violations.class);

        // 5. A binding onto a site it does not manage, with no form and no relation pick in
        //    the way. Through /manage the scoped site source already refuses the id at
        //    COERCION time -- which is a resource-layer answer, and a direct writer never
        //    asks it.
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.createEmptyRow();
            row.set(SiteDomainModel.SITE_ID, foreignSiteId);
            row.set(SiteDomainModel.HOSTNAME, "direct-steal.tenantscope.test");
            row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
            model.save(row);
        })).as("the site a domain belongs to must be one the tenant manages")
            .isInstanceOf(Violations.class);
        assertThat(domainByHostname("direct-steal.tenantscope.test"))
            .as("and the refused write left no row").isNull();

        // 6. The DELEGATED columns still save, so the rule is a freeze and not a wall.
        assertThatCode(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(ownDomainId);
            row.set(SiteDomainModel.HSTS_ENABLED, true);
            model.save(row);
        })).as("a tenant may still write the delegated columns").doesNotThrowAnyException();
        assertThat((Boolean) model.findById(ownDomainId).get(SiteDomainModel.HSTS_ENABLED)).isTrue();

        // 7. The SAME writes as an operator go through: the refusals above were about WHO
        //    was writing, not about the values.
        assertThatCode(() -> TenantConduits.as(adminPrincipal, () -> {
            Row row = model.findById(ownDomainId);
            row.set(SiteDomainModel.PATH, "/carve");
            row.set(SiteDomainModel.CERTIFICATE_ID, foreignCertificateId);
            model.save(row);
        })).as("an operator is unconstrained").doesNotThrowAnyException();

        // 8. ...and the tenant may no longer edit the row an operator gave a path: the rule
        //    is about the EFFECTIVE value, so a frozen field it merely inherits still bites.
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(ownDomainId);
            row.set(SiteDomainModel.HOSTNAME, "owned2.tenantscope.test");
            model.save(row);
        })).as("an inherited path is still a path").isInstanceOf(Violations.class);

        TenantConduits.as(adminPrincipal, () -> {
            Row row = model.findById(ownDomainId);
            row.set(SiteDomainModel.PATH, null);
            row.set(SiteDomainModel.CERTIFICATE_ID, null);
            model.save(row);
        });
    }

    /**
     * The DNS type allow-list: a tenant-originated write may only author A/AAAA/CNAME/TXT/SRV,
     * may not re-home a row between zones and may not relabel who manages it. The same writes
     * as an operator are unaffected.
     */
    @Test
    @Order(5)
    void tenantDnsWritesStayInsideTheTypeAllowList() {
        Model model = Models.get(DnsRecordModel.class);

        // 1. An allow-listed type saves.
        assertThatCode(() -> TenantConduits.as(tenantPrincipal,
            () -> model.save(record(model, "www", DnsRecordModel.TYPE_A, "10.0.0.1"))))
            .as("A is inside the allow-list").doesNotThrowAnyException();

        // 2. The zone-compromise primitives are refused: NS delegates a subtree away, CAA
        //    redirects issuance for the whole name, MX re-points mail.
        for (String type : List.of(DnsRecordModel.TYPE_NS, DnsRecordModel.TYPE_CAA,
                DnsRecordModel.TYPE_MX)) {
            String value = switch (type) {
                case DnsRecordModel.TYPE_CAA -> "0 issue \"letsencrypt.org\"";
                default -> "attacker.example.com.";
            };
            assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal, () -> {
                Row row = record(model, "hostile-" + type.toLowerCase(java.util.Locale.ROOT),
                    type, value);
                row.set(DnsRecordModel.PRIORITY, 10);
                model.save(row);
            })).as("%s is not a delegable record type", type).isInstanceOf(Violations.class);
            assertThat(model.find().where(DnsRecordModel.TYPE.eq(type))
                .where(DnsRecordModel.ZONE_ID.eq(zoneId)).first())
                .as("a refused %s write leaves no row behind", type).isNull();
        }

        // 3. An operator authors the same NS row without trouble -- the allow-list is about
        //    delegation, not about the record type being invalid.
        Row operatorNs = record(model, "sub", DnsRecordModel.TYPE_NS, "ns1.example.com.");
        assertThatCode(() -> TenantConduits.as(adminPrincipal, () -> model.save(operatorNs)))
            .as("an operator may author NS").doesNotThrowAnyException();

        // 4. A tenant cannot launder that row in by RETYPING it, in either direction.
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(operatorNs.get(DnsRecordModel.ID));
            row.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_A);
            row.set(DnsRecordModel.VALUE, "10.0.0.9");
            model.save(row);
        })).as("editing a row whose STORED type is outside the allow-list is refused")
            .isInstanceOf(Violations.class);

        // 5. Nor delete it, while its own A row deletes fine.
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal,
            () -> model.delete(model.findById(operatorNs.get(DnsRecordModel.ID)))))
            .as("deleting an NS row is the same zone-compromise primitive as writing one")
            .isInstanceOf(Violations.class);
        assertThat(model.findById(operatorNs.get(DnsRecordModel.ID)))
            .as("the operator NS row survived").isNotNull();

        // 6. Re-homing a row between zones is a takeover primitive.
        Row own = model.find().where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.NAME.eq("www")).first();
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(own.get(DnsRecordModel.ID));
            row.set(DnsRecordModel.ZONE_ID, zoneId + 1000);
            model.save(row);
        })).as("zone_id is frozen for a tenant").isInstanceOf(Violations.class);

        // 7. So is relabelling who manages it: managed_by decides the zone-file import's
        //    replace scope.
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(own.get(DnsRecordModel.ID));
            row.set(DnsRecordModel.MANAGED_BY, DnsRecordModel.MANAGED_BY_ACME);
            model.save(row);
        })).as("managed_by is frozen for a tenant").isInstanceOf(Violations.class);

        // 8. An ordinary value edit on its own A row still saves.
        assertThatCode(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(own.get(DnsRecordModel.ID));
            row.set(DnsRecordModel.VALUE, "10.0.0.2");
            model.save(row);
        })).as("an allow-listed row still edits").doesNotThrowAnyException();
        assertThat((String) model.findById(own.get(DnsRecordModel.ID)).get(DnsRecordModel.VALUE))
            .isEqualTo("10.0.0.2");

        assertThatCode(() -> TenantConduits.as(tenantPrincipal,
            () -> model.delete(model.findById(own.get(DnsRecordModel.ID)))))
            .as("a tenant may remove its own allow-listed row").doesNotThrowAnyException();

        TenantConduits.as(adminPrincipal,
            () -> model.delete(model.findById(operatorNs.get(DnsRecordModel.ID))));
    }

    /**
     * {@code /nic/update} is an ANONYMOUS request, so it reads as tenant-originated and rides
     * the same allow-list. That is fine for the address records it is for, and the protocol's
     * own dnserr is what a mistakenly-dynamic non-address record now gets instead of a 500.
     */
    @Test
    @Order(6)
    void dynamicDnsKeepsWorkingAndANonAddressDynamicRecordAnswersDnserr() throws Exception {
        Model model = Models.get(DnsRecordModel.class);

        // 1. An ordinary dynamic A record updates, with no principal anywhere in sight.
        Row dynamic = record(model, "router", DnsRecordModel.TYPE_A, "10.9.9.9");
        dynamic.set(DnsRecordModel.DYNAMIC, true);
        String token = DynamicDnsService.mintToken();
        dynamic.set(DnsRecordModel.DYNDNS_TOKEN, DynamicDnsService.digest(token));
        model.save(dynamic);

        HttpResponse<String> good = get("/nic/update?hostname="
            + URLEncoder.encode("router." + ZONE_ORIGIN, StandardCharsets.UTF_8)
            + "&myip=203.0.113.7&token=" + token, null);
        assertThat(good.statusCode()).isEqualTo(200);
        assertThat(good.body())
            .as("the tenant-write allow-list must not touch the dyndns path").startsWith("good");
        assertThat((String) model.findById(dynamic.get(DnsRecordModel.ID)).get(DnsRecordModel.VALUE))
            .isEqualTo("203.0.113.7");

        // 2. A record an operator mistakenly flagged dynamic on a non-address type is
        //    refused by the protocol rather than by a thrown violation.
        Row mistake = record(model, "mail", DnsRecordModel.TYPE_MX, "mx.example.com.");
        mistake.set(DnsRecordModel.PRIORITY, 10);
        mistake.set(DnsRecordModel.DYNAMIC, true);
        String mxToken = DynamicDnsService.mintToken();
        mistake.set(DnsRecordModel.DYNDNS_TOKEN, DynamicDnsService.digest(mxToken));
        TenantConduits.as(adminPrincipal, () -> model.save(mistake));

        HttpResponse<String> refused = get("/nic/update?hostname="
            + URLEncoder.encode("mail." + ZONE_ORIGIN, StandardCharsets.UTF_8)
            + "&myip=203.0.113.8&token=" + mxToken, null);
        assertThat(refused.statusCode()).isEqualTo(200);
        assertThat(refused.body()).as("dyndns2 writes an address, so only an address record "
            + "can be dynamic").isEqualTo("dnserr");
        assertThat((String) model.findById(mistake.get(DnsRecordModel.ID)).get(DnsRecordModel.VALUE))
            .as("and nothing was written").isEqualTo("mx.example.com.");

        TenantConduits.as(adminPrincipal, () -> {
            model.delete(model.findById(dynamic.get(DnsRecordModel.ID)));
            model.delete(model.findById(mistake.get(DnsRecordModel.ID)));
        });
    }

    /**
     * The zone / record / certificate tier stays ADMIN-ONLY through every surface a delegated
     * tenant can address: sources, the nav-hidden record resource, the peer API, the zone-file
     * import and the certificate pages.
     */
    @Test
    @Order(7)
    void everyZoneRecordAndCertificateSurfaceStaysClosedToATenant() throws Exception {
        String matchAll = Zenit.DRY.stringify(RecordSourceQuery.matchAll());

        // 1. Sources: unauthorized and unknown refuse IDENTICALLY with 404.
        for (String token : List.of("hohenheim.dns_zone", "hohenheim.certificate",
                "hohenheim.dns_record")) {
            assertThat(tenantDry("/zn/records/" + token + "/query", matchAll).statusCode())
                .as("query on %s", token).isEqualTo(404);
            assertThat(tenantGet("/zn/records/" + token + "/item/" + zoneId).statusCode())
                .as("item on %s", token).isEqualTo(404);
        }

        // 2. The ACME ACCOUNT row is excluded by the ACCESS criteria, so even the operator's
        //    own reads through the source cannot resolve it.
        Row account = Models.get(CertificateModel.class).find()
            .where(CertificateModel.PROVIDER.eq(CertificateModel.PROVIDER_ACME_ACCOUNT)).first();
        HttpResponse<String> adminAccount = get(
            "/zn/records/hohenheim.certificate/item/" + account.get(CertificateModel.ID),
            sessionToken);
        assertThat(adminAccount.statusCode())
            .as("the ACME account key row is not a resolvable certificate").isEqualTo(404);
        HttpResponse<String> adminCerts = post("/zn/records/hohenheim.certificate/query",
            matchAll, sessionToken, csrfToken, "application/dry");
        assertThat(adminCerts.statusCode()).isEqualTo(200);
        assertThat(adminCerts.body())
            .contains("Foreign tenant certificate")
            .doesNotContain("ACME account bookkeeping");

        // 3. Nav invisibility is not a gate: the record resource's slug still ROUTES, and it
        //    is the panel permission that refuses.
        assertThat(tenantGet("/admin/dns-records").statusCode()).isEqualTo(403);
        assertThat(tenantGet("/admin/dns-zones/" + zoneId + "/page/records").statusCode())
            .isEqualTo(403);
        assertThat(tenantGet("/admin/certificates").statusCode()).isEqualTo(403);
        assertThat(tenantGet("/admin/certificates-request?site=" + foreignSiteId).statusCode())
            .isEqualTo(403);

        // 4. ...and the delegated panel simply has no such peers.
        for (String slug : List.of("dns-records", "dns-zones", "dns-peers", "certificates")) {
            assertThat(tenantGet("/manage/" + slug).statusCode())
                .as("/manage/%s is not a delegated surface", slug).isEqualTo(404);
        }

        // 5. The peer/automation API refuses a session principal outright, tenant or not.
        assertThat(tenantGet("/api/dns/zones/" + ZONE_ORIGIN + "/records").statusCode())
            .as("the DNS API is admin-permissioned AND api-key-only").isIn(403, 404);
        assertThat(tenantPost("/api/dns/zones/" + ZONE_ORIGIN + "/records",
            "name=x&type=NS&value=ns.attacker.example.com.").statusCode()).isIn(403, 404);

        // 6. The zone-file import replaces every non-generated row in a zone in one POST.
        assertThat(tenantPost("/admin/dns-zones/" + zoneId + "/page/zone-file",
            "text=" + URLEncoder.encode("@ IN NS ns.attacker.example.com.",
                StandardCharsets.UTF_8)).statusCode())
            .as("a one-POST zone replacement stays admin-only").isIn(403, 404);
        assertThat(Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.TYPE.eq(DnsRecordModel.TYPE_NS)).first())
            .as("and wrote nothing").isNull();
    }

    /**
     * The added managedSiteIds callers must stay inside the memo: scoping the domain list
     * asks for the managed set through the panel gate, the resource AccessFunction, the
     * record source and the nav probe, and that must remain ONE grant-store enumeration.
     */
    @Test
    @Order(8)
    void theDomainScopeStaysWithinTheManagedSiteQueryBudget() throws Exception {
        java.util.concurrent.atomic.AtomicInteger finds = new java.util.concurrent.atomic.AtomicInteger();
        be.elevenways.zenit.auth.model.RecordGrantModel.SCHEMA
            .addBeforeFindHook(ignored -> finds.incrementAndGet());

        finds.set(0);
        assertThat(tenantGet("/manage/domains").statusCode()).isEqualTo(200);
        assertThat(finds.get())
            .as("record-grant finds during one scoped /manage/domains render")
            .isBetween(1, 3);
    }

    private static Row record(Model model, String name, String type, String value) {
        Row row = model.createEmptyRow();
        row.set(DnsRecordModel.ZONE_ID, zoneId);
        row.set(DnsRecordModel.NAME, name);
        row.set(DnsRecordModel.TYPE, type);
        row.set(DnsRecordModel.VALUE, value);
        row.set(DnsRecordModel.TTL, 300);
        row.set(DnsRecordModel.ENABLED, true);
        return row;
    }
}
