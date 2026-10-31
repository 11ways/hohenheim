package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.i18n.wording.Noun;
import be.elevenways.protoblast.common.i18n.wording.NounFact;
import be.elevenways.zenit.common.orm.behaviour.HistoryBehaviour;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Map;

/**
 * The webhook replay ledger: one row per ACCEPTED (signature-verified) delivery, keyed
 * by the provider's delivery id per site. Insert-first is the idempotency claim -- the
 * UNIQUE (instance_id, delivery_key) index makes a replayed delivery fail its insert, so a
 * provider retry can never enqueue a second deploy.
 */
public class WebhookDeliveryModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("webhook_delivery");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField INSTANCE_ID = SCHEMA.addField(IntegerField.builder().name("instance_id").build());

    /** Provider delivery id header when present, else sha256 of the raw body. */
    public static final StringField DELIVERY_KEY = SCHEMA.addField(
        StringField.builder().name("delivery_key").filterable(false).build());

    /** The provider event name (push, pull_request, ...); diagnostics only. */
    public static final StringField EVENT = SCHEMA.addField(
        StringField.builder().name("event").filterable(false).build());

    /** What the delivery caused (deployed, preview, ignored, ...); diagnostics only. */
    public static final StringField ACTION = SCHEMA.addField(
        StringField.builder().name("action").filterable(false).build());

    public static final DateTimeField RECEIVED_AT = SCHEMA.addField(
        DateTimeField.builder().name("received_at").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    /** One application's newest deliveries, newest first. */
    public List<Row> findRecent(int instanceId, int limit) {
        return find()
            .where(INSTANCE_ID.eq(instanceId))
            .orderBy(RECEIVED_AT, SortOrder.DESC)
            .orderBy(ID, SortOrder.DESC)
            .limit(limit)
            .all();
    }

    private static final Noun NOUN = new Noun(Map.of(
        "en", new Noun.Forms("webhook delivery", "webhook deliveries", Map.of(NounFact.ARTICLE, "a")),
        "nl", new Noun.Forms("webhookbezorging", "webhookbezorgingen", Map.of(NounFact.GENDER, "common"))));

    @Override public @NonNull Noun noun() { return NOUN; }

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "WebhookDelivery"; }
    @Override public String getTableName() { return "webhook_deliveries"; }
    @Override public Schema getSchema() { return SCHEMA; }
    @Override public List<Field<?, ?>> getChangeKeyFields() { return List.of(INSTANCE_ID); }

    /** Plumbing: recorded, kept out of people's feeds. */
    public static final HistoryBehaviour HISTORY =
        SCHEMA.addBehaviour(HistoryBehaviour.optional().changes().internal());
}
