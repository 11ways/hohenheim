package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * A git hosting provider installation (github.com, GitHub Enterprise, GitLab, ...):
 * the admin-owned credential record sites bind to for repository/branch selection,
 * authenticated clones, short-lived build credentials and deployment status reporting.
 *
 * Credentials are STATIC {@code .secret().encrypted()} columns -- never keys inside a
 * JSON settings map, which zenit refuses to encrypt (the InstanceVariableModel lesson).
 * When the three GitHub App columns are set, per-operation INSTALLATION TOKENS are
 * minted (about one hour of upstream validity) and the stored access token is only the
 * fallback; that minted token is what plugs into {@code BuildCredentials.issue}.
 */
public class GitProviderModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "git_provider");
    public static final Schema SCHEMA = new Schema();

    /** {@link #KIND} of a GitHub-compatible provider (github.com or GHE). */
    public static final String KIND_GITHUB = "github";

    /** {@link #KIND} of a GitLab-compatible provider; declared, not yet implemented. */
    public static final String KIND_GITLAB = "gitlab";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    /** Never localized: a provider name is operator data. */
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .required()
        .label(HohenheimFormCopy.label("provider_name"))
        .build());

    public static final EnumField KIND = SCHEMA.addField(EnumField.builder("kind")
        .value(KIND_GITHUB, v -> v.displayName("GitHub").icon("github").color("info"))
        .value(KIND_GITLAB, v -> v.displayName("GitLab").icon("gitlab").color("warning"))
        .defaultValue(KIND_GITHUB)
        .label(HohenheimFormCopy.label("provider_kind"))
        .help(HohenheimFormCopy.help("provider_kind"))
        .build());

    /** Blank = the public host of the kind (https://github.com); set for self-hosted. */
    public static final StringField BASE_URL = SCHEMA.addField(StringField.builder().name("base_url")
        .label(HohenheimFormCopy.label("provider_base_url"))
        .help(HohenheimFormCopy.help("provider_base_url"))
        .build());

    /** Personal/deploy access token; the fallback credential when no App is configured. */
    public static final StringField ACCESS_TOKEN = SCHEMA.addField(
        StringField.builder().name("access_token").secret().encrypted()
            .label(HohenheimFormCopy.label("provider_access_token"))
            .help(HohenheimFormCopy.help("provider_access_token"))
            .build());

    /** GitHub App id; with {@link #APP_INSTALLATION_ID} and the key, tokens are MINTED. */
    public static final StringField APP_ID = SCHEMA.addField(StringField.builder().name("app_id")
        .label(HohenheimFormCopy.label("provider_app_id"))
        .help(HohenheimFormCopy.help("provider_app_id"))
        .build());

    public static final StringField APP_INSTALLATION_ID = SCHEMA.addField(
        StringField.builder().name("app_installation_id")
            .label(HohenheimFormCopy.label("provider_app_installation_id"))
            .help(HohenheimFormCopy.help("provider_app_installation_id"))
            .build());

    /** The App's RS256 private key (PEM), encrypted at rest. */
    public static final TextField APP_PRIVATE_KEY_PEM = SCHEMA.addField(
        TextField.builder().name("app_private_key_pem").secret().encrypted()
            .label(HohenheimFormCopy.label("provider_app_private_key"))
            .help(HohenheimFormCopy.help("provider_app_private_key"))
            .build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        SCHEMA.setDisplayFields(NAME);
    }

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "GitProvider"; }
    @Override public String getTableName() { return "git_providers"; }
    @Override public Schema getSchema() { return SCHEMA; }
}
