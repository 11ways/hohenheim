package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;

/**
 * An environment inside one project (production, staging, ...): a GROUPING of
 * variables and workloads within one owner, never an authority of its own --
 * authorization stays the project's grant-derived ownership, and the write-funnel
 * guard (ProjectGuards) refuses attaching a record to an environment whose project
 * does not own that record.
 */
public class EnvironmentModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "environment");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField PROJECT_ID = SCHEMA.addField(
        IntegerField.builder().name("project_id")
            .required()
            .label(HohenheimFormCopy.label("project"))
            .build());

    /** Never localized: an environment name is user data. */
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .required()
        .label(HohenheimFormCopy.label("environment_name"))
        .build());

    public static final TextField DESCRIPTION = SCHEMA.addField(TextField.builder().name("description")
        .label(HohenheimFormCopy.label("project_description"))
        .build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    public static final BelongsTo<ProjectModel> PROJECT = SCHEMA.addRelation(
        BelongsTo.to(ProjectModel.class)
            .name("project")
            .localKey(PROJECT_ID)
            .remoteKey(ProjectModel.ID)
            .build());

    static {
        SCHEMA.setDisplayFields(NAME);
    }

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "Environment"; }
    @Override public String getTableName() { return "environments"; }
    @Override public Schema getSchema() { return SCHEMA; }
}
