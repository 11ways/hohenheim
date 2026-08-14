package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.HostnameAuthority;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.CompositeCriteria;
import be.elevenways.zenit.common.orm.query.criteria.CompositeOperator;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.security.PermissionChecker;
import be.elevenways.zenit.common.security.RecordCapabilityScope;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.server.data.RecordSourceGate;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Delegated operator panel at /manage: only the sites (and their domains) the
 * principal holds a manage grant on, no installation-wide peers.
 */
public final class ManagePanel extends Panel {

    public static final Permission ACCESS = Permission.of("hohenheim.manage.access");

    /**
     * This panel's URL slug, so a page that must PROJECT differently here compares
     * against the declaration instead of re-spelling the literal
     * ({@code CmsSupport.isDelegatedPanel} is the one reader).
     */
    public static final String SLUG = "manage";

    private static volatile boolean sourceRegistered = false;

    public ManagePanel() {
        super(Identifier.of("hohenheim", "manage"), SLUG,
            Microcopy.of("title").withFilter("scope", "manage"), ACCESS);
    }

    /**
     * Derives panel eligibility from walk-confirmed record grants while preserving
     * explicit global grants. Installed by {@code HohenheimHostWiring} at the
     * MODULES stage (never a Panel-constructor side effect): the checker and the
     * site source must exist the moment the server accepts requests, and boot-seam
     * installation makes that ordering structural.
     */
    public static synchronized void installEligibilityPolicy() {
        PermissionChecker current = Zenit.getPermissionChecker();
        if (current instanceof ManageEligibilityChecker) {
            return;
        }
        Zenit.setPermissionChecker(new ManageEligibilityChecker(current));
    }

    /**
     * Widens ONLY the boolean face of the panel's ACCESS permission: an explicit
     * resolver decision (either way) wins, and an abstain falls back to "holds a
     * walk-confirmed manage grant on at least one site". zenit-cms checks a
     * panel's access as a plain permission, so this layer is what makes a
     * grant-holding tenant eligible for /manage without a second global grant.
     *
     * AIDEV-NOTE: decide() MUST pass through to the delegate untouched. The
     * predecessor (EffectiveManagePermissionChecker) overrode only
     * hasPermission, so it inherited the interface default decide() -- which
     * maps false to abstain -- and thereby made an operator's explicit global
     * DENY invisible to RecordCapabilities row 2 (GATE_DENIED) in every
     * hohenheim install. Pinned by CapabilityWalkTest step 3.
     */
    private record ManageEligibilityChecker(PermissionChecker delegate) implements PermissionChecker {
        @Override
        public boolean hasPermission(Conduit conduit, Permission permission) {
            if (!ACCESS.equals(permission) || conduit == null) {
                return this.delegate.hasPermission(conduit, permission);
            }
            Boolean decision = this.delegate.decide(conduit, permission);
            if (decision != null) {
                return decision;
            }
            // Abstain: eligibility follows the record grants. Each candidate is
            // confirmed through the precedence walk (no recursion: the walk
            // consults this checker only for OTHER permissions -- the admin
            // bypass -- and for decide(), which passes through above).
            //
            // AIDEV-NOTE: instances count too, and they had to the moment the panel
            // grew an instance projection. Keying eligibility on SITES alone locked a
            // pure instance tenant (a game-server renter who owns no website) out of
            // the very panel built for them: 403 at /manage with a live manage grant
            // in hand. Every model this panel projects belongs in this disjunction.
            //
            // AIDEV-NOTE: each record-capability term asks reachesAny, never
            // "ids.isEmpty()". An id set cannot express every-record authority, so the
            // set spelling answered "reaches nothing" for a hohenheim.sites.manage_all
            // holder and 403'd them out of the panel their permission exists for.
            AccessContext ctx = RecordSourceGate.accessContextOf(conduit);
            return HohenheimAccess.managesAnySite(ctx)
                || HohenheimAccess.reachesAny(ctx, InstanceModel.MODEL_ID, HohenheimAccess.VIEW)
                // DATABASES join the disjunction for the same reason instances did: a
                // tenant who rents only a database holds no site or instance grant and
                // would be 403'd out of the panel that now projects their database.
                || HohenheimAccess.reachesAny(ctx, DatabaseModel.MODEL_ID, HohenheimAccess.VIEW)
                // PROJECTS join the disjunction for the reason stated above: a member of
                // a project that owns nothing yet holds no site or instance grant, and
                // would be 403'd out of the panel that now projects their project.
                || !Projects.visibleTo(ctx).isEmpty();
        }

