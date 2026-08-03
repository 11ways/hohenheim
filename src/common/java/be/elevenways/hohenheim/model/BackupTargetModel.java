package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.backup.BackupTargetRegistry;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/**
 * A configured destination for instance backup exports (the target seam's data half):
 * kind (filesystem or ssh now) plus per-kind settings via the registry-driven
 * type-enum pattern. Whether a target is genuinely OFF-HOST is a deployment fact the
 * operator owns -- a filesystem target on the controller host is not.
 */
public class BackupTargetModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "backup_target");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimFormCopy.label("name"))
        .build());

    public static final EnumField KIND = SCHEMA.addField(
        RegistryEnumField.builder("kind")
            .registry(BackupTargetRegistry.REGISTRY)
            .label(HohenheimFormCopy.label("kind"))
            .help(HohenheimFormCopy.help("backup_target_kind"))
            .build());

    public static final SchemaField SETTINGS = SCHEMA.addField(
        SchemaField.builder("settings")
            .schemaFrom("kind")
            .label(HohenheimFormCopy.label("settings"))
            .build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        SCHEMA.setDisplayFields(NAME);
    }

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "BackupTarget"; }
    @Override public String getTableName() { return "backup_targets"; }
    @Override public Schema getSchema() { return SCHEMA; }
}
