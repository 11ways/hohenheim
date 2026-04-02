package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;

/**
 * Tests settings, audit log, and certificate pages.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminPagesTest extends HohenheimTestBase {

    private String baseUrl() {
        return "http://localhost:" + getServerPort();
    }

    private void waitForTitle(String expected) {
        page.waitForCondition(() -> {
            var el = page.querySelector(".hh-header__title");
            return el != null && expected.equals(el.textContent());
        });
    }

    // -----------------------------------------------------------------------
    // Proxy state
    // -----------------------------------------------------------------------

    @Test
    @Order(0)
    void dashboardShowsProxyStateAccurately() {
        // In tests, the proxy server is not started -- ServerMain.getProxyServer() returns null
        navigateToApp("/");
        waitForHydration();

        String content = page.locator(".hh-stat-grid").textContent();
        // Should show "Not initialized" or "Stopped" -- never "Running"
        assertThat(content).doesNotContain("Running");
    }

    // -----------------------------------------------------------------------
    // Settings
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    void settingsPageShowsAllGroups() {
        navigateToApp("/settings");
        waitForHydration();

        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Settings");
        String content = page.content();
        assertThat(content).contains("Proxy");
        assertThat(content).contains("Logging");
        assertThat(content).contains("Security");
        assertThat(content).contains("Admin");
    }

    @Test
    @Order(2)
    void settingsPageHasEditableFields() {
        navigateToApp("/settings");
        waitForHydration();

        assertThat(page.locator("pl-input").count()).isGreaterThanOrEqualTo(3);
        assertThat(page.locator("input[type='checkbox']").count()).isGreaterThanOrEqualTo(3);
        assertThat(page.locator("form[action='/settings'] pl-button[type='submit']").count()).isEqualTo(1);
    }

    @Test
    @Order(3)
    void softNavFromDashboardToSettings() {
        navigateToApp("/");
        waitForHydration();

        page.locator(".hh-sidebar__link[href='/settings']").click();
        waitForTitle("Settings");

        assertThat(page.url()).endsWith("/settings");
    }

    // -----------------------------------------------------------------------
    // Audit Log
    // -----------------------------------------------------------------------

    @Test
    @Order(10)
    void auditLogPageRendersTable() {
        navigateToApp("/audit");
        waitForHydration();

        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Audit Log");
        assertThat(page.locator("pl-table").count()).isEqualTo(1);
        assertThat(page.locator("pl-table-head").count()).isEqualTo(4);
    }

    @Test
    @Order(11)
    void auditLogRecordsCreation() throws Exception {
        // Create a site first (within same JVM fork)
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/sites/create"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", "hh_session=" + sessionToken)
            .POST(HttpRequest.BodyPublishers.ofString("name=Audit+Test+Site&site_type=hohenheim%3Adead"))
            .build();

        HttpResponse<String> createResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(createResponse.statusCode()).isEqualTo(302);

        // Now check audit log page shows the creation entry
        navigateToApp("/audit");
        waitForHydration();

        // Use page content since pl-table-body custom element content may not be in textContent
        String content = page.locator(".hh-content").textContent();
        assertThat(content).contains("created");
        assertThat(content).contains("site");
    }

    @Test
    @Order(12)
    void softNavFromDashboardToAudit() {
        navigateToApp("/");
        waitForHydration();

        page.locator(".hh-sidebar__link[href='/audit']").click();
        waitForTitle("Audit Log");

        assertThat(page.url()).endsWith("/audit");
    }

    // -----------------------------------------------------------------------
    // Certificates
    // -----------------------------------------------------------------------

    @Test
    @Order(20)
    void certificateUploadFormRendersPlumageComponents() {
        navigateToApp("/certificates/upload");
        waitForHydration();

        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Upload Certificate");
        assertThat(page.locator("pl-input").count()).isGreaterThanOrEqualTo(1);
        assertThat(page.locator("pl-textarea").count()).isEqualTo(2);
        assertThat(page.locator("pl-button").count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(21)
    void softNavFromCertificatesListToUpload() {
        navigateToApp("/certificates");
        waitForHydration();

        page.locator("a[href='/certificates/upload']").click();
        waitForTitle("Upload Certificate");

        assertThat(page.url()).endsWith("/certificates/upload");
    }
}
