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
import java.util.List;
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

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // -----------------------------------------------------------------------
    // Settings
    // -----------------------------------------------------------------------

    /** One settings page load: render, save, reset, path browser and a rejected raw POST. */
    @Test
    @Order(1)
    void settingsPageRendersSavesResetsAndRefusesInvalidValues() throws Exception {
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
        assertThat(page.locator(
            "[data-path='app.ssl.dns_propagation_seconds'] pl-input-group-addon").innerText().trim())
            .isEqualTo("s");
        assertThat(page.locator(
            "[data-path='app.storage.data_path'] zf-path-input [data-zf-path-browse]").count()).isEqualTo(1);
        for (String path : new String[] {
            "framework.network.request_body_size_limit",
            "framework.network.request_individual_file_size_limit",
            "framework.network.request_total_file_size_limit",
            "framework.network.request_body_inflight_limit",
            "framework.compression.min_size_bytes"
        }) {
            assertThat(page.locator("[data-path='" + path + "'] pl-input-group-addon").innerText().trim())
                .isEqualTo("B");
        }
        assertThat(page.locator(
            ".cms-setting:has([data-path='app.auth_proteus.enabled']) .cms-setting-note-restart").count()).isEqualTo(1);
        assertThat(page.locator(
            ".cms-setting:has([data-path='app.auth_proteus.authenticator']) .cms-setting-note-restart").count()).isEqualTo(1);

        var fallback = page.locator("[data-path='app.proxy.fallback_address'] input");
        fallback.fill("http://127.0.0.1:9999");
        var threshold = page.locator("[data-path='app.security.domain_miss_threshold'] input");
        threshold.fill("7");
        String neverBan = "zf-array pl-field[data-path='app.security.never_ban']";
        page.click(neverBan + " [data-array-add]");
        waitForReactiveIdle();
        page.locator(neverBan + " .zf-array-row:nth-child(1) input").fill("203.0.113.7");
        page.click(neverBan + " [data-array-add]");
        waitForReactiveIdle();
        page.locator(neverBan + " .zf-array-row:nth-child(2) input").fill("198.51.100.0/24");
        page.click(neverBan + " [data-array-add]");
        waitForReactiveIdle();
        page.locator(neverBan + " .zf-array-row:nth-child(3) input").fill("remove.example");
        // The row controls are use:List.moveUp/moveDown/remove now, so the boundary
        // disabled guard and the default aria-label are the DIRECTIVE's -- assert them
        // here rather than only swapping the marker selector.
        assertThat(page.locator(neverBan + " .zf-array-row:nth-child(1) [data-list-move-up][disabled]")
            .count()).as("the first row cannot move up").isEqualTo(1);
        assertThat(page.locator(neverBan + " .zf-array-row:nth-child(3) [data-list-move-down][disabled]")
            .count()).as("the last row cannot move down").isEqualTo(1);
        assertThat(page.locator(neverBan + " .zf-array-row:nth-child(3) [data-list-remove]")
            .getAttribute("aria-label")).as("the remove control is still labelled").isEqualTo("Remove");
        page.click(neverBan + " .zf-array-row:nth-child(3) [data-list-remove]");
        waitForReactiveIdle();
        page.click(neverBan + " .zf-array-row:nth-child(1) [data-list-move-down]");
        waitForReactiveIdle();

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
        assertThat(security.get("never_ban"))
            .isEqualTo(List.of("198.51.100.0/24", "203.0.113.7"));

        // The live context applied the change without a restart.
        assertThat(HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Security.DOMAIN_MISS_THRESHOLD)).isEqualTo(7);
        assertThat(HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.NEVER_BAN))
            .isEqualTo(List.of("198.51.100.0/24", "203.0.113.7"));

        // Settings edits are accountable: the touched keys land in the activity log.
        Row entry = Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq("zenit:settings"))
            .orderBy(ActivityModel.ID, SortOrder.DESC)
            .first();
        assertThat(entry).isNotNull();
        assertThat(entry.get(ActivityModel.DETAIL)).contains("security.domain_miss_threshold");

        // Reset ARMS a clear that applies on save; it no longer empties the editor
        // client-side (the settings page's inline script, which clicked every remove
        // button, is gone). The write-back below is what proves the clear applied.
        String resetControl =
            ".cms-setting:has([data-path='app.security.never_ban']) [data-cms-setting-reset]";
        page.click(resetControl + " pl-checkbox button");
        waitForReactiveIdle();
        assertThat(page.locator(resetControl + " pl-checkbox button").getAttribute("aria-checked"))
            .as("the reset control is armed").isEqualTo("true");
        assertThat(page.locator(neverBan + " .zf-array-row").count())
            .as("an armed reset leaves the editor untouched until save").isEqualTo(2);
        page.click(".cms-settings-actions pl-button");
        page.waitForCondition(() -> HohenheimSettings.VALUES
            .getValue(HohenheimSettings.Security.NEVER_BAN).isEmpty());
        parsed = (Map<?, ?>) Zenit.DRY.parse(Files.readString(settingsDry));
        security = (Map<?, ?>) parsed.get("security");
        assertThat(security.containsKey("never_ban")).isFalse();

        // The save above PRG-reloads the page: the browse click below lands on a
        // dead listener unless the reloaded page has finished hydrating first. Settle
        // on the RELOADED document before waiting for hydration -- the cleared never_ban
        // editor only exists after the reload, whereas the still-hydrated pre-reload page
        // satisfies waitForHydration() on its own, before the navigation even starts.
        page.waitForCondition(() -> page.locator(neverBan + " .zf-array-row").count() == 0);
        waitForHydration();

        // The filesystem-path browser picks a server directory; the pick is
        // deliberately left unsaved (it must never reach the settings file).
        var pathField = page.locator("[data-path='app.storage.data_path']");
        pathField.locator("[data-zf-path-browse]").click();
        var dialog = page.locator("he-bottom .pl-dialog-modal[data-open]");
        dialog.waitFor();
        dialog.locator("pl-command-item div[role='option']").first().click();
        dialog.locator("[data-zf-path-choose-directory]").click();
        assertThat(pathField.locator("input").inputValue()).isEqualTo("/");

        // A number input sanitizes garbage client-side, so exercise the server
        // rejection with a raw POST: an uncoercible port must rerender with a
        // violation instead of persisting anything.
        Integer before = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTP_PORT);
        var response = post("/admin/settings",
            "app.proxy.http_port=not-a-port&app.proxy.http_port__base=" + before);

        // Validation failure rerenders the page (no PRG redirect).
        assertThat(response.statusCode()).isEqualTo(200);

        String raw = Files.exists(settingsDry) ? Files.readString(settingsDry) : "";
        assertThat(raw).doesNotContain("not-a-port");
        assertThat(HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTP_PORT))
            .isEqualTo(before);
    }

    // -----------------------------------------------------------------------
    // Activity log
    // -----------------------------------------------------------------------

    /** Creating a record shows up in the activity list, the dashboard feed and the activity detail. */
    @Test
    @Order(10)
    void activityLogDashboardFeedAndActivityDetailReflectACreation() throws Exception {
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

        var firstEntry = page.locator("a.widget-record-entry[href^='/admin/activity/']").first();
        // Verb icon leads the row, a relative timestamp trails it.
        assertThat(firstEntry.locator(".widget-record-icon pl-icon").count()).isEqualTo(1);
        assertThat(firstEntry.locator("pl-relative-time.widget-record-time").count()).isEqualTo(1);
        assertThat(firstEntry.locator("pl-relative-time.widget-record-time").innerText().trim())
            .isNotEmpty();

        // The title is the LOCALIZED verb plus the captured record title
        // ("Created · <name>"), never the raw token; the model token is
        // humanized in the subtitle.
        String titles = page.locator(".widget-record-title").allInnerTexts().toString();
        assertThat(titles).contains("Created");
        assertThat(titles).contains("·");
        assertThat(titles).doesNotContain("hohenheim:site");
        String subtitles = page.locator(".widget-record-subtitle").allInnerTexts().toString();
        assertThat(subtitles).doesNotContain("hohenheim:site");

        // The ANCHOR carries the row padding, so the whole item is clickable.
        var padding = firstEntry.evaluate("el => getComputedStyle(el).paddingLeft");
        assertThat(String.valueOf(padding)).isNotEqualTo("0px");

        Row activity = Models.get(ActivityModel.class).find()
            .orderBy(ActivityModel.ID, SortOrder.DESC)
            .first();
        assertThat(activity).isNotNull();

        navigateToApp("/admin/activity/" + activity.get(ActivityModel.ID));
        waitForHydration();

        var heading = page.locator(".cms-activity-detail-heading");
        assertThat(heading.locator(":scope > h1").count()).isEqualTo(1);
        assertThat(heading.locator(":scope > h1 + pl-badge[data-activity-verb]").count()).isEqualTo(1);
        assertThat(heading.locator("pl-badge[data-activity-verb] > pl-icon[data-activity-icon]").count())
            .isEqualTo(1);
        assertThat(heading.locator(":scope > pl-icon[data-activity-icon]").count()).isZero();
    }

    // -----------------------------------------------------------------------
    // Certificates
    // -----------------------------------------------------------------------

    /** The request and upload forms render their fields and refuse impossible input. */
    @Test
    @Order(19)
    void certificateRequestAndUploadFormsRenderAndValidate() throws Exception {
        navigateToApp("/admin/certificates-request");
        waitForHydration();

        String content = page.content();
        assertThat(content).contains("Let's Encrypt");
        assertThat(content).contains("DNS-01");
        assertThat(content).contains("*.example.com");
        assertThat(page.locator("pl-textarea[name='domains']").count()).isZero();
        assertThat(page.locator("zf-array pl-input[name='domains']").count()).isEqualTo(1);

        // Hand-built form pages get their inter-field rhythm from the plumage
        // card itself (pl-card-content > pl-field + pl-field); a regression
        // here squishes every label against the previous description.
        Object gap = page.evaluate(
            "() => getComputedStyle(document.querySelector('pl-card-content .zf-entries')).gap");
        assertThat(String.valueOf(gap)).isEqualTo("32px");

        // Item children portal into the overlay popup at hydration, so the
        // command option's disabled state is asserted inside the open popup.
        page.click("pl-select[name='dns_mode'] .pl-select-field");
        page.waitForSelector("he-bottom .pl-select-popup[data-open]");
        var command = page.locator(
            "he-bottom .pl-select-popup[data-open] div[role='option'][data-value='command']");
        assertThat(command.count()).isEqualTo(1);
        assertThat(command.getAttribute("aria-disabled")).isEqualTo("true");
        page.keyboard().press("Escape");

        // A wildcard with HTTP validation is refused before the CA is contacted.
        var response = post("/admin/certificates-request",
            "nice_name=wildcard&domains=*.example.test&challenge_type=http&dns_mode=manual");
        assertThat(response.statusCode()).isIn(302, 303);
        assertThat(response.headers().firstValue("Location").orElse(""))
            .contains("Wildcard").contains("DNS-01");

        // Every repeated domain value is kept, so the wildcard is still seen.
        response = post("/admin/certificates-request",
            "nice_name=wildcard&domains=&domains=example.test&domains=*.example.test"
                + "&challenge_type=http&dns_mode=manual");
        assertThat(response.statusCode()).isIn(302, 303);
        assertThat(response.headers().firstValue("Location").orElse(""))
            .contains("Wildcard").contains("DNS-01");

        post("/admin/certificates/new",
            "nice_name=my-bad-cert&certificate_pem=NOT-A-PEM-BODY&private_key_pem=NOT-A-KEY");
        Row cert = Models.get(CertificateModel.class).find()
            .where(CertificateModel.NICE_NAME.eq("my-bad-cert")).first();
        assertThat(cert)
            .as("an invalid PEM must not be stored")
            .isNull();

        // PEM blobs are multi-line: both fields must render as textareas, the
        // private key as a masked secret one.
        navigateToApp("/admin/certificates/new");
        waitForHydration();

        assertThat(page.locator("pl-textarea[name='certificate_pem']").count()).isEqualTo(1);
        assertThat(page.locator("pl-textarea[name='private_key_pem']").count()).isEqualTo(1);

        content = page.content();
        assertThat(content).contains("Certificate (PEM)");
        assertThat(content).contains("Private key (PEM)");
        assertThat(content).contains("intermediate chain");
        assertThat(content).doesNotContain("Renewal status");
        assertThat(page.locator("[data-path='challenge_type'], [data-path='dns_publisher']").count()).isZero();
        assertThat(page.locator("[data-path='auto_renew'], [data-group='renewal']").count()).isZero();
    }

    /** A failed renewal is diagnosable from the list, the detail page and the dashboard. */
    @Test
    @Order(21)
    void certificateListDetailAndDashboardSurfaceRenewalFailures() throws Exception {
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
            assertThat(page.locator("a[href='/admin/certificates-request']").count())
                .isGreaterThanOrEqualTo(1);
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

        // With the failure gone, its attention item disappears even if another subsystem still needs attention.
        navigateToApp("/admin/dashboard");
        waitForHydration();
        assertThat(page.locator(".hh-attention-item > a.hh-attention-target[href='/admin/certificates/"
            + cert.get(CertificateModel.ID) + "']").count()).isZero();
    }

    // -----------------------------------------------------------------------
    // Site record pages
    // -----------------------------------------------------------------------

    /** Site detail fields, the toggle action label, the conditional processes tab and the domains tab. */
    @Test
    @Order(23)
    void siteRecordPagesRenderFieldsActionsTabsAndDomains() throws Exception {
        var siteModel = Models.get(SiteModel.class);
        Row suffixSite = siteModel.createEmptyRow();
        suffixSite.set(SiteModel.NAME, "Suffix Site");
        suffixSite.set(SiteModel.SLUG, "suffix-site");
        suffixSite.set(SiteModel.SITE_TYPE, "hohenheim:static");
        suffixSite.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        suffixSite.set(SiteModel.SOURCE, "local");
        suffixSite.set(SiteModel.STATUS, "active");
        suffixSite.set(SiteModel.ENABLED, true);
        siteModel.save(suffixSite);

        Row toggleSite = siteModel.createEmptyRow();
        toggleSite.set(SiteModel.NAME, "Toggle Label Site");
        toggleSite.set(SiteModel.SLUG, "toggle-label-site");
        toggleSite.set(SiteModel.SITE_TYPE, "hohenheim:static");
        toggleSite.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        toggleSite.set(SiteModel.SOURCE, "local");
        toggleSite.set(SiteModel.STATUS, "active");
        toggleSite.set(SiteModel.ENABLED, true);
        siteModel.save(toggleSite);

        try {
            navigateToApp("/admin/sites/" + suffixSite.get(SiteModel.ID));
            waitForHydration();

            var field = page.locator("pl-field[data-path='settings.delay']");
            assertThat(field.locator("pl-input-group input[type='number']").count()).isEqualTo(1);
            assertThat(field.locator("pl-input-group pl-input-group-addon").innerText().trim())
                .isEqualTo("ms");
            assertThat(page.locator(
                "pl-field[data-path='settings.root_path'] zf-path-input [data-zf-path-browse]").count()).isEqualTo(1);

            // Enabled record: the toolbar action reads "Disable", never "Enable/disable".
            Object toggleId = toggleSite.get(SiteModel.ID);
            navigateToApp("/admin/sites/" + toggleId);
            waitForHydration();
            var toggleButton = page.locator(
                ".cms-record-toolbar pl-button[data-action-id='hohenheim:toggle_site']");
            assertThat(toggleButton.count()).isEqualTo(1);
            assertThat(toggleButton.innerText().trim()).isEqualTo("Disable");

            toggleSite.set(SiteModel.ENABLED, false);
            siteModel.save(toggleSite);

            navigateToApp("/admin/sites/" + toggleId);
            waitForHydration();
            assertThat(page.locator(
                ".cms-record-toolbar pl-button[data-action-id='hohenheim:toggle_site']").innerText().trim())
                .isEqualTo("Enable");
        } finally {
            siteModel.delete(suffixSite);
            siteModel.delete(toggleSite);
        }

        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("Audit Test Site")).first();
        assertThat(site).isNotNull();
        Integer siteId = site.get(SiteModel.ID);

        // The processes tab only exists for managed-process site types.
        assertThat(get("/admin/sites/" + siteId).body())
            .doesNotContain("/admin/sites/" + siteId + "/page/processes");
        assertThat(get("/admin/sites/" + siteId + "/page/processes").statusCode()).isEqualTo(404);

        Row managed = siteModel.createEmptyRow();
        managed.set(SiteModel.NAME, "Managed Processes Site");
        managed.set(SiteModel.SLUG, "managed-processes-site");
        managed.set(SiteModel.SITE_TYPE, "hohenheim:command");
        managed.set(SiteModel.SETTINGS, Map.of("start_command", "true"));
        managed.set(SiteModel.SOURCE, "local");
        managed.set(SiteModel.STATUS, "active");
        managed.set(SiteModel.ENABLED, true);
        siteModel.save(managed);

        try {
            Integer managedId = managed.get(SiteModel.ID);
            assertThat(get("/admin/sites/" + managedId).body())
                .contains("/admin/sites/" + managedId + "/page/processes");
            HttpResponse<String> processes = get("/admin/sites/" + managedId + "/page/processes");
            assertThat(processes.statusCode()).isEqualTo(200);
            assertThat(processes.body()).contains("Stored process logs");
        } finally {
            siteModel.delete(managed);
        }

        // The domains tab renders its empty state before any domain exists.
        navigateToApp("/admin/sites/" + siteId + "/page/domains");
        waitForHydration();
        assertThat(page.locator("body").textContent()).contains("No domains configured");

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
            var domainInputs = page.locator("zf-array pl-input[name='domains'] input");
            assertThat(domainInputs.count()).isEqualTo(2);
            assertThat(domainInputs.nth(0).inputValue()).isEqualTo("weave.example.test");
            assertThat(domainInputs.nth(1).inputValue()).isEqualTo("bare.example.test");
        } finally {
            certModel.delete(cert);
            domainModel.delete(covered);
            domainModel.delete(bare);
        }

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
}
