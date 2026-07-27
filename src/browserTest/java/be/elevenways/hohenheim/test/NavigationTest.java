package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Navigation through the zenit-cms admin shell: sidebar links, soft
 * navigation, back button, and shell layout.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NavigationTest extends HohenheimTestBase {

    private void waitForHeading(String expected) {
        page.waitForCondition(() -> {
            var el = page.querySelector("h1");
            return el != null && el.textContent().contains(expected);
        });
    }

    @Test
    @Order(1)
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

        // Soft nav to a list page: the URL, the heading and the shell survive.
        page.locator("pl-app-sidebar a[href='/admin/certificates']").click();
        waitForHeading("Certificates");
        assertThat(page.url()).endsWith("/admin/certificates");
        assertThat(page.locator("h1").first().textContent()).contains("Certificates");
        assertThat(page.locator(".cms-brand").textContent()).contains("Hohenheim");
        // Every declared resource plus dashboard/settings renders a sidebar entry.
        assertThat(page.locator("pl-app-sidebar a").count()).isGreaterThanOrEqualTo(10);

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
        page.locator("pl-app-sidebar a[href='/admin/sites']").click();
        waitForHeading("Sites");
        assertThat(page.locator("h1").first().textContent()).contains("Sites");
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
    @Order(2)
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

        try {
            navigateToApp("/admin/sites");
            waitForHydration();

            page.locator("pl-app-sidebar a[href='/admin/dashboard']").click();
            page.waitForCondition(() -> page.locator(".hh-dashboard-band").count() >= 3);

            // Stat tiles keep their localized titles AND their icons.
            page.waitForCondition(() -> page.locator("pl-stat-card .label").count() >= 3);
            var labels = page.locator("pl-stat-card .label").allInnerTexts();
            assertThat(labels).anySatisfy(label -> assertThat(label).contains("Sites"));
            assertThat(labels).allSatisfy(label -> assertThat(label.trim()).isNotEmpty());
            assertThat(page.locator("pl-stat-card .stat-icon pl-icon").count())
                .isGreaterThanOrEqualTo(3);

            // The attention panel shows the real entries, never a false all-clear.
            assertThat(page.locator(".hh-attention-item[data-severity='error']").count())
                .isGreaterThanOrEqualTo(1);
            assertThat(page.locator(".hh-attention-clear").count()).isZero();
        } finally {
            certModel.delete(cert);
        }
    }
}
