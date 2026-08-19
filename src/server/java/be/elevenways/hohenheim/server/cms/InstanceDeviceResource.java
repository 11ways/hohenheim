package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceDevices;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Attached disks and NICs of one instance (nav-hidden; reached through an instance's
 * Devices tab). THE operator surface over {@link InstanceDevices} -- the mechanism was
 * complete, quota-guarded and live-proven for three waves with no caller a human could
 * reach.
 *
 * AIDEV-NOTE: every write funnels into InstanceDevices and NEVER into
 * super.persistRow/updateRow/deleteRow. The row is only half of a device: the other
 * half is the volume or NIC at the daemon, plus the read-back that verifies the
 * isolation ACL carried. A form that wrote the row alone would report success while
 * attaching nothing until the next deploy -- the exact silent-success shape this
 * surface exists to avoid. The service also owns the authority check
 * (HohenheimAccess.requireOperationCapability inside each mutator), so /manage and
 * /admin cannot drift on what attaching a disk requires.
 */
public class InstanceDeviceResource extends RowResource {

    protected final InstanceDevices devices = new InstanceDevices();

    private final FormSpec formSpec = FormSpec.builder()
        .add(InstanceDeviceModel.INSTANCE_ID)
        .add(InstanceDeviceModel.TYPE)
        .add(InstanceDeviceModel.NAME)
        .add(InstanceDeviceModel.SIZE_GB)
        .add(InstanceDeviceModel.SOURCE_MEDIA)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(InstanceDeviceModel.NAME).filterable().build())
        .column(ColumnSpec.fromField(InstanceDeviceModel.INSTANCE_ID).build())
        .column(ColumnSpec.fromField(InstanceDeviceModel.TYPE).filterable().build())
        .column(ColumnSpec.fromField(InstanceDeviceModel.SIZE_GB).build())
        .column(ColumnSpec.fromField(InstanceDeviceModel.SOURCE_MEDIA).build())
        .column(ColumnSpec.fromField(InstanceDeviceModel.CREATED_AT).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_device"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance_device"); }
    @Override public @NonNull String slug() { return "instance-devices"; }
    @Override public @NonNull Model model() { return Models.get(InstanceDeviceModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 19; }
    @Override public @NonNull Icon icon() { return Icon.of("hard-drive"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> AccessDecision.allow(QueryPredicate.of(
            InstanceDeviceModel.INSTANCE_ID.isNotNull()));
    }

    /**
     * Attaching reaches the DAEMON, so the framework's rollback-after-the-fact scope
     * verification must not wrap it: a rolled-back create would remove the row and
     * leave the volume, and the host lease the attach takes cannot be acquired inside
     * the caller's own write transaction at all (single-writer engines refuse it by
     * name). {@link InstanceDevices} asks {@code requireOperationCapability} on the
     * target instance as its FIRST statement, before any write -- scope verified up
     * front, which is the stronger form the flag exists for.
     */
    @Override
    public boolean verifiesScopeBeforeMutating() {
        return true;
    }

    @Override
    public @Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("instances",
            InstanceDeviceResource::instanceIdOf).tab("devices");
    }

    /**
     * Resize and detach both demand {@code CONFIG} on the device's OWN instance, so the
     * affordances that lead to them are offered on exactly that answer.
     *
     * AIDEV-NOTE: this is the SAME question {@link InstanceDevices} asks as the first
     * statement of every mutator -- asked once more, earlier, so the surface stops
     * offering what the funnel will refuse. It cannot be a permission: authority here
     * lives on a RELATED record (a per-instance grant), and a Permission is a name, which
     * is why the affordances hung on the type-level updatePermission/deletePermission and
     * a view-only delegate was shown a detach button that could only 422. Read stays
     * WIDER on purpose (ManageInstanceDeviceResource scopes the list to {@code view}):
     * seeing that a disk exists on an instance you may view is not authority to change
     * it, and narrowing the read to match would delete that deliberate reading.
     */
    @Override
    public boolean writableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        return HohenheimAccess.hasInstanceCapability(accessContext, instanceIdOf(record),
            HohenheimAccess.CONFIG);
    }

    /**
     * Detach DELETES the backing volume at the daemon, so the confirmation says that in
     * so many words instead of the generic "are you sure": an accidental click here
     * loses a tenant's data and nothing on this side can bring it back.
     */
    @Override
    public @NonNull ConfirmationSpec deleteConfirmation() {
        return ConfirmationSpec.builder()
            .title(Microcopy.of("confirm_title").withFilter("scope", "cms"))
            .body(Microcopy.of("detach_confirm").withFilter("scope", "instance_device"))
            .confirmLabel(Microcopy.of("detach").withFilter("scope", "instance_device"))
            .cancelLabel(Microcopy.of("cancel").withFilter("scope", "cms"))
            .style(ActionStyle.DESTRUCTIVE)
            .build();
    }

    /** The Devices tab links here with ?instance_id= and ?type= so the target is preset. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(formSpec().defaultValues());
        String instanceId = conduit.getQueryParam("instance_id");
        if (instanceId != null && !instanceId.isEmpty()) {
            values.put("instance_id", instanceId);
        }
        String type = conduit.getQueryParam("type");
        if (InstanceDeviceModel.TYPE_DISK.equals(type)
                || InstanceDeviceModel.TYPE_NIC.equals(type)
                || InstanceDeviceModel.TYPE_CDROM.equals(type)) {
            values.put("type", type);
        }
        return Map.copyOf(values);
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        int instanceId = requireInstance(coerced.get("instance_id"));
        String name = String.valueOf(coerced.get("name"));
        String type = String.valueOf(coerced.get("type"));

        if (InstanceDeviceModel.TYPE_DISK.equals(type)) {
            this.devices.attachDisk(instanceId, name, sizeOf(coerced, null));
        } else if (InstanceDeviceModel.TYPE_NIC.equals(type)) {
            this.devices.attachNic(instanceId, name);
        } else if (InstanceDeviceModel.TYPE_CDROM.equals(type)) {
            this.devices.attachCdrom(instanceId, name,
                String.valueOf(coerced.getOrDefault("source_media", "")).trim());
        } else {
            throw Violations.ofField("type", type,
                CmsSupport.violationText("device_type_unknown"));
        }
        ActivityLog.record(Models.get(InstanceModel.class), instanceId, "device_attached", null);

        Row created = Models.get(InstanceDeviceModel.class).find()
            .where(InstanceDeviceModel.INSTANCE_ID.eq(instanceId))
            .where(InstanceDeviceModel.NAME.eq(name))
            .first();
        if (created == null) {
            // attachDisk/attachNic write the row before the daemon call and delete it
            // again on a daemon refusal; a missing row here means the refusal lane ran
            // without throwing, which would be the silent success this must never be.
            throw Violations.ofForm(CmsSupport.violationText("device_attach_incomplete"));
        }
        return created.get(InstanceDeviceModel.ID);
    }

    /**
     * The only editable dimension is a disk's SIZE: a device's name, type and owning
     * instance are its identity at the daemon, and there is no rename or re-home
     * operation to honour a change of them with.
     *
     * AIDEV-NOTE: the three identity reads take the STORED value when the write does not
     * carry the key, which on those keys means "unchanged" and passes. The inline cell
     * lane hands updateRow a map holding EXACTLY ONE entry, so reading them straight off
     * it made a resize refuse with "device_rename_unsupported" -- a refusal about a rename
     * nobody asked for.
     */
    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        int instanceId = instanceIdOf(existing);
        String name = existing.get(InstanceDeviceModel.NAME);
        String type = existing.get(InstanceDeviceModel.TYPE);

        Object submittedName = CmsSupport.valueOf(coerced, existing, InstanceDeviceModel.NAME);
        if (!String.valueOf(name).equals(String.valueOf(submittedName))) {
            throw Violations.ofField("name", submittedName,
                CmsSupport.violationText("device_rename_unsupported"));
        }
        Object submittedType = CmsSupport.valueOf(coerced, existing, InstanceDeviceModel.TYPE);
        if (!String.valueOf(type).equals(String.valueOf(submittedType))
                || instanceId != requireInstance(
                    CmsSupport.valueOf(coerced, existing, InstanceDeviceModel.INSTANCE_ID))) {
            throw Violations.ofField("type", submittedType,
                CmsSupport.violationText("device_retype_unsupported"));
        }
        String storedMedia = existing.get(InstanceDeviceModel.SOURCE_MEDIA);
        Object submittedMedia = coerced.get("source_media");
        if (submittedMedia != null && !String.valueOf(submittedMedia).trim().isEmpty()
                && !String.valueOf(submittedMedia).trim().equals(
                    storedMedia == null ? "" : storedMedia)) {
            // Swapping media is detach-and-attach at the daemon; an in-place edit would
            // report success while the old ISO stayed in the drive until the next deploy.
            throw Violations.ofField("source_media", submittedMedia,
                CmsSupport.violationText("device_media_change_unsupported"));
        }
        if (!InstanceDeviceModel.TYPE_DISK.equals(type)) {
            throw Violations.ofForm(CmsSupport.violationText("device_resize_not_a_disk"));
        }
        this.devices.resizeDisk(instanceId, name, sizeOf(coerced, existing));
        ActivityLog.record(Models.get(InstanceModel.class), instanceId, "device_resized", null);
    }

    /** Delete IS the detach: daemon first (volume deleted VERIFIED), then the row. */
    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        int instanceId = instanceIdOf(existing);
        String name = existing.get(InstanceDeviceModel.NAME);
        ActivityLog.withAction(ActivityLog.ACTION_DELETE, "device_detached",
            () -> this.devices.detach(instanceId, name));
    }

    // -- plumbing -------------------------------------------------------------

    static int instanceIdOf(@NonNull Row row) {
        Integer id = row.get(InstanceDeviceModel.INSTANCE_ID);
        return id != null ? id : -1;
    }

    private static int requireInstance(@Nullable Object value) {
        int instanceId;
        try {
            instanceId = Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException unparseable) {
            instanceId = -1;
        }
        if (instanceId <= 0 || Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(instanceId))
                .where(InstanceModel.DELETED_AT.isNull()).count() == 0) {
            throw Violations.ofField("instance_id", value,
                CmsSupport.violationText("unknown_instance"));
        }
        return instanceId;
    }

    /** The submitted size, else the stored one -- a write that carries no size resizes nothing. */
    private static int sizeOf(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        Object size = CmsSupport.valueOf(coerced, existing, InstanceDeviceModel.SIZE_GB);
        if (size instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(size));
        } catch (NumberFormatException unparseable) {
            // The model's own beforeValidate owns this refusal identity; 0 reaches it.
            return 0;
        }
    }
}
