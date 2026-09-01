package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.*;

import java.util.LinkedHashMap;
import java.util.Map;

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

    /** Click an upstream-kind choice card, then flush reactive updates. */
    private void selectUpstreamKind(String value) {
        page.click("pl-choice-group[name='upstream_kind'] pl-choice-card[data-value='"
            + value + "'] button");

        waitForReactiveIdle();
        waitForReactiveIdle();
    }

    /** One create-form load walked through every upstream variant. */
    @Test
    @Order(1)
    void createFormSwapsEverySettingsVariant() {
        navigateToApp("/admin/sites/new");
        waitForHydration();

        // 1. The offer is exactly the six upstreams a hostname can resolve to, drawn as
        //    CHOICE CARDS (icon + label + one-sentence description) instead of a dropdown.
        //    A stale workload kind reappearing here is what this count catches.
        var cards = page.locator(
            "pl-choice-group[name='upstream_kind'] pl-choice-card[data-value^='hohenheim:']");
        assertThat(cards.count()).as("step 1: six upstream kinds are offered").isEqualTo(6);

        String allText = cards.allTextContents().toString();
        assertThat(allText).as("step 1: every member is named").contains("Address", "Static",
            "Redirect", "Instance", "Dev namespace", "TLS passthrough");
        assertThat(allText).as("step 1: every card explains itself in a sentence")
            .contains("Serve static files", "Redirect requests", "Forward requests");
        assertThat(allText).as("step 1: no workload kind survives in the upstream list")
            .doesNotContain("Node.js", "Java / Zenit", "Alchemy", "Dead");

        // 2. Each upstream swaps in its own settings sub-form, client-side.
        // AIDEV-NOTE: a numeric entry is a pl-number-input, never a pl-input -- every field
        // carrying a NumberShape (which is every Integer/Long/Double field, bounds or not)
        // takes that branch of form/plain.hwk. Asking for pl-input by port name matched
        // nothing and the count assertions below simply read 0.
        selectUpstreamKind("hohenheim:address");
        page.waitForSelector("pl-input[name='settings.forward_host']");
        assertThat(page.locator("pl-number-input[name='settings.forward_port']").count())
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
        assertThat(page.locator("pl-number-input[name='settings.forward_port']").count()).isEqualTo(1);
        assertThat(page.locator("pl-switch[name='settings.proxy_protocol_v2']").count()).isEqualTo(1);
        assertThat(page.locator("pl-number-input[name='settings.connect_timeout']").count()).isEqualTo(1);

        // 3. The source is NOT on this form any more: it moved to the instance the site
        //    exposes, so no upstream may offer a repository field here.
        assertThat(page.locator("pl-select[name='source']").count())
            .as("step 3: the git source left the site form").isZero();
        assertThat(page.locator("pl-input[name='source_settings.repository_url']").count())
            .as("step 3: and so did its settings").isZero();
    }

    /**
     * The site-upstream flow END TO END: the instance pick sleeps until the instance
     * card is chosen, then offers the exposable application, and the submit lands a
     * site that names it.
     */
    @Test
    @Order(2)
    void instanceUpstreamFlowEndToEnd() {
        var instances = Models.get(InstanceModel.class);
        Row application = instances.find()
            .where(InstanceModel.NAME.eq("ukt-exposable-app")).first();
        if (application == null) {
            application = instances.createEmptyRow();
            application.set(InstanceModel.NAME, "ukt-exposable-app");
            application.set(InstanceModel.KIND, "hohenheim:application");
            application.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of()));
            instances.save(application);
        }
        Integer applicationId = application.get(InstanceModel.ID);

        navigateToApp("/admin/sites/new");
        waitForHydration();

        // 1. Under a kind that serves no instance, the pick stays ENABLED and its
        //    empty popup names the declared reason (the narrowing resolves to a
        //    match-none rule with reasonNothingQualifies, not to a disabled control).
        selectUpstreamKind("hohenheim:static");
        page.waitForCondition(() ->
            page.locator("pl-select[name='instance_id'][disabled]").count() == 0);
        openPlSelect("pl-select[name='instance_id']");
        page.waitForSelector(OPEN_SELECT_POPUP);
        assertThat(page.locator(OPEN_SELECT_POPUP).textContent())
            .contains("does not serve an instance");
        page.keyboard().press("Escape");
        page.waitForCondition(() -> page.locator(OPEN_SELECT_POPUP).count() == 0);

        // 2. The instance card wakes it, still with no round trip.
        selectUpstreamKind("hohenheim:instance");
        page.waitForCondition(() ->
            page.locator("pl-select[name='instance_id'][disabled]").count() == 0);

        // 3. The application is offered; pick it.
        openPlSelect("pl-select[name='instance_id']");
        page.waitForSelector(
            OPEN_SELECT_POPUP + " div[role='option'][data-value='" + applicationId + "']");
        page.click(
            OPEN_SELECT_POPUP + " div[role='option'][data-value='" + applicationId + "']");
        page.waitForCondition(() -> page.locator(OPEN_SELECT_POPUP).count() == 0);

        // 4. Submit: the site records WHICH workload its hostname serves.
        type("input[name='name']", "ukt-exposed-site");
        page.evaluate("document.querySelector('form.cms-form-layout').requestSubmit()");
        page.waitForCondition(() -> Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("ukt-exposed-site")).first() != null);

        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("ukt-exposed-site")).first();
        assertThat((Object) site.get(SiteModel.UPSTREAM_KIND))
            .isEqualTo("hohenheim:instance");
        assertThat((Object) site.get(SiteModel.INSTANCE_ID)).isEqualTo(applicationId);
    }
}
