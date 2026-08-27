package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.server.cms.AdminActivityResource;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.microcopy.server.DefaultCatalogLoader;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.server.http.RateLimitMiddleware;
import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Settings persistence, audit log, and certificate pages through the
 * zenit-cms admin.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminPagesTest extends HohenheimTestBase {

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
        // Secret settings never render their value: a STORED secret renders plumage's
        // masked "secret" register, never the password control (which would opt the whole
        // settings form into the browser's password manager).
        assertThat(page.locator(
            "[data-path='app.auth_proteus.access_key'] pl-input[type='secret']").count())
            .as("a stored secret renders the masked register").isEqualTo(1);
        assertThat(page.locator(
            "[data-path='app.auth_proteus.access_key'] input[type='password']").count())
            .as("and never the credential control").isEqualTo(0);
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
        //
        // AIDEV-NOTE: this source declares ONE root, "/", and a single-root browser opens
        // INSIDE it -- there is no root-list step to click through any more (zenit-forms
        // 7da018e replaced it with the listing's own multi-root switcher). So the first
        // option is a CHILD of "/", and what this pins is that the option navigated into
        // is the directory the footer then chooses: a picker that chose something other
        // than where the operator is standing is the defect worth catching.
        var pathField = page.locator("[data-path='app.storage.data_path']");
        pathField.locator("[data-zf-path-browse]").click();
        var dialog = page.locator("he-bottom .pl-dialog-modal[data-open]");
        dialog.waitFor();
        page.waitForCondition(() -> "/".equals(currentDirectory(dialog)));
        assertThat(currentDirectory(dialog))
            .as("a single-root browser opens inside its declared root")
            .isEqualTo("/");
        var firstEntry = dialog.locator("pl-command-item").first();
        // Directories sort first, so the first row is a child DIRECTORY of "/". Its path
        // is read from the rendered name rather than the element's value, which
        // pl-command-item carries as a property and never reflects as an attribute.
        String entryName = firstEntry.locator(".zf-path-entry-name").textContent().trim();
        assertThat(entryName)
            .as("the root listing offers a real child to descend into")
            .isNotEmpty().doesNotContain("/");
        String entryPath = "/" + entryName;
        firstEntry.locator("div[role='option']").click();
        page.waitForCondition(() -> entryPath.equals(currentDirectory(dialog)));
        dialog.locator("[data-zf-path-choose-directory]").click();
        assertThat(pathField.locator("input").inputValue())
            .as("the chosen path is the directory the browser was standing in")
            .isEqualTo(entryPath);

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

    /** The directory the open path browser is standing in, or null before it has loaded. */
    private static String currentDirectory(Locator dialog) {
        String text = dialog.locator(".zf-path-current").textContent();
        return text == null ? null : text.trim();
    }

    // -----------------------------------------------------------------------
    // Activity log
    // -----------------------------------------------------------------------

    /** Creating a record shows up in the activity list, the dashboard feed and the activity detail. */
    @Test
    @Order(10)
    void activityLogDashboardFeedAndActivityDetailReflectACreation() throws Exception {
        var createResponse = post("/admin/sites/new",
            "name=Audit+Test+Site&upstream_kind=hohenheim%3Astatic");
        assertThat(createResponse.statusCode()).isIn(200, 302, 303);

        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("Audit Test Site")).first();
        assertThat(site).isNotNull();

        // AIDEV-NOTE: read over HTTP, not through a hydrated page load. The activity LIST is
        // server-rendered and nothing here asserts a client re-render, so the browser round
        // trip bought only latency (~1.6s). What the list proves for hohenheim is that the
        // zenit-cms ActivityResource is MOUNTED here; the row's content is asserted straight
        // off the model below, which is stronger than a substring of the whole body.
        assertThat(get("/admin/activity").body())
            .as("the activity resource is mounted in the hohenheim panel")
            .contains("hohenheim:site");
        Row logged = Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq("hohenheim:site"))
            .orderBy(ActivityModel.ID, SortOrder.DESC)
            .first();
        assertThat(logged).as("the site creation was logged").isNotNull();
        assertThat((String) logged.get(ActivityModel.ACTION)).isEqualTo("create");

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
        var wildcardRefusal = popFlash();
        assertThat(wildcardRefusal).describedAs("the refusal rides the session flash").isNotNull();
        assertThat(wildcardRefusal.message().key()).isEqualTo("wildcard_requires_dns");

        // Every repeated domain value is kept, so the wildcard is still seen.
        response = post("/admin/certificates-request",
            "nice_name=wildcard&domains=&domains=example.test&domains=*.example.test"
                + "&challenge_type=http&dns_mode=manual");
        assertThat(response.statusCode()).isIn(302, 303);
        var repeatedRefusal = popFlash();
        assertThat(repeatedRefusal).describedAs("the refusal rides the session flash").isNotNull();
        assertThat(repeatedRefusal.message().key()).isEqualTo("wildcard_requires_dns");

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

    /** The rendered text of one read-only form entry, by its form path. */
    private String readonlyEntry(String path) {
        return page.locator("pl-field[data-path='" + path + "'] .zf-field-readonly")
            .first().innerText().trim();
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
            // AIDEV-NOTE: the LIST is read over HTTP -- both assertions are on server-rendered
            // markup (a header-action anchor and a list column), so a hydrated load only added
            // latency. The DETAIL page below stays hydrated on purpose: it is the one place
            // this method proves the client render does not turn the diagnostic into an input.
            String list = get("/admin/certificates").body();
            assertThat(list).contains("/admin/certificates-request");
            assertThat(list)
                .as("the renewal error is a visible list column")
                .contains("DNS problem: NXDOMAIN");

            // 1. The detail page states what the certificate covers, when it expires and why
            //    the last renewal failed -- all three used to live on the LIST only, and the
            //    renewal panel rendered three labels above empty boxes.
            navigateToApp("/admin/certificates/" + cert.get(CertificateModel.ID));
            waitForHydration();
            String detail = page.content();
            assertThat(detail).as("the renewal group still has its heading").contains("Renewal status");
            assertThat(detail).as("the coverage group is on the detail page").contains("Coverage");
            assertThat(detail).as("the covered host names are on the detail page")
                .contains("broken.example.test");
            assertThat(detail).contains("DNS problem: NXDOMAIN");
            // 2. Every absent renewal value SAYS it is absent instead of rendering a labelled
            //    empty box; the DNS publisher label is no longer an orphan.
            assertThat(readonlyEntry("expiry_display"))
                .as("an unissued certificate says so where its expiry goes")
                .isEqualTo("None - not issued yet");
            assertThat(readonlyEntry("next_attempt_display"))
                .as("no scheduled retry reads as such").isEqualTo("Not scheduled");
            assertThat(readonlyEntry("dns_publisher_display"))
                .as("the DNS publisher label is no longer an orphan").isEqualTo("None");
            assertThat(readonlyEntry("covered_names_display"))
                .as("the covered names are the certificate's own SAN list")
                .isEqualTo("broken.example.test");
            // 3. Diagnostics are read-only: no editable input carries the field.
            assertThat(page.locator("input[name='renewal_error'], textarea[name='renewal_error']").count())
                .isZero();
            assertThat(page.locator("input[name='expiry_display'], input[name='covered_names_display']")
                .count())
                .as("the display entries are never inputs")
                .isZero();

            // 4. Give it an expiry and a publisher: the same entries now read as an absolute
            //    stamp plus the relative wording, and the enum reads as its LABEL.
            Instant expiry = Instant.now().plus(Duration.ofDays(40));
            cert.set(CertificateModel.EXPIRES_ON, expiry);
            cert.set(CertificateModel.CHALLENGE_TYPE, CertificateModel.CHALLENGE_DNS);
            cert.set(CertificateModel.DNS_PUBLISHER, CertificateModel.DNS_PUBLISHER_INTERNAL);
            cert.set(CertificateModel.NEXT_ATTEMPT_AT, Instant.now().plus(Duration.ofHours(6)));
            certModel.save(cert);

            navigateToApp("/admin/certificates/" + cert.get(CertificateModel.ID));
            waitForHydration();
            String dated = page.content();
            // The stamp is rendered in the VIEWER's zone, so the assertion is on the SHAPE
            // (absolute wall-clock plus the relative wording), never on a zone-dependent day.
            assertThat(readonlyEntry("expiry_display"))
                .as("the expiry reads as an absolute stamp plus the relative wording")
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} \\(.+ from now\\)");
            assertThat(readonlyEntry("next_attempt_display"))
                .as("a scheduled retry replaces the absence sentence")
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} \\(.+ from now\\)");
            assertThat(dated).as("the next attempt is no longer 'Not scheduled'")
                .doesNotContain("Not scheduled");
            assertThat(readonlyEntry("dns_publisher_display"))
                .as("the DNS publisher reads as its declared label").isEqualTo("Internal DNS");

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

        // With the failure gone, its attention item disappears even if another subsystem still
        // needs attention. Asserted on the PROJECTION rather than through a second dashboard
        // load: the render half is already proven above, and AttentionCollector documents this
        // as the way to test a collector's negative case (see its note on the instance
        // collectors, and InstanceAttentionTest, which does exactly this).
        List<AttentionItem> afterDelete = new ArrayList<>();
        AttentionCollector.errorCertificates(afterDelete);
        String goneUrl = "/admin/certificates/" + cert.get(CertificateModel.ID);
        assertThat(afterDelete.stream()
            .map(raised -> raised.target() == null ? "" : raised.target().toUrl()).toList())
            .as("the deleted certificate raises no attention item")
            .doesNotContain(goneUrl);
    }

    /**
     * The re-issue mode of the request page: a ?cert_id= link turns the create form into the
     * edit form of an existing order, and a row that has no order to repeat is refused.
     */
    @Test
    @Order(22)
    void certificateRequestPageOpensInReissueModeForAnAcmeRow() throws Exception {
        var certModel = Models.get(CertificateModel.class);
        Row cert = certModel.createEmptyRow();
        cert.set(CertificateModel.NICE_NAME, "reissue-me-cert");
        cert.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
        cert.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        cert.set(CertificateModel.DOMAIN_NAMES_TEXT, "alpha.example.test,beta.example.test");
        cert.set(CertificateModel.CHALLENGE_TYPE, CertificateModel.CHALLENGE_DNS);
        cert.set(CertificateModel.DNS_PUBLISHER, CertificateModel.DNS_PUBLISHER_INTERNAL);
        certModel.save(cert);

        Row uploaded = certModel.createEmptyRow();
        uploaded.set(CertificateModel.NICE_NAME, "uploaded-cert");
        uploaded.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
        uploaded.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        certModel.save(uploaded);

        try {
            // 1. The list offers the action for the ACME row, in the overflow menu.
            String list = get("/admin/certificates").body();
            assertThat(list)
                .as("step 1: the re-issue link is rendered for the ACME certificate")
                .contains("/admin/certificates-request?cert_id=" + cert.get(CertificateModel.ID));
            assertThat(list)
                .as("step 1: and never for the manual upload")
                .doesNotContain("/admin/certificates-request?cert_id="
                    + uploaded.get(CertificateModel.ID));

            // 2. The page opens PREFILLED with what the row was last issued for, carrying the
            //    hidden id that makes the POST write back into that row.
            navigateToApp("/admin/certificates-request?cert_id=" + cert.get(CertificateModel.ID));
            waitForHydration();
            assertThat(page.locator("input[name='reissue_cert_id']").getAttribute("value"))
                .as("step 2: the row the submit re-issues is carried in the form")
                .isEqualTo(String.valueOf(cert.get(CertificateModel.ID)));
            var domainInputs = page.locator("zf-array pl-input[name='domains'] input");
            assertThat(domainInputs.count())
                .as("step 2: both stored hostnames are prefilled as rows").isEqualTo(2);
            assertThat(List.of(domainInputs.nth(0).inputValue(), domainInputs.nth(1).inputValue()))
                .as("step 2: carrying the names the row was last issued for")
                .containsExactly("alpha.example.test", "beta.example.test");
            assertThat(page.locator("pl-input[name='nice_name'] input").inputValue())
                .as("step 2: with the certificate's own name").isEqualTo("reissue-me-cert");
            assertThat(page.content())
                .as("step 2: and the page says it is re-issuing, not creating")
                .contains("re-issue");

            // 3. A row with no ACME order to repeat is refused at render, and the form stays
            //    a plain create form rather than half-adopting the row.
            navigateToApp("/admin/certificates-request?cert_id="
                + uploaded.get(CertificateModel.ID));
            waitForHydration();
            assertThat(page.locator("pl-alert[variant='destructive']").count())
                .as("step 3: the refusal is shown").isGreaterThanOrEqualTo(1);
            assertThat(page.locator("input[name='reissue_cert_id']").count())
                .as("step 3: and nothing would be written back to it").isZero();
        } finally {
            certModel.delete(cert);
            certModel.delete(uploaded);
        }
    }

    // -----------------------------------------------------------------------
    // Site record pages
    // -----------------------------------------------------------------------

    /** Site detail fields, the toggle action label, the retired processes tab and the domains tab. */
    @Test
    @Order(23)
    void siteRecordPagesRenderFieldsActionsTabsAndDomains() throws Exception {
        var siteModel = Models.get(SiteModel.class);
        Row suffixSite = siteModel.createEmptyRow();
        suffixSite.set(SiteModel.NAME, "Suffix Site");
        suffixSite.set(SiteModel.SLUG, "suffix-site");
        suffixSite.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        suffixSite.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        suffixSite.set(SiteModel.STATUS, "active");
        suffixSite.set(SiteModel.ENABLED, true);
        siteModel.save(suffixSite);

        Row toggleSite = siteModel.createEmptyRow();
        toggleSite.set(SiteModel.NAME, "Toggle Label Site");
        toggleSite.set(SiteModel.SLUG, "toggle-label-site");
        toggleSite.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        toggleSite.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
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

        // The processes tab is GONE: it was deleted with the host-user process lane
        // (phase-0 design section 3), so no site has one and the route 404s for every one.
        assertThat(get("/admin/sites/" + siteId).body())
            .doesNotContain("/admin/sites/" + siteId + "/page/processes");
        assertThat(get("/admin/sites/" + siteId + "/page/processes").statusCode()).isEqualTo(404);

        // The domains tab renders its empty state before any domain exists. Read over HTTP:
        // the empty state is server-rendered and RoutedLinkTargetsTest already reads this same
        // page that way. The POPULATED tab below stays a hydrated load -- that is where the
        // client render actually has something to get wrong.
        assertThat(get("/admin/sites/" + siteId + "/page/domains").body())
            .contains("No domains configured");

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

    /**
     * The dashboard must not contradict itself: a host that cannot take workloads is an
     * attention item, the checklist step that is BLOCKED does not wear a checkmark, and the
     * stat tiles form ONE grid whatever the role mix.
     */
    @Test
    @Order(24)
    void dashboardAttentionOnboardingAndStatsAgreeWithEachOther() throws Exception {
        var serverModel = Models.get(ServerModel.class);
        Row local = serverModel.findById(ServerModel.localServerId());
        String admission = local.get(ServerModel.ADMISSION);
        try {
            // 1. A host that is not admitted: the amber onboarding card and the attention
            //    list must agree, so "All clear" is impossible while the card is up.
            local.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
            serverModel.save(local);

            List<AttentionItem> blocked = new ArrayList<>();
            AttentionCollector.hostsNotAdmitted(blocked);
            assertThat(blocked)
                .as("step 1: a blocked host raises exactly one attention item")
                .hasSize(1);
            assertThat(blocked.get(0).severity()).as("step 1: as a warning").isEqualTo("warning");

            navigateToApp("/admin/dashboard");
            waitForHydration();
            assertThat(page.locator(".hh-attention-clear").count())
                .as("step 1: the dashboard never says 'All clear' while a host is blocked")
                .isZero();
            assertThat(page.locator(".hh-attention-item > a.hh-attention-target[href='/admin/servers/"
                + local.get(ServerModel.ID) + "']").count())
                .as("step 1: and the item links to the host that has to be admitted")
                .isEqualTo(1);

            // 2. The blocked checklist step wears a warning marker, never a checkmark.
            var blockedStep = page.locator(".hh-onboarding-step[data-state='blocked']");
            assertThat(blockedStep.count()).as("step 2: the admit step is blocked")
                .isGreaterThanOrEqualTo(1);
            assertThat(blockedStep.first()
                .locator(".hh-onboarding-marker pl-icon[name='circle-check']").count())
                .as("step 2: a blocking step must not look completed")
                .isZero();
            assertThat(blockedStep.first()
                .locator(".hh-onboarding-marker pl-icon[name='triangle-exclamation']").count())
                .as("step 2: it wears the warning marker instead")
                .isEqualTo(1);

            // 3. Every stat tile sits in ONE grid: proxy and firewall no longer contribute
            //    a region each, which used to split four tiles across two grids.
            assertThat(page.locator(".hh-dashboard-band .widget-columns").count())
                .as("step 3: exactly one stat grid on the dashboard")
                .isEqualTo(1);
            var grid = page.locator(".hh-dashboard-band .widget-columns").first();
            assertThat(grid.locator("a.widget-stat-link[href='/admin/sites']").count())
                .as("step 3: the sites tile is in it").isEqualTo(1);
            assertThat(grid.locator("a.widget-stat-link[href='/admin/bans']").count())
                .as("step 3: and so is the firewall tile that used to sit in its own grid")
                .isEqualTo(1);

            // 4. Admitting the host retracts the item; the collector answers negatively too.
            local.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
            serverModel.save(local);
            List<AttentionItem> admitted = new ArrayList<>();
            AttentionCollector.hostsNotAdmitted(admitted);
            assertThat(admitted).as("step 4: an admitted host raises nothing").isEmpty();

            // 5. A CORDONED host is a deliberate operator state, never a warning.
            local.set(ServerModel.ADMISSION, ServerModel.ADMISSION_CORDONED);
            serverModel.save(local);
            List<AttentionItem> cordoned = new ArrayList<>();
            AttentionCollector.hostsNotAdmitted(cordoned);
            assertThat(cordoned).as("step 5: a cordoned host raises nothing either").isEmpty();
        } finally {
            local.set(ServerModel.ADMISSION, admission);
            serverModel.save(local);
        }
    }

    /**
     * The dashboard's recent-activity band says WHY it is empty while recording is off.
     *
     * AIDEV-NOTE: it used to show the generic "no records found", which an operator reads
     * as "the fleet was quiet" -- the one reading that is never true when nothing is being
     * written down. The sentence is the activity browser's OWN
     * ({@code AdminActivityResource.recordingNotice()}), so the two surfaces cannot drift.
     */
    @Test
    @Order(25)
    void theDashboardActivityBandSaysWhenRecordingIsSwitchedOff() throws Exception {

        Boolean before = Zenit.SETTINGS_VALUES.getValue(ActivityLog.ENABLED);

        try {
            // 1. Recording off: the notice exists at all, and it is the framework
            //    resource's own -- hohenheim never reads activity.enabled a second time.
            Zenit.SETTINGS_VALUES.setValue(ActivityLog.ENABLED, false);
            Microcopy notice = AdminActivityResource.recordingNotice();
            assertThat(notice)
                .as("step 1: the activity resource declares a recording-off notice")
                .isNotNull();
            String sentence = notice.resolve(LocaleChain.ofTags("en"), new DefaultCatalogLoader());

            // 2. The dashboard band carries it, above the list it explains.
            navigateToApp("/admin/dashboard");
            waitForHydration();
            var alert = page.locator(".hh-dashboard-band .widget-alert");
            assertThat(alert.count())
                .as("step 2: the recent-activity band renders the notice")
                .isEqualTo(1);
            assertThat(alert.first().innerText())
                .as("step 2: in the activity browser's own words")
                .contains(sentence);
            assertThat(page.locator(".hh-dashboard-band .widget-records").count())
                .as("step 2: the list itself stays -- rows written before the switch still count")
                .isEqualTo(1);

            // 3. And it is the SAME sentence /admin/activity shows, which is what makes
            //    this one declaring home rather than two settings reads that agree today.
            assertThat(get("/admin/activity").body())
                .as("step 3: both surfaces say the same thing")
                .contains(sentence);

            // 4. FALSIFIED: with recording on the band is just the list -- an always-on
            //    notice would train operators to ignore the one that matters.
            Zenit.SETTINGS_VALUES.setValue(ActivityLog.ENABLED, true);
            assertThat(AdminActivityResource.recordingNotice())
                .as("step 4: nothing to say while recording is on")
                .isNull();
            navigateToApp("/admin/dashboard");
            waitForHydration();
            assertThat(page.locator(".hh-dashboard-band .widget-alert").count())
                .as("step 4: and the dashboard shows no notice")
                .isZero();
        } finally {
            Zenit.SETTINGS_VALUES.setValue(ActivityLog.ENABLED, before);
        }
    }
}
