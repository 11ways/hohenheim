package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.auth.SiteAuthProviderTypeRegistry;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

/**
 * A reusable per-site auth-provider configuration (Proteus realm, Basic credentials, ...). Sites
 * reference one by id via SiteModel.AUTH_PROVIDER_ID; the proxy builds a gate from it at route-load.
 */
public class SiteAuthProviderModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "site_auth_provider");
    public static final Schema SCHEMA = new Schema();

    /** PermissionSuggestionSources key: the assigned Proteus realm's vocabulary (registered server-side). */
    public static final String PROTEUS_SUGGESTION_SOURCE = "hohenheim:proteus_realm";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimFormCopy.label("auth_provider_name"))
        .help(HohenheimFormCopy.help("auth_provider_name")).build());

    // RegistryEnumField: values come from SiteAuthProviderTypeRegistry at runtime.
    public static final EnumField PROVIDER_TYPE = SCHEMA.addField(
        RegistryEnumField.builder("provider_type")
            .registry(SiteAuthProviderTypeRegistry.REGISTRY)
            .label(HohenheimFormCopy.label("auth_provider_type"))
            .help(HohenheimFormCopy.help("auth_provider_type"))
            .build());

    // Polymorphic config: schema resolved dynamically from provider_type.
    public static final SchemaField CONFIG = SCHEMA.addField(
        SchemaField.builder("config")
            .schemaFrom("provider_type")
            .label(HohenheimFormCopy.label("auth_provider_config"))
            .help(HohenheimFormCopy.help("auth_provider_config"))
            .build());

    // Provider-agnostic required permission for claims-based providers (null = any identity).
    // PermissionField: edits with the KnownPermissions vocabulary as autocomplete, plus
    // the assigned Proteus realm's fetched vocabulary on top.
    public static final StringField REQUIRED_PERMISSION = SCHEMA.addField(
        PermissionField.builder("required_permission")
            .suggestionSource(PROTEUS_SUGGESTION_SOURCE)
            .label(HohenheimFormCopy.label("required_permission"))
            .help(HohenheimFormCopy.help("required_permission")).build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    public List<Row> findAllOrdered() {
        return find().orderBy(NAME, SortOrder.ASC).all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "SiteAuthProvider"; }

    @Override
    public String getTableName() { return "site_auth_providers"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
