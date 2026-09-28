package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin settings page is zenit-cms's standard page, placed in the System group, offering its four mounts with
 * the framework one appended by the standard page under the key every host shares.
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

        // 2. The peer is the standard page itself, placed and described by its options, the framework
        //    mount appended last.
        Panel admin = PanelRegistry.getBySlug(HohenheimSlugs.ADMIN);
        assertThat(admin).as("step 2: the admin panel is registered").isNotNull();
        PanelPeer peer = admin.peerBySlug(SettingsPage.DEFAULT_SLUG);
        assertThat(peer).as("step 2: the standard page, never a subclass").isExactlyInstanceOf(SettingsPage.class);
        assertThat(peer.navGroup()).as("step 2: the settings entry sits in the System group")
            .isSameAs(NavGroup.SYSTEM);
        assertThat(peer.description()).as("step 2: the settings entry carries its nav hint").isNotNull();
        List<String> keys = ((SettingsPage) peer).mounts().stream().map(SettingsPage.Mount::key).toList();
        assertThat(keys).as("step 2: the host mounts in order, the framework mount appended last")
            .containsExactly("app", "comms", "spamservice", SettingsPage.FRAMEWORK_MOUNT_KEY);

        // 3. One page load expanding a group of each file-backed mount renders all three.
        navigateToApp("/admin/settings?section="
            + "setting-app-proxy,setting-framework-network,setting-comms-channels");
        waitForHydration();
        assertThat(page.locator("[data-path='app.proxy.http_port']").count())
            .as("step 3: the hohenheim mount renders its proxy group").isEqualTo(1);
        assertThat(page.locator("[data-path='framework.network.request_body_size_limit']").count())
            .as("step 3: the framework mount renders zenit's network group under its shared key")
            .isEqualTo(1);
        assertThat(page.locator("[data-path='comms.channels.mail_transports']").count())
            .as("step 3: the comms mount renders its transport chain").isEqualTo(1);

        // 4. The spamservice backend is mounted too, and the framework mount is labelled by
        //    the framework's own vocabulary.
        String content = page.content();
        assertThat(content).as("step 4: the spamservice mount is offered").contains("Spamservice");
        assertThat(content).as("step 4: the framework mount carries SettingsLabels.framework()")
            .contains("Framework");
    }
}
