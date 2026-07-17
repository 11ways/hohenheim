package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.sitetype.SiteTypeRegistry;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.behaviour.RevisionableBehaviour;
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
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimFormCopy.label("name"))
        .build());
    public static final StringField SLUG = SCHEMA.addField(StringField.builder().name("slug").build());

    // RegistryEnumField: values come from SiteTypeRegistry at runtime
    public static final EnumField SITE_TYPE = SCHEMA.addField(
        RegistryEnumField.builder("site_type")
            .registry(SiteTypeRegistry.REGISTRY)
            .label(HohenheimFormCopy.label("site_type"))
            .help(HohenheimFormCopy.help("site_type"))
            .build());

    public static final BooleanField ENABLED = SCHEMA.addField(BooleanField.builder("enabled")
        .defaultValue(true)
        .label(HohenheimFormCopy.label("enabled"))
        .help(HohenheimFormCopy.help("enabled"))
        .build());

    // Polymorphic settings: schema resolved dynamically from site_type
    public static final SchemaField SETTINGS = SCHEMA.addField(
        SchemaField.builder("settings")
            .schemaFrom("site_type")
            .label(HohenheimFormCopy.label("settings"))
            .build());

    // Source provisioning: null/"local" = local files, "git" = git-provisioned
    public static final EnumField SOURCE = SCHEMA.addField(
        EnumField.builder("source")
            .value("local", value -> value.displayName("Local files")
                .label(Microcopy.of("local_files").withFilter("scope", "site_source"))
                .icon("folder"))
            .value(SOURCE_GIT, value -> value.displayName("Git repository")
                .label(Microcopy.of("git_repository").withFilter("scope", "site_source"))
                .icon("code-branch")
                .schema(GitSourceSchema.SCHEMA))
            .defaultValue("local")
            .label(HohenheimFormCopy.label("source"))
            .help(HohenheimFormCopy.help("source"))
            .build());

    // Git-specific settings, only relevant when source == "git"
    public static final SchemaField SOURCE_SETTINGS = SCHEMA.addField(
        SchemaField.builder("source_settings")
            .schemaFrom(SOURCE)
            .label(HohenheimFormCopy.label("source_settings"))
            .build());

    public static final StringField DESCRIPTION = SCHEMA.addField(StringField.builder().name("description")
        .label(HohenheimFormCopy.label("description"))
        .build());
    public static final EnumField STATUS = SCHEMA.addField(EnumField.builder("status")
        .value(STATUS_ACTIVE, v -> v.displayName("Active").icon("circle-check").color("success"))
        .build());
    public static final IntegerField ACCESS_LIST_ID = SCHEMA.addField(IntegerField.builder().name("access_list_id")
        .label(HohenheimFormCopy.label("access_list"))
        .help(HohenheimFormCopy.help("access_list"))
        .build());
    public static final IntegerField AUTH_PROVIDER_ID = SCHEMA.addField(IntegerField.builder().name("auth_provider_id")
        .label(HohenheimFormCopy.label("auth_provider"))
        .help(HohenheimFormCopy.help("auth_provider"))
        .build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());
    public static final DateTimeField DELETED_AT = SCHEMA.addField(DateTimeField.builder().name("deleted_at").build());

    // Sites are the proxy's central config records: keep full snapshots so
    // admins can diff and restore them from the CMS history tab.
    public static final RevisionableBehaviour REVISIONABLE =
        SCHEMA.addBehaviour(RevisionableBehaviour.create(50));

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
