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
 * A trusted security-event reporter (a zenit app posting to /zn/security/ingest).
 * Only the SHA-256 hash of the zsec_ bearer token is stored; the raw value is
 * shown once at mint time (or, for auto-minted site reporters, kept on the
 * site row for env injection).
 */
public class SecurityReporterModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "security_reporter");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimFormCopy.label("name"))
        .build());
    public static final IntegerField SITE_ID = SCHEMA.addField(IntegerField.builder().name("site_id")
        .label(HohenheimFormCopy.label("reporter_site"))
        .help(HohenheimFormCopy.help("reporter_site"))
        .build());
    public static final StringField TOKEN_HASH = SCHEMA.addField(StringField.builder().name("token_hash")
        .secret()
        .build());
    public static final BooleanField ENABLED = SCHEMA.addField(BooleanField.builder("enabled")
        .defaultValue(true)
        .label(HohenheimFormCopy.label("enabled"))
        .build());
    public static final DateTimeField LAST_SEEN_AT = SCHEMA.addField(
        DateTimeField.builder().name("last_seen_at").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("updated_at").build());

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "SecurityReporter"; }

    @Override
    public String getTableName() { return "security_reporters"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
