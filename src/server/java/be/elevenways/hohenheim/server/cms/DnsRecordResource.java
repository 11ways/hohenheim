package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
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
import be.elevenways.zenit.common.edit.Nested;
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
 * so validation converts through {@link DnsRecordCodec}. The form follows
 * the record's TYPE: type-specific fields render from the sub-schema the
 * selected type declares ({@code data} schemaFrom), and the dyndns actions
 * exist only on address records.
 */
public class DnsRecordResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(DnsRecordModel.ZONE_ID, DnsZoneModel.MODEL_ID).build())
        .add(DnsRecordModel.NAME)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(DnsRecordModel.TYPE))
        .add(DnsRecordModel.VALUE)
        .add(Nested.of(DnsRecordModel.DATA).schemaFrom("type").build())
        .add(DnsRecordModel.TTL)
        .add(DnsRecordModel.ENABLED)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(DnsRecordModel.NAME).filterable().build())
        .column(ColumnSpec.fromField(DnsRecordModel.TYPE).filterable().build())
        .column(ColumnSpec.fromField(DnsRecordModel.VALUE).filterable().build())
        .column(ColumnSpec.fromField(DnsRecordModel.ENABLED).filterable().build())
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

    /**
     * Edit and delete ride {@code TenantWrites}' record lanes (per-record {@code edit}
     * grant OR hostname authority, tenant-authorable types only), so the synthesized
     * affordances are offered on exactly that answer -- the
     * {@link InstanceDeviceResource} shape: {@link ManageDnsRecordResource}'s read scope
     * is wider ({@code view} grants and derived hostnames), and without this a view-only
     * delegate was shown buttons the write pipeline could only refuse.
     */
    @Override
    public boolean updatableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        return super.updatableBy(record, accessContext)
            && TenantWrites.mayAuthorRecord(accessContext, record);
    }

    @Override
    public boolean deletableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        return super.deletableBy(record, accessContext)
            && TenantWrites.mayAuthorRecord(accessContext, record);
    }

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
        // The dyndns credential dies with the record via the model-level cascade
        // (DynamicDnsService.installCredentialCascade), shared with the peer API
        // and the zone-file import's replace.
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
            // Dynamic DNS is a capability of ADDRESS records: the action does not exist
            // on any other type (visibleFor is re-checked on invoke, so a direct POST
            // 404s). The token is a bearer credential that survives grant revocation,
            // so minting it is additionally its OWN capability -- an operator passes
            // through the walk's admin bypass, so one predicate answers for both panels.
            .visibleFor((row, ctx) -> DnsRecordModel.isAddressType(row.get(DnsRecordModel.TYPE))
                && ctx.hasCapability(DnsRecordModel.MODEL_ID,
                    row.get(DnsRecordModel.ID), HohenheimAccess.DYNDNS))
            .handler((row, ctx) -> mintDynamicToken(row))
            .build());
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "dyndns_revoke"))
            .label(Microcopy.of("dyndns_revoke").withFilter("scope", "dns_record"))
            .icon(Icon.of("ban"))
            .description(Microcopy.of("dyndns_revoke_hint").withFilter("scope", "dns_record"))
            .confirmation(ConfirmationSpec.destructive(
                Microcopy.of("dyndns_revoke_confirm").withFilter("scope", "dns_record")))
            // Only offered where a credential actually exists: revoke on a record that
            // is not dynamic is not a no-op button, it is not a button at all.
            .visibleFor((row, ctx) -> DnsRecordModel.isAddressType(row.get(DnsRecordModel.TYPE))
                && ctx.hasCapability(DnsRecordModel.MODEL_ID,
                    row.get(DnsRecordModel.ID), HohenheimAccess.DYNDNS)
                && DynamicDnsService.credentialFor(row.get(DnsRecordModel.ID)) != null)
            .handler((row, ctx) -> revokeDynamicToken(row))
            .build());
        return actions;
    }

    /** Arms (or re-keys) the record's dyndns credential; the plaintext is disclosed ONCE in the toast. */
    private CmsActionResult mintDynamicToken(@NonNull Row row) {
        String token = DynamicDnsService.mintFor(row.get(DnsRecordModel.ID));
        ActivityLog.record(this.model(), row.get(DnsRecordModel.ID), "dyndns_token_minted", null);

        // AIDEV-NOTE: only the digest is at rest (dns_dyndns_credentials), so this
        // toast is the ONLY disclosure. Re-mint is the recovery path. withSecretArg
        // parks the plaintext server-side (SecretDisclosures): the flash and durable
        // session data only ever carry a single-use handle.
        return CmsActionResult.refreshWithToast(
                Microcopy.of("dyndns_minted").withFilter("scope", "dns_record"))
            .withSecretArg("token", token);
    }

    /** Deletes the credential: the record stops being dynamic and its token dies now. */
    private CmsActionResult revokeDynamicToken(@NonNull Row row) {
        DynamicDnsService.revokeFor(row.get(DnsRecordModel.ID));
        ActivityLog.record(this.model(), row.get(DnsRecordModel.ID), "dyndns_token_revoked", null);
        return CmsActionResult.refreshWithToast(
            Microcopy.of("dyndns_revoked").withFilter("scope", "dns_record"));
    }
}
