package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * One portable, encrypted instance export written to a {@link BackupTargetModel}:
 * config, variables and volume data in a single checksummed archive, restorable to a
 * NEW instance. Distinct from {@link InstanceSnapshotModel} by design -- a snapshot
 * shares the instance's failure domain, a backup is what survives losing it.
 *
 * AIDEV-NOTE: only COMPLETE rows are restorable, and a row is stamped complete only
 * after the target confirmed the committed artifact byte-for-byte (stored sha read
 * back from the target, never from memory) -- an interrupted upload leaves a failed
 * row and no committed artifact, so nothing a later restore would accept.
 */
public class InstanceBackupModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "instance_backup");
    public static final Schema SCHEMA = new Schema();

    /** {@link #STATUS}: the archive is being built or uploaded; not restorable. */
    public static final String STATUS_UPLOADING = "uploading";

    /** {@link #STATUS}: committed on the target and verified end to end. */
    public static final String STATUS_COMPLETE = "complete";

    /** {@link #STATUS}: capture or upload failed; artifact removed, row kept as evidence. */
    public static final String STATUS_FAILED = "failed";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("instance_id")
            .label(HohenheimFormCopy.label("instance"))
            .build());

    public static final IntegerField TARGET_ID = SCHEMA.addField(
        IntegerField.builder().name("target_id")
            .label(HohenheimFormCopy.label("backup_target"))
            .build());

    public static final EnumField STATUS = SCHEMA.addField(EnumField.builder("status")
        .value(STATUS_UPLOADING, v -> v.displayName("Uploading").icon("cloud-arrow-up")
            .label(Microcopy.of("uploading").withFilter("scope", "backup_status")).color("blue"))
        .value(STATUS_COMPLETE, v -> v.displayName("Complete").icon("circle-check")
            .label(Microcopy.of("complete").withFilter("scope", "backup_status")).color("green"))
        .value(STATUS_FAILED, v -> v.displayName("Failed").icon("circle-exclamation")
            .label(Microcopy.of("failed").withFilter("scope", "backup_status")).color("red"))
        .defaultValue(STATUS_FAILED)
        .build());

    /** The committed key on the target (the .part staging key is never recorded). */
    public static final StringField REMOTE_KEY = SCHEMA.addField(
        StringField.builder().name("remote_key").filterable(false).build());

    /** sha256 of the encrypted archive as re-read FROM THE TARGET after commit. */
    public static final StringField SHA256 = SCHEMA.addField(
        StringField.builder().name("sha256").filterable(false).build());

    public static final LongField SIZE_BYTES = SCHEMA.addField(
        LongField.builder("size_bytes").filterable(false).build());

    /**
     * NON-sensitive manifest summary (name, kind, image, volume inventory with
     * checksums). The full manifest -- including instance settings and secret
     * variables -- lives ONLY inside the encrypted archive.
     */
    public static final SchemaField SUMMARY = SCHEMA.addField(
        SchemaField.builder("summary").build());

    public static final TextField ERROR = SCHEMA.addField(TextField.builder().name("error").build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "InstanceBackup"; }
    @Override public String getTableName() { return "instance_backups"; }
    @Override public Schema getSchema() { return SCHEMA; }
}
