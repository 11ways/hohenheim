package be.elevenways.hohenheim.test;

import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

/**
 * The site create form carries the type-discriminated settings sub-form:
 * picking a type in the selector swaps the sub-form client-side (the
 * variants are pre-translated server-side, no round trip).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SiteTypeTest extends HohenheimTestBase {

    /**
     * Select a value in the site_type pl-select: click the trigger, then the
     * portalled option, then flush cascading reactive updates.
     */
    private void selectSiteType(String value) {
        var trigger = page.locator("pl-select[name='site_type'] pl-select-trigger button");
        trigger.click();

        var item = page.locator("div[role='option'][data-value='" + value + "']");
        item.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(5000));
        item.click();

        waitForReactiveIdle();
        waitForReactiveIdle();
    }

    @Test
    @Order(1)
    void typeDropdownHasAllEightTypes() {
        navigateToApp("/admin/sites/new");
        waitForHydration();

        page.locator("pl-select[name='site_type'] pl-select-trigger button").click();

        var items = page.locator("div[role='option'][data-value^='hohenheim:']");
        assertThat(items.count()).isEqualTo(8);

        String allText = items.allTextContents().toString();
        assertThat(allText).contains("Proxy");
        assertThat(allText).contains("Node.js");
        assertThat(allText).contains("Alchemy");
        assertThat(allText).contains("Command");
        assertThat(allText).contains("Docker");
        assertThat(allText).contains("Static");
        assertThat(allText).contains("Redirect");
        assertThat(allText).contains("Dead");

        // Close the dropdown again so later interactions start clean.
        page.keyboard().press("Escape");
    }

    @Test
    @Order(2)
    void selectingProxyShowsItsSettingsSubForm() {
        navigateToApp("/admin/sites/new");
        waitForHydration();

        selectSiteType("hohenheim:proxy");

        page.waitForSelector("pl-input[name='settings.forward_host']");
        assertThat(page.locator("pl-input[name='settings.forward_host']").count()).isEqualTo(1);
        assertThat(page.locator("pl-input[name='settings.forward_port']").count()).isEqualTo(1);
    }

    @Test
    @Order(3)
    void switchingToRedirectSwapsTheSubForm() {
        navigateToApp("/admin/sites/new");
        waitForHydration();

        selectSiteType("hohenheim:proxy");
        page.waitForSelector("pl-input[name='settings.forward_host']");

        selectSiteType("hohenheim:redirect");
        page.waitForSelector("pl-input[name='settings.target_url']");

        assertThat(page.locator("pl-input[name='settings.target_url']").count()).isEqualTo(1);
        assertThat(page.locator("pl-input[name='settings.forward_host']").count()).isEqualTo(0);
    }

    @Test
    @Order(4)
    void nodeVariantCarriesEnvVarAndTransportControls() {
        navigateToApp("/admin/sites/new");
        waitForHydration();

        selectSiteType("hohenheim:node");
        page.waitForSelector("pl-input[name='settings.script']");

        String content = page.content();
        assertThat(content).contains("environment_variables");
        assertThat(content).contains("use_ports");
        assertThat(content).contains("wait_for_ready");
        assertThat(content).contains("api_keys");
    }
}
