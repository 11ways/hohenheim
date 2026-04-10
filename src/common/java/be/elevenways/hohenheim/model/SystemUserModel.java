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
 * System user discovered from /etc/passwd — referenced by NodeSiteType to run
 * child processes as a specific uid/gid. Stale entries are kept with obsolete=true
 * so that existing site references don't silently break when a user is removed.
 */
public class SystemUserModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "system_user");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name").build());
    public static final IntegerField UID = SCHEMA.addField(IntegerField.builder().name("uid").build());
    public static final IntegerField GID = SCHEMA.addField(IntegerField.builder().name("gid").build());
    public static final StringField HOME = SCHEMA.addField(StringField.builder().name("home").build());
    public static final StringField GECOS = SCHEMA.addField(StringField.builder().name("gecos").build());
    public static final BooleanField OBSOLETE = SCHEMA.addField(BooleanField.builder("obsolete").defaultValue(false).build());
    public static final DateTimeField LAST_SEEN_AT = SCHEMA.addField(DateTimeField.builder().name("last_seen_at").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    private final Datasource datasource;

    public SystemUserModel(Datasource datasource) {
        this.datasource = datasource;
    }

    /**
     * All non-obsolete users, alphabetically by name — used to populate the
     * admin-UI dropdown on the Node site edit form.
     */
    public List<Row> findActive() {
        return find()
            .where(OBSOLETE.eq(false))
            .orderBy(NAME, SortOrder.ASC)
            .all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "SystemUser"; }

    @Override
    public String getTableName() { return "system_users"; }

    @Override
    public Schema getSchema() { return SCHEMA; }

    @Override
    protected Datasource getDatasource() { return this.datasource; }
}
