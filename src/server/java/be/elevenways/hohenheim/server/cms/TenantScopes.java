package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.HostnameAuthority;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.CompositeCriteria;
import be.elevenways.zenit.common.orm.query.criteria.CompositeOperator;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.RecordCapabilityScope;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * THE delegated read scope of every model the /manage panel projects, declared once and
 * read by both the Manage* resource's accessFunction and ManagePanel's record source.
 *
 * AIDEV-NOTE: until 2026-09-23 each Manage* resource spelled its scope in its own
 * accessFunction and ManagePanel.declareSources spelled it again for the record source, and
 * the two had drifted: the instance source offered GENERATED (product-tier-owned) rows the
 * list hid, and the domain source listed the domains of soft-deleted sites the list hid. A
 * {@link Scope} is the pair a source and a list both need -- a BASE every principal is
 * narrowed by and the per-principal ACCESS half -- so a surface can only differ from its
 * picker by naming a DIFFERENT scope, which is then visible here.
 *
 * AIDEV-NOTE: two models deliberately carry TWO scopes, both declared here so the difference
 * is written down: access lists and git providers list only the rows a tenant MANAGES
 * ({@link #MANAGED_ACCESS_LISTS}, {@link #MANAGED_GIT_PROVIDERS}), while their pickers also
 * offer the operator's SHARED rows ({@link #USABLE_ACCESS_LISTS}, {@link #USABLE_GIT_PROVIDERS}).
 * A tenant may USE a shared row and must never open, retype or delete it.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class TenantScopes {

    /**
     * One model's delegated scope.
     *
     * @param base   the criteria every principal is narrowed by (admins included), or null
     * @param access the per-principal half; it answers null for an unconstrained principal
     */
    public record Scope(@Nullable Supplier<Criteria> base,
                        @NonNull Function<AccessContext, @Nullable Criteria> access) {

        /** @return base AND access, or null when neither constrains this principal */
        public @Nullable Criteria criteria(@NonNull AccessContext ctx) {
            Criteria baseCriteria = this.base != null ? this.base.get() : null;
            Criteria accessCriteria = this.access.apply(ctx);
            if (baseCriteria == null) {
                return accessCriteria;
            }
            return accessCriteria == null ? baseCriteria : Criteria.and(baseCriteria, accessCriteria);
        }

        /** @return the cms decision a Manage* resource's accessFunction answers */
        public @NonNull AccessDecision decide(@NonNull AccessContext ctx) {
            return TenantScopes.decision(this.criteria(ctx));
        }

        /** @return this scope as a resource access function */
        public @NonNull AccessFunction<Row> accessFunction() {
            return this::decide;
        }

        /** @return the builder with this scope's base and access halves declared on it */
        public <M extends Model> RecordSource.@NonNull Builder<M> applyTo(
                RecordSource.@NonNull Builder<M> builder) {
            if (this.base != null) {
                builder.baseCriteria(this.base);
            }
            return builder.accessCriteria(this.access);
        }
    }

    /** Live sites; tenants only the ones they manage. */
    public static final Scope SITES = new Scope(() -> SiteModel.DELETED_AT.isNull(),
        TenantScopes::siteAccess);

    /** Domains of live sites; tenants only those of the sites they manage. */
    public static final Scope DOMAINS = new Scope(SiteDomainResource::liveSiteScope,
        ctx -> HohenheimAccess.managedSiteScope(ctx, Models.get(SiteDomainModel.class),
            SiteDomainModel.SITE_ID::in));

    /** Protected paths; tenants only those of the sites they manage. */
    public static final Scope PROTECTED_PATHS = new Scope(null,
        ctx -> HohenheimAccess.managedSiteScope(ctx, Models.get(ProtectedPathModel.class),
            ProtectedPathModel.SITE_ID::in));

    /** DNS records; tenants only the names they answer for plus explicit view grants. */
    public static final Scope DNS_RECORDS = new Scope(null, TenantScopes::dnsRecordAccess);

    /**
     * Certificates minus the ACME account row; tenants only the walk-reachable ones.
     *
     * AIDEV-NOTE: the base is THE {@link HohenheimSources#notTheAcmeAccountRow}, never a
     * second spelling of the exclusion (ManageCertificateResource used to carry one).
     */
    public static final Scope CERTIFICATES = new Scope(HohenheimSources::notTheAcmeAccountRow,
        ctx -> HohenheimAccess.grantScope(ctx, Models.get(CertificateModel.class),
            CertificateModel.MODEL_ID, HohenheimAccess.VIEW, CertificateModel.ID::in));

    /**
     * Live AUTHORED instances; tenants only the ones the walk confirms {@code view} on.
     *
     * AIDEV-NOTE: GENERATED rows (database engines, stack services, releases) are managed
     * through their owning record's surface and never through a picker or the /manage
     * list -- the same clause InstanceApi.visibleInstances applies.
     */
    public static final Scope INSTANCES = new Scope(
        () -> Criteria.and(InstanceModel.DELETED_AT.isNull(), InstanceModel.GENERATED_BY.isNull()),
        ctx -> HohenheimAccess.instanceScope(ctx, HohenheimAccess.VIEW));

    /** The template catalog: operators browse everything, everyone else only APPROVED rows. */
    public static final Scope INSTANCE_TEMPLATES = new Scope(null,
        ctx -> HohenheimAccess.isAdmin(ctx) ? null : InstanceTemplateModel.APPROVED_AT.isNotNull());

    /** Instance schedules; tenants only those of viewable instances. */
    public static final Scope INSTANCE_SCHEDULES = new Scope(
        () -> RecordScheduleModel.MODEL.eq(InstanceModel.MODEL_ID.toString()),
        ctx -> HohenheimAccess.grantScope(ctx, Models.get(RecordScheduleModel.class),
            InstanceModel.MODEL_ID, HohenheimAccess.VIEW, TenantScopes::recordIdIn));

    /** Projects: THE visibility policy (membership, narrowed by an API key's scopes). */
    public static final Scope PROJECTS = new Scope(null, Projects::visibleScope);

    /** Managed databases; tenants only the ones they hold {@code view} on. */
    public static final Scope DATABASES = new Scope(null,
        ctx -> HohenheimAccess.databaseScope(ctx, HohenheimAccess.VIEW));

    /** Devices attached to an instance; tenants only those of viewable instances. */
    public static final Scope INSTANCE_DEVICES = new Scope(
        () -> InstanceDeviceModel.INSTANCE_ID.isNotNull(),
        ctx -> HohenheimAccess.grantScope(ctx, Models.get(InstanceDeviceModel.class),
            InstanceModel.MODEL_ID, HohenheimAccess.VIEW, InstanceDeviceModel.INSTANCE_ID::in));

    /** Instance-database attachments; tenants only those of viewable instances. */
    public static final Scope INSTANCE_DATABASES = new Scope(
        () -> InstanceDatabaseModel.INSTANCE_ID.isNotNull(),
        ctx -> HohenheimAccess.grantScope(ctx, Models.get(InstanceDatabaseModel.class),
            InstanceModel.MODEL_ID, HohenheimAccess.VIEW, InstanceDatabaseModel.INSTANCE_ID::in));

    /** Snapshots of the instances the principal holds {@code snapshots} on. */
    public static final Scope INSTANCE_SNAPSHOTS = new Scope(null,
        ctx -> HohenheimAccess.grantScope(ctx, Models.get(InstanceSnapshotModel.class),
            InstanceModel.MODEL_ID, HohenheimAccess.SNAPSHOTS, InstanceSnapshotModel.INSTANCE_ID::in));

    /** Backups of the instances the principal holds {@code backups} on. */
    public static final Scope INSTANCE_BACKUPS = new Scope(null,
        ctx -> HohenheimAccess.grantScope(ctx, Models.get(InstanceBackupModel.class),
            InstanceModel.MODEL_ID, HohenheimAccess.BACKUPS, InstanceBackupModel.INSTANCE_ID::in));

    /** Live previews; tenants only those of the APPLICATIONS they manage. */
    public static final Scope PREVIEWS = new Scope(() -> PreviewDeploymentModel.DELETED_AT.isNull(),
        ctx -> HohenheimAccess.grantScope(ctx, Models.get(PreviewDeploymentModel.class),
            InstanceModel.MODEL_ID, HohenheimAccess.MANAGE, PreviewDeploymentModel.APPLICATION_ID::in));

    /** The access lists a tenant OWNS: the /manage list. */
    public static final Scope MANAGED_ACCESS_LISTS = new Scope(null,
        ctx -> HohenheimAccess.grantScope(ctx, Models.get(AccessListModel.class),
            AccessListModel.MODEL_ID, HohenheimAccess.MANAGE, AccessListModel.ID::in));

    /** The access lists a tenant may ATTACH: shared rows plus the managed ones (the pickers). */
    public static final Scope USABLE_ACCESS_LISTS = new Scope(null, HohenheimAccess::accessListScope);

    /** The rules of the access lists a tenant manages. */
    public static final Scope ACCESS_RULES = new Scope(null,
        ctx -> HohenheimAccess.grantScope(ctx, Models.get(AccessRuleModel.class),
            AccessListModel.MODEL_ID, HohenheimAccess.MANAGE, AccessRuleModel.ACCESS_LIST_ID::in));

    /** The git providers a tenant OWNS: the /manage list. */
    public static final Scope MANAGED_GIT_PROVIDERS = new Scope(null,
        ctx -> HohenheimAccess.grantScope(ctx, Models.get(GitProviderModel.class),
            GitProviderModel.MODEL_ID, HohenheimAccess.MANAGE, GitProviderModel.ID::in));

    /** The git providers a tenant may USE: shared rows plus the managed ones (the pickers). */
    public static final Scope USABLE_GIT_PROVIDERS = new Scope(null, HohenheimAccess::gitProviderScope);

    private TenantScopes() {
    }

    /**
     * THE translation of a scope criteria into a cms decision: null (unconstrained) allows
     * everything, anything else allows exactly the rows it matches.
     */
    public static @NonNull AccessDecision decision(@Nullable Criteria scope) {
        return scope == null ? AccessDecision.allowAll() : AccessDecision.allow(QueryPredicate.of(scope));
    }

    /**
     * @return null for an unconstrained scope (the admin row, an every-site holder), an
     *         impossible criteria for principals without grants, else {@code ID IN (managed ids)}
     */
    private static @Nullable Criteria siteAccess(@NonNull AccessContext ctx) {
        return HohenheimAccess.managedSiteScope(ctx, Models.get(SiteModel.class), SiteModel.ID::in);
    }

    /** Record schedules key their target polymorphically, so the id set folds to strings. */
    private static @NonNull Criteria recordIdIn(@NonNull Set<Integer> instanceIds) {
        Set<String> ids = new LinkedHashSet<>();
        for (Integer id : instanceIds) {
            ids.add(String.valueOf(id));
        }
        return RecordScheduleModel.RECORD_ID.in(ids);
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
    private static @Nullable Criteria dnsRecordAccess(@NonNull AccessContext ctx) {
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
            // The SAME predicate the write side uses: the most specific covering rows
            // decide the name, so an exact row of a managed site lists its names even
            // under an operator's catch-all wildcard, while a name an equally specific
            // foreign row also covers is a name two owners answer for and lists nothing.
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
}
