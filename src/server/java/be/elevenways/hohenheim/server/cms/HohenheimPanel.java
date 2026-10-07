package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimRoles.Role;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.server.cms.AuthAdminParts;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelCluster;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.server.page.BuildInfoPage;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.cms.server.task.TaskAdmin;
import be.elevenways.zenit.comms.server.cms.CommsHubAdmin;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * CMS panel served at /admin. Constructed by the discovered
 * {@code HohenheimHostWiring} module at the MODULES boot stage (not
 * {@code @BlastAutoLoad}) because its resources reach server services, and
 * because the registration must be complete before STARTHTTP binds.
 */
public final class HohenheimPanel extends Panel {

    /**
     * THE admin permission, aliased from the common constant so the two faces (common
     * sources, server panel) can never spell it differently.
     */
    public static final Permission ACCESS = HohenheimSources.ADMIN_ACCESS;

    /** The panel's slug, aliased from the common declaring home so endpoint paths agree with it. */
    public static final String SLUG = HohenheimSlugs.ADMIN;

    // AIDEV-NOTE: the sidebar is EIGHT entries in one unlabelled block, in the order of the 2026-09-30 boards:
    // Dashboard, Apps, Databases, Hosts, Domains, Access, Activity, Settings (redesign plan, W4). It names what an
    // operator comes to DO, never a table: Apps reads sites, instances and stacks as one list (AppDirectory), and
    // Domains, Access, Activity and Settings are clusters (zenit-cms PanelCluster): one sidebar row each, their
    // members drawn as the tabs of every member's page. A member keeps its own route, gates and breadcrumbs, and the
    // command palette still lists it under its cluster.
    //
    // AIDEV-NOTE: an entry that is neither one of the eight nor a cluster member is showInNav(false), which removes
    // the sidebar row and NOTHING else, and it keeps a declared way in: Sites, Instances, Stacks and Projects from the
    // Apps list's toolbar (and every app row opens its record), the rest from the list or overview that owns them
    // (AdminNavigationJourneyTest pins both). A new admin entry therefore picks a cluster or a home that links it; a
    // ninth sidebar row is a design decision, not a side effect.
    //
    // AIDEV-NOTE: membership is read from the entries this node actually registered (clusterOf): a node without a
    // role drops that role's members, and a cluster left with none is not added at all, since the panel refuses a
    // member slug naming no entry.
    //
    // AIDEV-NOTE: the three groups below no longer shape this panel's sidebar (everything visible here sits in the
    // unlabelled default block), but the /manage panel's entries share their builders with the admin twins, and its
    // sidebar is still grouped by them.

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
        super(HohenheimIds.id("admin"), SLUG, Microcopy.of("title").withFilter("scope", "admin"), ACCESS);
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
    public @NonNull List<PanelEntry> buildEntries() {
        List<PanelEntry> peers = new ArrayList<>();
        // The dashboard comes first: the panel landing soft-redirects to the
        // first accessible dashboard entry.
        peers.add(new AdminDashboard());
        // Projects/environments span every product tier (sites, instances, databases
        // through their sites), so they are not gated on any single role.
        peers.add(ProjectParts.admin());
        peers.add(EnvironmentParts.admin());
        peers.add(EnvironmentParts.variables());
        addIf(peers, SiteParts.admin(), Role.PROXY);
        addIf(peers, DomainParts.admin(), Role.PROXY);
        addIf(peers, ReleasedClaimParts.admin(), Role.PROXY);
        addIf(peers, CertificateParts.admin(), Role.PROXY);
        addIf(peers, AccessListParts.admin(), Role.PROXY);
        addIf(peers, AccessRuleParts.admin(), Role.PROXY);
        addIf(peers, ProtectedPathParts.admin(), Role.PROXY);
        addIf(peers, AuthProviderParts.admin(), Role.PROXY);
        addIf(peers, DatabaseParts.admin(), Role.DATABASES);
        addIf(peers, DatabaseParts.engines(), Role.DATABASES);
        // Needs BOTH tiers to exist: it joins an instance to a managed database.
        if (HohenheimRoles.enabled(Role.DATABASES) && HohenheimRoles.enabled(Role.INSTANCES)) {
            peers.add(InstanceAttachmentParts.databasesAdmin());
        }
        addIf(peers, InstanceParts.admin(), Role.INSTANCES);
        addIf(peers, InstanceTemplateParts.admin(), Role.INSTANCES);
        addIf(peers, TemplateChildParts.variables(), Role.INSTANCES);
        addIf(peers, TemplateChildParts.files(), Role.INSTANCES);
        addIf(peers, TemplateChildParts.volumes(), Role.INSTANCES);
        // A declared database is created through the managed-database tier at
        // instance create, so the declaration form needs both tiers like the attachment.
        if (HohenheimRoles.enabled(Role.DATABASES) && HohenheimRoles.enabled(Role.INSTANCES)) {
            peers.add(TemplateChildParts.databases());
        }
        addIf(peers, InstanceFileParts.admin(), Role.INSTANCES);
        addIf(peers, InstanceVariableParts.admin(), Role.INSTANCES);
        addIf(peers, new InstanceFromTemplatePage(), Role.INSTANCES);
        addIf(peers, new PutOnlinePage(), Role.INSTANCES);
        addIf(peers, new InstanceTemplateImportPage(), Role.INSTANCES);
        addIf(peers, InstanceQuotaParts.admin(), Role.INSTANCES);
        addIf(peers, InstanceSnapshotParts.admin(), Role.INSTANCES);
        addIf(peers, InstanceBackupParts.admin(), Role.INSTANCES);
        addIf(peers, InstanceScheduleParts.admin(), Role.INSTANCES);
        addIf(peers, InstanceScheduleStepParts.admin(), Role.INSTANCES);
        addIf(peers, InstanceAttachmentParts.devicesAdmin(), Role.INSTANCES);
        addIf(peers, VolumeParts.admin(), Role.INSTANCES);
        addIf(peers, RuntimeImageParts.admin(), Role.INSTANCES);
        addIf(peers, InstanceScheduleRunParts.admin(), Role.INSTANCES);
        addIf(peers, GameDomainResource.admin(), Role.INSTANCES);
        addIf(peers, BackupTargetParts.admin(), Role.INSTANCES);
        // Build history serves the two tiers that produce images today (Docker sites
        // through the proxy role, container instances through the instances role).
        addIf(peers, OperationHistoryParts.builds(), Role.PROXY, Role.INSTANCES);
        // Release history: applications (the instance tier) release through the
        // health gate since the phase-0 re-keying; the proxy role merely exposes them.
        addIf(peers, OperationHistoryParts.releases(), Role.PROXY, Role.INSTANCES);
        addIf(peers, GitProviderParts.admin(), Role.PROXY);
        addIf(peers, PreviewParts.admin(), Role.PROXY);
        addIf(peers, StackParts.stacks(), Role.STACKS);
        addIf(peers, StackParts.services(), Role.STACKS);
        addIf(peers, StackParts.files(), Role.STACKS);
        // The host inventory serves stacks, managed databases AND the instance
        // tier: instance placement is gated on an ADMITTED host, and admit/
        // preflight/trust live on this resource -- an instances-only node
        // without it cannot place anything.
        if (HohenheimRoles.hostWorkloadsEnabled()) {
            peers.add(ServerParts.admin());
            peers.add(ReconcileFindingParts.admin());
        }
        addIf(peers, DnsZoneParts.admin(), Role.DNS);
        addIf(peers, DnsRecordParts.admin(), Role.DNS);
        addIf(peers, DnsPeerParts.admin(), Role.DNS);
        addIf(peers, DnsZonePeerParts.admin(), Role.DNS);
        peers.add(NotificationChannelParts.admin());
        // zenit-comms' delivery log: every alert the channels above sent, whether it arrived, and its retry. Gated by
        // comms' own permissions (other people's notification history), never the delegable panel grant; an operator
        // holding "*" sees it, a delegated admin only through an explicit comms.deliveries.* grant. Hohenheim is no
        // hub, so the hub's projects entry is not mounted.
        PanelEntry deliveries = CommsHubAdmin.install(CommsHubAdmin.Permissions.MODULE).deliveryLog(NavGroup.SYSTEM, 93);
        peers.add(deliveries);
        addIf(peers, BanParts.admin(), Role.FIREWALL);
        // zenit-auth's generated admin resources, wired into THIS panel (the
        // module's own default panel is disabled via auth.cms.auto_panel).
        // AIDEV-NOTE: zenit-auth grew the description seam (a third constructor argument),
        // so these two describe themselves like every other entry and
        // AdminNavigationJourneyTest step 2 no longer exempts anything.
        peers.add(AuthAdminParts.users(SECURITY_GROUP, 10,
            Microcopy.of("nav_hint").withFilter("scope", "user")));
        peers.add(AuthAdminParts.roles(SECURITY_GROUP, 20,
            Microcopy.of("nav_hint").withFilter("scope", "role")));
        addIf(peers, new SpamserviceOverviewPage(), Role.FIREWALL);
        addIf(peers, new SpamserviceInstallationResource(), Role.FIREWALL);
        addIf(peers, SpamserviceSamplesResource.create(), Role.FIREWALL);
        addIf(peers, SpamserviceClientsResource.create(), Role.FIREWALL);
        addIf(peers, SpamserviceClientKeysResource.create(), Role.FIREWALL);
        addIf(peers, SpamserviceSecurityEventsResource.create(), Role.FIREWALL);
        addIf(peers, SpamserviceWordsResource.create(), Role.FIREWALL);
        addIf(peers, new SpamserviceReputationPage(), Role.FIREWALL);
        PanelEntry activity = AdminActivityResource.admin();
        peers.add(activity);
        // Where a platform alert lands with nothing configured: every administrator's
        // own inbox, the local channel Alerts always fans out to.
        PanelEntry inbox = new AdminInboxPage();
        peers.add(inbox);
        // What is this server running: every bundled module's git commit.
        PanelEntry buildInfo = new BuildInfoPage();
        peers.add(buildInfo);
        SettingsPage settings = settingsPage();
        if (settings != null) {
            peers.add(settings);
        }
        // zenit's task admin: every scheduled task with Run now, and the live runs. Gated by the scheduler's own
        // permissions (TaskOperations.VIEW/MANAGE), so an operator holding "*" sees them, a delegated admin only by grant.
        peers.add(TaskAdmin.schedules(NavGroup.SYSTEM, 96));
        peers.add(TaskAdmin.runs(NavGroup.SYSTEM, 97));
        peers.add(AppParts.admin(present(peers, HohenheimSlugs.SITES, InstanceParts.SLUG, StackParts.SLUG,
                ProjectParts.SLUG),
            present(peers, PutOnlinePage.SLUG).isEmpty() ? null : PutOnlinePage.SLUG));
        addCluster(peers, cluster("domain_names", DOMAINS_CLUSTER, "globe", 50), DomainParts.SLUG,
            HohenheimSlugs.DNS_ZONES, HohenheimSlugs.CERTIFICATES, ReleasedClaimParts.SLUG);
        addCluster(peers, cluster("access", ACCESS_CLUSTER, "shield-halved", 60), HohenheimSlugs.ACCESS_LISTS,
            AuthAdminParts.USERS_SLUG, AuthAdminParts.ROLES_SLUG, BanParts.SLUG, SpamserviceOverviewPage.SLUG);
        addCluster(peers, cluster("activity", ACTIVITY_CLUSTER, "clock-rotate-left", 70),
            activity.slug(), inbox.slug(), deliveries.slug());
        addCluster(peers, cluster("settings", SETTINGS_CLUSTER, "gear", 80), SettingsPage.DEFAULT_SLUG,
            HohenheimSlugs.INSTANCE_TEMPLATES, RuntimeImageParts.SLUG, HohenheimSlugs.GIT_PROVIDERS,
            DatabaseParts.ENGINES_SLUG, NotificationChannelParts.SLUG, BackupTargetParts.SLUG,
            TaskAdmin.SCHEDULES_SLUG, TaskAdmin.RUNS_SLUG, buildInfo.slug());
        return peers;
    }

