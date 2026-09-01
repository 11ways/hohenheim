package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
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
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /** The list's quick-add entries; the zone rides along as a host-supplied preset. */
    private static final QuickCreateSpec QUICK_CREATE = QuickCreateSpec
        .of(DnsRecordModel.TYPE.getName(), DnsRecordModel.NAME.getName(),
            DnsRecordModel.VALUE.getName(), DnsRecordModel.TTL.getName())
        .presets(DnsRecordModel.ZONE_ID.getName());

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
        // An operator copies a record's value into a resolver check far more often
        // than they read it, so the cell carries the copy chip.
        .column(ColumnSpec.fromField(DnsRecordModel.VALUE).filterable().copyable().build())
        .column(ColumnSpec.fromField(DnsRecordModel.TTL).build())
        .column(ColumnSpec.fromField(DnsRecordModel.ENABLED).filterable().build())
        // AIDEV-NOTE: managed_by is a COLUMN rather than a badge because the zone's
        // Records tab renders this same spec: the tab used to show a bare "managed"
        // pill, which named neither the owner nor the reason. A column that says
        // "acme" says both, and the inline-cell lane re-renders from these columns.
        .column(ColumnSpec.fromField(DnsRecordModel.MANAGED_BY).build())
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
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "dns_record"); }
    @Override public @NonNull String slug() { return "dns-records"; }
    @Override public @NonNull Model model() { return Models.get(DnsRecordModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return CmsSupport.FILTERABLE_LIST; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.NETWORK_GROUP; }
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
    public boolean writableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        return TenantWrites.mayAuthorRecord(accessContext, record);
    }

    /**
     * The zone's Records tab links here with ?zone_id= so the pick is preselected.
     *
     * AIDEV-NOTE: the field-declared defaults are merged back in. Overriding this hook
     * REPLACES the base implementation, which is what serves them, so the create form
     * used to open with Enabled unticked and create a disabled record.
     */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(this.formSpec().defaultValues());
        Integer zoneId = prefilledZoneId(conduit);
        if (zoneId != null) {
            values.put(DnsRecordModel.ZONE_ID.getName(), zoneId);
        }
        return values;
    }

    /** Name and value are what an operator scans a zone for; the rest is structure. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(DnsRecordModel.NAME, DnsRecordModel.VALUE);
    }

    /**
     * The list's quick-add bar. The zone is a PRESET rather than a rendered pick:
     * every surface that offers this bar is already scoped to one zone.
     *
     * AIDEV-NOTE: TYPE renders even though MX and SRV cannot be completed here --
     * their sub-schema ({@code SchemaField.schemaFrom("type")}) has no room in a
     * one-line bar. That is not a DNS special case in the bar: the type option
     * carries the sub-schema fact off its own enum member, so the framework flips
     * Add into a link to the full form on its own. Adding a type here therefore
     * needs no change in either place.
     */
    @Override
    public @Nullable QuickCreateSpec quickCreate() {
        return QUICK_CREATE;
    }

    /**
     * The zone the bar adds into: the {@code ?zone_id=} prefill a create link carries, or
     * the zone whose Records tab is being rendered ({@link CmsSupport#scopedParentId}, which
     * documents why the answer comes off the REQUEST).
     */
    @Override
    public @NonNull Map<String, Object> quickCreatePresetValues(@NonNull AccessContext accessContext) {
        Conduit conduit = accessContext.conduit();
        if (conduit == null) {
            return Map.of();
        }
        Integer zoneId = CmsSupport.scopedParentId(conduit, DnsRecordModel.ZONE_ID.getName(),
            DnsZoneResource.SLUG);
        return zoneId != null ? Map.of(DnsRecordModel.ZONE_ID.getName(), zoneId) : Map.of();
    }

    /**
     * The columns an operator retypes without opening the record: a TTL bump and a
     * value correction are the everyday DNS edits.
     *
     * AIDEV-NOTE: TYPE is deliberately absent. Switching it swaps the DATA
     * sub-schema the type declares, so the write drops or demands typed extras a
     * one-cell editor never showed -- that belongs on the full form, where those
     * fields render.
     */
    @Override
    public @NonNull List<Field<?, ?>> inlineEditableFields() {
        return List.of(DnsRecordModel.NAME, DnsRecordModel.VALUE,
            DnsRecordModel.TTL, DnsRecordModel.ENABLED);
    }

    /**
     * The TTL cell says what the resolver will actually answer.
     *
     * A record with no explicit TTL is NOT a record without a TTL: it inherits the zone's
     * default, and the framework's absence marker rendered that as "None" -- the one reading
     * an operator must never take away from a DNS list. The number is derived from the zone
     * ({@link DnsZoneModel#defaultTtlOf}), never a literal, so it stays true when an operator
     * retunes the zone.
     *
     * AIDEV-NOTE: a per-row zone read, deliberately: it happens only for rows that HAVE no
     * TTL, it is a primary-key hit, and a listing is capped at the schema page size. The
     * alternative -- caching the zone on the resource -- would serve a stale default after a
     * zone edit, which is exactly the lie this override exists to remove.
     */
    @Override
    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {
        // The value cell prints the rdata the way a resolver does: MX priority and the SRV
        // priority/weight/port live in the type's sub-schema, so without them five MX rows
        // to one target render as five identical lines. Presentation only -- the stored
        // column keeps the bare target, which is what the ORM filter and the inline editor
        // read (the TTL cell below is the same shape, for the same reason).
        if (column.source() != null
                && DnsRecordModel.VALUE.getName().equals(column.source().getName())) {
            return DnsRecordModel.presentationValue(row);
        }
        Object value = super.cellValue(row, column);
        if (value != null || column.source() == null
                || !DnsRecordModel.TTL.getName().equals(column.source().getName())) {
            return value;
        }
        Integer zoneId = row.get(DnsRecordModel.ZONE_ID);
        Row zone = zoneId != null ? Models.get(DnsZoneModel.class).findById(zoneId) : null;
        // The seconds go in as TEXT: a TTL is an identifier of a cache window, not a
        // quantity, so it must never pick up locale digit grouping ("3,600" is not a TTL).
        return Microcopy.of("ttl_zone_default").withFilter("scope", "dns_record")
            .withArg("ttl", String.valueOf(DnsZoneModel.defaultTtlOf(zone)));
    }

    /** @return the zone a request is scoped to through its {@code ?zone_id=} prefill, or null */
    private static @Nullable Integer prefilledZoneId(@NonNull Conduit conduit) {
        // Malformed prefill: no preselection, never a broken form.
        return CmsSupport.parsedInt(conduit.getQueryParam(DnsRecordModel.ZONE_ID.getName()));
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

    /**
     * Names the record, its TYPE, its VALUE and the ZONE it answers in -- the generic dialog
     * names only the owner label, which says nothing about what stops resolving -- and states
     * that an authoritative answer changes the moment the row is gone.
     *
     * AIDEV-NOTE: the fallback is a whole other body rather than an optional clause, because
     * microcopy args echo VERBATIM: an absent zone or value would render a dangling
     * preposition in every locale. THE single composing home for this wording -- the zone's
     * bespoke Records tab ({@link DnsZoneRecordsPage}) renders its rows through this same
     * hook, and {@link ManageDnsRecordResource} inherits it.
     */
    @Override
    public @NonNull ConfirmationSpec deleteConfirmationFor(@NonNull Row record) {
        String origin = DeleteImpact.originOfZone(record.get(DnsRecordModel.ZONE_ID));
        String owner = record.get(DnsRecordModel.NAME);
        String type = record.get(DnsRecordModel.TYPE);
        String value = record.get(DnsRecordModel.VALUE);

        if (origin == null || origin.isBlank() || owner == null || owner.isBlank()
                || type == null || type.isBlank() || value == null || value.isBlank()) {
            return super.deleteConfirmationFor(record);
        }

        return deleteConfirmation(Microcopy.of("delete_confirm_named")
            .withFilter("scope", "dns_record")
            .withArg("name", DnsNames.absolute(origin, owner))
            .withArg("type", type)
            .withArg("value", value)
            .withArg("origin", origin));
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "dyndns_token"))
            .label(Microcopy.of("dyndns_token").withFilter("scope", "dns_record"))
            .icon(Icon.of("rotate"))
            .description(Microcopy.of("dyndns_token_hint").withFilter("scope", "dns_record")
                .withArg("url", dyndnsUpdateUrl()))
            // Dynamic DNS is a capability of ADDRESS records: the action does not exist
            // on any other type (visibleFor is re-checked on invoke, so a direct POST
            // 404s). The token is a bearer credential that survives grant revocation,
            // so minting it is additionally its OWN capability -- an operator passes
            // through the walk's admin bypass, so one predicate answers for both panels.
            // reachesRecord, never ctx.hasCapability: this runs once per RENDERED ROW.
            .visibleFor((row, ctx) -> DnsRecordModel.isAddressType(row.get(DnsRecordModel.TYPE))
                && HohenheimAccess.reachesRecord(ctx, DnsRecordModel.MODEL_ID,
                    row.get(DnsRecordModel.ID), HohenheimAccess.DYNDNS))
            .handler((row, ctx) -> mintDynamicToken(row))
            // A credential chore, not a per-row affordance: it belongs in the overflow menu.
            .inlineInRow(false)
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
                && HohenheimAccess.reachesRecord(ctx, DnsRecordModel.MODEL_ID,
                    row.get(DnsRecordModel.ID), HohenheimAccess.DYNDNS)
                && DynamicDnsService.credentialFor(row.get(DnsRecordModel.ID)) != null)
            .handler((row, ctx) -> revokeDynamicToken(row))
            .inlineInRow(false)
            .build());
        return actions;
    }

    /**
     * The dyndns2 update URL an operator pastes into a router, absolute where this
     * installation knows its own public URL.
     *
     * AIDEV-NOTE: {@code network.main_url} is THE declared home of "the public URL of this
     * installation" (zenit's sitemap origin and proteus' OAuth callbacks read the same
     * setting); the Host header is deliberately NOT consulted, because a description
     * rendered from an attacker-supplied header would hand an operator someone else's
     * host to send a DNS-write credential to. Unset leaves the PATH, which is still true
     * and is what the endpoint itself declares -- never a hand-typed literal.
     */
    private static @NonNull String dyndnsUpdateUrl() {

        String path = HohenheimEndpoints.DYNDNS_UPDATE.toUrl();
        String base = ServerSettings.VALUES.getValue(ServerSettings.Network.MAIN_URL);

        if (base == null || base.isBlank()) {
            return path;
        }

        String origin = base.strip();

        while (origin.endsWith("/")) {
            origin = origin.substring(0, origin.length() - 1);
        }

        return origin + path;
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
