package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.GeneratedDnsRecords;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryContext;
import be.elevenways.zenit.common.routing.RouteScope;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a DELEGATED TENANT may write, enforced on the model write pipelines rather than on
 * any resource, form or handler.
 *
 * A write is tenant-originated when a request is in flight and its principal does NOT hold
 * the installation-wide admin permission. Everything else -- background tasks, seeds, the
 * ACME publisher, CLI tools -- runs with no ambient conduit and is system work.
 *
 * AIDEV-NOTE: the resource layer is NOT an option for any of this, for the reason
 * SiteDomainResource.installRouteInvariant spells out at length: the framework's generic
 * revision-restore endpoint, the peer API, the zone-file import and any direct model.save
 * all reach the datasource without passing a single resource method. A form that omits a
 * field is a UX affordance, never a gate -- a direct POST carries whatever it likes.
 *
 * AIDEV-NOTE: the domain rule is a WHITELIST (see {@link #DOMAIN_TENANT_WRITABLE}), not a
 * blacklist of the four fields the decision named. A blacklist silently opens every column
 * added later; the whitelist makes a new column tenant-frozen by default and forces whoever
 * adds it to decide.
 *
 * AIDEV-NOTE: enforcement here deliberately does NOT touch the read path of
 * {@code DnsRecordModel}. {@code /nic/update} (DYNDNS_UPDATE, public, token-authenticated)
 * resolves its record with a DIRECT model query by token digest and no principal at all --
 * adding a Schema.addBeforeFindHook scope to DnsRecordModel would 404 every router polling
 * in production while looking like a security improvement. That request DOES pass here on
 * the WRITE side (an anonymous conduit reads as tenant-originated) and is allowed because it
 * is bounded to VALUE on a stored DYNAMIC row inside the type allow-list. Do not "fix" the
 * read side; the tenant READ scope lives on the RecordSource (ManagePanel.dnsRecordScope).
 *
 * @author Jelle De Loecker
 */
public final class TenantWrites {

    /**
     * The only columns a delegated tenant may set on a site domain. Everything else must
     * keep the value it already has (or, on a create, its declared default).
     */
    private static final Set<String> DOMAIN_TENANT_WRITABLE = Set.of(
        SiteDomainModel.SITE_ID.getName(),
        SiteDomainModel.HOSTNAME.getName(),
        SiteDomainModel.FORCE_SSL.getName(),
        SiteDomainModel.HSTS_ENABLED.getName(),
        SiteDomainModel.HSTS_SUBDOMAINS.getName(),
        SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT.getName());

    /**
     * Columns the write pipeline DERIVES; comparing them against the stored row would refuse
     * ordinary saves rather than protect anything. The primary key identifies the row, the
     * timestamps are behaviour-stamped, and live_route_key is written by the route invariant
     * on a later tier of this very pass.
     */
    private static final Set<String> DOMAIN_DERIVED = Set.of(
        SiteDomainModel.ID.getName(),
        SiteDomainModel.CREATED_AT.getName(),
        SiteDomainModel.UPDATED_AT.getName(),
        SiteDomainModel.LIVE_ROUTE_KEY.getName());

    /**
     * The DNS record types a delegated tenant may author. NS/CAA/DS/DNSKEY/MX are absent by
     * DECISION, not by omission: NS delegates a subtree away, CAA disables or redirects
     * issuance for the whole name, and DS/DNSKEY are the DNSSEC trust root.
     */
    public static final Set<String> RECORD_TYPES = Set.of(
        DnsRecordModel.TYPE_A, DnsRecordModel.TYPE_AAAA, DnsRecordModel.TYPE_CNAME,
        DnsRecordModel.TYPE_TXT, DnsRecordModel.TYPE_SRV);

    /** The DnsRecordModel counterpart of {@link #DOMAIN_DERIVED}: pipeline-written columns. */
    private static final Set<String> RECORD_DERIVED = Set.of(
        DnsRecordModel.ID.getName(),
        DnsRecordModel.CREATED_AT.getName(),
        DnsRecordModel.UPDATED_AT.getName());

    private static volatile boolean installed;

    private TenantWrites() {
    }

    /**
     * @return the access context of the request in flight, or null outside a request
     */
    public static @Nullable AccessContext acting() {
        Conduit conduit = RouteScope.currentConduit();
        return conduit != null ? AccessContext.of(conduit) : null;
    }

    /**
     * Whether the write in flight belongs to a delegated tenant rather than to an operator
     * or to the system.
     *
     * AIDEV-NOTE: an ANONYMOUS request reads as tenant-originated on purpose (fail closed) --
     * that is what puts /nic/update under the DNS type allow-list instead of outside it.
     */
    public static boolean isTenantOriginated() {
        if (GeneratedDnsRecords.inSystemScope()) {
            return false;
        }
        AccessContext ctx = acting();
        return ctx != null && !HohenheimAccess.isAdmin(ctx);
    }

    /** Install the tenant-write invariants; idempotent, called at the MODULES boot stage. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        SiteDomainModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null && isTenantOriginated()) {
                checkDomainWrite(row);
            }
        });
        DnsRecordModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null && isTenantOriginated()) {
                checkRecordWrite(row);
            }
        });
        DnsRecordModel.SCHEMA.addBeforeRemoveHook(context -> {
            if (!isTenantOriginated()) {
                return;
            }
            AccessContext ctx = acting();
            HostnameAuthority.Snapshot snapshot = HostnameAuthority.Snapshot.load();
            for (Row doomed : doomedRecords(context)) {
                refuseForeignRecordType(doomed.get(DnsRecordModel.TYPE));
                // Removing a row is authority over the row, so it asks the SAME question a
                // write does -- minus the claim half, since a delete claims no new name. An
                // anonymous caller holds none of it: /nic/update updates, it never deletes.
                boolean authorized = ctx != null && !ctx.isAnonymous()
                    && (ctx.hasCapability(DnsRecordModel.MODEL_ID, doomed.get(DnsRecordModel.ID),
                            HohenheimAccess.EDIT)
                        || HostnameAuthority.canManage(snapshot, ctx, fqdnOf(doomed, doomed)));
                if (!authorized) {
                    throw refusal(DnsRecordModel.NAME.getName(), doomed.get(DnsRecordModel.NAME));
                }
            }
        });
    }

    // --- Site domains ----------------------------------------------------------------

    /**
     * Refuse a tenant domain write that reaches past the delegated field set.
     *
     * @throws Violations anchored on the offending field
     */
    private static void checkDomainWrite(@NonNull Row row) {
        Model model = Models.get(SiteDomainModel.class);
        Row stored = row.has(SiteDomainModel.ID.getName())
            ? model.findById(row.get(SiteDomainModel.ID)) : null;

        // The site the row will belong to must be one the tenant actually manages. The
        // resource's AccessFunction scopes what a tenant may READ; it says nothing about
        // the site_id a CREATE submits, and a MOVE to another site is the same question.
        Object siteIdValue = effective(row, stored, SiteDomainModel.SITE_ID);
        AccessContext ctx = acting();
        if (!(siteIdValue instanceof Integer siteId) || ctx == null
                || !HohenheimAccess.canManageSite(ctx, siteId)) {
            throw Violations.ofField(SiteDomainModel.SITE_ID.getName(), siteIdValue,
                CmsSupport.violationText("tenant_site_not_managed"));
        }

        // An unbounded hostname set is not a delegable claim: a tenant wildcard swallows
        // every name under it and a regex can spell anything at all.
        Object matchType = effective(row, stored, SiteDomainModel.MATCH_TYPE);
        if (matchType != null && !SiteDomainModel.MATCH_EXACT.equals(matchType)) {
            throw Violations.ofField(SiteDomainModel.MATCH_TYPE.getName(), matchType,
                CmsSupport.violationText("tenant_match_type_exact"));
        }

        // A listener restriction makes the row DISJOINT from every other row's listener set,
        // and refuseRouteConflicts exempts disjoint sets by design -- so a tenant listener
        // walks straight past the hostname-overlap refusal.
        if (isPresent(effective(row, stored, SiteDomainModel.LISTEN_ON))) {
            throw Violations.ofField(SiteDomainModel.LISTEN_ON.getName(),
                effective(row, stored, SiteDomainModel.LISTEN_ON),
                CmsSupport.violationText("tenant_listen_on_frozen"));
        }

        // The SAME exemption applies to a differing path, one dimension over: two rows with
        // different paths are treated as distinct routes and never compared for hostname
        // overlap, so a tenant path carve-out on another tenant's live hostname would be
        // accepted. Freezing PATH empty for tenants is what keeps every tenant-vs-tenant
        // pair comparable. Admin-authored path carve-outs are unaffected (and are the
        // legitimate case the exemption exists for).
        if (isPresent(effective(row, stored, SiteDomainModel.PATH))) {
            throw Violations.ofField(SiteDomainModel.PATH.getName(),
                effective(row, stored, SiteDomainModel.PATH),
                CmsSupport.violationText("tenant_path_frozen"));
        }

        // Everything outside the delegated set keeps its stored value (its default on a
        // create). certificate_id lands here: pinning a certificate row is authority over a
        // name the tenant may not hold, and the picker failing closed is incidental, not a
        // gate.
        for (Field<?, ?> field : model.getSchema().getFields().values()) {
            String name = field.getName();
            if (DOMAIN_TENANT_WRITABLE.contains(name) || DOMAIN_DERIVED.contains(name)
                    || !row.has(name)) {
                continue;
            }
            Object baseline = stored != null ? stored.get(name) : field.getDefaultValue();
            if (!Objects.equals(row.get(name), baseline)) {
                throw Violations.ofField(name, row.get(name),
                    CmsSupport.violationText("tenant_field_frozen"));
            }
        }
    }

    // --- DNS records -----------------------------------------------------------------

    /**
     * Refuse a tenant DNS write outside the type allow-list, or one that re-homes or
     * re-labels the row.
     *
     * @throws Violations anchored on the offending field
     */
    private static void checkRecordWrite(@NonNull Row row) {
        Model model = Models.get(DnsRecordModel.class);
        Row stored = row.has(DnsRecordModel.ID.getName())
            ? model.findById(row.get(DnsRecordModel.ID)) : null;

        requireRecordAuthority(row, stored);
        refuseForeignRecordType(effective(row, stored, DnsRecordModel.TYPE));
        // A record being EDITED must have been allow-listed before the edit too, otherwise
        // "change the type of the NS row to A" launders it in.
        if (stored != null) {
            refuseForeignRecordType(stored.get(DnsRecordModel.TYPE));
        }

        // Moving a row between zones is a takeover primitive: it carries a name a tenant may
        // hold into a zone they do not.
        if (stored != null && row.has(DnsRecordModel.ZONE_ID.getName())
                && !Objects.equals(row.get(DnsRecordModel.ZONE_ID),
                    stored.get(DnsRecordModel.ZONE_ID))) {
            throw Violations.ofField(DnsRecordModel.ZONE_ID.getName(),
                row.get(DnsRecordModel.ZONE_ID),
                CmsSupport.violationText("tenant_zone_frozen"));
        }

        // managed_by drives the zone-file import's replace scope (it replaces only rows where
        // it is NULL), so writing it is writing that import's blast radius.
        Object storedManagedBy = stored != null ? stored.get(DnsRecordModel.MANAGED_BY) : null;
        if (row.has(DnsRecordModel.MANAGED_BY.getName())
                && !Objects.equals(row.get(DnsRecordModel.MANAGED_BY), storedManagedBy)) {
            throw Violations.ofField(DnsRecordModel.MANAGED_BY.getName(),
                row.get(DnsRecordModel.MANAGED_BY),
                CmsSupport.violationText("tenant_managed_by_frozen"));
        }
    }

    /**
     * WHOSE name this is. The type allow-list says what a tenant may author; this says where.
     *
     * Two lanes, and they are a UNION of authorities rather than two policies that can
     * disagree: HOSTNAME authority (the row's FQDN is covered by a domain of a site the
     * caller manages -- the same {@link HostnameAuthority} predicate a certificate order
     * asks) and an explicit per-record GRANT ({@code edit}, or {@code dyndns} narrowed to the
     * dynamic columns). Renaming or creating additionally needs hostname authority over the
     * name being CLAIMED, or a grant would launder into a takeover of a name it never covered.
     *
     * @throws Violations naming the field that carries the claim
     */
    private static void requireRecordAuthority(@NonNull Row row, @Nullable Row stored) {
        AccessContext ctx = acting();
        if (ctx == null) {
            return;
        }

        // AIDEV-NOTE: the ANONYMOUS lane is /nic/update and nothing else. It is already
        // authorized -- by a bearer token resolved against the row's digest before any write
        // -- so it is not asked for a capability it structurally cannot hold. It is bounded
        // instead: a stored DYNAMIC row, and VALUE the only column that may move. Without
        // that bound the exemption would be the bypass shape it looks like.
        if (ctx.isAnonymous()) {
            if (stored == null || !Boolean.TRUE.equals(stored.get(DnsRecordModel.DYNAMIC))) {
                throw refusal(DnsRecordModel.NAME.getName(), effective(row, stored, DnsRecordModel.NAME));
            }
            refuseChangesOutside(row, stored, Set.of(DnsRecordModel.VALUE.getName()));
            return;
        }

        Object recordId = stored != null ? stored.get(DnsRecordModel.ID) : null;
        boolean holdsEdit = recordId != null
            && ctx.hasCapability(DnsRecordModel.MODEL_ID, recordId, HohenheimAccess.EDIT);
        boolean holdsDyndns = recordId != null
            && ctx.hasCapability(DnsRecordModel.MODEL_ID, recordId, HohenheimAccess.DYNDNS);

        HostnameAuthority.Snapshot snapshot = HostnameAuthority.Snapshot.load();
        boolean ownsStoredName = stored != null
            && HostnameAuthority.canManage(snapshot, ctx, fqdnOf(stored, stored));

        // A CREATE has no row to hold authority over, so it answers to the claim half alone.
        if (stored != null && !holdsEdit && !ownsStoredName) {
            if (holdsDyndns) {
                // dyndns alone is authority over the token, never over the record: only the
                // two dynamic columns may move (the mint row action writes exactly those).
                refuseChangesOutside(row, stored, Set.of(DnsRecordModel.DYNAMIC.getName(),
                    DnsRecordModel.DYNDNS_TOKEN.getName()));
                return;
            }
            throw refusal(DnsRecordModel.NAME.getName(), effective(row, stored, DnsRecordModel.NAME));
        }

        String claimed = fqdnOf(row, stored);
        boolean claimsNewName = stored == null
            || !claimed.equals(fqdnOf(stored, stored));
        if (claimsNewName && !HostnameAuthority.canManage(snapshot, ctx, claimed)) {
            throw refusal(DnsRecordModel.NAME.getName(), claimed);
        }
    }

    /** @return the fully qualified name the write ends up with, or "" when its zone is gone */
    private static @NonNull String fqdnOf(@NonNull Row row, @Nullable Row stored) {
        Object zoneId = effective(row, stored, DnsRecordModel.ZONE_ID);
        Object name = effective(row, stored, DnsRecordModel.NAME);
        if (zoneId == null || name == null) {
            return "";
        }
        Row zone = Models.get(DnsZoneModel.class).findById(zoneId);
        String origin = zone != null ? zone.get(DnsZoneModel.ORIGIN) : null;
        return origin != null ? DnsNames.absolute(origin, String.valueOf(name)) : "";
    }

    /** Every column outside {@code writable} must keep its stored value (its default on a create). */
    private static void refuseChangesOutside(@NonNull Row row, @Nullable Row stored,
                                             @NonNull Set<String> writable) {
        Model model = Models.get(DnsRecordModel.class);
        for (Field<?, ?> field : model.getSchema().getFields().values()) {
            String name = field.getName();
            if (writable.contains(name) || RECORD_DERIVED.contains(name) || !row.has(name)) {
                continue;
            }
            Object baseline = stored != null ? stored.get(name) : field.getDefaultValue();
            if (!Objects.equals(row.get(name), baseline)) {
                throw refusal(name, row.get(name));
            }
        }
    }

    private static @NonNull Violations refusal(@NonNull String field, @Nullable Object value) {
        return Violations.ofField(field, value,
            CmsSupport.violationText("tenant_record_not_authorized"));
    }

    private static void refuseForeignRecordType(@Nullable Object type) {
        String text = type != null ? String.valueOf(type) : "";
        if (!RECORD_TYPES.contains(text)) {
            throw Violations.ofField(DnsRecordModel.TYPE.getName(), text,
                CmsSupport.violationText("tenant_record_type"));
        }
    }

    /**
     * The rows a criteria delete is about to remove.
     *
     * AIDEV-NOTE: a remove context carries CRITERIA, not rows -- the same re-query idiom
     * GeneratedDnsRecords.doomedRows uses, and for the same reason: enforcing on the
     * resource's delete method would leave every criteria delete outside the guard.
     */
    private static @NonNull List<Row> doomedRecords(@NonNull RemoveFromDatasource context) {
        Model model = context.getModel();
        QueryContext queryContext = context.getQueryContext();
        if (model == null || queryContext == null) {
            return List.of();
        }
        return model.executeFindQuery(new QueryContext(
            queryContext.getCriteria(), List.of(), null, null, List.of(), null,
            queryContext.getRelatedFilters(), queryContext.getLocaleChain(),
            queryContext.isAcrossLocales(), true, true, queryContext.getHints()));
    }

    /** The value the write will END UP with, reading the already-loaded stored row. */
    private static @Nullable Object effective(@NonNull Row row, @Nullable Row stored,
                                              @NonNull Field<?, ?> field) {
        if (row.has(field.getName())) {
            return row.get(field.getName());
        }
        return stored != null ? stored.get(field.getName()) : field.getDefaultValue();
    }

    private static boolean isPresent(@Nullable Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }
}
