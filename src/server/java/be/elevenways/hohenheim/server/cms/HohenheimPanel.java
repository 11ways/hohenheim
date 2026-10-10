package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimRoles.Role;
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

    // AIDEV-NOTE: the sidebar is EIGHT entries in one unlabelled block, in this order:
    // Dashboard, Apps, Databases, Hosts, Domains, Access, Activity, Settings. It names what an
    // operator comes to DO, never a table: Apps reads sites, instances and stacks as one list (AppDirectory), and
    // Domains, Access, Activity and Settings are clusters (zenit-cms PanelCluster): one sidebar row each, their
    // members drawn as the tabs of every member's page. A member keeps its own route, gates and breadcrumbs, and the
    // command palette still lists it under its cluster.
    //
    // AIDEV-NOTE: an entry that is neither one of the eight nor a cluster member is showInNav(false), which removes
    // the sidebar row and NOTHING else, and it keeps a declared way in: Sites, Instances, Stacks and Projects from the
    // Apps list's toolbar (and every app row opens its record), the rest from the list or overview that owns them
    // (AdminNavigationJourneyTest pins both). It also names that home as the row it stands under (PanelEntry.standsUnder,
    // the entry it is reached from), so its pages mark that row and their titles end with it; the journey's step 11
    // fails an entry of either panel whose pages would mark no row. A new admin entry therefore picks a cluster or a
    // home that links it; a ninth sidebar row is a design decision, not a side effect.
    //
    // AIDEV-NOTE: membership is read from the entries this node actually registered (clusterOf): a node without a
    // role drops that role's members, and a cluster left with none is not added at all, since the panel refuses a
    // member slug naming no entry.
    //
    // AIDEV-NOTE: the three groups below shape neither panel's sidebar any more: everything visible here sits in the
    // unlabelled default block, and so does /manage (ManagePanel). They remain the declared
    // group of the entries both twins share; a sidebar row of either panel sets NavGroup.DEFAULT or is clustered.

    /** Deploy group: everything an operator creates to make something RUN -- projects,
     *  sites, instances, stacks, databases, and the templates and git providers they are
     *  built from. Servers are deliberately NOT here: a host is not a workload, it is the
     *  installation itself, so it sits in the ungrouped top block beside the dashboard. */
    public static final NavGroup DEPLOY_GROUP =
        NavGroup.of("deploy", HohenheimMicrocopy.HOHENHEIM_NAV.of("deploy"), 150, Icon.of("rocket"));

    /** Networking group: how traffic REACHES those workloads -- DNS, certificates, access
     *  control, and the cooldown that holds a released hostname out of circulation. */
    public static final NavGroup NETWORK_GROUP =
        NavGroup.of("networking", HohenheimMicrocopy.HOHENHEIM_NAV.of("networking"), 200,
            Icon.of("network-wired"));

    /** Security group: who may act and who is refused -- users, roles, abuse protection,
     *  IP bans; opens the background tail. */
    public static final NavGroup SECURITY_GROUP =
        NavGroup.of("security", HohenheimMicrocopy.HOHENHEIM_NAV.of("security"), 800, Icon.of("shield-halved"))
            .withSeparatorBefore(true);

    public HohenheimPanel() {
        super(HohenheimIds.id("admin"), HohenheimSlugs.ADMIN, HohenheimMicrocopy.ADMIN.of("title"), ACCESS);
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
        addIf(peers, SiteParts.admin(), SiteParts.ROLE);
        addIf(peers, DomainParts.admin(), Role.PROXY);
        addIf(peers, ReleasedClaimParts.admin(), Role.PROXY);
        addIf(peers, CertificateParts.admin(), Role.PROXY);
        addIf(peers, AccessListParts.admin(), Role.PROXY);
        addIf(peers, AccessRuleParts.admin(), Role.PROXY);
        addIf(peers, ProtectedPathParts.admin(), Role.PROXY);
        addIf(peers, AuthProviderParts.admin(), Role.PROXY);
        addIf(peers, DatabaseParts.admin(), Role.DATABASES);
        addIf(peers, DatabaseParts.engines(), Role.DATABASES);
        if (InstanceAttachmentParts.databasesServed()) {
            peers.add(InstanceAttachmentParts.databasesAdmin());
        }
        addIf(peers, InstanceParts.admin(), Role.INSTANCES);
        addIf(peers, InstanceTemplateParts.admin(), Role.INSTANCES);
        addIf(peers, TemplateChildParts.variables(), Role.INSTANCES);
        addIf(peers, TemplateChildParts.files(), Role.INSTANCES);
        addIf(peers, TemplateChildParts.volumes(), Role.INSTANCES);
        // A declared database is created through the managed-database tier at
        // instance create, so the declaration form needs both tiers like the attachment.
        if (InstanceAttachmentParts.databasesServed()) {
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
        // health gate since the re-keying; the proxy role merely exposes them.
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
            HohenheimMicrocopy.USER.of("nav_hint")));
        peers.add(AuthAdminParts.roles(SECURITY_GROUP, 20,
            HohenheimMicrocopy.ROLE.of("nav_hint")));
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
        peers.add(AppParts.admin(present(peers, HohenheimSlugs.SITES, HohenheimSlugs.INSTANCES, HohenheimSlugs.STACKS,
                HohenheimSlugs.PROJECTS),
            present(peers, HohenheimSlugs.PUT_ONLINE).isEmpty() ? null : HohenheimSlugs.PUT_ONLINE));
        // In tab order: addresses, certificates, DNS zones, released addresses.
        addCluster(peers, cluster("domain_names", HohenheimSlugs.Cluster.DOMAIN_NAMES, "globe", 50),
            HohenheimSlugs.DOMAINS,
            HohenheimSlugs.CERTIFICATES, HohenheimSlugs.DNS_ZONES, HohenheimSlugs.RELEASED_CLAIMS);
        // In tab order: lists, blocked addresses, people (users and roles), sign-in providers.
        addCluster(peers, cluster("access", HohenheimSlugs.Cluster.ACCESS, "shield-halved", 60),
            HohenheimSlugs.ACCESS_LISTS,
            HohenheimSlugs.BANS, AuthAdminParts.USERS_SLUG, AuthAdminParts.ROLES_SLUG, HohenheimSlugs.AUTH_PROVIDERS,
            HohenheimSlugs.SPAMSERVICE);
        addCluster(peers, cluster("activity", HohenheimSlugs.Cluster.LOG, "clock-rotate-left", 70),
            activity.slug(), inbox.slug(), deliveries.slug());
        addCluster(peers, cluster("settings", HohenheimSlugs.Cluster.CONFIGURE, "gear", 80), SettingsPage.DEFAULT_SLUG,
            HohenheimSlugs.INSTANCE_TEMPLATES, HohenheimSlugs.RUNTIME_IMAGES, HohenheimSlugs.GIT_PROVIDERS,
            HohenheimSlugs.DATABASE_ENGINES, HohenheimSlugs.NOTIFICATIONS, HohenheimSlugs.BACKUP_TARGETS,
            TaskAdmin.SCHEDULES_SLUG, TaskAdmin.RUNS_SLUG, buildInfo.slug());
        return peers;
    }

    /** A sidebar cluster in the unlabelled default block, worded under {@code nav_cluster}; both panels build theirs here. */
    static PanelCluster.@NonNull Builder cluster(@NonNull String key, @NonNull String slug,
                                                 @NonNull String icon, int navOrder) {
        return PanelCluster.builder(HohenheimIds.id("cluster_" + key), slug,
                HohenheimMicrocopy.NAV_CLUSTER.of(key))
            .description(HohenheimMicrocopy.NAV_CLUSTER_HINT.of(key))
            .icon(Icon.of(icon))
            .navGroup(NavGroup.DEFAULT)
            .navOrder(navOrder);
    }

    /** Adds the cluster over those of these members this node registered; nothing when it registered none. */
    static void addCluster(@NonNull List<PanelEntry> peers, PanelCluster.@NonNull Builder cluster,
                           @NonNull String... members) {
        List<String> registered = present(peers, members);
        if (!registered.isEmpty()) {
            peers.add(cluster.members(registered.toArray(String[]::new)).build());
        }
    }

    /** @return those of these slugs an entry of {@code peers} carries, in the order given */
    static @NonNull List<String> present(@NonNull List<PanelEntry> peers, @NonNull String... slugs) {
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
     * The settings editor: Hohenheim's operator sections first (HohenheimSettingsSections, in its order), then
     * zenit's own settings behind "Framework (advanced)". A mount whose settings file this boot never loaded is left
     * out.
     */
    private static @Nullable SettingsPage settingsPage() {
        SettingsPage.Standard page = SettingsPage.standard(HohenheimIds.id("settings"));
        for (HohenheimSettingsSections section : HohenheimSettingsSections.values()) {
            page.mount(section.mount());
        }
        return page.frameworkAdvanced()
            // The operator-trusted keys carry their own authority (host-only, or hohenheim.admin.system for the
            // auth_proteus login, the proxy trust lists, the build images and the spam service), so the page is an
            // ordinary admin peer.
            .requirePermission(HohenheimSources.ADMIN_ACCESS)
            .navGroup(NavGroup.SYSTEM)
            .navOrder(95)
            .description(HohenheimMicrocopy.SETTINGS.of("nav_hint"))
            .build();
    }
}
