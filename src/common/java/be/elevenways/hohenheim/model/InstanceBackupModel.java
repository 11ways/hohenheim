package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.ui.ColorHue;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One portable, encrypted instance export written to a {@link BackupTargetModel}:
 * config, variables and volume data in a single checksummed archive, restorable to a
 * NEW instance. Distinct from {@link InstanceSnapshotModel} by design -- a snapshot
 * shares the instance's failure domain, a backup is what survives losing it.
 *
 * AIDEV-NOTE: only a row whose {@link Status} is restorable AND that names its committed key is restorable
 * ({@link #isRestorable}), and a row is stamped complete only after the target confirmed the committed artifact
 * byte-for-byte (stored sha read back from the target, never from memory) -- an interrupted upload leaves a failed
 * row and no committed artifact, so nothing a later restore would accept.
 */
public class InstanceBackupModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("instance_backup");
    public static final Schema SCHEMA = new Schema();

    /** The {@link #STATUS} vocabulary; each member states whether a backup in it can be restored. */
    public enum Status {

        /** The archive is being built or uploaded. */
        UPLOADING("uploading", "Uploading", "cloud-arrow-up", ColorHue.BLUE),

        /** Committed on the target and verified end to end. */
        COMPLETE("complete", "Complete", "circle-check", ColorHue.GREEN),

        /**
         * Capture or upload failed; artifact removed, row kept as evidence. The one exception: a boot settle that
         * could not reach the target stamps FAILED with the possibly-surviving key named in {@link #ERROR} (see
         * {@code InstanceBackups.recoverInterrupted}).
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

        /** Whether a backup in this status holds a committed, verified archive a restore may read. */
        public boolean restorable() {
            return switch (this) {
                case COMPLETE -> true;
                case UPLOADING, FAILED -> false;
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

    public static final String STATUS_UPLOADING = Status.UPLOADING.key();
    public static final String STATUS_COMPLETE = Status.COMPLETE.key();
    public static final String STATUS_FAILED = Status.FAILED.key();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("instance_id")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("instance"))
            .build());

    public static final IntegerField TARGET_ID = SCHEMA.addField(
        IntegerField.builder().name("target_id")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("backup_target"))
            .build());

    public static final EnumField STATUS = SCHEMA.addField(statusField());

    /** The committed key on the target (the .part staging key is never recorded). */
    public static final StringField REMOTE_KEY = SCHEMA.addField(
        StringField.builder().name("remote_key").filterable(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("remote_key"))
            .build());

    /** sha256 of the encrypted archive as re-read FROM THE TARGET after commit. */
    public static final StringField SHA256 = SCHEMA.addField(
        StringField.builder().name("sha256").filterable(false).build());

    public static final LongField SIZE_BYTES = SCHEMA.addField(
        LongField.builder("size_bytes").filterable(false)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("size_bytes")).build());

    /**
     * NON-sensitive manifest summary (name, kind, image, volume inventory with
     * checksums). The full manifest -- including instance settings and secret
     * variables -- lives ONLY inside the encrypted archive.
     */
    public static final SchemaField SUMMARY = SCHEMA.addField(
        SchemaField.builder("summary").build());

    public static final TextField ERROR = SCHEMA.addField(TextField.builder().name("error").build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("created_at")).build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "InstanceBackup"; }
    @Override public String getTableName() { return "instance_backups"; }
    @Override public Schema getSchema() { return SCHEMA; }

    /**
     * Whether this backup can be restored: its status holds a committed archive and the row names its key; a row
     * without a status or a key never completed, whatever else it says.
     */
    public static boolean isRestorable(@NonNull Row backup) {
        Status status = Status.parse(backup.get(STATUS));
        String key = backup.get(REMOTE_KEY);
        return status != null && status.restorable() && key != null && !key.isBlank();
    }

    /** The summary key naming the data disks a backup carries, written by the manifest's summary. */
    public static final String SUMMARY_DISKS = "disks";

    /** The summary key naming the host-directory volumes a backup carries, written by the manifest's summary. */
    public static final String SUMMARY_HOST_VOLUMES = "host_volumes";

    /**
     * The data disks (device names) this backup carries, read off its stored summary: the one read every surface
     * naming them goes through.
     *
     * @return the names in capture order, empty for a backup without disks or written before disks were captured
     */
    public static @NonNull List<String> dataDisksOf(@NonNull Row backup) {
        return summaryNamesOf(backup, SUMMARY_DISKS);
    }

    /** @return the host volumes (names) this backup carries, empty for one written before they were captured */
    public static @NonNull List<String> hostVolumesOf(@NonNull Row backup) {
        return summaryNamesOf(backup, SUMMARY_HOST_VOLUMES);
    }

    private static @NonNull List<String> summaryNamesOf(@NonNull Row backup, @NonNull String key) {
        List<String> names = new ArrayList<>();
        for (Object value : RawValues.list(RawValues.map((Object) backup.get(SUMMARY)).get(key))) {
            String name = RawValues.nonBlankString(value);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    private static EnumField statusField() {
        EnumField.Builder builder = EnumField.builder("status");
        for (Status status : Status.values()) {
            builder.value(status.key, value -> value.displayName(status.displayName).icon(status.icon)
                .label(HohenheimMicrocopy.BACKUP_STATUS.of(status.key)).color(status.color));
        }
        return builder.defaultValue(Status.FAILED.key).label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("status")).build();
    }

    /**
     * This instance's newest backup, whatever its status: by id, never created_at, which two backups inside one second
     * share.
     *
     * @return the row, null when the instance has no backup
     */
    public @Nullable Row newestOf(int instanceId) {
        return this.find().where(INSTANCE_ID.eq(instanceId)).orderBy(ID, SortOrder.DESC).first();
    }
}
