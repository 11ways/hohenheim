package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.ServerModel;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

/**
 * The site create form carries the type-discriminated settings sub-form:
 * picking a type in the selector swaps the sub-form client-side (the
 * variants are pre-translated server-side, no round trip).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SiteTypeTest extends HohenheimTestBase {

    /** The one open pl-select popup (closed popups stay mounted but hidden). */
    private static final String OPEN_SELECT_POPUP = "he-bottom .pl-select-popup[data-open]";

    /** Opens a pl-select via its field control and waits for its portalled popup. */
    private void openPlSelect(String hostSelector) {
        page.click(hostSelector + " .pl-select-field");
        page.waitForSelector(OPEN_SELECT_POPUP);
    }

    /** Select a value in the site_type pl-select, then flush reactive updates. */
    private void selectSiteType(String value) {
        openPlSelect("pl-select[name='site_type']");
        page.click(OPEN_SELECT_POPUP + " div[role='option'][data-value='" + value + "']");

        waitForReactiveIdle();
        waitForReactiveIdle();
    }

    /** One create-form load walked through every type variant and the git source swap. */
    @Test
    @Order(1)
    void createFormSwapsEverySettingsVariant() {
        navigateToApp("/admin/sites/new");
        waitForHydration();

        // Initial state: no source is git, so the git source settings stay out of the form.
        assertThat(page.locator("pl-input[name='source_settings.repository_url']").count()).isZero();

        openPlSelect("pl-select[name='site_type']");

        var items = page.locator(OPEN_SELECT_POPUP + " div[role='option'][data-value^='hohenheim:']");
        assertThat(items.count()).isEqualTo(11);

        String allText = items.allTextContents().toString();
        assertThat(allText).contains("Proxy");
        assertThat(allText).contains("Java / Zenit");
        assertThat(allText).contains("Node.js");
        assertThat(allText).contains("Alchemy");
        assertThat(allText).contains("Command");
        assertThat(allText).contains("Docker");
        assertThat(allText).contains("Static");
        assertThat(allText).contains("Redirect");
        assertThat(allText).contains("Dead");
        assertThat(allText).contains("Dev namespace");
        assertThat(allText).contains("TLS passthrough");

        // Close the dropdown again so later interactions start clean.
        page.keyboard().press("Escape");

        selectSiteType("hohenheim:proxy");
        page.waitForSelector("pl-input[name='settings.forward_host']");
        assertThat(page.locator("pl-input[name='settings.forward_host']").count()).isEqualTo(1);
        assertThat(page.locator("pl-input[name='settings.forward_port']").count()).isEqualTo(1);

        selectSiteType("hohenheim:redirect");
        page.waitForSelector("pl-input[name='settings.target_url']");
        assertThat(page.locator("pl-input[name='settings.target_url']").count()).isEqualTo(1);
        assertThat(page.locator("pl-input[name='settings.forward_host']").count()).isEqualTo(0);

        selectSiteType("hohenheim:node");
        page.waitForSelector("pl-input[name='settings.script']");

        String content = page.content();
        assertThat(content).contains("environment_variables");
        assertThat(content).contains("use_ports");
        assertThat(content).contains("wait_for_ready");
        assertThat(content).contains("api_keys");

        selectSiteType("hohenheim:docker");
        openPlSelect("pl-select[name='settings.server']");
        // The server picker is keyed by the canonical host key -- the servers.id, not the
        // name (M051), so a rename never orphans a stored placement. The option must be
        // THE local daemon's id key; asserting the literal "hohenheim:local" would pass
        // only against the pre-FK spelling that no longer exists anywhere.
        assertThat(page.locator(OPEN_SELECT_POPUP + " div[role='option'][data-value='"
            + ServerModel.registryKeyOf(ServerModel.localServerId()) + "']").count()).isEqualTo(1);
        page.keyboard().press("Escape");

        selectSiteType("hohenheim:tls_passthrough");
        page.waitForSelector("pl-input[name='settings.forward_host']");
        assertThat(page.locator("pl-input[name='settings.forward_port']").count()).isEqualTo(1);
        assertThat(page.locator("pl-switch[name='settings.proxy_protocol_v2']").count()).isEqualTo(1);
        assertThat(page.locator("pl-input[name='settings.connect_timeout']").count()).isEqualTo(1);

        openPlSelect("pl-select[name='source']");
        page.click(OPEN_SELECT_POPUP + " div[role='option'][data-value='git']");
        waitForReactiveIdle();
        page.waitForSelector("pl-input[name='source_settings.repository_url']");
        assertThat(page.locator("pl-input[name='source_settings.repository_url']").count()).isEqualTo(1);
    }
}
