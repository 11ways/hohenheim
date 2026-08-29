package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.instance.VariableTypeRegistry;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.validator.Regex;

import java.util.List;

/**
 * One TYPED variable of an instance template: key (the env name), type (registry-driven,
 * per-type settings via schemaFrom), required flag and default. The type builds a REAL
 * zenit field at create-from-template time, so a submitted value is refused by typed
 * coercion/validation, never a rule-string.
 */
public class InstanceTemplateVariableModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "instance_template_variable");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField TEMPLATE_ID = SCHEMA.addField(
        IntegerField.builder().name("template_id").build());

    /** The owning template, declared so its delete takes the variables along (InstanceCatalogGuards). */
    public static final BelongsTo<InstanceTemplateModel> TEMPLATE = SCHEMA.addRelation(
        BelongsTo.to(InstanceTemplateModel.class)
            .name("template")
            .localKey(TEMPLATE_ID)
            .remoteKey(InstanceTemplateModel.ID)
            .build());

    // The environment-variable name AND the substitution token ({{KEY}}) in command and
    // config-file content. Uppercase env spelling enforced by a typed validator.
    public static final StringField KEY = SCHEMA.addField(StringField.builder().name("key")
        .required()
        .validator(Regex.of("^[A-Z][A-Z0-9_]*$", "variable_key_format"))
        .label(HohenheimFormCopy.label("variable_key"))
        .help(HohenheimFormCopy.help("variable_key"))
        .build());

    // Catalog data that travels in exports: plain strings, never localized content.
    public static final StringField LABEL = SCHEMA.addField(StringField.builder().name("label")
        .label(HohenheimFormCopy.label("variable_label"))
        .help(HohenheimFormCopy.help("variable_label"))
        .build());

    public static final StringField DESCRIPTION = SCHEMA.addField(StringField.builder().name("description")
        .label(HohenheimFormCopy.label("description"))
        .build());

    public static final EnumField TYPE = SCHEMA.addField(
        RegistryEnumField.builder("type")
            .registry(VariableTypeRegistry.REGISTRY)
            .label(HohenheimFormCopy.label("variable_type"))
            .help(HohenheimFormCopy.help("variable_type"))
            .build());

    /** Per-type constraint settings (min/max, options, pattern, generate...). */
    public static final SchemaField SETTINGS = SCHEMA.addField(
        SchemaField.builder("settings")
            .schemaFrom("type")
            .label(HohenheimFormCopy.label("settings"))
            .build());

    public static final BooleanField REQUIRED = SCHEMA.addField(BooleanField.builder("required")
        .defaultValue(false)
        .label(HohenheimFormCopy.label("variable_required"))
        .build());

    /** Default value (string form; coerced through the type's field like any submission). */
    public static final StringField DEFAULT_VALUE = SCHEMA.addField(
        StringField.builder().name("default_value")
            .label(HohenheimFormCopy.label("variable_default"))
            .help(HohenheimFormCopy.help("variable_default"))
            .build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        SCHEMA.setDisplayFields(KEY);
    }

    /** All variables of one template, stable key order. */
    public List<Row> findByTemplateId(int templateId) {
        return find().where(TEMPLATE_ID.eq(templateId)).orderBy(KEY, SortOrder.ASC).all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "InstanceTemplateVariable"; }

    @Override
    public String getTableName() { return "instance_template_variables"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
