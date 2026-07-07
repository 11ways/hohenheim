package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Settings persistence, audit log, and certificate pages through the
 * zenit-cms admin.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminPagesTest extends HohenheimTestBase {

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

    // -----------------------------------------------------------------------
    // Settings
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    void settingsPageShowsAllGroups() {
        navigateToApp("/admin/settings");
        waitForHydration();

        String content = page.content();
        assertThat(content).contains("Proxy");
        assertThat(content).contains("Logging");
        assertThat(content).contains("Security");
        assertThat(content).contains("Let's Encrypt");
    }

    @Test
    @Order(2)
    void settingsSavePersistsToLocalDry() throws Exception {
        var response = post("/admin/settings",
            "proxy_http_port=8085&proxy_https_port=8443&proxy_fallback=http%3A%2F%2F127.0.0.1%3A9999"
            + "&proxy_force_https=on&log_access_path=%2Ftmp%2Faccess.log"
            + "&sec_log_domain_misses=on&sec_domain_miss_threshold=7"
            + "&ssl_le_email=admin%40example.com");
        assertThat(response.statusCode()).isEqualTo(302);
        // The port differs from the boot value, so the redirect carries the restart hint.
        assertThat(response.headers().firstValue("Location").orElse(""))
            .startsWith("/admin/settings?saved=true");

        // The write-back landed in the (test-redirected) local.dry file.
        Path localDry = Path.of(System.getProperty("hohenheim.local_settings"));
        assertThat(Files.isRegularFile(localDry)).isTrue();
        Map<?, ?> parsed = (Map<?, ?>) Zenit.DRY.parse(Files.readString(localDry));
        Map<?, ?> proxy = (Map<?, ?>) parsed.get("proxy");
        assertThat(((Number) proxy.get("http_port")).intValue()).isEqualTo(8085);
        Map<?, ?> security = (Map<?, ?>) parsed.get("security");
        assertThat(((Number) security.get("domain_miss_threshold")).intValue()).isEqualTo(7);
    }

    @Test
    @Order(3)
    void settingsSaveRejectsInvalidPortWithoutClaimingSuccess() throws Exception {
        var response = post("/admin/settings",
            "proxy_http_port=not-a-port&proxy_https_port=443&sec_domain_miss_threshold=5");

        assertThat(response.statusCode()).isEqualTo(302);
        String location = response.headers().firstValue("Location").orElse("");
        assertThat(location).startsWith("/admin/settings?error=");
        assertThat(location).doesNotContain("saved=true");
    }

    // -----------------------------------------------------------------------
    // Activity log
    // -----------------------------------------------------------------------

    @Test
    @Order(10)
    void activityLogRecordsCreation() throws Exception {
        var createResponse = post("/admin/sites/new",
            "name=Audit+Test+Site&site_type=hohenheim%3Adead&source=local");
        assertThat(createResponse.statusCode()).isIn(200, 302, 303);

        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("Audit Test Site")).first();
        assertThat(site).isNotNull();

        navigateToApp("/admin/activity");
        waitForHydration();

        String content = page.locator("body").textContent();
        assertThat(content).contains("create");
        assertThat(content).contains("hohenheim:site");
    }

    // -----------------------------------------------------------------------
    // Certificates
    // -----------------------------------------------------------------------

    @Test
    @Order(19)
    void certificateRequestFormLoads() {
        navigateToApp("/admin/certificates-request");
        waitForHydration();

        String content = page.content();
        assertThat(content).contains("Let's Encrypt");
        assertThat(content).contains("domains");
    }

    @Test
    @Order(20)
    void certificateUploadValidatesPems() throws Exception {
        post("/admin/certificates/new",
            "nice_name=my-bad-cert&certificate_pem=NOT-A-PEM-BODY&private_key_pem=NOT-A-KEY");

        Row cert = Models.get(CertificateModel.class).find()
            .where(CertificateModel.NICE_NAME.eq("my-bad-cert")).first();
        assertThat(cert)
            .as("an invalid PEM must not be stored")
            .isNull();
    }

    @Test
    @Order(21)
    void certificatesListLinksToRequestPage() {
        navigateToApp("/admin/certificates");
        waitForHydration();

        assertThat(page.locator("a[href='/admin/certificates-request']").count())
            .isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(22)
    void processesTabRendersForASite() throws Exception {
        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("Audit Test Site")).first();
        assertThat(site).isNotNull();

        navigateToApp("/admin/sites/" + site.get(SiteModel.ID) + "/page/processes");
        waitForHydration();

        String content = page.locator("body").textContent();
        assertThat(content).contains("Stored process logs");
    }

    @Test
    @Order(23)
    void domainsTabRendersForASite() throws Exception {
        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("Audit Test Site")).first();
        assertThat(site).isNotNull();

        navigateToApp("/admin/sites/" + site.get(SiteModel.ID) + "/page/domains");
        waitForHydration();

        String content = page.locator("body").textContent();
        assertThat(content).contains("No domains configured");
    }
}
