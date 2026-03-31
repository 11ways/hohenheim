package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

public class UserModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "user");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField EMAIL = SCHEMA.addField(StringField.builder().name("email").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name").build());
    public static final StringField PASSWORD_HASH = SCHEMA.addField(StringField.builder().name("password_hash").build());
    public static final StringField TOTP_SECRET = SCHEMA.addField(StringField.builder().name("totp_secret").build());
    public static final BooleanField TOTP_ENABLED = SCHEMA.addField(BooleanField.builder("totp_enabled").defaultValue(false).build());
    public static final BooleanField IS_DISABLED = SCHEMA.addField(BooleanField.builder("is_disabled").defaultValue(false).build());
    public static final BooleanField FORCE_PASSWORD_RESET = SCHEMA.addField(BooleanField.builder("force_password_reset").defaultValue(false).build());
    public static final DateTimeField EMAIL_VERIFIED_AT = SCHEMA.addField(DateTimeField.builder().name("email_verified_at").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());
    public static final DateTimeField DELETED_AT = SCHEMA.addField(DateTimeField.builder().name("deleted_at").build());

    private final Datasource datasource;

    public UserModel(Datasource datasource) {
        this.datasource = datasource;
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "User"; }

    @Override
    public String getTableName() { return "users"; }

    @Override
    public Schema getSchema() { return SCHEMA; }

    @Override
    protected Datasource getDatasource() { return this.datasource; }
}
