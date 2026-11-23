package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.i18n.wording.Noun;
import be.elevenways.protoblast.common.i18n.wording.NounFact;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.behaviour.HistoryBehaviour;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.ModelRef;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/**
 * What the outage watch of an app's site remembers between its sweeps (SiteOutages): one row per site it saw answering.
 *
 * AIDEV-NOTE: its own table, never columns on the site: a whole-row site save would write stale values back over them
 * (a second alert), and a tenant's site save would be refused for writing columns frozen for tenants. No row means the
 * site was never seen answering, which never alerts; the watch deletes the row of a site it no longer reads, and a
 * site's hard delete takes its row with it (SiteModel's remove cascade).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class SiteOutageModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("site_outage");
    /** The site outage model's reference: its class and MODEL_ID, resolved at first use. */
    public static final ModelRef<SiteOutageModel> REF = ModelRef.of(SiteOutageModel.class, MODEL_ID);

    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    /** The watched site; unique, so a second sweeper's first reading inserts nothing twice. */
    public static final IntegerField SITE_ID = SCHEMA.addField(IntegerField.builder().name("site_id").build());

    /**
     * When the watch first read the site's app answering.
     *
     * AIDEV-NOTE: inside an alerted outage it moves to the first answer after {@link #DOWN_SINCE}, and back to that
     * value when the next reading is down again: "two sweeps in a row" is an answer at or after it.
     */
    public static final DateTimeField UP_SEEN_AT = SCHEMA.addField(DateTimeField.builder().name("up_seen_at").build());

    /** When the watch first read the app down after it had answered; null while it answers. */
    public static final DateTimeField DOWN_SINCE = SCHEMA.addField(DateTimeField.builder().name("down_since").build());

    /** When the current outage's "stopped answering" alert went out; null while none did, the once-only latch. */
    public static final DateTimeField DOWN_ALERTED_AT = SCHEMA.addField(
        DateTimeField.builder().name("down_alerted_at").build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    public static final BelongsTo<SiteModel> SITE = SCHEMA.addRelation(
        BelongsTo.to(SiteModel.class)
            .name("site")
            .localKey(SITE_ID)
            .remoteKey(SiteModel.ID)
            .build());

    private static final Noun NOUN = new Noun(Map.of(
        "en", new Noun.Forms("site outage", "site outages", Map.of(NounFact.ARTICLE, "a")),
        "nl", new Noun.Forms("site-onderbreking", "site-onderbrekingen", Map.of(NounFact.GENDER, "common"))));

    @Override public @NonNull Noun noun() { return NOUN; }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "SiteOutage"; }

    @Override
    public String getTableName() { return "site_outages"; }

    @Override
    public Schema getSchema() { return SCHEMA; }

    /** The watch's own bookkeeping, rewritten by its sweeps: never recorded, and never a change of the site. */
    public static final HistoryBehaviour HISTORY = SCHEMA.addBehaviour(HistoryBehaviour.untracked());
}
