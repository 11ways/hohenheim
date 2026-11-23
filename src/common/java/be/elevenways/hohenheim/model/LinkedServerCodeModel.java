package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.i18n.wording.Noun;
import be.elevenways.protoblast.common.i18n.wording.NounFact;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.behaviour.HistoryBehaviour;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.field.UuidField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.ModelRef;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/**
 * The join codes this server issued so another Hohenheim can manage it: core's OneTimeCodes over this table.
 *
 * AIDEV-NOTE: bookkeeping of the one-time codes, no record a person lists; only a keyed digest of a code is stored.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class LinkedServerCodeModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("linked_server_code");
    /** The join code model's reference: its class and MODEL_ID, resolved at first use. */
    public static final ModelRef<LinkedServerCodeModel> REF = ModelRef.of(LinkedServerCodeModel.class, MODEL_ID);

    public static final Schema SCHEMA = new Schema();

    public static final UuidField ID = SCHEMA.addField(UuidField.builder("id").build());

    /** The account that issued the code. */
    public static final UuidField OWNER_ID = SCHEMA.addField(UuidField.builder("owner_id").nullable(false).build());

    public static final TextField CODE_DIGEST = SCHEMA.addField(TextField.builder().name("code_digest")
        .nullable(false).build());

    public static final DateTimeField EXPIRES_AT = SCHEMA.addField(DateTimeField.builder().name("expires_at")
        .nullable(false).build());

    public static final DateTimeField USED_AT = SCHEMA.addField(DateTimeField.builder().name("used_at").build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    /** A code is bookkeeping: never recorded. */
    public static final HistoryBehaviour HISTORY = SCHEMA.addBehaviour(HistoryBehaviour.untracked());

    private static final Noun NOUN = new Noun(Map.of(
        "en", new Noun.Forms("join code", "join codes", Map.of(NounFact.ARTICLE, "a")),
        "nl", new Noun.Forms("koppelcode", "koppelcodes", Map.of(NounFact.GENDER, "common"))));

    @Override public @NonNull Noun noun() { return NOUN; }
    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public UuidField getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "LinkedServerCode"; }
    @Override public String getTableName() { return "linked_server_codes"; }
    @Override public Schema getSchema() { return SCHEMA; }
    // A credential lookup, never served from a query cache.
    @Override public Long getQueryCacheDuration() { return 0L; }
}
