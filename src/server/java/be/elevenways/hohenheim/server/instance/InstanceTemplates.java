package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.variable.VariableTypeHandler;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.submit.FormValidator;
import be.elevenways.zenit.common.edit.submit.SubmittedValueCoercion;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The create-from-template flow and the tenant-selectability gate. A template's
 * variables become ONE ordinary zenit FormSpec (each type builds its real field), so a
 * submitted value is refused by the standard typed coercion/validation pipeline.
 */
public final class InstanceTemplates {

    private final InstanceVariables variables = new InstanceVariables();

    /**
     * Whether this template may be offered to / used by the given actor as an instance
     * source. Admins may use unapproved templates (they are the approvers, testing
     * their own catalog); everyone else requires an operator approval stamp.
     *
     * @throws Violations {@code template_not_approved}
     */
    public static void requireSelectable(@NonNull Row template, @Nullable AccessContext ctx) {
        if (ctx != null && HohenheimAccess.isAdmin(ctx)) {
            return;
        }
        if (template.get(InstanceTemplateModel.APPROVED_AT) == null) {
            throw Violations.ofForm(violationText("template_not_approved")
                .withArg("name", String.valueOf((Object) template.get(InstanceTemplateModel.NAME))));
        }
    }

    /** Whether the template declares an install step. */
    public static boolean hasInstallStep(@NonNull Row template) {
        String script = template.get(InstanceTemplateModel.INSTALL_SCRIPT);
        return script != null && !script.isBlank();
    }

    /**
     * The typed variable form of one template: one real zenit field per declared
     * variable, name = the variable KEY.
     */
    public @NonNull FormSpec variableFormSpec(int templateId) {
        FormSpec.Builder builder = FormSpec.builder();
        for (Row declared : declaredVariables(templateId)) {
            VariableTypeHandler handler = InstanceVariables.handlerOf(declared);
            String key = declared.get(InstanceTemplateVariableModel.KEY);
            String label = labelOf(declared);
            boolean required = Boolean.TRUE.equals(
                declared.get(InstanceTemplateVariableModel.REQUIRED));
            builder.add(handler.buildField(key, label, required,
                InstanceVariables.settingsOf(declared)));
        }
        return builder.build();
    }

    /** Render-time prefills: each variable's declared default under its key. */
    public @NonNull Map<String, Object> variableDefaults(int templateId) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (Row declared : declaredVariables(templateId)) {
            String fallback = declared.get(InstanceTemplateVariableModel.DEFAULT_VALUE);
            if (fallback != null && !fallback.isEmpty()) {
                defaults.put(declared.get(InstanceTemplateVariableModel.KEY), fallback);
            }
        }
        return defaults;
    }

    public @NonNull List<Row> declaredVariables(int templateId) {
        return Models.get(InstanceTemplateVariableModel.class).findByTemplateId(templateId);
    }

    /**
     * Create an instance FROM a template: coerce+validate the raw variable submit
     * through the typed form, persist the record (settings deep-copied from the
     * template -- the write funnel's image policy and quota hooks fire like on any
     * other create), then the variable rows and the template's config files.
     *
     * @param rawVariableValues the RAW form submit values (coercion happens here)
     * @return the created instance id
     * @throws Violations typed refusals from coercion/validation, or the approval gate
     */
    public int createFromTemplate(@NonNull Row template, @NonNull String name,
                                  @Nullable Integer serverId,
                                  @NonNull Map<String, Object> rawVariableValues,
                                  @Nullable AccessContext ctx) {
        requireSelectable(template, ctx);
        if (name.isBlank()) {
            throw Violations.ofField("name", name, violationText("name_required"));
        }
        int templateId = template.get(InstanceTemplateModel.ID);

        FormSpec spec = variableFormSpec(templateId);
        Map<String, Object> coerced = SubmittedValueCoercion.coerceFormOrThrow(spec, rawVariableValues);
        FormValidator.validateCoercedFormOrThrow(spec, coerced);

        InstanceModel instances = Models.get(InstanceModel.class);
        Row instance = instances.createEmptyRow();
        instance.set(InstanceModel.NAME, name.trim());
        instance.set(InstanceModel.KIND, template.get(InstanceTemplateModel.KIND));
        instance.set(InstanceModel.SETTINGS, copiedSettings(template));
        instance.set(InstanceModel.TEMPLATE_ID, templateId);
        instance.set(InstanceModel.SERVER_ID,
            serverId != null ? serverId : ServerModel.localServerId());
        instance.set(InstanceModel.INSTALL_STATE, hasInstallStep(template)
            ? InstanceModel.INSTALL_PENDING : InstanceModel.INSTALL_NONE);
        // The plan's default for game templates: clean-exit-as-crash. Template-created
        // instances restart on any unobserved exit; the operator can flip it per record.
        instance.set(InstanceModel.CRASH_POLICY, InstanceModel.CRASH_RESTART);
        instances.save(instance);
        int instanceId = instance.get(InstanceModel.ID);

        this.variables.writeForInstance(instanceId, declaredVariables(templateId), coerced);
        copyFiles(templateId, instanceId);
        return instanceId;
    }

    /** Copy the template's config files onto the instance (placeholders intact). */
    private static void copyFiles(int templateId, int instanceId) {
        InstanceFileModel files = Models.get(InstanceFileModel.class);
        for (Row templateFile : Models.get(InstanceTemplateFileModel.class)
                .findByTemplateId(templateId)) {
            Row file = files.createEmptyRow();
            file.set(InstanceFileModel.INSTANCE_ID, instanceId);
            file.set(InstanceFileModel.CONTAINER_PATH,
                templateFile.get(InstanceTemplateFileModel.CONTAINER_PATH));
            file.set(InstanceFileModel.CONTENT,
                templateFile.get(InstanceTemplateFileModel.CONTENT));
            file.set(InstanceFileModel.MODE,
                templateFile.get(InstanceTemplateFileModel.MODE));
            files.save(file);
        }
    }

    /** A deep-enough copy of the template's settings map (never the shared instance). */
    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> copiedSettings(@NonNull Row template) {
        Object settings = template.get(InstanceTemplateModel.SETTINGS);
        if (!(settings instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (value instanceof Map<?, ?> nested) {
                copy.put(String.valueOf(key), new LinkedHashMap<>((Map<String, Object>) nested));
            } else {
                copy.put(String.valueOf(key), value);
            }
        });
        return copy;
    }

    private static @NonNull String labelOf(@NonNull Row declared) {
        String label = declared.get(InstanceTemplateVariableModel.LABEL);
        if (label != null && !label.isBlank()) {
            return label;
        }
        return String.valueOf((Object) declared.get(InstanceTemplateVariableModel.KEY));
    }

    private static Microcopy violationText(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
