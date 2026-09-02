package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.DoubleField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.validation.Violations;

/**
 * A RUNNING database engine process on one host that serves many managed databases
 * as logical databases with their own credentials -- the container a
 * {@link DatabaseModel#PLACEMENT_SHARED shared} record lives in. Operator-owned; it
 * owns a {@code hohenheim:database_container} instance exactly like a dedicated
 * database record does, and is booked against the host once.
 *
 * AIDEV-NOTE: the engine-KIND vocabulary and the status vocabulary are
 * {@link DatabaseModel}'s (its two field builders); nothing here re-spells a token.
 * Root credentials are the controller's alone: readiness, logical-database creation,
 * dumps and restores use them, a workload only ever sees its own database's user.
 */
public class DatabaseEngineModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "database_engine");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimFormCopy.label("name"))
        .help(HohenheimFormCopy.help("database_engine_name"))
        .build());
    public static final EnumField ENGINE = SCHEMA.addField(DatabaseModel.engineFieldBuilder("engine")
        .label(HohenheimFormCopy.label("engine"))
        .help(HohenheimFormCopy.help("engine"))
        .build());
    public static final StringField IMAGE = SCHEMA.addField(StringField.builder().name("image")
        .label(HohenheimFormCopy.label("image"))
        .help(HohenheimFormCopy.help("image"))
        .build());
    /** The host the engine container runs on: a {@code servers.id} FK, never a name. */
    public static final IntegerField SERVER_ID = SCHEMA.addField(IntegerField.builder().name("server_id")
        .label(HohenheimFormCopy.label("server"))
        .help(HohenheimFormCopy.help("server"))
        .build());
    public static final StringField ROOT_USER = SCHEMA.addField(StringField.builder().name("root_user")
        .label(HohenheimFormCopy.label("root_user"))
        .help(HohenheimFormCopy.help("database_engine_root_user"))
        .build());
    public static final StringField ROOT_PASSWORD = SCHEMA.addField(StringField.builder()
        .name("root_password")
        .secret()
        .encrypted()
        .label(HohenheimFormCopy.label("root_password"))
        .help(HohenheimFormCopy.help("database_engine_root_password"))
        .build());
    public static final IntegerField MEMORY_LIMIT_MB = SCHEMA.addField(IntegerField.builder()
        .name("memory_limit_mb")
        .label(HohenheimFormCopy.label("memory_limit"))
        .help(HohenheimFormCopy.help("memory_limit"))
        .build());
    public static final DoubleField CPU_LIMIT = SCHEMA.addField(DoubleField.builder().name("cpu_limit")
        .label(HohenheimFormCopy.label("cpu_limit"))
        .help(HohenheimFormCopy.help("cpu_limit"))
        .build());
    public static final EnumField STATUS = SCHEMA.addField(
        DatabaseModel.statusFieldBuilder("status").build());
    public static final TextField FAILURE_REASON = SCHEMA.addField(TextField.builder()
        .name("failure_reason")
        .label(HohenheimFormCopy.label("failure_reason"))
        .help(HohenheimFormCopy.help("database_failure_reason"))
        .filterable(false)
        .build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        // The name is the data-volume key and a Docker object name, like a database's.
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null || !row.has(NAME.getName())) {
                return;
            }
            Object name = row.get(NAME.getName());
            if (name != null && !DatabaseModel.isValidName(String.valueOf(name))) {
                throw Violations.ofField(NAME.getName(), name,
                    Microcopy.of("database_name_invalid").withFilter("scope", "violations"));
            }
        });
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null && row.get(ID) == null && row.get(SERVER_ID) == null) {
                row.set(SERVER_ID, ServerModel.localServerId());
            }
        });
    }

    /** The engine with this unique name, or null if none. */
    public Row findByName(String name) {
        return find().where(NAME.eq(name)).first();
    }

    /** The engine of this kind on this host, or null when the host has none yet. */
    public Row findOnHost(int serverId, String engineToken) {
        return find().where(SERVER_ID.eq(serverId)).where(ENGINE.eq(engineToken)).first();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "DatabaseEngine"; }

    @Override
    public String getTableName() { return "database_engines"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
