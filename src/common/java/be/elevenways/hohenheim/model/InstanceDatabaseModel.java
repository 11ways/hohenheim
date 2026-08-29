package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

/**
 * Attaches a managed database to an INSTANCE: the workload receives the database's
 * connection details as environment variables derived at deploy time (never stored), and
 * they substitute into {@code command}, {@code cloud_init} and config files as
 * {@code {{PREFIX_PASSWORD}}} tokens. {@code env_prefix} names the variable family
 * ({@code DB} gives {@code DB_HOST}, ...); the instance's oldest link is the primary one
 * and additionally emits {@code DATABASE_URL}.
 *
 * AIDEV-NOTE: this is THE database-attachment table, and it used to have a site-keyed twin
 * ({@code site_databases}). Phase-0 brief 7 deleted that twin along with the record it hung
 * off: a site runs nothing, so nothing can be injected into it. The owner may still differ
 * from the CONSUMER -- an application owns its links while its serving release consumes
 * them -- which {@code InstanceDatabaseNetworks} resolves, and which is why the link
 * network is named after the owner rather than after whichever container is up.
 */
public class InstanceDatabaseModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "instance_database");
    public static final Schema SCHEMA = new Schema();

    /** Default {@link #ENV_PREFIX} when none is given; the site lane's spelling. */
    public static final String DEFAULT_PREFIX = "DB";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("instance_id").build());
    public static final IntegerField DATABASE_ID = SCHEMA.addField(
        IntegerField.builder().name("database_id").build());

    /** The attached workload, declared so a database delete can ask which live ones still hold it. */
    public static final BelongsTo<InstanceModel> INSTANCE = SCHEMA.addRelation(
        BelongsTo.to(InstanceModel.class)
            .name("instance")
            .localKey(INSTANCE_ID)
            .remoteKey(InstanceModel.ID)
            .build());

    /** The attached database, declared so its delete takes the links along (InstanceDatabaseLinks). */
    public static final BelongsTo<DatabaseModel> DATABASE = SCHEMA.addRelation(
        BelongsTo.to(DatabaseModel.class)
            .name("database")
            .localKey(DATABASE_ID)
            .remoteKey(DatabaseModel.ID)
            .build());
    public static final StringField ENV_PREFIX = SCHEMA.addField(
        StringField.builder().name("env_prefix")
            .label(HohenheimFormCopy.label("env_prefix"))
            .help(HohenheimFormCopy.help("env_prefix"))
            .build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("updated_at").build());

    /** All links for an instance, oldest first (the first one is the primary link). */
    public List<Row> findByInstanceId(Integer instanceId) {
        return find()
            .where(INSTANCE_ID.eq(instanceId))
            .orderBy(ID, SortOrder.ASC)
            .all();
    }

    /** All links pointing at a database. */
    public List<Row> findByDatabaseId(Integer databaseId) {
        return find().where(DATABASE_ID.eq(databaseId)).all();
    }

    static {
        // The env prefix names the variable family this link injects, which is the only
        // thing distinguishing two databases attached to one instance.
        SCHEMA.setDisplayFields(ENV_PREFIX);
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "InstanceDatabase"; }

    @Override
    public String getTableName() { return "instance_databases"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
