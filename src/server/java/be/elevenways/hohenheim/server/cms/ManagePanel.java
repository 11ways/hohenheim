package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.HostnameAuthority;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.CompositeCriteria;
import be.elevenways.zenit.common.orm.query.criteria.CompositeOperator;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.security.PermissionChecker;
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

    private static volatile boolean sourceRegistered = false;

    public ManagePanel() {
        super(Identifier.of("hohenheim", "manage"), "manage",
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
            AccessContext ctx = RecordSourceGate.accessContextOf(conduit);
            return !HohenheimAccess.managedSiteIds(ctx).isEmpty()
                || !HohenheimAccess.instanceIdsWith(ctx, HohenheimAccess.MANAGE).isEmpty();
        }

        @Override
        public @Nullable Boolean decide(Conduit conduit, @NonNull Permission permission) {
            return this.delegate.decide(conduit, permission);
        }
    }

    @Override
    public @NonNull List<PanelPeer> buildPeers() {
        return List.of(new ManageSiteResource(), new ManageDomainResource(),
            new ManageDnsRecordResource(), new ManageCertificateResource(),
            // The instance tier's tenant projection. Every one of these is scoped by a
            // walk-confirmed record capability, and the two schedule peers plus the
            // from-template page are nav-hidden: they are reached THROUGH an instance
            // (or a template) whose own scope already decided the principal may be here.
            new ManageInstanceResource(), new ManageInstanceScheduleResource(),
            new ManageInstanceScheduleStepResource(), new ManageInstanceDeviceResource(),
            new ManageInstanceSnapshotResource(),
            new ManageInstanceBackupResource(), new ManageInstanceTemplateResource(),
            new InstanceFromTemplatePage());
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
            .accessCriteria(ctx -> HohenheimAccess.instanceScope(ctx, HohenheimAccess.MANAGE))
            .build());

        // Record schedules: same hazard (the admin InstanceScheduleResource and the
        // delegated one both derive), and this source is the one a picker or a widget
        // would reach, so it carries the SAME scope the delegated resource enforces --
        // never the resource's scope in one place and an open source in another.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(RecordScheduleModel.class)
            .search(RecordScheduleModel.NAME)
            .accessCriteria(ManagePanel::recordScheduleScope)
            .build());

        // Instance devices: same two-derived-defaults hazard again, and the widest one
        // would list every tenant's disk names and sizes to whoever a picker rendered for.
        RecordSourceRegistry.INSTANCE.override(RecordSource.of(InstanceDeviceModel.class)
            .search(InstanceDeviceModel.NAME)
            .accessCriteria(ManagePanel::instanceDeviceScope)
            .build());
    }

    /** @return null for admins, else the devices of instances the principal manages */
    static @Nullable Criteria instanceDeviceScope(@NonNull AccessContext ctx) {
        if (HohenheimAccess.isAdmin(ctx)) {
            return null;
        }
        QueryPredicate predicate = ManageInstanceDeviceResource.decide(ctx).predicate();
        return predicate != null ? predicate.criteria()
            : Models.get(InstanceDeviceModel.class).matchNone();
    }

    /** @return null for admins, else the schedules of instances the principal manages */
    static @Nullable Criteria recordScheduleScope(@NonNull AccessContext ctx) {
        if (HohenheimAccess.isAdmin(ctx)) {
            return null;
        }
        QueryPredicate predicate = ManageInstanceScheduleResource.decide(ctx).predicate();
        return predicate != null ? predicate.criteria()
            : Models.get(RecordScheduleModel.class).matchNone();
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
     * @return null for admins, else a criteria that never widens past the two sets
     */
    static @Nullable Criteria dnsRecordScope(@NonNull AccessContext ctx) {
        Model model = Models.get(DnsRecordModel.class);
        if (HohenheimAccess.isAdmin(ctx)) {
            return null;
        }
        if (ctx.isAnonymous()) {
            return model.matchNone();
        }

        List<Criteria> reachable = new ArrayList<>(zoneScopedNameCriteria(ctx));
        Set<Integer> granted = HohenheimAccess.grantedRecordIds(ctx, DnsRecordModel.MODEL_ID,
            HohenheimAccess.VIEW);
        if (!granted.isEmpty()) {
            reachable.add(DnsRecordModel.ID.in(granted));
        }

        if (reachable.isEmpty()) {
            return model.matchNone();
        }
        return reachable.size() == 1 ? reachable.get(0)
            : new CompositeCriteria(CompositeOperator.OR, reachable.toArray(new Criteria[0]));
    }

    /** One {@code zone_id = z AND name IN (...)} clause per zone holding a managed hostname. */
    private static @NonNull List<Criteria> zoneScopedNameCriteria(@NonNull AccessContext ctx) {
        Set<Integer> managed = HohenheimAccess.managedSiteIds(ctx);
        if (managed.isEmpty()) {
            return List.of();
        }

        HostnameAuthority.Snapshot snapshot = HostnameAuthority.Snapshot.load();
        Set<String> hostnames = new LinkedHashSet<>();
        for (Row domain : Models.get(SiteDomainModel.class).find()
                .where(SiteDomainModel.SITE_ID.in(managed)).all()) {
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
     * The certificates a principal may read: the ones it REQUESTED (the walk's owner row,
     * enumerated) plus the ones explicitly granted {@code view}.
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
        Model model = Models.get(CertificateModel.class);
        if (HohenheimAccess.isAdmin(ctx)) {
            return null;
        }
        if (ctx.isAnonymous()) {
            return model.matchNone();
        }

        List<Criteria> reachable = new ArrayList<>();
        Long principalId = ctx.principalId();
        if (principalId != null) {
            reachable.add(CertificateModel.REQUESTED_BY_USER_ID.eq(principalId.intValue()));
        }
        Set<Integer> granted = HohenheimAccess.grantedRecordIds(ctx, CertificateModel.MODEL_ID,
            HohenheimAccess.VIEW);
        if (!granted.isEmpty()) {
            reachable.add(CertificateModel.ID.in(granted));
        }

        if (reachable.isEmpty()) {
            return model.matchNone();
        }
        return reachable.size() == 1 ? reachable.get(0)
            : new CompositeCriteria(CompositeOperator.OR, reachable.toArray(new Criteria[0]));
    }

    /**
     * @return null for admins (no extra constraint), an impossible criteria
     *         for principals without grants, else {@code ID IN (managed ids)}
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

    /** Matches nothing; NEVER ID.in(empty), which some backends reject or widen. */
    static @NonNull Criteria impossible() {
        return Models.get(SiteModel.class).matchNone();
    }

    /**
     * NAV-ONLY scope probe shared by the /manage peers: admins always have
     * scope; everyone else needs at least one walk-confirmed managed site.
     * Cheap per render thanks to the conduit-scoped managedSiteIds memo.
     */
    static boolean hasManageScope(@NonNull AccessContext ctx) {
        return HohenheimAccess.isAdmin(ctx) || !HohenheimAccess.managedSiteIds(ctx).isEmpty();
    }
}
