package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * The single row naming THIS controller; read through
 * {@code be.elevenways.hohenheim.server.ControllerIdentity}, never directly.
 */
public class ControllerIdentityModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "controller_identity");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    /** Always 1: the unique index on it is what makes the table single-row. */
    public static final IntegerField SINGLETON =
        SCHEMA.addField(IntegerField.builder().name("singleton").build());

    /** The lowercase alphanumeric namespace token every daemon resource name carries. */
    public static final StringField TOKEN =
        SCHEMA.addField(StringField.builder().name("token").build());

    public static final DateTimeField CREATED_AT =
        SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT =
        SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "ControllerIdentity"; }

    @Override
    public String getTableName() { return "controller_identity"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
