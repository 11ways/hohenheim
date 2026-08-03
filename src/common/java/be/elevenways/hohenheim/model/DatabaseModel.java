package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.protoblast.common.registry.Identifier;
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

    /** {@link #STATUS} value while the container is being provisioned. */
    public static final String STATUS_PROVISIONING = "provisioning";

    /** {@link #STATUS} value for a provisioned, usable database. */
    public static final String STATUS_ACTIVE = "active";

    /** {@link #STATUS} value when provisioning failed. */
    public static final String STATUS_FAILED = "failed";

    /**
     * {@link #STATUS} value when a destroy could not verify its teardown: the record (and
     * the only copy of {@code db_password}) is deliberately KEPT, the container may still
     * run, and the operator retries or force-destroys explicitly.
     */
    public static final String STATUS_DESTROY_FAILED = "destroy_failed";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimFormCopy.label("name"))
        .help(HohenheimFormCopy.help("database_name"))
        .build());
    public static final EnumField ENGINE = SCHEMA.addField(EnumField.builder("engine")
        .value("postgres", v -> v.displayName("PostgreSQL").icon("database").color("blue"))
        .value("mysql", v -> v.displayName("MySQL").icon("database").color("orange"))
        .value("redis", v -> v.displayName("Redis").icon("bolt").color("red"))
        .value("mongo", v -> v.displayName("MongoDB").icon("leaf").color("green"))
        .label(HohenheimFormCopy.label("engine"))
        .help(HohenheimFormCopy.help("engine"))
        .build());
    public static final StringField IMAGE = SCHEMA.addField(StringField.builder().name("image")
        .label(HohenheimFormCopy.label("image"))
        .help(HohenheimFormCopy.help("image"))
        .build());
    public static final StringField DB_USER = SCHEMA.addField(StringField.builder().name("db_user")
        .label(HohenheimFormCopy.label("db_user"))
        .help(HohenheimFormCopy.help("db_user"))
        .build());
    public static final StringField DB_PASSWORD = SCHEMA.addField(StringField.builder().name("db_password")
        .secret()
        .encrypted()
        .label(HohenheimFormCopy.label("db_password"))
        .help(HohenheimFormCopy.help("db_password"))
        .build());
    public static final StringField DB_NAME = SCHEMA.addField(StringField.builder().name("db_name")
        .label(HohenheimFormCopy.label("db_name"))
        .help(HohenheimFormCopy.help("db_name"))
        .build());
    public static final BooleanField EPHEMERAL = SCHEMA.addField(BooleanField.builder("ephemeral")
        .defaultValue(false)
        .label(HohenheimFormCopy.label("ephemeral"))
        .help(HohenheimFormCopy.help("ephemeral"))
        .build());
    // Optional container resource caps (null = unlimited).
    public static final IntegerField MEMORY_LIMIT_MB = SCHEMA.addField(IntegerField.builder().name("memory_limit_mb")
        .label(HohenheimFormCopy.label("memory_limit"))
        .help(HohenheimFormCopy.help("memory_limit"))
        .build());
    public static final DoubleField CPU_LIMIT = SCHEMA.addField(DoubleField.builder().name("cpu_limit")
        .label(HohenheimFormCopy.label("cpu_limit"))
        .help(HohenheimFormCopy.help("cpu_limit"))
        .build());
    public static final EnumField STATUS = SCHEMA.addField(EnumField.builder("status")
        .value(STATUS_PROVISIONING, v -> v.displayName("Provisioning").icon("rotate").color("warning"))
        .value(STATUS_ACTIVE, v -> v.displayName("Active").icon("circle-check").color("success"))
        .value(STATUS_FAILED, v -> v.displayName("Failed").icon("circle-xmark").color("destructive"))
        .value(STATUS_DESTROY_FAILED,
            v -> v.displayName("Destroy failed").icon("triangle-exclamation").color("destructive"))
        .build());
    /** The host this database's container runs on: a {@code servers.id} FK, never a name. */
    public static final IntegerField SERVER_ID = SCHEMA.addField(IntegerField.builder().name("server_id")
        .label(HohenheimFormCopy.label("server"))
        .help(HohenheimFormCopy.help("server"))
        .build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        // A managed database always has a concrete host; default the FK at create time
        // so no consumer ever re-invents a "blank means local" spelling.
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null && row.get(ID) == null && row.get(SERVER_ID) == null) {
                row.set(SERVER_ID, ServerModel.localServerId());
            }
        });
        // A database's published host port is recorded in the ledger after the container
        // hands it out (DatabaseService), so the record's death must release it. Via the
        // before/after pairing because a remove context carries CRITERIA, not a row --
        // see PortLedger.captureDoomedOwners. Without this, deleting the record through
        // ANY path other than DatabaseService.destroy leaves the port unclaimable forever.
        SCHEMA.addBeforeRemoveHook(PortLedger::captureDoomedOwners);
        SCHEMA.addAfterRemoveHook(PortLedger::releaseDoomedOwners);
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
}
