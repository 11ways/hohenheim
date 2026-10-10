package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.source.GitProviderKindRegistry;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * A git hosting provider installation (github.com, GitHub Enterprise, GitLab, ...):
 * the credential record sites bind to for repository/branch selection, authenticated
 * clones, short-lived build credentials and deployment status reporting. Ownership is
 * the record's {@code manage} grant subjects (the sites/instances doctrine, no owner
 * FK); {@link #SHARED} is what widens one row past its owners.
 *
 * Credentials are STATIC {@code .secret().encrypted()} columns -- never keys inside a
 * JSON settings map, which zenit refuses to encrypt (the InstanceVariableModel lesson).
 * When the GitHub App id, installation id ({@link #SETTINGS}) and private key are set,
 * per-operation INSTALLATION TOKENS are minted (about one hour of upstream validity) and
 * the stored access token is only the fallback; that minted token is what plugs into
 * {@code BuildCredentials.issue}.
 */
public class GitProviderModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("git_provider");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    /** Never localized: a provider name is operator data. */
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .required()
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("provider_name"))
        .build());

    // ONE discriminator over the kind registry: values enumerate it live, so a new kind is
    // one GitProviderKind class and no edit here. Stored value = "hohenheim:<kind>".
    public static final EnumField KIND = SCHEMA.addField(
        RegistryMemberField.builder("kind")
            .registry(GitProviderKindRegistry.REGISTRY)
            // Required on the FIELD, not on one form: the kind selects the auth scheme and
            // the base-url policy, so every writer (admin form, /manage projection, API)
            // must refuse a provider without one -- a UI-only rule would let the other
            // writers store a row no client can ever be built for.
            .required()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("provider_kind"))
            .help(HohenheimMicrocopy.HELP.of("provider_kind"))
            .build());

    /**
     * Per-kind NON-SECRET configuration (the GitHub App identifiers today).
     *
     * AIDEV-NOTE: every credential stays a COLUMN below and none of them may move here --
     * a field under a JSON SchemaField cannot be encrypted at all
     * ({@code Schema.refuseEncryptedJsonSubFields}), so a token stored here would be
     * plaintext. The dynamic (schemaFrom) form entry also REWRITES this whole map on
     * every admin save, so nothing that must survive a save of another kind belongs here.
     */
    public static final SchemaField SETTINGS = SCHEMA.addField(
        SchemaField.builder("settings")
            .schemaFrom("kind")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("provider_settings"))
            .build());

    /** Blank = the public host of the kind (https://github.com); set for self-hosted. */
    public static final StringField BASE_URL = SCHEMA.addField(StringField.builder().name("base_url")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("provider_base_url"))
        .help(HohenheimMicrocopy.HELP.of("provider_base_url"))
        .build());

    /**
     * Whether {@link #BASE_URL} was last set by the system tier ({@code hohenheim.admin.system} or declared system
     * work), the one fact that lets an operator-owned provider's API calls reach any address; never written by a form,
     * only by OperatorTrustedWrites' write hook.
     *
     * AIDEV-NOTE: reach is decided by WHO SET the target, not only by ownership at fetch time (decided 2026-10-02):
     * ownership changes where no write hook sees it (a revoked grant, a deleted tenant, a cascade), so a target a
     * tenant or delegate set stays unmarked and is never dialled with any-address reach after the record becomes
     * operator-owned. M011 marked every row stored before the rule.
     */
    public static final BooleanField TARGET_TRUSTED = SCHEMA.addField(BooleanField.builder("target_trusted")
        .defaultValue(false)
        .build());

    /** Personal/deploy access token; the fallback credential when no App is configured. */
    public static final StringField ACCESS_TOKEN = SCHEMA.addField(
        StringField.builder().name("access_token").secret().encrypted()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("provider_access_token"))
            .help(HohenheimMicrocopy.HELP.of("provider_access_token"))
            .build());

    /**
     * Offered to EVERY tenant's pickers, not only to the principals holding a manage
     * grant on the row: the operator's declaration that this installation's credential
     * may be used by anyone who can pick a provider at all. Default false, so a provider
     * a tenant creates in /manage is private to its owners until an admin says otherwise.
     */
    public static final BooleanField SHARED = SCHEMA.addField(BooleanField.builder("shared")
        .defaultValue(false)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("provider_shared"))
        .help(HohenheimMicrocopy.HELP.of("provider_shared"))
        .build());

    /** The App's RS256 private key (PEM), encrypted at rest. */
    public static final TextField APP_PRIVATE_KEY_PEM = SCHEMA.addField(
        TextField.builder().name("app_private_key_pem").secret().encrypted()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("provider_app_private_key"))
            .help(HohenheimMicrocopy.HELP.of("provider_app_private_key"))
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
