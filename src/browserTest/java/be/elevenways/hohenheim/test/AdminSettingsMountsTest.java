package be.elevenways.hohenheim.test;

import be.elevenways.zenit.cms.server.page.SettingsPage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin settings page offers its four mounts, the framework one being zenit-cms's own
 * {@code SettingsPage.frameworkMount()} under the key every host shares.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class AdminSettingsMountsTest extends HohenheimTestBase {

    @Test
    void theSettingsPageMountsHohenheimFrameworkCommsAndSpamservice() throws Exception {
        // 1. The framework mount keeps the shared key, so ?section= deep links and setting
        //    paths written before the switch still address it.
        assertThat(SettingsPage.FRAMEWORK_MOUNT_KEY).as("step 1: the framework mount key")
            .isEqualTo("framework");

        // 2. One page load expanding a group of each file-backed mount renders all three.
        navigateToApp("/admin/settings?section="
            + "setting-app-proxy,setting-framework-network,setting-comms-channels");
        waitForHydration();
        assertThat(page.locator("[data-path='app.proxy.http_port']").count())
            .as("step 2: the hohenheim mount renders its proxy group").isEqualTo(1);
        assertThat(page.locator("[data-path='framework.network.request_body_size_limit']").count())
            .as("step 2: the framework mount renders zenit's network group under its shared key")
            .isEqualTo(1);
        assertThat(page.locator("[data-path='comms.channels.mail_transports']").count())
            .as("step 2: the comms mount renders its transport chain").isEqualTo(1);

        // 3. The spamservice backend is mounted too, and the framework mount is labelled by
        //    the framework's own vocabulary.
        String content = page.content();
        assertThat(content).as("step 3: the spamservice mount is offered").contains("Spamservice");
        assertThat(content).as("step 3: the framework mount carries SettingsLabels.framework()")
            .contains("Framework");
    }
}
