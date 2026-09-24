package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.HohenheimRetiredNames;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.comms.CommsSettings;
import be.elevenways.zenit.comms.server.cms.CommsSettingsLabels;
import be.elevenways.zenit.server.setting.RetiredConfiguration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The comms transport chain is a group of the framework's own settings, edited on the admin settings page under
 * its own mount; the retired settings/comms.dry and COMMS__* are refused at boot.
 */
class CommsSettingsMountTest extends HohenheimTestBase {

    private static final String DSN = "hub://zcm_test_token_never_shown@127.0.0.1:1?insecure=true";

    @Test
    void theCommsGroupIsMountedOnceAndTheOldFileIsRetired() {
        // 1. The host's old comms file and prefix are declared for every boot to refuse.
        assertThat(RetiredConfiguration.declared()).as("step 1: the retired names are declared")
            .containsAll(HohenheimRetiredNames.RETIRED);

        // 2. A transport chain in the framework context is what the dispatcher reads.
        Zenit.SETTINGS_VALUES.setValue(CommsSettings.Channels.MAIL_TRANSPORTS, DSN);
        try {
            // 3. The settings page mounts the comms group and masks the secret chain. Sections
            //    are LAZY (zenit-cms 380f48f): a bare load renders only the first group's rows,
            //    so the comms channels section is named through ?section=, its JS-free lane.
            navigateToApp("/admin/settings?section=setting-" + CommsSettingsLabels.MOUNT_KEY + "-"
                + CommsSettings.Channels.CHANNELS.getName());
            waitForHydration();
            assertThat(page.locator("[data-path='comms.channels.mail_transports']").count())
                .as("step 3: the comms mount renders the mail transport chain setting")
                .isEqualTo(1);
            assertThat(page.locator("[data-path='framework.comms.channels.mail_transports']").count())
                .as("step 3: and the framework mount does not repeat it")
                .isZero();
            assertThat(page.content())
                .as("step 3: a stored transport DSN carries credentials and never renders in clear")
                .doesNotContain("zcm_test_token_never_shown");
        } finally {
            Zenit.SETTINGS_VALUES.setValue(CommsSettings.Channels.MAIL_TRANSPORTS, null);
        }
    }
}
