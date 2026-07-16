package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
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
        // Every hohenheim group renders (the old hand-rolled page only covered four).
        assertThat(content).contains("Proxy");
        assertThat(content).contains("Logging");
        assertThat(content).contains("Security");
        assertThat(content).contains("SSL / TLS");
        assertThat(content).contains("Storage");
        assertThat(content).contains("Proteus SSO");
        // Secret settings never render their value; the input is a masked password field.
        assertThat(page.locator(
            "[data-path='app.auth_proteus.access_key'] input[type='password']").count()).isEqualTo(1);
    }

    @Test
    @Order(2)
    void settingsSavePersistsToTheHohenheimDryFile() throws Exception {
        navigateToApp("/admin/settings");
        waitForHydration();

        var fallback = page.locator("[data-path='app.proxy.fallback_address'] input");
        fallback.fill("http://127.0.0.1:9999");
        var threshold = page.locator("[data-path='app.security.domain_miss_threshold'] input");
        threshold.fill("7");

        page.click(".cms-settings-actions pl-button");
        page.waitForCondition(() -> page.locator("pl-toast").count() > 0);

        // The DIFF-based write-back landed in the (test-redirected) settings file
        // with keys RELATIVE to the hohenheim group.
        Path settingsDry = Path.of(System.getProperty("hohenheim.settings"));
        assertThat(Files.isRegularFile(settingsDry)).isTrue();
        Map<?, ?> parsed = (Map<?, ?>) Zenit.DRY.parse(Files.readString(settingsDry));
        Map<?, ?> proxy = (Map<?, ?>) parsed.get("proxy");
        assertThat(String.valueOf(proxy.get("fallback_address"))).isEqualTo("http://127.0.0.1:9999");
        Map<?, ?> security = (Map<?, ?>) parsed.get("security");
        assertThat(((Number) security.get("domain_miss_threshold")).intValue()).isEqualTo(7);

        // The live context applied the change without a restart.
        assertThat(HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Security.DOMAIN_MISS_THRESHOLD)).isEqualTo(7);

        // Settings edits are accountable: the touched keys land in the activity log.
        Row entry = Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq("zenit:settings"))
            .orderBy(ActivityModel.ID, SortOrder.DESC)
            .first();
        assertThat(entry).isNotNull();
        assertThat(entry.get(ActivityModel.DETAIL)).contains("security.domain_miss_threshold");
    }

    @Test
    @Order(3)
    void settingsSaveRejectsAnInvalidValueWithoutHalfSaving() throws Exception {
        // A number input sanitizes garbage client-side, so exercise the server
        // rejection with a raw POST: an uncoercible port must rerender with a
        // violation instead of persisting anything.
        Integer before = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTP_PORT);
        var response = post("/admin/settings",
            "app.proxy.http_port=not-a-port&app.proxy.http_port__base=" + before);

        // Validation failure rerenders the page (no PRG redirect).
        assertThat(response.statusCode()).isEqualTo(200);

        Path settingsDry = Path.of(System.getProperty("hohenheim.settings"));
        String raw = Files.exists(settingsDry) ? Files.readString(settingsDry) : "";
        assertThat(raw).doesNotContain("not-a-port");
        assertThat(HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTP_PORT))
            .isEqualTo(before);
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
