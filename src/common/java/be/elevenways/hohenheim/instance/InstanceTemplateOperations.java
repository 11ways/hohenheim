package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.FormStep;
import be.elevenways.zenit.common.edit.Nested;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Creating an instance from one approved template: the wizard every panel places on its from-template page.
 *
 * AIDEV-NOTE: the subject IS the template (DECIDED D2-B11), loaded through the template catalog's scope, so a template
 * the caller may not select is concealed before any input is read; no template id travels as input. The variables
 * entry is declared empty and resolved per admitted template on the server (O04): its schema, and the secret defaults
 * that fill a blank secret before validation, never reach a rendered form.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstanceTemplateOperations {

    /** The subject: one template of the catalog. */
    public static final SubjectType<Row> TEMPLATE = SubjectType.record(InstanceTemplateModel.MODEL_ID);

    public static final StringField NAME = StringField.builder("name").required()
        .label(copy("instance_name")).build();

    /** The host; an operator's choice, fixed to null for everyone else (InstancePlacement decides). */
    public static final IntegerField SERVER_ID = IntegerField.builder("serverId").label(copy("host")).build();

    public static final IntegerField PROJECT_ID = IntegerField.builder("projectId").label(copy("project")).build();

    /** No picker offers an environment here: every placement fixes it to null. */
    public static final IntegerField ENVIRONMENT_ID = IntegerField.builder("environmentId").build();

    /** The template's own typed variables, resolved per admitted template on the server. */
    public static final String VARIABLES = "variables";

    private static final FormSpec INPUT = FormSpec.builder()
        .add(NAME)
        .add(SERVER_ID)
        .add(PROJECT_ID)
        .add(ENVIRONMENT_ID)
        .add(Nested.of(VARIABLES).subSpec(FormSpec.builder().build()).build())
        .step(FormStep.of("details", copy("step_details"), NAME.getName(), SERVER_ID.getName(),
            PROJECT_ID.getName(), ENVIRONMENT_ID.getName()))
        .step(FormStep.of(VARIABLES, Microcopy.of("variables").withFilter("scope", "template_contents"), VARIABLES))
        .build();

    public static final Operation<Row, CreateFromTemplate, Integer> CREATE_INSTANCE_FROM_TEMPLATE =
        Operation.declare(HohenheimIds.id("create_instance_from_template"))
            .label(Microcopy.of("create_instance").withFilter("scope", "instance_template"))
            .icon(Icon.of("plus"))
            .one(TEMPLATE)
            .gate(OperationGate.open())
            .input(OperationInput.of(INPUT, CreateFromTemplate.class, values -> new CreateFromTemplate(
                values.get(NAME), values.get(SERVER_ID), values.get(PROJECT_ID), values.get(ENVIRONMENT_ID),
                variables(values.get(VARIABLES)))))
            .result(Integer.class)
            .rateLimit(HohenheimEndpoints.INSTANCE_CREATE_LIMIT)
            .facts(OperationFact.REACHES_OUTSIDE)
            .register();

    private InstanceTemplateOperations() {}

    /**
     * One coerced create: the template's variables as their typed values, a secret one as the pipeline sealed it.
     *
     * @param variables by variable key; attribution (project, environment) is never a variable (D5-B06)
     */
    public record CreateFromTemplate(@Nullable String name, @Nullable Integer serverId, @Nullable Integer projectId,
                                     @Nullable Integer environmentId, @NonNull Map<String, Object> variables) {

        public CreateFromTemplate {
            variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        }
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> variables(@Nullable Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static @NonNull Microcopy copy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "instance_from_template");
    }
}
