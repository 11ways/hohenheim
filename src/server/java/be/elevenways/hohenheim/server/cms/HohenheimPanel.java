package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimRoles.Role;
import be.elevenways.hohenheim.server.HohenheimCommsSettings;
import be.elevenways.hohenheim.server.HohenheimSettingsFiles;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.server.cms.AuthRolesResource;
import be.elevenways.zenit.auth.server.cms.AuthUsersResource;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.cms.server.page.BuildInfoPage;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.comms.CommsSettings;
import be.elevenways.zenit.comms.server.cms.CommsSettingsLabels;
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
    // ordered by WHAT THIS PRODUCT IS FOR, not alphabetically and not by module: Deploy
    // and Networking are the daily surfaces, and Security -- eleven items, eight of them
    // contributed by the spamservice module -- is a background concern that used to open
    // the sidebar and push the workload surfaces below the fold on a 1440x900 screen. Do
    // not re-weight a group ANOTHER module declares by re-declaring its id here:
    // NavGroup.equals is id-only and PanelNav keeps the first instance it sees, so the
    // winner would depend on peer declaration order.
    //
    // AIDEV-NOTE: these groups are named after the OPERATOR'S JOB, not after the tiers the
    // code is split into. The predecessors (Compute / Proxy / Infrastructure) were the
    // module map read out loud: they told an operator which subsystem a resource belonged
    // to, never which task it belonged to, and "Infrastructure" ended up meaning "the rest".
    // Deploy = everything you create to make something RUN, Networking = how traffic
    // reaches it, Security = who may act, System = the installation's own record. A new
    // resource picks the group by the question its operator is asking, and a resource that
    // fits none of the four is a signal the taxonomy is wrong -- not a reason for a fifth
    // catch-all.
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
    // AIDEV-NOTE: the separator STAYS before SECURITY after the regrouping, and it is still
    // the only one. Section headings now carry their own visual register (they share
    // pl-nav-label's, with the icon gutter reserved), so a rule between EVERY group would be
    // noise on top of a boundary the heading already draws; the one boundary a heading
    // cannot express is the change of SUBJECT -- Deploy and Networking are what you operate,
    // Security and System are the installation administering itself. It is declared on a
    // group THIS class owns: NavGroup.SYSTEM is zenit-cms's constant, and re-declaring its
    // id from here to add a fact would make the winning instance depend on peer declaration
    // order, which the note above forbids.
    //
    // AIDEV-NOTE: the System section therefore renders WITHOUT an icon while the other three
    // labelled groups carry one. That is the framework constant's shape, not an oversight --
    // and it aligns correctly because the nav reserves the icon gutter for labelled groups
    // either way. Giving it one means adding an Icon to NavGroup.SYSTEM in zenit-cms, never
    // shadowing the id from here.

    /** Deploy group: everything an operator creates to make something RUN -- projects,
     *  sites, instances, stacks, databases, and the templates and git providers they are
     *  built from. Servers are deliberately NOT here: a host is not a workload, it is the
     *  installation itself, so it sits in the ungrouped top block beside the dashboard. */
    public static final NavGroup DEPLOY_GROUP =
        NavGroup.of("deploy", Microcopy.of("deploy").withFilter("scope", "nav"), 150, Icon.of("rocket"));

    /** Networking group: how traffic REACHES those workloads -- DNS, certificates, access
     *  control, and the cooldown that holds a released hostname out of circulation. */
    public static final NavGroup NETWORK_GROUP =
        NavGroup.of("networking", Microcopy.of("networking").withFilter("scope", "nav"), 200,
            Icon.of("network-wired"));

    /** Security group: who may act and who is refused -- users, roles, abuse protection,
     *  IP bans; opens the background tail. */
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
        addIf(peers, new CertificateResource(), Role.PROXY);
        addIf(peers, new AccessListResource(), Role.PROXY);
        addIf(peers, new AccessRuleResource(), Role.PROXY);
        addIf(peers, new ProtectedPathResource(), Role.PROXY);
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
        addIf(peers, new InstanceVolumeResource(), Role.INSTANCES);
        addIf(peers, new RuntimeImageResource(), Role.INSTANCES);
        addIf(peers, new InstanceScheduleRunResource(), Role.INSTANCES);
        addIf(peers, new GameDomainResource(), Role.INSTANCES);
        addIf(peers, new BackupTargetResource(), Role.INSTANCES);
        // Build history serves the two tiers that produce images today (Docker sites
        // through the proxy role, container instances through the instances role).
        addIf(peers, new BuildOperationResource(), Role.PROXY, Role.INSTANCES);
        // Release history: applications (the instance tier) release through the
        // health gate since the phase-0 re-keying; the proxy role merely exposes them.
        addIf(peers, new ReleaseOperationResource(), Role.PROXY, Role.INSTANCES);
        addIf(peers, new GitProviderResource(), Role.PROXY);
        addIf(peers, new PreviewDeploymentResource(), Role.PROXY);
        addIf(peers, new StackResource(), Role.STACKS);
        addIf(peers, new StackServiceResource(), Role.STACKS);
        addIf(peers, new StackFileResource(), Role.STACKS);
        // The host inventory serves stacks, managed databases AND the instance
        // tier: instance placement is gated on an ADMITTED host, and admit/
        // preflight/trust live on this resource -- an instances-only node
        // without it cannot place anything.
        if (HohenheimRoles.hostWorkloadsEnabled()) {
            peers.add(new ServerResource());
            peers.add(new ReconcileFindingResource());
        }
        addIf(peers, new DnsZoneResource(), Role.DNS);
        addIf(peers, new DnsRecordResource(), Role.DNS);
        addIf(peers, new DnsPeerResource(), Role.DNS);
        addIf(peers, new DnsZonePeerResource(), Role.DNS);
        peers.add(new NotificationChannelResource());
        addIf(peers, new BanResource(), Role.FIREWALL);
        // zenit-auth's generated admin resources, wired into THIS panel (the
        // module's own default panel is disabled via auth.cms.auto_panel).
        // AIDEV-NOTE: zenit-auth grew the description seam (a third constructor argument),
        // so these two describe themselves like every other entry and
        // AdminNavigationJourneyTest step 2 no longer exempts anything.
        peers.add(new AuthUsersResource(SECURITY_GROUP, 10,
            Microcopy.of("nav_hint").withFilter("scope", "user")));
        peers.add(new AuthRolesResource(SECURITY_GROUP, 20,
            Microcopy.of("nav_hint").withFilter("scope", "role")));
        addIf(peers, new SpamserviceOverviewPage(), Role.FIREWALL);
        addIf(peers, new SpamserviceInstallationResource(), Role.FIREWALL);
        addIf(peers, new SpamserviceSamplesResource(), Role.FIREWALL);
        addIf(peers, new SpamserviceClientsResource(), Role.FIREWALL);
        addIf(peers, new SpamserviceClientKeysResource(), Role.FIREWALL);
        addIf(peers, new SpamserviceSecurityEventsResource(), Role.FIREWALL);
        addIf(peers, new SpamserviceWordsResource(), Role.FIREWALL);
        addIf(peers, new SpamserviceReputationPage(), Role.FIREWALL);
        peers.add(new AdminActivityResource());
        // What is this server running: every bundled module's git commit (System group).
        peers.add(new BuildInfoPage());
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
        try {
            mounts.add(new SettingsPage.Mount(CommsSettingsLabels.MOUNT_KEY, CommsSettingsLabels.mount(),
                SettingsEditor.forFile(CommsSettings.VALUES, HohenheimCommsSettings.settingsFile())));
        } catch (IllegalArgumentException notLoaded) {
            // Boot without the comms settings file: no transport chain to edit.
        }
        mounts.add(new SettingsPage.Mount("spamservice",
            Microcopy.literal("Spamservice"), new SpamserviceSettingsBackend()));
        if (mounts.isEmpty()) {
            return null;
        }
        return new AdminSettingsPage(mounts);
    }
}
