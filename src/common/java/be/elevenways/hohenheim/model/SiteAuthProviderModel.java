package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.i18n.wording.Noun;
import be.elevenways.protoblast.common.i18n.wording.NounFact;
import be.elevenways.zenit.common.orm.behaviour.HistoryBehaviour;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.auth.SiteAuthProviderTypeRegistry;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.ModelRef;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Map;

/**
 * A reusable per-site auth-provider configuration (Proteus realm, Basic credentials, ...). Sites
 * reference one by id via SiteModel.AUTH_PROVIDER_ID; the proxy builds a gate from it at route-load.
 */
public class SiteAuthProviderModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("site_auth_provider");
    /** The site auth provider model's reference: its class and MODEL_ID, resolved at first use. */
    public static final ModelRef<SiteAuthProviderModel> REF = ModelRef.of(SiteAuthProviderModel.class, MODEL_ID);

    public static final Schema SCHEMA = new Schema();

    /** PermissionSuggestionSources key: the assigned Proteus realm's vocabulary (registered server-side). */
    public static final Identifier PROTEUS_SUGGESTION_SOURCE = HohenheimIds.id("proteus_realm");

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("auth_provider_name"))
        .help(HohenheimMicrocopy.HELP.of("auth_provider_name")).build());

    // RegistryMemberField: values come from SiteAuthProviderTypeRegistry at runtime.
    public static final EnumField PROVIDER_TYPE = SCHEMA.addField(
        RegistryMemberField.builder("provider_type")
            .registry(SiteAuthProviderTypeRegistry.REGISTRY)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("auth_provider_type"))
            .help(HohenheimMicrocopy.HELP.of("auth_provider_type"))
            .build());

    // Polymorphic config: schema resolved dynamically from provider_type.
    public static final SchemaField CONFIG = SCHEMA.addField(
        SchemaField.builder("config")
            .schemaFrom("provider_type")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("auth_provider_config"))
            .help(HohenheimMicrocopy.HELP.of("auth_provider_config"))
            .build());

    // Provider-agnostic required permission for claims-based providers (null = hohenheim.site.<slug> of the gated
    // site). PermissionField: edits with the declared permissions as autocomplete, plus
    // the assigned Proteus realm's fetched vocabulary on top.
    public static final StringField REQUIRED_PERMISSION = SCHEMA.addField(
        PermissionField.builder("required_permission")
            .suggestionSource(PROTEUS_SUGGESTION_SOURCE)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("required_permission"))
            .help(HohenheimMicrocopy.HELP.of("required_permission")).build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("created_at")).build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    public List<Row> findAllOrdered() {
        return find().orderBy(NAME, SortOrder.ASC).all();
    }

    private static final Noun NOUN = new Noun(Map.of(
        "en", new Noun.Forms("site login provider", "site login providers", Map.of(NounFact.ARTICLE, "a")),
        "nl", new Noun.Forms("site-inlogaanbieder", "site-inlogaanbieders", Map.of(NounFact.GENDER, "common"))));

    @Override public @NonNull Noun noun() { return NOUN; }

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

    public static final HistoryBehaviour HISTORY = SCHEMA.addBehaviour(HistoryBehaviour.optional().changes());
}
