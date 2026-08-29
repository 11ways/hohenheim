package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.test.source.TestSources;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.GrantModel;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.PermissionGroupModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.data.RecordSourceBucketQuery;
import be.elevenways.zenit.common.data.RecordSourceQuery;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The delegated /manage panel plus the /admin grants UI end-to-end: granting
 * through the Access tab unlocks exactly one site's safe edit/operate surface,
 * domains stay read-only, and effective group/negative grants drive eligibility.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ManagePanelTest extends HohenheimTestBase {

    private static Integer siteAId;
    private static Integer appAId;
    private static Integer siteBId;
    private static Integer domainAId;
    private static Integer operatorId;
    private static String operatorSession;
    private static String operatorCsrf;

    @BeforeAll
    static void seedSitesAndOperator() {
        var siteModel = Models.get(SiteModel.class);

        Row siteA = siteModel.createEmptyRow();
        siteA.set(SiteModel.NAME, "Manage Site A");
        siteA.set(SiteModel.SLUG, "manage-site-a");
        siteA.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        siteA.set(SiteModel.SETTINGS, Map.of(
            "root_path", "/srv/manage-a",
            "system_user_id", "hohenheim:site-a",
            "environment_variables", Map.of("DAEMON_SECRET", "must-not-render"),
            "command", "unsafe-host-command"));
        siteA.set(SiteModel.STATUS, "active");
        siteA.set(SiteModel.ENABLED, true);
        // The source lives on the application instance the site exposes now (phase-0
        // design section 3); the Deploys tab lives on that instance too.
        appAId = TestSources.attachGitSource(siteA, Map.of(
            "repository_url", "ssh://private/manage-a.git",
            "build_command", "private-build-command",
            "webhook_secret", "private-webhook-secret"));
        siteModel.save(siteA);
        siteAId = siteA.get(SiteModel.ID);

        Row siteB = siteModel.createEmptyRow();
        siteB.set(SiteModel.NAME, "Manage Site B");
        siteB.set(SiteModel.SLUG, "manage-site-b");
        siteB.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        siteB.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        siteB.set(SiteModel.STATUS, "active");
        siteB.set(SiteModel.ENABLED, true);
        siteModel.save(siteB);
        siteBId = siteB.get(SiteModel.ID);

        Row domain = Models.get(SiteDomainModel.class).createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteAId);
        domain.set(SiteDomainModel.HOSTNAME, "managed.example.com");
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        Models.get(SiteDomainModel.class).save(domain);
        domainAId = domain.get(SiteDomainModel.ID);

        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, "operator@hohenheim.local");
        user.set(UserModel.DISPLAY_NAME, "Site Operator");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        operatorId = user.get(UserModel.ID);

        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, operatorId.longValue());
        operatorCsrf = ZenitAuth.randomToken();
        session.set(CsrfTokens.TOKEN, operatorCsrf);
        Zenit.getSessionStore().save(session);
        operatorSession = session.token().secret();
    }

    private HttpResponse<String> get(String path, String session) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session)
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String session, String csrf) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session)
            .header("X-Csrf-Token", csrf)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> adminPost(String path, String body) throws Exception {
        return post(path, body, sessionToken, csrfToken);
    }

    private HttpResponse<String> operatorGet(String path) throws Exception {
        return get(path, operatorSession);
    }

    private HttpResponse<String> operatorPost(String path, String body) throws Exception {
        return post(path, body, operatorSession, operatorCsrf);
    }

    /** Ungranted -> granted through the Access tab: exactly one site's delegated surface opens up. */
    @Test
    @Order(1)
    void grantingThroughTheAccessTabUnlocksExactlyTheGrantedSite() throws Exception {
        assertThat(operatorGet("/manage").statusCode()).isEqualTo(403);
        assertThat(operatorGet("/manage/sites").statusCode()).isEqualTo(403);
        // A manage-less login has NO accessible panel: the landing refuses
        // (403), it never redirects -- a redirect could only loop.
        assertThat(operatorGet("/").statusCode()).isEqualTo(403);

        // The GENERIC record-access matrix (zenit-auth's contributed subpage)
        // is the grant surface -- the hand-written SiteAccessPage is deleted.
        HttpResponse<String> add = adminPost(
            "/admin/sites/" + siteAId + "/page/access",
            "access.0.type=user&access.0.id=" + operatorId
                + "&access.0.caps.0.key=manage&access.0.caps.0.value=allow");
        assertThat(add.statusCode()).isIn(302, 303);

        // The Access tab IS the generic page: only it renders the
        // pl-capability-matrix, with the new grant as a subject row.
        HttpResponse<String> pageView = adminGet("/admin/sites/" + siteAId + "/page/access");
        assertThat(pageView.statusCode()).isEqualTo(200);
        assertThat(pageView.body()).contains("<pl-capability-matrix");
        assertThat(pageView.body()).contains("data-subject=\"user:" + operatorId + "\"");
        assertThat(pageView.body()).contains("Site Operator");

        // Panel eligibility is derived from the effective record grant; no
        // second global grant is created or synchronized.
        UserPrincipal principal = new UserPrincipal(operatorId, "Site Operator");
        assertThat(HohenheimAccess.managedSiteIds(principal)).containsExactly(siteAId);
        assertThat(GrantService.listDirectGrants(GrantSubjectType.USER, operatorId))
            .noneMatch(grant -> "hohenheim.manage.access".equals(grant.get(GrantModel.PERMISSION)));

        assertThat(operatorGet("/manage").statusCode()).isIn(200, 302, 303);

        // Post-login landing: the manage-only principal lands on /manage,
        // the operator (admin) keeps landing on /admin (lower landingWeight).
        HttpResponse<String> operatorLanding = operatorGet("/");
        assertThat(operatorLanding.statusCode()).isIn(302, 303);
        assertThat(operatorLanding.headers().firstValue("Location")).hasValue("/manage");
        HttpResponse<String> adminLanding = adminGet("/");
        assertThat(adminLanding.statusCode()).isIn(302, 303);
        assertThat(adminLanding.headers().firstValue("Location")).hasValue("/admin");

        HttpResponse<String> list = operatorGet("/manage/sites");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("Manage Site A");
        assertThat(list.body()).doesNotContain("Manage Site B");
        assertThat(list.body()).doesNotContain("data-column=\"upstream_kind\"");
        // Safe row actions only.
        assertThat(list.body()).contains("toggle_site");
        assertThat(list.body()).doesNotContain("clone_site");

        // The delegated surface offers the SAME generic access tab (a manage
        // holder may delegate its delegable capability from /manage).
        HttpResponse<String> manageAccess = operatorGet(
            "/manage/sites/" + siteAId + "/page/access");
        assertThat(manageAccess.statusCode()).isEqualTo(200);
        assertThat(manageAccess.body()).contains("<pl-capability-matrix");

        // The tenant's subject picker is an EXACT lookup, never the directory: the
        // administrator's address (a fact of the installation, not of site A) is in the
        // admin's page, which ships the listing, and nowhere in the tenant's, which ships
        // the lookup and its own copy (finding F4).
        assertThat(pageView.body()).contains("test@hohenheim.local");
        assertThat(manageAccess.body()).doesNotContain("test@hohenheim.local");
        assertThat(manageAccess.body()).contains("Exact email address");

        // Site B's manage detail URL reads as missing, not forbidden.
        assertThat(operatorGet("/manage/sites/" + siteBId).statusCode()).isEqualTo(404);

        // The admin panel stays closed to the operator.
        assertThat(operatorGet("/admin").statusCode()).isEqualTo(403);
        assertThat(operatorGet("/admin/sites").statusCode()).isEqualTo(403);

        // The manage-scoped site picker offers only granted sites.
        String body = Zenit.DRY.stringify(RecordSourceQuery.matchAll());
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/zn/records/hohenheim.site/query"))
            .header("Content-Type", "application/dry")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + operatorSession)
            .header("X-Csrf-Token", operatorCsrf)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> picker = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(picker.statusCode()).isEqualTo(200);
        assertThat(picker.body()).contains("Manage Site A");
        assertThat(picker.body()).doesNotContain("Manage Site B");
    }

    /** The delegated surface never exposes or accepts execution controls, secrets or domain writes. */
    @Test
    @Order(2)
    void delegatedSurfaceStaysSafeAndReturnsToManage() throws Exception {
        HttpResponse<String> form = operatorGet("/manage/sites/" + siteAId);
        assertThat(form.statusCode()).isEqualTo(200);
        assertThat(form.body())
            .doesNotContain("name=\"upstream_kind\"")
            .doesNotContain("name=\"source\"")
            .doesNotContain("name=\"settings.root_path\"")
            .doesNotContain("name=\"source_settings.build_command\"")
            .doesNotContain("must-not-render")
            .doesNotContain("private-webhook-secret")
            .doesNotContain("name=\"settings.system_user_id\"");

        HttpResponse<String> response = operatorPost("/manage/sites/" + siteAId,
            "name=Manage+Site+A+Edited&enabled=false&description=Delegated+description"
                + "&upstream_kind=hohenheim%3Acommand"
                + "&settings.root_path=%2Ftmp%2Fhijacked"
                + "&settings.command=cat+%2Fetc%2Fshadow"
                + "&settings.system_user_id=hohenheim%3Aroot"
                + "&settings.environment_variables.DAEMON_SECRET=stolen"
                + "&source_settings.repository_url=ssh%3A%2F%2Fattacker%2Frepo.git"
                + "&source_settings.build_command=malicious");
        assertThat(response.statusCode()).isIn(302, 303);

        Row site = Models.get(SiteModel.class).findById(siteAId);
        assertThat(site.get(SiteModel.NAME)).isEqualTo("Manage Site A Edited");
        assertThat(site.get(SiteModel.ENABLED)).isFalse();
        assertThat(site.get(SiteModel.DESCRIPTION)).isEqualTo("Delegated description");
        assertThat(site.get(SiteModel.UPSTREAM_KIND)).isEqualTo("hohenheim:instance");
        assertThat(site.get(SiteModel.SETTINGS)).isEqualTo(Map.of(
            "root_path", "/srv/manage-a",
            "system_user_id", "hohenheim:site-a",
            "environment_variables", Map.of("DAEMON_SECRET", "must-not-render"),
            "command", "unsafe-host-command"));

        // The Deploys tab lives on the APPLICATION instance now; reaching it through
        // /manage takes an instance grant, exactly like production delegation does.
        // (Revoked again below: a lingering instance grant would keep this principal
        // ELIGIBLE for /manage and break the eligibility journey that runs later.)
        RecordGrants.grant(GrantSubjectType.USER, operatorId, InstanceModel.MODEL_ID, appAId,
            HohenheimAccess.MANAGE, true);
        try {
        HttpResponse<String> delegated = operatorGet(
            "/manage/instances/" + appAId + "/page/deployments");
        assertThat(delegated.statusCode()).isEqualTo(200);
        assertThat(delegated.body())
            .doesNotContain("private-webhook-secret")
            .doesNotContain("data-webhook-secret")
            .doesNotContain("data-webhook-url");

        // The manage-rendered page embeds ITS OWN URL as the _return target,
        // so deploy actions bounce back to /manage, not /admin.
        assertThat(delegated.body())
            .contains("name=\"_return\"")
            .contains("value=\"/manage/instances/" + appAId + "/page/deployments\"");

        HttpResponse<String> admin = adminGet(
            "/admin/instances/" + appAId + "/page/deployments");
        assertThat(admin.statusCode()).isEqualTo(200);
        assertThat(admin.body()).contains("private-webhook-secret").contains("data-webhook-secret");

        // No proxy runs in this suite, so deploy is redirect-only: the handler
        // finds no git handler and just answers with the return redirect.
        String manageTarget = "/manage/instances/" + appAId + "/page/deployments";
        HttpResponse<String> fromManage = operatorPost("/instances/" + appAId + "/deploy",
            "_return=" + java.net.URLEncoder.encode(manageTarget, java.nio.charset.StandardCharsets.UTF_8));
        assertThat(fromManage.statusCode()).isIn(302, 303);
        assertThat(fromManage.headers().firstValue("Location")).hasValue(manageTarget);

        // A forged _return can never open-redirect: unsafe values fall back
        // to the admin page.
        HttpResponse<String> forged = operatorPost("/instances/" + appAId + "/deploy",
            "_return=" + java.net.URLEncoder.encode("https://evil.example/", java.nio.charset.StandardCharsets.UTF_8));
        assertThat(forged.statusCode()).isIn(302, 303);
        assertThat(forged.headers().firstValue("Location"))
            .hasValue("/admin/instances/" + appAId + "/page/deployments");
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, operatorId, InstanceModel.MODEL_ID,
                appAId, HohenheimAccess.MANAGE);
        }

        HttpResponse<String> subpage = operatorGet("/manage/sites/" + siteAId + "/page/domains");
        assertThat(subpage.statusCode()).isEqualTo(200);
        // Binding a hostname to a managed site is delegated; REQUESTING a certificate for it
        // stays installation administration (an issued certificate is authority over a name).
        assertThat(subpage.body()).contains("managed.example.com").contains("add-domain-link")
            .doesNotContain("certificates-request");

        // The delegated record form is WRITABLE now, but offers only the delegated columns.
        // That omission is UX: the freeze itself lives in the SiteDomainModel write pipeline
        // and is proven against a direct model save in TenantDomainDnsScopeTest.
        HttpResponse<String> domainForm = operatorGet("/manage/domains/" + domainAId);
        assertThat(domainForm.statusCode()).isEqualTo(200);
        assertThat(domainForm.body())
            .contains("cms__snapshot")
            .doesNotContain("name=\"listen_on\"")
            .doesNotContain("name=\"match_type\"")
            .doesNotContain("name=\"path\"")
            .doesNotContain("name=\"certificate_id\"");
        assertThat(operatorPost("/manage/domains/new",
            "site_id=" + siteAId + "&hostname=delegated.example.com").statusCode())
            .isIn(302, 303);
        // A forged non-exact match type never lands: the form drops it and the pipeline
        // refuses an effective value other than exact.
        assertThat(operatorPost("/manage/domains/" + domainAId,
            "site_id=" + siteAId + "&hostname=managed.example.com&match_type=regex").statusCode())
            .isIn(200, 302, 303, 422);

        Row domain = Models.get(SiteDomainModel.class).findById(domainAId);
        assertThat(domain.get(SiteDomainModel.HOSTNAME)).isEqualTo("managed.example.com");
        assertThat(domain.get(SiteDomainModel.MATCH_TYPE)).isEqualTo(SiteDomainModel.MATCH_EXACT);

        Row boundDomain = Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq("delegated.example.com")).first();
        assertThat(boundDomain).isNotNull();
        assertThat(boundDomain.get(SiteDomainModel.SITE_ID)).isEqualTo(siteAId);
        assertThat(operatorPost("/manage/domains/" + boundDomain.get(SiteDomainModel.ID) + "/delete",
            confirmed(""))
            .statusCode()).isIn(302, 303);
        assertThat(Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq("delegated.example.com")).first()).isNull();
    }

    /** Revocation, group/negative record grants and an explicit global deny all drive eligibility. */
    @Test
    @Order(3)
    void recordAndGlobalGrantsDrivePanelEligibility() throws Exception {
        GrantService.createDirectGrant(GrantSubjectType.USER, operatorId, "hohenheim.manage.access", true);
        HttpResponse<String> remove = adminPost("/admin/sites/" + siteAId + "/page/access",
            "access.__removed=" + java.net.URLEncoder.encode("user:" + operatorId,
                java.nio.charset.StandardCharsets.UTF_8));
        assertThat(remove.statusCode()).isIn(302, 303);

        // The matrix has no subject row for the operator anymore.
        HttpResponse<String> pageView = adminGet("/admin/sites/" + siteAId + "/page/access");
        assertThat(pageView.statusCode()).isEqualTo(200);
        assertThat(pageView.body()).doesNotContain("data-subject=\"user:" + operatorId + "\"");

        // The independently administered panel grant remains untouched.
        assertThat(GrantService.listDirectGrants(GrantSubjectType.USER, operatorId))
            .anyMatch(grant -> "hohenheim.manage.access".equals(grant.get(GrantModel.PERMISSION)));
        // The panel stays reachable, and its landing is the manage DASHBOARD now
        // (the first accessible DashboardPanelPeer wins the index), so /manage
        // redirects there rather than rendering a card grid.
        HttpResponse<String> landing = operatorGet("/manage");
        assertThat(landing.statusCode()).isIn(302, 303);
        assertThat(landing.headers().firstValue("Location")).hasValue("/manage/dashboard");

        // NAV-ONLY hiding: the panel grant without any site keeps /manage
        // reachable, but the zero-in-scope Sites/Domains entries leave the nav
        // of the dashboard landing...
        HttpResponse<String> emptyPanel = operatorGet("/manage/dashboard");
        assertThat(emptyPanel.statusCode()).isEqualTo(200);
        assertThat(emptyPanel.body()).doesNotContain("href=\"/manage/sites\"");
        assertThat(emptyPanel.body()).doesNotContain("href=\"/manage/domains\"");
        // ...while the direct URL still answers with the scoped (empty) list:
        // hiding must never become enforcement.
        assertThat(operatorGet("/manage/sites").statusCode()).isEqualTo(200);

        for (Row grant : GrantService.listDirectGrants(GrantSubjectType.USER, operatorId)) {
            if ("hohenheim.manage.access".equals(grant.get(GrantModel.PERMISSION))) {
                GrantService.deleteDirectGrant(GrantSubjectType.USER, operatorId, grant.get(GrantModel.ID));
            }
        }
        assertThat(operatorGet("/manage").statusCode()).isEqualTo(403);
        assertThat(operatorGet("/manage/sites").statusCode()).isEqualTo(403);

        Row group = AuthModels.permissionGroups().createEmptyRow();
        group.set(PermissionGroupModel.SLUG, "manage-operators");
        group.set(PermissionGroupModel.TITLE, "Manage Operators");
        AuthModels.permissionGroups().save(group);
        Integer groupId = group.get(PermissionGroupModel.ID);

        GrantService.createDirectGrant(GrantSubjectType.USER, operatorId, "group.manage-operators", true);
        RecordGrants.grant(GrantSubjectType.GROUP, groupId, SiteModel.MODEL_ID, siteBId,
            HohenheimAccess.MANAGE, true);

        assertThat(operatorGet("/manage").statusCode()).isIn(200, 302, 303);
        assertThat(operatorGet("/manage/sites").body()).contains("Manage Site B");

        RecordGrants.grant(GrantSubjectType.USER, operatorId, SiteModel.MODEL_ID, siteBId,
            HohenheimAccess.MANAGE, false);
        assertThat(operatorGet("/manage").statusCode()).isEqualTo(403);
        assertThat(HohenheimAccess.managedSiteIds(new UserPrincipal(operatorId, "Site Operator")))
            .isEmpty();

        RecordGrants.revoke(GrantSubjectType.USER, operatorId, SiteModel.MODEL_ID, siteBId, HohenheimAccess.MANAGE);
        assertThat(operatorGet("/manage").statusCode()).isIn(200, 302, 303);
        RecordGrants.revoke(GrantSubjectType.GROUP, groupId, SiteModel.MODEL_ID, siteBId, HohenheimAccess.MANAGE);
        assertThat(operatorGet("/manage").statusCode()).isEqualTo(403);

        // An explicit global deny beats a record grant, while its absence falls back.
        RecordGrants.grant(GrantSubjectType.USER, operatorId, SiteModel.MODEL_ID, siteAId,
            HohenheimAccess.MANAGE, true);
        GrantService.createDirectGrant(GrantSubjectType.USER, operatorId, "hohenheim.manage.access", false);

        assertThat(operatorGet("/manage").statusCode()).isEqualTo(403);
        // The deploy verb is instance-keyed now and answers to the POWER capability on
        // the record; a site grant confers nothing on it.
        assertThat(operatorPost("/instances/" + appAId + "/deploy", "").statusCode()).isEqualTo(403);
        UserPrincipal principal = new UserPrincipal(operatorId, "Site Operator");
        assertThat(HohenheimAccess.managedSiteIds(principal)).isEmpty();
        assertThat(HohenheimAccess.canManageSite(principal, siteAId)).isFalse();

        for (Row grant : GrantService.listDirectGrants(GrantSubjectType.USER, operatorId)) {
            if ("hohenheim.manage.access".equals(grant.get(GrantModel.PERMISSION))) {
                GrantService.deleteDirectGrant(GrantSubjectType.USER, operatorId, grant.get(GrantModel.ID));
            }
        }
        assertThat(operatorGet("/manage").statusCode()).isIn(200, 302, 303);
        RecordGrants.revoke(GrantSubjectType.USER, operatorId, SiteModel.MODEL_ID, siteAId, HohenheimAccess.MANAGE);
    }

    /**
     * Boundary 4 (tenant A data <- tenant B): a logged-in principal with NO
     * grants gets 403 or provably empty results on EVERY hohenheim source,
     * for EVERY record-source operation -- query, item, vocabulary, buckets.
     */
    @Test
    @Order(5)
    void ungrantedLoginGetsNothingFromAnyHohenheimSource() throws Exception {
        Row outsider = AuthModels.users().createEmptyRow();
        outsider.set(UserModel.EMAIL, "outsider@hohenheim.local");
        outsider.set(UserModel.DISPLAY_NAME, "No Grants");
        outsider.set(UserModel.ENABLED, true);
        outsider.set(UserModel.CREATED_AT, Instant.now());
        outsider.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(outsider);

        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, outsider.get(UserModel.ID).longValue());
        String csrf = ZenitAuth.randomToken();
        session.set(CsrfTokens.TOKEN, csrf);
        Zenit.getSessionStore().save(session);
        String outsiderSession = session.token().secret();

        String query = Zenit.DRY.stringify(RecordSourceQuery.matchAll());
        String buckets = Zenit.DRY.stringify(
            new RecordSourceBucketQuery(null, "created_at", 7, null));

        // Every installation-wide source is admin-gated. Unauthorized and unknown
        // refuse IDENTICALLY with 404 (RecordSourceGate: no existence oracle).
        // hohenheim.certificate, hohenheim.dns_record and hohenheim.database deliberately
        // LEFT OUT: they carry a capability vocabulary now, so they are grant-scoped like
        // the site source rather than admin-gated. They are asserted below, as EMPTY
        // rather than as 404. hohenheim.access_list moved out with the protected-paths
        // work: it is scoped like git providers (shared rows plus manage holders) and is
        // asserted below as hiding every private row.
        List<String> adminOnly = List.of(
            "hohenheim.site_auth_provider", "hohenheim.system_user",
            "hohenheim.spamservice_system_users", "hohenheim.dns_zone",
            "hohenheim.ban", "zenit.activity");
        for (String token : adminOnly) {
            assertThat(dryPost("/zn/records/" + token + "/query", query, outsiderSession, csrf).statusCode())
                .as("query on %s", token).isEqualTo(404);
            assertThat(get("/zn/records/" + token + "/item/1", outsiderSession).statusCode())
                .as("item on %s", token).isEqualTo(404);
            assertThat(get("/zn/records/" + token + "/vocabulary", outsiderSession).statusCode())
                .as("vocabulary on %s", token).isEqualTo(404);
            assertThat(dryPost("/zn/records/" + token + "/buckets", buckets, outsiderSession, csrf).statusCode())
                .as("buckets on %s", token).isEqualTo(404);
        }

        // The site source is grant-scoped instead of 403: an ungranted login
        // gets an EMPTY result set, and ids read as missing.
        HttpResponse<String> sites = dryPost("/zn/records/hohenheim.site/query", query, outsiderSession, csrf);
        assertThat(sites.statusCode()).isEqualTo(200);
        assertThat(sites.body()).doesNotContain("Manage Site");
        assertThat(get("/zn/records/hohenheim.site/item/" + siteAId, outsiderSession).statusCode())
            .isEqualTo(404);
        // Search-only source: the vocabulary carries no variables and buckets
        // have no whitelisted date field.
        HttpResponse<String> siteVocabulary = get("/zn/records/hohenheim.site/vocabulary", outsiderSession);
        assertThat(siteVocabulary.statusCode()).isEqualTo(200);
        assertThat(siteVocabulary.body()).doesNotContain("created_at");
        assertThat(dryPost("/zn/records/hohenheim.site/buckets", buckets, outsiderSession, csrf).statusCode())
            .isEqualTo(400);

        // The capability-scoped sources answer the same way: reachable, and empty. A
        // login with no grant, no requested certificate and no allocated database holds
        // nothing, so "open to a grant holder" must not mean "open".
        for (String token : List.of("hohenheim.certificate", "hohenheim.dns_record",
                "hohenheim.database")) {
            HttpResponse<String> scoped =
                dryPost("/zn/records/" + token + "/query", query, outsiderSession, csrf);
            assertThat(scoped.statusCode()).as("query on %s", token).isEqualTo(200);
            assertThat(scoped.body())
                .as("%s returns no rows to a principal with no grants", token)
                .contains("\"total\":l0");
            assertThat(get("/zn/records/" + token + "/item/1", outsiderSession).statusCode())
                .as("item on %s", token).isEqualTo(404);
        }

        // The access-list source is scoped like git providers: SHARED rows are offered
        // to every authenticated principal by declaration, so the source answers -- but
        // a private list stays invisible and its id reads as missing.
        Row hiddenList = Models.get(AccessListModel.class)
            .createEmptyRow();
        hiddenList.set(AccessListModel.NAME, "Outsider Hidden List");
        Models.get(AccessListModel.class).save(hiddenList);
        HttpResponse<String> lists = dryPost("/zn/records/hohenheim.access_list/query",
            query, outsiderSession, csrf);
        assertThat(lists.statusCode()).as("query on hohenheim.access_list").isEqualTo(200);
        assertThat(lists.body())
            .as("a private list is invisible to a principal with no grants")
            .doesNotContain("Outsider Hidden List");
        assertThat(get("/zn/records/hohenheim.access_list/item/"
                + hiddenList.get(AccessListModel.ID),
                outsiderSession).statusCode())
            .as("item on hohenheim.access_list").isEqualTo(404);
    }

    /**
     * The grant-enumeration query budget: one /manage render asks for the
     * managed-site set several times (panel eligibility, list scope, every nav
     * probe) and, since the DNS and certificate peers joined the panel, for
     * their own (model, capability) sets too. The conduit memo must collapse
     * that to ONE grant-store enumeration PER DISTINCT SET -- pinned as a
     * per-request cap on record-grant finds so a regression (a new caller, a
     * lost memo) becomes a visible number, not a silent slowdown.
     */
    @Test
    @Order(6)
    void managedSiteIdsStaysWithinItsPerRequestQueryBudget() throws Exception {
        Row tenant = AuthModels.users().createEmptyRow();
        tenant.set(UserModel.EMAIL, "budget@hohenheim.local");
        tenant.set(UserModel.DISPLAY_NAME, "Budget Tenant");
        tenant.set(UserModel.ENABLED, true);
        tenant.set(UserModel.CREATED_AT, Instant.now());
        tenant.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(tenant);
        Integer tenantId = tenant.get(UserModel.ID);

        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, tenantId.longValue());
        session.set(CsrfTokens.TOKEN, ZenitAuth.randomToken());
        Zenit.getSessionStore().save(session);

        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, siteAId,
            HohenheimAccess.MANAGE, true);
        try {
            java.util.concurrent.atomic.AtomicInteger finds = new java.util.concurrent.atomic.AtomicInteger();
            be.elevenways.zenit.auth.model.RecordGrantModel.SCHEMA
                .addBeforeFindHook(ignored -> finds.incrementAndGet());

            finds.set(0);
            assertThat(get("/manage/sites", session.token().secret()).statusCode()).isEqualTo(200);
            int perRequest = finds.get();

            // Memoized: each distinct set's enumeration (1 candidate fetch + 1
            // walk confirmation) runs ONCE per request. Without the memo every
            // caller pays it again and this cap breaks loudly.
            //
            // AIDEV-NOTE: the cap moved 6 -> 12 when /manage grew the instance
            // projection (2026-08-04). The panel now asks about six distinct
            // capability sets per render, not three -- site#manage, dns_record#view,
            // certificate#view plus instance#manage (panel eligibility AND the
            // instance list's nav probe, one memo entry between them),
            // instance#snapshots and instance#backups for the artifact peers' nav
            // probes. Each is still enumerated ONCE; the number this test exists to
            // catch is the un-memoized one, which is per CALLER (a dozen-plus
            // enumerations for the same set) and stays far outside this range.
            // AIDEV-NOTE: the cap moved 12 -> 14 in phase-0 brief 7. Previews are keyed to
            // the APPLICATION now, so the preview peer's nav probe asks about
            // instance#manage where it used to ask about site#manage -- the same number of
            // distinct sets, but on a render that had not yet enumerated the instance one
            // it pays that set's first fetch. It is still ONE enumeration per distinct set
            // per request; the un-memoized shape this pins against is per CALLER and an
            // order of magnitude outside this range.
            // AIDEV-NOTE: the cap moved 14 -> 15 with the protected-paths work: access
            // lists carry a manage grant surface now, so a render enumerates a SEVENTH
            // distinct set (access_list#manage) for the /manage access-list peer. Still
            // ONE enumeration per distinct set per request; the un-memoized shape this
            // pins against is per CALLER and an order of magnitude outside this range.
            assertThat(perRequest)
                .as("record-grant finds during one /manage/sites request "
                    + "(7 distinct capability sets + walk confirmations)")
                .isBetween(1, 15);
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, siteAId,
                HohenheimAccess.MANAGE);
        }
    }

    private HttpResponse<String> dryPost(String path, String body, String session, String csrf) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/dry")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session)
            .header("X-Csrf-Token", csrf)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Route-conflict takeover: a disabled site is exempt from the cross-site route
     * check, so every path that flips one live must re-judge it. Toggle is the only
     * row action a delegated tenant has, and the delegated form has an enabled
     * checkbox.
     */
    @Test
    @Order(4)
    void enablingAStagedConflictingSiteIsRefusedOnEveryDelegatedPath() throws Exception {
        var siteModel = Models.get(SiteModel.class);
        var domainModel = Models.get(SiteDomainModel.class);

        // A victim: live, and holding the contested hostname.
        Row victim = siteModel.createEmptyRow();
        victim.set(SiteModel.NAME, "Takeover Victim");
        victim.set(SiteModel.SLUG, "takeover-victim");
        victim.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        victim.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        victim.set(SiteModel.STATUS, "active");
        victim.set(SiteModel.ENABLED, true);
        siteModel.save(victim);
        Row victimDomain = domainModel.createEmptyRow();
        victimDomain.set(SiteDomainModel.SITE_ID, victim.get(SiteModel.ID));
        victimDomain.set(SiteDomainModel.HOSTNAME, "takeover.example.com");
        victimDomain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domainModel.save(victimDomain);

        // The tenant's staged site: disabled, so it was allowed to claim the same
        // hostname, and granted to the operator.
        Row staged = siteModel.createEmptyRow();
        staged.set(SiteModel.NAME, "Staged Takeover");
        staged.set(SiteModel.SLUG, "staged-takeover");
        staged.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        staged.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        staged.set(SiteModel.STATUS, "active");
        staged.set(SiteModel.ENABLED, false);
        siteModel.save(staged);
        Integer stagedId = staged.get(SiteModel.ID);
        Row stagedDomain = domainModel.createEmptyRow();
        stagedDomain.set(SiteDomainModel.SITE_ID, stagedId);
        stagedDomain.set(SiteDomainModel.HOSTNAME, "takeover.example.com");
        stagedDomain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domainModel.save(stagedDomain);

        // ...and an innocent staged site on a hostname nobody else claims.
        Row innocent = siteModel.createEmptyRow();
        innocent.set(SiteModel.NAME, "Staged Innocent");
        innocent.set(SiteModel.SLUG, "staged-innocent");
        innocent.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        innocent.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        innocent.set(SiteModel.STATUS, "active");
        innocent.set(SiteModel.ENABLED, false);
        siteModel.save(innocent);
        Integer innocentId = innocent.get(SiteModel.ID);
        Row innocentDomain = domainModel.createEmptyRow();
        innocentDomain.set(SiteDomainModel.SITE_ID, innocentId);
        innocentDomain.set(SiteDomainModel.HOSTNAME, "no-conflict.example.com");
        innocentDomain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domainModel.save(innocentDomain);

        RecordGrants.grant(GrantSubjectType.USER, operatorId, SiteModel.MODEL_ID, stagedId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, operatorId, SiteModel.MODEL_ID, innocentId,
            HohenheimAccess.MANAGE, true);

        try {
            // 1. The toggle action refuses to seize the victim's hostname.
            assertThat(operatorPost("/manage/sites/" + stagedId + "/action/toggle_site", "")
                .statusCode()).isIn(302, 303);
            assertThat(siteModel.findById(stagedId).get(SiteModel.ENABLED))
                .as("toggle must not enable a route-conflicting site").isEqualTo(false);

            // 2. Neither does the delegated form's enabled checkbox.
            assertThat(operatorPost("/manage/sites/" + stagedId,
                "name=Staged+Takeover&enabled=true&description=").statusCode())
                .isIn(200, 302, 303, 422);
            assertThat(siteModel.findById(stagedId).get(SiteModel.ENABLED))
                .as("the delegated form must not enable a route-conflicting site")
                .isEqualTo(false);

            // 3. A site with no conflict still toggles live.
            assertThat(operatorPost("/manage/sites/" + innocentId + "/action/toggle_site", "")
                .statusCode()).isIn(302, 303);
            assertThat(siteModel.findById(innocentId).get(SiteModel.ENABLED))
                .as("a non-conflicting site still enables").isEqualTo(true);

            // 4. Disabling is never blocked -- not even for the site that now owns a
            //    hostname somebody else also staged.
            assertThat(operatorPost("/manage/sites/" + innocentId + "/action/toggle_site", "")
                .statusCode()).isIn(302, 303);
            assertThat(siteModel.findById(innocentId).get(SiteModel.ENABLED))
                .as("disabling is never refused").isEqualTo(false);

            // 5. The admin form path refuses the same takeover (the invariant is shared,
            //    not per-panel).
            String adminEnableBody = "name=Staged+Takeover&upstream_kind=hohenheim%3Astatic"
                + "&enabled=true&settings.root_path=%2Ftmp&description=";
            assertThat(adminPost("/admin/sites/" + stagedId, adminEnableBody).statusCode())
                .isIn(200, 302, 303, 422);
            assertThat(siteModel.findById(stagedId).get(SiteModel.ENABLED))
                .as("the admin form must not enable a route-conflicting site either")
                .isEqualTo(false);

            // 6. Once the victim stands down, that very same submit goes through -- so
            //    step 5 was refused by the invariant, not by a malformed body.
            victim.set(SiteModel.ENABLED, false);
            siteModel.save(victim);

            // 5b. Standing the victim down RELEASES its hostname, and the release
            //     quarantine (ReleasedClaims) keeps it reserved for its former owner: the
            //     victim was operator-owned, the staged site carries a manage grant, so
            //     this is still a cross-owner takeover and is still refused -- on the real
            //     admin HTTP path. Lift it the way an administrator does, so step 6 keeps
            //     proving what it claims.
            assertThat(adminPost("/admin/sites/" + stagedId, adminEnableBody).statusCode())
                .isIn(200, 422);
            assertThat(siteModel.findById(stagedId).get(SiteModel.ENABLED))
                .as("a just-released hostname stays quarantined against a different owner")
                .isEqualTo(false);
            Models.get(ReleasedRouteClaimModel.class).find().delete();

            assertThat(adminPost("/admin/sites/" + stagedId, adminEnableBody).statusCode())
                .isIn(302, 303);
            assertThat(siteModel.findById(stagedId).get(SiteModel.ENABLED))
                .as("with the conflict gone the same submit enables the site")
                .isEqualTo(true);
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, operatorId, SiteModel.MODEL_ID, stagedId,
                HohenheimAccess.MANAGE);
            RecordGrants.revoke(GrantSubjectType.USER, operatorId, SiteModel.MODEL_ID, innocentId,
                HohenheimAccess.MANAGE);
            siteModel.delete(staged);
            siteModel.delete(innocent);
            siteModel.delete(victim);
            domainModel.delete(stagedDomain);
            domainModel.delete(innocentDomain);
            domainModel.delete(victimDomain);
            // Tearing live sites down IS a release, so the fixture ledgers quarantine rows
            // that would otherwise refuse another test class's claim on these hostnames.
            Models.get(ReleasedRouteClaimModel.class).find().delete();
        }
    }
}
