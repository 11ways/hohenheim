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
 * A managed config file of one INSTANCE, staged into its container before every start
 * via the archive API (the StackFileModel mechanism generalized). Rows are seeded from
 * the instance's template at create-from-template and stay per-instance editable;
 * {@code {{KEY}}} placeholders resolve against the instance's variables at deploy-time
 * upload, so secrets exist inside the container only, never re-persisted plaintext.
 * This is THE instance config-file mechanism later waves (Velocity forced-hosts
 * materialization) write through.
 */
public class InstanceFileModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "instance_file");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField INSTANCE_ID = SCHEMA.addField(
        IntegerField.builder().name("instance_id").build());

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

    /** All files of one instance, stable path order. */
    public List<Row> findByInstanceId(int instanceId) {
        return find().where(INSTANCE_ID.eq(instanceId)).orderBy(CONTAINER_PATH, SortOrder.ASC).all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "InstanceFile"; }

    @Override
    public String getTableName() { return "instance_files"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
