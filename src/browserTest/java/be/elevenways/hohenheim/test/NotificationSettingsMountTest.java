package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.HohenheimRetiredNames;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.notifications.NotificationSettings;
import be.elevenways.zenit.notifications.server.cms.NotificationSettingsLabels;
import be.elevenways.zenit.server.setting.RetiredConfiguration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The notifications transport chain is a group of the framework's own settings, edited on the admin settings page
 * under its own mount; the retired settings/comms.dry is adopted into settings/local.dry and COMMS__* refused at
 * boot.
 */
class NotificationSettingsMountTest extends HohenheimTestBase {

    private static final String DSN = "hub://zcm_test_token_never_shown@127.0.0.1:1?insecure=true";

    @Test
    void theNotificationsGroupIsMountedOnceAndTheOldFileIsRetired() {
        // 1. The host's old comms file and prefix are declared for every boot to judge.
        assertThat(RetiredConfiguration.declared()).as("step 1: the retired names are declared")
            .containsAll(HohenheimRetiredNames.RETIRED);

        // 2. A transport chain in the framework context is what the dispatcher reads.
        Zenit.SETTINGS_VALUES.setValue(NotificationSettings.Channels.MAIL_TRANSPORTS, DSN);
        try {
            // 3. The settings page mounts the notifications group and masks the secret chain. Sections
            //    are LAZY (zenit-cms 380f48f): a bare load renders only the first group's rows,
            //    so the notifications channels section is named through ?section=, its JS-free lane.
            navigateToApp("/admin/settings?section=setting-" + NotificationSettingsLabels.MOUNT_KEY + "-"
                + NotificationSettings.Channels.CHANNELS.getName());
            waitForHydration();
            assertThat(page.locator("[data-path='notifications.channels.mail_transports']").count())
                .as("step 3: the notifications mount renders the mail transport chain setting")
                .isEqualTo(1);
            assertThat(page.locator("[data-path='framework.notifications.channels.mail_transports']").count())
                .as("step 3: and the framework mount does not repeat it")
                .isZero();
            assertThat(page.content())
                .as("step 3: a stored transport DSN carries credentials and never renders in clear")
                .doesNotContain("zcm_test_token_never_shown");
        } finally {
            Zenit.SETTINGS_VALUES.setValue(NotificationSettings.Channels.MAIL_TRANSPORTS, null);
        }
    }
}
