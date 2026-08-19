package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.PasswordHasher;
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

    /** The argon2 hash stored inside one rule's per-type data map. */
    private static String storedPassword(int ruleId) {
        Row rule = Models.get(AccessRuleModel.class).findById(ruleId);
        Object value = AccessRuleModel.dataOf(rule).get(AccessRuleModel.BASIC_AUTH_PASSWORD.getName());
        return value == null ? null : String.valueOf(value);
    }

    @Test
    @Order(1)
    void storedSecretsStayServerSideAndBlankSavesKeepThem() throws Exception {
        // Notification channel: a secret URL field on a plain resource form.
        var response = postForm("/admin/notifications/new",
            "name=Ops+alerts&format=slack&url=" + java.net.URLEncoder.encode(WEBHOOK_URL, "UTF-8"));
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row row = Models.get(NotificationChannelModel.class).findByName("Ops alerts");
        assertThat(row).isNotNull();
        assertThat((String) row.get(NotificationChannelModel.URL)).isEqualTo(WEBHOOK_URL);
        Integer channelId = row.get(NotificationChannelModel.ID);

        navigateToApp("/admin/notifications/" + channelId);
        waitForHydration();
        assertThat(page.content()).doesNotContain(WEBHOOK_URL);
        assertThat(page.content()).doesNotContain("xoxb-hook-token");

        // Blank url keeps the stored secret; other edits apply.
        response = postForm("/admin/notifications/" + channelId, "name=Ops+alerts+renamed&format=slack&url=");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row storedChannel = Models.get(NotificationChannelModel.class).findByName("Ops alerts renamed");
        assertThat(storedChannel).isNotNull();
        assertThat((String) storedChannel.get(NotificationChannelModel.URL)).isEqualTo(WEBHOOK_URL);

        // Proteus access key: a secret inside the dynamic provider-config sub-form.
        response = postForm("/admin/auth-providers/new",
            "name=Realm+gate&provider_type=hohenheim%3Aproteus"
            + "&config.endpoint=https%3A%2F%2Fproteus.example.com"
            + "&config.realm_client=hohenheim-realm"
            + "&config.access_key=" + ACCESS_KEY
            + "&config.authenticator=password");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        row = Models.get(SiteAuthProviderModel.class).find()
            .where(SiteAuthProviderModel.NAME.eq("Realm gate")).first();
        assertThat(row).isNotNull();
        assertThat(configOf(row)).containsEntry("access_key", ACCESS_KEY);
        Integer providerId = row.get(SiteAuthProviderModel.ID);

        navigateToApp("/admin/auth-providers/" + providerId);
        waitForHydration();
        assertThat(page.content()).doesNotContain(ACCESS_KEY);

        // Blank access key keeps the stored one; the endpoint edit applies.
        response = postForm("/admin/auth-providers/" + providerId,
            "name=Realm+gate&provider_type=hohenheim%3Aproteus"
            + "&config.endpoint=https%3A%2F%2Fproteus2.example.com"
            + "&config.realm_client=hohenheim-realm"
            + "&config.access_key="
            + "&config.authenticator=password");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row storedProvider = Models.get(SiteAuthProviderModel.class).find()
            .where(SiteAuthProviderModel.ID.eq(providerId)).first();
        Map<String, Object> config = configOf(storedProvider);
        assertThat(config).containsEntry("access_key", ACCESS_KEY);
        assertThat(config).containsEntry("endpoint", "https://proteus2.example.com");

        // Access rule: a hashed secret INSIDE a dynamic (schemaFrom) sub-form, blank save
        // keeps the hash untouched.
        String password = "access-list-password-4c2a9e";
        response = postForm("/admin/access-lists/new", "name=Private+network&satisfy=any");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        row = Models.get(AccessListModel.class).find()
            .where(AccessListModel.NAME.eq("Private network")).first();
        assertThat(row).isNotNull();
        Integer accessListId = row.get(AccessListModel.ID);

        response = postForm("/admin/access-lists/" + accessListId + "/rules",
            "type=basic_auth");
        assertThat(response.statusCode()).isIn(200, 302, 303);
        Row rule = Models.get(AccessRuleModel.class).find()
            .where(AccessRuleModel.ACCESS_LIST_ID.eq(accessListId)).first();
        assertThat(rule).isNotNull();
        Integer ruleId = rule.get(AccessRuleModel.ID);

        response = postForm("/admin/access-rules/" + ruleId,
            "type=basic_auth&data.username=operator&data.password=" + password + "&enabled=true");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        String storedHash = storedPassword(ruleId);
        assertThat(storedHash).startsWith("$argon2");
        assertThat(PasswordHasher.verify(password, storedHash)).isTrue();

        navigateToApp("/admin/access-rules/" + ruleId);
        waitForHydration();
        assertThat(page.content()).doesNotContain(password);

        response = postForm("/admin/access-rules/" + ruleId,
            "type=basic_auth&data.username=operator&data.password=&enabled=true");
        assertThat(response.statusCode()).isIn(200, 302, 303);
        assertThat(storedPassword(ruleId)).isEqualTo(storedHash);

        // Git webhook secret: a secret inside a site's source settings map.
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
    @Order(2)
    void basicAuthCredentialsRemainVisibleAndEditable() throws Exception {
        var response = postForm("/admin/auth-providers/new",
            "name=Team+gate&provider_type=hohenheim%3Abasic"
            + "&config.credentials.0.key=alice&config.credentials.0.value=secret123");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row row = Models.get(SiteAuthProviderModel.class).find()
            .where(SiteAuthProviderModel.NAME.eq("Team gate")).first();
        assertThat(row).isNotNull();
        Map<String, String> credentials = BasicAuthProviderType.credentials(configOf(row));
        assertThat(credentials.get("alice")).isEqualTo("secret123");
        Integer id = row.get(SiteAuthProviderModel.ID);

        // Basic provider credentials are intentionally operator-visible.
        navigateToApp("/admin/auth-providers/" + id);
        waitForHydration();
        String content = page.content();
        assertThat(content).contains("alice");
        assertThat(content).contains("secret123");

        // Editing the visible password replaces it directly.
        response = postForm("/admin/auth-providers/" + id,
            "name=Team+gate&provider_type=hohenheim%3Abasic"
            + "&config.credentials.0.key=alice&config.credentials.0.value=new-secret");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row stored = Models.get(SiteAuthProviderModel.class).find()
            .where(SiteAuthProviderModel.ID.eq(id)).first();
        Map<String, String> updated = BasicAuthProviderType.credentials(configOf(stored));
        assertThat(updated.get("alice")).isEqualTo("new-secret");
        String header = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("alice:new-secret".getBytes());
        assertThat(BasicAuthProviderType.verify(header, updated)).isEqualTo("alice");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configOf(Row row) {
        return (Map<String, Object>) row.get(SiteAuthProviderModel.CONFIG);
    }
}
