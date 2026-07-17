package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimSettingsFiles;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
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
 * CMS panel served at /admin. Constructed explicitly in ServerMain (not
 * {@code @ZenitAutoLoad}) because its resources reach server services.
 */
public final class HohenheimPanel extends Panel {

    public static final Permission ACCESS = Permission.of("hohenheim.admin.access");

    /** Proxy configuration group: sites, certificates, access control. */
    public static final NavGroup PROXY_GROUP =
        NavGroup.of("proxy", Microcopy.of("hohenheim.nav.proxy"), 300, Icon.of("globe"));

    /** Infrastructure group: databases, servers, notifications. */
    public static final NavGroup INFRA_GROUP =
        NavGroup.of("infra", Microcopy.of("hohenheim.nav.infra"), 200, Icon.of("server"));

    public HohenheimPanel() {
        super(Identifier.of("hohenheim", "admin"), "admin", Microcopy.of("hohenheim.admin.title"), ACCESS);
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
        peers.add(new ServerResource());
        peers.add(new DnsZoneResource());
        peers.add(new DnsRecordResource());
        peers.add(new DnsPeerResource());
        peers.add(new DnsZonePeerResource());
        peers.add(new NotificationChannelResource());
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
                Microcopy.of("Hohenheim").withFallback("Hohenheim"), appEditor));
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
        if (mounts.isEmpty()) {
            return null;
        }
        return new SettingsPage(
            Identifier.of("hohenheim", "settings"), "settings",
            Microcopy.of("hohenheim.settings.title"), Icon.of("gear"), mounts);
    }
}
