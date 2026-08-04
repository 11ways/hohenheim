package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.variable.VariableTypeHandler;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.server.RecordGrants;
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

    /**
     * The template a raw submit names, or null when the field is absent, malformed or
     * points at nothing. Shared by the HTML page handler and the API create handler so
     * both resolve the same field the same way.
     */
    public static @Nullable Row templateFrom(@NonNull Map<String, Object> form) {
        Integer templateId = submittedInteger(form, "template_id");
        return templateId == null ? null
            : Models.get(InstanceTemplateModel.class).findById(templateId);
    }

    /** One raw submit value as a trimmed string ("" when absent); lists take their first. */
    public static @NonNull String submittedString(@NonNull Map<String, Object> form,
                                                  @NonNull String name) {
        Object value = form.get(name);
        if (value instanceof List<?> list) {
            value = list.isEmpty() ? null : list.get(0);
        }
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** One raw submit value as an Integer, or null when absent or unparseable. */
    public static @Nullable Integer submittedInteger(@NonNull Map<String, Object> form,
                                                     @NonNull String name) {
        String raw = submittedString(form, name);
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException malformed) {
            return null;
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
     * THE instance-creation funnel, for every surface: the admin page, the /manage page
     * and the automation API all land here, so "what the UI refuses" and "what the API
     * refuses" are one set of refusals by construction rather than by review.
     *
     * The order is load-bearing. Authority first (may this actor create at all), then the
     * template approval gate, then PLACEMENT (a non-admin's requested host is ignored --
     * see {@link InstancePlacement}), then the typed variable coercion/validation, and
     * only then the write, where the image policy and the quota reservation fire as
     * beforeValidate/beforeWrite hooks. A tenant create finally plants the creator's
     * {@code manage} grant, because a record its creator cannot see is not a create.
     *
     * @param serverId the caller-supplied host; honoured for admins, ignored for tenants
     * @param rawVariableValues the RAW form submit values (coercion happens here)
     * @return the created instance id
     * @throws Violations typed refusals: create authority, approval, placement, coercion,
     *         image policy, quota
     */
    public int createFromTemplate(@NonNull Row template, @NonNull String name,
                                  @Nullable Integer serverId,
                                  @NonNull Map<String, Object> rawVariableValues,
                                  @Nullable AccessContext ctx) {
        if (ctx != null && !HohenheimAccess.canCreateInstances(ctx)) {
            throw Violations.ofForm(violationText("instance_create_not_permitted"));
        }
        requireSelectable(template, ctx);
        if (name.isBlank()) {
            throw Violations.ofField("name", name, violationText("name_required"));
        }
        int placement = InstancePlacement.forActor(ctx, serverId);
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
        instance.set(InstanceModel.SERVER_ID, placement);
        instance.set(InstanceModel.INSTALL_STATE, hasInstallStep(template)
            ? InstanceModel.INSTALL_PENDING : InstanceModel.INSTALL_NONE);
        // The plan's default for game templates: clean-exit-as-crash. Template-created
        // instances restart on any unobserved exit; the operator can flip it per record.
        instance.set(InstanceModel.CRASH_POLICY, InstanceModel.CRASH_RESTART);
        instances.save(instance);
        int instanceId = instance.get(InstanceModel.ID);

        this.variables.writeForInstance(instanceId, declaredVariables(templateId), coerced);
        copyFiles(templateId, instanceId);
        grantCreatorManage(instanceId, ctx);
        return instanceId;
    }

    /**
     * Hand a tenant creator {@code manage} on what it just created. Operator creates
     * plant nothing: an empty subject set IS operator ownership, and a grant there would
     * make one admin's instance look tenant-held to sameOwner.
     *
     * AIDEV-NOTE: this MUST name the same subject the quota charged
     * ({@link HohenheimAccess#creationOwnerSubjects}) -- InstanceQuota reserves against
     * that set at create, and this is what makes the reservation honest afterwards.
     */
    private static void grantCreatorManage(int instanceId, @Nullable AccessContext ctx) {
        for (String subject : HohenheimAccess.creationOwnerSubjects(ctx)) {
            int separator = subject.indexOf(':');
            RecordGrants.grant(subject.substring(0, separator),
                Integer.parseInt(subject.substring(separator + 1)),
                InstanceModel.MODEL_ID, instanceId, HohenheimAccess.MANAGE, true);
        }
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
