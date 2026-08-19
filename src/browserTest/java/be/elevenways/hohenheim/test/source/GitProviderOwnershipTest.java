package be.elevenways.hohenheim.test.source;

import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.source.GitProviderKinds;
import be.elevenways.hohenheim.server.source.GitProviders;
import be.elevenways.hohenheim.server.source.GiteaProviderKind;
import be.elevenways.hohenheim.server.source.GithubProviderKind;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.data.RecordSourceQuery;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.validation.Violations;
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
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The git provider tier end to end: the kind registry is the ONE vocabulary, the per-kind
 * invariants refuse at SAVE, and ownership is a manage grant -- so a tenant reaches shared
 * providers plus its own, registers providers of its own from /manage, and never sees
 * another tenant's forge installation.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GitProviderOwnershipTest extends HohenheimTestBase {

    private static Integer sharedProviderId;
    private static Integer tenantProviderId;
    private static Integer strangerProviderId;

    private static Integer tenantUserId;
    private static String tenantSession;
    private static String tenantCsrf;

    private static Integer strangerUserId;
    private static String strangerSession;
    private static String strangerCsrf;

    @BeforeAll
    static void seedProvidersAndTenants() {
        Model providers = Models.get(GitProviderModel.class);

        sharedProviderId = save(providers, "Operator GitHub", GithubProviderKind.ID, null, true);
        tenantProviderId = save(providers, "Tenant Forge", GiteaProviderKind.ID,
            "https://forge.tenant.example", false);
        strangerProviderId = save(providers, "Stranger Forge", GiteaProviderKind.ID,
            "https://forge.stranger.example", false);

        tenantUserId = user("provider-tenant@hohenheim.local", "Provider Tenant");
        strangerUserId = user("provider-stranger@hohenheim.local", "Provider Stranger");

        RecordGrants.grant(GrantSubjectType.USER, tenantUserId,
            GitProviderModel.MODEL_ID, tenantProviderId, HohenheimAccess.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.USER, strangerUserId,
            GitProviderModel.MODEL_ID, strangerProviderId, HohenheimAccess.MANAGE, true);

        String[] tenantAuth = session(tenantUserId);
        tenantSession = tenantAuth[0];
        tenantCsrf = tenantAuth[1];
        String[] strangerAuth = session(strangerUserId);
        strangerSession = strangerAuth[0];
        strangerCsrf = strangerAuth[1];
    }

    private static Integer save(Model providers, String name, Identifier kind,
                                String baseUrl, boolean shared) {
        Row row = providers.createEmptyRow();
        row.set(GitProviderModel.NAME, name);
        row.set(GitProviderModel.KIND, kind.toString());
        row.set(GitProviderModel.BASE_URL, baseUrl);
        row.set(GitProviderModel.SHARED, shared);
        row.set(GitProviderModel.ACCESS_TOKEN, "token-" + name);
        providers.save(row);
        return row.get(GitProviderModel.ID);
    }

    private static Integer user(String email, String displayName) {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, email);
        user.set(UserModel.DISPLAY_NAME, displayName);
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        return user.get(UserModel.ID);
    }

    /** @return the session secret and its csrf token */
    private static String[] session(Integer userId) {
        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, userId.longValue());
        String csrf = ZenitAuth.randomToken();
        session.set(CsrfTokens.TOKEN, csrf);
        Zenit.getSessionStore().save(session);
        return new String[] {session.token().secret(), csrf};
    }

    private HttpResponse<String> get(String path, String session) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session)
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String session, String csrf)
            throws Exception {
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

    /** The picker's own source, asked as one principal. */
    private HttpResponse<String> pickerAs(String session, String csrf) throws Exception {
        String body = Zenit.DRY.stringify(RecordSourceQuery.matchAll());
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/zn/records/hohenheim.git_provider/query"))
            .header("Content-Type", "application/dry")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session)
            .header("X-Csrf-Token", csrf)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Step 1: the registry IS the vocabulary. Every kind the form offers has a handler,
     * every handler is in the registry, and the stored token is the identifier string.
     */
    @Test
    @Order(1)
    void theKindRegistryIsTheOnlyVocabulary() {
        assertThat(GitProviderModel.KIND.getValues().keySet())
            .as("the form's kind options enumerate the registry, never a hand-written list")
            .containsExactlyInAnyOrder("hohenheim:github", "hohenheim:gitlab", "hohenheim:gitea");

        for (String token : GitProviderModel.KIND.getValues().keySet()) {
            assertThat(GitProviderKinds.getHandler(token))
                .as("kind '" + token + "' is offered, so it must have a server handler")
                .isNotNull();
        }
        assertThat(GitProviderKinds.declaredKinds())
            .as("no handler exists that the form cannot offer")
            .hasSize(GitProviderModel.KIND.getValues().size());
    }

    /**
     * Step 2: the per-kind invariants refuse at SAVE, not at the first deploy -- and an
     * undeclared kind fails CLOSED on both the write path and the client funnel.
     */
    @Test
    @Order(2)
    void perKindInvariantsRefuseAtSave() {
        Model providers = Models.get(GitProviderModel.class);

        Row gitea = providers.createEmptyRow();
        gitea.set(GitProviderModel.NAME, "Hostless Gitea");
        gitea.set(GitProviderModel.KIND, GiteaProviderKind.ID.toString());
        Throwable hostless = catchThrowable(() -> providers.save(gitea));
        assertThat(hostless)
            .as("a Gitea provider without its own host is refused at SAVE")
            .isInstanceOf(Violations.class);

        Row bitbucket = providers.createEmptyRow();
        bitbucket.set(GitProviderModel.NAME, "Bitbucket");
        bitbucket.set(GitProviderModel.KIND, "hohenheim:bitbucket");
        Throwable undeclared = catchThrowable(() -> providers.save(bitbucket));
        assertThat(undeclared)
            .as("an undeclared kind is refused at SAVE; unknown fails closed")
            .isInstanceOf(Violations.class);

        Row rogue = providers.createEmptyRow();
        rogue.set(GitProviderModel.ID, 9401);
        rogue.set(GitProviderModel.KIND, "hohenheim:bitbucket");
        assertThat(catchThrowable(() -> GitProviders.clientFor(rogue)))
            .as("the client funnel refuses the same kind by name, for rows no form wrote")
            .isInstanceOf(Violations.class);

        // The stored rows are untouched: a refusal leaves nothing behind.
        assertThat(providers.find().where(GitProviderModel.NAME.eq("Hostless Gitea")).first())
            .as("a refused save persists nothing")
            .isNull();
    }

    /**
     * Step 3: the picker scope. A tenant is offered the shared provider plus the one it
     * manages, and never another tenant's -- one scope declaration, so the record source
     * behind the picker and the /manage list cannot disagree.
     */
    @Test
    @Order(3)
    void thePickerOffersSharedProvidersAndOwnedOnesOnly() throws Exception {
        HttpResponse<String> tenant = pickerAs(tenantSession, tenantCsrf);
        assertThat(tenant.statusCode()).isEqualTo(200);
        assertThat(tenant.body())
            .as("the shared operator provider is offered to every tenant")
            .contains("Operator GitHub");
        assertThat(tenant.body())
            .as("the provider this tenant manages is offered")
            .contains("Tenant Forge");
        assertThat(tenant.body())
            .as("another tenant's unshared provider is never named")
            .doesNotContain("Stranger Forge");

        HttpResponse<String> stranger = pickerAs(strangerSession, strangerCsrf);
        assertThat(stranger.statusCode()).isEqualTo(200);
        assertThat(stranger.body()).contains("Operator GitHub", "Stranger Forge");
        assertThat(stranger.body())
            .as("the scope is symmetric: neither tenant sees the other's")
            .doesNotContain("Tenant Forge");

        HttpResponse<String> admin = adminGet("/admin/git-providers");
        assertThat(admin.statusCode()).isEqualTo(200);
        assertThat(admin.body())
            .as("the operator keeps seeing every installation")
            .contains("Operator GitHub", "Tenant Forge", "Stranger Forge");
    }

    /**
     * Step 4: the /manage lane. The tenant lists only what it manages, another tenant's id
     * reads as MISSING, and a create adopts the row by planting the creator's manage grant.
     */
    @Test
    @Order(4)
    void theManageLaneListsOwnedProvidersAndAdoptsWhatItCreates() throws Exception {
        HttpResponse<String> list = get("/manage/git-providers", tenantSession);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("Tenant Forge");
        assertThat(list.body())
            .as("a shared provider is USABLE, never editable, so it is not in this list")
            .doesNotContain("Operator GitHub");
        assertThat(list.body()).doesNotContain("Stranger Forge");

        assertThat(get("/manage/git-providers/" + strangerProviderId, tenantSession).statusCode())
            .as("another tenant's provider reads as missing, not forbidden")
            .isEqualTo(404);
        assertThat(get("/manage/git-providers/" + sharedProviderId, tenantSession).statusCode())
            .as("a shared provider is not editable from the delegated surface either")
            .isEqualTo(404);

        HttpResponse<String> created = post("/manage/git-providers/new",
            "name=Tenant+Second+Forge&kind=hohenheim%3Agitea"
                + "&base_url=https%3A%2F%2Fsecond.tenant.example"
                + "&access_token=second-token&shared=true",
            tenantSession, tenantCsrf);
        assertThat(created.statusCode()).isIn(302, 303);

        Row row = Models.get(GitProviderModel.class).find()
            .where(GitProviderModel.NAME.eq("Tenant Second Forge")).first();
        assertThat(row).as("the delegated create persisted").isNotNull();
        assertThat(row.get(GitProviderModel.SHARED))
            .as("shared is not on this form, so a submitted value is dropped by coercion")
            .isNotEqualTo(true);
        assertThat(HohenheimAccess.manageSubjectsOf(GitProviderModel.MODEL_ID,
                row.get(GitProviderModel.ID)))
            .as("creating adopts the row: the creator holds manage on what it registered")
            .containsExactly("user:" + tenantUserId);

        assertThat(get("/manage/git-providers/" + row.get(GitProviderModel.ID), strangerSession)
                .statusCode())
            .as("the freshly created row is invisible to another tenant")
            .isEqualTo(404);
    }

    /** Step 5: the per-kind sub-form -- App identifiers on GitHub, and on nothing else. */
    @Test
    @Order(5)
    void theKindSubFormCarriesOnlyItsOwnKindsSettings() throws Exception {
        Model providers = Models.get(GitProviderModel.class);
        Row row = providers.findById(sharedProviderId);
        row.set(GitProviderModel.SETTINGS, Map.of(
            GithubProviderKind.APP_ID.getName(), "42",
            GithubProviderKind.APP_INSTALLATION_ID.getName(), "55"));
        providers.save(row);

        HttpResponse<String> form = adminGet("/admin/git-providers/" + sharedProviderId);
        assertThat(form.statusCode()).isEqualTo(200);
        assertThat(form.body())
            .as("the GitHub kind's sub-form renders its App identifiers")
            .contains("settings.app_id");
        assertThat(form.body())
            .as("the token column stays a column, never a JSON sub-field (it is encrypted)")
            .contains("name=\"access_token\"");

        HttpResponse<String> gitea = adminGet("/admin/git-providers/" + tenantProviderId);
        assertThat(gitea.statusCode()).isEqualTo(200);
        assertThat(gitea.body())
            .as("a Gitea provider renders no GitHub App inputs at all")
            .doesNotContain("name=\"settings.app_id\"");
    }
}
