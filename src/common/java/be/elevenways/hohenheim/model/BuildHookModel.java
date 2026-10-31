package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.wording.Noun;
import be.elevenways.protoblast.common.i18n.wording.NounFact;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.behaviour.HistoryBehaviour;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.UuidField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.ModelRef;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/**
 * A build hook: a named secret URL that deploys its application when POSTed.
 *
 * AIDEV-NOTE: only a keyed digest of the URL's token is stored ({@link #TOKEN_DIGEST}); the URL is shown once, by the
 * create or regenerate that minted it, and afterwards only {@link #URL_PREVIEW} (the path with the token's last
 * characters). A revoked hook keeps its row so the releases it started still name it: it is also the record behind
 * {@code HohenheimPrincipalKind.BUILD_HOOK}, whose title is {@link #NAME}.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class BuildHookModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("build_hook");
    /** The build hook model's reference: its class and MODEL_ID, resolved at first use. */
    public static final ModelRef<BuildHookModel> REF = ModelRef.of(BuildHookModel.class, MODEL_ID);

    public static final Schema SCHEMA = new Schema();

    public static final UuidField ID = SCHEMA.addField(UuidField.builder("id").build());

    public static final IntegerField INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("instance_id")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("instance")).build());

    /** The owner's name for what POSTs it ("Headless CMS", "Nightly"); an identifier, never localized. */
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .required()
        .label(HohenheimMicrocopy.BUILD_HOOK.of("build_hook_name"))
        .help(HohenheimMicrocopy.BUILD_HOOK.of("build_hook_name_help"))
        .build());

    /** The keyed digest of the URL's token under the active keyring key; looked up by every candidate key's digest. */
    public static final StringField TOKEN_DIGEST = SCHEMA.addField(
        StringField.builder().name("token_digest").secret().filterable(false).build());

    /** What the list shows of the URL once it was shown: its path with only the token's last characters. */
    public static final StringField URL_PREVIEW = SCHEMA.addField(
        StringField.builder().name("url_preview").filterable(false)
            .label(HohenheimMicrocopy.BUILD_HOOK.of("build_hook_url")).build());

    public static final DateTimeField REVOKED_AT = SCHEMA.addField(
        DateTimeField.builder().name("revoked_at")
            .label(HohenheimMicrocopy.BUILD_HOOK.of("build_hook_revoked_at"))
            .absent(HohenheimMicrocopy.BUILD_HOOK.of("build_hook_active")).build());

    /** When the URL last started a deploy; bookkeeping, outside the history. */
    public static final DateTimeField LAST_TRIGGERED_AT = SCHEMA.addField(
        DateTimeField.builder().name("last_triggered_at")
            .label(HohenheimMicrocopy.BUILD_HOOK.of("build_hook_last_triggered"))
            .absent(HohenheimMicrocopy.BUILD_HOOK.of("build_hook_never_triggered")).build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at")
            .label(HohenheimMicrocopy.BUILD_HOOK.of("build_hook_created_at")).build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    /** A hook's change is filed on its application; a trigger is not a change of the hook. */
    public static final HistoryBehaviour HISTORY = SCHEMA.addBehaviour(HistoryBehaviour.optional().changes()
        .except(LAST_TRIGGERED_AT)
        .filedOn(InstanceModel.class, row -> row.get(INSTANCE_ID)));

    static {
        // The owner's name is the only human-readable thing on a hook: the digest is secret, the preview a fragment.
        SCHEMA.setDisplayFields(NAME);
    }

    private static final Noun NOUN = new Noun(Map.of(
        "en", new Noun.Forms("build hook", "build hooks", Map.of(NounFact.ARTICLE, "a")),
        "nl", new Noun.Forms("buildhook", "buildhooks", Map.of(NounFact.GENDER, "common"))));

    @Override public @NonNull Noun noun() { return NOUN; }

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public UuidField getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "BuildHook"; }
    @Override public String getTableName() { return "build_hooks"; }
    @Override public Schema getSchema() { return SCHEMA; }
    // A credential lookup, never served from a query cache.
    @Override public Long getQueryCacheDuration() { return 0L; }
}
