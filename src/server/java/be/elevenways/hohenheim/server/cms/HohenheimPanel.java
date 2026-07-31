package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimSettingsFiles;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.server.cms.AuthRolesResource;
import be.elevenways.zenit.auth.server.cms.AuthUsersResource;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.cms.common.resource.ActivityResource;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.setting.ServerSettings;
import be.elevenways.zenit.server.setting.SettingsEditor;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * CMS panel served at /admin. Constructed by the discovered
 * {@code HohenheimHostWiring} module at the MODULES boot stage (not
 * {@code @ZenitAutoLoad}) because its resources reach server services, and
 * because the registration must be complete before STARTHTTP binds.
 */
public final class HohenheimPanel extends Panel {

    public static final Permission ACCESS = Permission.of("hohenheim.admin.access");

    /** Proxy configuration group: sites, certificates, access control. */
    public static final NavGroup PROXY_GROUP =
        NavGroup.of("proxy", Microcopy.of("proxy").withFilter("scope", "nav"), 300, Icon.of("globe"));

    /** Infrastructure group: databases, servers, notifications. */
    public static final NavGroup INFRA_GROUP =
        NavGroup.of("infra", Microcopy.of("infra").withFilter("scope", "nav"), 200, Icon.of("server"));

    /** Security group: IP bans. */
    public static final NavGroup SECURITY_GROUP =
        NavGroup.of("security", Microcopy.of("security").withFilter("scope", "nav"), 150, Icon.of("shield-halved"));

    public HohenheimPanel() {
        super(Identifier.of("hohenheim", "admin"), "admin", Microcopy.of("title").withFilter("scope", "admin"), ACCESS);
    }

    @Override
    public List<PanelPeer> buildPeers() {
        List<PanelPeer> peers = new ArrayList<>();
        // The dashboard comes first: the panel landing soft-redirects to the
        // first accessible DashboardPanelPeer.
        peers.add(new AdminDashboard());
        peers.add(new SiteResource());
        peers.add(new SiteDomainResource());
        peers.add(new SiteDatabaseResource());
        peers.add(new CertificateResource());
        peers.add(new AccessListResource());
        peers.add(new AuthProviderResource());
        peers.add(new DatabaseResource());
        peers.add(new StackResource());
        peers.add(new StackServiceResource());
        peers.add(new StackFileResource());
        peers.add(new ServerResource());
        peers.add(new DnsZoneResource());
        peers.add(new DnsRecordResource());
        peers.add(new DnsPeerResource());
        peers.add(new DnsZonePeerResource());
        peers.add(new NotificationChannelResource());
        peers.add(new BanResource());
        // zenit-auth's generated admin resources, wired into THIS panel (the
        // module's own default panel is disabled via auth.cms.auto_panel).
        peers.add(new AuthUsersResource(SECURITY_GROUP, 2));
        peers.add(new AuthRolesResource(SECURITY_GROUP, 3));
        peers.add(new SpamserviceOverviewPage());
        peers.add(new SpamserviceInstallationResource());
        peers.add(new SpamserviceSamplesResource());
        peers.add(new SpamserviceClientsResource());
        peers.add(new SpamserviceClientKeysResource());
        peers.add(new SpamserviceSecurityEventsResource());
        peers.add(new SpamserviceWordsResource());
        peers.add(new SpamserviceReputationPage());
        peers.add(new ActivityResource());
        SettingsPage settings = settingsPage();
        if (settings != null) {
            peers.add(settings);
        }
        peers.add(new CertificateRequestPage());
        return peers;
    }

    /**
     * The framework settings editor: Hohenheim's own settings file plus
     * zenit's server settings ({@code settings/local.dry}). Each mount only
     * appears when this boot actually loaded its editable file, so the panel
     * never breaks over a missing settings source (test boots load others).
     * Mounts are subtree-scoped by context ownership, so the framework mount
     * skips the hohenheim group and vice versa.
     */
    private static @Nullable SettingsPage settingsPage() {
        List<SettingsPage.Mount> mounts = new ArrayList<>();
        try {
            SettingsEditor appEditor = SettingsEditor.forFile(
                HohenheimSettings.VALUES, HohenheimSettingsFiles.settingsFile());
            mounts.add(new SettingsPage.Mount("app",
                Microcopy.literal("Hohenheim"), appEditor));
        } catch (IllegalArgumentException notLoaded) {
            // Boot without the hohenheim settings file: framework mount only.
        }
        try {
            SettingsEditor frameworkEditor = SettingsEditor.forFile(
                ServerSettings.VALUES, ServerZenitRuntime.PATH_ROOT.resolve("settings/local.dry"));
            mounts.add(new SettingsPage.Mount("framework",
                Microcopy.of("framework").withFallback("Framework"), frameworkEditor));
        } catch (IllegalArgumentException notLoaded) {
            // Boot without the standard zenit chain: app settings only.
        }
        mounts.add(new SettingsPage.Mount("spamservice",
            Microcopy.literal("Spamservice"), new SpamserviceSettingsBackend()));
        if (mounts.isEmpty()) {
            return null;
        }
        return new SettingsPage(
            Identifier.of("hohenheim", "settings"), "settings",
            Microcopy.of("title").withFilter("scope", "settings"), Icon.of("gear"), mounts);
    }
}
