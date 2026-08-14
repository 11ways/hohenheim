package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.process.SiteApiKeys;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Full site lifecycle through the zenit-cms resource routes: create with
 * type-discriminated settings, nested env-var/api-key transports, git source
 * settings with webhook-secret generation, relation picks, and soft delete.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SiteLifecycleTest extends HohenheimTestBase {

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

    private Row site(String name) {
        return Models.get(SiteModel.class).find().where(SiteModel.NAME.eq(name)).first();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> settingsOf(String name) {
        Row row = site(name);
        assertThat(row).isNotNull();
        Object settings = row.get(SiteModel.SETTINGS);
        return settings instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** Creating and editing the two type-discriminated shapes: a proxy site and a node site with nested transports. */
    @Test
    @Order(1)
    void proxyAndNodeSitesCreateAndEdit() throws Exception {
        var response = postForm("/admin/sites/new",
            "name=Test+Backend&site_type=hohenheim%3Aproxy&source=local"
            + "&settings.forward_host=127.0.0.1&settings.forward_port=8080");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Map<String, Object> proxySettings = settingsOf("Test Backend");
        assertThat(proxySettings.get("forward_host")).isEqualTo("127.0.0.1");
        assertThat(String.valueOf(proxySettings.get("forward_port"))).isEqualTo("8080");

        Row proxyRow = site("Test Backend");
        navigateToApp("/admin/sites/" + proxyRow.get(SiteModel.ID));
        waitForHydration();

        assertThat(page.locator("form").count()).isGreaterThan(0);
        assertThat(page.content()).contains("Test Backend");
        assertThat(page.content()).contains("127.0.0.1");

        response = postForm("/admin/sites/new",
            "name=Node+App&site_type=hohenheim%3Anode&source=local"
            + "&settings.script="
            + "&settings.use_ports=true"
            + "&settings.environment_variables.0.key=NODE_ENV"
            + "&settings.environment_variables.0.value=production"
            + "&settings.environment_variables.1.key=APP_PORT"
            + "&settings.environment_variables.1.value=3000"
            // Arrays post as repeated same-name fields (the array-item partial
            // renders every item input with the entry path, unindexed).
            + "&settings.api_keys=alpha-adopted-key-0123456789abcdefgh"
            + "&settings.api_keys=beta-adopted-key-zyxwvutsrq9876543210");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Map<String, Object> nodeSettings = settingsOf("Node App");

        @SuppressWarnings("unchecked")
        Map<String, String> env = (Map<String, String>) nodeSettings.get("environment_variables");
        assertThat(env)
            .containsEntry("NODE_ENV", "production")
            .containsEntry("APP_PORT", "3000");

        // Typed api keys are ADOPTED as digests: the plaintext is never stored.
        @SuppressWarnings("unchecked")
        List<String> apiKeys = (List<String>) nodeSettings.get("api_keys");
        assertThat(apiKeys).containsExactly(
            SiteApiKeys.digest("alpha-adopted-key-0123456789abcdefgh"), SiteApiKeys.digest("beta-adopted-key-zyxwvutsrq9876543210"));
        assertThat(nodeSettings.get("use_ports")).isEqualTo(true);

        Row nodeRow = site("Node App");
        String nodePath = "/admin/sites/" + nodeRow.get(SiteModel.ID);
        navigateToApp(nodePath);
        waitForHydration();

        String content = page.content();
        assertThat(content).contains("environment_variables");
        assertThat(content)
            .as("the secret env map still shows its KEYS in the form")
            .contains("NODE_ENV");
        assertThat(content)
            .as("a secret() env map never echoes its VALUES back into the form")
            .doesNotContain("production");
        assertThat(content)
            .as("a secret() api key is never echoed back into the form")
            .doesNotContain("alpha-adopted-key-0123456789abcdefgh");

        response = postForm(nodePath,
            "name=Node+App&site_type=hohenheim%3Anode&source=local&settings.use_ports=false");
        assertThat(response.statusCode()).isIn(200, 302, 303);
        assertThat(settingsOf("Node App").get("use_ports")).isEqualTo(false);
        // An edit that never mentions the secret list must keep the stored keys.
        assertThat((List<String>) settingsOf("Node App").get("api_keys"))
            .as("a submit with no api_keys (ABSENT) entry keeps the stored digests")
            .containsExactly(SiteApiKeys.digest("alpha-adopted-key-0123456789abcdefgh"), SiteApiKeys.digest("beta-adopted-key-zyxwvutsrq9876543210"));

        // The other blank shape: a submit that DOES carry the field but EMPTY
        // (settings.api_keys= with no value) coerces to an empty list. A secret
        // ListField must treat an empty submit as "keep stored", not "clear" --
        // otherwise every admin save that does not re-enter keys silently wipes them.
        response = postForm(nodePath,
            "name=Node+App&site_type=hohenheim%3Anode&source=local&settings.use_ports=false"
            + "&settings.api_keys=");
        assertThat(response.statusCode()).isIn(200, 302, 303);
        assertThat((List<String>) settingsOf("Node App").get("api_keys"))
            .as("a submit with an EMPTY api_keys list keeps the stored digests, not clears them")
            .containsExactly(SiteApiKeys.digest("alpha-adopted-key-0123456789abcdefgh"), SiteApiKeys.digest("beta-adopted-key-zyxwvutsrq9876543210"));

        response = postForm(nodePath,
            "name=Node+App&site_type=hohenheim%3Anode&source=local&settings.use_ports=true");
        assertThat(response.statusCode()).isIn(200, 302, 303);
        assertThat(settingsOf("Node App").get("use_ports")).isEqualTo(true);
    }

    /** Redirect and git-sourced creation, the access-list relation pick, the row actions and soft delete. */
    @Test
    @Order(2)
    void redirectAndGitSitesThroughActionsToDeletion() throws Exception {
        var response = postForm("/admin/sites/new",
            "name=Old+Domain&site_type=hohenheim%3Aredirect&source=local"
            + "&settings.target_url=https%3A%2F%2Fexample.com&settings.http_status=301");
        assertThat(response.statusCode()).isIn(200, 302, 303);
        assertThat(settingsOf("Old Domain").get("target_url")).isEqualTo("https://example.com");

        response = postForm("/admin/sites/new",
            "name=Git+App&site_type=hohenheim%3Astatic&source=git"
            + "&settings.root_path=%2Fvar%2Fwww%2Fgitapp"
            + "&source_settings.repository_url=https%3A%2F%2Fexample.com%2Frepo.git"
            + "&source_settings.build_environment_variables.0.key=CI"
            + "&source_settings.build_environment_variables.0.value=true");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row gitRow = site("Git App");
        assertThat(gitRow).isNotNull();
        assertThat((String) gitRow.get(SiteModel.SOURCE)).isEqualTo(SiteModel.SOURCE_GIT);

        @SuppressWarnings("unchecked")
        Map<String, Object> sourceSettings = (Map<String, Object>) gitRow.get(SiteModel.SOURCE_SETTINGS);
        assertThat(sourceSettings.get("repository_url")).isEqualTo("https://example.com/repo.git");
        assertThat(String.valueOf(sourceSettings.get("webhook_secret")))
            .as("a webhook secret is auto-generated on first save")
            .isNotBlank();

        @SuppressWarnings("unchecked")
        Map<String, String> buildEnv = (Map<String, String>) sourceSettings.get("build_environment_variables");
        assertThat(buildEnv).containsEntry("CI", "true");

        response = postForm("/admin/access-lists/new",
            "name=Office+Only&satisfy=any&allowed_ips=10.0.0.0%2F8");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row listRow = Models.get(AccessListModel.class).find()
            .where(AccessListModel.NAME.eq("Office Only")).first();
        assertThat(listRow).isNotNull();
        Integer listId = listRow.get(AccessListModel.ID);

        Row redirectRow = site("Old Domain");
        Integer redirectId = redirectRow.get(SiteModel.ID);
        response = postForm("/admin/sites/" + redirectId,
            "name=Old+Domain&site_type=hohenheim%3Aredirect&source=local"
            + "&settings.target_url=https%3A%2F%2Fexample.com"
            + "&access_list_id=" + listId);
        assertThat(response.statusCode()).isIn(200, 302, 303);
        assertThat((Integer) site("Old Domain").get(SiteModel.ACCESS_LIST_ID)).isEqualTo(listId);

        assertThat((Boolean) site("Old Domain").get(SiteModel.ENABLED)).isNotEqualTo(Boolean.FALSE);

        postForm("/admin/sites/" + redirectId + "/action/toggle_site", "");
        assertThat((Boolean) Models.get(SiteModel.class).findById(redirectId).get(SiteModel.ENABLED))
            .isEqualTo(false);

        postForm("/admin/sites/" + redirectId + "/action/toggle_site", "");
        assertThat((Boolean) Models.get(SiteModel.class).findById(redirectId).get(SiteModel.ENABLED))
            .isEqualTo(true);

        response = postForm("/admin/sites/" + redirectId + "/action/clone_site", "");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row clone = site("Old Domain (copy)");
        assertThat(clone).isNotNull();
        assertThat((Boolean) clone.get(SiteModel.ENABLED))
            .as("clones start disabled")
            .isEqualTo(false);

        // Cloning a site that HAS api keys must not hand them to the copy: they
        // are per-site bearer credentials, like the regenerated webhook secret.
        Integer nodeId = site("Node App").get(SiteModel.ID);
        response = postForm("/admin/sites/" + nodeId + "/action/clone_site", "");
        assertThat(response.statusCode()).isIn(200, 302, 303);
        assertThat(settingsOf("Node App (copy)").get("api_keys"))
            .as("a clone must carry none of the source's api keys")
            .isNull();

        // Minting discloses the plaintext ONCE, in the toast; only the digest lands.
        int keysBefore = ((List<?>) settingsOf("Node App").get("api_keys")).size();
        response = postForm("/admin/sites/" + nodeId + "/action/generate_api_key", "");
        assertThat(response.statusCode()).isIn(200, 302, 303);
        List<String> keysAfter = (List<String>) settingsOf("Node App").get("api_keys");
        assertThat(keysAfter)
            .as("generating a key must append exactly one digest")
            .hasSize(keysBefore + 1);
        assertThat(keysAfter.get(keysAfter.size() - 1))
            .as("a generated key must be stored as a digest, never as plaintext")
            .startsWith("sha256:")
            .doesNotContain(SiteApiKeys.KEY_MARKER);

        navigateToApp("/admin/sites/" + nodeId);
        waitForHydration();
        String minted = page.content();
        int markerAt = minted.indexOf(SiteApiKeys.KEY_MARKER);
        assertThat(markerAt)
            .as("the mint toast is the one disclosure of the plaintext key")
            .isGreaterThan(-1);
        navigateToApp("/admin/sites/" + nodeId);
        waitForHydration();
        assertThat(page.content())
            .as("a reload must not re-disclose the generated key")
            .doesNotContain(minted.substring(markerAt, markerAt + 20));

        Integer gitId = gitRow.get(SiteModel.ID);
        response = postForm("/admin/sites/" + gitId + "/delete", "");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row after = Models.get(SiteModel.class).findById(gitId);
        assertThat((Object) after.get(SiteModel.DELETED_AT)).isNotNull();

        // One list render proves both the live site is listed and the soft-deleted one is hidden.
        navigateToApp("/admin/sites");
        waitForHydration();
        assertThat(page.content()).contains("Test Backend");
        assertThat(page.content()).doesNotContain("Git App");
    }
}
