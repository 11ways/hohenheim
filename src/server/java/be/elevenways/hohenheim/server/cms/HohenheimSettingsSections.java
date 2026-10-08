package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.common.setting.SettingGroup;
import be.elevenways.zenit.comms.CommsSettings;
import be.elevenways.zenit.comms.server.cms.CommsSettingsLabels;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.function.Function;

/**
 * The operator sections of Hohenheim's settings page, in the order an operator reads them; zenit's own settings follow
 * behind "Framework (advanced)".
 *
 * AIDEV-NOTE: every group of {@link HohenheimSettings#HOHENHEIM} belongs to exactly ONE section here
 * ({@code HohenheimSettingsSectionsTest} pins it): the page's framework mount leaves the whole Hohenheim group out once
 * any section offers part of it, so a group no section names would vanish from the page. A section keeps every
 * setting's stored path (the section key only prefixes the page's input names and anchors). The resource sections of
 * the boards (templates, images, git, engines, channels, backup targets, tasks) are the Settings cluster's tabs, never
 * settings here.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum HohenheimSettingsSections {

    GENERAL("general", Microcopy.of("section_general").withFilter("scope", "settings_section"),
        HohenheimSettings.Roles.GROUP, HohenheimSettings.Storage.GROUP, HohenheimSettings.Logging.GROUP,
        HohenheimSettings.Process.GROUP),
    HTTPS("https", Microcopy.of("section_https").withFilter("scope", "settings_section"), HohenheimSettings.Ssl.GROUP),
    BACKUPS("backups", Microcopy.of("section_backups").withFilter("scope", "settings_section"),
        HohenheimSettings.Database.GROUP, HohenheimSettings.Backup.GROUP),
    NOTIFICATIONS(label -> SettingsPage.frameworkGroup(CommsSettingsLabels.MOUNT_KEY, label, CommsSettings.ROOT),
        "notifications", Microcopy.of("section_notifications").withFilter("scope", "settings_section")),
    APPS("apps", Microcopy.of("section_apps").withFilter("scope", "settings_section"),
        HohenheimSettings.Instances.GROUP, HohenheimSettings.Builds.GROUP, HohenheimSettings.Releases.GROUP,
        HohenheimSettings.Previews.GROUP, HohenheimSettings.Files.GROUP, HohenheimSettings.Sftp.GROUP,
        HohenheimSettings.Stacks.GROUP,
        HohenheimSettings.Quota.GROUP),
    HOSTS("hosts", Microcopy.of("section_hosts").withFilter("scope", "settings_section"),
        HohenheimSettings.Hosts.GROUP, HohenheimSettings.Capacity.GROUP, HohenheimSettings.Incus.GROUP),
    DNS("dns", Microcopy.of("section_dns").withFilter("scope", "settings_section"), HohenheimSettings.Dns.GROUP),
    BLOCKING("blocking", Microcopy.of("section_blocking").withFilter("scope", "settings_section"),
        HohenheimSettings.Security.GROUP),
    ABUSE(label -> new SettingsPage.Mount("spamservice", label, new SpamserviceSettingsBackend()), "abuse",
        Microcopy.of("section_abuse").withFilter("scope", "settings_section")),
    PROXY("proxy", Microcopy.of("section_proxy").withFilter("scope", "settings_section"),
        HohenheimSettings.Proxy.GROUP, HohenheimSettings.ProxyAuth.GROUP,
        HohenheimSettings.AuthProteus.GROUP);

    private final String key;
    private final Microcopy label;
    private final List<SettingGroup> groups;
    private final @Nullable Function<Microcopy, SettingsPage.@Nullable Mount> elsewhere;

    /** A section over groups of Hohenheim's own settings, its key the mount key. */
    HohenheimSettingsSections(@NonNull String key, @NonNull Microcopy label, @NonNull SettingGroup... groups) {
        this.key = key;
        this.label = label;
        this.groups = List.of(groups);
        this.elsewhere = null;
    }

    /** A section over another settings home (comms' channels, the spamservice backend) under its own mount key. */
    HohenheimSettingsSections(@NonNull Function<Microcopy, SettingsPage.@Nullable Mount> mount, @NonNull String key,
                              @NonNull Microcopy label) {
        this.key = key;
        this.label = label;
        this.groups = List.of();
        this.elsewhere = mount;
    }

    /** @return the groups of Hohenheim's own settings this section offers; none for a section over another home */
    public @NonNull List<SettingGroup> groups() {
        return groups;
    }

    /** @return the section's heading */
    public @NonNull Microcopy label() {
        return label;
    }

    /** @return the section's mount, or null when this boot never loaded the settings file it edits */
    public SettingsPage.@Nullable Mount mount() {
        if (elsewhere != null) {
            return elsewhere.apply(label());
        }
        SettingGroup[] own = groups.toArray(SettingGroup[]::new);
        return own.length == 1
            ? SettingsPage.frameworkGroup(key, label(), own[0])
            : SettingsPage.frameworkSection(key, label(), own);
    }

    /** @return the anchor of one of this section's groups on the settings page, for a deep link */
    public @NonNull String anchorOf(@NonNull SettingGroup group) {
        if (!groups.contains(group)) {
            throw new IllegalArgumentException("Section " + key + " does not offer group " + group.getPath());
        }
        return SettingsPage.anchor(key, groups.size() == 1 ? "" : group.getName());
    }
}
