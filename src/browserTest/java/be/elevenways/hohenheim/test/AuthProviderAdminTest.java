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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin UI test for the auth-provider CRUD pages and the site-form provider dropdown.
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
        navigateToApp("/auth-providers");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("Auth Providers");
        assertThat(body).contains("Create Auth Provider");
        assertThat(body).contains("No auth providers yet.");
    }

    @Test
    @Order(2)
    void createFormShowsProteusFieldsByDefault() {
        navigateToApp("/auth-providers/create");
        waitForHydration();

        String form = page.locator("form[action='/auth-providers/create']").textContent();
        assertThat(form).contains("Name");
        assertThat(form).contains("Provider Type");
        assertThat(form).contains("Realm Server URL");
        assertThat(form).contains("Required Permission");
    }

    @Test
    @Order(3)
    void createBasicProviderPersistsHashedCredentials() throws Exception {
        var response = postForm("/auth-providers/create",
            "name=Staff+Gate&provider_type=hohenheim%3Abasic"
            + "&credentials%5B0%5D.name=alice&credentials%5B0%5D.value=secret123");
        assertThat(response.statusCode()).isEqualTo(302);

        Row row = Models.get(SiteAuthProviderModel.class).find()
            .where(SiteAuthProviderModel.NAME.eq("Staff Gate")).first();
        assertThat(row).isNotNull();
        assertThat((String) row.get(SiteAuthProviderModel.PROVIDER_TYPE))
            .isEqualTo(BasicAuthProviderType.ID.toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) row.get(SiteAuthProviderModel.CONFIG);
        List<Map<String, Object>> credentials = BasicAuthProviderType.credentialList(config);
        assertThat(credentials).hasSize(1);
        assertThat(credentials.get(0).get(BasicAuthProviderType.USERNAME)).isEqualTo("alice");
        assertThat((String) credentials.get(0).get(BasicAuthProviderType.PASSWORD_HASH))
            .as("password must be stored hashed, never plaintext")
            .isNotEqualTo("secret123").isNotBlank();
    }

    @Test
    @Order(4)
    void listShowsCreatedProvider() {
        navigateToApp("/auth-providers");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("Staff Gate");
        assertThat(body).contains("HTTP Basic");
    }

    @Test
    @Order(5)
    void editFormShowsStoredValuesWithBlankPassword() {
        Row row = Models.get(SiteAuthProviderModel.class).find()
            .where(SiteAuthProviderModel.NAME.eq("Staff Gate")).first();
        Integer id = row.get(SiteAuthProviderModel.ID);

        navigateToApp("/auth-providers/" + id);
        waitForHydration();

        String form = page.locator("form[action='/auth-providers/" + id + "']").textContent();
        assertThat(form).contains("Provider Type");
        // Username round-trips; the password input must come back blank (write-only).
        assertThat(page.locator("pl-input[name='credentials[0].name'] input").inputValue())
            .isEqualTo("alice");
        assertThat(page.locator("pl-input[name='credentials[0].value'] input").inputValue())
            .isEmpty();
    }

    @Test
    @Order(6)
    void siteCreateFormOffersAuthProviderDropdown() {
        navigateToApp("/sites/create");
        waitForHydration();

        String form = page.locator("form[action='/sites/create']").textContent();
        assertThat(form).contains("Auth Provider");
        // pl-select renders its option list through a portal at the document bottom,
        // so the provider option text lives outside the form element.
        assertThat(page.locator("body").textContent()).contains("Staff Gate");
    }

    @Test
    @Order(7)
    void sidebarLinksToAuthProviders() {
        navigateToApp("/");
        waitForHydration();

        PlaywrightAssertions.assertThat(
            page.locator("pl-app-sidebar a[href='/auth-providers']")).hasCount(1);
    }
}
