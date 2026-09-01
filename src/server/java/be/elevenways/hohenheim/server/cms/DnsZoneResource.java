package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.dns.DelegationVerdict;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.server.dns.DelegationCheck;
import be.elevenways.hohenheim.server.dns.DnsDelegationHealth;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DnsNameservers;
import be.elevenways.hohenheim.server.dns.DnsSecondaryFreshness;
import be.elevenways.hohenheim.server.dns.DnsZoneSnapshot;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.SystemDelegationLookup;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
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
import be.elevenways.zenit.common.orm.query.aggregate.Aggregate;
import be.elevenways.zenit.common.routing.RouteScope;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hosted authoritative DNS zones. The serial is framework-managed: every
 * zone or record mutation bumps it and swaps the serving snapshot.
 */
public final class DnsZoneResource extends RowResource {

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
        .column(ColumnSpec.virtual("secondaries", Microcopy.of("secondaries")
            .withFilter("scope", "dns_zone")).build())
        .column(ColumnSpec.fromField(DnsZoneModel.DELEGATION_STATUS).build())
        .column(ColumnSpec.virtual("record_count", Microcopy.of("record_count")
            .withFilter("scope", "dns_zone")).build())
        .filter(FilterSpec.forField(DnsZoneModel.ORIGIN, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(DnsZoneModel.ORIGIN)).build())
        .filter(FilterSpec.forField(DnsZoneModel.ENABLED, FilterSpec.Kind.BOOLEAN)
            .label(FieldLabels.labelFor(DnsZoneModel.ENABLED)).build())
        .build();

