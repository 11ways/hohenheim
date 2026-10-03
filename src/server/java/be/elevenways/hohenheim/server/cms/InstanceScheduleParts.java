package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.InstanceScheduleOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
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
import be.elevenways.zenit.common.data.RowScope;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.task.record.RecordSchedules;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.DateTimeException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The instance schedule entries from shared parts: the operator's on /admin and the tenant's on /manage, nav-hidden
 * and reached through an instance's Schedules tab.
 *
 * AIDEV-NOTE: every save makes the EDITOR own the chain (RecordSchedules.editedBy stamps run_as and re-arms written
 * timing), so later executions are authorized against the last person who shaped the intent, and authoring one at
 * all demands CONFIG on its instance NOW. The /manage twin narrows the read to the schedules of instances the caller
 * may view (TenantScopes.INSTANCE_SCHEDULES); every write already demands CONFIG.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceScheduleParts {

    /** Both twins' slug, shared with the step entry's parent link. */
    public static final String SLUG = "instance-schedules";

    /** INSTANCE schedules only; the /manage scope narrows this same base per principal. */
    public static final RowScope ROWS = RowScope.within(
        () -> RecordScheduleModel.MODEL.eq(InstanceModel.MODEL_ID.toString()));

    /** The Schedules tab's quick-add entries; the instance rides along as a preset. */
    private static final QuickCreateSpec QUICK_CREATE = QuickCreateSpec
        .of(RecordScheduleModel.NAME.getName(), RecordScheduleModel.CRON.getName(),
            RecordScheduleModel.TIMEZONE.getName(), RecordScheduleModel.ENABLED.getName())
        .presets(RecordScheduleModel.RECORD_ID.getName());

    private InstanceScheduleParts() {
    }

    /** @return the operator's instance schedules, with the history tab */
    public static @NonNull PanelResource<Row> admin() {
        return base(HohenheimIds.id("instance_schedule"))
            .scope(ROWS)
            .tabs(ResourceTabs.<Row>of(List.of(new InstanceScheduleStepsPage())).withHistory().withContributions())
            .build();
    }

    /** @return the tenant's instance schedules; the admin history stays off the delegated surface */
    public static @NonNull PanelResource<Row> manage() {
        return base(HohenheimIds.id("manage_instance_schedule"))
            .scope(TenantScopes.INSTANCE_SCHEDULES)
            .tabs(ResourceTabs.<Row>of(List.of(new InstanceScheduleStepsPage())).withContributions())
            .build();
    }

    private static PanelResource.@NonNull Builder<Row> base(@NonNull Identifier id) {
        FormSpec form = FormSpec.builder()
            // The target INSTANCE, picked by name through the instances record source (so a delegate only sees
            // instances their scope reaches) and prefilled by the tab; the stored record_id stays the polymorphic
            // STRING the schedule mechanism keys on, the source coercing the picked id both ways.
            .add(RelationPick.of(RecordScheduleModel.RECORD_ID, InstanceModel.MODEL_ID)
                .clearable(false).creatable(false).build())
            .add(RecordScheduleModel.NAME)
            .add(RecordScheduleModel.CRON)
            .add(RecordScheduleModel.TIMEZONE)
            .add(RecordScheduleModel.ENABLED)
            .build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            // "When does this run next" belongs to the schedule's name, and "why not" to the switch that is off.
            .column(ColumnSpec.fromField(RecordScheduleModel.NAME).filterable().subtext("next_fire_at").build())
            .column(ColumnSpec.fromField(RecordScheduleModel.NEXT_FIRE_AT).hidden().build())
            .column(ColumnSpec.fromField(RecordScheduleModel.RECORD_ID).build())
            .column(ColumnSpec.fromField(RecordScheduleModel.CRON).copyable().build())
            .column(ColumnSpec.fromField(RecordScheduleModel.ENABLED).filterable().subtext("disabled_reason").build())
            .column(ColumnSpec.fromField(RecordScheduleModel.DISABLED_REASON).hidden().build())
            .build();
        return PanelResource.builder(id, SLUG, InstanceScheduleOperations.SCHEDULE)
            .label(Microcopy.of("plural").withFilter("scope", "instance_schedule"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "instance_schedule"))
            .icon(Icon.of("clock"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(18)
            .showInNav(false)
            // A record schedule's owner is polymorphic (model + record id); this panel's schedules are instances'.
            .parent(ResourceParent.of(HohenheimSlugs.INSTANCES, RecordScheduleModel.RECORD_ID, RecordScheduleModel.MODEL)
                .tab("schedules"))
            .reads(ResourceReads.rows())
            .form(ResourceForm.<Row>of(form)
                // The target instance is chosen once: an existing schedule never moves to another instance (every
                // grant, step capability and run row was checked against this one).
                .bindings(List.of(ResourceFieldBinding.of(RecordScheduleModel.RECORD_ID.getName(),
                    FieldAccess.customRecordAware((ctx, record) -> record == null
                        ? FieldAccess.Decision.EDITABLE : FieldAccess.Decision.READONLY))))
                .createDefaults(request -> createDefaults(form, request.conduit()))
                // AIDEV-NOTE: deliberately NO inline cell: ENABLED makes the row DUE for a sweeper polling every
                // minute, so one click would fire a real chain against a live instance within 60s, and every write
                // re-stamps run_as. That wants a form and a deliberate save.
                .quickCreate(QUICK_CREATE)
                .quickCreatePresets(InstanceScheduleParts::quickCreatePresets)
                // A schedule's front door is its chain: a fresh schedule runs NOTHING until it has a step.
                .landingTab(InstanceScheduleStepsPage.SLUG)
                .build())
            // A schedule is found by its name or by the cron expression an operator remembers writing.
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL)
                .search(RecordScheduleModel.NAME, RecordScheduleModel.CRON).build())
            .writes(ResourceMutations.rows().create().update().delete(InstanceScheduleOperations.DELETE_SCHEDULE)
                .beforeSave(InstanceScheduleParts::authored)
                .build())
            // Edit demands CONFIG on the target instance, the same question every write asks; a view-only delegate
            // is offered no edit (read stays the wider VIEW on the delegated twin). Delete answers through its
            // operation's authorizer.
            .authority(ResourceAuthority.<Row>builder().update(null, InstanceScheduleParts::writableBy).build())
            .actions(List.of(runNow()));
    }

    /** The instance's Schedules tab links here with ?record_id= so the target is preset, as a STRING. */
    private static @NonNull Map<String, Object> createDefaults(@NonNull FormSpec form, @NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(form.defaultValues());
        Integer instanceId = CmsSupport.prefill(conduit, HohenheimParams.RECORD_ID_PREFILL);
        if (instanceId != null) {
            values.put(RecordScheduleModel.RECORD_ID.getName(), String.valueOf(instanceId));
        }
        return Map.copyOf(values);
    }

    /** The instance the quick-add bar schedules against: the prefill, else the tab's own record. */
    private static @NonNull Map<String, Object> quickCreatePresets(@NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        if (conduit == null) {
            return Map.of();
        }
        Integer instanceId = CmsSupport.scopedParentId(conduit, HohenheimParams.RECORD_ID_PREFILL.getName(),
            HohenheimSlugs.INSTANCES);
        return instanceId != null
            ? Map.of(RecordScheduleModel.RECORD_ID.getName(), String.valueOf(instanceId))
            : Map.of();
    }

    /**
     * Validates the target, demands CONFIG on it, and hands the chain to its editor through core's schedule API.
     *
     * AIDEV-NOTE: the row already holds the stored values under the submitted ones, so a partial write is judged on
     * what the row will hold. The target MODEL is a column no form entry backs; it is staged here.
     *
     * @throws Violations anchored on the field that cannot be scheduled
     */
    private static void authored(@NonNull RowSave save) {
        Row schedule = save.row();
        Object recordId = schedule.get(RecordScheduleModel.RECORD_ID);
        int instanceId = parseInstanceId(recordId);
        if (instanceId <= 0
                || Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(instanceId)).count() == 0) {
            throw Violations.ofField("record_id", recordId, CmsSupport.violationText("unknown_instance"));
        }
        requireManage(save.access(), instanceId);
        schedule.set(RecordScheduleModel.MODEL, InstanceModel.MODEL_ID.toString());
        try {
            RecordSchedules.editedBy(schedule, save.access());
        } catch (DateTimeException unknownZone) {
            throw Violations.ofField("timezone", schedule.get(RecordScheduleModel.TIMEZONE),
                CmsSupport.violationText("invalid_timezone"));
        } catch (IllegalArgumentException unparseable) {
            throw Violations.ofField("cron", schedule.get(RecordScheduleModel.CRON),
                CmsSupport.violationText("invalid_cron"));
        }
    }

    /**
     * Whether the caller may shape this schedule: CONFIG on its instance, the render face.
     *
     * AIDEV-NOTE: reachesRecord, which answers off the request memo, because this runs once per RENDERED ROW;
     * {@link #requireManage} reads identically but keeps the FRESH walk, because it throws to gate a write and the
     * memo deliberately does not see a grant written earlier in the same request.
     */
    public static boolean writableBy(@NonNull Row schedule, @NonNull AccessContext access) {
        return isInstanceSchedule(schedule) && HohenheimAccess.reachesRecord(access, InstanceModel.MODEL_ID,
            parseInstanceId(schedule.get(RecordScheduleModel.RECORD_ID)), HohenheimAccess.CONFIG);
    }

    /** Run the chain off-cron, offered on an enabled schedule to a CONFIG holder. */
    private static @NonNull PanelAction<Row> runNow() {
        return PanelAction.<Row, String>places(InstanceScheduleOperations.RUN_SCHEDULE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(Microcopy.of("run_finished")
                    .withFilter("scope", "instance_schedule").withArg("status", result.value())))
            .label(Microcopy.of("run_now").withFilter("scope", "instance_schedule"))
            .icon(Icon.of("play"))
            .build();
    }

    /**
     * A schedule declares what runs unattended, so authoring one is a CONFIG act; no isAdmin prefix, the walk's own
     * admin row answers for operators.
     *
     * @throws Violations naming the refusal
     */
    static void requireManage(@NonNull AccessContext access, int instanceId) {
        if (HohenheimAccess.hasInstanceCapability(access, instanceId, HohenheimAccess.CONFIG)) {
            return;
        }
        throw Violations.ofForm(CmsSupport.violationText("schedule_not_allowed"));
    }

    /**
     * Whether the schedule is an instance's. The table also holds other models' schedules (a preview's expiry) whose
     * record id may parse as an instance id; no instance gate may judge those.
     */
    static boolean isInstanceSchedule(@Nullable Row schedule) {
        return schedule != null && InstanceModel.MODEL_ID.toString().equals(schedule.get(RecordScheduleModel.MODEL));
    }

    /** @return the instance id a schedule's polymorphic record id names, -1 when it names none */
    static int parseInstanceId(@Nullable Object recordId) {
        try {
            return Integer.parseInt(String.valueOf(recordId));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static @NonNull RecordSchedules recordSchedules() {
        return new RecordSchedules(Db.currentOrDefault());
    }
}
