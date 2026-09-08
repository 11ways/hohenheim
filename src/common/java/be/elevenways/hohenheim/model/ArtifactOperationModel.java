package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/** Durable upload receipt; successful rows also own immutable artifact source history. */
public class ArtifactOperationModel extends Model {
    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "artifact_operation");
    public static final Schema SCHEMA = new Schema();
    public static final String PENDING = "pending";
    public static final String RUNNING = "running";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";
    public static final String INTERRUPTED = "interrupted";
    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final IntegerField APPLICATION_ID = SCHEMA.addField(IntegerField.builder().name("application_id").build());
    public static final IntegerField SITE_ID = SCHEMA.addField(IntegerField.builder().name("site_id").build());
    public static final StringField STATUS = SCHEMA.addField(StringField.builder().name("status").build());
    public static final StringField ARTIFACT_SHA256 = SCHEMA.addField(StringField.builder().name("artifact_sha256").build());
    public static final IntegerField INSTANCE_ID = SCHEMA.addField(IntegerField.builder().name("instance_id").build());
    public static final StringField IMAGE_ID = SCHEMA.addField(StringField.builder().name("image_id").build());
    /** Safe machine error code only: exception messages can contain paths or credentials. */
    public static final StringField ERROR = SCHEMA.addField(StringField.builder().name("error").build());
    public static final DateTimeField FINISHED_AT = SCHEMA.addField(DateTimeField.builder().name("finished_at").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());
    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "ArtifactOperation"; }
    @Override public String getTableName() { return "artifact_operations"; }
    @Override public Schema getSchema() { return SCHEMA; }
}
