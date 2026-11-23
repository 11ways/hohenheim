package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.cluster.LinkFailure;
import be.elevenways.hohenheim.cluster.LinkRole;
import be.elevenways.protoblast.common.i18n.wording.Noun;
import be.elevenways.protoblast.common.i18n.wording.NounFact;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.behaviour.HistoryBehaviour;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.field.UuidField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.ModelRef;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/**
 * Another complete Hohenheim this server is linked to: one this server manages, or the one master that manages it.
 *
 * AIDEV-NOTE: each side stores the key it PRESENTS ({@link #LINK_KEY}, encrypted) and only the keyed digest of the
 * key it ACCEPTS ({@link #LINK_KEY_DIGEST}); a key proves which server asks and carries no authority
 * (HohenheimPrincipalKind.LINKED_SERVER). The state fields are the probe's own bookkeeping, written by narrow
 * statements, never recorded. A {@code managed_by} row grants nothing by existing: only the refusals of
 * LinkedServers.whyNoMaster and whyCannotManage read it.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class LinkedServerModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("linked_server");
    /** The linked server model's reference: its class and MODEL_ID, resolved at first use. */
    public static final ModelRef<LinkedServerModel> REF = ModelRef.of(LinkedServerModel.class, MODEL_ID);

    public static final Schema SCHEMA = new Schema();

    /** A UUID v7, assigned by the writer. */
    public static final UuidField ID = SCHEMA.addField(UuidField.builder("id").build());

    /** What a person calls that server; unique on this server ignoring case. */
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_name")).build());

    public static final EnumField ROLE = SCHEMA.addField(roleField());

    /** Where that server answers: an https base address. */
    public static final StringField BASE_URL = SCHEMA.addField(StringField.builder().name("base_url")
        .label(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_address")).build());

    /** The key this server presents to that server. */
    public static final StringField LINK_KEY = SCHEMA.addField(StringField.builder().name("link_key").secret()
        .encrypted().filterable(false).build());

    /** The keyed digest of the key that server presents here. */
    public static final StringField LINK_KEY_DIGEST = SCHEMA.addField(StringField.builder().name("link_key_digest")
        .secret().filterable(false).build());

    /** That server's controller identity, pinned when it joined. */
    public static final StringField REMOTE_TOKEN = SCHEMA.addField(StringField.builder().name("remote_token")
        .filterable(false).build());

    /** The version that server answered last. */
    public static final StringField BUILD = SCHEMA.addField(StringField.builder().name("build")
        .label(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_version")).build());

    /** When that server last answered a status call; this server's own row of the Servers list reads "-". */
    public static final DateTimeField LAST_SEEN_AT = SCHEMA.addField(DateTimeField.builder().name("last_seen_at")
        .label(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_last_answer"))
        .absent(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_last_answer_none")).build());

    /** Since when every call failed; null while it answers. */
    public static final DateTimeField FAILING_SINCE = SCHEMA.addField(DateTimeField.builder().name("failing_since")
        .build());

    /** Why the last call failed; null while it answers. */
    public static final EnumField LAST_FAILURE = SCHEMA.addField(failureField());

    /** When this run of failures was alerted; null while it answers and inside the grace before the alert. */
    public static final DateTimeField ALERTED_AT = SCHEMA.addField(DateTimeField.builder().name("alerted_at").build());

    /** How many apps that server's own dashboard counts, as its last status answer said; kept while it is silent. */
    public static final IntegerField APPS = SCHEMA.addField(IntegerField.builder().name("apps")
        .label(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_apps")).build());

    /** How many sites that server holds, as its last status answer said; kept while it is silent. */
    public static final IntegerField SITES = SCHEMA.addField(IntegerField.builder().name("sites")
        .label(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_sites")).build());

    /** Since when the record of that server is in step; null before its first sync and once a kind went back. */
    public static final DateTimeField IN_STEP_SINCE = SCHEMA.addField(DateTimeField.builder().name("in_step_since")
        .build());

    /** The kinds whose generation went back on that server (its database was restored), model ids joined by commas. */
    public static final TextField WENT_BACK = SCHEMA.addField(TextField.builder().name("went_back").build());

    /** When a kind first went back: the one restored alert's mark; null while none did. */
    public static final DateTimeField RESTORED_AT = SCHEMA.addField(DateTimeField.builder().name("restored_at")
        .build());

    /** The kinds one side shares and the other does not (the two builds differ), model ids joined by commas. */
    public static final TextField SKIPPED = SCHEMA.addField(TextField.builder().name("skipped").build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    /** The probe rewrites the state every minute: never recorded. */
    public static final HistoryBehaviour HISTORY = SCHEMA.addBehaviour(HistoryBehaviour.untracked());

    static {
        SCHEMA.setDisplayFields(NAME);
    }

    private static final Noun NOUN = new Noun(Map.of(
        "en", new Noun.Forms("linked server", "linked servers", Map.of(NounFact.ARTICLE, "a")),
        "nl", new Noun.Forms("gekoppelde server", "gekoppelde servers", Map.of(NounFact.GENDER, "common"))));

    private static @NonNull EnumField roleField() {
        EnumField.Builder builder = EnumField.builder("role");
        for (LinkRole role : LinkRole.values()) {
            builder.value(role.token(), spec -> spec.label(role.label()));
        }
        return builder.label(HohenheimMicrocopy.LINKED_SERVER.of("linked_server_role")).build();
    }

    private static @NonNull EnumField failureField() {
        EnumField.Builder builder = EnumField.builder("last_failure");
        for (LinkFailure failure : LinkFailure.values()) {
            builder.value(failure.token(), spec -> spec.label(failure.label()));
        }
        return builder.build();
    }

    @Override public @NonNull Noun noun() { return NOUN; }
    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public UuidField getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "LinkedServer"; }
    @Override public String getTableName() { return "linked_servers"; }
    @Override public Schema getSchema() { return SCHEMA; }
    // A credential lookup, never served from a query cache.
    @Override public Long getQueryCacheDuration() { return 0L; }
}
