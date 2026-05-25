package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * A managed database provisioned as a container by ManagedDatabase. Stores the desired
 * configuration (engine, image, credentials, initial database); runtime state (published
 * port, container id) is read from Docker on demand, not persisted. {@code engine} is the
 * lowercase token of {@code ManagedDatabase.Engine} ({@code "postgres"}, ...) -- kept as a
 * string because this model lives in the common source set, which can't see the server enum.
 */
public class DatabaseModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "database");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name").build());
    public static final StringField ENGINE = SCHEMA.addField(StringField.builder().name("engine").build());
    public static final StringField IMAGE = SCHEMA.addField(StringField.builder().name("image").build());
    public static final StringField DB_USER = SCHEMA.addField(StringField.builder().name("db_user").build());
    public static final StringField DB_PASSWORD = SCHEMA.addField(StringField.builder().name("db_password").build());
    public static final StringField DB_NAME = SCHEMA.addField(StringField.builder().name("db_name").build());
    public static final BooleanField EPHEMERAL = SCHEMA.addField(BooleanField.builder("ephemeral").defaultValue(false).build());
    public static final StringField STATUS = SCHEMA.addField(StringField.builder().name("status").build());
    public static final StringField SERVER_NAME = SCHEMA.addField(StringField.builder().name("server_name").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    private final Datasource datasource;

    public DatabaseModel(Datasource datasource) {
        this.datasource = datasource;
    }

    /** The database with this unique name, or null if none. */
    public Row findByName(String name) {
        return find().where(NAME.eq(name)).first();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "Database"; }

    @Override
    public String getTableName() { return "managed_databases"; }

    @Override
    public Schema getSchema() { return SCHEMA; }

    @Override
    protected Datasource getDatasource() { return this.datasource; }
}
