package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.common.brand.Brands;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.setting.SettingGroup;
import be.elevenways.zenit.notifications.NotificationSettings;
import be.elevenways.zenit.notifications.server.cms.NotificationSettingsLabels;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.function.Function;

/**
 * The operator sections of Hohenheim's settings page, in the order an operator reads them; zenit's own settings follow
 * behind "Framework (advanced)".
 *
 * AIDEV-NOTE: every group of {@link HohenheimSettings#HOHENHEIM} belongs to exactly ONE section here: the page's
 * framework mount leaves the whole Hohenheim group out once any section offers part of it, so zenit-cms refuses the
 * page at boot when a group is named by no section (SettingsPage) and {@code AdminSettingsMountsTest} pins it. A
 * section keeps every setting's stored path (the section key only prefixes the page's input names and anchors). The
 * resource sections (templates, images, git, engines, channels, backup targets, tasks) are the Settings cluster's
 * tabs, never settings here.
 *
 * AIDEV-NOTE: a section of several groups draws them in the order named here (SettingsPage.frameworkSection), and
 * the page opens on the first group of the first section: General, the proxy's group (what this server answers for
 * names it does not serve and which proxies stand in front of it, the boards' "General"). The mockups' order follows
 * (boards Settings, Settings-Proxy, Settings-Notifications); the sections they do not draw come after Blocking, the
 * installation's own plumbing last. The section list draws one row per section (HohenheimPanel, SECTION_ROWS).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum HohenheimSettingsSections {

    GENERAL("general", HohenheimMicrocopy.SETTINGS_SECTION.of("section_general"), HohenheimSettings.Proxy.GROUP),
    HTTPS("https", HohenheimMicrocopy.SETTINGS_SECTION.of("section_https"), HohenheimSettings.Ssl.GROUP),
    BACKUPS("backups", HohenheimMicrocopy.SETTINGS_SECTION.of("section_backups"),
        HohenheimSettings.Database.GROUP, HohenheimSettings.Backup.GROUP),
    NOTIFICATIONS(label -> SettingsPage.frameworkGroup(NotificationSettingsLabels.MOUNT_KEY, label, NotificationSettings.ROOT),
        "notifications", HohenheimMicrocopy.SETTINGS_SECTION.of("section_notifications")),
    DNS("dns", HohenheimMicrocopy.SETTINGS_SECTION.of("section_dns"), HohenheimSettings.Dns.GROUP),
    BLOCKING("blocking", HohenheimMicrocopy.SETTINGS_SECTION.of("section_blocking"),
        HohenheimSettings.Security.GROUP),
    APPS("apps", HohenheimMicrocopy.SETTINGS_SECTION.of("section_apps"),
        HohenheimSettings.Instances.GROUP, HohenheimSettings.Builds.GROUP, HohenheimSettings.Releases.GROUP,
        HohenheimSettings.Previews.GROUP, HohenheimSettings.Files.GROUP, HohenheimSettings.Sftp.GROUP,
        HohenheimSettings.Stacks.GROUP,
        HohenheimSettings.Quota.GROUP),
    HOSTS("hosts", HohenheimMicrocopy.SETTINGS_SECTION.of("section_hosts"),
        HohenheimSettings.Hosts.GROUP, HohenheimSettings.Capacity.GROUP, HohenheimSettings.Incus.GROUP),
    // How people sign in: the protected paths' sessions and the Proteus SSO login.
    SIGNIN("signin", HohenheimMicrocopy.SETTINGS_SECTION.of("section_signin"),
        HohenheimSettings.ProxyAuth.GROUP, HohenheimSettings.AuthProteus.GROUP),
    ABUSE(label -> new SettingsPage.Mount("spamservice", label, new SpamserviceSettingsBackend()), "abuse",
        HohenheimMicrocopy.SETTINGS_SECTION.of("section_abuse")),
    // The installation's name and look, and who runs it: zenit's brand group, offered here rather than behind
    // "Framework (advanced)" because the people an installation serves read it.
    BRAND(label -> SettingsPage.frameworkGroup("brand", label, Brands.BRAND), "brand",
        HohenheimMicrocopy.SETTINGS_SECTION.of("section_brand")),
    // The installation's own plumbing: its roles, where it keeps files, what it logs, how it confines host processes.
    INSTALLATION("installation", HohenheimMicrocopy.SETTINGS_SECTION.of("section_installation"),
        HohenheimSettings.Roles.GROUP, HohenheimSettings.Storage.GROUP, HohenheimSettings.Logging.GROUP,
        HohenheimSettings.Process.GROUP);

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

    /** A section over another settings home (the notifications channels, the spamservice backend) under its own key. */
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

    /** @return the icon heading the section (boards Settings and Settings-Notifications) */
    public @NonNull Icon icon() {
        return Icon.of(switch (this) {
            case GENERAL -> "sliders";
            case HTTPS -> "lock";
            case BACKUPS -> "box-archive";
            case NOTIFICATIONS -> "bell";
            case DNS -> "sitemap";
            case BLOCKING -> "shield-halved";
            case APPS -> "cubes";
            case HOSTS -> "server";
            case SIGNIN -> "id-badge";
            case ABUSE -> "robot";
            case BRAND -> "palette";
            case INSTALLATION -> "hard-drive";
        });
    }

    /** @return the slugs of the resources whose lists the section holds above its settings, in display order */
    public @NonNull List<String> records() {
        return switch (this) {
            case BACKUPS -> List.of(HohenheimSlugs.BACKUP_TARGETS);
            case NOTIFICATIONS -> List.of(HohenheimSlugs.NOTIFICATIONS);
            case GENERAL, HTTPS, DNS, BLOCKING, APPS, HOSTS, SIGNIN, ABUSE, BRAND, INSTALLATION -> List.of();
        };
    }

    /** @return whether the Settings cluster's other pages are listed right after this section */
    public boolean clusterMembersFollow() {
        return switch (this) {
            case NOTIFICATIONS -> true;
            case GENERAL, HTTPS, BACKUPS, DNS, BLOCKING, APPS, HOSTS, SIGNIN, ABUSE, BRAND, INSTALLATION -> false;
        };
    }

    /** @return the section's mount under its icon, or null when this boot never loaded the settings file it edits */
    public SettingsPage.@Nullable Mount mount() {
        SettingsPage.Mount mount;
        if (elsewhere != null) {
            mount = elsewhere.apply(label());
        } else {
            SettingGroup[] own = groups.toArray(SettingGroup[]::new);
            mount = own.length == 1
                ? SettingsPage.frameworkGroup(key, label(), own[0])
                : SettingsPage.frameworkSection(key, label(), own);
        }
        return mount == null ? null : mount.withIcon(icon());
    }

    /** @return the anchor of one of this section's groups on the settings page, for a deep link */
    public @NonNull String anchorOf(@NonNull SettingGroup group) {
        if (!groups.contains(group)) {
            throw new IllegalArgumentException("Section " + key + " does not offer group " + group.getPath());
        }
        return SettingsPage.anchor(key, groups.size() == 1 ? "" : group.getName());
    }
}
