package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimRoles.Role;
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

    /** Below ManagePanel's default 100: an operator holding both panels lands on /admin. */
    @Override
    public int landingWeight() {
        return 50;
    }

    /**
     * AIDEV-NOTE: role filtering happens HERE, never in nav visibility: dispatch
     * resolves through the memoized peersBySlug, so a peer this method omits has
     * no ROUTE either -- /admin/stacks 404s on a stacks-less node instead of
     * being merely hidden. peers() memoizes for the panel's lifetime, matching
     * the boot-captured HohenheimRoles snapshot these gates read.
     */
    @Override
    public List<PanelPeer> buildPeers() {
        List<PanelPeer> peers = new ArrayList<>();
        // The dashboard comes first: the panel landing soft-redirects to the
        // first accessible DashboardPanelPeer.
        peers.add(new AdminDashboard());
        addIf(peers, new SiteResource(), Role.PROXY);
        addIf(peers, new SiteDomainResource(), Role.PROXY);
        addIf(peers, new ReleasedClaimResource(), Role.PROXY);
        addIf(peers, new SiteDatabaseResource(), Role.DATABASES);
        addIf(peers, new CertificateResource(), Role.PROXY);
        addIf(peers, new AccessListResource(), Role.PROXY);
        addIf(peers, new AuthProviderResource(), Role.PROXY);
        addIf(peers, new DatabaseResource(), Role.DATABASES);
        addIf(peers, new InstanceResource(), Role.INSTANCES);
        addIf(peers, new InstanceTemplateResource(), Role.INSTANCES);
        addIf(peers, new InstanceTemplateVariableResource(), Role.INSTANCES);
        addIf(peers, new InstanceTemplateFileResource(), Role.INSTANCES);
        addIf(peers, new InstanceFileResource(), Role.INSTANCES);
        addIf(peers, new InstanceFromTemplatePage(), Role.INSTANCES);
        addIf(peers, new InstanceTemplateImportPage(), Role.INSTANCES);
        addIf(peers, new InstanceQuotaResource(), Role.INSTANCES);
        addIf(peers, new InstanceSnapshotResource(), Role.INSTANCES);
        addIf(peers, new InstanceBackupResource(), Role.INSTANCES);
        addIf(peers, new InstanceScheduleResource(), Role.INSTANCES);
        addIf(peers, new InstanceScheduleStepResource(), Role.INSTANCES);
        addIf(peers, new InstanceScheduleRunResource(), Role.INSTANCES);
        addIf(peers, new GameDomainResource(), Role.INSTANCES);
        addIf(peers, new BackupTargetResource(), Role.INSTANCES);
        addIf(peers, new StackResource(), Role.STACKS);
        addIf(peers, new StackServiceResource(), Role.STACKS);
        addIf(peers, new StackFileResource(), Role.STACKS);
        // The Docker host inventory serves stacks AND managed databases.
        addIf(peers, new ServerResource(), Role.STACKS, Role.DATABASES);
        addIf(peers, new ReconcileFindingResource(), Role.STACKS, Role.DATABASES, Role.INSTANCES);
        addIf(peers, new DnsZoneResource(), Role.DNS);
        addIf(peers, new DnsRecordResource(), Role.DNS);
        addIf(peers, new DnsPeerResource(), Role.DNS);
        addIf(peers, new DnsZonePeerResource(), Role.DNS);
        peers.add(new NotificationChannelResource());
        addIf(peers, new BanResource(), Role.FIREWALL);
        // zenit-auth's generated admin resources, wired into THIS panel (the
        // module's own default panel is disabled via auth.cms.auto_panel).
        peers.add(new AuthUsersResource(SECURITY_GROUP, 2));
        peers.add(new AuthRolesResource(SECURITY_GROUP, 3));
        addIf(peers, new SpamserviceOverviewPage(), Role.FIREWALL);
        addIf(peers, new SpamserviceInstallationResource(), Role.FIREWALL);
        addIf(peers, new SpamserviceSamplesResource(), Role.FIREWALL);
        addIf(peers, new SpamserviceClientsResource(), Role.FIREWALL);
        addIf(peers, new SpamserviceClientKeysResource(), Role.FIREWALL);
        addIf(peers, new SpamserviceSecurityEventsResource(), Role.FIREWALL);
        addIf(peers, new SpamserviceWordsResource(), Role.FIREWALL);
        addIf(peers, new SpamserviceReputationPage(), Role.FIREWALL);
        peers.add(new ActivityResource());
        SettingsPage settings = settingsPage();
        if (settings != null) {
            peers.add(settings);
        }
        addIf(peers, new CertificateRequestPage(), Role.PROXY);
        return peers;
    }

    /** Adds the peer only when at least one of its owning roles is enabled. */
    private static void addIf(List<PanelPeer> peers, PanelPeer peer, Role... roles) {
        if (HohenheimRoles.anyEnabled(roles)) {
            peers.add(peer);
        }
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
