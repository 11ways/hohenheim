package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SiteTypeTest extends HohenheimTestBase {

    @Test
    @Order(1)
    void createFormShowsProxyFieldsByDefault() {
        navigateToApp("/sites/create");
        waitForHydration();

        assertThat(page.content()).contains("Upstream Host");
        assertThat(page.content()).contains("Port");
        assertThat(page.content()).doesNotContain("Root Directory");
        assertThat(page.content()).doesNotContain("Target URL");
    }

    @Test
    @Order(2)
    void typeDropdownHasAllThreeTypes() {
        navigateToApp("/sites/create");
        waitForHydration();

        var options = page.locator("select[name='site_type'] option");
        assertThat(options.count()).isEqualTo(3);
        assertThat(options.nth(0).textContent()).contains("Proxy");
        assertThat(options.nth(1).textContent()).contains("Static");
        assertThat(options.nth(2).textContent()).contains("Redirect");
    }

    @Test
    @Order(3)
    void switchingToStaticShowsStaticFields() {
        navigateToApp("/sites/create");
        waitForHydration();

        page.locator("select[name='site_type']").selectOption("hohenheim:static");
        page.waitForCondition(() -> page.content().contains("Root Directory"));

        assertThat(page.content()).contains("Root Directory");
        assertThat(page.content()).contains("SPA Fallback");
        assertThat(page.content()).doesNotContain("Upstream Host");
    }

    @Test
    @Order(4)
    void switchingToRedirectShowsRedirectFields() {
        navigateToApp("/sites/create");
        waitForHydration();

        page.locator("select[name='site_type']").selectOption("hohenheim:redirect");
        page.waitForCondition(() -> page.content().contains("Target URL"));

        assertThat(page.content()).contains("Target URL");
        assertThat(page.content()).contains("Status Code");
        assertThat(page.content()).doesNotContain("Upstream Host");
    }

    @Test
    @Order(5)
    void switchingBackToProxyRestoresProxyFields() {
        navigateToApp("/sites/create");
        waitForHydration();

        page.locator("select[name='site_type']").selectOption("hohenheim:redirect");
        page.waitForCondition(() -> page.content().contains("Target URL"));

        page.locator("select[name='site_type']").selectOption("hohenheim:proxy");
        page.waitForCondition(() -> page.content().contains("Upstream Host"));

        assertThat(page.content()).contains("Upstream Host");
        assertThat(page.content()).doesNotContain("Target URL");
    }

    @Test
    @Order(6)
    void formHasDomainField() {
        navigateToApp("/sites/create");
        waitForHydration();
        assertThat(page.content()).contains("Domain (optional)");
    }
}
