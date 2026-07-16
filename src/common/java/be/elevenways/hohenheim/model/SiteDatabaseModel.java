package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

/**
 * Attaches a managed database to a site: the site's processes receive the database's
 * connection details as environment variables derived at spawn time (never stored).
 * {@code env_prefix} names the variable family ({@code DB} gives {@code DB_HOST}, ...);
 * the site's oldest link is the primary one and additionally emits {@code DATABASE_URL}.
 */
public class SiteDatabaseModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "site_database");
    public static final Schema SCHEMA = new Schema();

    /** Default {@link #ENV_PREFIX} when none is given. */
    public static final String DEFAULT_PREFIX = "DB";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField SITE_ID = SCHEMA.addField(IntegerField.builder().name("site_id").build());
    public static final IntegerField DATABASE_ID = SCHEMA.addField(IntegerField.builder().name("database_id").build());
    public static final StringField ENV_PREFIX = SCHEMA.addField(StringField.builder().name("env_prefix")
        .help(Microcopy.of("hohenheim.site_database.env_prefix_help"))
        .build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    /** All links for a site, oldest first (the first one is the primary link). */
    public List<Row> findBySiteId(Integer siteId) {
        return find()
            .where(SITE_ID.eq(siteId))
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
    public String getModelName() { return "SiteDatabase"; }

    @Override
    public String getTableName() { return "site_databases"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
