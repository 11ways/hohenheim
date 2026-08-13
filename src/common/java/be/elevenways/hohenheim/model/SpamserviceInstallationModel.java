package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;
import be.elevenways.zenit.common.validation.validator.Range;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Stores the single local Spamservice runtime installation supervised by Hohenheim. */
public class SpamserviceInstallationModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "spamservice_installation");
    public static final int SINGLETON_ID = 1;
    public static final Schema SCHEMA = new Schema();

    public static final int MIN_PORT = 1_024;
    public static final int MAX_PORT = 65_535;
    public static final int MIN_HEAP_SIZE_MB = 64;
    public static final int MAX_HEAP_SIZE_MB = 4_096;

    public static final IntegerField ID = SCHEMA.addField(
        IntegerField.builder().name("id").defaultValue(SINGLETON_ID).build());
    public static final BooleanField ENABLED = SCHEMA.addField(
        BooleanField.builder("enabled").defaultValue(false).build());
    public static final IntegerField PORT = SCHEMA.addField(
        IntegerField.builder().name("port").defaultValue(8095)
            .validator(Range.of(MIN_PORT, MAX_PORT)).build());
    // AIDEV-NOTE: deliberately NOT .required(). The requirement is CONDITIONAL -- only an
    // ENABLED installation needs a system user, and that is enforced where it is knowable
    // (SpamserviceManager's installation store refuses to start without a resolvable user).
    // Declaring it unconditionally made the shipped singleton stub, which by definition has
    // no user yet, unrepresentable through the validating save path: it could only ever be
    // written by the raw INSERT that used to sit in M041.
    public static final IntegerField SYSTEM_USER_ID = SCHEMA.addField(
        IntegerField.builder().name("system_user_id").build());
    public static final IntegerField MAX_HEAP_MB = SCHEMA.addField(
        IntegerField.builder().name("max_heap_mb").defaultValue(512)
            .validator(Range.of(MIN_HEAP_SIZE_MB, MAX_HEAP_SIZE_MB)).build());
    public static final StringField CONTROLLER_KEY = SCHEMA.addField(
        StringField.builder().name("controller_key").secret().encrypted().filterable(false).build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("updated_at").build());

    public static final BelongsTo<SystemUserModel> SYSTEM_USER = SCHEMA.addRelation(
        BelongsTo.to(SystemUserModel.class).name("system_user")
            .localKey(SYSTEM_USER_ID).remoteKey(SystemUserModel.ID).build());

    /** Returns the one configured installation row without creating it. */
    public @Nullable Row installation() {
        return find().where(ID.eq(SINGLETON_ID)).first();
    }

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "SpamserviceInstallation"; }
    @Override public String getTableName() { return "spamservice_installations"; }
    @Override public Schema getSchema() { return SCHEMA; }
}
