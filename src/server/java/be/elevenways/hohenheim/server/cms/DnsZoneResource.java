package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
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
        .add(Select.of(DnsZoneModel.ROLE)
            .options(OptionSource.of(List.of(
                FieldOption.of(DnsZoneModel.ROLE_PRIMARY, Microcopy.of("role_primary").withFilter("scope", "dns_role")),
                FieldOption.of(DnsZoneModel.ROLE_SECONDARY, Microcopy.of("role_secondary").withFilter("scope", "dns_role")))))
            .build())
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
        .column(ColumnSpec.fromField(DnsZoneModel.ORIGIN).filterable().build())
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

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "dns_zone"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("hohenheim.dns_zone.plural"); }
    @Override public @NonNull String slug() { return "dns-zones"; }
    @Override public @NonNull Model model() { return Models.get(DnsZoneModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.INFRA_GROUP; }
    @Override public int navOrder() { return 30; }
    @Override public @NonNull Icon icon() { return Icon.of("sitemap"); }

    @Override
    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {
        if ("record_count".equals(column.name())) {
            return Models.get(DnsRecordModel.class).find()
                .where(DnsRecordModel.ZONE_ID.eq(row.get(DnsZoneModel.ID)))
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

    /** Deleting a zone takes its records (including ACME-managed ones) with it. */
    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(existing.get(DnsZoneModel.ID)))
            .delete();
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
        String primaryNs = nsValue != null ? String.valueOf(nsValue).trim().toLowerCase() : "";
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
}