    /** The cluster slugs: each is the sidebar row's URL, which lands on the first member the viewer may open. */
    public static final String DOMAINS_CLUSTER = "domain-names";
    public static final String ACCESS_CLUSTER = "access";
    public static final String ACTIVITY_CLUSTER = "log";
    public static final String SETTINGS_CLUSTER = "configure";

    private static PanelCluster.@NonNull Builder cluster(@NonNull String key, @NonNull String slug,
                                                         @NonNull String icon, int navOrder) {
        return PanelCluster.builder(HohenheimIds.id("cluster_" + key), slug,
                Microcopy.of(key).withFilter("scope", "nav_cluster"))
            .description(Microcopy.of(key).withFilter("scope", "nav_cluster_hint"))
            .icon(Icon.of(icon))
            .navGroup(NavGroup.DEFAULT)
            .navOrder(navOrder);
    }

    /** Adds the cluster over those of these members this node registered; nothing when it registered none. */
    private static void addCluster(@NonNull List<PanelEntry> peers, PanelCluster.@NonNull Builder cluster,
                                   @NonNull String... members) {
        List<String> registered = present(peers, members);
        if (!registered.isEmpty()) {
            peers.add(cluster.members(registered.toArray(String[]::new)).build());
        }
    }

    /** @return those of these slugs an entry of {@code peers} carries, in the order given */
    private static @NonNull List<String> present(@NonNull List<PanelEntry> peers, @NonNull String... slugs) {
        List<String> found = new ArrayList<>();
        for (String slug : slugs) {
            for (PanelEntry peer : peers) {
                if (peer.slug().equals(slug)) {
                    found.add(slug);
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Adds the peer only when at least one of its owning roles is enabled -- THE role gate
     * for a panel peer, shared with {@link ManagePanel} so the delegated projection of a
     * tier can never outlive the tier's own admin surface.
     */
    static void addIf(List<PanelEntry> peers, PanelEntry peer, Role... roles) {
        if (HohenheimRoles.anyEnabled(roles)) {
            peers.add(peer);
        }
    }

    /**
     * The settings editor: Hohenheim's operator sections first (HohenheimSettingsSections, in the boards' order), then
     * zenit's own settings behind "Framework (advanced)". A mount whose settings file this boot never loaded is left
     * out.
     */
    private static @Nullable SettingsPage settingsPage() {
        SettingsPage.Standard page = SettingsPage.standard(HohenheimIds.id("settings"));
        for (HohenheimSettingsSections section : HohenheimSettingsSections.values()) {
            page.mount(section.mount());
        }
        return page.frameworkAdvanced()
            // The page edits the operator-trusted endpoints (auth_proteus is fetched with any-address reach) and the
            // private-network opt-ins, so the delegable panel entry alone never reaches it.
            .requirePermission(HohenheimSources.ADMIN_SYSTEM)
            .navGroup(NavGroup.SYSTEM)
            .navOrder(95)
            .description(Microcopy.of("nav_hint").withFilter("scope", "settings"))
            .build();
    }
}
