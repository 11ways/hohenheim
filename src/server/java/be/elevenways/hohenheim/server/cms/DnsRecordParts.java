package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.resource.ResourceAuthority;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
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
public final class DnsRecordParts {

    public static @NonNull PanelResource<Row> admin() {
        DnsRecordParts parts = new DnsRecordParts();
        return entry(parts.id())
            .reads(ResourceReads.rows().mapCells(parts::cellValue))
            .list(ResourceList.rows(parts.tableSpec()).chrome(CmsSupport.FILTERABLE_LIST).facets().ruleFilters()
                .search(parts.searchFields().toArray(Field<?, ?>[]::new)).build())
            .form(ResourceForm.<Row>of(parts.formSpec()).quickCreate(QUICK_CREATE)
                .quickCreatePresets(access -> CmsSupport.parentPreset(access, DnsRecordModel.ZONE_ID.getName(),
                    HohenheimSlugs.DNS_ZONES))
                .createDefaults(request -> parts.createValues(request.conduit()))
                .inlineEditable(parts.inlineEditableFields().toArray(Field<?, ?>[]::new)).build())
            // Its own list is kept out of the sidebar (entry) and stands under the zones through this parent; reached
            // from a zone's Records tab.
            .parent(parts.parent())
            .writes(ResourceMutations.rows().create(call -> parts.persistRow(call.values(), call.access()))
                .update(call -> { parts.updateRow(call.record(), call.values(), call.access()); return null; })
                .delete(DnsOperations.DELETE_RECORD).build())
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions()).build();
    }

    static PanelResource.@NonNull Builder<Row> entry(@NonNull Identifier id) {
        DnsOperations.init();
        return PanelResource.builder(id, HohenheimSlugs.DNS_RECORDS, DnsOperations.RECORD)
            .label(HohenheimMicrocopy.DNS_RECORD.of("plural"))
            .recordLabel(HohenheimMicrocopy.DNS_RECORD.of("singular"))
            .navGroup(HohenheimPanel.NETWORK_GROUP).navOrder(31).icon(Icon.of("list-ul")).showInNav(false)
            .authority(ResourceAuthority.<Row>builder().write(null,
                (row, access) -> TenantWrites.mayAuthorRecord(access, row)).build())
            .deleteConfirmation(DeleteConfirmation.<Row>defaults().forRow((row, request) ->
                new DnsRecordParts().deleteConfirmationFor(row)))
            .actions(List.of(PanelAction.<Row, CmsActionResult>places(DnsOperations.MINT_DYNAMIC_TOKEN,
                ActionPlacement.ROW, (request, result) -> result.value()).inlineInRow(false)
                .dynamicDescription(row -> HohenheimMicrocopy.DNS_RECORD.of("dyndns_token_hint")
                    .withArg("url", dyndnsUpdateUrl())).build(),
                PanelAction.<Row, CmsActionResult>places(DnsOperations.REVOKE_DYNAMIC_TOKEN,
                    ActionPlacement.ROW, (request, result) -> result.value()).inlineInRow(false)
                    .confirmation(ConfirmationSpec.destructive(HohenheimMicrocopy.DNS_RECORD
                        .of("dyndns_revoke_confirm"))).build()));
    }

    public static void requireImportable(@NonNull Panel panel, int zoneId, @NonNull AccessContext access) {
        ResourceWrites.requireOutsideArchive(panel, admin(),
            Map.of(DnsRecordModel.ZONE_ID.getName(), zoneId), access, "import");
    }

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
        .filter(FilterSpec.leaf(DnsRecordModel.NAME, CoreTypes.CONTAINS)
            .label(FieldLabels.labelFor(DnsRecordModel.NAME)).build())
        .filter(FilterSpec.leaf(DnsRecordModel.TYPE, CoreTypes.EQUALS)
            .label(FieldLabels.labelFor(DnsRecordModel.TYPE)).build())
        .filter(FilterSpec.leaf(DnsRecordModel.ENABLED, CoreTypes.IS_TRUE, CoreTypes.IS_FALSE)
            .label(FieldLabels.labelFor(DnsRecordModel.ENABLED)).build())
        .build();

    public @NonNull Identifier id() { return HohenheimIds.id("dns_record"); }
    public @NonNull Microcopy label() { return HohenheimMicrocopy.DNS_RECORD.of("plural"); }
    public @NonNull Microcopy recordLabel() { return HohenheimMicrocopy.DNS_RECORD.of("singular"); }

    public @NonNull String slug() { return HohenheimSlugs.DNS_RECORDS; }
    public @NonNull Model model() { return Models.get(DnsRecordModel.class); }
    public @NonNull FormSpec formSpec() { return this.formSpec; }
    public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }

    /**
     * A record belongs to its zone: the zone's Records tab is its home, and a zone that becomes read-only (trashed,
     * or under a trashed record) makes every write of its records refused by zenit-cms.
     */
    public @Nullable ResourceParent<Row> parent() {
        return ResourceParent.of(HohenheimSlugs.DNS_ZONES, DnsRecordModel.ZONE_ID).tab(HohenheimSlugs.Tab.RECORDS);
    }

    /**
     * Edit and delete ride {@code TenantWrites}' record lanes (per-record {@code edit}
     * grant OR hostname authority, tenant-authorable types only), so the synthesized
     * affordances are offered on exactly that answer -- the
     * {@link InstanceAttachmentParts#devicesAdmin()} shape: {@link ManageDnsRecordParts}'s read scope
     * is wider ({@code view} grants and derived hostnames), and without this a view-only
     * delegate was shown buttons the write pipeline could only refuse.
     */
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
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(this.formSpec().defaultValues());
        Integer zoneId = prefilledZoneId(conduit);
        if (zoneId != null) {
            values.put(DnsRecordModel.ZONE_ID.getName(), zoneId);
        }
        return values;
    }

    /** Name and value are what an operator scans a zone for; the rest is structure. */
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
    public @Nullable QuickCreateSpec quickCreate() {
        return QUICK_CREATE;
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
        Object value = row.get(column.name());
        if (value != null || column.source() == null
                || !DnsRecordModel.TTL.getName().equals(column.source().getName())) {
            return value;
        }
        Integer zoneId = row.get(DnsRecordModel.ZONE_ID);
        Row zone = zoneId != null ? Models.get(DnsZoneModel.class).findById(zoneId) : null;
        // The seconds go in as TEXT: a TTL is an identifier of a cache window, not a
        // quantity, so it must never pick up locale digit grouping ("3,600" is not a TTL).
        return HohenheimMicrocopy.DNS_RECORD.of("ttl_zone_default")
            .withArg("ttl", String.valueOf(DnsZoneModel.defaultTtlOf(zone)));
    }

    /** @return the zone a request is scoped to through its {@code ?zone_id=} prefill, or null */
    private static @Nullable Integer prefilledZoneId(@NonNull Conduit conduit) {
        // Malformed prefill: no preselection, never a broken form.
        return CmsSupport.prefill(conduit, HohenheimParams.ZONE_ID_PREFILL);
    }

    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        int zoneId = validate(values, null, this.model());
        Object id = DnsRowWrites.create(this.model(), this.formSpec(), values, accessContext);
        DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);
        return id;
    }

    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        int zoneId = validate(values, existing, this.model());
        Integer previousZone = existing.get(DnsRecordModel.ZONE_ID);
        DnsRowWrites.update(this.model(), this.formSpec(), existing, values, accessContext);
        if (previousZone != null && previousZone != zoneId) {
            DnsZoneStore.INSTANCE.bumpSerialAndReload(previousZone);
        }
        DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);
    }

    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        Integer zoneId = existing.get(DnsRecordModel.ZONE_ID);
        // The dyndns credential dies with the record via the model-level cascade
        // (DynamicDnsService.installCredentialCascade), shared with the peer API
        // and the zone-file import's replace.
        this.model().delete(existing);
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
     * policy, which {@link ManageDnsRecordParts} shares through the common entry declaration.
     */
    public @NonNull ConfirmationSpec deleteConfirmationFor(@NonNull Row record) {
        String origin = DeleteImpact.originOfZone(record.get(DnsRecordModel.ZONE_ID));
        String owner = record.get(DnsRecordModel.NAME);
        String type = record.get(DnsRecordModel.TYPE);
        String value = record.get(DnsRecordModel.VALUE);

        if (origin == null || origin.isBlank() || owner == null || owner.isBlank()
                || type == null || type.isBlank() || value == null || value.isBlank()) {
            return DeleteConfirmation.<Row>defaults().fallback();
        }

        return DeleteConfirmation.body(HohenheimMicrocopy.DNS_RECORD.of("delete_confirm_named")
            .withArg("name", DnsNames.absolute(origin, owner))
            .withArg("type", type)
            .withArg("value", value)
            .withArg("origin", origin));
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
    static @NonNull String dyndnsUpdateUrl() {

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
    static CmsActionResult mintDynamicToken(@NonNull Row row) {
        String token = DynamicDnsService.mintFor(row.get(DnsRecordModel.ID));
        ActivityLog.record(Models.get(DnsRecordModel.class), row.get(DnsRecordModel.ID), HohenheimActivityAction.DYNDNS_TOKEN_MINTED, null);

        // AIDEV-NOTE: only the digest is at rest (dns_dyndns_credentials), so this
        // toast is the ONLY disclosure. Re-mint is the recovery path. withSecretArg
        // parks the plaintext server-side (SecretDisclosures): the flash and durable
        // session data only ever carry a single-use handle.
        return CmsActionResult.refreshWithToast(
                HohenheimMicrocopy.DNS_RECORD.of("dyndns_minted"))
            .withSecretArg("token", token);
    }

    /** Deletes the credential: the record stops being dynamic and its token dies now. */
    static CmsActionResult revokeDynamicToken(@NonNull Row row) {
        DynamicDnsService.revokeFor(row.get(DnsRecordModel.ID));
        ActivityLog.record(Models.get(DnsRecordModel.class), row.get(DnsRecordModel.ID), HohenheimActivityAction.DYNDNS_TOKEN_REVOKED, null);
        return CmsActionResult.refreshWithToast(
            HohenheimMicrocopy.DNS_RECORD.of("dyndns_revoked"));
    }
}
