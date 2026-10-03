package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.instance.InstanceScheduleOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceAuthority;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.resource.RowSave;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.ScheduleStepForms;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.RegistryMemberField;
import be.elevenways.zenit.common.orm.field.TypeDefinition;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.task.record.RecordScheduleActions;
import be.elevenways.zenit.server.task.record.RecordSchedules;
import be.elevenways.zenit.server.task.record.SchedulePlacements;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The chain steps of an instance schedule from shared parts, admin and tenant twins, nav-hidden and reached through a
 * schedule's Steps tab.
 *
 * AIDEV-NOTE: saving a step demands CONFIG on the schedule's instance AND the action's OWN capability on it NOW
 * (scheduling an action requires the capability the action needs: the Pterodactyl lesson), and hands the chain to the
 * editor (RecordSchedules.chainEditedBy): whoever last shaped the chain is whose authority it executes under. The
 * /manage twin reads a step exactly when its schedule is visible (TenantScopes.INSTANCE_SCHEDULE_STEPS).
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceScheduleStepParts {

    /** Both twins' slug. */
    public static final String SLUG = "instance-schedule-steps";

    /** Request-scoped memo of schedule rows, keyed by schedule id (render-only reads). */
    private static final IdentifierKey<Map<Integer, Row>> SCHEDULE_ROWS =
        IdentifierKey.of("hohenheim", "schedule_step_schedule_rows");

    private InstanceScheduleStepParts() {
    }

    /** @return the operator's steps, with the history tab */
    public static @NonNull PanelResource<Row> admin() {
        return base(HohenheimIds.id("instance_schedule_step"))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /** @return the tenant's steps, visible exactly when their schedule is */
    public static @NonNull PanelResource<Row> manage() {
        return base(HohenheimIds.id("manage_instance_schedule_step"))
            .scope(TenantScopes.INSTANCE_SCHEDULE_STEPS)
            .tabs(ResourceTabs.<Row>none().withContributions())
            .build();
    }

    private static PanelResource.@NonNull Builder<Row> base(@NonNull Identifier id) {
        FormSpec form = FormSpec.builder()
            .add(RecordScheduleStepModel.SCHEDULE_ID)
            .add(RecordScheduleStepModel.POSITION)
            .add(ScheduleStepForms.action())
            .add(ScheduleStepForms.input())
            .add(RecordScheduleStepModel.OFFSET_SECONDS)
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(RecordScheduleStepModel.FAILURE_POLICY))
            .add(RecordScheduleStepModel.RETRY_LIMIT)
            .build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(RecordScheduleStepModel.SCHEDULE_ID).build())
            .column(ColumnSpec.fromField(RecordScheduleStepModel.POSITION).build())
            .column(ColumnSpec.fromField(RecordScheduleStepModel.ACTION).subtext("offset_seconds").build())
            .column(ColumnSpec.fromField(RecordScheduleStepModel.OFFSET_SECONDS).hidden().build())
            .column(ColumnSpec.fromField(RecordScheduleStepModel.FAILURE_POLICY).build())
            .build();
        return PanelResource.builder(id, SLUG, InstanceScheduleOperations.STEP)
            .label(Microcopy.of("plural").withFilter("scope", "schedule_step"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "schedule_step"))
            .icon(Icon.of("list-ol"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(19)
            .showInNav(false)
            .parent(ResourceParent.of(InstanceScheduleParts.SLUG, RecordScheduleStepModel.SCHEDULE_ID)
                .tab(InstanceScheduleStepsPage.SLUG))
            // A step IS its action; the label is read off the action registry's own declaration.
            .reads(ResourceReads.rows().title(step -> {
                String action = CmsSupport.enumLabel(RecordScheduleStepModel.ACTION,
                    step.get(RecordScheduleStepModel.ACTION));
                return action != null && !action.isBlank() ? action : null;
            }))
            .form(ResourceForm.<Row>of(form)
                // A step belongs to the schedule it was created in: the form renders it read-only on an existing
                // step and authorize() refuses a write that still tries to move it.
                .bindings(List.of(ResourceFieldBinding.of(RecordScheduleStepModel.SCHEDULE_ID.getName(),
                    FieldAccess.customRecordAware((ctx, record) -> record == null
                        ? FieldAccess.Decision.EDITABLE : FieldAccess.Decision.READONLY))))
                .createDefaults(request -> {
                    Map<String, Object> values = new LinkedHashMap<>(form.defaultValues());
                    Integer scheduleId = CmsSupport.prefill(request.conduit(), HohenheimParams.SCHEDULE_ID_PREFILL);
                    if (scheduleId != null) {
                        values.put(RecordScheduleStepModel.SCHEDULE_ID.getName(), scheduleId);
                    }
                    return Map.copyOf(values);
                })
                .build())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).build())
            .writes(ResourceMutations.rows().create().update().delete(InstanceScheduleOperations.DELETE_STEP)
                .beforeSave(save -> authorize(save))
                .afterSave(save -> RecordSchedules.chainEditedBy(
                    save.row().get(RecordScheduleStepModel.SCHEDULE_ID), save.access()))
                .build())
            .authority(ResourceAuthority.<Row>builder()
                .create(null, InstanceScheduleStepParts::creatableBy)
                .update(null, InstanceScheduleStepParts::writableBy)
                .build());
    }

    /**
     * Adding a step shapes the chain, so the create affordance follows CONFIG on the schedule's instance.
     *
     * AIDEV-NOTE: the AFFORDANCE face. Create is record-less, so the schedule is read off the request (the
     * {@code ?schedule_id=} prefill, else the schedule whose Steps tab is rendering); where the request names none (a
     * bare create submit) this answers true and {@link #authorize} is the enforced gate. A named schedule that does
     * not exist, or is not an instance schedule, offers nothing.
     */
    static boolean creatableBy(@NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        if (conduit == null) {
            return true;
        }
        Integer scheduleId = CmsSupport.scopedParentId(conduit, HohenheimParams.SCHEDULE_ID_PREFILL.getName(),
            InstanceScheduleParts.SLUG);
        if (scheduleId == null) {
            return true;
        }
        Row schedule = scheduleForRender(access, scheduleId);
        if (schedule == null || !InstanceModel.MODEL_ID.toString().equals(schedule.get(RecordScheduleModel.MODEL))) {
            return false;
        }
        return InstanceScheduleParts.writableBy(schedule, access);
    }

    /**
     * Edit and delete demand CONFIG on the parent schedule's instance (an edit additionally the action's own
     * capability, inside {@link #authorize}), so the affordances are offered on the shared floor of those gates.
     *
     * AIDEV-NOTE: reachesRecord, never hasInstanceCapability: this runs once per RENDERED ROW, and the schedule row
     * rides a request memo for the same reason. The write paths keep the fresh load.
     */
    public static boolean writableBy(@NonNull Row step, @NonNull AccessContext access) {
        Row schedule = scheduleForRender(access, step.get(RecordScheduleStepModel.SCHEDULE_ID));
        return schedule != null && InstanceScheduleParts.writableBy(schedule, access);
    }

    /** The render-side schedule lookup: one load per DISTINCT schedule per request. */
    private static @Nullable Row scheduleForRender(@NonNull AccessContext access, @Nullable Integer scheduleId) {
        if (scheduleId == null) {
            return null;
        }
        Conduit conduit = access.conduit();
        if (conduit == null) {
            return loadSchedule(scheduleId);
        }
        Map<Integer, Row> cache = conduit.getAttribute(SCHEDULE_ROWS);
        if (cache == null) {
            cache = new LinkedHashMap<>();
            try {
                conduit.setAttribute(SCHEDULE_ROWS, cache);
            } catch (UnsupportedOperationException attributeless) {
                // A conduit without attribute storage just pays the load each call.
            }
        }
        if (cache.containsKey(scheduleId)) {
            return cache.get(scheduleId);
        }
        Row schedule = loadSchedule(scheduleId);
        cache.put(scheduleId, schedule);
        return schedule;
    }

    /**
     * The write-time half of per-step authorization: the schedule must exist and target an instance, a stored step
     * stays in its schedule, and the EDITOR must hold CONFIG on that instance AND the selected action's own
     * capability now.
     *
     * AIDEV-NOTE: the row holds the stored values under the submitted ones, so a partial write (the inline cell lane
     * submits ONE entry) is judged on what the row will hold. A step's schedule is compared against the STORED one:
     * an edit repointing a step at ANOTHER schedule would hand that chain a step never checked for its CONFIG.
     *
     * @throws Violations anchored on the field that cannot be saved
     */
    private static void authorize(@NonNull RowSave save) {
        Row step = save.row();
        Object scheduleId = step.get(RecordScheduleStepModel.SCHEDULE_ID);
        if (!save.isCreate()) {
            Row stored = Models.get(RecordScheduleStepModel.class).findById(save.key());
            if (stored != null && !Objects.equals(scheduleId, stored.get(RecordScheduleStepModel.SCHEDULE_ID))) {
                throw Violations.ofField("schedule_id", scheduleId,
                    CmsSupport.violationText("schedule_step_schedule_fixed"));
            }
        }
        Row schedule = scheduleId instanceof Integer id ? loadSchedule(id) : null;
        if (schedule == null || !InstanceModel.MODEL_ID.toString().equals(schedule.get(RecordScheduleModel.MODEL))) {
            throw Violations.ofField("schedule_id", scheduleId, CmsSupport.violationText("unknown_schedule"));
        }
        String recordId = schedule.get(RecordScheduleModel.RECORD_ID);
        InstanceScheduleParts.requireManage(save.access(), InstanceScheduleParts.parseInstanceId(recordId));
        Object action = step.get(RecordScheduleStepModel.ACTION);
        String refusal = actionRefusal(save.access(), recordId, action instanceof String key ? key : null);
        if (refusal != null) {
            throw Violations.ofField("action", action, CmsSupport.violationText("schedule_action_" + refusal));
        }
    }

    /**
     * Why the editor may not put this action on the instance's chain, or null: an operation is asked what the pipeline
     * would offer the editor on that instance now (its gate's authorization half), a legacy action its declared
     * capability.
     *
     * @return the refusal token, the same vocabulary for both
     */
    @SuppressWarnings("removal")
    private static @Nullable String actionRefusal(@NonNull AccessContext editor, @NonNull String recordId,
                                                 @Nullable String action) {
        TypeDefinition member = ((RegistryMemberField) RecordScheduleStepModel.ACTION).memberFor(action);
        if (!(member instanceof Operation<?, ?, ?> operation)) {
            return RecordScheduleActions.editRefusal(editor, InstanceModel.MODEL_ID, recordId, action);
        }
        if (SchedulePlacements.find(operation.id()) == null) {
            return RecordScheduleActions.REFUSAL_UNKNOWN_ACTION;
        }
        if (operation.subjectType() == null
                || !InstanceModel.MODEL_ID.equals(operation.subjectType().modelId())) {
            return RecordScheduleActions.REFUSAL_MODEL_MISMATCH;
        }
        Row instance = Models.get(InstanceModel.class).findById(InstanceScheduleParts.parseInstanceId(recordId));
        if (instance == null) {
            return RecordScheduleActions.REFUSAL_UNKNOWN_ACTION;
        }
        @SuppressWarnings("unchecked")
        Operation<Row, ?, ?> onInstance = (Operation<Row, ?, ?>) operation;
        return OperationPipeline.offer(onInstance, editor, instance) instanceof OperationPipeline.Offer.Hidden
            ? RecordScheduleActions.REFUSAL_CAPABILITY_DENIED : null;
    }

    private static @Nullable Row loadSchedule(@Nullable Integer scheduleId) {
        if (scheduleId == null) {
            return null;
        }
        return Models.get(RecordScheduleModel.class).find().where(RecordScheduleModel.ID.eq(scheduleId)).first();
    }
}
