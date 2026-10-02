package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimRoles.Role;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.server.page.CmsRecordSources;
import be.elevenways.zenit.common.data.RecordCreateProvider;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.security.PermissionComputation;
import be.elevenways.zenit.common.security.Permissions;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Delegated operator panel at /manage: only the sites (and their domains) the
 * principal holds a manage grant on, no installation-wide peers.
 */
public final class ManagePanel extends Panel {

    /** Aliased from the common constant so the two faces can never spell it differently. */
    public static final Permission ACCESS = HohenheimSources.MANAGE_ACCESS;

    /**
     * This panel's URL slug, so a page that must PROJECT differently here compares
     * against the declaration instead of re-spelling the literal
     * ({@code CmsSupport.isDelegatedPanel} is the one reader).
     */
    public static final String SLUG = HohenheimSlugs.MANAGE;

    private static volatile boolean sourceRegistered = false;

    public ManagePanel() {
        super(HohenheimIds.id(SLUG), SLUG,
            Microcopy.of("title").withFilter("scope", "manage"), ACCESS);
    }

    /** The one eligibility computation, held so a JVM that boots twice installs the same instance. */
    private static final PermissionComputation ELIGIBILITY = ManagePanel::eligible;

    /**
     * Installs the panel's eligibility as the computation of the computed {@link HohenheimSources#MANAGE_ACCESS}:
     * an explicit decision of the checker wins either way, and an abstain asks {@link #eligible}, on every lane
     * (request, detached, websocket) alike. Installed by {@code HohenheimHostWiring} at the MODULES stage.
     *
     * AIDEV-NOTE: this replaced a private PermissionChecker wrapper that widened only the request face, so a
     * detached context (a hop, a channel) answered false for a tenant the request lane admitted (review 4, D14). The
     * computation never runs inside decide(), so an operator's explicit global DENY stays visible to the capability
     * walk's gate row (CapabilityWalkTest step 3).
     */
    public static void installEligibility() {
        Permissions.compute(HohenheimSources.MANAGE_ACCESS, ELIGIBILITY);
    }

    /**
     * Whether a principal with no explicit decision is eligible for /manage: it holds a walk-confirmed grant on at
     * least one record of a model this panel projects. It asks no conduit.
     *
     * AIDEV-NOTE: every model this panel projects belongs in this disjunction: keying it on sites alone locked a pure
     * instance tenant out of the panel built for them, and databases, git providers and projects joined for the same
     * reason. Each record-capability term asks reachesAny, never "ids.isEmpty()": an id set cannot express
     * every-record authority, which 403'd a hohenheim.sites.manage_all holder. The walk consults the checker's
     * decide() only, never this computation, so there is no recursion.
     */
    static boolean eligible(@NonNull AccessContext ctx) {
        return HohenheimAccess.managesAnySite(ctx)
            || HohenheimAccess.reachesAny(ctx, InstanceModel.MODEL_ID, HohenheimAccess.VIEW)
            || HohenheimAccess.reachesAny(ctx, DatabaseModel.MODEL_ID, HohenheimAccess.VIEW)
            || HohenheimAccess.reachesAny(ctx, GitProviderModel.MODEL_ID, HohenheimAccess.MANAGE)
            || !Projects.visibleTo(ctx).isEmpty();
    }

    /**
     * AIDEV-NOTE: every tier's projection is gated on the SAME role its admin surface is
     * gated on ({@link HohenheimPanel#addIf}, one home). Until 2026-08-29 this list carried
     * no role predicate at all, so a proxy-only node answered /manage/instances and
     * /manage/databases with empty lists and the overview offered "Your instances" -- pages
     * the admin panel had (correctly) dropped since 767be086. A peer this method omits has
     * no ROUTE either (peersBySlug), exactly like the admin panel.
     */
    @Override
    public @NonNull List<PanelEntry> buildEntries() {
        return declareEntries();
    }

