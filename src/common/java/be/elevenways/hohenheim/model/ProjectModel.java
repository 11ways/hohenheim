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
import be.elevenways.zenit.common.orm.model.relation.HasMany;

/**
 * A project: the hierarchical OWNER tier (open decision 7, decided). A project's
 * identity as an owner is its zenit-auth permission GROUP ({@link #GROUP_ID}) --
 * a record belongs to the project when its {@code manage} grant is held by the
 * subject {@code group:<group_id>}, so ownership stays entirely grant-derived
 * (sameOwner, quota buckets, placement and the released-claim ledger all keep
 * answering from HohenheimAccess.manageSubjectsOf). Membership is the framework's
 * one group-membership walk: a positive {@code group.<slug>} grant on the user.
 *
 * AIDEV-NOTE: there is deliberately NO project_id column on any owned record and
 * NO owner column here -- a foreign key would be a second ownership authority
 * beside the grants, the exact "projects bolted onto URLs" failure the plan
 * forbids. "Which records are in project P" is a grant query (Projects.projectOf).
 */
public class ProjectModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "project");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    /** Never localized: a project name is user data, like instance and server names. */
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .required()
        .label(HohenheimFormCopy.label("project_name"))
        .build());

    public static final TextField DESCRIPTION = SCHEMA.addField(TextField.builder().name("description")
        .label(HohenheimFormCopy.label("project_description"))
        .build());

    /**
     * The zenit-auth permission group that IS this project's owner subject. Stamped by
     * the server-side write hook at create (ProjectGuards) and immutable afterwards --
     * membership grants reference the group's slug, so it may never be swapped.
     */
    public static final IntegerField GROUP_ID = SCHEMA.addField(
        IntegerField.builder().name("group_id").build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    public static final HasMany<EnvironmentModel> ENVIRONMENTS = SCHEMA.addRelation(
        HasMany.to(EnvironmentModel.class)
            .name("environments")
            .localKey(ID)
            .remoteKey(EnvironmentModel.PROJECT_ID)
            .build());

    static {
        SCHEMA.setDisplayFields(NAME);
    }

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "Project"; }
    @Override public String getTableName() { return "projects"; }
    @Override public Schema getSchema() { return SCHEMA; }
}
