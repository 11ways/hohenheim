package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SiteCrudTest extends HohenheimTestBase {

    @Test
    @Order(1)
    void dashboardRenders() {
        navigateToApp("/");
        waitForHydration();
        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Dashboard");
        assertThat(page.content()).contains("Sites");
        assertThat(page.content()).contains("Certificates");
        assertThat(page.content()).contains("Proxy");
    }

    @Test
    @Order(2)
    void sitesListShowsEmptyState() {
        navigateToApp("/sites");
        waitForHydration();
        assertThat(page.content()).contains("No sites configured yet");
    }

    @Test
    @Order(3)
    void createSiteFormLoads() {
        navigateToApp("/sites/create");
        waitForHydration();
        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Create Site");
        assertThat(page.content()).contains("New Site");
        assertThat(page.locator("form").count()).isGreaterThan(0);
    }

    @Test
    @Order(4)
    void createSiteFormHasPlumageComponents() {
        navigateToApp("/sites/create");
        waitForHydration();
        assertThat(page.locator("pl-input").count()).isGreaterThan(0);
        assertThat(page.locator("pl-button").count()).isGreaterThan(0);
    }

    @Test
    @Order(5)
    void certificatesListRenders() {
        navigateToApp("/certificates");
        waitForHydration();
        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Certificates");
        assertThat(page.content()).contains("No certificates yet");
    }

    @Test
    @Order(6)
    void softNavFromSitesToCreateForm() {
        navigateToApp("/sites");
        waitForHydration();

        page.locator("a[href='/sites/create']").click();

        page.waitForCondition(() -> {
            var el = page.querySelector(".hh-header__title");
            return el != null && "Create Site".equals(el.textContent());
        });

        assertThat(page.url()).endsWith("/sites/create");
    }
}