    /**
     * The entry declaration behind {@link #buildEntries}, callable without a panel instance:
     * a Panel self-registers in its constructor, so a test that wants to see what THIS role
     * set declares asks here rather than constructing a second /manage panel.
     */
    public static @NonNull List<PanelEntry> declareEntries() {
        List<PanelEntry> peers = new ArrayList<>();
        // The dashboard FIRST: the panel-index rule redirects /manage to the first
        // accessible DashboardPanelPeer, so the landing is a real page (what needs
        // attention, then the principal's instances), never a contentless card grid.
        peers.add(new ManageDashboard());
        HohenheimPanel.addIf(peers, new ManageSiteResource(), Role.PROXY);
        HohenheimPanel.addIf(peers, DomainParts.manage(), Role.PROXY);
        HohenheimPanel.addIf(peers, new ManageDnsRecordResource(), Role.DNS);
        HohenheimPanel.addIf(peers, new ManageCertificateResource(), Role.PROXY);
        // The instance tier's tenant projection. Every one of these is scoped by a
        // walk-confirmed record capability, and the two schedule peers plus the
        // from-template page are nav-hidden: they are reached THROUGH an instance
        // (or a template) whose own scope already decided the principal may be here.
        HohenheimPanel.addIf(peers, new ManageInstanceResource(), Role.INSTANCES);
        HohenheimPanel.addIf(peers, new ManageInstanceScheduleResource(), Role.INSTANCES);
        HohenheimPanel.addIf(peers, new ManageInstanceScheduleStepResource(), Role.INSTANCES);
        HohenheimPanel.addIf(peers, new ManageInstanceDeviceResource(), Role.INSTANCES);
        HohenheimPanel.addIf(peers, InstanceVariableParts.manage(), Role.INSTANCES);
        HohenheimPanel.addIf(peers, new ManageInstanceSnapshotResource(), Role.INSTANCES);
        HohenheimPanel.addIf(peers, new ManageInstanceBackupResource(), Role.INSTANCES);
        HohenheimPanel.addIf(peers, InstanceTemplateParts.manage(), Role.INSTANCES);
        HohenheimPanel.addIf(peers, new InstanceFromTemplatePage(), Role.INSTANCES);
        // The managed-database tier's tenant projection: allocate, read credentials
        // (its own capability, its own tab), back up and destroy your OWN databases.
        HohenheimPanel.addIf(peers, new ManageDatabaseResource(), Role.DATABASES);
        // Needs BOTH tiers to exist: it joins an instance to a managed database.
        if (HohenheimRoles.enabled(Role.DATABASES) && HohenheimRoles.enabled(Role.INSTANCES)) {
            peers.add(new ManageInstanceDatabaseResource());
        }
        // The project tier's tenant projection: which projects the principal is a
        // MEMBER of, and who else is in them. Both read-only -- see
        // ManageProjectResource for why a membership editor here could only refuse.
        // Projects span every product tier, so they are not gated on any single role.
        peers.add(new ManageProjectResource());
        peers.add(new ManageProjectMemberResource());
        // Preview deployments of granted sites: view, create for a chosen ref,
        // destroy. Scoped by the site's manage grant like domains are.
        HohenheimPanel.addIf(peers, new ManagePreviewDeploymentResource(), Role.PROXY);
        // The tenant's OWN forge installations: register one, test it, use it on the
        // tenant's own sites. Shared operator providers are usable but never listed
        // here -- see GitProviderParts.manage().
        HohenheimPanel.addIf(peers, GitProviderParts.manage(), Role.PROXY);
        HohenheimPanel.addIf(peers, new ManageAccessListResource(), Role.PROXY);
        HohenheimPanel.addIf(peers, new ManageAccessRuleResource(), Role.PROXY);
        HohenheimPanel.addIf(peers, new ManageProtectedPathResource(), Role.PROXY);
        return peers;
    }

