package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NavigationTest extends HohenheimTestBase {

    private void waitForTitle(String expected) {
        page.waitForCondition(() -> {
            var el = page.querySelector(".hh-header__title");
            return el != null && expected.equals(el.textContent());
        });
    }

    @Test
    @Order(1)
    void dashboardLoads() {
        navigateToApp("/");
        waitForHydration();
        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Dashboard");
    }

    @Test
    @Order(2)
    void sitesPageLoads() {
        navigateToApp("/sites");
        waitForHydration();
        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Sites");
    }

    @Test
    @Order(3)
    void certificatesPageLoads() {
        navigateToApp("/certificates");
        waitForHydration();
        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Certificates");
    }

    @Test
    @Order(4)
    void softNavFromDashboardToSites() {
        navigateToApp("/");
        waitForHydration();

        page.locator("pl-app-sidebar a[href='/sites']").click();
        waitForTitle("Sites");

        assertThat(page.url()).endsWith("/sites");
        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Sites");
    }

    @Test
    @Order(5)
    void softNavFromSitesToCertificates() {
        navigateToApp("/sites");
        waitForHydration();

        page.locator("pl-app-sidebar a[href='/certificates']").click();
        waitForTitle("Certificates");

        assertThat(page.url()).endsWith("/certificates");
        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Certificates");
    }

    @Test
    @Order(6)
    void softNavFromCertificatesToDashboard() {
        navigateToApp("/certificates");
        waitForHydration();

        page.locator("pl-app-sidebar a[href='/']").click();
        waitForTitle("Dashboard");

        assertThat(page.url()).endsWith("/");
        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Dashboard");
    }

    @Test
    @Order(7)
    void sidebarPersistsAcrossSoftNav() {
        navigateToApp("/");
        waitForHydration();

        assertThat(page.locator(".hh-brand").textContent()).isEqualTo("Hohenheim");

        page.locator("pl-app-sidebar a[href='/sites']").click();
        waitForTitle("Sites");

        assertThat(page.locator(".hh-brand").textContent()).isEqualTo("Hohenheim");
        assertThat(page.locator("pl-app-sidebar a").count()).isEqualTo(10);
    }

    @Test
    @Order(8)
    void settingsPageLoads() {
        navigateToApp("/settings");
        waitForHydration();
        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Settings");
        assertThat(page.content()).contains("Proxy");
        assertThat(page.content()).contains("Security");
    }

    @Test
    @Order(9)
    void sidebarIsLeftOfMainContent() {
        // The pl-app-shell grid places the sidebar to the left of the content
        // (grid-areas "sidebar content"), so they sit on the same row.
        navigateToApp("/");
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
    @Order(10)
    void browserBackButtonWorks() {
        navigateToApp("/");
        waitForHydration();

        page.locator("pl-app-sidebar a[href='/sites']").click();
        waitForTitle("Sites");

        page.goBack();
        waitForTitle("Dashboard");

        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Dashboard");
    }
}
