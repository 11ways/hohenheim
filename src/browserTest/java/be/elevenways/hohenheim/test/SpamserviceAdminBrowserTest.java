package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Browser coverage for disconnected Spamservice administration and canonical /admin routes. */
class SpamserviceAdminBrowserTest extends HohenheimTestBase {

    @Test
    void disconnectedOverviewIsGracefulAndUsesPlumage() {
        navigateToApp("/admin/spamservice");
        waitForHydration();

        assertThat(page.locator("h1").innerText()).contains("Spamservice");
        assertThat(page.locator("pl-alert").innerText()).contains("not connected");
        assertThat(page.locator("pl-card").count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void installationNeverRendersTheControllerKey() {
        navigateToApp("/admin/spamservice-installation");
        waitForHydration();

        assertThat(page.locator("pl-switch[name='enabled']").count()).isEqualTo(1);
        assertThat(page.locator("input[type='hidden'][name='enabled']").count()).isEqualTo(1);
        assertThat(page.locator("[name='working_directory']").count()).isZero();
        assertThat(page.locator("pl-input[name='port'][type='number']").count()).isEqualTo(1);
        assertThat(page.locator("zf-relation-field").count()).isEqualTo(1);
        assertThat(page.locator("pl-input[name='max_heap_mb'][type='number']").count()).isEqualTo(1);
        assertThat(page.locator("[name='controller_key']").count()).isZero();
        assertThat(page.content()).doesNotContain("controller_key");
    }

    @Test
    void settingsAndReputationLiveInsideTheHohenheimAdmin() {
        navigateToApp("/admin/settings");
        waitForHydration();
        assertThat(page.locator("body").innerText()).contains("Spamservice");

        navigateToApp("/admin/spamservice-reputation");
        waitForHydration();
        assertThat(page.locator("pl-input[name='ip']").count()).isEqualTo(1);
        assertThat(page.locator("form[action='/admin/spamservice-reputation']").count()).isEqualTo(1);
    }
}