    /**
     * THE SiteModel default source, serving the admin pickers AND the /manage
     * tenant surface through one per-principal scope (admins unconstrained,
     * tenants their granted sites, everyone else nothing). Registered
     * server-side (not in HohenheimSources) because the scope reads zenit-auth
     * record grants, which common code cannot see.
     * AIDEV-NOTE: the browser registry legitimately lacks this source, and
     * that is SAFE only because the framework treats the browser registry as
     * a subset: widget-config revival tolerates browser-unresolvable tokens
     * and client re-renders reach /zn/records/{token} by TOKEN (zenit-widget
     * RecordsFunctions/ChartFunctions). Naming this token in a widget config
     * used to kill soft navigation onto /admin/dashboard (revival rejected
     * 'hohenheim.site' against the browser registry) -- pinned by
     * NavigationTest.softNavDashboardKeepsStatTitlesIconsAndAttentionEntries
     * and zenit-cms's ServerOnlySourceSoftNavBrowserTest. The trade-off of
     * staying server-only: client-side source pickers and rule vocabularies
     * cannot offer it.
     * AIDEV-NOTE: this replaced the separate "hohenheim.manage_site" source --
     * two sources with identical semantics over one model were a shadowing
     * hazard.
     */
    public static synchronized void registerSiteSource() {
        if (sourceRegistered) {
            return;
        }
        sourceRegistered = true;
        declareSources();
    }

    /**
     * The registration body {@link #registerSiteSource} runs exactly once at boot,
     * callable again so a test can replay it against a fresh registry.
     *
     * AIDEV-NOTE: every declaration here is an override(), never a register(). zenit-cms
     * derives a default source for every model a RowResource exposes whose schema has a
     * display field -- and the display-field CONVENTION means a plain "name" column is
     * enough, so all of these models derive one. Replacement is complete and never a
     * merge, so an explicit source that changes the derived gate is REFUSED at boot
     * unless it says the change is deliberate; every source below narrows the derived
     * admin gate to a walk-confirmed manage scope, which IS that deliberate change.
     *
     * AIDEV-NOTE: no scope is SPELLED here. Each source applies its model's
     * {@link TenantScopes} declaration, the same one the Manage* resource's accessFunction
     * reads, so a picker and the /manage list cannot drift apart again (they had: the
     * instance source offered generated rows and the domain source the domains of
     * soft-deleted sites, both of which the lists hid).
     */
    static void declareSources() {
        // The SiteModel default source. zenit-cms derives one from SiteModel.NAME through
        // both the admin SiteResource and the delegated ManageSiteResource; this server-side
        // declaration replaces it deliberately, because its scope reads zenit-auth grants
        // unavailable to the common/browser registration lane.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(SiteModel.class)
            .search(SiteModel.NAME, SiteModel.SLUG)
            .scopedBy(TenantScopes.SITES)
            .build());

