package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;

/** Canonical source pointer, promoted only after health success or restore into a new application. */
public class ArtifactSourceModel extends Model {
    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "artifact_source");
    public static final Schema SCHEMA = new Schema();
    public static final IntegerField APPLICATION_ID = SCHEMA.addField(IntegerField.builder().name("application_id").build());
    public static final StringField ARTIFACT_SHA256 = SCHEMA.addField(StringField.builder().name("artifact_sha256").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());
    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return APPLICATION_ID; }
    @Override public String getModelName() { return "ArtifactSource"; }
    @Override public String getTableName() { return "artifact_sources"; }
    @Override public Schema getSchema() { return SCHEMA; }
}
