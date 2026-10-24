package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.wording.ByteUnit;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.behaviour.HistoryBehaviour;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.LongField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.ModelRef;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * One encrypted dump of a managed database stored on a {@link BackupTargetModel}: the record a restore lists and
 * reads, written by the nightly task, "Back up now" and the pre-restore backup of an in-place restore.
 *
 * AIDEV-NOTE: a dump OUTLIVES its database. {@link #DATABASE_ID} carries no foreign key, and the database's name,
 * engine and image are copied onto the row, because restoring the dump of a deleted database into a new one is the
 * recovery this ledger exists for. Only a COMPLETE row naming its key is restorable ({@link #isRestorable}), and a row
 * is stamped complete only after the target re-hashed the committed artifact.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class DatabaseDumpModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("database_dump");

    /** The database dump model's reference: its class and MODEL_ID, resolved at first use. */
    public static final ModelRef<DatabaseDumpModel> REF = ModelRef.of(DatabaseDumpModel.class, MODEL_ID);

    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField DATABASE_ID = SCHEMA.addField(IntegerField.builder().name("database_id")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("database")).build());

    /** The database's name when the dump was taken. */
    public static final StringField DATABASE_NAME = SCHEMA.addField(StringField.builder().name("database_name")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("name")).build());

    /** The engine token, {@link DatabaseModel#ENGINE}'s vocabulary: a restore refuses a database of another kind. */
    public static final EnumField ENGINE = SCHEMA.addField(DatabaseModel.engineFieldBuilder("engine")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("engine")).build());

    /** The image that served the database, which a restore into a new database runs again. */
    public static final StringField IMAGE = SCHEMA.addField(StringField.builder().name("image").filterable(false)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("image")).build());

    /**
     * The name inside the engine the dump was taken from; a restore into a new database keeps it, because a mongo
     * archive names its database and restores only into that one.
     */
    public static final StringField DB_NAME = SCHEMA.addField(StringField.builder().name("db_name").filterable(false)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("db_name")).build());

    public static final IntegerField TARGET_ID = SCHEMA.addField(IntegerField.builder().name("target_id")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("backup_target")).build());

    public static final EnumField STATUS = SCHEMA.addField(BackupStatus.field());

    /** The committed key on the target (the staging key is never recorded). */
    public static final StringField REMOTE_KEY = SCHEMA.addField(StringField.builder().name("remote_key")
        .filterable(false).label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("remote_key")).build());

    /** sha256 of the encrypted artifact as re-read FROM THE TARGET after commit. */
    public static final StringField SHA256 = SCHEMA.addField(StringField.builder().name("sha256").filterable(false)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("sha256")).build());

    public static final LongField SIZE_BYTES = SCHEMA.addField(LongField.builder("size_bytes").filterable(false)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("size_bytes")).byteSize(ByteUnit.BYTE).build());

    public static final TextField ERROR = SCHEMA.addField(TextField.builder().name("error").build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("created_at")).build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "DatabaseDump"; }
    @Override public String getTableName() { return "database_dumps"; }
    @Override public Schema getSchema() { return SCHEMA; }

    /** Whether this dump can be restored: complete, and naming its committed key. */
    public static boolean isRestorable(@NonNull Row dump) {
        return BackupStatus.restorable(dump.get(STATUS), dump.get(REMOTE_KEY));
    }

    /** Whether this dump is settled (complete or failed), the one state its delete applies to; unknown is not. */
    public static boolean isSettled(@NonNull Row dump) {
        BackupStatus status = BackupStatus.parse(dump.get(STATUS));
        return status != null && status.settled();
    }

    /** The ledger is the system's own bookkeeping; the restore and delete operations write the activity rows. */
    public static final HistoryBehaviour HISTORY = SCHEMA.addBehaviour(HistoryBehaviour.untracked());
}
