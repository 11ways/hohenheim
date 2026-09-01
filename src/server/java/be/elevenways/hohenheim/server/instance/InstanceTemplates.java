package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.model.InstanceTemplateVolumeModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.hohenheim.server.instance.variable.VariableTypeHandler;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.model.GrantSubjectType;
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

import java.util.ArrayList;
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

    /** The volume declarations this template hands to every instance created from it. */
    public @NonNull List<Row> declaredVolumes(int templateId) {
        return Models.get(InstanceTemplateVolumeModel.class).findByTemplateId(templateId);
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

        // A create INTO a project: the submitted project (or the chosen environment's)
        // becomes the creation OWNER -- the quota charge, the placement decision, the
        // environment-grouping guard and the planted manage grant all follow the one
        // pinned derivation (HohenheimAccess.withCreationOwner). Membership is the gate.
        Integer environmentId = submittedInteger(rawVariableValues, "environment_id");
        Row project = projectFor(rawVariableValues, environmentId);
        if (project != null) {
            if (!Projects.mayCreateInto(ctx, project)) {
                throw Violations.ofField("project_id", project.get(ProjectModel.ID),
                    violationText("project_member_required"));
            }
            int[] created = new int[1];
            HohenheimAccess.withCreationOwner(Projects.ownerSubjectsOf(project), () ->
                created[0] = createRecord(template, name, serverId, environmentId,
                    rawVariableValues, ctx));
            return created[0];
        }
        if (environmentId != null) {
            // An environment without a resolvable project can never pass the grouping
            // guard; refuse it by name instead of surfacing a generic mismatch.
            throw Violations.ofField("environment_id", environmentId,
                violationText("environment_unknown"));
        }
        return createRecord(template, name, serverId, null, rawVariableValues, ctx);
    }

    /** The submitted project row, from {@code project_id} or the environment's parent. */
    private static @Nullable Row projectFor(@NonNull Map<String, Object> form,
                                            @Nullable Integer environmentId) {
        Integer projectId = submittedInteger(form, "project_id");
        if (projectId != null) {
            Row project = Models.get(ProjectModel.class).findById(projectId);
            if (project == null) {
                throw Violations.ofField("project_id", projectId,
                    violationText("project_unknown"));
            }
            if (environmentId != null) {
                Row environment = Models.get(EnvironmentModel.class).findById(environmentId);
                if (environment == null || !projectId.equals(
                        environment.get(EnvironmentModel.PROJECT_ID))) {
                    throw Violations.ofField("environment_id", environmentId,
                        violationText("environment_project_mismatch"));
                }
            }
            return project;
        }
        if (environmentId != null) {
            Row environment = Models.get(EnvironmentModel.class).findById(environmentId);
            if (environment == null) {
                return null;
            }
            return Models.get(ProjectModel.class)
                .findById(environment.get(EnvironmentModel.PROJECT_ID));
        }
        return null;
    }

    private int createRecord(@NonNull Row template, @NonNull String name,
                             @Nullable Integer serverId, @Nullable Integer environmentId,
                             @NonNull Map<String, Object> rawVariableValues,
                             @Nullable AccessContext ctx) {
        if (name.isBlank()) {
            throw Violations.ofField("name", name, violationText("name_required"));
        }
        // Placement is runtime-aware: the template's kind names the daemon flavour it
        // needs, and only hosts declaring that runtime qualify.
        InstanceKindHandler kindHandler = InstanceKinds.getHandler(
            template.get(InstanceTemplateModel.KIND));
        Map<String, Object> placedSettings = copiedSettings(template);
        // The settings travel WITH the runtime: they price the workload against each
        // host's memory budget and answer the kind's own host requirements (a prepared
        // image must already be published on the host that gets chosen).
        int placement = InstancePlacement.forActor(ctx, serverId,
            InstancePlacement.Workload.of(kindHandler, placedSettings));
        int templateId = template.get(InstanceTemplateModel.ID);

        FormSpec spec = variableFormSpec(templateId);
        Map<String, Object> coerced = SubmittedValueCoercion.coerceFormOrThrow(spec, rawVariableValues);
        FormValidator.validateCoercedFormOrThrow(spec, coerced);
        // The declared databases' cheap refusals (engine, injectability, label taken)
        // come BEFORE the instance row exists, so the common failures never need the
        // compensation below. The declared VOLUMES answer the same way, for the same
        // reason -- and because a volume declaration that cannot be honoured must never
        // become an instance whose Volumes tab is empty.
        List<Row> templateVolumes = declaredVolumes(templateId);
        requireVolumesCopyable(kindHandler, templateVolumes);
        TemplateDatabases.precheck(template, name.trim(), ctx);

        InstanceModel instances = Models.get(InstanceModel.class);
        Row instance = instances.createEmptyRow();
        instance.set(InstanceModel.NAME, name.trim());
        instance.set(InstanceModel.KIND, template.get(InstanceTemplateModel.KIND));
        instance.set(InstanceModel.SETTINGS, placedSettings);
        instance.set(InstanceModel.TEMPLATE_ID, templateId);
        instance.set(InstanceModel.SERVER_ID, placement);
        if (environmentId != null) {
            // Validated against the pinned project owner by the ProjectGuards hook.
            instance.set(InstanceModel.ENVIRONMENT_ID, environmentId);
        }
        instance.set(InstanceModel.INSTALL_STATE, hasInstallStep(template)
            ? InstanceModel.INSTALL_PENDING : InstanceModel.INSTALL_NONE);
        // The plan's default for game templates: clean-exit-as-crash. Template-created
        // instances restart on any unobserved exit; the operator can flip it per record.
        instance.set(InstanceModel.CRASH_POLICY, InstanceModel.CRASH_RESTART);
        instances.save(instance);
        int instanceId = instance.get(InstanceModel.ID);

        // AIDEV-NOTE: ownership is planted BEFORE the variable and file writes, not after.
        // Those writes are per-record authored content and answer to the record's own
        // capabilities (TenantWrites gates instance variables on `config`), so a tenant
        // create that granted itself manage only at the end refused its OWN template
        // variables halfway through. The creator is the owner from the moment the row
        // exists; nothing between the save and here reads the grant.
        grantCreatorManage(instanceId, ctx);
        this.variables.writeForInstance(instanceId, declaredVariables(templateId), coerced);
        copyFiles(templateId, instanceId);
        completeOrDestroy(instanceId, () -> {
            copyVolumes(instanceId, templateVolumes);
            attachDeclaredDatabases(template, instance, placement, ctx);
        });
        return instanceId;
    }

    /**
     * Refuse a template volume declaration this create could not honour, BEFORE the
     * instance row exists -- the {@code TemplateDatabases.precheck} precedent.
     *
     * @throws Violations see {@link #requireVolumesDeclarable}
     */
    private static void requireVolumesCopyable(@Nullable InstanceKindHandler kindHandler,
                                               @NonNull List<Row> declared) {
        requireVolumesDeclarable(kindHandler, VolumeDeclaration.ofRows(declared));
    }

    /**
     * One template volume declaration, in the shape every surface that can author one
     * speaks: a stored row, a submitted admin form, an imported document.
     */
    public record VolumeDeclaration(@NonNull String name, @Nullable String containerPath,
                                    @Nullable Long quotaBytes, boolean exclusive) {

        /** @return the declaration a stored {@code instance_template_volumes} row carries */
        public static @NonNull VolumeDeclaration of(@NonNull Row volume) {
            return new VolumeDeclaration(
                String.valueOf((Object) volume.get(InstanceTemplateVolumeModel.NAME)),
                volume.get(InstanceTemplateVolumeModel.CONTAINER_PATH),
                volume.get(InstanceTemplateVolumeModel.QUOTA_BYTES),
                Boolean.TRUE.equals(volume.get(InstanceTemplateVolumeModel.EXCLUSIVE)));
        }

        public static @NonNull List<VolumeDeclaration> ofRows(@NonNull List<Row> volumes) {
            List<VolumeDeclaration> declared = new ArrayList<>(volumes.size());
            for (Row volume : volumes) {
                declared.add(of(volume));
            }
            return declared;
        }
    }

    /**
     * THE template-volume rule set, for every surface that can produce one: the admin
     * resource authoring a declaration, an import carrying one, the create copying one.
     *
     * AIDEV-NOTE: every rule here is asked of the DECLARING home rather than re-stated:
     * the name of {@link InstanceVolumes#requirePlainName}, the one-container-path-one-
     * directory rule of {@link InstanceVolumes#addMount}. The mount map is keyed by the
     * volume NAME instead of its host path, because the instance id the host path is
     * derived from does not exist yet -- and that refusal names its map keys, so it names
     * the two volumes either way.
     *
     * @throws Violations naming the volume: a kind that mounts none, a name that is not a
     *         directory name, a missing container path, two volumes at one path, or a
     *         quota no backend could apply
     */
    public static void requireVolumesDeclarable(@Nullable InstanceKindHandler kindHandler,
                                                @NonNull List<VolumeDeclaration> declared) {

        if (declared.isEmpty()) {
            return;
        }

        Map<String, String> paths = new LinkedHashMap<>();

        for (VolumeDeclaration volume : declared) {

            String name = volume.name();

            if (kindHandler == null || !kindHandler.supportsVolumes()) {
                throw Violations.ofField(InstanceTemplateVolumeModel.NAME.getName(), name,
                    violationText("template_volume_kind_unsupported").withArg("name", name));
            }

            InstanceVolumes.requirePlainName(name);
            String containerPath = volume.containerPath();

            // AIDEV-NOTE: the model's own required-ness closes the authoring path, so this
            // arm is a fail-closed backstop rather than a reachable refusal today. It
            // stays because a declaration with no container path is SKIPPED by the mount
            // derivation -- an instance would carry a volume nothing ever mounts, which is
            // the silent-empty shape this whole precheck exists to prevent.
            if (containerPath == null || containerPath.isBlank()) {
                throw Violations.ofField(
                    InstanceTemplateVolumeModel.CONTAINER_PATH.getName(), containerPath,
                    violationText("template_volume_container_path_required")
                        .withArg("name", name));
            }

            Long quota = volume.quotaBytes();

            if (quota != null && quota <= 0) {
                throw Violations.ofField(
                    InstanceTemplateVolumeModel.QUOTA_BYTES.getName(), quota,
                    violationText("volume_quota_invalid"));
            }

            // AIDEV-NOTE: the collision the mount map cannot see. It is keyed by the volume
            // NAME here, so a second declaration of one name overwrites the first instead
            // of conflicting -- and the copy funnels through InstanceVolumes.declare, which
            // re-declares that one name. Two rows would become ONE instance volume and the
            // create would report success, so the duplicate is refused by name.
            if (paths.containsKey(name)) {
                throw Violations.ofField(InstanceTemplateVolumeModel.NAME.getName(), name,
                    violationText("template_volume_name_taken").withArg("name", name));
            }

            InstanceVolumes.addMount(paths, name, containerPath);
        }
    }

    /**
     * Copy the template's volume declarations onto the instance.
     *
     * AIDEV-NOTE: the DECLARATIONS, which is the whole contract (phase-0 design 4.6). The
     * host directories behind them are minted by the first deploy through
     * {@link InstanceVolumes#mountsFor}, on the backend that host actually has -- a create
     * never touches a filesystem, and there is no template-side content to carry over
     * (a template's authored content is its {@code instance_template_files}).
     */
    private static void copyVolumes(int instanceId, @NonNull List<Row> declared) {
        for (Row volume : declared) {
            InstanceVolumes.declare(instanceId,
                volume.get(InstanceTemplateVolumeModel.NAME),
                volume.get(InstanceTemplateVolumeModel.CONTAINER_PATH),
                volume.get(InstanceTemplateVolumeModel.QUOTA_BYTES),
                Boolean.TRUE.equals(volume.get(InstanceTemplateVolumeModel.EXCLUSIVE)));
        }
    }

    /**
     * Run the post-write half of a create, destroying the instance when it refuses.
     *
     * AIDEV-NOTE: the residual lane only. Everything cheaply refusable is prechecked
     * above, so what lands here is a write that failed for its own reasons; a create that
     * kept a half-built record would be the silent-success shape this whole funnel exists
     * to avoid. A destroy that itself fails is logged BESIDE the original refusal rather
     * than replacing it -- the operator must read what the create refused.
     */
    private static void completeOrDestroy(int instanceId, @NonNull Runnable work) {
        try {
            work.run();
        } catch (RuntimeException | Error refused) {
            try {
                new InstanceService().destroy(instanceId);
            } catch (RuntimeException | Error compensation) {
                Blast.log("TEMPLATE: instance", instanceId, "kept after a refused create"
                    + " step; destroy failed:", compensation.getMessage());
            }
            throw refused;
        }
    }

    /**
     * Allocate and attach the template's declared databases on the instance's host.
     *
     * AIDEV-NOTE: the instance row is written FIRST and the databases after it, because a
     * database allocation schedules daemon work after commit while an instance row does
     * not: compensating a refused allocation is a destroy of a never-deployed record,
     * compensating a refused instance save would race a background provision. Inside the
     * panel's mutation transaction the rollback covers both anyway; the explicit destroy
     * is {@link #completeOrDestroy}'s, for the in-process lane.
     */
    private static void attachDeclaredDatabases(@NonNull Row template, @NonNull Row instance,
                                                int serverId, @Nullable AccessContext ctx) {
        int instanceId = instance.get(InstanceModel.ID);
        TemplateDatabases.link(instanceId, TemplateDatabases.allocate(template,
            instance.get(InstanceModel.NAME), serverId, ctx));
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
            RecordGrants.grant(GrantSubjectType.fromKey(subject.substring(0, separator)),
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
