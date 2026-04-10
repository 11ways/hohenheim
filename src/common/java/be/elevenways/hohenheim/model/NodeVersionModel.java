package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

/**
 * Installed Node.js binary discovered by UpdateNodeVersions — referenced by
 * NodeSiteType.NODE_VERSION_ID to pin a site to a specific runtime. Stale entries
 * are kept with obsolete=true so existing site references don't silently break
 * when a version is uninstalled from the host.
 */
public class NodeVersionModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "node_version");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField VERSION = SCHEMA.addField(StringField.builder().name("version").build());
    public static final StringField PATH = SCHEMA.addField(StringField.builder().name("path").build());
    public static final StringField SOURCE = SCHEMA.addField(StringField.builder().name("source").build());
    public static final BooleanField OBSOLETE = SCHEMA.addField(BooleanField.builder("obsolete").defaultValue(false).build());
    public static final DateTimeField LAST_SEEN_AT = SCHEMA.addField(DateTimeField.builder().name("last_seen_at").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    private final Datasource datasource;

    public NodeVersionModel(Datasource datasource) {
        this.datasource = datasource;
    }

    /**
     * All non-obsolete node versions, newest first (lexicographic on version),
     * for the admin-UI dropdown.
     */
    public List<Row> findActive() {
        return find()
            .where(OBSOLETE.eq(false))
            .orderBy(VERSION, SortOrder.DESC)
            .all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "NodeVersion"; }

    @Override
    public String getTableName() { return "node_versions"; }

    @Override
    public Schema getSchema() { return SCHEMA; }

    @Override
    protected Datasource getDatasource() { return this.datasource; }
}
