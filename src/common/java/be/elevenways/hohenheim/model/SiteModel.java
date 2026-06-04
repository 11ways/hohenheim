package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.sitetype.SiteTypeRegistry;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

public class SiteModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "site");
    public static final Schema SCHEMA = new Schema();

    /** {@link #SOURCE} value for git-provisioned sites. */
    public static final String SOURCE_GIT = "git";

    /** {@link #STATUS} value for an active site. */
    public static final String STATUS_ACTIVE = "active";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name").build());
    public static final StringField SLUG = SCHEMA.addField(StringField.builder().name("slug").build());

    // RegistryEnumField: values come from SiteTypeRegistry at runtime
    public static final EnumField SITE_TYPE = SCHEMA.addField(
        RegistryEnumField.builder("site_type")
            .registry(SiteTypeRegistry.REGISTRY)
            .build());

    public static final BooleanField ENABLED = SCHEMA.addField(BooleanField.builder("enabled").defaultValue(true).build());

    // Polymorphic settings: schema resolved dynamically from site_type
    public static final SchemaField SETTINGS = SCHEMA.addField(
        SchemaField.builder("settings")
            .schemaFrom("site_type")
            .build());

    // Source provisioning: null/"local" = local files, "git" = git-provisioned
    public static final StringField SOURCE = SCHEMA.addField(
        StringField.builder().name("source").build());

    // Git-specific settings, only relevant when source == "git"
    public static final SchemaField SOURCE_SETTINGS = SCHEMA.addField(
        SchemaField.builder("source_settings")
            .subSchema(GitSourceSchema.SCHEMA)
            .build());

    public static final StringField DESCRIPTION = SCHEMA.addField(StringField.builder().name("description").build());
    public static final StringField STATUS = SCHEMA.addField(StringField.builder().name("status").build());
    public static final IntegerField ACCESS_LIST_ID = SCHEMA.addField(IntegerField.builder().name("access_list_id").build());
    public static final IntegerField AUTH_PROVIDER_ID = SCHEMA.addField(IntegerField.builder().name("auth_provider_id").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());
    public static final DateTimeField DELETED_AT = SCHEMA.addField(DateTimeField.builder().name("deleted_at").build());


    public List<Row> findEnabled() {
        return find()
            .where(ENABLED.eq(true))
            .where(DELETED_AT.isNull())
            .orderBy(NAME, SortOrder.ASC)
            .all();
    }

    public List<Row> findActive() {
        return find()
            .where(DELETED_AT.isNull())
            .orderBy(CREATED_AT, SortOrder.DESC)
            .all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "Site"; }

    @Override
    public String getTableName() { return "sites"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
