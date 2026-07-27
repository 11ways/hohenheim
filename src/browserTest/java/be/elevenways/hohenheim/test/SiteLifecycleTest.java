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
            + "&settings.api_keys=alpha-key"
            + "&settings.api_keys=beta-key");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Map<String, Object> nodeSettings = settingsOf("Node App");

        @SuppressWarnings("unchecked")
        Map<String, String> env = (Map<String, String>) nodeSettings.get("environment_variables");
        assertThat(env)
            .containsEntry("NODE_ENV", "production")
            .containsEntry("APP_PORT", "3000");

        @SuppressWarnings("unchecked")
        List<String> apiKeys = (List<String>) nodeSettings.get("api_keys");
        assertThat(apiKeys).containsExactly("alpha-key", "beta-key");
        assertThat(nodeSettings.get("use_ports")).isEqualTo(true);

        Row nodeRow = site("Node App");
        String nodePath = "/admin/sites/" + nodeRow.get(SiteModel.ID);
        navigateToApp(nodePath);
        waitForHydration();

        String content = page.content();
        assertThat(content).contains("environment_variables");
        assertThat(content).contains("NODE_ENV");
        assertThat(content).contains("production");
        assertThat(content).contains("alpha-key");

        response = postForm(nodePath,
            "name=Node+App&site_type=hohenheim%3Anode&source=local&settings.use_ports=false");
        assertThat(response.statusCode()).isIn(200, 302, 303);
        assertThat(settingsOf("Node App").get("use_ports")).isEqualTo(false);

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
