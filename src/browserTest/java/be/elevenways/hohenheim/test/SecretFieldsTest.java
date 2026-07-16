package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stored-secret contract (Field.secret() + FormSecrets) on hohenheim's
 * admin forms: secrets never reach the client and a blank submit keeps the
 * stored value, including through the dynamic provider-config sub-form.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecretFieldsTest extends HohenheimTestBase {

    private static final String WEBHOOK_URL = "https://hooks.example.com/services/T000/B000/xoxb-hook-token";
    private static final String ACCESS_KEY = "proteus-access-key-9f8e7d6c";
    private static final String SITE_SECRET = "site-webhook-secret-a1b2c3d4";

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
    void storedWebhookUrlNeverReachesTheEditPageAndBlankSaveKeepsIt() throws Exception {
        var response = postForm("/admin/notifications/new",
            "name=Ops+alerts&format=slack&url=" + java.net.URLEncoder.encode(WEBHOOK_URL, "UTF-8"));
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row row = Models.get(NotificationChannelModel.class).findByName("Ops alerts");
        assertThat(row).isNotNull();
        assertThat((String) row.get(NotificationChannelModel.URL)).isEqualTo(WEBHOOK_URL);
        Integer id = row.get(NotificationChannelModel.ID);

        navigateToApp("/admin/notifications/" + id);
        waitForHydration();
        assertThat(page.content()).doesNotContain(WEBHOOK_URL);
        assertThat(page.content()).doesNotContain("xoxb-hook-token");

        // Blank url keeps the stored secret; other edits apply.
        response = postForm("/admin/notifications/" + id, "name=Ops+alerts+renamed&format=slack&url=");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row stored = Models.get(NotificationChannelModel.class).findByName("Ops alerts renamed");
        assertThat(stored).isNotNull();
        assertThat((String) stored.get(NotificationChannelModel.URL)).isEqualTo(WEBHOOK_URL);
    }

    @Test
    @Order(2)
    void proteusAccessKeyIsMaskedInsideTheDynamicSubFormAndBlankSaveKeepsIt() throws Exception {
        var response = postForm("/admin/auth-providers/new",
            "name=Realm+gate&provider_type=hohenheim%3Aproteus"
            + "&config.endpoint=https%3A%2F%2Fproteus.example.com"
            + "&config.realm_client=hohenheim-realm"
            + "&config.access_key=" + ACCESS_KEY
            + "&config.authenticator=password");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row row = Models.get(SiteAuthProviderModel.class).find()
            .where(SiteAuthProviderModel.NAME.eq("Realm gate")).first();
        assertThat(row).isNotNull();
        assertThat(configOf(row)).containsEntry("access_key", ACCESS_KEY);
        Integer id = row.get(SiteAuthProviderModel.ID);

        navigateToApp("/admin/auth-providers/" + id);
        waitForHydration();
        assertThat(page.content()).doesNotContain(ACCESS_KEY);

        // Blank access key keeps the stored one; the endpoint edit applies.
        response = postForm("/admin/auth-providers/" + id,
            "name=Realm+gate&provider_type=hohenheim%3Aproteus"
            + "&config.endpoint=https%3A%2F%2Fproteus2.example.com"
            + "&config.realm_client=hohenheim-realm"
            + "&config.access_key="
            + "&config.authenticator=password");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row stored = Models.get(SiteAuthProviderModel.class).find()
            .where(SiteAuthProviderModel.ID.eq(id)).first();
        Map<String, Object> config = configOf(stored);
        assertThat(config).containsEntry("access_key", ACCESS_KEY);
        assertThat(config).containsEntry("endpoint", "https://proteus2.example.com");
    }

    @Test
    @Order(3)
    void gitWebhookSecretNeverReachesTheSiteEditPage() {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, "Secret git site");
        site.set(SiteModel.SLUG, "secret-git-site");
        site.set(SiteModel.SITE_TYPE, "hohenheim:dead");
        site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        site.set(SiteModel.ENABLED, false);
        site.set(SiteModel.SOURCE, SiteModel.SOURCE_GIT);
        site.set(SiteModel.SOURCE_SETTINGS, Map.of(
            "repository_url", "https://git.example.com/site.git",
            "webhook_secret", SITE_SECRET));
        sites.save(site);

        navigateToApp("/admin/sites/" + site.get(SiteModel.ID));
        waitForHydration();
        assertThat(page.content()).doesNotContain(SITE_SECRET);
    }

    @Test
    @Order(4)
    void basicAuthCredentialHashesAreMaskedAndBlankSaveKeepsThemVerifiable() throws Exception {
        var response = postForm("/admin/auth-providers/new",
            "name=Team+gate&provider_type=hohenheim%3Abasic"
            + "&config.credentials.0.key=alice&config.credentials.0.value=secret123");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row row = Models.get(SiteAuthProviderModel.class).find()
            .where(SiteAuthProviderModel.NAME.eq("Team gate")).first();
        assertThat(row).isNotNull();
        Map<String, String> hashes = BasicAuthProviderType.credentialHashes(configOf(row));
        String storedHash = hashes.get("alice");
        assertThat(storedHash).startsWith("$argon2");
        Integer id = row.get(SiteAuthProviderModel.ID);

        // The edit page shows the username but neither the plaintext nor the hash.
        navigateToApp("/admin/auth-providers/" + id);
        waitForHydration();
        String content = page.content();
        assertThat(content).contains("alice");
        assertThat(content).doesNotContain("secret123");
        assertThat(content).doesNotContain("$argon2");

        // A blank password resubmit keeps the stored hash verifiable.
        response = postForm("/admin/auth-providers/" + id,
            "name=Team+gate&provider_type=hohenheim%3Abasic"
            + "&config.credentials.0.key=alice&config.credentials.0.value=");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row stored = Models.get(SiteAuthProviderModel.class).find()
            .where(SiteAuthProviderModel.ID.eq(id)).first();
        Map<String, String> kept = BasicAuthProviderType.credentialHashes(configOf(stored));
        assertThat(kept.get("alice")).isEqualTo(storedHash);
        String header = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("alice:secret123".getBytes());
        assertThat(BasicAuthProviderType.verify(header, kept)).isEqualTo("alice");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configOf(Row row) {
        return (Map<String, Object>) row.get(SiteAuthProviderModel.CONFIG);
    }
}
