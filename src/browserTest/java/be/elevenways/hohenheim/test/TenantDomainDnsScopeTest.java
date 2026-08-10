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
import java.util.Base64;
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
    private static Integer foreignRecordId;
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
        ownDomain.set(SiteDomainModel.FORCE_SSL, false);
        domainModel.save(ownDomain);
        ownDomainId = ownDomain.get(SiteDomainModel.ID);

        Row foreignDomain = domainModel.createEmptyRow();
        foreignDomain.set(SiteDomainModel.SITE_ID, foreignSiteId);
        foreignDomain.set(SiteDomainModel.HOSTNAME, "foreign.tenantscope.test");
        foreignDomain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        foreignDomain.set(SiteDomainModel.FORCE_SSL, false);
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
        // must be invisible through the certificate source's BASE criteria -- which
        // RecordSource ANDs into query, resolve, exists and buckets alike, so it is a real
        // exclusion on every path and not merely a display filter.
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

        // Another tenant's DNS record, authored as the system so nothing under test made it.
        // It lives in the SAME zone as the tenant's own names: the zone is not the unit of
        // authority, so a scope that leaked would leak this row.
        var recordModel = Models.get(DnsRecordModel.class);
        Row foreignRecord = recordModel.createEmptyRow();
        foreignRecord.set(DnsRecordModel.ZONE_ID, zoneId);
        foreignRecord.set(DnsRecordModel.NAME, "foreign");
        foreignRecord.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_A);
        foreignRecord.set(DnsRecordModel.VALUE, "10.0.0.66");
        foreignRecord.set(DnsRecordModel.TTL, 300);
        foreignRecord.set(DnsRecordModel.ENABLED, true);
        recordModel.save(foreignRecord);
        foreignRecordId = foreignRecord.get(DnsRecordModel.ID);

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
        tenantSession = session.token().secret();

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

    /**
     * A dyndns2 update, credential in HTTP Basic auth.
     *
     * AIDEV-NOTE: the {@code ?token=} query fallback was REMOVED (it leaked the DNS-write
     * credential into access logs and Referer headers), and this test kept spelling it --
     * every update answered badauth. Basic auth is the only way to present the token now;
     * DynamicDnsTest.publicNicUpdateRouteWorksWithBasicAuth pins that the query form stays
     * refused. Never put the token back in the URL here.
     */
    private HttpResponse<String> nicUpdate(String token, String query) throws Exception {
        String basic = Base64.getEncoder().encodeToString(
            ("dyndns:" + token).getBytes(StandardCharsets.UTF_8));
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/nic/update?" + query))
            .header("Authorization", "Basic " + basic)
            .build(), HttpResponse.BodyHandlers.ofString());
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
     * The two halves of a tenant DNS write: the NAME must be one the tenant already answers
     * for ({@code HostnameAuthority}, the same predicate a certificate order asks) and the
     * TYPE must be inside the delegated allow-list. Re-homing between zones and relabelling
     * who manages a row stay refused; the same writes as an operator are unaffected.
     */
    @Test
    @Order(5)
    void tenantDnsWritesNeedNameAuthorityAndStayInsideTheTypeAllowList() {
        Model model = Models.get(DnsRecordModel.class);

        // 1. An allow-listed type under a hostname the tenant SERVES saves.
        assertThatCode(() -> TenantConduits.as(tenantPrincipal,
            () -> model.save(record(model, "owned", DnsRecordModel.TYPE_A, "10.0.0.1"))))
            .as("A under a managed hostname is inside both halves").doesNotThrowAnyException();

        // 2. The SAME type under a name the tenant does not serve is refused -- the zone is
        //    not the unit of authority, the hostname is.
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal,
            () -> model.save(record(model, "foreign", DnsRecordModel.TYPE_A, "10.0.0.166"))))
            .as("another tenant's hostname is not a name this tenant may author")
            .isInstanceOf(Violations.class);
        assertThat(model.find().where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.NAME.eq("foreign"))
            .where(DnsRecordModel.VALUE.eq("10.0.0.166")).first())
            .as("and the refused write left no row").isNull();

        // 3. So is a name nothing on this installation serves at all (fails closed on
        //    absence, exactly like a certificate order for an unserved name).
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal,
            () -> model.save(record(model, "unserved", DnsRecordModel.TYPE_A, "10.0.0.67"))))
            .as("an unserved name has no owner, so nobody but an operator may claim it")
            .isInstanceOf(Violations.class);

        // 4. The zone-compromise primitives are refused even on the tenant's OWN name: NS
        //    delegates a subtree away, CAA redirects issuance, MX re-points mail.
        for (String type : List.of(DnsRecordModel.TYPE_NS, DnsRecordModel.TYPE_CAA,
                DnsRecordModel.TYPE_MX)) {
            String value = switch (type) {
                case DnsRecordModel.TYPE_CAA -> "0 issue \"letsencrypt.org\"";
                default -> "attacker.example.com.";
            };
            assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal, () -> {
                Row row = record(model, "owned", type, value);
                row.set(DnsRecordModel.PRIORITY, 10);
                model.save(row);
            })).as("%s is not a delegable record type", type).isInstanceOf(Violations.class);
            assertThat(model.find().where(DnsRecordModel.TYPE.eq(type))
                .where(DnsRecordModel.ZONE_ID.eq(zoneId)).first())
                .as("a refused %s write leaves no row behind", type).isNull();
        }

        // 5. An operator authors the same NS row without trouble -- the allow-list is about
        //    delegation, not about the record type being invalid.
        Row operatorNs = record(model, "sub", DnsRecordModel.TYPE_NS, "ns1.example.com.");
        assertThatCode(() -> TenantConduits.as(adminPrincipal, () -> model.save(operatorNs)))
            .as("an operator may author NS").doesNotThrowAnyException();

        // 6. A tenant cannot launder that row in by RETYPING it, in either direction.
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(operatorNs.get(DnsRecordModel.ID));
            row.set(DnsRecordModel.TYPE, DnsRecordModel.TYPE_A);
            row.set(DnsRecordModel.VALUE, "10.0.0.9");
            model.save(row);
        })).as("editing a row whose STORED type is outside the allow-list is refused")
            .isInstanceOf(Violations.class);

        // 7. Nor delete it, while its own A row deletes fine.
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal,
            () -> model.delete(model.findById(operatorNs.get(DnsRecordModel.ID)))))
            .as("deleting an NS row is the same zone-compromise primitive as writing one")
            .isInstanceOf(Violations.class);
        assertThat(model.findById(operatorNs.get(DnsRecordModel.ID)))
            .as("the operator NS row survived").isNotNull();

        // 8. Re-homing a row between zones is a takeover primitive.
        Row own = model.find().where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.NAME.eq("owned")).first();
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(own.get(DnsRecordModel.ID));
            row.set(DnsRecordModel.ZONE_ID, zoneId + 1000);
            model.save(row);
        })).as("zone_id is frozen for a tenant").isInstanceOf(Violations.class);

        // 9. So is relabelling who manages it: managed_by decides the zone-file import's
        //    replace scope.
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(own.get(DnsRecordModel.ID));
            row.set(DnsRecordModel.MANAGED_BY, DnsRecordModel.MANAGED_BY_ACME);
            model.save(row);
        })).as("managed_by is frozen for a tenant").isInstanceOf(Violations.class);

        // 10. An ordinary value edit on its own A row still saves.
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

        HttpResponse<String> good = nicUpdate(token, "hostname="
            + URLEncoder.encode("router." + ZONE_ORIGIN, StandardCharsets.UTF_8)
            + "&myip=203.0.113.7");
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

        HttpResponse<String> refused = nicUpdate(mxToken, "hostname="
            + URLEncoder.encode("mail." + ZONE_ORIGIN, StandardCharsets.UTF_8)
            + "&myip=203.0.113.8");
        assertThat(refused.statusCode()).isEqualTo(200);
        assertThat(refused.body()).as("dyndns2 writes an address, so only an address record "
            + "can be dynamic").isEqualTo("dnserr");
        assertThat((String) model.findById(mistake.get(DnsRecordModel.ID)).get(DnsRecordModel.VALUE))
            .as("and nothing was written").isEqualTo("mx.example.com.");

        // 3. The anonymous lane is BOUNDED, not exempt: it may move VALUE on a stored DYNAMIC
        //    row and nothing else. Without the bound, "no principal" would be a write pass.
        Integer dynamicId = dynamic.get(DnsRecordModel.ID);
        assertThatThrownBy(() -> TenantConduits.as(null, () -> {
            Row row = model.findById(dynamicId);
            row.set(DnsRecordModel.NAME, "hijacked");
            model.save(row);
        })).as("an anonymous caller may not relabel the row its token unlocks")
            .isInstanceOf(Violations.class);
        assertThat((String) model.findById(dynamicId).get(DnsRecordModel.NAME))
            .as("and the owner name is untouched").isEqualTo("router");

        Row notDynamic = record(model, "static-a", DnsRecordModel.TYPE_A, "10.1.1.1");
        model.save(notDynamic);
        assertThatThrownBy(() -> TenantConduits.as(null, () -> {
            Row row = model.findById(notDynamic.get(DnsRecordModel.ID));
            row.set(DnsRecordModel.VALUE, "10.1.1.2");
            model.save(row);
        })).as("nor touch a row that was never dynamic").isInstanceOf(Violations.class);
        assertThat((String) model.findById(notDynamic.get(DnsRecordModel.ID))
            .get(DnsRecordModel.VALUE)).isEqualTo("10.1.1.1");

        // 4. Minting a token is its OWN capability: a tenant holding only edit is refused,
        //    and the stored digest is proof it wrote nothing.
        assertGranted(DnsRecordModel.MODEL_ID, dynamicId, HohenheimAccess.EDIT);
        assertGranted(DnsRecordModel.MODEL_ID, dynamicId, HohenheimAccess.VIEW);
        String storedDigest = model.findById(dynamicId).get(DnsRecordModel.DYNDNS_TOKEN);
        assertThat(tenantPost("/manage/dns-records/" + dynamicId + "/action/dyndns_token", "")
            .statusCode()).as("edit is not dyndns").isIn(403, 404);
        assertThat((String) model.findById(dynamicId).get(DnsRecordModel.DYNDNS_TOKEN))
            .as("the refused action minted nothing").isEqualTo(storedDigest);

        assertGranted(DnsRecordModel.MODEL_ID, dynamicId, HohenheimAccess.DYNDNS);
        assertThat(tenantPost("/manage/dns-records/" + dynamicId + "/action/dyndns_token", "")
            .statusCode()).as("the dyndns holder may re-mint").isIn(200, 302, 303);
        assertThat((String) model.findById(dynamicId).get(DnsRecordModel.DYNDNS_TOKEN))
            .as("and the stored digest actually rotated").isNotEqualTo(storedDigest);

        for (String capability : List.of(HohenheimAccess.EDIT, HohenheimAccess.VIEW,
                HohenheimAccess.DYNDNS)) {
            RecordGrants.revoke("user", tenantId, DnsRecordModel.MODEL_ID, dynamicId, capability);
        }
        TenantConduits.as(adminPrincipal, () -> {
            model.delete(model.findById(dynamicId));
            model.delete(model.findById(mistake.get(DnsRecordModel.ID)));
            model.delete(model.findById(notDynamic.get(DnsRecordModel.ID)));
        });
    }

    /**
     * The ZONE tier stays admin-only through every surface a delegated tenant can address --
     * a tenant reaches names, never the zone row that holds the DNSSEC/TSIG material -- and
     * the record/certificate tiers answer only for what the tenant is scoped to.
     */
    @Test
    @Order(7)
    void everyZoneSurfaceStaysClosedAndTheScopedTiersLeakNothing() throws Exception {
        String matchAll = Zenit.DRY.stringify(RecordSourceQuery.matchAll());

        // 1. The zone source: unauthorized and unknown refuse IDENTICALLY with 404. Zones are
        //    a PERMANENT admin-only non-goal, so there is no scoped view of one.
        assertThat(tenantDry("/zn/records/hohenheim.dns_zone/query", matchAll).statusCode())
            .as("query on hohenheim.dns_zone").isEqualTo(404);
        assertThat(tenantGet("/zn/records/hohenheim.dns_zone/item/" + zoneId).statusCode())
            .as("item on hohenheim.dns_zone").isEqualTo(404);

        // 2. The record and certificate sources ARE open to a grant holder -- and empty of
        //    everything the tenant does not answer for. Reachability is not disclosure.
        HttpResponse<String> records =
            tenantDry("/zn/records/hohenheim.dns_record/query", matchAll);
        assertThat(records.statusCode()).as("the record source is open to a grant holder")
            .isEqualTo(200);
        // By ID, not by scanning the body for a string the projection may never carry: a
        // substring assertion over a DataItem list is exactly the check that cannot fail.
        assertThat(tenantGet("/zn/records/hohenheim.dns_record/item/" + foreignRecordId)
            .statusCode())
            .as("another tenant's record does not resolve through the source").isEqualTo(404);

        HttpResponse<String> certs = tenantDry("/zn/records/hohenheim.certificate/query", matchAll);
        assertThat(certs.statusCode()).isEqualTo(200);
        assertThat(certs.body())
            .as("a certificate the tenant neither requested nor was granted stays invisible")
            .doesNotContain("Foreign tenant certificate")
            .doesNotContain("ACME account bookkeeping");
        assertThat(tenantGet("/zn/records/hohenheim.certificate/item/" + foreignCertificateId)
            .statusCode()).as("and resolves as missing by id too").isEqualTo(404);

        // 3. The ACME ACCOUNT row is excluded by the source's BASE criteria (which
        //    RecordSource ANDs into query, resolve, exists and buckets alike), so even the
        //    operator's own reads through the source cannot resolve it.
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

        // 4. Nav invisibility is not a gate: the ADMIN resources' slugs still ROUTE, and it
        //    is the panel permission that refuses.
        assertThat(tenantGet("/admin/dns-records").statusCode()).isEqualTo(403);
        assertThat(tenantGet("/admin/dns-zones/" + zoneId + "/page/records").statusCode())
            .isEqualTo(403);
        assertThat(tenantGet("/admin/certificates").statusCode()).isEqualTo(403);
        assertThat(tenantGet("/admin/certificates-request?site=" + foreignSiteId).statusCode())
            .isEqualTo(403);

        // 5. The delegated panel has a records and a certificates peer -- and NO zone or peer
        //    surface, in either direction, permanently.
        for (String slug : List.of("dns-zones", "dns-peers")) {
            assertThat(tenantGet("/manage/" + slug).statusCode())
                .as("/manage/%s is not a delegated surface", slug).isEqualTo(404);
        }
        HttpResponse<String> manageRecords = tenantGet("/manage/dns-records");
        assertThat(manageRecords.statusCode()).isEqualTo(200);
        assertThat(manageRecords.body())
            .as("the delegated record list carries no foreign name")
            .doesNotContain("foreign.tenantscope.test").doesNotContain("ns1.example.com");
        HttpResponse<String> manageCerts = tenantGet("/manage/certificates");
        assertThat(manageCerts.statusCode()).isEqualTo(200);
        assertThat(manageCerts.body())
            .as("the delegated certificate list carries no foreign certificate")
            .doesNotContain("Foreign tenant certificate");

        // 6. The peer/automation API refuses a session principal outright, tenant or not.
        assertThat(tenantGet("/api/dns/zones/" + ZONE_ORIGIN + "/records").statusCode())
            .as("the DNS API is admin-permissioned AND api-key-only").isIn(403, 404);
        assertThat(tenantPost("/api/dns/zones/" + ZONE_ORIGIN + "/records",
            "name=x&type=NS&value=ns.attacker.example.com.").statusCode()).isIn(403, 404);

        // 7. The zone-file import replaces every non-generated row in a zone in one POST.
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
     * The grant-store enumerations must stay inside the per-request memo. Rendering
     * /manage/domains asks for the managed-site set through the panel gate, the resource
     * AccessFunction, the record source and the nav probe -- and, since the DNS and
     * certificate peers joined the panel, for their own (model, capability) sets through
     * their nav probes. The budget is one enumeration per DISTINCT set plus the walk
     * confirmations, never one per asker: the memo is a MAP keyed by model+capability, so a
     * fourth peer must not multiply this number by the number of times it is asked.
     */
    @Test
    @Order(8)
    void theDomainScopeStaysWithinTheManagedSiteQueryBudget() throws Exception {
        java.util.concurrent.atomic.AtomicInteger finds = new java.util.concurrent.atomic.AtomicInteger();
        be.elevenways.zenit.auth.model.RecordGrantModel.SCHEMA
            .addBeforeFindHook(ignored -> finds.incrementAndGet());

        finds.set(0);
        assertThat(tenantGet("/manage/domains").statusCode()).isEqualTo(200);
        // AIDEV-NOTE: the cap moved 6 -> 12 when /manage grew the instance projection
        // (2026-08-04): the panel asks about six distinct capability sets per render
        // now, not three. Each is still enumerated ONCE per request; the number this
        // pins against is the un-memoized one, which is per CALLER and far outside it.
        assertThat(finds.get())
            .as("record-grant finds during one scoped /manage/domains render "
                + "(6 distinct capability sets + walk confirmations)")
            .isBetween(1, 12);
    }

    /**
     * The delegated DNS surface end to end, through the HTTP resource rather than the model:
     * a tenant authors a name it serves by typing the ABSOLUTE name (it never learns a zone
     * row exists), edits and deletes it, and is missing on another tenant's row through both
     * the resource route and the record source.
     */
    @Test
    @Order(9)
    void aTenantAuthorsEditsAndDeletesRecordsUnderTheHostnamesItServes() throws Exception {
        Model model = Models.get(DnsRecordModel.class);

        // 1. Create by absolute name: no zone picker anywhere in the transport.
        assertThat(tenantPost("/manage/dns-records/new",
            "name=owned.tenantscope.test&type=A&value=10.0.0.5&ttl=300&enabled=true")
            .statusCode()).as("a tenant may author a name it serves").isIn(302, 303);
        Row created = model.find().where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.NAME.eq("owned")).first();
        assertThat(created).as("the row persisted, split into (zone, relative owner)").isNotNull();
        assertThat((String) created.get(DnsRecordModel.VALUE)).isEqualTo("10.0.0.5");

        // 2. A name it does NOT serve writes nothing, whatever the status page says.
        tenantPost("/manage/dns-records/new",
            "name=foreign.tenantscope.test&type=A&value=10.0.0.99&ttl=300&enabled=true");
        assertThat(model.find().where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.NAME.eq("foreign"))
            .where(DnsRecordModel.VALUE.eq("10.0.0.99")).first())
            .as("the hostname is the unit of authority, not the zone").isNull();

        // 3. Another tenant's row is MISSING through the resource and through the source.
        assertThat(tenantGet("/manage/dns-records/" + foreignRecordId).statusCode())
            .as("a foreign record is 404, never 403 -- no existence oracle").isEqualTo(404);
        assertThat(tenantGet("/zn/records/hohenheim.dns_record/item/" + foreignRecordId)
            .statusCode()).as("and missing through the source too").isEqualTo(404);
        assertThat(tenantPost("/manage/dns-records/" + foreignRecordId + "/delete", "")
            .statusCode()).isEqualTo(404);
        assertThat(model.findById(foreignRecordId)).as("and it is still there").isNotNull();

        // 4. Its own row edits and deletes.
        assertThat(tenantPost("/manage/dns-records/" + created.get(DnsRecordModel.ID),
            "name=owned.tenantscope.test&type=A&value=10.0.0.6&ttl=300&enabled=true")
            .statusCode()).isIn(302, 303);
        assertThat((String) model.findById(created.get(DnsRecordModel.ID))
            .get(DnsRecordModel.VALUE)).isEqualTo("10.0.0.6");
        assertThat(tenantPost("/manage/dns-records/" + created.get(DnsRecordModel.ID) + "/delete",
            "").statusCode()).isIn(302, 303);
        assertThat(model.findById(created.get(DnsRecordModel.ID)))
            .as("a tenant may remove its own row").isNull();

        // 5. An explicit view grant is the SECOND lane, and it is view-only: the row becomes
        //    reachable, and a write is still refused because view is not edit.
        assertGranted(DnsRecordModel.MODEL_ID, foreignRecordId, HohenheimAccess.VIEW);
        assertThat(tenantGet("/zn/records/hohenheim.dns_record/item/" + foreignRecordId)
            .statusCode()).as("a view grant reaches exactly one foreign row").isEqualTo(200);
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(foreignRecordId);
            row.set(DnsRecordModel.VALUE, "10.0.0.77");
            model.save(row);
        })).as("view is not edit").isInstanceOf(Violations.class);
        assertThat((String) model.findById(foreignRecordId).get(DnsRecordModel.VALUE))
            .as("and the refused write left the value alone").isEqualTo("10.0.0.66");

        // 6. An edit grant authorizes the row -- but NOT a rename onto a third name, which is
        //    the claim half and answers to hostname authority alone.
        assertGranted(DnsRecordModel.MODEL_ID, foreignRecordId, HohenheimAccess.EDIT);
        assertThatCode(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(foreignRecordId);
            row.set(DnsRecordModel.VALUE, "10.0.0.78");
            model.save(row);
        })).as("an edit grant is authority over the row").doesNotThrowAnyException();
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(foreignRecordId);
            row.set(DnsRecordModel.NAME, "takeover");
            model.save(row);
        })).as("a grant on a row is never authority over a name it never covered")
            .isInstanceOf(Violations.class);
        assertThat((String) model.findById(foreignRecordId).get(DnsRecordModel.NAME))
            .isEqualTo("foreign");

        // 7. Revoking both puts the row back out of reach on every surface.
        RecordGrants.revoke("user", tenantId, DnsRecordModel.MODEL_ID, foreignRecordId,
            HohenheimAccess.VIEW);
        RecordGrants.revoke("user", tenantId, DnsRecordModel.MODEL_ID, foreignRecordId,
            HohenheimAccess.EDIT);
        assertThat(tenantGet("/zn/records/hohenheim.dns_record/item/" + foreignRecordId)
            .statusCode()).as("a revoked grant is a closed door again").isEqualTo(404);
        assertThatThrownBy(() -> TenantConduits.as(tenantPrincipal, () -> {
            Row row = model.findById(foreignRecordId);
            row.set(DnsRecordModel.VALUE, "10.0.0.79");
            model.save(row);
        })).as("and the write lane closed with it").isInstanceOf(Violations.class);
    }

    /**
     * Certificate {@code view}: a tenant sees the certificates it REQUESTED and nothing else,
     * and the delegated surface never carries key material or a way to obtain it.
     */
    @Test
    @Order(10)
    void aTenantSeesOnlyItsOwnCertificatesAndNeverAPrivateKey() throws Exception {
        var certModel = Models.get(CertificateModel.class);

        Row mine = certModel.createEmptyRow();
        mine.set(CertificateModel.NICE_NAME, "Tenant requested certificate");
        mine.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
        mine.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        mine.set(CertificateModel.DOMAIN_NAMES_TEXT, "owned.tenantscope.test");
        mine.set(CertificateModel.REQUESTED_BY_USER_ID, tenantId);
        mine.set(CertificateModel.CERTIFICATE_PEM, "-----BEGIN CERTIFICATE-----ownedcert");
        mine.set(CertificateModel.PRIVATE_KEY_PEM, "-----BEGIN PRIVATE KEY-----ownedsecret");
        certModel.save(mine);
        Integer mineId = mine.get(CertificateModel.ID);

        // 1. The requester is the owner: its own certificate is listed, the other tenant's is
        //    not, and neither list nor detail carries the key.
        HttpResponse<String> list = tenantGet("/manage/certificates");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body())
            .contains("Tenant requested certificate")
            .doesNotContain("Foreign tenant certificate")
            .doesNotContain("ownedsecret");

        HttpResponse<String> detail = tenantGet("/manage/certificates/" + mineId);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body())
            .as("status is delegable; the PEMs are not, on any surface")
            .contains("owned.tenantscope.test")
            .doesNotContain("ownedsecret")
            .doesNotContain("BEGIN PRIVATE KEY");

        // 2. Another tenant's certificate is missing through the delegated resource.
        assertThat(tenantGet("/manage/certificates/" + foreignCertificateId).statusCode())
            .as("a foreign certificate is 404, never 403").isEqualTo(404);

        // 3. The download endpoint is the private-key export. The delegated surface neither
        //    OFFERS it (no row action, so no link) nor would it help if forged: key export is
        //    never delegable and never owner-implied, so even the OWNER is refused.
        assertThat(detail.body())
            .as("the delegated detail page offers no export affordance")
            .doesNotContain("/certificates/" + mineId + "/download");
        assertThat(tenantGet("/certificates/" + mineId + "/download").statusCode())
            .as("a tenant never obtains a private key, not even its own certificate's")
            .isIn(403, 404);

        certModel.delete(certModel.findById(mineId));
    }

    /** Grant and PROVE it landed: grant() over a live deny is a documented silent no-op. */
    private static void assertGranted(be.elevenways.protoblast.common.registry.Identifier model,
                                      Object recordId, String capability) {
        Row written = RecordGrants.grant("user", tenantId, model, recordId, capability, true);
        assertThat((Boolean) written.get(
            be.elevenways.zenit.auth.model.RecordGrantModel.VALUE))
            .as("the %s grant actually landed", capability).isTrue();
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
