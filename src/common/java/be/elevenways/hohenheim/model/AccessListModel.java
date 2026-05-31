package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * IP allow/deny rules with optional basic auth.
 */
public class AccessListModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "access_list");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name").build());
    public static final StringField SATISFY = SCHEMA.addField(StringField.builder().name("satisfy").build());
    public static final StringField BASIC_AUTH_USER = SCHEMA.addField(StringField.builder().name("basic_auth_user").build());
    public static final StringField BASIC_AUTH_PASS = SCHEMA.addField(StringField.builder().name("basic_auth_pass").build());
    public static final StringField ALLOWED_IPS = SCHEMA.addField(StringField.builder().name("allowed_ips").build());
    public static final StringField DENIED_IPS = SCHEMA.addField(StringField.builder().name("denied_ips").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());


    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "AccessList"; }

    @Override
    public String getTableName() { return "access_lists"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
