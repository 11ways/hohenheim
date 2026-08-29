package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

/**
 * A template's DEFAULT volume declarations, copied onto the instance at create time --
 * the {@code instance_template_variables} to {@code instance_variables} precedent.
 *
 * AIDEV-NOTE: a copy, never a live reference. A template edit must not silently re-mount
 * or re-quota a running workspace, which is the same reason template VARIABLES are copied
 * rather than joined ({@code InstanceTemplates.createFromTemplate}).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public class InstanceTemplateVolumeModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "instance_template_volume");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField TEMPLATE_ID = SCHEMA.addField(
        IntegerField.builder().name("template_id").build());

    /** The owning template, declared so its delete takes the volumes along (InstanceCatalogGuards). */
    public static final BelongsTo<InstanceTemplateModel> TEMPLATE = SCHEMA.addRelation(
        BelongsTo.to(InstanceTemplateModel.class)
            .name("template")
            .localKey(TEMPLATE_ID)
            .remoteKey(InstanceTemplateModel.ID)
            .build());

    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .required()
        .label(HohenheimFormCopy.label("volume_name"))
        .help(HohenheimFormCopy.help("volume_name"))
        .build());

    public static final StringField CONTAINER_PATH = SCHEMA.addField(
        StringField.builder().name("container_path")
            .required()
            .label(HohenheimFormCopy.label("container_path"))
            .help(HohenheimFormCopy.help("container_path"))
            .build());

    public static final LongField QUOTA_BYTES = SCHEMA.addField(
        LongField.builder().name("quota_bytes")
            .label(HohenheimFormCopy.label("quota_bytes"))
            .help(HohenheimFormCopy.help("quota_bytes"))
            .build());

    public static final BooleanField EXCLUSIVE = SCHEMA.addField(
        BooleanField.builder("exclusive").defaultValue(false)
            .label(HohenheimFormCopy.label("exclusive_volume"))
            .help(HohenheimFormCopy.help("exclusive_volume"))
            .build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("updated_at").build());

    /** @return this template's declared volumes, name-ordered */
    public List<Row> findByTemplateId(int templateId) {
        return find().where(TEMPLATE_ID.eq(templateId)).orderBy(NAME, SortOrder.ASC).all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "InstanceTemplateVolume"; }

    @Override
    public String getTableName() { return "instance_template_volumes"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
