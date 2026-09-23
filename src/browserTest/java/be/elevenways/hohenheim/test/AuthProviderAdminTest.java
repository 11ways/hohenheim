package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.server.auth.BasicCredentials;
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
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auth-provider CRUD through the zenit-cms resource routes, including the
 * type-discriminated config sub-form and editable Basic credentials.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthProviderAdminTest extends HohenheimTestBase {

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

    /** The create form's type selector, its config placeholder, and the known-permission combobox. */
    @Test
    @Order(1)
    void createFormOffersTypeSelectorAndPermissionVocabulary() {
        navigateToApp("/admin/auth-providers/new");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("Name").contains("Provider type");
        assertThat(page.content()).contains("provider_type");

        // No provider type selected yet: the config fieldset must SAY so
        // instead of rendering a mysteriously empty section.
        var placeholder = page.locator("zf-schema-sub-form pl-empty-state");
        assertThat(placeholder.count()).isEqualTo(1);
        assertThat(placeholder.innerText()).contains("choose a type");

        // PermissionField: a free-text pl-select over the KnownPermissions
        // vocabulary (the LuckPerms editor model) with described entries.
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

        // Choosing a type swaps the placeholder for the type's fields, live.
        page.locator("pl-select[name='provider_type'] .pl-select-field").click();
        page.waitForSelector("he-bottom .pl-select-popup[data-open]");
        page.locator("div[role='option'][data-value='hohenheim:basic']").click();
        page.waitForSelector("zf-schema-sub-form pl-field");

        assertThat(page.locator("zf-schema-sub-form pl-empty-state").count()).isZero();

        // Free text commits as the value: type an unlisted permission, Enter.
        picker.locator(".pl-select-field").click();
        var popup = page.locator(
            "he-bottom .pl-select-popup[data-open]");
        popup.locator("input[role='searchbox']").fill("custom.special.permission");
        popup.locator("input[role='searchbox']").press("Enter");
        assertThat(picker.locator(".pl-select-value").innerText().trim())
            .isEqualTo("custom.special.permission");
    }

    /** A created Basic provider shows up in the list; its passwords are stored hashed and edited write-only. */
    @Test
    @Order(2)
    void basicProviderRoundTripsThroughListAndEditForm() throws Exception {
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
        assertThat(BasicCredentials.isHashed(credentials.get("alice")))
            .as("the typed password is stored as its argon2 hash").isTrue();

        navigateToApp("/admin/auth-providers");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("Auth providers");
        assertThat(body).contains("Staff Gate");
        // The sidebar deliberately does NOT carry this entry any more: auth providers are
        // reached from the Sites list header (AdminNavigationJourneyTest step 6 pins the
        // link, step 4 pins that this page still answers). The page is what matters here.
        PlaywrightAssertions.assertThat(
            page.locator("pl-app-sidebar a[href='/admin/auth-providers']")).hasCount(0);

        // Regression: over soft navigation the form renders CLIENT-side; the
        // permission descriptions are Java-registered microcopy, which only
        // resolves in the browser when the served bundle carries catalog keys
        // (they are in no template manifest). A raw "auth_admin_access" here
        // means the bundle union regressed to manifest-only.
        page.locator("a[href='/admin/auth-providers/new']").first().click();
        page.waitForCondition(() ->
            page.locator("pl-select[name='required_permission']").count() == 1);

        var subtitle = page.locator(
            "div[role='option'][data-value='auth.admin.access'] .pl-select-subtitle");
        page.waitForCondition(() -> subtitle.count() == 1
            && !subtitle.innerText().trim().equals("auth_admin_access")
            && !subtitle.innerText().trim().isEmpty());
        assertThat(subtitle.innerText()).isEqualTo("Access the admin panel");

        navigateToApp("/admin/auth-providers/" + row.get(SiteAuthProviderModel.ID));
        waitForHydration();

        assertThat(page.content()).contains("Staff Gate");
        assertThat(page.locator("form").count()).isGreaterThan(0);
        assertThat(page.content()).contains("placeholder=\"Username\"")
            .contains("placeholder=\"Password\"");
        // The password is WRITE-ONLY: the username row is there, its box is blank, and
        // neither the plaintext nor the stored argon2 hash reaches the page.
        String storedHash = credentials.get("alice");
        var username = page.locator("pl-input[name='config.credentials.0.key'] input");
        assertThat(username.inputValue()).as("the username stays visible").isEqualTo("alice");
        var password = page.locator("pl-input[name='config.credentials.0.value'] input");
        assertThat(password.inputValue()).as("the password box is blank").isEmpty();
        assertThat(page.content()).as("the stored hash is never rendered")
            .doesNotContain(storedHash)
            .doesNotContain("secret123");

        // A blank password on save KEEPS the stored hash, which still verifies.
        String path = "/admin/auth-providers/" + row.get(SiteAuthProviderModel.ID);
        var kept = postForm(path, "name=Staff+Gate&provider_type=hohenheim%3Abasic"
            + "&config.credentials.0.key=alice&config.credentials.0.value=");
        assertThat(kept.statusCode()).as("a blank-password save succeeds: " + kept.body())
            .isIn(200, 302, 303);
        Map<String, String> afterKeep = storedCredentials();
        assertThat(afterKeep.get("alice")).as("a blank password keeps the stored hash")
            .isEqualTo(storedHash);
        assertThat(BasicAuthProviderType.verify(basicHeader("alice", "secret123"), afterKeep))
            .as("and the kept password still verifies").isEqualTo("alice");

        // A typed password REPLACES it, hashed again.
        var replaced = postForm(path, "name=Staff+Gate&provider_type=hohenheim%3Abasic"
            + "&config.credentials.0.key=alice&config.credentials.0.value=newpass456");
        assertThat(replaced.statusCode()).as("a new-password save succeeds: " + replaced.body())
            .isIn(200, 302, 303);
        Map<String, String> afterReplace = storedCredentials();
        assertThat(BasicCredentials.isHashed(afterReplace.get("alice")))
            .as("the replacement is stored hashed").isTrue();
        assertThat(BasicAuthProviderType.verify(basicHeader("alice", "newpass456"), afterReplace))
            .as("the new password verifies").isEqualTo("alice");
        assertThat(BasicAuthProviderType.verify(basicHeader("alice", "secret123"), afterReplace))
            .as("and the old one no longer does").isNull();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> storedCredentials() {
        Row row = Models.get(SiteAuthProviderModel.class).find()
            .where(SiteAuthProviderModel.NAME.eq("Staff Gate")).first();
        assertThat(row).isNotNull();
        return BasicAuthProviderType.credentials(
            (Map<String, Object>) row.get(SiteAuthProviderModel.CONFIG));
    }

    private static String basicHeader(String username, String password) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** A Proteus provider merges its realm's vocabulary into the suggestions; sites can pick providers. */
    @Test
    @Order(3)
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

        // The site create form offers the auth-provider pick.
        navigateToApp("/admin/sites/new");
        waitForHydration();

        assertThat(page.content()).contains("auth_provider_id");
    }
}
