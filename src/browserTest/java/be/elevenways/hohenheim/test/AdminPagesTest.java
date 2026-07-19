package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.server.http.RateLimitMiddleware;
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

        navigateToApp("/admin/dashboard");
        waitForHydration();
        assertThat(page.locator(".hh-dashboard-band").count()).isGreaterThanOrEqualTo(3);
        assertThat(page.locator("a.widget-stat-link[href='/admin/sites']").count()).isEqualTo(1);
        assertThat(page.locator("a.widget-record-entry[href^='/admin/activity/']").count())
            .isGreaterThanOrEqualTo(1);
        assertThat(page.locator(".widget-records dl.widget-record").count()).isZero();
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
        assertThat(content).contains("DNS-01");
        assertThat(content).contains("*.example.com");

        // Item children portal into the overlay popup at hydration, so the
        // command option's disabled state is asserted inside the open popup.
        page.click("pl-select[name='dns_mode'] .pl-select-field");
        page.waitForSelector("he-bottom .pl-select-popup[data-open]");
        var command = page.locator(
            "he-bottom .pl-select-popup[data-open] div[role='option'][data-value='command']");
        assertThat(command.count()).isEqualTo(1);
        assertThat(command.getAttribute("aria-disabled")).isEqualTo("true");
        page.keyboard().press("Escape");
    }

    @Test
    @Order(19)
    void wildcardRequestRefusesHttpValidationBeforeContactingTheCa() throws Exception {
        var response = post("/admin/certificates-request",
            "nice_name=wildcard&domains=*.example.test&challenge_type=http&dns_mode=manual");

        assertThat(response.statusCode()).isIn(302, 303);
        assertThat(response.headers().firstValue("Location").orElse(""))
            .contains("Wildcard").contains("DNS-01");
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
    @Order(21)
    void renewalErrorsAreVisibleInListAndDetail() throws Exception {
        // A cert whose last renewal failed: the diagnosis must be readable.
        var certModel = Models.get(CertificateModel.class);
        Row cert = certModel.createEmptyRow();
        cert.set(CertificateModel.NICE_NAME, "failing-renewal-cert");
        cert.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
        cert.set(CertificateModel.STATUS, CertificateModel.STATUS_ERROR);
        cert.set(CertificateModel.DOMAIN_NAMES_TEXT, "broken.example.test");
        cert.set(CertificateModel.RENEWAL_ERROR, "DNS problem: NXDOMAIN looking up A for broken.example.test");
        cert.set(CertificateModel.ERROR_COUNT, 3);
        certModel.save(cert);

        try {
            navigateToApp("/admin/certificates");
            waitForHydration();
            assertThat(page.content())
                .as("the renewal error is a visible list column")
                .contains("DNS problem: NXDOMAIN");

            navigateToApp("/admin/certificates/" + cert.get(CertificateModel.ID));
            waitForHydration();
            String detail = page.content();
            assertThat(detail).contains("Renewal status");
            assertThat(detail).contains("DNS problem: NXDOMAIN");
            // Diagnostics are read-only: no editable input carries the field.
            assertThat(page.locator("input[name='renewal_error'], textarea[name='renewal_error']").count())
                .isZero();

            // The dashboard attention panel lists the failing cert with a link.
            navigateToApp("/admin/dashboard");
            waitForHydration();
            var item = page.locator(".hh-attention-item[data-severity='error']");
            assertThat(item.count()).isGreaterThanOrEqualTo(1);
            assertThat(page.locator(".hh-attention-item > a.hh-attention-target[href='/admin/certificates/"
                + cert.get(CertificateModel.ID) + "']").count()).isEqualTo(1);
        } finally {
            certModel.delete(cert);
        }

        // With the failure gone, the panel reports all clear.
        navigateToApp("/admin/dashboard");
        waitForHydration();
        assertThat(page.locator(".hh-attention-clear").count()).isEqualTo(1);
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

    @Test
    @Order(24)
    void expensiveEndpointsAreRateLimited() throws Exception {
        // The base installs a disable-all resolver (suite-wide buckets would
        // trip across classes); unset it so the DECLARED policies apply.
        RateLimitMiddleware.setPolicyResolver(null);
        try {
            HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build();
            boolean limited = false;
            for (int i = 0; i < 40 && !limited; i++) {
                var response = client.send(HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + "/certificates/999999/download"))
                    .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
                    .build(), HttpResponse.BodyHandlers.ofString());
                limited = response.statusCode() == 429;
            }
            assertThat(limited)
                .as("the declared download policy (30/min) must answer 429 under a hammer")
                .isTrue();
        } finally {
            RateLimitMiddleware.setPolicyResolver((conduit, endpoint, declared) -> null);
        }
    }

    @Test
    @Order(25)
    void domainsWeaveWithCertificates() throws Exception {
        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("Audit Test Site")).first();
        Integer siteId = site.get(SiteModel.ID);

        var domainModel = Models.get(SiteDomainModel.class);
        Row covered = domainModel.createEmptyRow();
        covered.set(SiteDomainModel.SITE_ID, siteId);
        covered.set(SiteDomainModel.HOSTNAME, "weave.example.test");
        covered.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domainModel.save(covered);
        Row bare = domainModel.createEmptyRow();
        bare.set(SiteDomainModel.SITE_ID, siteId);
        bare.set(SiteDomainModel.HOSTNAME, "bare.example.test");
        bare.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domainModel.save(bare);

        var certModel = Models.get(CertificateModel.class);
        Row cert = certModel.createEmptyRow();
        cert.set(CertificateModel.NICE_NAME, "weave-cert");
        cert.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
        cert.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        cert.set(CertificateModel.DOMAIN_NAMES_TEXT, "weave.example.test,www.weave.example.test");
        certModel.save(cert);

        try {
            // Coverage column: the covered hostname links its cert, the bare one shows None.
            navigateToApp("/admin/sites/" + siteId + "/page/domains");
            waitForHydration();
            assertThat(page.locator("[data-cert-status='active'] a[href='/admin/certificates/"
                + cert.get(CertificateModel.ID) + "']").count()).isEqualTo(1);
            assertThat(page.locator("[data-cert-status='none']").count()).isEqualTo(1);

            // The add-domain link preselects this site on the CREATE form.
            assertThat(page.locator("#add-domain-link").getAttribute("href"))
                .isEqualTo("/admin/domains/new?site_id=" + siteId);
            navigateToApp("/admin/domains/new?site_id=" + siteId);
            waitForHydration();
            // The pick's value is a Java-side property; the SSR-resolved display
            // title in the field is the observable prefill.
            assertThat(page.locator("pl-select[name='site_id'] .pl-select-value").textContent().trim())
                .isEqualTo("Audit Test Site");

            // The request-certificate link prefills the site's exact hostnames.
            navigateToApp("/admin/certificates-request?site=" + siteId);
            waitForHydration();
            String domainsValue = page.locator("#domains").inputValue();
            assertThat(domainsValue).contains("weave.example.test").contains("bare.example.test");
        } finally {
            certModel.delete(cert);
            domainModel.delete(covered);
            domainModel.delete(bare);
        }
    }
}
