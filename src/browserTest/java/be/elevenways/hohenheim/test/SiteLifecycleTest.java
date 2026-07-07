package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.SiteModel;
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

    @Test
    @Order(1)
    void createProxySiteWithNestedSettings() throws Exception {
        var response = postForm("/admin/sites/new",
            "name=Test+Backend&site_type=hohenheim%3Aproxy&source=local"
            + "&settings.forward_host=127.0.0.1&settings.forward_port=8080");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Map<String, Object> settings = settingsOf("Test Backend");
        assertThat(settings.get("forward_host")).isEqualTo("127.0.0.1");
        assertThat(String.valueOf(settings.get("forward_port"))).isEqualTo("8080");
    }

    @Test
    @Order(2)
    void siteAppearsInList() {
        navigateToApp("/admin/sites");
        waitForHydration();
        assertThat(page.content()).contains("Test Backend");
    }

    @Test
    @Order(3)
    void editPageRendersTheSite() {
        Row row = site("Test Backend");
        navigateToApp("/admin/sites/" + row.get(SiteModel.ID));
        waitForHydration();

        assertThat(page.locator("form").count()).isGreaterThan(0);
        assertThat(page.content()).contains("Test Backend");
        assertThat(page.content()).contains("127.0.0.1");
    }

    @Test
    @Order(4)
    void createNodeSiteWithEnvVarsAndApiKeys() throws Exception {
        var response = postForm("/admin/sites/new",
            "name=Node+App&site_type=hohenheim%3Anode&source=local"
            + "&settings.script="
            + "&settings.use_ports=true"
            + "&settings.environment_variables.0.key=NODE_ENV"
            + "&settings.environment_variables.0.value=production"
            + "&settings.environment_variables.1.key=APP_PORT"
            + "&settings.environment_variables.1.value=3000"
            // Arrays post as repeated same-name fields (the array-item partial
            // renders every item input with the entry path, unindexed).
            + "&settings.api_keys=alpha-key"
            + "&settings.api_keys=beta-key");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Map<String, Object> settings = settingsOf("Node App");

        @SuppressWarnings("unchecked")
        Map<String, String> env = (Map<String, String>) settings.get("environment_variables");
        assertThat(env)
            .containsEntry("NODE_ENV", "production")
            .containsEntry("APP_PORT", "3000");

        @SuppressWarnings("unchecked")
        List<String> apiKeys = (List<String>) settings.get("api_keys");
        assertThat(apiKeys).containsExactly("alpha-key", "beta-key");
        assertThat(settings.get("use_ports")).isEqualTo(true);
    }

    @Test
    @Order(5)
    void nodeSiteEditRendersEnvVarEditor() {
        Row row = site("Node App");
        navigateToApp("/admin/sites/" + row.get(SiteModel.ID));
        waitForHydration();

        String content = page.content();
        assertThat(content).contains("environment_variables");
        assertThat(content).contains("NODE_ENV");
        assertThat(content).contains("production");
        assertThat(content).contains("alpha-key");
    }

    @Test
    @Order(6)
    void nodeSiteUpdateRoundTripsUsePorts() throws Exception {
        Row row = site("Node App");
        String path = "/admin/sites/" + row.get(SiteModel.ID);

        var response = postForm(path,
            "name=Node+App&site_type=hohenheim%3Anode&source=local&settings.use_ports=false");
        assertThat(response.statusCode()).isIn(200, 302, 303);
        assertThat(settingsOf("Node App").get("use_ports")).isEqualTo(false);

        response = postForm(path,
            "name=Node+App&site_type=hohenheim%3Anode&source=local&settings.use_ports=true");
        assertThat(response.statusCode()).isIn(200, 302, 303);
        assertThat(settingsOf("Node App").get("use_ports")).isEqualTo(true);
    }

    @Test
    @Order(7)
    void createRedirectSite() throws Exception {
        var response = postForm("/admin/sites/new",
            "name=Old+Domain&site_type=hohenheim%3Aredirect&source=local"
            + "&settings.target_url=https%3A%2F%2Fexample.com&settings.http_status=301");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Map<String, Object> settings = settingsOf("Old Domain");
        assertThat(settings.get("target_url")).isEqualTo("https://example.com");
    }

    @Test
    @Order(8)
    void gitSourcedSiteGetsAWebhookSecret() throws Exception {
        var response = postForm("/admin/sites/new",
            "name=Git+App&site_type=hohenheim%3Astatic&source=git"
            + "&settings.root_path=%2Fvar%2Fwww%2Fgitapp"
            + "&source_settings.repository_url=https%3A%2F%2Fexample.com%2Frepo.git"
            + "&source_settings.build_environment_variables.0.key=CI"
            + "&source_settings.build_environment_variables.0.value=true");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row row = site("Git App");
        assertThat(row).isNotNull();
        assertThat((String) row.get(SiteModel.SOURCE)).isEqualTo(SiteModel.SOURCE_GIT);

        @SuppressWarnings("unchecked")
        Map<String, Object> sourceSettings = (Map<String, Object>) row.get(SiteModel.SOURCE_SETTINGS);
        assertThat(sourceSettings.get("repository_url")).isEqualTo("https://example.com/repo.git");
        assertThat(String.valueOf(sourceSettings.get("webhook_secret")))
            .as("a webhook secret is auto-generated on first save")
            .isNotBlank();

        @SuppressWarnings("unchecked")
        Map<String, String> buildEnv = (Map<String, String>) sourceSettings.get("build_environment_variables");
        assertThat(buildEnv).containsEntry("CI", "true");
    }

    @Test
    @Order(9)
    void siteFormAttachesAccessList() throws Exception {
        var response = postForm("/admin/access-lists/new",
            "name=Office+Only&satisfy=any&allowed_ips=10.0.0.0%2F8");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row listRow = Models.get(AccessListModel.class).find()
            .where(AccessListModel.NAME.eq("Office Only")).first();
        assertThat(listRow).isNotNull();
        Integer listId = listRow.get(AccessListModel.ID);

        Row row = site("Old Domain");
        response = postForm("/admin/sites/" + row.get(SiteModel.ID),
            "name=Old+Domain&site_type=hohenheim%3Aredirect&source=local"
            + "&settings.target_url=https%3A%2F%2Fexample.com"
            + "&access_list_id=" + listId);
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row updated = site("Old Domain");
        assertThat((Integer) updated.get(SiteModel.ACCESS_LIST_ID)).isEqualTo(listId);
    }

    @Test
    @Order(10)
    void toggleActionDisablesAndEnables() throws Exception {
        Row row = site("Old Domain");
        Integer id = row.get(SiteModel.ID);
        assertThat((Boolean) row.get(SiteModel.ENABLED)).isNotEqualTo(Boolean.FALSE);

        postForm("/admin/sites/" + id + "/action/toggle_site", "");
        assertThat((Boolean) Models.get(SiteModel.class).findById(id).get(SiteModel.ENABLED))
            .isEqualTo(false);

        postForm("/admin/sites/" + id + "/action/toggle_site", "");
        assertThat((Boolean) Models.get(SiteModel.class).findById(id).get(SiteModel.ENABLED))
            .isEqualTo(true);
    }

    @Test
    @Order(11)
    void cloneActionCopiesTheSite() throws Exception {
        Row row = site("Old Domain");
        var response = postForm("/admin/sites/" + row.get(SiteModel.ID) + "/action/clone_site", "");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row clone = site("Old Domain (copy)");
        assertThat(clone).isNotNull();
        assertThat((Boolean) clone.get(SiteModel.ENABLED))
            .as("clones start disabled")
            .isEqualTo(false);
    }

    @Test
    @Order(12)
    void deleteSoftDeletesAndHidesFromList() throws Exception {
        Row row = site("Git App");
        Integer id = row.get(SiteModel.ID);

        var response = postForm("/admin/sites/" + id + "/delete", "");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row after = Models.get(SiteModel.class).findById(id);
        assertThat((Object) after.get(SiteModel.DELETED_AT)).isNotNull();

        navigateToApp("/admin/sites");
        waitForHydration();
        assertThat(page.content()).doesNotContain("Git App");
    }
}
