package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * An IP ban: auto-created by the threat scorer or the spamservice reputation
 * policy, or manual from the admin. A null expires_at means permanent; the
 * kernel expires nftables elements itself, this row is the source of truth.
 */
public class BanModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "ban");
    public static final Schema SCHEMA = new Schema();

    public static final String SOURCE_AUTO = "auto";
    public static final String SOURCE_MANUAL = "manual";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField IP = SCHEMA.addField(StringField.builder().name("ip")
        .label(HohenheimFormCopy.label("ip"))
        .build());
    public static final StringField REASON = SCHEMA.addField(StringField.builder().name("reason")
        .label(HohenheimFormCopy.label("ban_reason"))
        .build());
    public static final StringField SOURCE = SCHEMA.addField(StringField.builder().name("source")
        .label(HohenheimFormCopy.label("ban_source"))
        .build());
    public static final StringField EVENT_TYPE = SCHEMA.addField(StringField.builder().name("event_type")
        .label(HohenheimFormCopy.label("event_type"))
        .build());
    public static final DateTimeField EXPIRES_AT = SCHEMA.addField(
        DateTimeField.builder().name("expires_at")
            .label(HohenheimFormCopy.label("expires_at"))
            .build());
    public static final BooleanField ACTIVE = SCHEMA.addField(BooleanField.builder("active")
        .defaultValue(true)
        .label(HohenheimFormCopy.label("active"))
        .build());
    public static final DateTimeField LIFTED_AT = SCHEMA.addField(
        DateTimeField.builder().name("lifted_at").build());
    public static final StringField LIFTED_BY = SCHEMA.addField(
        StringField.builder().name("lifted_by").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("updated_at").build());

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "Ban"; }

    @Override
    public String getTableName() { return "bans"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