        @Override
        public @Nullable Boolean decide(Conduit conduit, @NonNull Permission permission) {
            return this.delegate.decide(conduit, permission);
        }
    }

    @Override
    public @NonNull List<PanelPeer> buildPeers() {
        // The dashboard FIRST: the panel-index rule redirects /manage to the first
        // accessible DashboardPanelPeer, so the landing is a real page (what needs
        // attention, then the principal's instances), never a contentless card grid.
        return List.of(new ManageDashboard(),
            new ManageSiteResource(), new ManageDomainResource(),
            new ManageDnsRecordResource(), new ManageCertificateResource(),
            // The instance tier's tenant projection. Every one of these is scoped by a
            // walk-confirmed record capability, and the two schedule peers plus the
            // from-template page are nav-hidden: they are reached THROUGH an instance
            // (or a template) whose own scope already decided the principal may be here.
            new ManageInstanceResource(), new ManageInstanceScheduleResource(),
            new ManageInstanceScheduleStepResource(), new ManageInstanceDeviceResource(),
            new ManageInstanceSnapshotResource(),
            new ManageInstanceBackupResource(), new ManageInstanceTemplateResource(),
            new InstanceFromTemplatePage(),
            // The managed-database tier's tenant projection: allocate, read credentials
            // (its own capability, its own tab), back up and destroy your OWN databases.
            new ManageDatabaseResource(), new ManageInstanceDatabaseResource(),
            // The project tier's tenant projection: which projects the principal is a
            // MEMBER of, and who else is in them. Both read-only -- see
            // ManageProjectResource for why a membership editor here could only refuse.
            new ManageProjectResource(), new ManageProjectMemberResource(),
            // Preview deployments of granted sites: view, create for a chosen ref,
            // destroy. Scoped by the site's manage grant like domains are.
            new ManagePreviewDeploymentResource());
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
        // override, not register: the manage panel deliberately serves a WIDER audience
        // than the sites panel's own access permission -- any principal with a grant,
        // scoped to the sites that grant covers. The registry refuses a silent widening
        // of the derived default, and this is the verb that declares one.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(SiteModel.class)
            .search(SiteModel.NAME, SiteModel.SLUG)
            .baseCriteria(() -> SiteModel.DELETED_AT.isNull())
            .accessCriteria(ManagePanel::siteScope)
            .build());

