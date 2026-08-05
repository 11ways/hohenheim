package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.validation.Violations;

/**
 * One attached instance device: an extra disk (a daemon-side custom volume) or an
 * extra NIC. The row is the DESIRED state the deploy reconciles onto the daemon;
 * its reservation in the core ledger is charged by the beforeWrite hook adjacent
 * to this row's write (the InstanceModel.QUOTA_BUCKET shape), so two racing
 * attaches cannot both spend the last slot.
 *
 * AIDEV-NOTE: rows are HARD-deleted (detach and destroy-cleanup), never
 * soft-deleted -- the remove-hook release pairing is the only release lane, unlike
 * instances whose destroy soft-deletes.
 */
public class InstanceDeviceModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "instance_device");
    public static final Schema SCHEMA = new Schema();

    public static final String TYPE_DISK = "disk";
    public static final String TYPE_NIC = "nic";

    /** Device names become daemon device/volume names; keep them tight and portable. */
    private static final String NAME_PATTERN = "[a-z][a-z0-9-]{0,30}";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("instance_id").build());

    public static final EnumField TYPE = SCHEMA.addField(EnumField.builder("type")
        .value(TYPE_DISK, v -> v.displayName("Disk").icon("hard-drive")
            .label(Microcopy.of("disk").withFilter("scope", "instance_device")))
        .value(TYPE_NIC, v -> v.displayName("NIC").icon("network-wired")
            .label(Microcopy.of("nic").withFilter("scope", "instance_device")))
        .build());

    // User data (a device name), never localized.
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimFormCopy.label("name"))
        .build());

    /** Disk size in GB (the quota-charged amount); null for NICs. */
    public static final IntegerField SIZE_GB = SCHEMA.addField(
        IntegerField.builder().name("size_gb")
            .label(HohenheimFormCopy.label("device_size_gb"))
            .build());

    /** The ledger bucket this row's reservation was charged to (reserve hook stamps it). */
    public static final StringField QUOTA_BUCKET = SCHEMA.addField(
        StringField.builder().name("quota_bucket").build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        SCHEMA.setDisplayFields(NAME);
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            if (row.has(NAME.getName())) {
                String name = row.get(NAME);
                if (name == null || !name.matches(NAME_PATTERN) || "root".equals(name)
                        || "eth0".equals(name)) {
                    // root and eth0 are the driver-owned devices; a row claiming either
                    // would collide with the create body's own overrides.
                    throw Violations.ofField("name", name,
                        Microcopy.of("device_name_invalid").withFilter("scope", "violations")
                            .withArg("name", String.valueOf(name)));
                }
            }
            // A disk write that CARRIES the type must carry a real size (a sizeless
            // disk would reserve 0 and count nothing); a size update alone is judged
            // on the staged value.
            boolean declaresDisk = row.has(TYPE.getName())
                && TYPE_DISK.equals(row.get(TYPE.getName()));
            if (declaresDisk || row.has(SIZE_GB.getName())) {
                Integer size = row.has(SIZE_GB.getName()) ? row.get(SIZE_GB) : null;
                boolean disk = declaresDisk || !row.has(TYPE.getName());
                if (disk && (size == null || size < 1)) {
                    throw Violations.ofField("size_gb", size,
                        Microcopy.of("device_size_invalid").withFilter("scope", "violations"));
                }
            }
        });
    }

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "InstanceDevice"; }
    @Override public String getTableName() { return "instance_devices"; }
    @Override public Schema getSchema() { return SCHEMA; }
}
