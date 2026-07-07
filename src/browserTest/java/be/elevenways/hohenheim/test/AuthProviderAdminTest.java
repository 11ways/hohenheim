package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auth-provider CRUD through the zenit-cms resource routes, including the
 * type-discriminated config sub-form and save-time credential hashing.
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
        assertThat(body).contains("name");
        assertThat(page.content()).contains("provider_type");
    }

    @Test
    @Order(3)
    void createBasicProviderPersistsHashedCredentials() throws Exception {
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
        Map<String, String> credentials = BasicAuthProviderType.credentialHashes(config);
        assertThat(credentials).hasSize(1).containsKey("alice");
        assertThat(credentials.get("alice"))
            .as("password must be stored hashed, never plaintext")
            .isNotEqualTo("secret123").isNotBlank();
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
