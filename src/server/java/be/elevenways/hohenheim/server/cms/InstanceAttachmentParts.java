package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.DeviceType;
import be.elevenways.hohenheim.instance.InstanceAttachmentOperations;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimRoles.Role;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.instance.InstanceAttachmentOperationHandlers;
import be.elevenways.hohenheim.server.instance.InstanceDevices;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceAuthority;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.resource.ResourceVerb;
import be.elevenways.zenit.cms.common.resource.RowSave;
import be.elevenways.zenit.cms.common.resource.RowWriteCall;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.RowScope;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What is attached to an instance, as shared parts: its devices and its managed database attachments, the admin
 * entries and the /manage twins built from them.
 *
 * AIDEV-NOTE: every device write reaches the daemon (attach, resize, detach) through InstanceDevices, which asks the
 * instance capability as its FIRST statement; those writes own their envelope, because a rolled-back transaction
 * could not undo the daemon's side and the host lease refuses to wait inside one.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceAttachmentParts {

    /** Devices attached to an instance; the /manage scope narrows this same base per principal. */
    public static final RowScope DEVICE_ROWS = RowScope.within(() -> InstanceDeviceModel.INSTANCE_ID.isNotNull());

    /** Attachments to an instance; the /manage scope narrows this same base per principal. */
    public static final RowScope DATABASE_ROWS = RowScope.within(() -> InstanceDatabaseModel.INSTANCE_ID.isNotNull());

    private InstanceAttachmentParts() {
    }

    /**
     * Whether this boot serves database attachments: they join an instance to a managed database, so both tiers must
     * run; a template's declared databases follow the same answer.
     */
    public static boolean databasesServed() {
        return HohenheimRoles.enabled(Role.DATABASES) && HohenheimRoles.enabled(Role.INSTANCES);
    }

    // -- devices -----------------------------------------------------------------------------------------------------

    /** @return the operator's devices: attach, resize a disk, detach (any type) */
    public static @NonNull PanelResource<Row> devicesAdmin() {
        FormSpec form = FormSpec.builder()
            .add(InstanceDeviceModel.INSTANCE_ID)
            .add(InstanceDeviceModel.TYPE)
            .add(InstanceDeviceModel.NAME)
            .add(InstanceDeviceModel.SIZE_GB)
            .add(InstanceDeviceModel.SOURCE_MEDIA)
            .build();
        return devices(HohenheimIds.id("instance_device"), form)
            .scope(DEVICE_ROWS)
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /**
     * @return the tenant's devices: those of instances it may view. Its type options DERIVE from the model's own TYPE
     *         vocabulary minus the operator-only ones, and coercion runs against this spec, so a hand-posted cdrom is
     *         refused by the select itself -- hide AND enforce
     */
    public static @NonNull PanelResource<Row> devicesManage() {
        FormSpec form = FormSpec.builder()
            .add(InstanceDeviceModel.INSTANCE_ID)
            .add(Select.of(InstanceDeviceModel.TYPE).options(OptionSource.of(tenantTypeOptions())).build())
            .add(InstanceDeviceModel.NAME)
            .add(InstanceDeviceModel.SIZE_GB)
            .build();
        return ManageTwin.reached(devices(ManageTwin.id("instance_device"), form), TenantScopes.INSTANCE_DEVICES,
                ResourceTabs.<Row>none().withContributions())
            .build();
    }

    private static PanelResource.@NonNull Builder<Row> devices(@NonNull Identifier id, @NonNull FormSpec form) {
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceDeviceModel.NAME).filterable().build())
            .column(ColumnSpec.fromField(InstanceDeviceModel.INSTANCE_ID).build())
            .column(ColumnSpec.fromField(InstanceDeviceModel.TYPE).filterable().build())
            .column(ColumnSpec.fromField(InstanceDeviceModel.SIZE_GB).build())
            .column(ColumnSpec.fromField(InstanceDeviceModel.SOURCE_MEDIA).build())
            .column(ColumnSpec.fromField(InstanceDeviceModel.CREATED_AT).build())
            .build();
        return PanelResource.builder(id, HohenheimSlugs.INSTANCE_DEVICES, InstanceAttachmentOperations.DEVICE)
            .label(HohenheimMicrocopy.INSTANCE_DEVICE.of("plural"))
            .recordLabel(HohenheimMicrocopy.INSTANCE_DEVICE.of("singular"))
            .icon(Icon.of("hard-drive"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(19)
            .showInNav(false)
            .standsUnder(HohenheimSlugs.INSTANCES)
            .parent(ResourceParent.of(HohenheimSlugs.INSTANCES, InstanceDeviceModel.INSTANCE_ID)
            .tab(HohenheimSlugs.Tab.DEVICES))
            .reads(ResourceReads.rows())
            .form(ResourceForm.<Row>of(form).createDefaults(request -> {
                Map<String, Object> values = instancePrefilled(form, request);
                DeviceType type = DeviceType.parse(CmsSupport.prefill(request.conduit(),
                    HohenheimParams.DEVICE_TYPE_PREFILL));
                if (type == null) {
                    return values;
                }
                Map<String, Object> typed = new LinkedHashMap<>(values);
                typed.put(InstanceDeviceModel.TYPE.getName(), type.token());
                return Map.copyOf(typed);
            }).build())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).build())
            // AIDEV-NOTE: attaching reaches the DAEMON, so the framework's rollback-after-the-fact scope verification
            // must not wrap it: a rolled-back create would remove the row and leave the volume, and the host lease
            // cannot be acquired inside the caller's own write transaction at all. InstanceDevices asks
            // requireOperationCapability on the target instance as its FIRST statement, before any write.
            .writes(ResourceMutations.rows()
                .create(InstanceAttachmentParts::attachDevice)
                .update(InstanceAttachmentParts::resizeDevice)
                .delete(InstanceAttachmentOperations.DETACH_DEVICE)
                .scopeVerifiedBeforeWrite()
                .ownsWriteEnvelope(ResourceVerb.CREATE, ResourceVerb.UPDATE, ResourceVerb.DELETE)
                .build())
            // Detach DELETES the backing volume at the daemon, so the confirmation says that in so many words.
            .deleteConfirmation(DeleteConfirmation.of(Confirmations.of(HohenheimMicrocopy.INSTANCE_DEVICE.of("detach"),
                HohenheimMicrocopy.INSTANCE_DEVICE.of("detach_confirm"), ActionStyle.DESTRUCTIVE)))
            // AIDEV-NOTE: the SAME capability InstanceDevices asks as the first statement of every mutator, asked
            // earlier so the surface stops offering what the funnel will refuse. Read stays WIDER on purpose: seeing
            // that a disk exists on an instance you may view is not authority to change it.
            .authority(ResourceAuthority.<Row>builder()
                .create(null, InstanceAttachmentParts::mayAttach)
                .update(null, (device, access) -> InstanceDevices.mayChangeDevicesOf(access,
                    device.get(InstanceDeviceModel.INSTANCE_ID)))
                .build());
    }

    /**
     * Attaching demands the device capability on the TARGET instance: the one the request names (the Devices tab's
     * {@code ?instance_id=} prefill, else the instance whose tab is rendering), else whether ANY instance would
     * accept an attach from this principal.
     */
    private static boolean mayAttach(@NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        Integer instanceId = conduit == null ? null : CmsSupport.scopedParentId(conduit,
            HohenheimParams.INSTANCE_ID_PREFILL.getName(), HohenheimSlugs.INSTANCES);
        return instanceId != null ? InstanceDevices.mayChangeDevicesOf(access, instanceId)
            : InstanceDevices.mayChangeAnyDevices(access);
    }

    private static @NonNull List<FieldOption<String>> tenantTypeOptions() {
        List<FieldOption<String>> options = new ArrayList<>();
        for (Map.Entry<String, EnumField.EnumValue> value : InstanceDeviceModel.TYPE.getValues().entrySet()) {
            // Fail closed: a token that is no member is never offered either.
            DeviceType type = DeviceType.parse(value.getKey());
            if (type == null || type.operatorOnly()) {
                continue;
            }
            options.add(FieldOption.of(value.getKey(), value.getValue().getLabel()));
        }
        return options;
    }

    private static @NonNull Object attachDevice(@NonNull RowWriteCall call) {
        Map<String, Object> values = call.values();
        int instanceId = requireInstance(values.get(InstanceDeviceModel.INSTANCE_ID.getName()));
        String name = String.valueOf(values.get(InstanceDeviceModel.NAME.getName()));
        InstanceDevices devices = new InstanceDevices();
        switch (DeviceType.require(values.get(InstanceDeviceModel.TYPE.getName()))) {
            case DISK -> devices.attachDisk(instanceId, name, sizeOf(values, null));
            case NIC -> devices.attachNic(instanceId, name);
            case CDROM -> devices.attachCdrom(instanceId, name,
                String.valueOf(values.getOrDefault(InstanceDeviceModel.SOURCE_MEDIA.getName(), "")).trim());
        }
        ActivityLog.record(Models.get(InstanceModel.class), instanceId, HohenheimActivityAction.DEVICE_ATTACHED, null);
        Row created = Models.get(InstanceDeviceModel.class).find()
            .where(InstanceDeviceModel.INSTANCE_ID.eq(instanceId))
            .where(InstanceDeviceModel.NAME.eq(name))
            .first();
        if (created == null) {
            // attachDisk/attachNic write the row before the daemon call and delete it again on a daemon refusal; a
            // missing row here means the refusal lane ran without throwing, the silent success this must never be.
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("device_attach_incomplete"));
        }
        return created.get(InstanceDeviceModel.ID);
    }

    /**
     * The only editable dimension is a disk's SIZE: a device's name, type and owning instance are its identity at the
     * daemon, and there is no rename or re-home operation to honour a change of them with.
     *
     * AIDEV-NOTE: the identity reads take the STORED value when the write does not carry the key, which means
     * "unchanged" and passes: the inline cell lane writes a map holding exactly one entry.
     */
    private static @Nullable Object resizeDevice(@NonNull RowWriteCall call) {
        Row existing = Objects.requireNonNull(call.record(), "an update carries its record");
        Map<String, Object> values = call.values();
        Integer owner = existing.get(InstanceDeviceModel.INSTANCE_ID);
        int instanceId = owner != null ? owner : -1;
        String name = existing.get(InstanceDeviceModel.NAME);
        String type = existing.get(InstanceDeviceModel.TYPE);
        Object submittedName = CmsSupport.valueOf(values, existing, InstanceDeviceModel.NAME);
        if (!String.valueOf(name).equals(String.valueOf(submittedName))) {
            throw Violations.ofField("name", submittedName,
                HohenheimMicrocopy.VIOLATIONS.of("device_rename_unsupported"));
        }
        Object submittedType = CmsSupport.valueOf(values, existing, InstanceDeviceModel.TYPE);
        if (!String.valueOf(type).equals(String.valueOf(submittedType)) || instanceId != requireInstance(
                CmsSupport.valueOf(values, existing, InstanceDeviceModel.INSTANCE_ID))) {
            throw Violations.ofField("type", submittedType,
                HohenheimMicrocopy.VIOLATIONS.of("device_retype_unsupported"));
        }
        String storedMedia = existing.get(InstanceDeviceModel.SOURCE_MEDIA);
        Object submittedMedia = values.get(InstanceDeviceModel.SOURCE_MEDIA.getName());
        if (submittedMedia != null && !String.valueOf(submittedMedia).trim().isEmpty()
                && !String.valueOf(submittedMedia).trim().equals(storedMedia == null ? "" : storedMedia)) {
            // Swapping media is detach-and-attach at the daemon; an in-place edit would report success while the old
            // ISO stayed in the drive until the next deploy.
            throw Violations.ofField("source_media", submittedMedia,
                HohenheimMicrocopy.VIOLATIONS.of("device_media_change_unsupported"));
        }
        if (DeviceType.parse(type) != DeviceType.DISK) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("device_resize_not_a_disk"));
        }
        new InstanceDevices().resizeDisk(instanceId, name, sizeOf(values, existing));
        ActivityLog.record(Models.get(InstanceModel.class), instanceId, HohenheimActivityAction.DEVICE_RESIZED, null);
        return null;
    }

    private static int requireInstance(@Nullable Object value) {
        int instanceId = RawValues.intOr(value, -1);
        if (instanceId <= 0 || Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(instanceId)).count() == 0) {
            throw Violations.ofField("instance_id", value, HohenheimMicrocopy.VIOLATIONS.of("unknown_instance"));
        }
        return instanceId;
    }

    /** The submitted size, else the stored one -- a write that carries no size resizes nothing. */
    private static int sizeOf(@NonNull Map<String, Object> values, @Nullable Row existing) {
        Object size = CmsSupport.valueOf(values, existing, InstanceDeviceModel.SIZE_GB);
        if (size instanceof Number number) {
            return number.intValue();
        }
        // The model's own beforeValidate owns the refusal identity of a malformed size; 0 reaches it.
        Integer parsed = RawValues.parsedInt(size);
        return parsed != null ? parsed : 0;
    }

    // -- database attachments ----------------------------------------------------------------------------------------

    /** @return the operator's instance-database attachments */
    public static @NonNull PanelResource<Row> databasesAdmin() {
        return databases(HohenheimIds.id("instance_database"))
            .scope(DATABASE_ROWS)
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /** @return the tenant's attachments: those of instances it may view; the contributed tabs only */
    public static @NonNull PanelResource<Row> databasesManage() {
        return ManageTwin.reached(databases(ManageTwin.id("instance_database")), TenantScopes.INSTANCE_DATABASES,
                ResourceTabs.<Row>none().withContributions())
            .build();
    }

    private static PanelResource.@NonNull Builder<Row> databases(@NonNull Identifier id) {
        FormSpec form = FormSpec.builder()
            .add(RelationPick.of(InstanceDatabaseModel.INSTANCE_ID, InstanceModel.MODEL_ID).build())
            .add(RelationPick.of(InstanceDatabaseModel.DATABASE_ID, DatabaseModel.MODEL_ID).build())
            .add(InstanceDatabaseModel.ENV_PREFIX)
            .build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceDatabaseModel.INSTANCE_ID)
                .relation(RelationPick.of(InstanceDatabaseModel.INSTANCE_ID, InstanceModel.MODEL_ID).build()).build())
            .column(ColumnSpec.fromField(InstanceDatabaseModel.DATABASE_ID)
                .relation(RelationPick.of(InstanceDatabaseModel.DATABASE_ID, DatabaseModel.MODEL_ID).build()).build())
            .column(ColumnSpec.fromField(InstanceDatabaseModel.ENV_PREFIX).copyable().build())
            .column(ColumnSpec.fromField(InstanceDatabaseModel.CREATED_AT).build())
            .build();
        return PanelResource.builder(id, HohenheimSlugs.INSTANCE_DATABASES, InstanceAttachmentOperations.DATABASE_LINK)
            .label(HohenheimMicrocopy.INSTANCE_DATABASE.of("plural"))
            .recordLabel(HohenheimMicrocopy.INSTANCE_DATABASE.of("singular"))
            .icon(Icon.of("database"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(21)
            .showInNav(false)
            .standsUnder(HohenheimSlugs.INSTANCES)
            .parent(ResourceParent.of(HohenheimSlugs.INSTANCES, InstanceDatabaseModel.INSTANCE_ID)
            .tab(HohenheimSlugs.Tab.DATABASES))
            .reads(ResourceReads.rows().title(InstanceAttachmentParts::linkTitle))
            .form(ResourceForm.<Row>of(form)
                .createDefaults(request -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    Integer instanceId = CmsSupport.prefill(request.conduit(), HohenheimParams.INSTANCE_ID_PREFILL);
                    if (instanceId != null) {
                        values.put(InstanceDatabaseModel.INSTANCE_ID.getName(), instanceId);
                    }
                    values.put(InstanceDatabaseModel.ENV_PREFIX.getName(), InstanceDatabaseModel.DEFAULT_PREFIX);
                    return Map.copyOf(values);
                })
                // AIDEV-NOTE: the env prefix only, the one thing about an attachment that is text rather than a pick.
                // It bites at the NEXT DEPLOY: injection reads the prefix when the workload starts.
                .inlineEditable(InstanceDatabaseModel.ENV_PREFIX)
                .build())
            // The env prefix names the injected variable family; nothing else here is text.
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).search(InstanceDatabaseModel.ENV_PREFIX)
                .build())
            .writes(ResourceMutations.rows()
                .create()
                .update()
                .delete(InstanceAttachmentOperations.DELETE_DATABASE_LINK)
                .beforeSave(InstanceAttachmentParts::requireLinkReachable)
                .build())
            // The delete dialog names BOTH sides and the consequence.
            .deleteConfirmation(DeleteConfirmation.<Row>defaults().forRow((link, request) -> linkDeleteBody(link)))
            // Edit rides TenantWrites.requireInstanceLinkAuthority's two-sided rule (instance config plus database
            // manage), offered on exactly that answer; the pipeline hook stays the gate.
            .authority(ResourceAuthority.<Row>builder()
                .update(null, (link, access) -> InstanceAttachmentOperationHandlers.mayChangeLink(link, access))
                .build());
    }

    /**
     * "{database} on {instance}": the two sides ARE the record; the schema's display field is the env prefix, which
     * titled every attachment "DB".
     */
    private static @Nullable String linkTitle(@NonNull Row link) {
        String database = DeleteImpact.databaseNameOf(link.get(InstanceDatabaseModel.DATABASE_ID));
        String instance = DeleteImpact.instanceNameOf(link.get(InstanceDatabaseModel.INSTANCE_ID));
        if (database == null || instance == null) {
            return null;
        }
        return CmsSupport.resolvedTextOrDefault(HohenheimMicrocopy.INSTANCE_DATABASE.of("record_title")
            .withArg("database", database)
            .withArg("instance", instance));
    }

    /** Reachability is revoked at the daemon on confirm, the injected variable family at the next deploy. */
    private static @NonNull ConfirmationSpec linkDeleteBody(@NonNull Row link) {
        String database = DeleteImpact.databaseNameOf(link.get(InstanceDatabaseModel.DATABASE_ID));
        String instance = DeleteImpact.instanceNameOf(link.get(InstanceDatabaseModel.INSTANCE_ID));
        if (database == null || instance == null) {
            return DeleteConfirmation.<Row>defaults().fallback();
        }
        return DeleteConfirmation.body(HohenheimMicrocopy.INSTANCE_DATABASE.of("delete_confirm")
            .withArg("database", database)
            .withArg("instance", instance)
            .withArg("prefix", DatabaseEnvInjection.normalizedPrefix(link.get(InstanceDatabaseModel.ENV_PREFIX))));
    }

    /**
     * Reachability, enforced at LINK time so injection never emits credentials the workload cannot connect to: the
     * instance must run on a driver that HAS link networks, and the database must live on the same server.
     *
     * AIDEV-NOTE: for a TENANT the authority decision comes BEFORE any lookup below, because the lookups are UNSCOPED
     * and their refusals used to be a cross-tenant oracle; operators keep the full diagnostic vocabulary.
     */
    private static void requireLinkReachable(@NonNull RowSave save) {
        Row link = save.row();
        Integer instanceId = link.get(InstanceDatabaseModel.INSTANCE_ID);
        if (instanceId == null) {
            throw Violations.ofField("instance_id", null, HohenheimMicrocopy.VIOLATIONS.of("instance_required"));
        }
        Integer databaseId = link.get(InstanceDatabaseModel.DATABASE_ID);
        if (TenantWrites.isTenantOriginated()) {
            if (databaseId == null) {
                throw Violations.ofField("database_id", null, HohenheimMicrocopy.VIOLATIONS.of("database_required"));
            }
            TenantWrites.requireInstanceLinkAuthority(instanceId, databaseId);
        }
        Row instance = Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(instanceId)).first();
        if (instance == null) {
            throw Violations.ofField("instance_id", instanceId, HohenheimMicrocopy.VIOLATIONS.of("instance_missing"));
        }
        String kind = instance.get(InstanceModel.KIND);
        InstanceKindHandler handler = InstanceKinds.getHandler(kind);
        if (handler == null || !handler.supportedRuntimes().contains(ServerModel.RUNTIME_DOCKER)) {
            throw Violations.ofField("instance_id", instanceId,
                HohenheimMicrocopy.VIOLATIONS.of("instance_kind_no_injection").withArg("kind", String.valueOf(kind)));
        }
        if (databaseId == null) {
            throw Violations.ofField("database_id", null, HohenheimMicrocopy.VIOLATIONS.of("database_required"));
        }
        Row database = Models.get(DatabaseModel.class).find().where(DatabaseModel.ID.eq(databaseId)).first();
        if (database == null) {
            throw Violations.ofField("database_id", databaseId, HohenheimMicrocopy.VIOLATIONS.of("database_missing"));
        }
        int databaseServer = ServerModel.canonicalServerId(database.get(DatabaseModel.SERVER_ID));
        int instanceServer = ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID));
        if (databaseServer != instanceServer) {
            throw Violations.ofField("database_id", databaseId,
                HohenheimMicrocopy.VIOLATIONS.of("database_instance_server_mismatch")
                    .withArg("name", database.get(DatabaseModel.NAME))
                    .withArg("server", ServerModel.nameOf(databaseServer))
                    .withArg("instance_server", ServerModel.nameOf(instanceServer)));
        }
        String stored = link.get(InstanceDatabaseModel.ENV_PREFIX);
        String prefix = stored == null || stored.isEmpty() ? InstanceDatabaseModel.DEFAULT_PREFIX : stored;
        if (!prefix.matches(InstanceDatabaseModel.PREFIX_PATTERN)) {
            throw Violations.ofField("env_prefix", prefix, HohenheimMicrocopy.VIOLATIONS.of("prefix_format"));
        }
        Integer id = link.get(InstanceDatabaseModel.ID);
        for (Row other : Models.get(InstanceDatabaseModel.class).findByInstanceId(instanceId)) {
            if (id != null && id.equals(other.get(InstanceDatabaseModel.ID))) {
                continue;
            }
            if (databaseId.equals(other.get(InstanceDatabaseModel.DATABASE_ID))) {
                throw Violations.ofField("database_id", databaseId,
                    HohenheimMicrocopy.VIOLATIONS.of("database_already_attached"));
            }
            String otherPrefix = DatabaseEnvInjection.normalizedPrefix(other.get(InstanceDatabaseModel.ENV_PREFIX));
            if (otherPrefix.equalsIgnoreCase(prefix)) {
                throw Violations.ofField("env_prefix", prefix,
                    HohenheimMicrocopy.VIOLATIONS.of("prefix_taken")
                        .withArg("prefix", prefix.toUpperCase(Locale.ROOT)));
            }
        }
    }

    // -- plumbing ----------------------------------------------------------------------------------------------------

    /** The instance's tab links here with ?instance_id= so the owner is preset. */
    private static @NonNull Map<String, Object> instancePrefilled(@NonNull FormSpec form,
                                                                  @NonNull PanelRequest request) {
        Map<String, Object> values = new LinkedHashMap<>(form.defaultValues());
        Integer instanceId = CmsSupport.prefill(request.conduit(), HohenheimParams.INSTANCE_ID_PREFILL);
        if (instanceId != null) {
            values.put("instance_id", instanceId);
        }
        return Map.copyOf(values);
    }

}