        // The domain source, for the SAME reason and by the same verb -- plus one that is
        // specific to this model: site_domain is exposed by TWO RowResources (the admin
        // SiteDomainResource and the delegated ManageDomainResource), so zenit-cms derives
        // a default source from BOTH panels and which one wins is decided by panel walk
        // ORDER. That is a shadowing hazard exactly like the deleted "hohenheim.manage_site"
        // one: it decides whether the token is admin-gated-unscoped or manage-gated-scoped
        // at boot. This explicit registration makes the answer boot-order-independent.
        // AIDEV-NOTE: scoped by the domain's PARENT SITE, never by a grant on the domain
        // row -- SiteDomainModel deliberately has NO grant surface of its own (see
        // docs/instance-tier-plan.md, Phase 2 parallel gate): a second authority over a
        // child row is a second authority that can disagree with the first.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(SiteDomainModel.class)
            .search(SiteDomainModel.HOSTNAME)
            .accessCriteria(ManagePanel::domainScope)
            .build());

        // DNS records: the SAME shadowing hazard (the admin DnsRecordResource and the
        // delegated ManageDnsRecordResource both derive a default), plus the reason the
        // capability vocabulary exists at all -- a tenant reaches individual names inside a
        // zone it can never see.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(DnsRecordModel.class)
            .search(DnsRecordModel.NAME, DnsRecordModel.VALUE)
            .accessCriteria(ManagePanel::dnsRecordScope)
            .build());

        // Certificates: this REPLACES the common ADMIN_ACCESS-gated registration (which the
        // browser registry keeps, legitimately -- the scope below reads zenit-auth record
        // grants that common code cannot see). The base criteria is the SAME method the
        // common registration uses, never a second copy of the ACME-account exclusion.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(CertificateModel.class)
            .search(CertificateModel.NICE_NAME)
            .baseCriteria(HohenheimSources::notTheAcmeAccountRow)
            .accessCriteria(ManagePanel::certificateScope)
            .build());

        // Instances: the SAME two-panel shadowing hazard as sites and domains, now that
        // ManageInstanceResource exposes the model beside the admin InstanceResource --
        // which of the two derived defaults wins (admin-gated-unscoped versus
        // manage-gated-scoped) would otherwise be decided by panel walk ORDER at boot.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(InstanceModel.class)
            .search(InstanceModel.NAME)
            .baseCriteria(() -> InstanceModel.DELETED_AT.isNull())
            .accessCriteria(ctx -> HohenheimAccess.instanceScope(ctx, HohenheimAccess.VIEW))
            .build());

        // Record schedules: same hazard (the admin InstanceScheduleResource and the
        // delegated one both derive), and this source is the one a picker or a widget
        // would reach, so it carries the SAME scope the delegated resource enforces --
        // never the resource's scope in one place and an open source in another.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(RecordScheduleModel.class)
            .search(RecordScheduleModel.NAME)
            .accessCriteria(ManagePanel::recordScheduleScope)
            .build());

        // Projects: the SAME two-derived-defaults hazard, now that ManageProjectResource
        // exposes the model beside the admin ProjectResource -- and the widest of the two
        // would name every tenant's projects to whoever a picker rendered for. The scope
        // is THE visibility policy, so a picker and the resource can never disagree.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(ProjectModel.class)
            .search(ProjectModel.NAME)
            .accessCriteria(Projects::visibleScope)
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
            .accessCriteria(ctx -> HohenheimAccess.databaseScope(ctx, HohenheimAccess.VIEW))
            .build());

        // Instance devices: same two-derived-defaults hazard again, and the widest one
        // would list every tenant's disk names and sizes to whoever a picker rendered for.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(InstanceDeviceModel.class)
            .search(InstanceDeviceModel.NAME)
            .accessCriteria(ManagePanel::instanceDeviceScope)
            .build());

        // Preview deployments: the same two-derived-defaults hazard (the admin
        // PreviewDeploymentResource and the delegated ManagePreviewDeploymentResource
        // both derive), and the widest one would name every tenant's branch names and
        // preview hostnames to whoever a picker rendered for.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(
                PreviewDeploymentModel.class)
            .search(PreviewDeploymentModel.HOSTNAME,
                PreviewDeploymentModel.REF)
            .baseCriteria(() ->
                PreviewDeploymentModel.DELETED_AT.isNull())
            .accessCriteria(ManagePanel::previewScope)
            .build());

        // Instance-database attachments: the same hazard once more. The row names both a
        // workload and a credential store, so an unscoped source would tell any principal
        // a picker rendered for which databases every other tenant's servers run on.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(InstanceDatabaseModel.class)
            .title()
            .accessCriteria(ManagePanel::instanceDatabaseScope)
            .build());
    }

    /** @return null for an unconstrained scope, else the devices of viewable instances */
    static @Nullable Criteria instanceDeviceScope(@NonNull AccessContext ctx) {
        return HohenheimAccess.grantScope(ctx, Models.get(InstanceDeviceModel.class),
            InstanceModel.MODEL_ID, HohenheimAccess.VIEW, InstanceDeviceModel.INSTANCE_ID::in);
    }

    /** @return null for an unconstrained scope, else the attachments of viewable instances */
    static @Nullable Criteria instanceDatabaseScope(@NonNull AccessContext ctx) {
        return HohenheimAccess.grantScope(ctx, Models.get(InstanceDatabaseModel.class),
            InstanceModel.MODEL_ID, HohenheimAccess.VIEW, InstanceDatabaseModel.INSTANCE_ID::in);
    }

    /** @return null for an unconstrained scope, else the schedules of viewable instances */
    static @Nullable Criteria recordScheduleScope(@NonNull AccessContext ctx) {
        return ManageInstanceScheduleResource.scopeCriteria(ctx);
    }

    /**
     * The DNS records a principal may read: the ones under a hostname it answers for, plus
     * the ones explicitly granted {@code view}.
     *
     * AIDEV-NOTE: the derived half enumerates EXACT managed hostnames only. A managed
     * WILDCARD domain confers write authority over the names it covers (HostnamePatterns
     * .covers, via HostnameAuthority) but contributes no owner label here, so such a row is
     * authored-but-unlisted until an explicit view grant. Deliberate: the read scope is a
     * criteria over stored owner labels, and widening it to "any label a wildcard could
     * cover" would need a scan of dns_records to build a query over dns_records.
     *
     * @return null for an unconstrained walk scope, else a criteria that never widens
     *         past the two sets
     */
    static @Nullable Criteria dnsRecordScope(@NonNull AccessContext ctx) {
        // The walk's tri-state instead of hand-written isAdmin/isAnonymous branches:
        // ALL (the admin row -- DnsRecordModel declares no type-level) is the
        // unconstrained answer, and an anonymous context already scopes to NONE here
        // AND contributes no hostname clauses below, so both prefixes were second
        // spellings of rows the walk owns. The composite itself cannot fold onto
        // grantScope: the derived-hostname half is not a grant question.
        RecordCapabilityScope granted = HohenheimAccess.capabilityScope(ctx,
            DnsRecordModel.MODEL_ID, HohenheimAccess.VIEW);
        if (granted.isAll()) {
            return null;
        }

        List<Criteria> reachable = new ArrayList<>(zoneScopedNameCriteria(ctx));
        Set<Integer> grantedIds = HohenheimAccess.grantedRecordIds(ctx,
            DnsRecordModel.MODEL_ID, HohenheimAccess.VIEW);
        if (!grantedIds.isEmpty()) {
            reachable.add(DnsRecordModel.ID.in(grantedIds));
        }

        if (reachable.isEmpty()) {
            return Models.get(DnsRecordModel.class).matchNone();
        }
        return reachable.size() == 1 ? reachable.get(0)
            : new CompositeCriteria(CompositeOperator.OR, reachable.toArray(new Criteria[0]));
    }

    /**
     * One {@code zone_id = z AND name IN (...)} clause per zone holding a managed hostname.
     *
     * AIDEV-NOTE: the site scope is read as a TRI-STATE, not as an id set. An every-site
     * holder (hohenheim.sites.manage_all) reaches every domain row, and asking for ids there
     * throws by design -- so the domain query drops its site filter instead of being handed
     * an empty set that would have silently produced no clauses at all.
     */
    private static @NonNull List<Criteria> zoneScopedNameCriteria(@NonNull AccessContext ctx) {
        RecordCapabilityScope sites = HohenheimAccess.capabilityScope(ctx, SiteModel.MODEL_ID,
            HohenheimAccess.MANAGE);
        if (sites.isNone()) {
            return List.of();
        }

        HostnameAuthority.Snapshot snapshot = HostnameAuthority.Snapshot.load();
        Set<String> hostnames = new LinkedHashSet<>();
        var domains = Models.get(SiteDomainModel.class).find();
        if (!sites.isAll()) {
            domains.where(SiteDomainModel.SITE_ID.in(
                HohenheimAccess.managedSiteIds(ctx)));
        }
        for (Row domain : domains.all()) {
            String hostname = domain.get(SiteDomainModel.HOSTNAME);
            if (hostname == null || hostname.isBlank()
                    || !SiteDomainModel.MATCH_EXACT.equals(domain.get(SiteDomainModel.MATCH_TYPE))) {
                continue;
            }
            // The SAME predicate the write side uses: a name a second, unmanaged site also
            // covers is a name two owners answer for, and neither of them alone owns it.
            if (HostnameAuthority.canManage(snapshot, ctx, hostname)) {
                hostnames.add(BlastString.lower(hostname.trim()));
            }
        }
        if (hostnames.isEmpty()) {
            return List.of();
        }

        List<Criteria> perZone = new ArrayList<>();
        for (Row zone : Models.get(DnsZoneModel.class).find().all()) {
            String origin = zone.get(DnsZoneModel.ORIGIN);
            Integer zoneId = zone.get(DnsZoneModel.ID);
            if (origin == null || zoneId == null) {
                continue;
            }
            Set<String> owners = new LinkedHashSet<>();
            for (String hostname : hostnames) {
                String owner = DnsNames.relative(origin, hostname);
                if (owner != null) {
                    owners.add(owner);
                }
            }
            if (!owners.isEmpty()) {
                perZone.add(new CompositeCriteria(CompositeOperator.AND,
                    DnsRecordModel.ZONE_ID.eq(zoneId), DnsRecordModel.NAME.in(owners)));
            }
        }
        return perZone;
    }

    /**
     * The certificates a principal may read: exactly the rows the walk confirms
     * {@code view} on -- the ones it REQUESTED arrive through the walk's own owner row
     * (CertificateModel declares {@code ownedBy(requested_by_user_id)} and VIEW is
     * owner-implied), the rest through explicit grants.
     *
     * AIDEV-NOTE: no hand-written {@code REQUESTED_BY_USER_ID.eq(principalId)} disjunct
     * beside the walk. That was a SECOND spelling of the owner row, and a WIDER one:
     * the walk's version also demands the credential's scope cover the capability
     * (Principal.coversCapability -- see the framework note on why a scope-narrowed API
     * key must not inherit owner-implied capabilities) and sits behind the gate-denial
     * row. The hand-written disjunct consulted neither, so a narrowed API key could
     * enumerate every certificate its owning user ever requested. Same lesson as
     * Projects.coversOwnedVocabulary, one tier over.
     *
     * AIDEV-NOTE: deliberately NOT "every certificate covering a managed domain", which the
     * superseded AIDEV-TODO in HohenheimSources proposed. Coverage is authority to REQUEST a
     * certificate and CertificateAuthority already owns that question; making it a READ scope
     * too would put a second authority beside the capability walk this vocabulary declares,
     * and the two would disagree the moment a name moves between sites.
     *
     * @return null for admins, else a criteria matching only the walk-reachable rows
     */
    static @Nullable Criteria certificateScope(@NonNull AccessContext ctx) {
        return HohenheimAccess.grantScope(ctx, Models.get(CertificateModel.class),
            CertificateModel.MODEL_ID, HohenheimAccess.VIEW, CertificateModel.ID::in);
    }

    /**
     * @return null for an unconstrained scope (the admin row, an every-site holder),
     *         an impossible criteria for principals without grants, else
     *         {@code ID IN (managed ids)}
     */
    static @Nullable Criteria siteScope(@NonNull AccessContext ctx) {
        return HohenheimAccess.managedSiteScope(ctx, Models.get(SiteModel.class), SiteModel.ID::in);
    }

    /**
     * @return null for admins, else the domains of the principal's managed sites
     */
    static @Nullable Criteria domainScope(@NonNull AccessContext ctx) {
        return HohenheimAccess.managedSiteScope(ctx, Models.get(SiteDomainModel.class),
            SiteDomainModel.SITE_ID::in);
    }

    /**
     * @return null for admins, else the previews of the principal's managed sites
     */
    static @Nullable Criteria previewScope(@NonNull AccessContext ctx) {
        return HohenheimAccess.managedSiteScope(ctx,
            Models.get(PreviewDeploymentModel.class),
            PreviewDeploymentModel.SITE_ID::in);
    }

    /** Matches nothing; NEVER ID.in(empty), which some backends reject or widen. */
    static @NonNull Criteria impossible() {
        return Models.get(SiteModel.class).matchNone();
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
