package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.GeneratedDnsRecords;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.HeaderAction;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FieldOption;
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
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        // The origin is pasted straight into dig/whois far more often than it is read.
        .column(ColumnSpec.fromField(DnsZoneModel.ORIGIN).filterable().copyable().build())
        .column(ColumnSpec.fromField(DnsZoneModel.ENABLED).filterable().build())
        .column(ColumnSpec.fromField(DnsZoneModel.ROLE).build())
        .column(ColumnSpec.fromField(DnsZoneModel.SERIAL).build())
        .column(ColumnSpec.fromField(DnsZoneModel.TRANSFER_STATUS).build())
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
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Model model() { return Models.get(DnsZoneModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }

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
            .build());
        return actions;
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

    @Override
    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {
        if ("record_count".equals(column.name())) {
            Integer zoneId = row.get(DnsZoneModel.ID);
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
        return super.cellValue(row, column);
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
        Object id = super.persistRow(values, accessContext);
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
     * Deleting a zone takes its records (including generated ones) with it.
     *
     * AIDEV-NOTE: the sweeping scope is REQUIRED here, not decoration. A generated row is
     * un-deletable through every tenant path (GeneratedDnsRecords' remove guard), and a
     * cascade from the declaring container is the one legitimate exception: the record the
     * challenge was published into is going away, which is exactly the reclaim condition.
     */
    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        GeneratedDnsRecords.sweeping(() -> Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(existing.get(DnsZoneModel.ID)))
            .delete());
        super.deleteRow(existing, accessContext);
        DnsZoneStore.INSTANCE.reload();
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
    public @NonNull List<HeaderAction> headerActions() {
        List<HeaderAction> actions = new ArrayList<>(super.headerActions());
        actions.addAll(List.of(
            CmsSupport.relatedList("dns_peers_link", "dns-peers", "dns_peer", Icon.of("handshake"))));
        return actions;
    }

}
