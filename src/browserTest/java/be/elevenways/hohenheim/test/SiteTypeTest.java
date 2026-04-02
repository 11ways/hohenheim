package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SiteTypeTest extends HohenheimTestBase {

    /**
     * Get the visible text content of the form, excluding hydration script data.
     */
    private String getFormText() {
        return page.locator("form[action='/sites/create']").textContent();
    }

    @Test
    @Order(1)
    void createFormShowsProxyFieldsByDefault() {
        navigateToApp("/sites/create");
        waitForHydration();

        String formText = getFormText();
        assertThat(formText).contains("Upstream Host");
        assertThat(formText).contains("Port");
        assertThat(formText).doesNotContain("Root Directory");
        assertThat(formText).doesNotContain("Target URL");
    }

    @Test
    @Order(2)
    void typeDropdownHasAllThreeTypes() {
        navigateToApp("/sites/create");
        waitForHydration();

        var options = page.locator("select[name='site_type'] option");
        assertThat(options.count()).isEqualTo(6);
        assertThat(options.nth(0).textContent()).contains("Proxy");
        assertThat(options.nth(1).textContent()).contains("Node.js");
        assertThat(options.nth(2).textContent()).contains("Alchemy");
        assertThat(options.nth(3).textContent()).contains("Static");
        assertThat(options.nth(4).textContent()).contains("Redirect");
        assertThat(options.nth(5).textContent()).contains("Dead");
    }

    @Test
    @Order(3)
    void switchingToStaticShowsStaticFields() {
        navigateToApp("/sites/create");
        waitForHydration();

        page.locator("select[name='site_type']").selectOption("hohenheim:static");
        page.waitForCondition(() -> getFormText().contains("Root Directory"));

        String formText = getFormText();
        assertThat(formText).contains("Root Directory");
        assertThat(formText).contains("SPA Fallback");
        assertThat(formText).doesNotContain("Upstream Host");
    }

    @Test
    @Order(4)
    void switchingToRedirectShowsRedirectFields() {
        navigateToApp("/sites/create");
        waitForHydration();

        page.locator("select[name='site_type']").selectOption("hohenheim:redirect");
        page.waitForCondition(() -> getFormText().contains("Target URL"));

        String formText = getFormText();
        assertThat(formText).contains("Target URL");
        assertThat(formText).contains("Status Code");
        assertThat(formText).doesNotContain("Upstream Host");
    }

    @Test
    @Order(5)
    void switchingBackToProxyRestoresProxyFields() {
        navigateToApp("/sites/create");
        waitForHydration();

        page.locator("select[name='site_type']").selectOption("hohenheim:redirect");
        page.waitForCondition(() -> getFormText().contains("Target URL"));

        page.locator("select[name='site_type']").selectOption("hohenheim:proxy");
        page.waitForCondition(() -> getFormText().contains("Upstream Host"));

        String formText = getFormText();
        assertThat(formText).contains("Upstream Host");
        assertThat(formText).doesNotContain("Target URL");
    }

    @Test
    @Order(6)
    void formHasDomainField() {
        navigateToApp("/sites/create");
        waitForHydration();
        assertThat(getFormText()).contains("Domain (optional)");
    }
}
