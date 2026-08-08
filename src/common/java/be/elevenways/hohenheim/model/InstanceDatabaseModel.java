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
 * AIDEV-NOTE: a SECOND join model beside {@link SiteDatabaseModel} rather than one
 * owner-polymorphic table, decided 2026-08-08 on evidence and not on symmetry. Three
 * reasons, any one of which is sufficient. (1) `site_databases` has fourteen production
 * consumers and most are structurally site-shaped: {@code SiteReleases} folds the links
 * into the release FINGERPRINT (so an attach mints a new release), {@code ProxyReloadHooks}
 * reloads the proxy on a link change, {@code DockerReconciler} resolves orphan ownership
 * by this model id, {@code AttentionCollector} and {@code DatabaseRestorePage} name the
 * SITE, and {@code SiteModel}'s remove hook cascades. An owner-kind column would make
 * every one of them branch -- two code paths wearing one name. (2) The link keys the SITE
 * on purpose because a gated release mints a NEW instance row, so the link must outlive
 * instances; an instance link keys the instance itself. Those are different lifetimes,
 * not one lifetime with a flag. (3) The link network handle is {@code dblink-<owner>-<db>}:
 * one numeric pair namespace cannot serve two owner kinds without colliding (site 5 + db 3
 * versus instance 5 + db 3), and re-scheming it would orphan every network already at a
 * daemon. What IS shared is the DERIVATION -- {@code DatabaseEnvInjection.vars} and
 * {@code connectionUrl} are owner-agnostic and both lanes call them.
 */
public class InstanceDatabaseModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "instance_database");
    public static final Schema SCHEMA = new Schema();

    /** Default {@link #ENV_PREFIX} when none is given; the site lane's spelling. */
    public static final String DEFAULT_PREFIX = SiteDatabaseModel.DEFAULT_PREFIX;

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("instance_id").build());
    public static final IntegerField DATABASE_ID = SCHEMA.addField(
        IntegerField.builder().name("database_id").build());
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
