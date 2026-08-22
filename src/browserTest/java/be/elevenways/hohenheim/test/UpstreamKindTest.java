package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

/**
 * The site create form carries the upstream-discriminated settings sub-form: picking an
 * upstream in the selector swaps the sub-form client-side (the variants are pre-translated
 * server-side, no round trip).
 *
 * AIDEV-NOTE: renamed from SiteTypeTest with the upstream vocabulary (phase-0 design
 * section 3). The workload half of the old site_type set (docker, node, java, command,
 * alchemy) is gone: those questions are answered by the INSTANCE kind now, so this test
 * walks the six upstreams a hostname can resolve to and nothing else.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UpstreamKindTest extends HohenheimTestBase {

    /** The one open pl-select popup (closed popups stay mounted but hidden). */
    private static final String OPEN_SELECT_POPUP = "he-bottom .pl-select-popup[data-open]";

    /** Opens a pl-select via its field control and waits for its portalled popup. */
    private void openPlSelect(String hostSelector) {
        page.click(hostSelector + " .pl-select-field");
        page.waitForSelector(OPEN_SELECT_POPUP);
    }

    /** Select a value in the upstream_kind pl-select, then flush reactive updates. */
    private void selectUpstreamKind(String value) {
        openPlSelect("pl-select[name='upstream_kind']");
        page.click(OPEN_SELECT_POPUP + " div[role='option'][data-value='" + value + "']");

        waitForReactiveIdle();
        waitForReactiveIdle();
    }

    /** One create-form load walked through every upstream variant. */
    @Test
    @Order(1)
    void createFormSwapsEverySettingsVariant() {
        navigateToApp("/admin/sites/new");
        waitForHydration();

        // 1. The offer is exactly the six upstreams a hostname can resolve to. The
        //    workload kinds that used to sit in this list live on the instance now, and a
        //    stale one reappearing here is what this count catches.
        openPlSelect("pl-select[name='upstream_kind']");

        var items = page.locator(OPEN_SELECT_POPUP + " div[role='option'][data-value^='hohenheim:']");
        assertThat(items.count()).as("step 1: six upstream kinds are offered").isEqualTo(6);

        String allText = items.allTextContents().toString();
        assertThat(allText).as("step 1: every member is named").contains("Address", "Static",
            "Redirect", "Instance", "Dev namespace", "TLS passthrough");
        assertThat(allText).as("step 1: no workload kind survives in the upstream list")
            .doesNotContain("Node.js", "Java / Zenit", "Alchemy", "Dead");

        // Close the dropdown again so later interactions start clean.
        page.keyboard().press("Escape");

        // 2. Each upstream swaps in its own settings sub-form, client-side.
        selectUpstreamKind("hohenheim:address");
        page.waitForSelector("pl-input[name='settings.forward_host']");
        assertThat(page.locator("pl-input[name='settings.forward_port']").count())
            .as("step 2: the address upstream asks for host and port").isEqualTo(1);

        selectUpstreamKind("hohenheim:redirect");
        page.waitForSelector("pl-input[name='settings.target_url']");
        assertThat(page.locator("pl-input[name='settings.forward_host']").count())
            .as("step 2: the previous variant's fields are gone").isEqualTo(0);

        selectUpstreamKind("hohenheim:instance");
        page.waitForSelector("pl-input[name='settings.port']");
        assertThat(page.locator("pl-select[name='settings.scheme']").count())
            .as("step 2: the instance upstream asks which port and scheme to serve")
            .isEqualTo(1);

        selectUpstreamKind("hohenheim:tls_passthrough");
        page.waitForSelector("pl-input[name='settings.forward_host']");
        assertThat(page.locator("pl-input[name='settings.forward_port']").count()).isEqualTo(1);
        assertThat(page.locator("pl-switch[name='settings.proxy_protocol_v2']").count()).isEqualTo(1);
        assertThat(page.locator("pl-input[name='settings.connect_timeout']").count()).isEqualTo(1);

        // 3. The source is NOT on this form any more: it moved to the instance the site
        //    exposes, so no upstream may offer a repository field here.
        assertThat(page.locator("pl-select[name='source']").count())
            .as("step 3: the git source left the site form").isZero();
        assertThat(page.locator("pl-input[name='source_settings.repository_url']").count())
            .as("step 3: and so did its settings").isZero();
    }
}
