package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.DeviceDetail;
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
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.protoblast.common.typed.rule.Condition;
import be.elevenways.protoblast.common.typed.rule.Operand;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.render.CmsTemplateIds;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.cms.common.resource.CellMapper;
import be.elevenways.zenit.cms.common.resource.ChildList;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.common.edit.EditContext;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
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
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

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

    /**
     * @return the operator's devices: attach, resize a disk, detach (any type). Install media is no type of this form:
     *         it is attached through its own action (the record's ISO pick of its host), so the select offers only the
     *         types without one and refuses a hand-posted cdrom itself
     */
    public static @NonNull PanelResource<Row> devicesAdmin() {
        return devices(HohenheimIds.id("instance_device"), deviceForm(type -> true))
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
        return ManageTwin.reached(devices(ManageTwin.id("instance_device"), deviceForm(type -> !type.operatorOnly())),
                TenantScopes.INSTANCE_DEVICES, ResourceTabs.<Row>none().withContributions())
            .build();
    }

    /**
     * The device form: its instance is the fixed parent (shown, never edited), and its detail entries DERIVE from the
     * type facts ({@link DeviceType#details()}, the vocabulary the Devices tab's columns read): a detail some offered
     * type means is an entry, shown only while the picked type means it.
     */
    private static @NonNull FormSpec deviceForm(@NonNull Predicate<DeviceType> offered) {
        Predicate<DeviceType> formable = type -> !type.ownAttachAction() && offered.test(type);
        FormSpec.Builder form = FormSpec.builder()
            .add(RelationPick.of(InstanceDeviceModel.INSTANCE_ID, InstanceModel.MODEL_ID).build())
            .add(Select.of(InstanceDeviceModel.TYPE).options(OptionSource.of(typeOptions(formable))).build())
            .add(InstanceDeviceModel.NAME);
        for (DeviceDetail detail : DeviceDetail.values()) {
            List<String> meaning = new ArrayList<>();
            for (DeviceType type : DeviceType.values()) {
                if (formable.test(type) && type.details().contains(detail)) {
                    meaning.add(type.token());
                }
            }
            if (!meaning.isEmpty()) {
                form.add(detail.field())
                    .showWhen(detail.field().getName(), InstanceDeviceModel.TYPE.getName(),
                        meaning.toArray(String[]::new));
            }
        }
        return form.build();
    }

    /** The device type a create link prefilled, or null when it named none or there is no request. */
    private static @Nullable DeviceType prefilledType(@Nullable Conduit conduit) {
        return DeviceType.parse(CmsSupport.prefill(conduit, HohenheimParams.DEVICE_TYPE_PREFILL));
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
            // A device's instance is its identity at the daemon: the parent is fixed, the create is written under the
            // instance the Devices tab names, and no edit moves it.
            .parent(ResourceParent.of(HohenheimSlugs.INSTANCES, InstanceDeviceModel.INSTANCE_ID)
                .tab(HohenheimSlugs.Tab.DEVICES).fixed())
            .reads(ResourceReads.rows())
            .form(ResourceForm.<Row>of(form).createDefaults((access, panel) -> {
                DeviceType type = prefilledType(access.conduit());
                Map<String, Object> values = new LinkedHashMap<>(form.defaultValues());
                if (type != null) {
                    values.put(InstanceDeviceModel.TYPE.getName(), type.token());
                }
                return Map.copyOf(values);
            })
                // The page a Devices tab link opens is named by the kind it attaches.
                .createTitle(request -> {
                    DeviceType type = prefilledType(request.conduit());
                    return type != null ? type.attachTitle() : null;
                })
                // An attached device is seen where it lives: its instance's Devices tab, beside its siblings.
                .createdRecordUrl((device, request) -> CmsRoutes.subpage(request.panelSlug(), HohenheimSlugs.INSTANCES,
                    device.get(InstanceDeviceModel.INSTANCE_ID), HohenheimSlugs.Tab.DEVICES))
                .build())
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
            .deleteConfirmation(DeleteConfirmation.of(ConfirmationSpec.verb(
                HohenheimMicrocopy.INSTANCE_DEVICE.of("detach"),
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
            CmsEndpoints.PARENT_PARAM.getName(), HohenheimSlugs.INSTANCES);
        return instanceId != null ? InstanceDevices.mayChangeDevicesOf(access, instanceId)
            : InstanceDevices.mayChangeAnyDevices(access);
    }

    /** The TYPE vocabulary's options a device form offers: only those {@code formable} accepts. */
    private static @NonNull List<FieldOption<String>> typeOptions(@NonNull Predicate<DeviceType> formable) {
        List<FieldOption<String>> options = new ArrayList<>();
        for (Map.Entry<String, EnumField.EnumValue> value : InstanceDeviceModel.TYPE.getValues().entrySet()) {
            // Fail closed: a token that is no member is never offered either.
            DeviceType type = DeviceType.parse(value.getKey());
            if (type == null || !formable.test(type)) {
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
            case DISK -> devices.attachDisk(instanceId, name, sizeOf(call.merged()));
            case NIC -> devices.attachNic(instanceId, name);
            // The form's select never offers it (ownAttachAction): install media is attached by its own operation.
            case CDROM -> throw new IllegalStateException("install media is attached through "
                + "attach_install_media, never the device form");
        }
        History.event(Models.get(InstanceModel.class), instanceId, Change.fact(HohenheimActivityAction.DEVICE_ATTACHED,
            Map.of(HohenheimActivityAction.DEVICE_KEY, name)));
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
     * AIDEV-NOTE: the identity reads take the MERGED record, where a key the write does not carry is the stored value,
     * which means "unchanged" and passes: the inline cell lane writes a map holding exactly one entry. The media read
     * stays on the submitted map: no device form declares it, so only a hand-posted value is judged.
     */
    private static @Nullable Object resizeDevice(@NonNull RowWriteCall call) {
        Row existing = Objects.requireNonNull(call.record(), "an update carries its record");
        OperationInput.Values merged = call.merged();
        Integer owner = existing.get(InstanceDeviceModel.INSTANCE_ID);
        int instanceId = owner != null ? owner : -1;
        String name = existing.get(InstanceDeviceModel.NAME);
        String type = existing.get(InstanceDeviceModel.TYPE);
        Object submittedName = merged.get(InstanceDeviceModel.NAME.getName());
        if (!String.valueOf(name).equals(String.valueOf(submittedName))) {
            throw Violations.ofField("name", submittedName,
                HohenheimMicrocopy.VIOLATIONS.of("device_rename_unsupported"));
        }
        Object submittedType = merged.get(InstanceDeviceModel.TYPE.getName());
        if (!String.valueOf(type).equals(String.valueOf(submittedType)) || instanceId != requireInstance(
                merged.get(InstanceDeviceModel.INSTANCE_ID.getName()))) {
            throw Violations.ofField("type", submittedType,
                HohenheimMicrocopy.VIOLATIONS.of("device_retype_unsupported"));
        }
        String storedMedia = existing.get(InstanceDeviceModel.SOURCE_MEDIA);
        Object submittedMedia = call.values().getOrDefault(InstanceDeviceModel.SOURCE_MEDIA.getName(), null);
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
        new InstanceDevices().resizeDisk(instanceId, name, sizeOf(merged));
        History.event(Models.get(InstanceModel.class), instanceId, Change.fact(HohenheimActivityAction.DEVICE_RESIZED,
            Map.of(HohenheimActivityAction.DEVICE_KEY, name)));
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

    /** The disk's size off the merged record -- a write that carries no size resizes nothing. */
    private static int sizeOf(OperationInput.@NonNull Values merged) {
        Object size = merged.get(InstanceDeviceModel.SIZE_GB.getName());
        if (size instanceof Number number) {
            return number.intValue();
        }
        // The model's own beforeValidate owns the refusal identity of a malformed size; 0 reaches it.
        Integer parsed = RawValues.parsedInt(size);
        return parsed != null ? parsed : 0;
    }

    // -- database attachments ----------------------------------------------------------------------------------------

    /** The virtual columns drawing the attached database's engine and what it does. */
    private static final String ENGINE_COLUMN = "engine";
    private static final String STATE_COLUMN = "database_state";

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

    /**
     * The Attached databases tab of an instance on a Docker daemon (injection rides the link network, which an Incus
     * container or VM has none of): the attachment list of the hosting panel narrowed to it, where an operator
     * attaches and detaches.
     */
    public static @NonNull ChildList<Row> databasesTab() {
        return ChildList.<Row>of(HohenheimSlugs.INSTANCE_DATABASES)
            .icon(Icon.of("database"))
            .secondary()
            .lead(HohenheimMicrocopy.INSTANCE_DATABASE.of("lead"))
            .visibleWhen((instance, access) -> injectsDatabases(instance))
            .hide(HohenheimSlugs.INSTANCE_DATABASES, InstanceDatabaseModel.INSTANCE_ID.getName());
    }

    /** @return whether the instance runs on a Docker daemon, the one runtime a database is injected into */
    static boolean injectsDatabases(@NonNull Row instance) {
        InstanceKindHandler handler = InstanceKinds.handlerOf(instance);
        return handler != null && handler.supportedRuntimes().contains(ServerModel.RUNTIME_DOCKER);
    }

    /**
     * An attachment is its two sides: the instance is its fixed parent (attached from that instance's tab, never
     * re-pointed), the database is chosen once and frozen after. The picker offers only databases not attached yet,
     * each with its engine and state; the save keeps its own refusal of a duplicate.
     */
    private static PanelResource.@NonNull Builder<Row> databases(@NonNull Identifier id) {
        FormSpec form = FormSpec.builder()
            .createTitle(HohenheimMicrocopy.INSTANCE_DATABASE.of("add"))
            .add(RelationPick.of(InstanceDatabaseModel.INSTANCE_ID, InstanceModel.MODEL_ID).build())
            .add(RelationPick.of(InstanceDatabaseModel.DATABASE_ID, DatabaseModel.MODEL_ID)
                .rulesFromContext(InstanceAttachmentParts::attachableDatabases)
                .emptyReason(context -> HohenheimMicrocopy.INSTANCE_DATABASE.of("none_attachable"))
                .build())
            .add(InstanceDatabaseModel.ENV_PREFIX)
            .build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceDatabaseModel.DATABASE_ID)
                .relation(RelationPick.of(InstanceDatabaseModel.DATABASE_ID, DatabaseModel.MODEL_ID).build()).build())
            .column(ColumnSpec.fromField(InstanceDatabaseModel.INSTANCE_ID)
                .relation(RelationPick.of(InstanceDatabaseModel.INSTANCE_ID, InstanceModel.MODEL_ID).build()).build())
            .column(ColumnSpec.virtual(ENGINE_COLUMN, HohenheimMicrocopy.HOHENHEIM_FIELD.of("engine"))
                .renderer(CmsTemplateIds.CELL_ENUM_BADGE).build())
            .column(ColumnSpec.fromField(InstanceDatabaseModel.ENV_PREFIX).copyable().build())
            .column(ColumnSpec.virtual(STATE_COLUMN, HohenheimMicrocopy.INSTANCE_DATABASE.of("status"))
                .renderer(HohenheimTemplateIds.CELL_STATE_LINE).build())
            .column(ColumnSpec.fromField(InstanceDatabaseModel.CREATED_AT).hidden().build())
            .build();
        return PanelResource.builder(id, HohenheimSlugs.INSTANCE_DATABASES, InstanceAttachmentOperations.DATABASE_LINK)
            .label(HohenheimMicrocopy.INSTANCE_DATABASE.of("plural"))
            .recordLabel(HohenheimMicrocopy.INSTANCE_DATABASE.of("singular"))
            .icon(Icon.of("database"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(21)
            .showInNav(false)
            .parent(ResourceParent.of(HohenheimSlugs.INSTANCES, InstanceDatabaseModel.INSTANCE_ID)
                .tab(HohenheimSlugs.Tab.DATABASES).fixed())
            // The engine and the state of every listed attachment's database, read for the whole page at once.
            .reads(ResourceReads.rows().title(InstanceAttachmentParts::linkTitle)
                .pageBatch((links, access) -> databaseCells(links)))
            .form(ResourceForm.<Row>of(form)
                .bindings(List.of(ResourceFieldBinding.frozenAfterCreate(
                    InstanceDatabaseModel.DATABASE_ID.getName())))
                .createDefaults((access, panel) -> Map.of(InstanceDatabaseModel.ENV_PREFIX.getName(),
                    InstanceDatabaseModel.DEFAULT_PREFIX))
                .createTitle(InstanceAttachmentParts::attachTitle)
                .createLabel(HohenheimMicrocopy.INSTANCE_DATABASE.of("attach"))
                .createdToast(link -> attachedToast(link))
                // An attachment is seen where it lives: its instance's Attached databases tab.
                .createdRecordUrl((link, request) -> CmsRoutes.subpage(request.panelSlug(), HohenheimSlugs.INSTANCES,
                    link.get(InstanceDatabaseModel.INSTANCE_ID), HohenheimSlugs.Tab.DATABASES))
                // AIDEV-NOTE: the env prefix only, the one thing about an attachment that is text rather than a pick.
                // It bites at the NEXT DEPLOY: injection reads the prefix when the workload starts.
                .inlineEditable(InstanceDatabaseModel.ENV_PREFIX)
                .build())
            // The env prefix names the injected variable family; nothing else here is text. A row opens the database
            // it attaches; the attachment's own form is its Edit.
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).search(InstanceDatabaseModel.ENV_PREFIX)
                .rowLink((link, request) -> CmsRoutes.open(request.panelSlug(), HohenheimSlugs.DATABASES,
                    link.get(InstanceDatabaseModel.DATABASE_ID)))
                .build())
            .writes(ResourceMutations.rows()
                .create()
                .update()
                .delete(InstanceAttachmentOperations.DELETE_DATABASE_LINK)
                .beforeSave(InstanceAttachmentParts::requireLinkReachable)
                .build())
            // Removing an attachment is a detach, named so on the row, through the attachment's own delete operation.
            .replaceDeleteAction(ActionPlacement.ROW, detach())
            // The delete dialog names BOTH sides and the consequence.
            .deleteConfirmation(DeleteConfirmation.<Row>defaults().forRow((link, request) -> linkDeleteBody(link)))
            // Edit rides TenantWrites.requireInstanceLinkAuthority's two-sided rule (instance config plus database
            // manage), offered on exactly that answer; the pipeline hook stays the gate.
            .authority(ResourceAuthority.<Row>builder()
                .update(null, (link, access) -> InstanceAttachmentOperationHandlers.mayChangeLink(link, access))
                .build());
    }

    /** The Detach row action: the attachment's delete operation, confirmed with both sides named. */
    private static @NonNull PanelAction<Row> detach() {
        ConfirmationSpec detach = ConfirmationSpec.verb(HohenheimMicrocopy.INSTANCE_DATABASE.of("detach"),
            HohenheimMicrocopy.INSTANCE_DATABASE.of("detach_confirm_any"), ActionStyle.DESTRUCTIVE);
        return PanelAction.<Row, Integer>places(InstanceAttachmentOperations.DELETE_DATABASE_LINK, ActionPlacement.ROW,
                (context, result) -> CmsActionResult.refreshWithToast(detachedToast(context.subjects().getFirst())))
            .label(HohenheimMicrocopy.INSTANCE_DATABASE.of("detach"))
            .icon(Icon.of("link-slash"))
            .style(ActionStyle.DESTRUCTIVE)
            .inlineInRow(false)
            .confirmation(detach)
            .dynamicConfirmation(link -> detach.withBody(detachBody(link)))
            .build();
    }

    /** The databases the attach picker offers: every one not attached to the instance yet; on a record, its own. */
    private static @NonNull Condition attachableDatabases(@NonNull EditContext context) {
        if (context.record() instanceof Row link && link.get(InstanceDatabaseModel.DATABASE_ID) != null) {
            return Condition.test(DatabaseModel.ID.getName(), CoreTypes.EQUALS,
                Operand.of(link.get(InstanceDatabaseModel.DATABASE_ID)));
        }
        Conduit conduit = context.conduit();
        Integer instanceId = conduit == null ? null : CmsSupport.scopedParentId(conduit,
            CmsEndpoints.PARENT_PARAM.getName(), HohenheimSlugs.INSTANCES);
        if (instanceId == null) {
            return Condition.EVERYTHING;
        }
        List<Object> attached = new ArrayList<>();
        for (Row link : Models.get(InstanceDatabaseModel.class).findByInstanceId(instanceId)) {
            attached.add(link.get(InstanceDatabaseModel.DATABASE_ID));
        }
        return attached.isEmpty() ? Condition.EVERYTHING
            : Condition.test(DatabaseModel.ID.getName(), CoreTypes.NOT_IN, Operand.of(attached));
    }

    /** "Attach a database to shop-web": the create page names the app it attaches to, the parent it is opened under. */
    private static @Nullable Microcopy attachTitle(@NonNull PanelRequest request) {
        Integer instanceId = CmsSupport.scopedParentId(request.conduit(), CmsEndpoints.PARENT_PARAM.getName(),
            HohenheimSlugs.INSTANCES);
        String instance = instanceId == null ? null : DeleteImpact.instanceNameOf(instanceId);
        return instance == null ? null
            : HohenheimMicrocopy.INSTANCE_DATABASE.of("attach_title").withArg("instance", instance);
    }

    /** "shop-db is attached to shop-web", null when either side is gone (the generated toast stands). */
    private static @Nullable Microcopy attachedToast(@NonNull Row link) {
        return twoSides(link, "attached_toast");
    }

    /** "shop-db is detached from shop-web", or the side-less sentence when either side is gone. */
    private static @NonNull Microcopy detachedToast(@NonNull Row link) {
        Microcopy named = twoSides(link, "detached_toast");
        return named != null ? named : HohenheimMicrocopy.INSTANCE_DATABASE.of("detached_toast_any");
    }

    /** The detach dialog's body: both sides and the consequence, or the side-less sentence. */
    private static @NonNull Microcopy detachBody(@NonNull Row link) {
        Microcopy sentence = detachSentence(link);
        return sentence != null ? sentence : HohenheimMicrocopy.INSTANCE_DATABASE.of("detach_confirm_any");
    }

    /** @return the attachment's sentence {@code key} naming its database and instance, null when either is gone */
    private static @Nullable Microcopy twoSides(@NonNull Row link, @NonNull String key) {
        String database = DeleteImpact.databaseNameOf(link.get(InstanceDatabaseModel.DATABASE_ID));
        String instance = DeleteImpact.instanceNameOf(link.get(InstanceDatabaseModel.INSTANCE_ID));
        if (database == null || instance == null) {
            return null;
        }
        return HohenheimMicrocopy.INSTANCE_DATABASE.of(key).withArg("database", database).withArg("instance", instance);
    }

    /**
     * The engine badge and the state line of each listed attachment's database, its rows loaded in one read.
     *
     * AIDEV-NOTE: the state is the database's own verdict (DatabaseVerdict), which asks the engine instance serving
     * an active database per record; batching that read belongs to DatabaseVerdict, not to this page.
     */
    private static @NonNull CellMapper<Row> databaseCells(@NonNull List<Row> links) {
        Set<Integer> ids = new HashSet<>();
        for (Row link : links) {
            Integer databaseId = link.get(InstanceDatabaseModel.DATABASE_ID);
            if (databaseId != null) {
                ids.add(databaseId);
            }
        }
        Map<Integer, Row> databases = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Row database : Models.get(DatabaseModel.class).find().where(DatabaseModel.ID.in(ids)).all()) {
                databases.put(database.get(DatabaseModel.ID), database);
            }
        }
        return (link, column) -> {
            Row database = databases.get((Integer) link.get(InstanceDatabaseModel.DATABASE_ID));
            if (database == null) {
                return null;
            }
            return switch (column.name()) {
                case ENGINE_COLUMN -> EnumBadgeState.ofNullable(DatabaseModel.ENGINE,
                    database.get(DatabaseModel.ENGINE));
                case STATE_COLUMN -> DatabaseVerdict.ofDatabase(database).cell();
                default -> null;
            };
        };
    }

    /**
     * "{database} on {instance}": the two sides ARE the record; the schema's display field is the env prefix, which
     * titled every attachment "DB".
     */
    private static @Nullable Microcopy linkTitle(@NonNull Row link) {
        return twoSides(link, "record_title");
    }

    /** Reachability is revoked at the daemon on confirm, the injected variable family at the next deploy. */
    private static @NonNull ConfirmationSpec linkDeleteBody(@NonNull Row link) {
        Microcopy sentence = detachSentence(link);
        return sentence == null ? DeleteConfirmation.<Row>defaults().fallback() : DeleteConfirmation.body(sentence);
    }

    /** @return the detach's consequence naming both sides and the variable family, null when either side is gone */
    private static @Nullable Microcopy detachSentence(@NonNull Row link) {
        Microcopy named = twoSides(link, "delete_confirm");
        return named == null ? null
            : named.withArg("prefix", DatabaseEnvInjection.normalizedPrefix(link.get(InstanceDatabaseModel.ENV_PREFIX)));
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

}
