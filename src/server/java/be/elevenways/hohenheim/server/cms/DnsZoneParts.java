package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.dns.DelegationVerdict;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.model.GroupedCounts;
import be.elevenways.hohenheim.server.dns.DelegationCheck;
import be.elevenways.hohenheim.server.dns.DnsDelegationHealth;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DnsNameservers;
import be.elevenways.hohenheim.server.dns.DnsSecondaryFreshness;
import be.elevenways.hohenheim.server.dns.DnsZoneSnapshot;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.SystemDelegationLookup;
import be.elevenways.zenit.common.validation.validator.Email;
import be.elevenways.zenit.common.validation.ValidationContext;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.ChildList;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteScope;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.hohenheim.net.Hostnames;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * Hosted authoritative DNS zones. The serial is framework-managed: every
 * zone or record mutation bumps it and swaps the serving snapshot.
 */
public final class DnsZoneParts {

    public static @NonNull PanelResource<Row> admin() {
        DnsOperations.init();
        DnsZoneParts parts = new DnsZoneParts();
        return PanelResource.builder(HohenheimIds.id("dns_zone"), HohenheimSlugs.DNS_ZONES, DnsOperations.ZONE)
            .label(HohenheimMicrocopy.DNS_ZONE.of("plural")).recordLabel(HohenheimMicrocopy.DNS_ZONE.of("singular"))
            .description(HohenheimMicrocopy.DNS_ZONE.of("nav_hint"))
            .icon(Icon.of("sitemap")).navGroup(HohenheimPanel.NETWORK_GROUP).navOrder(10)
            .reads(ResourceReads.rows().mapCells(parts::cellValue)
                .mapValues((row, values) -> parts.valuesFromRow(row)))
            .list(ResourceList.rows(parts.tableSpec()).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(parts.searchFields().toArray(Field<?, ?>[]::new))
                .computed(parts.tableSpec().column("secondaries"), (row, request) -> secondarySummary(row))
                .computed(parts.tableSpec().column("record_count"), (row, request) -> recordCount(row))
                .rowLink((row, request) -> CmsRoutes.subpage(request.panelSlug(), HohenheimSlugs.DNS_ZONES,
                    row.get(DnsZoneModel.ID), HohenheimSlugs.Tab.RECORDS)).build())
            .form(ResourceForm.<Row>of(parts.formSpec()).bindings(parts.fieldBindings())
                .quickCreate(parts.quickCreate()).build())
            .writes(ResourceMutations.rows().create(call -> parts.persistRow(call.values(), call.access()))
                .update(call -> { parts.updateRow(call.record(), call.values(), call.access()); return null; })
                .delete(DnsOperations.DELETE_ZONE).build())
            .deleteConfirmation(DeleteConfirmation.<Row>of(parts.deleteConfirmation())
                .forRow((row, request) -> parts.deleteConfirmationFor(row)))
            .actions(List.of(PanelAction.<Row>link(HohenheimIds.id("dns_records"), ActionPlacement.ROW)
                .label(HohenheimMicrocopy.DNS_ZONE.of("records"))
                .description(HohenheimMicrocopy.DNS_ZONE.of("records_hint"))
                .icon(Icon.of("list-ul")).inlineInRow(false)
                .route((row, request) -> CmsRoutes.subpage(request.panelSlug(), HohenheimSlugs.DNS_ZONES,
                    row.get(DnsZoneModel.ID), HohenheimSlugs.Tab.RECORDS)).build(),
                PanelAction.<Row, CmsActionResult>places(DnsOperations.CHECK_HEALTH, ActionPlacement.ROW,
                    (request, result) -> result.value()).build()))
            .tabs(ResourceTabs.<Row>of(List.of(new DnsZoneRecordsPage(), new DnsZoneFilePage(),
                secondariesTab())).withHistory().withContributions())
            .relatedPages(parts.relatedPages().toArray(RelatedPage[]::new)).build();
    }

