package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.*;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auth-provider CRUD through the zenit-cms resource routes, including the
 * type-discriminated config sub-form and editable Basic credentials.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthProviderAdminTest extends HohenheimTestBase {

    private String baseUrl() {
        return "http://localhost:" + getServerPort();
    }

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void authProvidersListRenders() {
        navigateToApp("/admin/auth-providers");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("Auth Providers");
    }

    @Test
    @Order(2)
    void createFormRendersTypeSelector() {
        navigateToApp("/admin/auth-providers/new");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("Name").contains("Provider type");
        assertThat(page.content()).contains("provider_type");
    }

    @Test
    @Order(2)
    void configSectionShowsPlaceholderUntilATypeIsChosen() {
        navigateToApp("/admin/auth-providers/new");
        waitForHydration();

        // No provider type selected yet: the config fieldset must SAY so
        // instead of rendering a mysteriously empty section.
        var placeholder = page.locator("zf-schema-sub-form pl-empty-state");
        assertThat(placeholder.count()).isEqualTo(1);
        assertThat(placeholder.innerText()).contains("choose a type");

        // Choosing a type swaps the placeholder for the type's fields, live.
        page.locator("pl-select[name='provider_type'] .pl-select-field").click();
        page.waitForSelector("he-bottom .pl-select-popup[data-open]");
        page.locator("div[role='option'][data-value='hohenheim:basic']").click();
        page.waitForSelector("zf-schema-sub-form pl-field");

        assertThat(page.locator("zf-schema-sub-form pl-empty-state").count()).isZero();
    }

    @Test
    @Order(2)
    void requiredPermissionOffersKnownPermissionCombobox() {
        // PermissionField: a free-text pl-select over the KnownPermissions
        // vocabulary (the LuckPerms editor model) with described entries.
        navigateToApp("/admin/auth-providers/new");
        waitForHydration();

        var picker = page.locator("pl-select[name='required_permission']");
        assertThat(picker.count()).isEqualTo(1);
        assertThat(picker.getAttribute("free-text")).isNotNull();

        // Boot-registered and endpoint-declared permissions both feed it
        // (options stay mounted while the popup is closed).
        assertThat(page.locator("div[role='option'][data-value='hohenheim.admin.access']").count())
            .isEqualTo(1);
        assertThat(page.locator("div[role='option'][data-value='auth.admin.access']").count())
            .isEqualTo(1);
        // Described permissions render their description line.
        assertThat(page.locator("div[role='option'][data-value='auth.admin.access'] .pl-select-subtitle")
            .innerText()).isEqualTo("Access the admin panel");

        // Free text commits as the value: type an unlisted permission, Enter.
        picker.locator(".pl-select-field").click();
        var popup = page.locator(
            "he-bottom .pl-select-popup[data-open]");
        popup.locator("input[role='searchbox']").fill("custom.special.permission");
        popup.locator("input[role='searchbox']").press("Enter");
        assertThat(picker.locator(".pl-select-value").innerText().trim())
            .isEqualTo("custom.special.permission");
    }

    @Test
    @Order(1)
    void permissionDescriptionsResolveOnAClientSideRender() {
        // Regression: over soft navigation the form renders CLIENT-side; the
        // permission descriptions are Java-registered microcopy, which only
        // resolves in the browser when the served bundle carries catalog keys
        // (they are in no template manifest). A raw "auth_admin_access" here
        // means the bundle union regressed to manifest-only.
        navigateToApp("/admin/auth-providers");
        waitForHydration();

        page.locator("a[href='/admin/auth-providers/new']").first().click();
        page.waitForCondition(() ->
            page.locator("pl-select[name='required_permission']").count() == 1);

        var subtitle = page.locator(
            "div[role='option'][data-value='auth.admin.access'] .pl-select-subtitle");
        page.waitForCondition(() -> subtitle.count() == 1
            && !subtitle.innerText().trim().equals("auth_admin_access")
            && !subtitle.innerText().trim().isEmpty());
        assertThat(subtitle.innerText()).isEqualTo("Access the admin panel");
    }

    @Test
    @Order(2)
    void proteusProviderSuggestsTheAssignedRealmsVocabulary() throws Exception {
        // A stub Proteus answers the realm-client known_permissions call; the
        // edit form must merge the realm's vocabulary into the suggestions.
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            byte[] body = ("[{\"permission\":\"site.internal\",\"description\":\"Internal staff only\"},"
                + "{\"permission\":\"group.admins\",\"description\":\"Administrators\"}]")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();

        var model = Models.get(SiteAuthProviderModel.class);
        Row row = model.createEmptyRow();
        row.set(SiteAuthProviderModel.NAME, "Realm Suggest Provider");
        row.set(SiteAuthProviderModel.PROVIDER_TYPE, "hohenheim:proteus");
        row.set(SiteAuthProviderModel.CONFIG, Map.of(
            "endpoint", "http://127.0.0.1:" + stub.getAddress().getPort(),
            "realm_client", "testrealm",
            "access_key", "test-access-key",
            "authenticator", "password"));
        model.save(row);

        try {
            navigateToApp("/admin/auth-providers/" + row.get(SiteAuthProviderModel.ID));
            waitForHydration();

            // The endpoint config field is a UrlField: a native url input.
            assertThat(page.locator("pl-field[data-path='config.endpoint'] input[type='url']").count())
                .isEqualTo(1);

            assertThat(page.locator("div[role='option'][data-value='site.internal']").count())
                .isEqualTo(1);
            assertThat(page.locator(
                    "div[role='option'][data-value='site.internal'] .pl-select-subtitle").innerText())
                .isEqualTo("Internal staff only");
            // Groups ride along as group.<slug>, described by their title.
            assertThat(page.locator("div[role='option'][data-value='group.admins']").count())
                .isEqualTo(1);
            // The local vocabulary is still merged in.
            assertThat(page.locator("div[role='option'][data-value='hohenheim.admin.access']").count())
                .isEqualTo(1);
        } finally {
            model.delete(row);
            stub.stop(0);
        }
    }

    @Test
    @Order(3)
    void createBasicProviderPersistsEditableCredentials() throws Exception {
        // KeyValueField transport: config.credentials indexed row scopes.
        var response = postForm("/admin/auth-providers/new",
            "name=Staff+Gate&provider_type=hohenheim%3Abasic"
            + "&config.credentials.0.key=alice&config.credentials.0.value=secret123");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row row = Models.get(SiteAuthProviderModel.class).find()
            .where(SiteAuthProviderModel.NAME.eq("Staff Gate")).first();
        assertThat(row).isNotNull();
        assertThat((String) row.get(SiteAuthProviderModel.PROVIDER_TYPE))
            .isEqualTo(BasicAuthProviderType.ID.toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) row.get(SiteAuthProviderModel.CONFIG);
        Map<String, String> credentials = BasicAuthProviderType.credentials(config);
        assertThat(credentials).hasSize(1).containsKey("alice");
        assertThat(credentials.get("alice")).isEqualTo("secret123");
    }

    @Test
    @Order(4)
    void listShowsCreatedProvider() {
        navigateToApp("/admin/auth-providers");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("Staff Gate");
    }

    @Test
    @Order(5)
    void editFormRendersTheProvider() {
        Row row = Models.get(SiteAuthProviderModel.class).find()
            .where(SiteAuthProviderModel.NAME.eq("Staff Gate")).first();
        Integer id = row.get(SiteAuthProviderModel.ID);

        navigateToApp("/admin/auth-providers/" + id);
        waitForHydration();

        assertThat(page.content()).contains("Staff Gate");
        assertThat(page.locator("form").count()).isGreaterThan(0);
        assertThat(page.content()).contains("placeholder=\"Username\"")
            .contains("placeholder=\"Password\"");
        var password = page.locator("pl-input[name='config.credentials.0.value'] input");
        assertThat(password.inputValue()).isEqualTo("secret123");
        assertThat(password.getAttribute("type")).isNotEqualTo("password");
    }

    @Test
    @Order(6)
    void siteCreateFormOffersAuthProviderPick() {
        navigateToApp("/admin/sites/new");
        waitForHydration();

        assertThat(page.content()).contains("auth_provider_id");
    }

    @Test
    @Order(7)
    void sidebarLinksToAuthProviders() {
        navigateToApp("/admin");
        waitForHydration();

        PlaywrightAssertions.assertThat(
            page.locator("pl-app-sidebar a[href='/admin/auth-providers']")).hasCount(1);
    }
}
