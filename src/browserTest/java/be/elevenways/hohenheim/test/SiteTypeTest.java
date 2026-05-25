package be.elevenways.hohenheim.test;

import com.microsoft.playwright.Locator;
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

    /**
     * Select a value in a pl-select by clicking its trigger and then clicking the item.
     * Items are rendered in a portal, so we search the whole page for the option.
     * Uses the pl-select-item's value attribute (on the custom element) to find the item.
     */
    private void selectPlOption(String selectName, String value) {
        var trigger = page.locator("pl-select[name='" + selectName + "'] pl-select-trigger button");
        trigger.click();

        // Wait for the popup to be positioned on-screen before clicking
        var item = page.locator("div[role='option'][data-value='" + value + "']");
        item.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(5000));
        item.click();

        // Wait twice to flush cascading reactive updates
        waitForReactiveIdle();
        waitForReactiveIdle();
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
    void typeDropdownHasAllSevenTypes() {
        navigateToApp("/sites/create");
        waitForHydration();

        // Open the dropdown to make items visible (rendered in portal)
        page.locator("pl-select[name='site_type'] pl-select-trigger button").click();

        // Count site type items via their inner option div with data-value attribute
        var items = page.locator("pl-select-item div[role='option'][data-value^='hohenheim:']");
        assertThat(items.count()).isEqualTo(7);

        String allText = items.allTextContents().toString();
        assertThat(allText).contains("Proxy");
        assertThat(allText).contains("Node.js");
        assertThat(allText).contains("Alchemy");
        assertThat(allText).contains("Command");
        assertThat(allText).contains("Static");
        assertThat(allText).contains("Redirect");
        assertThat(allText).contains("Dead");
    }

    @Test
    @Order(3)
    void switchingToStaticShowsStaticFields() {
        navigateToApp("/sites/create");
        waitForHydration();

        selectPlOption("site_type", "hohenheim:static");
        page.waitForCondition(() -> getFormText().contains("Root Directory")
            && !getFormText().contains("Upstream Host"));

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

        selectPlOption("site_type", "hohenheim:redirect");
        page.waitForCondition(() -> getFormText().contains("Target URL")
            && !getFormText().contains("Upstream Host"));

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

        selectPlOption("site_type", "hohenheim:redirect");
        page.waitForCondition(() -> getFormText().contains("Target URL"));

        selectPlOption("site_type", "hohenheim:proxy");
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

    @Test
    @Order(7)
    void switchingToNodeShowsAdvancedNodeFields() {
        navigateToApp("/sites/create");
        waitForHydration();

        selectPlOption("site_type", "hohenheim:node");
        page.waitForCondition(() -> getFormText().contains("Environment Variables"));

        String formText = getFormText();
        assertThat(formText).contains("Environment Variables");
        assertThat(formText).contains("Wait For Ready Signal");
    }

    @Test
    @Order(8)
    void switchingToStaticShowsIndexControls() {
        navigateToApp("/sites/create");
        waitForHydration();

        selectPlOption("site_type", "hohenheim:static");
        page.waitForCondition(() -> getFormText().contains("Use index.html for directories"));

        String formText = getFormText();
        assertThat(formText).contains("Use index.html for directories");
        assertThat(formText).contains("Show directory listing");
    }
}