    /**
     * The Secondaries tab: the framework's child list over the zone-peer entry, narrowed to the zone. A secondary zone
     * has none: its authority lives on its own primary.
     */
    private static @NonNull ChildList<Row> secondariesTab() {
        return ChildList.<Row>of(HohenheimSlugs.DNS_ZONE_PEERS)
            .label(HohenheimMicrocopy.DNS_ZONE.of("secondaries"))
            .icon(Icon.of("handshake"))
            .hide(HohenheimSlugs.DNS_ZONE_PEERS, DnsZonePeerModel.ZONE_ID.getName())
            .visibleWhen((zone, access) -> !DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone)));
    }

    /** One week: the ceiling for cache TTLs. */
    private static final int MAX_TTL = 604800;

    /** Four weeks: the ceiling for the SOA refresh/retry/expire intervals. */
    private static final int MAX_INTERVAL = 2419200;

    private final FormSpec formSpec = FormSpec.builder()
        .add(DnsZoneModel.ORIGIN)
        .add(DnsZoneModel.ENABLED)
        // The select derives from the ROLE EnumField -- the vocabulary's one declaring home.
        .add(DnsZoneModel.ROLE)
        .add(Select.of(DnsZoneModel.PRIMARY_PEER_ID)
            .options(OptionSource.dynamic(ctx -> peerOptions()))
            .build())
        .add(DnsZoneModel.SOA_PRIMARY_NS)
        .add(DnsZoneModel.SOA_CONTACT)
        .add(DnsZoneModel.DEFAULT_TTL)
        .add(DnsZoneModel.NEGATIVE_TTL)
        .add(DnsZoneModel.SOA_REFRESH)
        .add(DnsZoneModel.SOA_RETRY)
        .add(DnsZoneModel.SOA_EXPIRE)
        .add(DnsZoneModel.DNSSEC_ENABLED)
        // Replication diagnostics: written by the transfer machinery, read-only here
        // and hidden entirely on a primary zone (see fieldBindings).
        .add(DnsZoneModel.TRANSFER_STATUS)
        .add(DnsZoneModel.LAST_TRANSFER_AT)
        .add(DnsZoneModel.TRANSFER_MESSAGE)
        // Delegation diagnostics: written by the delegation check, read-only here and
        // hidden entirely on a secondary zone (the mirror image of the transfer trio).
        .add(DnsZoneModel.DELEGATION_STATUS)
        .add(DnsZoneModel.DELEGATION_CHECKED_AT)
        .add(DnsZoneModel.DELEGATION_DETAIL)
        // A zone is its origin, its role and who answers for it. The SOA timers have
        // working defaults and the replication diagnostics are read-only output, so both
        // fold -- and on a primary zone the diagnostics are hidden entirely, which simply
        // leaves the section with fewer members.
        .section(FormSection.advanced(
            DnsZoneModel.DEFAULT_TTL.getName(),
            DnsZoneModel.NEGATIVE_TTL.getName(),
            DnsZoneModel.SOA_REFRESH.getName(),
            DnsZoneModel.SOA_RETRY.getName(),
            DnsZoneModel.SOA_EXPIRE.getName(),
            DnsZoneModel.TRANSFER_STATUS.getName(),
            DnsZoneModel.LAST_TRANSFER_AT.getName(),
            DnsZoneModel.TRANSFER_MESSAGE.getName(),
            DnsZoneModel.DELEGATION_STATUS.getName(),
            DnsZoneModel.DELEGATION_CHECKED_AT.getName(),
            DnsZoneModel.DELEGATION_DETAIL.getName()))
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        // The origin is pasted straight into dig/whois far more often than it is read.
        .column(ColumnSpec.fromField(DnsZoneModel.ORIGIN).filterable().copyable().build())
        // AIDEV-NOTE: table cells never wrap, so this list's width is the sum of its
        // columns' content. With Enabled, Serial and a second inline row button the
        // table measured 1344px against the 1134px a 1440px viewport leaves beside the
        // sidebar, and the pinned Actions column hid Delegation and Records -- the two
        // columns an operator opens this list for. Enabled (almost always yes; still a
        // filter) and Serial (a diagnostic the transfer status already summarizes) are
        // offered in the column picker instead of shown by default.
        .column(ColumnSpec.fromField(DnsZoneModel.ENABLED).filterable().hidden().build())
        .column(ColumnSpec.fromField(DnsZoneModel.ROLE).build())
        .column(ColumnSpec.fromField(DnsZoneModel.SERIAL).hidden().build())
        .column(ColumnSpec.fromField(DnsZoneModel.TRANSFER_STATUS).build())
        // The outbound half of replication: TRANSFER_STATUS answers only for a zone this
        // instance PULLS, so a primary's column was blank and its replication state was
        // readable nowhere on the list. This one is its mirror image, per role.
        .column(ColumnSpec.virtual("secondaries", HohenheimMicrocopy.DNS_ZONE.of("secondaries")).build())
        .column(ColumnSpec.fromField(DnsZoneModel.DELEGATION_STATUS).build())
        .column(ColumnSpec.virtual("record_count", HohenheimMicrocopy.DNS_ZONE.of("record_count")).build())
        .filter(FilterSpec.leaf(DnsZoneModel.ORIGIN, CoreTypes.CONTAINS)
            .label(FieldLabels.labelFor(DnsZoneModel.ORIGIN)).build())
        .filter(FilterSpec.leaf(DnsZoneModel.ENABLED, CoreTypes.IS_TRUE, CoreTypes.IS_FALSE)
            .label(FieldLabels.labelFor(DnsZoneModel.ENABLED)).build())
        .build();

    public @NonNull Model model() { return Models.get(DnsZoneModel.class); }
    public @NonNull FormSpec formSpec() { return this.formSpec; }
    public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }

    /** The origin is the zone's identity. */
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(DnsZoneModel.ORIGIN);
    }

    /**
     * The list's quick-add bar: an origin is a whole zone.
     *
     * AIDEV-NOTE: every other entry is either field-defaulted or DEGRADES correctly -- a
     * blank primary NS and contact are derived from the origin by {@code DnsZoneStore}
     * when the snapshot is built, so a zone added here serves correctly and gets tuned in
     * its own form.
     *
     * AIDEV-NOTE: there is deliberately NO inline counterpart, and the
     * {@link DnsRecordParts} precedent does not transfer up to the zone. No zone field
     * is stored metadata: {@link #updateRow} bumps the SERIAL and swaps the SERVED
     * snapshot on every save, so a cell commit would re-announce the zone to every
     * secondary. DNSSEC_ENABLED additionally triggers key generation and ENABLED takes a
     * live domain dark.
     */
    public @Nullable QuickCreateSpec quickCreate() {
        return QuickCreateSpec.of(DnsZoneModel.ORIGIN.getName());
    }

    /**
     * The on-demand run of what the two DNS health tasks do on their schedule: the
     * delegation check against the parent and one SOA probe per linked secondary.
     */
    static @NonNull CmsActionResult checkHealth(@NonNull Row zone) {
        DelegationCheck.Report report = DnsDelegationHealth.check(zone,
            new DelegationCheck(SystemDelegationLookup.INSTANCE));
        List<DnsSecondaryFreshness.Outcome> probed = DnsSecondaryFreshness.probeZone(zone);
        int behind = 0;
        for (DnsSecondaryFreshness.Outcome outcome : probed) {
            if (!outcome.current()) {
                behind++;
            }
        }
        if (report == null) {
            return CmsActionResult.errorToast(
                HohenheimMicrocopy.DNS_ZONE.of("check_health_unserved"));
        }
        return CmsActionResult.refreshWithToast(
            HohenheimMicrocopy.DNS_ZONE.of("check_health_done")
                .withArg("verdict", report.verdict().label())
                .withArg("secondaries", probed.size())
                .withArg("behind", behind));
    }

    /**
     * Zone id to stored record count, one grouped aggregate per request.
     *
     * AIDEV-NOTE: the list cell and each row's delete dialog read this one memo, so a page costs one aggregate, never a
     * COUNT per row.
     */
    private static final IdentifierKey<Map<Integer, Long>> RECORD_COUNTS =
        IdentifierKey.of("hohenheim", "dns_zone_record_counts");

    /** Zone id to its linked secondaries, one grouped aggregate per request. */
    private static final IdentifierKey<Map<Integer, Long>> SECONDARY_COUNTS =
        IdentifierKey.of("hohenheim", "dns_zone_secondary_counts");

    /** Zone id to its linked secondaries currently serving our serial, one grouped aggregate per request. */
    private static final IdentifierKey<Map<Integer, Long>> CURRENT_SECONDARY_COUNTS =
        IdentifierKey.of("hohenheim", "dns_zone_current_secondary_counts");

    private static @NonNull Map<Integer, Long> recordCounts() {
        return GroupedCounts.of(Models.get(DnsRecordModel.class).find(), DnsRecordModel.ZONE_ID);
    }

    private static @NonNull Map<Integer, Long> secondaryCounts() {
        return GroupedCounts.of(Models.get(DnsZonePeerModel.class).find(), DnsZonePeerModel.ZONE_ID);
    }

    /**
     * A link is CURRENT when the probe reached it and found nothing to lag about; a link
     * nobody has probed yet is not counted as healthy (fail closed -- an unprobed
     * secondary is exactly the one that silently stopped pulling).
     */
    private static @NonNull Map<Integer, Long> currentSecondaryCounts() {
        return GroupedCounts.of(Models.get(DnsZonePeerModel.class).find()
            .where(DnsZonePeerModel.PROBED_AT.isNotNull()).where(DnsZonePeerModel.BEHIND_SINCE.isNull()),
            DnsZonePeerModel.ZONE_ID);
    }

    /**
     * What this PRIMARY replicates outward, off the freshness the probe task persists.
     *
     * @return the summary, or null on a secondary (its inbound status column answers instead)
     */
    private static @Nullable Object secondarySummary(@NonNull Row zone) {
        if (DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
            return null;
        }
        Integer id = zone.get(DnsZoneModel.ID);
        long total = RouteScope.memo(SECONDARY_COUNTS, DnsZoneParts::secondaryCounts).getOrDefault(id, 0L);
        if (total == 0) {
            return HohenheimMicrocopy.DNS_ZONE.of("secondaries_none");
        }
        long current = RouteScope.memo(CURRENT_SECONDARY_COUNTS, DnsZoneParts::currentSecondaryCounts)
            .getOrDefault(id, 0L);
        return HohenheimMicrocopy.DNS_ZONE.of("secondaries_current")
            .withArg("current", Math.toIntExact(current))
            .withArg("total", Math.toIntExact(total));
    }

    /**
     * Replication diagnostics belong to secondary zones only; a primary zone shows them
     * neither in its list cell nor in its form. That is presentation per RECORD, so across
     * records the list still sorts and filters by them (the cross-record answer is declared).
     */
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        FieldAccess secondaryOnly = FieldAccess.customRecordAware((ctx, record) ->
            record instanceof Row zone && DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))
                ? FieldAccess.Decision.READONLY
                : FieldAccess.Decision.HIDDEN)
            .acrossRecords(ctx -> FieldAccess.Decision.READONLY);
        // The mirror image: a secondary's delegation is judged where it is owned.
        FieldAccess primaryOnly = FieldAccess.customRecordAware((ctx, record) ->
            record instanceof Row zone && !DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))
                ? FieldAccess.Decision.READONLY
                : FieldAccess.Decision.HIDDEN)
            .acrossRecords(ctx -> FieldAccess.Decision.READONLY);
        return List.of(
            ResourceFieldBinding.of(DnsZoneModel.TRANSFER_STATUS.getName(), secondaryOnly),
            ResourceFieldBinding.of(DnsZoneModel.LAST_TRANSFER_AT.getName(), secondaryOnly),
            ResourceFieldBinding.of(DnsZoneModel.TRANSFER_MESSAGE.getName(), secondaryOnly),
            ResourceFieldBinding.of(DnsZoneModel.DELEGATION_STATUS.getName(), primaryOnly),
            ResourceFieldBinding.of(DnsZoneModel.DELEGATION_CHECKED_AT.getName(), primaryOnly),
            ResourceFieldBinding.of(DnsZoneModel.DELEGATION_DETAIL.getName(), primaryOnly));
    }

    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {
        // A primary zone transfers from nobody: the stored word would be noise, and a
        // null cell renders blank rather than as a badge.
        if (DnsZoneModel.TRANSFER_STATUS.getName().equals(column.name())
            && !DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(row))) {
            return null;
        }
        // And a secondary's delegation is its primary's business.
        if (DnsZoneModel.DELEGATION_STATUS.getName().equals(column.name())
            && DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(row))) {
            return null;
        }
        return row.get(column.name());
    }

    /**
     * How many records the zone actually holds, PER ROLE.
     *
     * A secondary authors nothing locally -- its records arrive over AXFR and live in the
     * served snapshot -- so counting {@code dns_records} rows reported 0 for a replica
     * serving a full zone, which is the one reading that must never be wrong here.
     *
     * @return the stored rows for a primary, the served snapshot's records for a replica
     */
    private static long recordCount(@NonNull Row zone) {
        if (DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
            return servedRecordCount(zone);
        }
        return RouteScope.memo(RECORD_COUNTS, DnsZoneParts::recordCounts).getOrDefault(zone.get(DnsZoneModel.ID), 0L);
    }

    /**
     * @return the records the replica currently SERVES, excluding the SOA (which is not a
     *         stored row on a primary either, so the two counts stay comparable); zero when
     *         the zone has never transferred or its snapshot expired
     */
    private static long servedRecordCount(@NonNull Row zone) {
        String origin = zone.get(DnsZoneModel.ORIGIN);
        Integer zoneId = zone.get(DnsZoneModel.ID);
        DnsZoneSnapshot snapshot = origin != null ? DnsZoneStore.INSTANCE.getZone(origin) : null;
        if (snapshot == null || zoneId == null || snapshot.getZoneId() != zoneId) {
            return 0;
        }
        return snapshot.allRecordsExceptSoa().size();
    }

    /**
     * The detail column read by a human: one LINE per finding, each named by the verdict's
     * own {@link DelegationVerdict#label()} rather than by its stored token.
     *
     * The column stores {@code token subject} lines -- the shape the alert body and the zone
     * API read -- and the form used to hand those straight to a single-line input, which
     * collapsed several findings into one run-on string of snake_case words. The field is
     * MULTILINE now and this is its localization: the vocabulary's labels are Microcopy, so
     * the same rows read as Dutch sentences for a Dutch operator.
     *
     * AIDEV-NOTE: safe to substitute because the entry is READONLY on every surface that
     * offers it (see {@link #fieldBindings}), so this value is never submitted back; and a
     * line whose token this build does not declare is passed through verbatim rather than
     * dropped. The locale comes off the request scope ({@code RouteScope.currentConduit}): {@code valuesFromRow} takes
     * no context.
     */
    public @NonNull Map<String, Object> valuesFromRow(@NonNull Row row) {
        Map<String, Object> values = new HashMap<>(DnsRowWrites.values(this.model(), this.formSpec(), row));
        Object detail = values.get(DnsZoneModel.DELEGATION_DETAIL.getName());
        if (detail instanceof String text && !text.isBlank()) {
            values.put(DnsZoneModel.DELEGATION_DETAIL.getName(), readableFindings(text));
        }
        return values;
    }

    /** @return the stored finding lines with each verdict token replaced by its label */
    private static @NonNull String readableFindings(@NonNull String stored) {
        Conduit conduit = RouteScope.currentConduit();
        StringBuilder text = new StringBuilder();
        for (String line : stored.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            DelegationCheck.Finding finding = DelegationCheck.Finding.parse(line);
            if (finding == null || conduit == null) {
                text.append(line.trim());
                continue;
            }
            String label = finding.verdict().label()
                .resolve(conduit.getLocales(), conduit.getMessageResolver());
            text.append(label);
            if (!finding.subject().isEmpty()) {
                text.append(": ").append(finding.subject());
            }
        }
        return text.toString();
    }

    /** Peer choices for the primary-peer select, with a leading "none" option. */
    static @NonNull List<FieldOption<Integer>> peerOptions() {
        List<FieldOption<Integer>> options = new ArrayList<>();
        options.add(FieldOption.of(null, HohenheimMicrocopy.DNS_PEER.of("peer_none")));
        for (Row peer : Models.get(DnsPeerModel.class).find().all()) {
            options.add(FieldOption.of(peer.get(DnsPeerModel.ID),
                String.valueOf(peer.get(DnsPeerModel.NAME))));
        }
        return options;
    }

    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        validate(values, null, this.model());
        boolean primary = !DnsZoneModel.ROLE_SECONDARY.equals(values.get(DnsZoneModel.ROLE.getName()));
        // A primary zone whose SOA MNAME was left blank names the first declared nameserver,
        // so the MNAME is one of the apex NS rows seeded below instead of a stray host; an
        // explicit value is the operator's and stands. A secondary's SOA comes from the
        // transfer, so nothing is defaulted there.
        if (primary) {
            Object named = values.get(DnsZoneModel.SOA_PRIMARY_NS.getName());
            String declared = DnsNameservers.defaultPrimaryNs();
            if (declared != null && (named == null || String.valueOf(named).isBlank())) {
                values.put(DnsZoneModel.SOA_PRIMARY_NS.getName(), declared);
            }
        }
        Object id = DnsRowWrites.create(this.model(), this.formSpec(), values, accessContext);
        // A new primary zone starts with the controller's declared nameservers at its apex;
        // a secondary's rows come from its primary. Seeded once, never re-asserted.
        if (id instanceof Integer zoneId && primary) {
            DnsNameservers.seedApexRows(zoneId);
        }
        DnsZoneStore.INSTANCE.reload();
        return id;
    }

    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        validate(values, existing, this.model());
        DnsRowWrites.update(this.model(), this.formSpec(), existing, values, accessContext);

        // Bumping a SECONDARY zone's serial would leapfrog the primary's and
        // freeze replication (the refresh check would see "already current").
        Object role = values.containsKey("role") ? values.get("role") : existing.get(DnsZoneModel.ROLE);
        if (DnsZoneModel.ROLE_SECONDARY.equals(role)) {
            DnsZoneStore.INSTANCE.reload();
        }
        else {
            DnsZoneStore.INSTANCE.bumpSerialAndReload(existing.get(DnsZoneModel.ID));
        }
    }

    /**
     * Deleting a zone takes its records and its peer links with it -- on the model funnel
     * ({@code DnsZoneCascades}), so every delete lane cascades; this override only swaps
     * the served snapshot once the delete has landed.
     */
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        this.model().delete(existing);
        DnsZoneStore.INSTANCE.reload();
    }

    /**
     * The record-LESS dialog can only speak about the type, and a zone delete is never
     * generic enough for that: it always resolves per record.
     */
    public @NonNull ConfirmationSpec deleteConfirmation() {
        return deleteConfirmation(
            HohenheimMicrocopy.DNS_ZONE.of("delete_confirm"), null);
    }

    /**
     * Names the zone, how many stored records go with it, everything that resolves inside
     * it, and -- the one that can lock an operator out of the surface they are clicking in
     * -- whether the zone answers for the hostname THIS request arrived on.
     *
     * AIDEV-NOTE: the four bodies are a deliberate 2x2 (dependents yes/no x admin-host
     * yes/no) rather than one sentence with an optional clause: microcopy args echo
     * verbatim, so an "empty when absent" argument would render a dangling colon in every
     * locale. The typed confirmation is unconditional -- a zone delete removes an
     * authoritative name and every record under it, and there is no undo.
     */
    public @NonNull ConfirmationSpec deleteConfirmationFor(@NonNull Row record) {
        String origin = record.get(DnsZoneModel.ORIGIN);
        long records = recordCount(record);
        String dependents = DeleteImpact.join(DeleteImpact.dependentsOfZone(origin));
        String adminHost = DeleteImpact.adminHostnameInZone(origin);

        String key = adminHost != null
            ? (dependents.isEmpty() ? "delete_confirm_admin" : "delete_confirm_admin_dependents")
            : (dependents.isEmpty() ? "delete_confirm_named" : "delete_confirm_dependents");

        Microcopy body = HohenheimMicrocopy.DNS_ZONE.of(key)
            .withArg("origin", origin == null ? "" : origin)
            .withArg("records", records);
        if (!dependents.isEmpty()) {
            body = body.withArg("dependents", dependents);
        }
        if (adminHost != null) {
            body = body.withArg("host", adminHost);
        }
        return deleteConfirmation(body, origin);
    }

    /** The framework's delete dialog with a zone-specific body, typed-confirmation gated on the origin. */
    private static @NonNull ConfirmationSpec deleteConfirmation(@NonNull Microcopy body,
                                                                @Nullable String origin) {
        return DeleteConfirmation.body(body).withTypedConfirmation(origin);
    }

    /**
     * Normalizes and checks the zone fields THIS write carries.
     *
     * AIDEV-NOTE: the coerced map is PARTIAL (the inline cell lane carries one entry, a
     * form omits what the browser did not submit): an absent field is LEFT ALONE on an
     * update, never normalized to a blank and written back. This used to put "" for an
     * unsubmitted soa_primary_ns/soa_contact, which blanked the stored SOA on every edit
     * that did not carry them. On a create absence really is blank, so the create lane
     * still normalizes every field.
     */
    private static void validate(@NonNull Map<String, Object> coerced, @Nullable Row existing,
                                 @NonNull Model model) {
        boolean creating = existing == null;
        if (creating || coerced.containsKey(DnsZoneModel.ORIGIN.getName())) {
            Object originValue = CmsSupport.valueOf(coerced, existing, DnsZoneModel.ORIGIN);
            String rawOrigin = originValue != null ? String.valueOf(originValue) : "";
            String origin = DnsNames.normalizeOrigin(rawOrigin);
            if (origin == null) {
                throw Violations.ofField("origin", rawOrigin, HohenheimMicrocopy.VIOLATIONS.of("dns_origin_format"));
            }
            coerced.put(DnsZoneModel.ORIGIN.getName(), origin);

            Row duplicate = model.find().where(DnsZoneModel.ORIGIN.eq(origin)).first();
            if (duplicate != null
                && (existing == null || !duplicate.get(DnsZoneModel.ID).equals(existing.get(DnsZoneModel.ID)))) {
                throw Violations.ofField("origin", origin, HohenheimMicrocopy.VIOLATIONS.of("dns_origin_taken"));
            }
        }

        if (creating || coerced.containsKey(DnsZoneModel.SOA_PRIMARY_NS.getName())) {
            Object nsValue = coerced.get(DnsZoneModel.SOA_PRIMARY_NS.getName());
            String primaryNs = Hostnames.stripTrailingDots(
                nsValue != null ? String.valueOf(nsValue).trim().toLowerCase(Locale.ROOT) : "");
            if (!primaryNs.isEmpty() && DnsNames.normalizeOrigin(primaryNs) == null) {
                throw Violations.ofField("soa_primary_ns", primaryNs,
                    HohenheimMicrocopy.VIOLATIONS.of("dns_target_format"));
            }
            coerced.put(DnsZoneModel.SOA_PRIMARY_NS.getName(), primaryNs);
        }

        if (creating || coerced.containsKey(DnsZoneModel.SOA_CONTACT.getName())) {
            Object contactValue = coerced.get(DnsZoneModel.SOA_CONTACT.getName());
            String contact = trimmed(contactValue);
            // A contact's domain may be written fully qualified ("hostmaster@example.com."); the shape is zenit's.
            if (!Email.instance().validate(Hostnames.stripTrailingDots(contact), ValidationContext.of("soa_contact"))
                    .isValid()) {
                throw Violations.ofField("soa_contact", contact,
                    HohenheimMicrocopy.VIOLATIONS.of("dns_contact_format"));
            }
            coerced.put(DnsZoneModel.SOA_CONTACT.getName(), contact);
        }

        checkDuration(coerced, "default_ttl", MAX_TTL, "dns_ttl_range");
        checkDuration(coerced, "negative_ttl", MAX_TTL, "dns_ttl_range");
        checkDuration(coerced, "soa_refresh", MAX_INTERVAL, "dns_interval_range");
        checkDuration(coerced, "soa_retry", MAX_INTERVAL, "dns_interval_range");
        checkDuration(coerced, "soa_expire", MAX_INTERVAL, "dns_interval_range");
    }

    private static void checkDuration(@NonNull Map<String, Object> coerced, @NonNull String field,
                                      int max, @NonNull String violationKey) {
        Object value = coerced.get(field);
        if (value == null) {
            return;
        }
        if (!(value instanceof Integer seconds) || seconds < 0 || seconds > max) {
            throw Violations.ofField(field, value, HohenheimMicrocopy.VIOLATIONS.of(violationKey));
        }
    }

    /**
     * The federation peers these zones transfer with, demoted out of the sidebar.
     */
    public @NonNull List<RelatedPage> relatedPages() {
        return List.of(RelatedPage.toPeer(HohenheimSlugs.DNS_PEERS));
    }

}
