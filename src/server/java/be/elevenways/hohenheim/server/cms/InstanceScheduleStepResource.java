package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.task.record.RecordScheduleActions;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chain steps of an instance schedule (nav-hidden; reached through the schedule's
 * form). Saving a step demands the ACTION'S OWN capability on the target instance
 * NOW (scheduling an action requires the capability the action needs -- the
 * Pterodactyl lesson), and re-stamps the parent schedule's run_as to the editor:
 * whoever last shaped the chain is whose authority it executes under.
 */
public class InstanceScheduleStepResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(RecordScheduleStepModel.SCHEDULE_ID)
        .add(RecordScheduleStepModel.POSITION)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(RecordScheduleStepModel.ACTION))
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(RecordScheduleStepModel.PAYLOAD))
        .add(RecordScheduleStepModel.OFFSET_SECONDS)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(RecordScheduleStepModel.FAILURE_POLICY))
        .add(RecordScheduleStepModel.RETRY_LIMIT)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(RecordScheduleStepModel.SCHEDULE_ID).build())
        .column(ColumnSpec.fromField(RecordScheduleStepModel.POSITION).build())
        .column(ColumnSpec.fromField(RecordScheduleStepModel.ACTION).subtext("offset_seconds").build())
        .column(ColumnSpec.fromField(RecordScheduleStepModel.OFFSET_SECONDS).hidden().build())
        .column(ColumnSpec.fromField(RecordScheduleStepModel.FAILURE_POLICY).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_schedule_step"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "schedule_step"); }
    @Override public @NonNull String slug() { return "instance-schedule-steps"; }
    @Override public @NonNull Model model() { return Models.get(RecordScheduleStepModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 19; }
    @Override public @NonNull Icon icon() { return Icon.of("list-ol"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("instance-schedules",
            row -> row.get(RecordScheduleStepModel.SCHEDULE_ID)).tab("steps");
    }

    /** The schedule's Steps list links here with ?schedule_id= preset. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(formSpec().defaultValues());
        String scheduleId = conduit.getQueryParam("schedule_id");
        if (scheduleId != null && !scheduleId.isEmpty()) {
            try {
                values.put("schedule_id", Integer.parseInt(scheduleId));
            } catch (NumberFormatException ignored) {
                // Malformed prefill: render the bare form.
            }
        }
        return Map.copyOf(values);
    }

    /**
     * Edit and delete both demand {@code CONFIG} on the parent schedule's instance
     * (delete enforces exactly that via {@link InstanceScheduleResource#requireManage};
     * an edit additionally demands the chosen ACTION's own capability inside
     * {@link #authorize}), so the synthesized affordances are offered on the shared
     * floor of those gates instead of lying to a view-only delegate. The
     * {@link InstanceDeviceResource} shape.
     */
    @Override
    public boolean writableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        Row schedule = loadSchedule(record.get(RecordScheduleStepModel.SCHEDULE_ID));
        if (schedule == null) {
            return false;
        }
        return HohenheimAccess.hasInstanceCapability(accessContext,
            InstanceScheduleResource.parseInstanceId(schedule.get(RecordScheduleModel.RECORD_ID)),
            HohenheimAccess.CONFIG);
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Row schedule = authorize(coerced, null, accessContext);
        Object id = super.persistRow(coerced, accessContext);
        restampAuthority(schedule, accessContext);
        return id;
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Row schedule = authorize(coerced, existing, accessContext);
        super.updateRow(existing, coerced, accessContext);
        restampAuthority(schedule, accessContext);
    }

    /** Removing a step is shaping the chain too: same gate, same authority re-stamp. */
    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        Row schedule = loadSchedule(existing.get(RecordScheduleStepModel.SCHEDULE_ID));
        if (schedule != null) {
            int instanceId = InstanceScheduleResource.parseInstanceId(
                schedule.get(RecordScheduleModel.RECORD_ID));
            InstanceScheduleResource.requireManage(accessContext, instanceId);
        }
        super.deleteRow(existing, accessContext);
        if (schedule != null) {
            restampAuthority(schedule, accessContext);
        }
    }

    /**
     * The edit-time half of per-step authorization: the schedule must exist and target
     * an instance, and the EDITOR must hold the selected action's own capability on
     * that instance now.
     *
     * AIDEV-NOTE: both reads take the STORED value when the write does not carry the key.
     * The inline cell lane hands updateRow a map holding EXACTLY ONE entry, so reading
     * schedule_id straight off it refused every partial write with "unknown_schedule" --
     * naming a chain the operator never repointed.
     *
     * @param existing the stored step, or null on a create
     */
    private @NonNull Row authorize(@NonNull Map<String, Object> coerced,
                                   @Nullable Row existing,
                                   @NonNull AccessContext accessContext) {
        Object scheduleId = CmsSupport.valueOf(coerced, existing,
            RecordScheduleStepModel.SCHEDULE_ID);
        Row schedule = scheduleId instanceof Integer id ? loadSchedule(id) : null;

        if (schedule == null
                || !InstanceModel.MODEL_ID.toString().equals(schedule.get(RecordScheduleModel.MODEL))) {
            throw Violations.ofField("schedule_id", scheduleId,
                CmsSupport.violationText("unknown_schedule"));
        }

        String recordId = schedule.get(RecordScheduleModel.RECORD_ID);
        Object action = CmsSupport.valueOf(coerced, existing, RecordScheduleStepModel.ACTION);
        String refusal = RecordScheduleActions.editRefusal(accessContext,
            InstanceModel.MODEL_ID, recordId,
            action instanceof String key ? key : null);

        if (refusal != null) {
            throw Violations.ofField("action", action,
                CmsSupport.violationText("schedule_action_" + refusal));
        }

        return schedule;
    }

    /** The last editor of the chain owns the intent its executions are checked against. */
    private static void restampAuthority(@NonNull Row schedule,
                                         @NonNull AccessContext accessContext) {
        Models.get(RecordScheduleModel.class).find()
            .where(RecordScheduleModel.ID.eq(schedule.get(RecordScheduleModel.ID)))
            .assign(RecordScheduleModel.RUN_AS, accessContext.principalId())
            .updateAll();
    }

    private static @Nullable Row loadSchedule(@Nullable Integer scheduleId) {
        if (scheduleId == null) {
            return null;
        }
        return Models.get(RecordScheduleModel.class).find()
            .where(RecordScheduleModel.ID.eq(scheduleId)).first();
    }
}
