package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RecordSubpageRegistry;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.Nested;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The /manage view over individual DNS records: a tenant authors names under the hostnames
 * it already serves, and never learns that a zone row exists.
 *
 * AIDEV-NOTE: the form takes an ABSOLUTE name and this resource resolves the owning zone
 * from it, because a zone picker is exactly the thing the Phase 2 decision refuses to give a
 * tenant (docs/instance-tier-plan.md: zones are a permanent admin-only non-goal). The
 * resolution is a UX affordance, NOT the gate -- TenantWrites decides authority over the
 * resulting FQDN on the model write pipeline, which a direct POST, the peer API and a
 * revision restore all pass and this method does not.
 */
public final class ManageDnsRecordResource extends DnsRecordResource {

    private final FormSpec manageFormSpec = FormSpec.builder()
        .add(DnsRecordModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(DnsRecordModel.TYPE))
        .add(DnsRecordModel.VALUE)
        .add(Nested.of(DnsRecordModel.DATA).schemaFrom("type").build())
        .add(DnsRecordModel.TTL)
        .add(DnsRecordModel.ENABLED)
        .build();

    private final TableSpec<Row> manageTableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(DnsRecordModel.NAME).build())
        .column(ColumnSpec.fromField(DnsRecordModel.TYPE).build())
        .column(ColumnSpec.fromField(DnsRecordModel.VALUE).copyable().build())
        .column(ColumnSpec.fromField(DnsRecordModel.ENABLED).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "manage_dns_record"); }
    @Override public @NonNull FormSpec formSpec() { return this.manageFormSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.manageTableSpec; }
    @Override public boolean showInNav() { return true; }
    @Override public int navOrder() { return 35; }

    @Override
    public @Nullable Microcopy description() {
        return CmsSupport.navHint("dns_record");
    }
    /** Admins see every record; everyone else only the names they answer for. */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> {
            Criteria scope = ManagePanel.dnsRecordScope(ctx);
            return scope == null
                ? AccessDecision.allowAll()
                : AccessDecision.allow(QueryPredicate.of(scope));
        };
    }

    /**
     * No quick-add bar here: the admin declaration presets {@code zone_id}, which this
     * form does not carry at all -- a tenant names the record absolutely and the zone is
     * RESOLVED from it (a zone picker is the permanent non-goal this resource exists to
     * avoid). Inheriting that declaration would refuse this peer's registration outright.
     */
    @Override
    public @Nullable QuickCreateSpec quickCreate() {
        return null;
    }

    /**
     * Answer the search a tenant actually types.
     *
     * AIDEV-NOTE: this list RENDERS absolute names while the column STORES relative owners,
     * so the inherited searchFields(NAME, VALUE) matched nothing for anyone who typed what
     * they were looking at. The term is rewritten to the owner relative to its hosted zone
     * before the query sees it, and ONLY when that owner is a real substring of the typed
     * text -- which is what makes the rewrite a widening: the match is a contains, so every
     * row the typed term matched still matches.
     *
     * AIDEV-NOTE: that property was CLAIMED here before and was FALSE. A term equal to a
     * hosted origin relativizes to the APEX marker "@", which is not a substring of anything
     * the operator typed: searching "example.com" rewrote to "@" and returned the apex rows
     * of EVERY visible zone while losing every genuine hit (a CNAME whose value is
     * "example.com" does not contain "@"). The longest-origin tie-break made it worse -- with
     * example.com and sub.example.com both hosted, "sub.example.com" collapsed the same way.
     * An apex owner is therefore NOT a rewrite; see {@link #relativeTerm}.
     */
    @Override
    public @NonNull List<Row> listRows(TableView.@NonNull Applied<Row> applied,
                                       @NonNull AccessContext accessContext) {
        String term = applied.searchTerm();
        String relative = term == null ? null : relativeTerm(term);
        return super.listRows(relative == null ? applied : applied.withSearch(relative), accessContext);
    }

    /**
     * The typed term rewritten to the owner relative to the longest hosted zone containing
     * it, or null when no rewrite applies.
     *
     * AIDEV-NOTE: null for an APEX owner is the whole point, not an oversight. The residual
     * is deliberate and narrow: an exact-origin search matches by VALUE only, because the
     * apex rows a tenant would also want are stored as "@" and the search lane carries one
     * substring, so asking for them costs every other zone's apex.
     */
    private static @Nullable String relativeTerm(@NonNull String term) {
        String best = null;
        int bestOrigin = -1;
        for (Row zone : Models.get(DnsZoneModel.class).find().all()) {
            String origin = zone.get(DnsZoneModel.ORIGIN);
            if (origin == null) {
                continue;
            }
            String owner = DnsNames.relative(origin, term.trim());
            if (owner != null && !DnsNames.APEX.equals(owner) && origin.length() > bestOrigin) {
                best = owner;
                bestOrigin = origin.length();
            }
        }
        return best;
    }

    /** The list renders the absolute name; a tenant has no relative-to-what to read it against. */
    @Override
    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {
        if (DnsRecordModel.NAME.getName().equals(column.name())) {
            return absoluteName(row);
        }
        return super.cellValue(row, column);
    }

    @Override
    public @NonNull Map<String, Object> valuesFromRow(@NonNull Row row) {
        Map<String, Object> values = new HashMap<>(super.valuesFromRow(row));
        values.put(DnsRecordModel.NAME.getName(), absoluteName(row));
        return values;
    }

    /** zone_id is resolved from the name and is not a form entry; stamp it like the provider stamp. */
    @Override
    public @NonNull Row valuesToRow(@NonNull Map<String, Object> coerced) {
        Row row = super.valuesToRow(coerced);
        if (coerced.get(DnsRecordModel.ZONE_ID.getName()) instanceof Integer zoneId) {
            row.set(DnsRecordModel.ZONE_ID, zoneId);
        }
        return row;
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        return super.persistRow(resolveZone(coerced, null), accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        super.updateRow(existing, resolveZone(coerced, existing), accessContext);
    }

    /**
     * Split the submitted absolute name into (zone, relative owner).
     *
     * AIDEV-NOTE: a submitted name WINS, an absent one falls back to the stored row's own
     * absolute name -- the per-key partial-update semantic DnsRecordEdits already applies.
     * Without the fallback an update carrying only some entries (the inline cell lane
     * commits exactly ONE) resolved an empty name and refused with "no hosted zone contains
     * this name", which is a message about a field the operator never touched.
     *
     * @throws Violations when no hosted zone contains the name, or when an edit would move
     *         the row into a different zone (a re-home is a takeover primitive the write
     *         pipeline refuses anyway; refusing it here keeps the row and its owner label
     *         from ever disagreeing)
     */
    private @NonNull Map<String, Object> resolveZone(@NonNull Map<String, Object> coerced,
                                                     @Nullable Row existing) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        Object raw = values.get(DnsRecordModel.NAME.getName());
        String fqdn = raw != null ? String.valueOf(raw).trim()
            : existing != null ? absoluteName(existing) : "";

        Row bestZone = null;
        String bestOwner = null;
        for (Row zone : Models.get(DnsZoneModel.class).find().all()) {
            String origin = zone.get(DnsZoneModel.ORIGIN);
            if (origin == null) {
                continue;
            }
            String owner = DnsNames.relative(origin, fqdn);
            if (owner == null) {
                continue;
            }
            // Longest matching origin wins: a delegated child zone owns its own names.
            if (bestZone == null || origin.length()
                    > String.valueOf(bestZone.get(DnsZoneModel.ORIGIN)).length()) {
                bestZone = zone;
                bestOwner = owner;
            }
        }

        if (bestZone == null) {
            throw Violations.ofField(DnsRecordModel.NAME.getName(), fqdn,
                CmsSupport.violationText("tenant_record_no_zone"));
        }
        Integer zoneId = bestZone.get(DnsZoneModel.ID);
        if (existing != null && !zoneId.equals(existing.get(DnsRecordModel.ZONE_ID))) {
            throw Violations.ofField(DnsRecordModel.NAME.getName(), fqdn,
                CmsSupport.violationText("tenant_zone_frozen"));
        }

        values.put(DnsRecordModel.NAME.getName(), bestOwner);
        values.put(DnsRecordModel.ZONE_ID.getName(), zoneId);
        return values;
    }

    private static @NonNull String absoluteName(@NonNull Row row) {
        String name = row.get(DnsRecordModel.NAME);
        Row zone = Models.get(DnsZoneModel.class).findById(row.get(DnsRecordModel.ZONE_ID));
        String origin = zone != null ? zone.get(DnsZoneModel.ORIGIN) : null;
        return origin != null && name != null ? DnsNames.absolute(origin, name)
            : String.valueOf(name);
    }

    /**
     * The contributed subpages only (the generic access matrix): a delegable holder must be
     * able to delegate from /manage, and the page gates itself per record via visibleFor.
     * The admin activity/revision history stays off the delegated surface, exactly like
     * {@link ManageSiteResource}.
     */
    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        return new ArrayList<>(
            RecordSubpageRegistry.INSTANCE.contributionsFor(this.model().getModelId()));
    }

    /**
     * NAV-ONLY; the route itself stays scoped by accessFunction.
     *
     * AIDEV-NOTE: reachesAny, never "ids.isEmpty()" -- an id set cannot express every-record
     * authority, so the set spelling hid this peer from an every-site holder (and threw once
     * the walk started saying ALL out loud).
     */
    @Override
    public boolean hasInScopeRecords(@NonNull AccessContext access) {
        return HohenheimAccess.managesAnySite(access)
            || HohenheimAccess.reachesAny(access, DnsRecordModel.MODEL_ID, HohenheimAccess.VIEW);
    }
}
