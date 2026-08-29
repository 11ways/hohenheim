package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.validator.Regex;

import java.util.List;

/**
 * A managed database an instance template DECLARES it needs: creating an instance from the
 * template allocates one database per row on the instance's host and attaches it under
 * {@code env_prefix} ({@code instance_databases}), so the workload finds its credentials in
 * the injected variable family without any operator attach step.
 *
 * AIDEV-NOTE: this is a DECLARATION, not an attachment. The declaration is copied into a
 * real database record plus a real link row at create time (the template variables to
 * instance variables precedent), so editing the template later never re-points a running
 * workload's database. {@code image} is an operator-authored override of the engine's
 * default image (a template is operator-curated, which is why a tenant creating from an
 * approved one may run it); null means the engine's own default.
 */
public class InstanceTemplateDatabaseModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "instance_template_database");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField TEMPLATE_ID = SCHEMA.addField(
        IntegerField.builder().name("template_id").build());

    /** The owning template, declared so its delete takes the declarations along (InstanceCatalogGuards). */
    public static final BelongsTo<InstanceTemplateModel> TEMPLATE = SCHEMA.addRelation(
        BelongsTo.to(InstanceTemplateModel.class)
            .name("template")
            .localKey(TEMPLATE_ID)
            .remoteKey(InstanceTemplateModel.ID)
            .build());

    /** The engine token, the SAME vocabulary {@link DatabaseModel#ENGINE} stores. */
    public static final EnumField ENGINE = SCHEMA.addField(
        DatabaseModel.engineFieldBuilder("engine")
            .label(HohenheimFormCopy.label("engine"))
            .help(HohenheimFormCopy.help("template_database_engine"))
            .build());

    /** The injected variable family the created attachment carries. */
    public static final StringField ENV_PREFIX = SCHEMA.addField(
        StringField.builder().name("env_prefix")
            .required()
            .validator(Regex.of("^" + InstanceDatabaseModel.PREFIX_PATTERN + "$", "prefix_format"))
            .label(HohenheimFormCopy.label("env_prefix"))
            .help(HohenheimFormCopy.help("template_database_prefix"))
            .build());

    /** Engine image override; blank = the engine's default image. */
    public static final StringField IMAGE = SCHEMA.addField(
        StringField.builder().name("image")
            .label(HohenheimFormCopy.label("image"))
            .help(HohenheimFormCopy.help("template_database_image"))
            .build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("updated_at").build());

    static {
        SCHEMA.setDisplayFields(ENV_PREFIX);
    }

    /** @return this template's declared databases, prefix-ordered */
    public List<Row> findByTemplateId(int templateId) {
        return find().where(TEMPLATE_ID.eq(templateId)).orderBy(ENV_PREFIX, SortOrder.ASC).all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "InstanceTemplateDatabase"; }

    @Override
    public String getTableName() { return "instance_template_databases"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
