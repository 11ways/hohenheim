package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Individual zone records. Hidden from the sidebar -- reached through a
 * zone's Records tab. A row that persists is a row the codec can serve,
 * so validation converts through {@link DnsRecordCodec}.
 */
public final class DnsRecordResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(DnsRecordModel.ZONE_ID, DnsZoneModel.MODEL_ID).build())
        .add(DnsRecordModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(DnsRecordModel.TYPE))
        .add(DnsRecordModel.VALUE)
        .add(DnsRecordModel.TTL)
        .add(DnsRecordModel.PRIORITY)
        .add(DnsRecordModel.WEIGHT)
        .add(DnsRecordModel.PORT)
        .add(DnsRecordModel.ENABLED)
        .add(DnsRecordModel.DYNAMIC)
        .add(DnsRecordModel.DYNDNS_TOKEN)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(DnsRecordModel.NAME).filterable().build())
        .column(ColumnSpec.fromField(DnsRecordModel.TYPE).filterable().build())
        .column(ColumnSpec.fromField(DnsRecordModel.VALUE).filterable().build())
        .column(ColumnSpec.fromField(DnsRecordModel.ENABLED).filterable().build())
        .column(ColumnSpec.fromField(DnsRecordModel.DYNAMIC).filterable().build())
        .column(ColumnSpec.fromField(DnsRecordModel.ZONE_ID)
            .relation(RelationPick.of(DnsRecordModel.ZONE_ID, DnsZoneModel.MODEL_ID).build()).build())
        .filter(FilterSpec.forField(DnsRecordModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(DnsRecordModel.NAME)).build())
        .filter(FilterSpec.forField(DnsRecordModel.TYPE, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(DnsRecordModel.TYPE)).build())
        .filter(FilterSpec.forField(DnsRecordModel.ENABLED, FilterSpec.Kind.BOOLEAN)
            .label(FieldLabels.labelFor(DnsRecordModel.ENABLED)).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "dns_record"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "dns_record"); }
    @Override public @NonNull String slug() { return "dns-records"; }
    @Override public @NonNull Model model() { return Models.get(DnsRecordModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.INFRA_GROUP; }
    @Override public int navOrder() { return 31; }
    @Override public @NonNull Icon icon() { return Icon.of("list-ul"); }
    @Override public boolean showInNav() { return false; }

    /** The zone's Records tab links here with ?zone_id= so the pick is preselected. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        String zoneId = conduit.getQueryParam("zone_id");
        if (zoneId != null && !zoneId.isEmpty()) {
            try {
                return Map.of("zone_id", Integer.parseInt(zoneId));
            } catch (NumberFormatException ignored) {
                // Malformed prefill: render the bare form.
            }
        }
        return Map.of();
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        int zoneId = validate(values, null, this.model());
        Object id = super.persistRow(values, accessContext);
        DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);
        return id;
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        int zoneId = validate(values, existing, this.model());
        super.updateRow(existing, values, accessContext);
        Integer previousZone = existing.get(DnsRecordModel.ZONE_ID);
        if (previousZone != null && previousZone != zoneId) {
            DnsZoneStore.INSTANCE.bumpSerialAndReload(previousZone);
        }
        DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);
    }

    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        Integer zoneId = existing.get(DnsRecordModel.ZONE_ID);
        super.deleteRow(existing, accessContext);
        if (zoneId != null) {
            DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);
        }
    }

    /** @return the validated zone id (shared pipeline with the peer/automation API) */
    private static int validate(@NonNull Map<String, Object> coerced, @Nullable Row existing,
                                @NonNull Model model) {
        return DnsRecordEdits.validate(coerced, existing, model);
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "dyndns_token"))
            .label(Microcopy.of("dyndns_token").withFilter("scope", "dns_record"))
            .icon(Icon.of("rotate"))
            .description(Microcopy.of("dyndns_token_hint").withFilter("scope", "dns_record"))
            .handler((row, ctx) -> mintDynamicToken(row))
            .build());
        return actions;
    }

    /** Marks an A/AAAA record dynamic and (re)generates its update token, disclosed ONCE in the toast. */
    private CmsActionResult mintDynamicToken(@NonNull Row row) {
        String type = row.get(DnsRecordModel.TYPE);
        if (!DnsRecordModel.TYPE_A.equals(type) && !DnsRecordModel.TYPE_AAAA.equals(type)) {
            return CmsActionResult.errorToast(
                Microcopy.of("dyndns_only_address").withFilter("scope", "dns_record"));
        }

        String token = DynamicDnsService.mintToken();
        row.set(DnsRecordModel.DYNAMIC, true);
        // Store the DIGEST, never the plaintext: the token is a bearer credential
        // recoverable from any DB read otherwise. authenticate() hashes the presented
        // token and matches on the digest. (The write hook would hash it anyway; this
        // is explicit so the intent reads at the write site.)
        row.set(DnsRecordModel.DYNDNS_TOKEN, DynamicDnsService.digest(token));
        this.model().save(row);
        ActivityLog.record(this.model(), row.get(DnsRecordModel.ID), "dyndns_token_minted", null);

        // AIDEV-NOTE: the field is secret(), so the edit form no longer echoes the
        // token -- this toast is the ONLY disclosure. Re-mint is the recovery path.
        // withSecretArg parks the plaintext server-side (SecretDisclosures): the
        // flash and durable session data only ever carry a single-use handle.
        return CmsActionResult.refreshWithToast(
                Microcopy.of("dyndns_minted").withFilter("scope", "dns_record"))
            .withSecretArg("token", token);
    }
}
