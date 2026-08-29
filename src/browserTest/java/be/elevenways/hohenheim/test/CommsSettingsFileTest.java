package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.HohenheimCommsSettings;
import be.elevenways.zenit.comms.CommsSettings;
import be.elevenways.zenit.server.setting.SettingsEditor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The comms transport chain is configured through zenit-comms' OWN settings file, and the
 * admin settings page edits it; a key in local.dry never reaches the dispatcher.
 */
class CommsSettingsFileTest extends HohenheimTestBase {

    private static final String DSN = "hub://zcm_test_token_never_shown@127.0.0.1:1?insecure=true";

    @Test
    void theCommsFileFeedsTheDispatcherContextAndTheSettingsPageEditsIt() throws Exception {
        Path file = HohenheimCommsSettings.settingsFile();

        // 1. The boot created the editable file, so the panel's comms mount can locate it.
        assertThat(file).as("the comms settings file exists after boot").exists();
        assertThatCode(() -> SettingsEditor.forFile(CommsSettings.VALUES, file))
            .as("the comms context loaded that file as its editable source")
            .doesNotThrowAnyException();

        // 2. A transport chain written to the file lands in zenit-comms' context on load.
        Files.writeString(file, "{\"channels\": {\"mail_transports\": \"" + DSN + "\"}}\n");
        HohenheimCommsSettings.load();
        assertThat(CommsSettings.VALUES.getValue(CommsSettings.Channels.MAIL_TRANSPORTS))
            .as("the mail chain is read from the comms file, keys relative to the comms group")
            .isEqualTo(DSN);

        // 3. The settings page mounts the comms context and masks the secret chain.
        navigateToApp("/admin/settings");
        waitForHydration();
        assertThat(page.locator("[data-path='comms.channels.mail_transports']").count())
            .as("the comms mount renders the mail transport chain setting")
            .isEqualTo(1);
        assertThat(page.content())
            .as("a stored transport DSN carries credentials and never renders in clear")
            .doesNotContain("zcm_test_token_never_shown");
    }
}
