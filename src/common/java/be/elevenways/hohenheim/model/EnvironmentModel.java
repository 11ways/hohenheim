package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.zenit.common.orm.behaviour.HistoryBehaviour;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
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

    /** Between the project's title and the environment's name in the environment's title. */
    public static final String PROJECT_SEPARATOR = " / ";

    public static final Identifier MODEL_ID = HohenheimIds.id("environment");
    public static final Schema SCHEMA = new Schema();

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    public static final IntegerField PROJECT_ID = SCHEMA.addField(
        IntegerField.builder().name("project_id")
            .required()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("project"))
            .build());

    /** Never localized: an environment name is user data. */
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .required()
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("environment_name"))
        .build());

    public static final TextField DESCRIPTION = SCHEMA.addField(TextField.builder().name("description")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("project_description"))
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

    /**
     * An environment is named with its project ("Webshop / production"): two projects' "production" are told apart in
     * every list, picker and history line outside their own project page.
     *
     * AIDEV-NOTE: the one place an environment's title is decided; {@link #resolveTitleBeneathParent} keeps the bare
     * name for the crumb under the project's own crumb. The title reads the project row by key, as every title surface
     * of an admin-only environment may.
     *
     * @return null when the environment has no name, so the pk fallback applies
     */
    @Override
    public @Nullable String resolveDisplayTitle(@NonNull Row row, @Nullable LocaleChain chain) {
        String name = super.resolveDisplayTitle(row, chain);
        Integer projectId = row.get(PROJECT_ID);
        if (name == null || projectId == null) {
            return name;
        }
        ProjectModel projects = Models.get(ProjectModel.class);
        Row project = projects.find().where(ProjectModel.ID.eq(projectId)).first();
        String projectTitle = project != null ? projects.resolveDisplayTitle(project, chain) : null;
        return projectTitle != null ? projectTitle + PROJECT_SEPARATOR + name : name;
    }

    @Override
    public @Nullable String resolveTitleBeneathParent(@NonNull Row row, @Nullable LocaleChain chain) {
        return super.resolveDisplayTitle(row, chain);
    }

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "Environment"; }
    @Override public String getTableName() { return "environments"; }
    @Override public Schema getSchema() { return SCHEMA; }

    public static final HistoryBehaviour HISTORY = SCHEMA.addBehaviour(HistoryBehaviour.optional().changes());
}
