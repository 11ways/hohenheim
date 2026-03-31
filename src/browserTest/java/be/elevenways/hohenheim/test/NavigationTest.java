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

        page.locator(".hh-sidebar__link[href='/sites']").click();
        waitForTitle("Sites");

        assertThat(page.url()).endsWith("/sites");
        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Sites");
    }

    @Test
    @Order(5)
    void softNavFromSitesToCertificates() {
        navigateToApp("/sites");
        waitForHydration();

        page.locator(".hh-sidebar__link[href='/certificates']").click();
        waitForTitle("Certificates");

        assertThat(page.url()).endsWith("/certificates");
        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Certificates");
    }

    @Test
    @Order(6)
    void softNavFromCertificatesToDashboard() {
        navigateToApp("/certificates");
        waitForHydration();

        page.locator(".hh-sidebar__link[href='/']").click();
        waitForTitle("Dashboard");

        assertThat(page.url()).endsWith("/");
        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Dashboard");
    }

    @Test
    @Order(7)
    void sidebarPersistsAcrossSoftNav() {
        navigateToApp("/");
        waitForHydration();

        assertThat(page.locator(".hh-sidebar__logo").textContent()).isEqualTo("Hohenheim");

        page.locator(".hh-sidebar__link[href='/sites']").click();
        waitForTitle("Sites");

        assertThat(page.locator(".hh-sidebar__logo").textContent()).isEqualTo("Hohenheim");
        assertThat(page.locator(".hh-sidebar__link").count()).isEqualTo(3);
    }

    @Test
    @Order(8)
    void browserBackButtonWorks() {
        navigateToApp("/");
        waitForHydration();

        page.locator(".hh-sidebar__link[href='/sites']").click();
        waitForTitle("Sites");

        page.goBack();
        waitForTitle("Dashboard");

        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Dashboard");
    }
}
