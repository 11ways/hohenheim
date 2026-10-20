package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.orm.behaviour.HistoryBehaviour;
import be.elevenways.zenit.common.orm.datasource.Row;
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
import be.elevenways.zenit.common.orm.model.ModelRef;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.model.relation.BelongsTo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** The environment model's reference: its class and MODEL_ID, resolved at first use. */
    public static final ModelRef<EnvironmentModel> REF = ModelRef.of(EnvironmentModel.class, MODEL_ID);

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
     * AIDEV-NOTE: the one place an environment's title is decided. The projects of a page of environments are titled
     * in one read through the project source, as the reader may read them (referencedTitles); one the reader may not
     * read, and every title asked without a reader (a background lane), is the bare name, as is the crumb under the
     * project's own crumb (resolveTitleBeneathParent).
     *
     * @return per row, null when the environment has no name, so the pk fallback applies
     */
    @Override
    public @NonNull List<@Nullable String> resolveDisplayTitles(@NonNull List<Row> rows, @Nullable LocaleChain chain,
                                                                @Nullable AccessContext reader) {
        Set<Integer> projectIds = new LinkedHashSet<>();
        for (Row row : rows) {
            Integer projectId = row.get(PROJECT_ID);
            if (projectId != null) {
                projectIds.add(projectId);
            }
        }
        RecordSource<?> projects = RecordSourceRegistry.INSTANCE.byId(ProjectModel.MODEL_ID);
        Map<String, String> named = reader == null || projectIds.isEmpty() || projects == null
            || !projects.authorizes(reader) ? Map.of() : projects.referencedTitles(projectIds, chain, reader);
        List<@Nullable String> titles = new ArrayList<>(rows.size());
        for (Row row : rows) {
            String name = super.resolveDisplayTitle(row, chain);
            String project = named.get(String.valueOf((Object) row.get(PROJECT_ID)));
            titles.add(name == null || project == null ? name : project + PROJECT_SEPARATOR + name);
        }
        return titles;
    }

    @Override public Identifier getModelId() { return MODEL_ID; }
    @Override public Field<?, ?> getPrimaryKeyField() { return ID; }
    @Override public String getModelName() { return "Environment"; }
    @Override public String getTableName() { return "environments"; }
    @Override public Schema getSchema() { return SCHEMA; }

    public static final HistoryBehaviour HISTORY = SCHEMA.addBehaviour(HistoryBehaviour.optional().changes());
}
