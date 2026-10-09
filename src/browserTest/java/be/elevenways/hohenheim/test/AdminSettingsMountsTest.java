package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.server.cms.HohenheimSettingsSections;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolvers;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.common.setting.SettingGroup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin settings page reads as an operator's: Hohenheim's own sections first, in the boards' order, each gathering
 * the groups that answer one question, and zenit's own settings behind one "Framework (advanced)" disclosure.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class AdminSettingsMountsTest extends HohenheimTestBase {

    private static final String NAV = ".cms-settings-nav";

    @Test
    void operatorSectionsComeFirstAndTheFrameworkWaitsBehindItsDisclosure() throws Exception {
        // 1. Every group of Hohenheim's settings belongs to exactly one section: the framework mount leaves the whole
        //    Hohenheim group out, so a group no section names would vanish from the page.
        List<SettingGroup> offered = new ArrayList<>();
        for (HohenheimSettingsSections section : HohenheimSettingsSections.values()) {
            offered.addAll(section.groups());
        }
        assertThat(offered).as("step 1: each Hohenheim group is offered by exactly one section")
            .doesNotHaveDuplicates()
            .containsExactlyInAnyOrderElementsOf(HohenheimSettings.HOHENHEIM.getChildGroups().values());

        // 2. The peer is the standard page in the System group, its operator sections first and the framework mount
        //    last and advanced.
        Panel admin = PanelRegistry.getBySlug(HohenheimSlugs.ADMIN);
        assertThat(admin).as("step 2: the admin panel is registered").isNotNull();
        PanelEntry peer = admin.entryBySlug(SettingsPage.DEFAULT_SLUG);
        assertThat(peer).as("step 2: the standard page, never a subclass").isExactlyInstanceOf(SettingsPage.class);
        assertThat(peer.navGroup()).as("step 2: the settings entry sits in the System group")
            .isSameAs(NavGroup.SYSTEM);
        List<SettingsPage.Mount> mounts = ((SettingsPage) peer).mounts();
        assertThat(mounts).extracting(SettingsPage.Mount::key).as("step 2: the sections in the boards' order")
            .containsExactly("general", "https", "backups", "comms", "apps", "hosts", "dns", "blocking",
                "spamservice", "proxy", SettingsPage.FRAMEWORK_MOUNT_KEY);
        assertThat(mounts).filteredOn(SettingsPage.Mount::advanced).extracting(SettingsPage.Mount::key)
            .as("step 2: only the framework mount is advanced").containsExactly(SettingsPage.FRAMEWORK_MOUNT_KEY);

        // 3. The page lists the operator sections and folds the framework's groups into one disclosure.
        navigateToApp("/admin/settings");
        waitForHydration();
        List<String> sections = page.locator(NAV + " a.cms-settings-nav-item[data-depth='0']:not([hidden])")
            .allInnerTexts().stream().map(String::trim).toList();
        assertThat(sections).as("step 3: the operator sections, in order")
            .startsWith("General", "HTTPS and certificates", "Backups", "Notifications", "Apps and deploys", "Hosts",
                "DNS server", "Blocking and firewall", "Abuse protection", "Proxy");
        String toggle = NAV + " [data-cms-settings-nav-mount='" + SettingsPage.FRAMEWORK_MOUNT_KEY + "']";
        assertThat(page.locator(toggle).innerText().trim()).as("step 3: one disclosure names the framework")
            .isEqualTo("Framework (advanced)");
        assertThat(page.locator(toggle).getAttribute("aria-expanded")).as("step 3: folded").isEqualTo("false");
        String frameworkRows = NAV + " a[data-cms-settings-nav^='setting-framework-']";
        assertThat(page.locator(frameworkRows + ":not([hidden])").count())
            .as("step 3: no framework group is listed while folded").isZero();

        // 4. Opening the disclosure lists the framework's groups.
        page.click(toggle);
        waitForAttribute(toggle, "aria-expanded", "true");
        assertThat(page.locator(frameworkRows + ":not([hidden])").count())
            .as("step 4: the framework's groups are listed once opened").isPositive();

        // 5. A deep link reaches a group of each kind of section, a framework group included while folded.
        String backups = HohenheimSettingsSections.BACKUPS.anchorOf(HohenheimSettings.Database.GROUP);
        assertThat(backups).as("step 5: the backups section anchors its database group")
            .isEqualTo("setting-backups-database");
        navigateToApp("/admin/settings?section=setting-https," + backups
            + ",setting-framework-network,setting-comms-channels");
        waitForHydration();
        assertThat(page.locator("[data-path='https.letsencrypt_enabled']").count())
            .as("step 5: a one-group section offers its rows under the section key").isEqualTo(1);
        assertThat(page.locator("[data-path='backups.database.control_plane_backup_target']").count())
            .as("step 5: a section of several groups keeps each group's path").isEqualTo(1);
        assertThat(page.locator("[data-path='comms.channels.mail_transports']").count())
            .as("step 5: the notifications section is comms' own mount").isEqualTo(1);
        assertThat(page.locator("#setting-framework-network").isVisible())
            .as("step 5: a requested framework group shows although the disclosure is folded").isTrue();
        assertThat(page.locator("[data-path='framework.network.request_body_size_limit']").count())
            .as("step 5: the framework keeps its shared key").isEqualTo(1);

        // 6. Every group Hohenheim's sections offer is listed with its icon (board Settings), a one-group section's
        //    own row included.
        List<String> bare = new ArrayList<>();
        for (HohenheimSettingsSections section : HohenheimSettingsSections.values()) {
            for (SettingGroup group : section.groups()) {
                String row = NAV + " [data-cms-settings-nav='" + section.anchorOf(group) + "'] pl-icon";
                if (group.getIcon() == null || page.locator(row).count() != 1) {
                    bare.add(section.anchorOf(group));
                }
            }
        }
        assertThat(bare).as("step 6: no Hohenheim settings row is listed without its icon").isEmpty();

        // 7. The Settings cluster names its record sections as the board does: App templates and Git connections.
        for (String[] expected : List.of(
                new String[]{HohenheimSlugs.INSTANCE_TEMPLATES, "en", "App templates"},
                new String[]{HohenheimSlugs.INSTANCE_TEMPLATES, "nl", "App-sjablonen"},
                new String[]{HohenheimSlugs.GIT_PROVIDERS, "en", "Git connections"},
                new String[]{HohenheimSlugs.GIT_PROVIDERS, "nl", "Git-koppelingen"})) {
            assertThat(admin.entryBySlug(expected[0]).label()
                    .tryResolve(LocaleChain.ofTags(expected[1]), MessageResolvers.getDefault()))
                .as("step 7: the '" + expected[0] + "' tab reads as the board names it (" + expected[1] + ")")
                .isEqualTo(expected[2]);
        }
    }
}
