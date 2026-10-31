package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.i18n.wording.Noun;
import be.elevenways.protoblast.common.i18n.wording.NounFact;
import be.elevenways.zenit.common.orm.behaviour.HistoryBehaviour;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.ModelRef;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.ColorHue;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;

/**
 * A delivery target for platform notifications. {@code kind} is the transport (today: only
 * {@code webhook}), {@code format} shapes the JSON body for the receiver ({@code slack},
 * {@code discord}, or {@code generic}). Slack and Discord both accept incoming-webhook URLs.
 */
public class NotificationChannelModel extends Model {

    public static final Identifier MODEL_ID = HohenheimIds.id("notification_channel");
    /** The notification channel model's reference: its class and MODEL_ID, resolved at first use. */
    public static final ModelRef<NotificationChannelModel> REF = ModelRef.of(NotificationChannelModel.class, MODEL_ID);

    public static final Schema SCHEMA = new Schema();

    /** {@link #KIND} value for HTTP webhook delivery (the only transport today). */
    public static final String KIND_WEBHOOK = "webhook";

    /** {@link #FORMAT} values shaping the JSON body for the receiver. */
    public static final String FORMAT_SLACK = "slack";
    public static final String FORMAT_DISCORD = "discord";
    public static final String FORMAT_GENERIC = "generic";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name").required()
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("name")).build());
    public static final StringField KIND = SCHEMA.addField(StringField.builder().name("kind").build());
    public static final EnumField FORMAT = SCHEMA.addField(EnumField.builder("format")
        .required()
        .value(FORMAT_SLACK, v -> v.displayName("Slack")
            .label(HohenheimMicrocopy.NOTIFICATION_FORMAT.of(FORMAT_SLACK)).icon("message").color(ColorHue.PURPLE))
        .value(FORMAT_DISCORD, v -> v.displayName("Discord")
            .label(HohenheimMicrocopy.NOTIFICATION_FORMAT.of(FORMAT_DISCORD)).icon("comments").color(ColorHue.INDIGO))
        .value(FORMAT_GENERIC, v -> v.displayName("Generic JSON")
            .label(HohenheimMicrocopy.NOTIFICATION_FORMAT.of(FORMAT_GENERIC)).icon("code").color(ColorHue.GRAY))
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("format"))
        .build());
    // The webhook URL is a bearer capability (Slack/Discord embed the token in the path).
    public static final StringField URL = SCHEMA.addField(
        StringField.builder().name("url").secret().encrypted().required()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("url")).build());
    // Subscribed event tokens; empty/null = receive every event, which the help text says
    // out loud -- an empty picker otherwise reads as "subscribed to nothing".
    public static final ListField<String> EVENTS = SCHEMA.addField(
        ListField.<String>builder(StringField.builder().name("event").build()).name("events")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("events"))
            .help(HohenheimMicrocopy.HELP.of("notification_events")).build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("created_at")).build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());


    private static final Noun NOUN = new Noun(Map.of(
        "en", new Noun.Forms("notification channel", "notification channels", Map.of(NounFact.ARTICLE, "a")),
        "nl", new Noun.Forms("meldingskanaal", "meldingskanalen", Map.of(NounFact.GENDER, "neuter"))));

    @Override public @NonNull Noun noun() { return NOUN; }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "NotificationChannel"; }

    @Override
    public String getTableName() { return "notification_channels"; }

    @Override
    public Schema getSchema() { return SCHEMA; }

    public static final HistoryBehaviour HISTORY = SCHEMA.addBehaviour(HistoryBehaviour.optional().changes());
}
