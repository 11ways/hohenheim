package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * A delivery target for platform notifications. {@code kind} is the transport (today: only
 * {@code webhook}), {@code format} shapes the JSON body for the receiver ({@code slack},
 * {@code discord}, or {@code generic}). Slack and Discord both accept incoming-webhook URLs.
 */
public class NotificationChannelModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "notification_channel");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name").build());
    public static final StringField KIND = SCHEMA.addField(StringField.builder().name("kind").build());
    public static final StringField FORMAT = SCHEMA.addField(StringField.builder().name("format").build());
    public static final StringField URL = SCHEMA.addField(StringField.builder().name("url").build());
    // Subscribed event tokens; empty/null = receive every event.
    public static final ListField<String> EVENTS = SCHEMA.addField(
        ListField.<String>builder(StringField.builder().name("event").build()).name("events").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());


    /** The channel with this unique name, or null if none. */
    public Row findByName(String name) {
        return find().where(NAME.eq(name)).first();
    }

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
}
