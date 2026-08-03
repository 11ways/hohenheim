package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * A driver-level snapshot of one instance's volumes: a point-in-time copy stored on
 * the CONTROLLER host, restorable in place. A snapshot is NOT a backup -- it shares
 * the instance's failure domain and dies with the host; the distinct
 * {@link InstanceBackupModel} rows are what leave the host. Distinct records with
 * distinct capabilities, by the instance-tier plan's explicit call.
 */
public class InstanceSnapshotModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "instance_snapshot");
    public static final Schema SCHEMA = new Schema();

    /** {@link #STATUS}: capture finished and every payload checksum was verified. */
    public static final String STATUS_COMPLETE = "complete";

    /** {@link #STATUS}: capture failed; the files are gone and restore refuses the row. */
    public static final String STATUS_FAILED = "failed";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("instance_id")
            .label(HohenheimFormCopy.label("instance"))
            .build());

    public static final EnumField STATUS = SCHEMA.addField(EnumField.builder("status")
        .value(STATUS_COMPLETE, v -> v.displayName("Complete").icon("circle-check")
            .label(Microcopy.of("complete").withFilter("scope", "snapshot_status")).color("green"))
        .value(STATUS_FAILED, v -> v.displayName("Failed").icon("circle-exclamation")
            .label(Microcopy.of("failed").withFilter("scope", "snapshot_status")).color("red"))
        .defaultValue(STATUS_FAILED)
        .build());

    /** Free-form operator note ("before 1.20 upgrade"). */
    public static final StringField NOTE = SCHEMA.addField(StringField.builder().name("note")
        .label(HohenheimFormCopy.label("note"))
        .build());

    /** Host directory holding this snapshot's payload files. */
    public static final StringField DIRECTORY = SCHEMA.addField(
        StringField.builder().name("directory").filterable(false).build());

    /**
     * Per-volume payload inventory: list of maps {name, path, file, sha256, size}.
     * The checksums recorded here are what restore verifies BEFORE touching any
     * live state.
     */
    public static final SchemaField VOLUMES = SCHEMA.addField(
        SchemaField.builder("volumes").build());

    public static final LongField TOTAL_BYTES = SCHEMA.addField(
        LongField.builder("total_bytes").filterable(false).build());

    public static final TextField ERROR = SCHEMA.addField(TextField.builder().name("error").build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "InstanceSnapshot"; }
    @Override public String getTableName() { return "instance_snapshots"; }
    @Override public Schema getSchema() { return SCHEMA; }
}
