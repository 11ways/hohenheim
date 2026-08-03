package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

/**
 * A config file an instance template declares (StackFileModel generalized to the
 * instance tier). Content may carry {@code {{KEY}}} variable placeholders; it is COPIED
 * onto the created instance (instance_files) and substituted at deploy-time upload,
 * so a variable change re-renders on the next deploy. Encrypted at rest like stack
 * files -- config files routinely carry credentials -- while staying admin-visible
 * (encryption is not secrecy).
 */
public class InstanceTemplateFileModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "instance_template_file");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField TEMPLATE_ID = SCHEMA.addField(
        IntegerField.builder().name("template_id").build());

    public static final StringField CONTAINER_PATH = SCHEMA.addField(StringField.builder().name("container_path")
        .required()
        .label(HohenheimFormCopy.label("container_path"))
        .help(HohenheimFormCopy.help("file_container_path"))
        .build());

    public static final TextField CONTENT = SCHEMA.addField(TextField.builder().name("content")
        .encrypted()
        .label(HohenheimFormCopy.label("file_content"))
        .help(HohenheimFormCopy.help("template_file_content"))
        .build());

    public static final StringField MODE = SCHEMA.addField(StringField.builder().name("mode")
        .defaultValue("0644")
        .label(HohenheimFormCopy.label("file_mode"))
        .help(HohenheimFormCopy.help("file_mode"))
        .build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        SCHEMA.setDisplayFields(CONTAINER_PATH);
    }

    /** All files of one template, stable path order. */
    public List<Row> findByTemplateId(int templateId) {
        return find().where(TEMPLATE_ID.eq(templateId)).orderBy(CONTAINER_PATH, SortOrder.ASC).all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "InstanceTemplateFile"; }

    @Override
    public String getTableName() { return "instance_template_files"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
