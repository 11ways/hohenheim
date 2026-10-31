package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.i18n.wording.ByteUnit;

import be.elevenways.protoblast.common.i18n.wording.Noun;
import be.elevenways.protoblast.common.i18n.wording.NounFact;
import be.elevenways.zenit.common.orm.behaviour.HistoryBehaviour;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.ModelRef;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.ColorHue;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * A driver-level snapshot of one instance's volumes: a point-in-time copy stored on
 * the CONTROLLER host, restorable in place. A snapshot is NOT a backup -- it shares
 * the instance's failure domain and dies with the host; the distinct
 * {@link InstanceBackupModel} rows are what leave the host. Distinct records with
 * distinct capabilities, by explicit decision.
 */
public class InstanceSnapshotModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("instance_snapshot");
    /** The instance snapshot model's reference: its class and MODEL_ID, resolved at first use. */
    public static final ModelRef<InstanceSnapshotModel> REF = ModelRef.of(InstanceSnapshotModel.class, MODEL_ID);

    public static final Schema SCHEMA = new Schema();

    /** The {@link #STATUS} vocabulary; each member states whether a snapshot in it can be restored. */
    public enum Status {

        /** The capture is running: the row exists from its start, its payload is still being written. */
        CAPTURING("capturing", "Capturing", "camera", ColorHue.BLUE),

        /** Capture finished and every payload checksum was verified. */
        COMPLETE("complete", "Complete", "circle-check", ColorHue.GREEN),

        /**
         * Capture failed, or a controller restart interrupted it ({@code InstanceSnapshots.recoverInterrupted});
         * the payload is removed, the row kept as evidence, and restore refuses it.
         */
        FAILED("failed", "Failed", "circle-exclamation", ColorHue.RED);

        private final @NonNull String key;
        private final @NonNull String displayName;
        private final @NonNull String icon;
        private final @NonNull ColorHue color;

        Status(@NonNull String key, @NonNull String displayName, @NonNull String icon, @NonNull ColorHue color) {
            this.key = key;
            this.displayName = displayName;
            this.icon = icon;
            this.color = color;
        }

        /** The stored key this member round-trips through. */
        public @NonNull String key() {
            return this.key;
        }

        /** Whether a snapshot in this status holds a verified payload a restore may read. */
        public boolean restorable() {
            return switch (this) {
                case COMPLETE -> true;
                case CAPTURING, FAILED -> false;
            };
        }

        /** @return the member stored under this key, null for a blank or unknown one */
        public static @Nullable Status parse(@Nullable String key) {
            for (Status status : values()) {
                if (status.key.equals(key)) {
                    return status;
                }
            }
            return null;
        }
    }

    public static final String STATUS_CAPTURING = Status.CAPTURING.key();
    public static final String STATUS_COMPLETE = Status.COMPLETE.key();
    public static final String STATUS_FAILED = Status.FAILED.key();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("instance_id")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("instance"))
            .build());

    /**
     * AIDEV-NOTE: the default stays FAILED, as instance_backups' does: a row no capture stamped never reads
     * restorable. A capture stamps CAPTURING itself when it creates its row.
     */
    public static final EnumField STATUS = SCHEMA.addField(statusField());

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
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("total_bytes")).byteSize(ByteUnit.BYTE).build());

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

    private static final Noun NOUN = new Noun(Map.of(
        "en", new Noun.Forms("instance snapshot", "instance snapshots", Map.of(NounFact.ARTICLE, "an")),
        "nl", new Noun.Forms("instantiesnapshot", "instantiesnapshots", Map.of(NounFact.GENDER, "common"))));

    @Override public @NonNull Noun noun() { return NOUN; }

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "InstanceSnapshot"; }
    @Override public String getTableName() { return "instance_snapshots"; }
    @Override public Schema getSchema() { return SCHEMA; }

    /** Whether this snapshot can be restored: its status holds a verified payload; an unknown status never does. */
    public static boolean isRestorable(@NonNull Row snapshot) {
        Status status = Status.parse(snapshot.get(STATUS));
        return status != null && status.restorable();
    }

    private static EnumField statusField() {
        EnumField.Builder builder = EnumField.builder("status");
        for (Status status : Status.values()) {
            builder.value(status.key, value -> value.displayName(status.displayName).icon(status.icon)
                .label(HohenheimMicrocopy.SNAPSHOT_STATUS.of(status.key)).color(status.color));
        }
        return builder.defaultValue(Status.FAILED.key).label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("status")).build();
    }

    /** A snapshot's change is filed on its instance. */
    public static final HistoryBehaviour HISTORY = SCHEMA.addBehaviour(HistoryBehaviour.optional().changes()
        .filedOn(InstanceModel.class, row -> row.get(INSTANCE_ID)));
}
