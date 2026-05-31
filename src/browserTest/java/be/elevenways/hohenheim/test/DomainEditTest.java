package be.elevenways.hohenheim.test;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.auth.server.AuthCookieSupport;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.util.List;

/**
 * Tests domain editing: navigation, form fields, save, and advanced settings.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DomainEditTest extends HohenheimTestBase {

    private static String siteId;
    private static String domainId;

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

    private HttpResponse<String> getPage(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .GET()
            .build();

        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void createSiteWithDomain() throws Exception {
        var response = postForm("/sites/create",
            "name=Domain+Test+Site&site_type=hohenheim%3Aproxy"
            + "&forward_host=127.0.0.1&forward_port=9090&hostname=edit-test.example.com");
        assertThat(response.statusCode()).isEqualTo(302);

        // Query the database directly for the IDs
        var ds = HohenheimDatabase.datasource();
        var siteModel = Models.get(SiteModel.class);
        var domainModel = Models.get(SiteDomainModel.class);

        Row site = siteModel.find().where(SiteModel.NAME.eq("Domain Test Site")).first();
        assertThat(site).isNotNull();
        siteId = String.valueOf(site.get(SiteModel.ID));

        List<Row> domains = domainModel.findBySiteId(site.get(SiteModel.ID));
        assertThat(domains).isNotEmpty();
        domainId = String.valueOf(domains.get(0).get(SiteDomainModel.ID));
    }

    @Test
    @Order(2)
    void domainHostnameLinksToEditPage() {
        navigateToApp("/sites");
        waitForHydration();

        page.locator(".hh-site-link").first().click();
        page.waitForCondition(() -> {
            var el = page.querySelector(".hh-header__title");
            return el != null && "Domain Test Site".equals(el.textContent());
        });

        // The hostname should be a link now
        var domainLink = page.locator("pl-table-cell a").first();
        assertThat(domainLink.textContent()).isEqualTo("edit-test.example.com");

        String href = domainLink.getAttribute("href");
        assertThat(href).contains("/domains/");
    }

    @Test
    @Order(3)
    void domainEditPageLoads() {
        navigateToApp("/sites");
        waitForHydration();

        page.locator(".hh-site-link").first().click();
        page.waitForCondition(() -> {
            var el = page.querySelector(".hh-header__title");
            return el != null && "Domain Test Site".equals(el.textContent());
        });

        // Click the domain link
        page.locator("pl-table-cell a").first().click();
        page.waitForCondition(() -> {
            var el = page.querySelector(".hh-header__title");
            return el != null && "Edit Domain".equals(el.textContent());
        });

        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Edit Domain");
        assertThat(page.locator(".hh-header__subtitle").textContent()).contains("edit-test.example.com");
    }

    @Test
    @Order(4)
    void domainEditFormShowsAllFields() {
        // Navigate directly via full page load
        navigateToApp("/sites/" + siteId + "/domains/" + domainId);
        waitForHydration();

        // Verify the page loaded
        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Edit Domain");

        // Basic fields present
        assertThat(page.locator("input[name='force_ssl']").count()).isEqualTo(1);
        assertThat(page.locator("input[name='hsts_enabled']").count()).isEqualTo(1);
        assertThat(page.locator("input[name='hsts_subdomains']").count()).isEqualTo(1);
        assertThat(page.locator("pl-select[name='match_type']").count()).isEqualTo(1);

        // Advanced section exists
        assertThat(page.locator("pl-collapsible").count()).isEqualTo(1);
        assertThat(page.content()).contains("Advanced Settings");
        assertThat(page.content()).contains("Path Prefix");
    }

    @Test
    @Order(5)
    void updateDomainSettings() throws Exception {
        var response = postForm("/sites/" + siteId + "/domains/" + domainId,
            "hostname=edit-test.example.com&match_type=exact&force_ssl=on&hsts_enabled=on&path=%2Fapi&strip_path=on");

        assertThat(response.statusCode()).isEqualTo(302);

        // Verify the updated domain by loading the edit page again
        var editAfter = getPage("/sites/" + siteId + "/domains/" + domainId);
        assertThat(editAfter.statusCode()).isEqualTo(200);
        String afterBody = editAfter.body();
        assertThat(afterBody).contains("edit-test.example.com");
        assertThat(afterBody).contains("hsts_enabled");
    }

    @Test
    @Order(6)
    void updateDomainRequiresHostname() throws Exception {
        // Submit with empty hostname
        var response = postForm("/sites/" + siteId + "/domains/" + domainId,
            "hostname=&match_type=exact");

        // Should re-render with error, not redirect
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Hostname is required");
    }
}