    /** The panel slug, referenced by the record resource's zone-scoped preset. */
    public static final String SLUG = "dns-zones";

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "dns_zone"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "dns_zone"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "dns_zone"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Model model() { return Models.get(DnsZoneModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    /** The origin is the zone's identity. */
    @Override
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
     * {@link DnsRecordResource} precedent does not transfer up to the zone. No zone field
     * is stored metadata: {@link #updateRow} bumps the SERIAL and swaps the SERVED
     * snapshot on every save, so a cell commit would re-announce the zone to every
     * secondary. DNSSEC_ENABLED additionally triggers key generation and ENABLED takes a
     * live domain dark.
     */
    @Override
    public @Nullable QuickCreateSpec quickCreate() {
        return QuickCreateSpec.of(DnsZoneModel.ORIGIN.getName());
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.NETWORK_GROUP; }
    @Override public int navOrder() { return 10; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "dns_zone");
    }
    @Override public @NonNull Icon icon() { return Icon.of("sitemap"); }

    /** The zone list opens the records workspace; the standard edit action still opens zone settings. */
    @Override
    public @NonNull String rowUrl(@NonNull Row row) {
        return recordsUrl(row);
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(0, RowAction.Url.<Row>builder(Identifier.of("hohenheim", "dns_records"))
            .label(Microcopy.of("records").withFilter("scope", "dns_zone"))
            .description(Microcopy.of("records_hint").withFilter("scope", "dns_zone"))
            .icon(Icon.of("list-ul"))
            .url(row -> new Uri(recordsUrl(row)))
            // The zone's title link already opens the records workspace (rowUrl), so
            // an inline button beside Edit repeated it for 120px of every row.
            .inlineInRow(false)
            .build());
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "check_dns_health"))
            .label(Microcopy.of("check_health").withFilter("scope", "dns_zone"))
            .description(Microcopy.of("check_health_hint").withFilter("scope", "dns_zone"))
            .icon(Icon.of("stethoscope"))
            .visibleFor((row, ctx) -> !DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(row)))
            .handler((row, ctx) -> checkHealth(row))
            .build());
        return actions;
    }

    /**
     * The on-demand run of what the two DNS health tasks do on their schedule: the
     * delegation check against the parent and one SOA probe per linked secondary.
     */
    private static @NonNull CmsActionResult checkHealth(@NonNull Row zone) {
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
                Microcopy.of("check_health_unserved").withFilter("scope", "dns_zone"));
        }
        return CmsActionResult.refreshWithToast(
            Microcopy.of("check_health_done").withFilter("scope", "dns_zone")
                .withArg("verdict", report.verdict().label())
                .withArg("secondaries", probed.size())
                .withArg("behind", behind));
    }

    private static @NonNull String recordsUrl(@NonNull Row row) {
        // rowUrl / RowAction.Url are String- and Uri-typed boundaries, so the typed
        // target is rendered here rather than concatenated.
        return CmsRoutes.subpage("admin", "dns-zones", row.get(DnsZoneModel.ID),
            "records").toUrl();
    }

    /**
     * Request-scoped memo of the record counts of the zones on the page being rendered.
     *
     * AIDEV-NOTE: {@code cellValue} takes no context, so the counts are computed where a
     * context DOES exist ({@link #listRows}) and read back through the framework's own
     * request scope ({@code RouteScope.currentConduit}) -- never a field on the resource,
     * which one Panel instance shares across every concurrent request.
     */
    private static final IdentifierKey<Map<Integer, Long>> RECORD_COUNTS =
        IdentifierKey.of("hohenheim", "dns_zone_record_counts");

    /** The same memo for the outbound secondary links of the zones on the page. */
    private static final IdentifierKey<Map<Integer, int[]>> SECONDARY_COUNTS =
        IdentifierKey.of("hohenheim", "dns_zone_secondary_counts");

    /**
     * The page's rows, with every zone's record count resolved in ONE grouped aggregate.
     *
     * AIDEV-NOTE: this replaced a per-row {@code COUNT(*)} inside {@code cellValue} -- 25
     * extra statements on a default page, and the column is on by default. The count query
     * is keyed on exactly the ids this page loaded, so it does not grow with the table.
     */
    @Override
    public @NonNull List<Row> listRows(TableView.Applied<Row> applied,
                                       @NonNull AccessContext accessContext) {
        List<Row> rows = super.listRows(applied, accessContext);
        Conduit conduit = accessContext.conduit();
        if (conduit != null) {
            try {
                conduit.setAttribute(RECORD_COUNTS, countRecordsPerZone(rows));
                conduit.setAttribute(SECONDARY_COUNTS, countSecondariesPerZone(rows));
            } catch (UnsupportedOperationException attributeless) {
                // An attribute-less conduit degrades to the per-row count below.
            }
        }
        return rows;
    }

    /** @return zone id -> stored record count, for exactly the zones handed in */
    private static @NonNull Map<Integer, Long> countRecordsPerZone(@NonNull List<Row> zones) {
        Map<Integer, Long> counts = new HashMap<>();
        List<Integer> ids = new ArrayList<>();
        for (Row zone : zones) {
            Integer id = zone.get(DnsZoneModel.ID);
            if (id != null) {
                ids.add(id);
                counts.put(id, 0L);
            }
        }
        if (ids.isEmpty()) {
            return counts;
        }
        for (Row group : Models.get(DnsRecordModel.class).find()
                .where(DnsRecordModel.ZONE_ID.in(ids))
                .groupBy(DnsRecordModel.ZONE_ID)
                .aggregateAll(Aggregate.count().as("record_count"))) {
            Object zoneId = group.get(DnsRecordModel.ZONE_ID.getName());
            Object counted = group.get("record_count");
            if (zoneId instanceof Number id && counted instanceof Number number) {
                counts.put(id.intValue(), number.longValue());
            }
        }
        return counts;
    }

    /** @return zone id -> {linked secondaries, of which currently serving our serial} */
    private static @NonNull Map<Integer, int[]> countSecondariesPerZone(@NonNull List<Row> zones) {
        Map<Integer, int[]> counts = new HashMap<>();
        List<Integer> ids = new ArrayList<>();
        for (Row zone : zones) {
            Integer id = zone.get(DnsZoneModel.ID);
            if (id != null && !DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
                ids.add(id);
                counts.put(id, new int[] {0, 0});
            }
        }
        if (ids.isEmpty()) {
            return counts;
        }
        for (Row link : Models.get(DnsZonePeerModel.class).find()
                .where(DnsZonePeerModel.ZONE_ID.in(ids)).all()) {
            int[] tally = counts.get(link.get(DnsZonePeerModel.ZONE_ID));
            if (tally != null) {
                tally[0]++;
                if (isCurrent(link)) {
                    tally[1]++;
                }
            }
        }
        return counts;
    }

    /**
     * A link is CURRENT when the probe reached it and found nothing to lag about; a link
     * nobody has probed yet is not counted as healthy (fail closed -- an unprobed
     * secondary is exactly the one that silently stopped pulling).
     */
    private static boolean isCurrent(@NonNull Row link) {
        return link.get(DnsZonePeerModel.PROBED_AT) != null
            && link.get(DnsZonePeerModel.BEHIND_SINCE) == null;
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
        Integer zoneId = zone.get(DnsZoneModel.ID);
        Conduit conduit = RouteScope.currentConduit();
        Map<Integer, int[]> memo = conduit == null ? null : conduit.getAttribute(SECONDARY_COUNTS);
        int[] tally = memo != null ? memo.get(zoneId) : null;
        if (tally == null) {
            tally = countSecondariesPerZone(List.of(zone)).get(zoneId);
        }
        if (tally == null || tally[0] == 0) {
            return Microcopy.of("secondaries_none").withFilter("scope", "dns_zone");
        }
        return Microcopy.of("secondaries_current").withFilter("scope", "dns_zone")
            .withArg("current", tally[1])
            .withArg("total", tally[0]);
    }

    /**
     * Replication diagnostics belong to secondary zones only; a primary zone shows them
     * neither in its list cell nor in its form.
     */
    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        FieldAccess secondaryOnly = FieldAccess.customRecordAware((ctx, record) ->
            record instanceof Row zone && DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))
                ? FieldAccess.Decision.READONLY
                : FieldAccess.Decision.HIDDEN);
        // The mirror image: a secondary's delegation is judged where it is owned.
        FieldAccess primaryOnly = FieldAccess.customRecordAware((ctx, record) ->
            record instanceof Row zone && !DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))
                ? FieldAccess.Decision.READONLY
                : FieldAccess.Decision.HIDDEN);
        return List.of(
            ResourceFieldBinding.of(DnsZoneModel.TRANSFER_STATUS.getName(), secondaryOnly),
            ResourceFieldBinding.of(DnsZoneModel.LAST_TRANSFER_AT.getName(), secondaryOnly),
            ResourceFieldBinding.of(DnsZoneModel.TRANSFER_MESSAGE.getName(), secondaryOnly),
            ResourceFieldBinding.of(DnsZoneModel.DELEGATION_STATUS.getName(), primaryOnly),
            ResourceFieldBinding.of(DnsZoneModel.DELEGATION_CHECKED_AT.getName(), primaryOnly),
            ResourceFieldBinding.of(DnsZoneModel.DELEGATION_DETAIL.getName(), primaryOnly));
    }

    @Override
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
        if ("secondaries".equals(column.name())) {
            return secondarySummary(row);
        }
        if ("record_count".equals(column.name())) {
            return recordCount(row);
        }
        return super.cellValue(row, column);
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
        return recordCount(zone.get(DnsZoneModel.ID));
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

    /** @return the zone's stored record count, off the page memo when this row is on it */
    private static long recordCount(@Nullable Integer zoneId) {
        Conduit conduit = RouteScope.currentConduit();
        Map<Integer, Long> counted = conduit == null ? null : conduit.getAttribute(RECORD_COUNTS);
        if (counted != null && counted.containsKey(zoneId)) {
            return counted.get(zoneId);
        }
        // A row outside the memoized page (a detail render, a conduit-less caller).
        return Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .count();
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
     * dropped. The locale comes off the request scope, the same seam {@link #recordCount}
     * documents -- {@code valuesFromRow} takes no context.
     */
    @Override
    public @NonNull Map<String, Object> valuesFromRow(@NonNull Row row) {
        Map<String, Object> values = new HashMap<>(super.valuesFromRow(row));
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

    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        List<RecordScopedPage<Row>> pages = new ArrayList<>(
            List.of(new DnsZoneRecordsPage(), new DnsZoneFilePage(), new DnsZoneSecondariesPage()));
        pages.addAll(this.frameworkSubpages());
        return pages;
    }

    /** Peer choices for the primary-peer select, with a leading "none" option. */
    static @NonNull List<FieldOption<Integer>> peerOptions() {
        List<FieldOption<Integer>> options = new ArrayList<>();
        options.add(FieldOption.of(null, Microcopy.of("peer_none").withFilter("scope", "dns_peer")));
        for (Row peer : Models.get(DnsPeerModel.class).find().all()) {
            options.add(FieldOption.of(peer.get(DnsPeerModel.ID),
                String.valueOf(peer.get(DnsPeerModel.NAME))));
        }
        return options;
    }

    @Override
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
        Object id = super.persistRow(values, accessContext);
        // A new primary zone starts with the controller's declared nameservers at its apex;
        // a secondary's rows come from its primary. Seeded once, never re-asserted.
        if (id instanceof Integer zoneId && primary) {
            DnsNameservers.seedApexRows(zoneId);
        }
        DnsZoneStore.INSTANCE.reload();
        return id;
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        validate(values, existing, this.model());
        super.updateRow(existing, values, accessContext);

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
    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        super.deleteRow(existing, accessContext);
        DnsZoneStore.INSTANCE.reload();
    }

    /**
     * The record-LESS dialog can only speak about the type, and a zone delete is never
     * generic enough for that: it always resolves per record.
     */
    @Override
    public @NonNull ConfirmationSpec deleteConfirmation() {
        return deleteConfirmation(
            Microcopy.of("delete_confirm").withFilter("scope", "dns_zone"), null);
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
    @Override
    public @NonNull ConfirmationSpec deleteConfirmationFor(@NonNull Row record) {
        String origin = record.get(DnsZoneModel.ORIGIN);
        long records = recordCount(record);
        String dependents = DeleteImpact.join(DeleteImpact.dependentsOfZone(origin));
        String adminHost = DeleteImpact.adminHostnameInZone(origin);

        String key = adminHost != null
            ? (dependents.isEmpty() ? "delete_confirm_admin" : "delete_confirm_admin_dependents")
            : (dependents.isEmpty() ? "delete_confirm_named" : "delete_confirm_dependents");

        Microcopy body = Microcopy.of(key).withFilter("scope", "dns_zone")
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

    /** The generic delete dialog with a zone-specific body, typed-confirmation gated. */
    private static @NonNull ConfirmationSpec deleteConfirmation(@NonNull Microcopy body,
                                                                @Nullable String origin) {
        ConfirmationSpec.Builder builder = ConfirmationSpec.builder()
            .title(Microcopy.of("confirm_title").withFilter("scope", "cms"))
            .body(body)
            .confirmLabel(Microcopy.of("delete").withFilter("scope", "cms"))
            .cancelLabel(Microcopy.of("cancel").withFilter("scope", "cms"))
            .style(ActionStyle.DESTRUCTIVE);
        if (origin != null && !origin.isEmpty()) {
            builder.requireTypedConfirmation(origin);
        }
        return builder.build();
    }

    private static void validate(@NonNull Map<String, Object> coerced, @Nullable Row existing,
                                 @NonNull Model model) {
        Object originValue = coerced.get("origin");
        String rawOrigin = originValue != null ? String.valueOf(originValue)
            : existing != null ? existing.get(DnsZoneModel.ORIGIN) : "";
        String origin = DnsNames.normalizeOrigin(rawOrigin);
        if (origin == null) {
            throw Violations.ofField("origin", rawOrigin, CmsSupport.violationText("dns_origin_format"));
        }
        coerced.put("origin", origin);

        Row duplicate = model.find().where(DnsZoneModel.ORIGIN.eq(origin)).first();
        if (duplicate != null
            && (existing == null || !duplicate.get(DnsZoneModel.ID).equals(existing.get(DnsZoneModel.ID)))) {
            throw Violations.ofField("origin", origin, CmsSupport.violationText("dns_origin_taken"));
        }

        Object nsValue = coerced.get("soa_primary_ns");
        String primaryNs = nsValue != null ? String.valueOf(nsValue).trim().toLowerCase(Locale.ROOT) : "";
        while (primaryNs.endsWith(".")) {
            primaryNs = primaryNs.substring(0, primaryNs.length() - 1);
        }
        if (!primaryNs.isEmpty() && DnsNames.normalizeOrigin(primaryNs) == null) {
            throw Violations.ofField("soa_primary_ns", primaryNs,
                CmsSupport.violationText("dns_target_format"));
        }
        coerced.put("soa_primary_ns", primaryNs);

        Object contactValue = coerced.get("soa_contact");
        String contact = contactValue != null ? String.valueOf(contactValue).trim() : "";
        if (!contact.isEmpty() && !contact.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw Violations.ofField("soa_contact", contact,
                CmsSupport.violationText("dns_contact_format"));
        }
        coerced.put("soa_contact", contact);

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
            throw Violations.ofField(field, value, CmsSupport.violationText(violationKey));
        }
    }

    /**
     * The federation peers these zones transfer with, demoted out of the sidebar.
     */
    @Override
    public @NonNull List<RelatedPage> relatedPages() {
        return List.of(RelatedPage.toPeer("dns-peers"));
    }

}
