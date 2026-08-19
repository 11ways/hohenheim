package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSources;
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

    /**
     * THE admin permission, aliased from the common constant so the two faces (common
     * sources, server panel) can never spell it differently.
     */
    public static final Permission ACCESS = HohenheimSources.ADMIN_ACCESS;

    // AIDEV-NOTE: sidebar order is these weights, ASCENDING (PanelNav.sections). It is
    // ordered by WHAT THIS PRODUCT IS FOR, not alphabetically and not by module: Compute
    // and Proxy are the daily surfaces, Infrastructure supports them, and Security --
    // eleven items, eight of them contributed by the spamservice module -- is a background
    // concern that used to open the sidebar and push Compute below the fold on a 1440x900
    // screen. Do not re-weight a group ANOTHER module declares by re-declaring its id here:
    // NavGroup.equals is id-only and PanelNav keeps the first instance it sees, so the
    // winner would depend on peer declaration order.
    //
    // AIDEV-NOTE: the sidebar is CURATED, not a schema dump. 39 visible entries became 20:
    // a peer stays visible only when an operator would go LOOKING for it by name. Every
    // demoted peer is showInNav(false) -- which removes the sidebar entry and NOTHING else,
    // the route and the record stay live -- and each one has a declared home: a record tab
    // on its parent (instance snapshots/backups), a HeaderAction on the parent list
    // (backup targets, quotas, game domains, auth providers, previews, build/release
    // history, reconcile findings, environments, DNS peers), or a link on the surface that
    // owns it (the spamservice sub-resources, off the abuse-protection overview). A peer
    // whose demotion target does not exist stays VISIBLE with a description rather than
    // becoming unreachable. Every visible peer carries a unique navOrder inside its group
    // and a description() -- ties made the order depend on declaration order, and a bare
    // label is what made this panel read as a table list.
    //
    // AIDEV-NOTE: the separator sits before SECURITY, not before SYSTEM, on purpose. It
    // marks the same boundary (the daily product surfaces end here, background concerns
    // follow) but declares it on a group THIS class owns. NavGroup.SYSTEM is zenit-cms's
    // constant: re-declaring its id from here to add a fact would make the winning instance
    // depend on peer declaration order, which the note above forbids.

    /** Compute group: the container/VM management surface -- instances, templates,
     *  quotas, snapshots, backups, backup targets, game domains. Servers and reconcile
     *  findings stay in Infrastructure deliberately (host inventory, not workloads). */
    public static final NavGroup COMPUTE_GROUP =
        NavGroup.of("compute", Microcopy.of("compute").withFilter("scope", "nav"), 150, Icon.of("cubes"));

    /** Proxy configuration group: sites, certificates, access control. */
    public static final NavGroup PROXY_GROUP =
        NavGroup.of("proxy", Microcopy.of("proxy").withFilter("scope", "nav"), 200, Icon.of("globe"));

    /** Infrastructure group: databases, servers, notifications. */
    public static final NavGroup INFRA_GROUP =
        NavGroup.of("infra", Microcopy.of("infra").withFilter("scope", "nav"), 250, Icon.of("server"));

    /** Security group: abuse protection, IP bans, users and roles; opens the background tail. */
    public static final NavGroup SECURITY_GROUP =
        NavGroup.of("security", Microcopy.of("security").withFilter("scope", "nav"), 800, Icon.of("shield-halved"))
            .withSeparatorBefore(true);

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
        // Projects/environments span every product tier (sites, instances, databases
        // through their sites), so they are not gated on any single role.
        peers.add(new ProjectResource());
        peers.add(new EnvironmentResource());
        peers.add(new EnvironmentVariableResource());
        addIf(peers, new SiteResource(), Role.PROXY);
        addIf(peers, new SiteDomainResource(), Role.PROXY);
        addIf(peers, new ReleasedClaimResource(), Role.PROXY);
        addIf(peers, new SiteDatabaseResource(), Role.DATABASES);
        addIf(peers, new CertificateResource(), Role.PROXY);
        addIf(peers, new AccessListResource(), Role.PROXY);
        addIf(peers, new AuthProviderResource(), Role.PROXY);
        addIf(peers, new DatabaseResource(), Role.DATABASES);
        // Needs BOTH tiers to exist: it joins an instance to a managed database.
        if (HohenheimRoles.enabled(Role.DATABASES) && HohenheimRoles.enabled(Role.INSTANCES)) {
            peers.add(new InstanceDatabaseResource());
        }
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
        addIf(peers, new InstanceDeviceResource(), Role.INSTANCES);
        addIf(peers, new InstanceScheduleRunResource(), Role.INSTANCES);
        addIf(peers, new GameDomainResource(), Role.INSTANCES);
        addIf(peers, new BackupTargetResource(), Role.INSTANCES);
        // Build history serves the two tiers that produce images today (Docker sites
        // through the proxy role, container instances through the instances role).
        addIf(peers, new BuildOperationResource(), Role.PROXY, Role.INSTANCES);
        // Release history: today only Docker sites release through the health gate.
        addIf(peers, new ReleaseOperationResource(), Role.PROXY);
        addIf(peers, new GitProviderResource(), Role.PROXY);
        addIf(peers, new PreviewDeploymentResource(), Role.PROXY);
        addIf(peers, new StackResource(), Role.STACKS);
        addIf(peers, new StackServiceResource(), Role.STACKS);
        addIf(peers, new StackFileResource(), Role.STACKS);
        // The host inventory serves stacks, managed databases AND the instance
        // tier: instance placement is gated on an ADMITTED host, and admit/
        // preflight/trust live on this resource -- an instances-only node
        // without it cannot place anything.
        addIf(peers, new ServerResource(), Role.STACKS, Role.DATABASES, Role.INSTANCES);
        addIf(peers, new ReconcileFindingResource(), Role.STACKS, Role.DATABASES, Role.INSTANCES);
        addIf(peers, new DnsZoneResource(), Role.DNS);
        addIf(peers, new DnsRecordResource(), Role.DNS);
        addIf(peers, new DnsPeerResource(), Role.DNS);
        addIf(peers, new DnsZonePeerResource(), Role.DNS);
        peers.add(new NotificationChannelResource());
        addIf(peers, new BanResource(), Role.FIREWALL);
        // zenit-auth's generated admin resources, wired into THIS panel (the
        // module's own default panel is disabled via auth.cms.auto_panel).
        // AIDEV-NOTE: no description() reaches these two -- AuthUsersResource and
        // AuthRolesResource are FINAL and expose no description seam, so their sidebar
        // entries stay label-only until zenit-auth grows one. Everything else visible in
        // this panel carries one (AdminNavigationJourneyTest step 2 skips exactly these).
        peers.add(new AuthUsersResource(SECURITY_GROUP, 30));
        peers.add(new AuthRolesResource(SECURITY_GROUP, 40));
        addIf(peers, new SpamserviceOverviewPage(), Role.FIREWALL);
        addIf(peers, new SpamserviceInstallationResource(), Role.FIREWALL);
        addIf(peers, new SpamserviceSamplesResource(), Role.FIREWALL);
        addIf(peers, new SpamserviceClientsResource(), Role.FIREWALL);
        addIf(peers, new SpamserviceClientKeysResource(), Role.FIREWALL);
        addIf(peers, new SpamserviceSecurityEventsResource(), Role.FIREWALL);
        addIf(peers, new SpamserviceWordsResource(), Role.FIREWALL);
        addIf(peers, new SpamserviceReputationPage(), Role.FIREWALL);
        peers.add(new AdminActivityResource());
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
        return new AdminSettingsPage(mounts);
    }
}
