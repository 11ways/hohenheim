package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Tests the site creation form's type-specific fields and site type system.
 */
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
    void formHasNameField() {
        navigateToApp("/sites/create");
        waitForHydration();

        assertThat(page.locator("pl-input").count()).isGreaterThanOrEqualTo(1);
        assertThat(page.content()).contains("Site Name");
    }

    @Test
    @Order(4)
    void formHasDomainField() {
        navigateToApp("/sites/create");
        waitForHydration();

        assertThat(page.content()).contains("Domain (optional)");
    }

    @Test
    @Order(5)
    void formHasSubmitAndCancelButtons() {
        navigateToApp("/sites/create");
        waitForHydration();

        assertThat(page.content()).contains("Create Site");
        assertThat(page.content()).contains("Cancel");
    }
}
