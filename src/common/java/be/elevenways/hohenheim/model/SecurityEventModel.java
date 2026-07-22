package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.DateField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * AGGREGATED security events: one row per (reporter, type, ip, day) with an
 * atomically incremented count -- never a row per probe. A null reporter_id
 * means Hohenheim's own proxy observed the event.
 */
public class SecurityEventModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "security_event");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField REPORTER_ID = SCHEMA.addField(
        IntegerField.builder().name("reporter_id")
            .label(HohenheimFormCopy.label("reporter"))
            .build());
    public static final StringField TYPE = SCHEMA.addField(StringField.builder().name("type")
        .label(HohenheimFormCopy.label("event_type"))
        .build());
    public static final StringField IP = SCHEMA.addField(StringField.builder().name("ip")
        .label(HohenheimFormCopy.label("ip"))
        .build());
    public static final DateField DAY = SCHEMA.addField(DateField.builder().name("day")
        .label(HohenheimFormCopy.label("day"))
        .build());
    public static final IntegerField COUNT = SCHEMA.addField(IntegerField.builder().name("count")
        .label(HohenheimFormCopy.label("event_count"))
        .build());
    public static final DateTimeField FIRST_AT = SCHEMA.addField(
        DateTimeField.builder().name("first_at").build());
    public static final DateTimeField LAST_AT = SCHEMA.addField(
        DateTimeField.builder().name("last_at")
            .label(HohenheimFormCopy.label("last_seen"))
            .build());
    public static final StringField LAST_DETAIL = SCHEMA.addField(
        StringField.builder().name("last_detail").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("updated_at").build());

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "SecurityEvent"; }

    @Override
    public String getTableName() { return "security_events"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
