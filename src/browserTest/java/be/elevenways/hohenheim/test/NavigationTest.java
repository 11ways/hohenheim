package be.elevenways.hohenheim.test;

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
    void dashboardLoads() {
        navigateToApp("/admin");
        waitForHydration();
        assertThat(page.locator("pl-app-sidebar").count()).isEqualTo(1);
        assertThat(page.content()).contains("Sites");
    }

    @Test
    @Order(2)
    void sitesPageLoads() {
        navigateToApp("/admin/sites");
        waitForHydration();
        assertThat(page.locator("h1").first().textContent()).contains("Sites");
    }

    @Test
    @Order(3)
    void certificatesPageLoads() {
        navigateToApp("/admin/certificates");
        waitForHydration();
        assertThat(page.locator("h1").first().textContent()).contains("Certificates");
    }

    @Test
    @Order(4)
    void softNavFromSitesToCertificates() {
        navigateToApp("/admin/sites");
        waitForHydration();

        page.locator("pl-app-sidebar a[href='/admin/certificates']").click();
        waitForHeading("Certificates");

        assertThat(page.url()).endsWith("/admin/certificates");
    }

    @Test
    @Order(5)
    void sidebarPersistsAcrossSoftNav() {
        navigateToApp("/admin/sites");
        waitForHydration();

        String brand = page.locator(".cms-brand").textContent();
        assertThat(brand).contains("Hohenheim");

        page.locator("pl-app-sidebar a[href='/admin/certificates']").click();
        waitForHeading("Certificates");

        assertThat(page.locator(".cms-brand").textContent()).contains("Hohenheim");
        // Every declared resource plus dashboard/settings renders a sidebar entry.
        assertThat(page.locator("pl-app-sidebar a").count()).isGreaterThanOrEqualTo(10);
    }

    @Test
    @Order(6)
    void settingsPageLoads() {
        navigateToApp("/admin/settings");
        waitForHydration();
        assertThat(page.locator("h1").first().textContent()).contains("Settings");
        assertThat(page.content()).contains("Proxy");
        assertThat(page.content()).contains("Security");
    }

    @Test
    @Order(7)
    void sidebarIsLeftOfMainContent() {
        // The pl-app-shell grid places the sidebar to the left of the content
        // (grid-areas "sidebar content"), so they sit on the same row.
        navigateToApp("/admin");
        waitForHydration();

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
    }

    @Test
    @Order(8)
    void browserBackButtonWorks() {
        navigateToApp("/admin/sites");
        waitForHydration();

        page.locator("pl-app-sidebar a[href='/admin/certificates']").click();
        waitForHeading("Certificates");

        page.goBack();
        waitForHeading("Sites");

        assertThat(page.locator("h1").first().textContent()).contains("Sites");
    }
}