        // The domain source, for the SAME reason and by the same verb -- plus one that is
        // specific to this model: site_domain is exposed by TWO resources (the admin
        // DomainParts.admin() and the delegated DomainParts.manage()), so zenit-cms derives
        // a default source from BOTH panels and which one wins is decided by panel walk
        // ORDER. That is a shadowing hazard exactly like the deleted "hohenheim.manage_site"
        // one: it decides whether the token is admin-gated-unscoped or manage-gated-scoped
        // at boot. This explicit registration makes the answer boot-order-independent.
        // AIDEV-NOTE: scoped by the domain's PARENT SITE, never by a grant on the domain
        // row -- SiteDomainModel deliberately has NO grant surface of its own (see
        // docs/instance-tier-plan.md, Phase 2 parallel gate): a second authority over a
        // child row is a second authority that can disagree with the first.
        // AIDEV-NOTE: the base now carries the live-site filter the /manage list always
        // applied: a domain of a soft-deleted site is no longer offered by a picker either.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(SiteDomainModel.class)
            .search(SiteDomainModel.HOSTNAME)
            .scopedBy(TenantScopes.DOMAINS)
            .build());

        // Access lists: the model's REFERENCE policy. The pickers (a site's list, a protected
        // path's list) offer shared rows plus the principal's managed ones -- the git-provider
        // policy verbatim -- while the /manage list keeps its OWNED scope
        // (TenantScopes.MANAGED_ACCESS_LISTS): a picker reads the reference policy ahead of any
        // panel resource's source, so the tenant list is never widened to offer a shared row.
        // An explicit override for the same two-panel shadowing reason as site_domain
        // (AccessListResource and ManageAccessListResource both expose the model).
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(AccessListModel.class)
            .search(AccessListModel.NAME)
            .scopedBy(TenantScopes.USABLE_ACCESS_LISTS)
            .referencePolicy()
            .build());

        // Protected paths: child rows scoped by their parent SITE, like domains.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(ProtectedPathModel.class)
            .search(ProtectedPathModel.PATH)
            .scopedBy(TenantScopes.PROTECTED_PATHS)
            .build());

        // DNS records: this one scopes child rows by their parent zone, so a tenant reaches
        // names inside a zone it cannot otherwise enumerate -- a deliberate narrowing of the
        // admin-gated default zenit-cms derives from DnsRecordModel.NAME.
        // The create half serves the list pages' quick-add bar. It is ADMIN-gated on top
        // of the source's own read scope, because its form carries zone_id: adding "into
        // an arbitrary zone" is an operator act, while a tenant's create lane is
        // ManageDnsRecordResource's form, which resolves the zone from the name it typed.
        // The write pipeline (TenantWrites) stays the gate either way -- this only decides
        // which surface is OFFERED.
        //
        // AIDEV-NOTE: the provider is the FRAMEWORK's own resource-backed one
        // (CmsRecordSources.createProviderFor), which is what zenit-cms derives for every
        // creatable RowResource whose source it registers itself. An explicit source
        // replaces the derived default WHOLE -- facets never merge -- so the create half
        // has to be declared here or the bar simply never appears. It used to be a
        // hand-written copy of that provider; nothing about the reduction or the
        // persistence path was ever hohenheim-specific.
        var dnsRecords = RecordSource.of(DnsRecordModel.class)
            .search(DnsRecordModel.NAME, DnsRecordModel.VALUE)
            .scopedBy(TenantScopes.DNS_RECORDS);
        RecordCreateProvider dnsCreate = CmsRecordSources.createProviderFor(new DnsRecordResource());
        if (dnsCreate != null) {
            dnsRecords.creatable(dnsCreate, HohenheimSources.ADMIN_ACCESS);
        }
        RecordSourceRegistry.INSTANCE.override(dnsRecords.build());

        // Certificates: this REPLACES the common ADMIN_ACCESS-gated registration (which the
        // browser registry keeps, legitimately -- the scope below reads zenit-auth record
        // grants that common code cannot see). The base criteria is the SAME method the
        // common registration uses, never a second copy of the ACME-account exclusion.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(CertificateModel.class)
            .search(CertificateModel.NICE_NAME)
            .scopedBy(TenantScopes.CERTIFICATES)
            .build());

        // Instances: the SAME two-panel shadowing hazard as sites and domains, now that
        // ManageInstanceResource exposes the model beside the admin InstanceResource --
        // which of the two derived defaults wins (admin-gated-unscoped versus
        // manage-gated-scoped) would otherwise be decided by panel walk ORDER at boot.
        // AIDEV-NOTE: kind IS projected because the site form's dependent instance
        // pick (HohenheimPickRules.UpstreamInstanceRules) narrows on it -- the
        // projection is the rule vocabulary, so dropping it silently 400s the picker.
        // AIDEV-NOTE: the base excludes GENERATED instances like the /manage list always
        // did (TenantScopes.INSTANCES): a product-tier-owned row is managed through its
        // owning record, and a picker offering it was a tenant read the list refused.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(InstanceModel.class)
            .project(InstanceModel.NAME, InstanceModel.KIND)
            .search(InstanceModel.NAME)
            .scopedBy(TenantScopes.INSTANCES)
            .build());

        // Templates: exposed by TWO entries (InstanceTemplateParts.admin and .manage),
        // so a derived default would be boot-order-decided --
        // the same shadowing hazard as instances above. The scope is THE catalog policy:
        // operators browse everything, everyone else only APPROVED templates. The
        // instance form's dependent template pick narrows on the projected kind.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(InstanceTemplateModel.class)
            .project(InstanceTemplateModel.NAME, InstanceTemplateModel.KIND)
            .search(InstanceTemplateModel.NAME)
            .scopedBy(TenantScopes.INSTANCE_TEMPLATES)
            .build());

        // Record schedules: this declaration carries the SAME scope the delegated resource
        // enforces, so a picker and the resource can never disagree -- a deliberate
        // narrowing of the default derived from RecordScheduleModel.NAME.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(RecordScheduleModel.class)
            .search(RecordScheduleModel.NAME)
            .scopedBy(TenantScopes.INSTANCE_SCHEDULES)
            .build());

        // Projects: the SAME two-derived-defaults hazard, now that ManageProjectResource
        // exposes the model beside the admin ProjectResource -- and the widest of the two
        // would name every tenant's projects to whoever a picker rendered for. The scope
        // is THE visibility policy, so a picker and the resource can never disagree.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(ProjectModel.class)
            .search(ProjectModel.NAME)
            .scopedBy(TenantScopes.PROJECTS)
            .build());

        // Managed databases: the common registration (HohenheimSources) is ADMIN_ACCESS
        // with no accessCriteria, which the browser registry legitimately keeps. Here the
        // model is exposed by a SECOND RowResource (ManageDatabaseResource beside the
        // admin DatabaseResource), so without this the widest of the two derived defaults
        // decides -- and it would name every tenant's database to whoever a picker
        // rendered for, starting with the site-database attachment picker. override, not
        // register: the manage panel deliberately serves a WIDER audience than the
        // databases panel's own permission, scoped to what each principal was granted.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(DatabaseModel.class)
            .search(DatabaseModel.NAME)
            .scopedBy(TenantScopes.DATABASES)
            .build());

        // Instance devices: same two-derived-defaults hazard again, and the widest one
        // would list every tenant's disk names and sizes to whoever a picker rendered for.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(InstanceDeviceModel.class)
            .search(InstanceDeviceModel.NAME)
            .scopedBy(TenantScopes.INSTANCE_DEVICES)
            .build());

        // Preview deployments: the same two-derived-defaults hazard (the admin
        // PreviewDeploymentResource and the delegated ManagePreviewDeploymentResource
        // both derive), and the widest one would name every tenant's branch names and
        // preview hostnames to whoever a picker rendered for.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(PreviewDeploymentModel.class)
            .search(PreviewDeploymentModel.HOSTNAME, PreviewDeploymentModel.REF)
            .scopedBy(TenantScopes.PREVIEWS)
            .build());

        // Git providers: the SAME two-derived-defaults hazard (the admin
        // GitProviderParts.admin() and the delegated GitProviderParts.manage() both derive one
        // from the model's display field), and the widest of the two would name every
        // tenant's forge installation -- host included -- to whoever a picker rendered
        // for. The scope IS the visibility policy (shared rows plus the ones the
        // principal manages), so the site form's provider picker and this source can
        // never disagree. It is the model's REFERENCE policy, as access lists' is: the
        // /manage list keeps its owned scope (TenantScopes.MANAGED_GIT_PROVIDERS).
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(GitProviderModel.class)
            .search(GitProviderModel.NAME)
            .scopedBy(TenantScopes.USABLE_GIT_PROVIDERS)
            .referencePolicy()
            .build());

        // Instance-database attachments: the row names both a workload and a credential
        // store, so this source must carry the parent instance's visibility scope instead
        // of the admin gate zenit-cms derives.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(InstanceDatabaseModel.class)
            .title()
            .scopedBy(TenantScopes.INSTANCE_DATABASES)
            .build());
    }

    /**
     * NAV-ONLY scope probe shared by the /manage peers: whether the walk reaches ANY site.
     *
     * AIDEV-NOTE: no {@code isAdmin} disjunct anymore -- the walk's own admin-bypass row
     * already answers ALL for one, and the type-level row answers ALL for an every-site
     * holder that a hand-written admin check would have missed. Cheap per render thanks to
     * the conduit-scoped scope memo, and free for an admin (a whole-model row runs no query).
     */
    static boolean hasManageScope(@NonNull AccessContext ctx) {
        return HohenheimAccess.managesAnySite(ctx);
    }
}
