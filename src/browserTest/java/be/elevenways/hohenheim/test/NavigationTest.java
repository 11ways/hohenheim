package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.junit.jupiter.api.Test;

import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/**
 * Navigation through the zenit-cms admin shell: sidebar links, soft
 * navigation, back button, and shell layout.
 */
class NavigationTest extends HohenheimTestBase {

    private void waitForHeading(String expected) {
        page.waitForCondition(() -> {
            var el = page.querySelector("h1");
            return el != null && el.textContent().contains(expected);
        });
    }

    @Test
    void shellLayoutSurvivesSoftNavigationAcrossThePanel() {
        navigateToApp("/admin");
        waitForHydration();

        // The dashboard is the landing page and carries the shell chrome.
        assertThat(page.locator("pl-app-sidebar").count()).isEqualTo(1);
        assertThat(page.content()).contains("Sites");

        // The pl-app-shell grid places the sidebar to the left of the content
        // (grid-areas "sidebar content"), so they sit on the same row.
        var sidebar = page.locator("pl-app-sidebar").boundingBox();
        var main = page.locator("pl-app-content").boundingBox();
        assertThat(sidebar).isNotNull();
        assertThat(main).isNotNull();
        assertThat(sidebar.x + sidebar.width)
            .as("sidebar right edge should be at or before main left edge")
            .isLessThanOrEqualTo(main.x + 1.0);
        assertThat(main.y)
            .as("main content should sit on the same row as the sidebar")
            .isLessThan(sidebar.y + sidebar.height);

        // Soft nav into a cluster: the Domains entry lands on its first member, Addresses, whose page heads with
        // the cluster's tabs; a tab soft-navigates to a sibling member. The URL, the heading and the shell survive.
        page.locator("pl-app-sidebar a[href='/admin/" + HohenheimPanel.DOMAINS_CLUSTER + "']").click();
        waitForHeading("Addresses");
        assertThat(page.url()).endsWith("/admin/domains");
        page.locator("[data-cms-cluster-tabs] a[href='/admin/certificates']").first().click();
        waitForHeading("Certificates");
        assertThat(page.url()).endsWith("/admin/certificates");
        assertThat(page.locator("h1").first().textContent()).contains("Certificates");
        assertThat(page.locator(".cms-brand").textContent()).contains("Hohenheim");
        // The boards' eight sidebar entries, each a link.
        assertThat(page.locator("pl-app-sidebar a").count()).isGreaterThanOrEqualTo(8);

        // Regression: after a soft nav the client renders the list footer itself;
        // filtered short keys ("none" scope=cms target=range) must resolve from
        // the browser bundle, not degrade to their raw key.
        page.waitForCondition(() -> page.locator(".cms-list-count").count() > 0);
        // Allow the async microcopy fill one repaint before judging.
        page.waitForCondition(() -> {
            String text = page.locator(".cms-list-count").textContent().trim();
            return !text.equals("none") && !text.equals("range");
        });
        String count = page.locator(".cms-list-count").textContent().trim();
        assertThat(count).satisfiesAnyOf(
            t -> assertThat(t).contains("No records"),
            t -> assertThat(t).contains("of"));

        // A second soft nav keeps the brand in place.
        page.locator("pl-app-sidebar a[href='/admin/apps']").click();
        waitForHeading("Apps");
        assertThat(page.locator("h1").first().textContent()).contains("Apps");
        assertThat(page.locator(".cms-brand").textContent()).contains("Hohenheim");

        // The browser back button restores the previous soft-navigated page.
        page.goBack();
        waitForHeading("Certificates");
        assertThat(page.locator("h1").first().textContent()).contains("Certificates");

        navigateToApp("/admin/settings");
        waitForHydration();
        assertThat(page.locator("h1").first().textContent()).contains("Settings");
        assertThat(page.content()).contains("Proxy");
        assertThat(page.content()).contains("Security");
    }

    @Test
    void softNavDashboardKeepsStatTitlesIconsAndAttentionEntries() throws Exception {
        // Regression: the dashboard re-renders CLIENT-side over soft navigation.
        // Stat titles are locale-map lookups (they need the payload-seeded locale
        // chain) and typed attention items must ride WidgetInstance runtime
        // data -- the old render-time server-only collector read produced a
        // false "All clear" on every soft nav.
        var certModel = Models.get(CertificateModel.class);
        Row cert = certModel.createEmptyRow();
        cert.set(CertificateModel.NICE_NAME, "softnav-attention-cert");
        cert.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_LETSENCRYPT);
        cert.set(CertificateModel.STATUS, CertificateModel.STATUS_ERROR);
        cert.set(CertificateModel.DOMAIN_NAMES_TEXT, "softnav.example.test");
        cert.set(CertificateModel.RENEWAL_ERROR, "boom");
        cert.set(CertificateModel.ERROR_COUNT, 3);
        certModel.save(cert);
        // An app, so the dashboard is the running fleet's (attention, stat tiles, apps) rather than a fresh install's
        // lead, which counts nothing on purpose.
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "softnav-app");
        site.set(SiteModel.SLUG, "softnav-app");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);

        try {
            navigateToApp("/admin/sites");
            waitForHydration();

            page.locator("pl-app-sidebar a[href='/admin/dashboard']").click();
            page.waitForCondition(() -> page.locator(".hh-dashboard-band").count() >= 3);

            // Stat tiles keep their localized titles AND their icons.
            page.waitForCondition(() -> page.locator("pl-stat-card .label").count() >= 3);
            var labels = page.locator("pl-stat-card .label").allInnerTexts();
            assertThat(labels).anySatisfy(label -> assertThat(label).contains("Apps"));
            assertThat(labels).allSatisfy(label -> assertThat(label.trim()).isNotEmpty());
            assertThat(page.locator("pl-stat-card .stat-icon pl-icon").count())
                .isGreaterThanOrEqualTo(3);

            // The attention panel shows the real entries, never a false all-clear.
            assertThat(page.locator(".hh-attention-item[data-severity='error']").count())
                .isGreaterThanOrEqualTo(1);
            assertThat(page.locator(".hh-attention-clear").count()).isZero();
        } finally {
            certModel.delete(cert);
            siteModel.delete(site);
        }
    }
}
