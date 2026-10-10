package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.ColorHue;

/**
 * A driver-level snapshot of one instance's volumes: a point-in-time copy stored on
 * the CONTROLLER host, restorable in place. A snapshot is NOT a backup -- it shares
 * the instance's failure domain and dies with the host; the distinct
 * {@link InstanceBackupModel} rows are what leave the host. Distinct records with
 * distinct capabilities, by explicit decision.
 */
public class InstanceSnapshotModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("instance_snapshot");
    public static final Schema SCHEMA = new Schema();

    /** {@link #STATUS}: capture finished and every payload checksum was verified. */
    public static final String STATUS_COMPLETE = "complete";

    /** {@link #STATUS}: capture failed; the files are gone and restore refuses the row. */
    public static final String STATUS_FAILED = "failed";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("instance_id")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("instance"))
            .build());

    public static final EnumField STATUS = SCHEMA.addField(EnumField.builder("status")
        .value(STATUS_COMPLETE, v -> v.displayName("Complete").icon("circle-check")
            .label(HohenheimMicrocopy.SNAPSHOT_STATUS.of("complete")).color(ColorHue.GREEN))
        .value(STATUS_FAILED, v -> v.displayName("Failed").icon("circle-exclamation")
            .label(HohenheimMicrocopy.SNAPSHOT_STATUS.of("failed")).color(ColorHue.RED))
        .defaultValue(STATUS_FAILED)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("status")).build());

    /** Free-form operator note ("before 1.20 upgrade"). */
    public static final StringField NOTE = SCHEMA.addField(StringField.builder().name("note")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("note"))
        .build());

    /** Host directory holding this snapshot's payload files (volume-tar lane only). */
    public static final StringField DIRECTORY = SCHEMA.addField(
        StringField.builder().name("directory").filterable(false).build());

    /**
     * DAEMON-side snapshot name (native lane only, e.g. Incus): the payload lives in
     * the instance's own storage pool, not on the controller, and dies with the
     * host/pool -- which is exactly why a snapshot is not a backup.
     */
    public static final StringField NATIVE_NAME = SCHEMA.addField(
        StringField.builder().name("native_name").filterable(false).build());

    /**
     * Per-volume payload inventory: list of maps {name, path, file, sha256, size}.
     * The checksums recorded here are what restore verifies BEFORE touching any
     * live state.
     */
    public static final SchemaField VOLUMES = SCHEMA.addField(
        SchemaField.builder("volumes").build());

    public static final LongField TOTAL_BYTES = SCHEMA.addField(
        LongField.builder("total_bytes").filterable(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("total_bytes")).build());

    public static final TextField ERROR = SCHEMA.addField(TextField.builder().name("error").build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("created_at")).build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        // The operator's own note. NATIVE_NAME is deliberately not a second display field:
        // it is filterable(false), and a display field IS the derived record source's
        // search field -- the note-less fallback lives on the resource instead.
        SCHEMA.setDisplayFields(NOTE);
    }

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "InstanceSnapshot"; }
    @Override public String getTableName() { return "instance_snapshots"; }
    @Override public Schema getSchema() { return SCHEMA; }
}
