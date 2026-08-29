package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;

import java.util.List;

/**
 * A managed config file uploaded into a stack service's container before start
 * (via the archive API, so it works on remote daemons too). Content is encrypted
 * at rest because these files routinely carry tokens and credentials; it stays
 * visible to admin editors on purpose.
 */
public class StackFileModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "stack_file");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField STACK_SERVICE_ID = SCHEMA.addField(
        IntegerField.builder().name("stack_service_id").build());

    /** The owning service, declared so its delete takes the files along (StackCascades). */
    public static final BelongsTo<StackServiceModel> SERVICE = SCHEMA.addRelation(
        BelongsTo.to(StackServiceModel.class)
            .name("service")
            .localKey(STACK_SERVICE_ID)
            .remoteKey(StackServiceModel.ID)
            .build());

    public static final StringField CONTAINER_PATH = SCHEMA.addField(StringField.builder().name("container_path")
        .required()
        .label(HohenheimFormCopy.label("container_path"))
        .help(HohenheimFormCopy.help("file_container_path"))
        .build());

    public static final TextField CONTENT = SCHEMA.addField(TextField.builder().name("content")
        .encrypted()
        .label(HohenheimFormCopy.label("file_content"))
        .help(HohenheimFormCopy.help("file_content"))
        .build());

    public static final StringField MODE = SCHEMA.addField(StringField.builder().name("mode")
        .defaultValue("0644")
        .label(HohenheimFormCopy.label("file_mode"))
        .help(HohenheimFormCopy.help("file_mode"))
        .build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    /** All files of one service. */
    public List<Row> findByServiceId(int serviceId) {
        return find().where(STACK_SERVICE_ID.eq(serviceId))
            .orderBy(ID, be.elevenways.zenit.common.orm.query.SortOrder.ASC).all();
    }

    static {
        // The path inside the container, exactly as the sibling file models declare it.
        SCHEMA.setDisplayFields(CONTAINER_PATH);
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "StackFile"; }

    @Override
    public String getTableName() { return "stack_files"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
