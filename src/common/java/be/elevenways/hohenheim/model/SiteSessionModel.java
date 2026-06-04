package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * Persisted proxy-auth session row. Backs the optional DbSessionStore drop-in; the default proxy
 * session store is in-memory, so this table is empty until a DB-backed store is wired.
 */
public class SiteSessionModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "site_session");
    public static final Schema SCHEMA = new Schema();

    public static final StringField ID = SCHEMA.addField(StringField.builder().name("id").build());
    public static final IntegerField SITE_ID = SCHEMA.addField(IntegerField.builder().name("site_id").build());
    public static final SchemaField DATA = SCHEMA.addField(SchemaField.builder("data").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField EXPIRES_AT = SCHEMA.addField(DateTimeField.builder().name("expires_at").build());

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "SiteSession"; }

    @Override
    public String getTableName() { return "site_sessions"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
