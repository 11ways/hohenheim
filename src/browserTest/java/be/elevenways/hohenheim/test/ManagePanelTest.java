package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.GrantModel;
import be.elevenways.zenit.auth.model.PermissionGroupModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
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
        siteA.set(SiteModel.SITE_TYPE, "hohenheim:static");
        siteA.set(SiteModel.SETTINGS, Map.of(
            "root_path", "/srv/manage-a",
            "system_user_id", "hohenheim:site-a",
            "environment_variables", Map.of("DAEMON_SECRET", "must-not-render"),
            "command", "unsafe-host-command"));
        siteA.set(SiteModel.SOURCE, SiteModel.SOURCE_GIT);
        siteA.set(SiteModel.SOURCE_SETTINGS, Map.of(
            "repository_url", "ssh://private/manage-a.git",
            "build_command", "private-build-command",
            "webhook_secret", "private-webhook-secret"));
        siteA.set(SiteModel.STATUS, "active");
        siteA.set(SiteModel.ENABLED, true);
        siteModel.save(siteA);
        siteAId = siteA.get(SiteModel.ID);

        Row siteB = siteModel.createEmptyRow();
        siteB.set(SiteModel.NAME, "Manage Site B");
        siteB.set(SiteModel.SLUG, "manage-site-b");
        siteB.set(SiteModel.SITE_TYPE, "hohenheim:static");
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
        operatorSession = session.id();
    }

    private String baseUrl() {
        return "http://localhost:" + getServerPort();
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

    private HttpResponse<String> adminGet(String path) throws Exception {
        return get(path, sessionToken);
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

    @Test
    @Order(1)
    void ungrantedOperatorCannotEnterManage() throws Exception {
        assertThat(operatorGet("/manage").statusCode()).isEqualTo(403);
        assertThat(operatorGet("/manage/sites").statusCode()).isEqualTo(403);
    }

    @Test
    @Order(2)
    void grantingThroughTheAccessTabUnlocksManage() throws Exception {
        HttpResponse<String> add = adminPost(
            "/admin/sites/" + siteAId + "/access/add", "user_id=" + operatorId);
        assertThat(add.statusCode()).isIn(302, 303);

        // The Access tab lists the new grant row.
        HttpResponse<String> pageView = adminGet("/admin/sites/" + siteAId + "/page/access");
        assertThat(pageView.statusCode()).isEqualTo(200);
        assertThat(pageView.body()).contains("operator@hohenheim.local");
        assertThat(pageView.body()).contains("data-subject-id");

        // Panel eligibility is derived from the effective record grant; no
        // second global grant is created or synchronized.
        UserPrincipal principal = new UserPrincipal(operatorId, "Site Operator");
        assertThat(HohenheimAccess.managedSiteIds(principal)).containsExactly(siteAId);
        assertThat(GrantService.listDirectGrants("user", operatorId))
            .noneMatch(grant -> "hohenheim.manage.access".equals(grant.get(GrantModel.PERMISSION)));
    }

    @Test
    @Order(3)
    void manageListsOnlyGrantedSitesAndAdminStaysClosed() throws Exception {
        assertThat(operatorGet("/manage").statusCode()).isIn(200, 302, 303);

        HttpResponse<String> list = operatorGet("/manage/sites");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("Manage Site A");
        assertThat(list.body()).doesNotContain("Manage Site B");
        assertThat(list.body()).doesNotContain("data-column=\"site_type\"");

        // Site B's manage detail URL reads as missing, not forbidden.
        assertThat(operatorGet("/manage/sites/" + siteBId).statusCode()).isEqualTo(404);

        // The admin panel stays closed to the operator.
        assertThat(operatorGet("/admin").statusCode()).isEqualTo(403);
        assertThat(operatorGet("/admin/sites").statusCode()).isEqualTo(403);
    }

    @Test
    @Order(4)
    void operatorRowActionsOfferToggleButNeverClone() throws Exception {
        HttpResponse<String> list = operatorGet("/manage/sites");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("toggle_site");
        assertThat(list.body()).doesNotContain("clone_site");
    }

    @Test
    @Order(5)
    void editingAGrantedSiteCannotOverwriteExecutionControls() throws Exception {
        HttpResponse<String> form = operatorGet("/manage/sites/" + siteAId);
        assertThat(form.statusCode()).isEqualTo(200);
        assertThat(form.body())
            .doesNotContain("name=\"site_type\"")
            .doesNotContain("name=\"source\"")
            .doesNotContain("name=\"settings.root_path\"")
            .doesNotContain("name=\"source_settings.build_command\"")
            .doesNotContain("must-not-render")
            .doesNotContain("private-webhook-secret")
            .doesNotContain("name=\"settings.system_user_id\"");

        HttpResponse<String> response = operatorPost("/manage/sites/" + siteAId,
            "name=Manage+Site+A+Edited&enabled=false&description=Delegated+description"
                + "&site_type=hohenheim%3Acommand&source=local"
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
        assertThat(site.get(SiteModel.SITE_TYPE)).isEqualTo("hohenheim:static");
        assertThat(site.get(SiteModel.SETTINGS)).isEqualTo(Map.of(
            "root_path", "/srv/manage-a",
            "system_user_id", "hohenheim:site-a",
            "environment_variables", Map.of("DAEMON_SECRET", "must-not-render"),
            "command", "unsafe-host-command"));
        assertThat(site.get(SiteModel.SOURCE)).isEqualTo(SiteModel.SOURCE_GIT);
        assertThat(site.get(SiteModel.SOURCE_SETTINGS)).isEqualTo(Map.of(
            "repository_url", "ssh://private/manage-a.git",
            "build_command", "private-build-command",
            "webhook_secret", "private-webhook-secret"));
    }

    @Test
    @Order(6)
    void deploymentWebhookSecretIsAdminOnly() throws Exception {
        HttpResponse<String> delegated = operatorGet(
            "/manage/sites/" + siteAId + "/page/deployments");
        assertThat(delegated.statusCode()).isEqualTo(200);
        assertThat(delegated.body())
            .doesNotContain("private-webhook-secret")
            .doesNotContain("data-webhook-secret")
            .doesNotContain("data-webhook-url");

        // The manage-rendered page embeds ITS OWN URL as the _return target,
        // so deploy actions bounce back to /manage, not /admin.
        assertThat(delegated.body())
            .contains("name=\"_return\"")
            .contains("value=\"/manage/sites/" + siteAId + "/page/deployments\"");

        HttpResponse<String> admin = adminGet(
            "/admin/sites/" + siteAId + "/page/deployments");
        assertThat(admin.statusCode()).isEqualTo(200);
        assertThat(admin.body()).contains("private-webhook-secret").contains("data-webhook-secret");
    }

    @Test
    @Order(7)
    void manageScopedSitePickerOffersOnlyGrantedSites() throws Exception {
        String body = Zenit.DRY.stringify(RecordSourceQuery.matchAll());
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/zn/records/hohenheim.manage_site/query"))
            .header("Content-Type", "application/dry")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + operatorSession)
            .header("X-Csrf-Token", operatorCsrf)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Manage Site A");
        assertThat(response.body()).doesNotContain("Manage Site B");
    }

    @Test
    @Order(7)
    void operatorActionSubmittedFromManageReturnsToManage() throws Exception {
        // No proxy runs in this suite, so deploy is redirect-only: the handler
        // finds no git handler and just answers with the return redirect.
        String manageTarget = "/manage/sites/" + siteAId + "/page/deployments";
        HttpResponse<String> fromManage = operatorPost("/sites/" + siteAId + "/deploy",
            "_return=" + java.net.URLEncoder.encode(manageTarget, java.nio.charset.StandardCharsets.UTF_8));
        assertThat(fromManage.statusCode()).isIn(302, 303);
        assertThat(fromManage.headers().firstValue("Location")).hasValue(manageTarget);

        // A forged _return can never open-redirect: unsafe values fall back
        // to the admin page.
        HttpResponse<String> forged = operatorPost("/sites/" + siteAId + "/deploy",
            "_return=" + java.net.URLEncoder.encode("https://evil.example/", java.nio.charset.StandardCharsets.UTF_8));
        assertThat(forged.statusCode()).isIn(302, 303);
        assertThat(forged.headers().firstValue("Location"))
            .hasValue("/admin/sites/" + siteAId + "/page/deployments");
    }

    @Test
    @Order(8)
    void delegatedDomainsAreReadOnly() throws Exception {
        HttpResponse<String> subpage = operatorGet("/manage/sites/" + siteAId + "/page/domains");
        assertThat(subpage.statusCode()).isEqualTo(200);
        assertThat(subpage.body()).contains("managed.example.com").doesNotContain("add-domain-link");

        // The delegated record form must render INERT: updatable()==false makes
        // the renderer null the submitUrl (so no form posts back to the domain
        // record and the save pl-button is dropped) and skip the
        // optimistic-concurrency token (cms__snapshot) that only writable forms
        // carry. A bare type="submit" check would trip over the shell's logout
        // button.
        HttpResponse<String> domainForm = operatorGet("/manage/domains/" + domainAId);
        assertThat(domainForm.statusCode()).isEqualTo(200);
        assertThat(domainForm.body())
            .doesNotContain("action=\"/manage/domains/")
            .doesNotContain("<pl-button type=\"submit\"")
            .doesNotContain("cms__snapshot");
        assertThat(operatorPost("/manage/domains/new",
            "site_id=" + siteAId + "&hostname=hijack.example.com&match_type=exact").statusCode())
            .isEqualTo(404);
        assertThat(operatorPost("/manage/domains/" + domainAId,
            "site_id=" + siteAId + "&hostname=hijacked.example.com&match_type=regex").statusCode())
            .isEqualTo(404);
        assertThat(operatorPost("/manage/domains/" + domainAId + "/delete", "").statusCode())
            .isEqualTo(404);

        Row domain = Models.get(SiteDomainModel.class).findById(domainAId);
        assertThat(domain.get(SiteDomainModel.HOSTNAME)).isEqualTo("managed.example.com");
        assertThat(Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq("hijack.example.com")).first()).isNull();
    }

    @Test
    @Order(9)
    void revokingRecordAccessDoesNotDeleteIndependentGlobalPermission() throws Exception {
        GrantService.createDirectGrant("user", operatorId, "hohenheim.manage.access", true);
        HttpResponse<String> remove = adminPost("/admin/sites/" + siteAId + "/access/remove",
            "subject_type=user&subject_id=" + operatorId + "&capability=" + HohenheimAccess.MANAGE);
        assertThat(remove.statusCode()).isIn(302, 303);

        // The grants table is empty again (the empty state renders).
        HttpResponse<String> pageView = adminGet("/admin/sites/" + siteAId + "/page/access");
        assertThat(pageView.statusCode()).isEqualTo(200);
        assertThat(pageView.body()).doesNotContain("data-subject-id");

        // The independently administered panel grant remains untouched.
        assertThat(GrantService.listDirectGrants("user", operatorId))
            .anyMatch(grant -> "hohenheim.manage.access".equals(grant.get(GrantModel.PERMISSION)));
        assertThat(operatorGet("/manage").statusCode()).isIn(200, 302, 303);

        for (Row grant : GrantService.listDirectGrants("user", operatorId)) {
            if ("hohenheim.manage.access".equals(grant.get(GrantModel.PERMISSION))) {
                GrantService.deleteDirectGrant("user", operatorId, grant.get(GrantModel.ID));
            }
        }
        assertThat(operatorGet("/manage").statusCode()).isEqualTo(403);
        assertThat(operatorGet("/manage/sites").statusCode()).isEqualTo(403);
    }

    @Test
    @Order(10)
    void groupAndNegativeRecordGrantsDrivePanelEligibility() throws Exception {
        Row group = AuthModels.permissionGroups().createEmptyRow();
        group.set(PermissionGroupModel.SLUG, "manage-operators");
        group.set(PermissionGroupModel.TITLE, "Manage Operators");
        AuthModels.permissionGroups().save(group);
        Integer groupId = group.get(PermissionGroupModel.ID);

        GrantService.createDirectGrant("user", operatorId, "group.manage-operators", true);
        RecordGrants.grant("group", groupId, SiteModel.MODEL_ID, siteBId,
            HohenheimAccess.MANAGE, true);

        assertThat(operatorGet("/manage").statusCode()).isIn(200, 302, 303);
        assertThat(operatorGet("/manage/sites").body()).contains("Manage Site B");

        RecordGrants.grant("user", operatorId, SiteModel.MODEL_ID, siteBId,
            HohenheimAccess.MANAGE, false);
        assertThat(operatorGet("/manage").statusCode()).isEqualTo(403);
        assertThat(HohenheimAccess.managedSiteIds(new UserPrincipal(operatorId, "Site Operator")))
            .isEmpty();

        RecordGrants.revoke("user", operatorId, SiteModel.MODEL_ID, siteBId, HohenheimAccess.MANAGE);
        assertThat(operatorGet("/manage").statusCode()).isIn(200, 302, 303);
        RecordGrants.revoke("group", groupId, SiteModel.MODEL_ID, siteBId, HohenheimAccess.MANAGE);
        assertThat(operatorGet("/manage").statusCode()).isEqualTo(403);
    }

    @Test
    @Order(11)
    void explicitGlobalManageDenyBeatsRecordGrantWhileAbsenceFallsBack() throws Exception {
        RecordGrants.grant("user", operatorId, SiteModel.MODEL_ID, siteAId,
            HohenheimAccess.MANAGE, true);
        GrantService.createDirectGrant("user", operatorId, "hohenheim.manage.access", false);

        assertThat(operatorGet("/manage").statusCode()).isEqualTo(403);
        assertThat(operatorPost("/sites/" + siteAId + "/deploy", "").statusCode()).isEqualTo(403);
        UserPrincipal principal = new UserPrincipal(operatorId, "Site Operator");
        assertThat(HohenheimAccess.managedSiteIds(principal)).isEmpty();
        assertThat(HohenheimAccess.canManageSite(principal, siteAId)).isFalse();

        for (Row grant : GrantService.listDirectGrants("user", operatorId)) {
            if ("hohenheim.manage.access".equals(grant.get(GrantModel.PERMISSION))) {
                GrantService.deleteDirectGrant("user", operatorId, grant.get(GrantModel.ID));
            }
        }
        assertThat(operatorGet("/manage").statusCode()).isIn(200, 302, 303);
        RecordGrants.revoke("user", operatorId, SiteModel.MODEL_ID, siteAId, HohenheimAccess.MANAGE);
    }
}
