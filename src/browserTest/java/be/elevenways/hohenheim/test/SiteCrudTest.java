package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.*;

/**
 * Site CRUD through the zenit-cms resource routes: list, create form,
 * create submit (slug + status derivation), edit, and soft delete.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SiteCrudTest extends HohenheimTestBase {

    private String baseUrl() {
        return "http://localhost:" + getServerPort();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
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
    void sitesListShowsEmptyState() {
        navigateToApp("/admin/sites");
        waitForHydration();
        assertThat(page.locator("pl-empty-state").count()).isEqualTo(1);
    }

    @Test
    @Order(2)
    void createSiteFormLoads() {
        navigateToApp("/admin/sites/new");
        waitForHydration();
        assertThat(page.locator("form").count()).isGreaterThan(0);
        assertThat(page.locator("pl-input").count()).isGreaterThan(0);
        assertThat(page.locator("pl-button").count()).isGreaterThan(0);
        // The type-discriminated settings sub-form renders a type selector.
        assertThat(page.content()).contains("site_type");
    }

    @Test
    @Order(3)
    void createSitePersistsWithSlugAndStatus() throws Exception {
        HttpResponse<String> response = post("/admin/sites/new",
            "name=Crud+Test+Site&site_type=hohenheim%3Adead&enabled=true&source=local");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("Crud Test Site"))
            .first();
        assertThat(site).isNotNull();
        assertThat((String) site.get(SiteModel.SLUG)).isEqualTo("crud-test-site");
        assertThat((String) site.get(SiteModel.STATUS)).isEqualTo(SiteModel.STATUS_ACTIVE);
    }

    @Test
    @Order(4)
    void editFormShowsTheSite() {
        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("Crud Test Site"))
            .first();
        assertThat(site).isNotNull();

        navigateToApp("/admin/sites/" + site.get(SiteModel.ID));
        waitForHydration();

        assertThat(page.content()).contains("Crud Test Site");
        assertThat(page.locator("form").count()).isGreaterThan(0);
    }

    @Test
    @Order(5)
    void siteListShowsTheCreatedSite() {
        navigateToApp("/admin/sites");
        waitForHydration();
        assertThat(page.content()).contains("Crud Test Site");
    }

    @Test
    @Order(6)
    void deleteSoftDeletesTheSite() throws Exception {
        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("Crud Test Site"))
            .first();
        assertThat(site).isNotNull();
        Integer siteId = site.get(SiteModel.ID);

        HttpResponse<String> response = post("/admin/sites/" + siteId + "/delete", "");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row after = Models.get(SiteModel.class).findById(siteId);
        assertThat(after).isNotNull();
        assertThat((Object) after.get(SiteModel.DELETED_AT))
            .as("delete must soft-delete, not remove the row")
            .isNotNull();

        // Soft-deleted sites disappear from the list.
        navigateToApp("/admin/sites");
        waitForHydration();
        assertThat(page.content()).doesNotContain("Crud Test Site");
    }
}
