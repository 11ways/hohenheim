package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

public class AuditLogModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "audit_log");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField USER_ID = SCHEMA.addField(IntegerField.builder().name("user_id").build());
    public static final StringField USER_LABEL = SCHEMA.addField(StringField.builder().name("user_label").build());
    public static final StringField ACTION = SCHEMA.addField(StringField.builder().name("action").build());
    public static final StringField RESOURCE_TYPE = SCHEMA.addField(StringField.builder().name("resource_type").build());
    public static final StringField RESOURCE_ID = SCHEMA.addField(StringField.builder().name("resource_id").build());
    public static final StringField RESOURCE_NAME = SCHEMA.addField(StringField.builder().name("resource_name").build());
    public static final SchemaField METADATA = SCHEMA.addField(SchemaField.builder("metadata").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());


    public List<Row> findRecent(int limit) {
        return find()
            .orderBy(CREATED_AT, SortOrder.DESC)
            .limit(limit)
            .all();
    }

    public List<Row> findByResource(String resourceType, String resourceId) {
        return find()
            .where(RESOURCE_TYPE.eq(resourceType))
            .where(RESOURCE_ID.eq(resourceId))
            .orderBy(CREATED_AT, SortOrder.DESC)
            .all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "AuditLog"; }

    @Override
    public String getTableName() { return "audit_log"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
