package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.task.record.RecordSchedules;
import be.elevenways.zenit.server.task.schedule.CronExpression;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-instance schedules (nav-hidden; reached through an instance's Schedules tab).
 * Every save re-stamps run_as to the EDITOR, so the chain's later executions are
 * authorized against the last person who shaped the intent -- and editing a schedule
 * at all requires manage on its instance NOW.
 */
public class InstanceScheduleResource extends RowResource {

    /** The Schedules tab's quick-add entries; the instance rides along as a preset. */
    private static final QuickCreateSpec QUICK_CREATE = QuickCreateSpec
        .of(RecordScheduleModel.NAME.getName(), RecordScheduleModel.CRON.getName(),
            RecordScheduleModel.TIMEZONE.getName(), RecordScheduleModel.ENABLED.getName())
        .presets(RecordScheduleModel.RECORD_ID.getName());

    private final FormSpec formSpec = FormSpec.builder()
        .add(RecordScheduleModel.RECORD_ID)
        .add(RecordScheduleModel.NAME)
        .add(RecordScheduleModel.CRON)
        .add(RecordScheduleModel.TIMEZONE)
        .add(RecordScheduleModel.ENABLED)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        // "When does this run next" belongs to the schedule's name, and "why not" belongs
        // to the switch that is off -- a disabled reason in a column of its own was blank
        // on every healthy row.
        .column(ColumnSpec.fromField(RecordScheduleModel.NAME).filterable()
            .subtext("next_fire_at").build())
        .column(ColumnSpec.fromField(RecordScheduleModel.NEXT_FIRE_AT).hidden().build())
        .column(ColumnSpec.fromField(RecordScheduleModel.RECORD_ID).build())
        .column(ColumnSpec.fromField(RecordScheduleModel.CRON).copyable().build())
        .column(ColumnSpec.fromField(RecordScheduleModel.ENABLED).filterable()
            .subtext("disabled_reason").build())
        .column(ColumnSpec.fromField(RecordScheduleModel.DISABLED_REASON).hidden().build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_schedule"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance_schedule"); }
    @Override public @NonNull String slug() { return "instance-schedules"; }
    @Override public @NonNull Model model() { return Models.get(RecordScheduleModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    /** A schedule is found by its name or by the cron expression an operator remembers writing. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(RecordScheduleModel.NAME, RecordScheduleModel.CRON);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 18; }
    @Override public @NonNull Icon icon() { return Icon.of("clock"); }
    @Override public boolean showInNav() { return false; }

    /** This surface manages INSTANCE schedules only. */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> AccessDecision.allow(QueryPredicate.of(
            RecordScheduleModel.MODEL.eq(InstanceModel.MODEL_ID.toString())));
    }

    @Override
    public @Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("instances",
            row -> parseInstanceId(row.get(RecordScheduleModel.RECORD_ID))).tab("schedules");
    }

    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        List<RecordScopedPage<Row>> pages = new ArrayList<>(List.of(new InstanceScheduleStepsPage()));
        pages.addAll(this.frameworkSubpages());
        return pages;
    }

    /** The instance's Schedules tab links here with ?record_id= so the target is preset. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(formSpec().defaultValues());
        String recordId = conduit.getQueryParam("record_id");
        if (recordId != null && !recordId.isEmpty()) {
            values.put("record_id", recordId);
        }
        return Map.copyOf(values);
    }

    /**
     * The Schedules tab's quick-add bar; the instance rides along as a host-supplied
     * preset, and every add still passes {@link #validated} (target, cron, zone, the
     * CONFIG demand and the run_as stamp).
     *
     * AIDEV-NOTE: there is deliberately NO inline counterpart here. ENABLED makes the row
     * DUE for a sweeper that polls every minute, so one click fires a real chain against a
     * live instance within 60s -- that wants a form and a deliberate save. {@code validated}
     * is now partial-safe (it reads record_id, cron and timezone through the stored value),
     * so this is the ONE reason left; it is still enough. Note that any write here also
     * re-stamps run_as and clears disabled_reason, which is deliberate for a hand edit and
     * would be a surprise for a one-click cell.
     */
    @Override
    public @Nullable QuickCreateSpec quickCreate() {
        return QUICK_CREATE;
    }

    /**
     * The instance the bar schedules against: the {@code ?record_id=} prefill, else the
     * tab's own record. Rendered as a STRING because record schedules key their target
     * polymorphically.
     */
    @Override
    public @NonNull Map<String, Object> quickCreatePresetValues(@NonNull AccessContext accessContext) {
        Conduit conduit = accessContext.conduit();
        if (conduit == null) {
            return Map.of();
        }
        Integer instanceId = CmsSupport.scopedParentId(conduit,
            RecordScheduleModel.RECORD_ID.getName(), "instances");
        return instanceId != null
            ? Map.of(RecordScheduleModel.RECORD_ID.getName(), String.valueOf(instanceId))
            : Map.of();
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Map<String, Object> values = validated(coerced, null, accessContext);
        return super.persistRow(values, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> values = validated(coerced, existing, accessContext);
        super.updateRow(existing, values, accessContext);
    }

    /**
     * Edit and delete both demand {@code CONFIG} on the schedule's target instance
     * (the same question {@link #requireManage} enforces on every write), so the
     * synthesized affordances are offered on exactly that answer -- the
     * {@link InstanceDeviceResource} shape: read stays the WIDER {@code view} on the
     * delegated peer, and without this a view-only delegate was shown Edit/Delete
     * buttons the pipeline could only refuse.
     *
     * AIDEV-NOTE: reachesRecord here and in the run_now {@code visibleFor} below -- both
     * run once per RENDERED ROW. {@link #requireManage} reads IDENTICALLY and must keep
     * the fresh walk: it THROWS to gate a write, and the memo deliberately does not see a
     * grant written earlier in the same request. Render predicate above, write gate
     * below, same question, different face.
     */
    @Override
    public boolean writableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        return HohenheimAccess.reachesRecord(accessContext, InstanceModel.MODEL_ID,
            parseInstanceId(record.get(RecordScheduleModel.RECORD_ID)), HohenheimAccess.CONFIG);
    }

    /** Delete removes the chain and its run history with the schedule. */
    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        requireManage(accessContext, parseInstanceId(existing.get(RecordScheduleModel.RECORD_ID)));
        Integer id = existing.get(RecordScheduleModel.ID);
        ActivityLog.withAction(ActivityLog.ACTION_DELETE, "delete_schedule",
            () -> recordSchedules().deleteSchedule(id));
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        // Firing the chain off-schedule is shaping WHEN it runs, so it demands the same
        // CONFIG the schedule's own edits do -- visibleFor hides the button from a
        // view-only delegate AND refuses a direct POST (the dispatcher re-checks it on
        // invoke), and the handler asks once more because visibility alone is not a gate:
        // runNow itself authorizes execution against the stored run_as, never the invoker.
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "run_schedule"))
            .label(Microcopy.of("run_now").withFilter("scope", "instance_schedule"))
            .icon(Icon.of("play"))
            .visibleFor((row, ctx) -> Boolean.TRUE.equals(row.get(RecordScheduleModel.ENABLED))
                && HohenheimAccess.reachesRecord(ctx, InstanceModel.MODEL_ID,
                    parseInstanceId(row.get(RecordScheduleModel.RECORD_ID)),
                    HohenheimAccess.CONFIG))
            .handler((row, ctx) -> {
                requireManage(ctx.access(), parseInstanceId(row.get(RecordScheduleModel.RECORD_ID)));
                Row run = recordSchedules().runNow(row.get(RecordScheduleModel.ID));
                String status = run != null ? run.get(RecordScheduleRunModel.STATUS) : "busy";
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("run_finished").withFilter("scope", "instance_schedule")
                        .withArg("status", status));
            })
            .build());
        return actions;
    }

    /**
     * Validate target/cron/zone, demand manage on the instance, stamp run_as.
     *
     * AIDEV-NOTE: target, cron and zone are read through the STORED value when the write
     * does not carry the key. The inline cell lane hands updateRow a map holding EXACTLY
     * ONE entry, so reading record_id straight off it refused every partial write with
     * "unknown_instance" -- and reading cron off it refused with "invalid_cron" while
     * parsing the literal string "null".
     *
     * @param existing the stored schedule, or null on a create
     */
    private @NonNull Map<String, Object> validated(@NonNull Map<String, Object> coerced,
                                                   @Nullable Row existing,
                                                   @NonNull AccessContext accessContext) {
        Object recordId = CmsSupport.valueOf(coerced, existing, RecordScheduleModel.RECORD_ID);
        int instanceId = parseInstanceId(recordId);

        if (instanceId <= 0
                || Models.get(InstanceModel.class).find()
                    .where(InstanceModel.ID.eq(instanceId))
                    .where(InstanceModel.DELETED_AT.isNull()).count() == 0) {
            throw Violations.ofField("record_id", recordId,
                CmsSupport.violationText("unknown_instance"));
        }

        requireManage(accessContext, instanceId);

        Object cron = CmsSupport.valueOf(coerced, existing, RecordScheduleModel.CRON);
        try {
            CronExpression.parse(String.valueOf(cron));
        } catch (RuntimeException e) {
            throw Violations.ofField("cron", cron, CmsSupport.violationText("invalid_cron"));
        }

        Object timezone = CmsSupport.valueOf(coerced, existing, RecordScheduleModel.TIMEZONE);
        if (timezone instanceof String zone && !zone.isBlank()) {
            try {
                ZoneId.of(zone);
            } catch (RuntimeException e) {
                throw Violations.ofField("timezone", zone,
                    CmsSupport.violationText("invalid_timezone"));
            }
        }

        Map<String, Object> values = new LinkedHashMap<>(coerced);
        values.put("model", InstanceModel.MODEL_ID.toString());
        // The last editor's authority is what the chain executes under.
        values.put("run_as", accessContext.principalId());
        // A hand-edited schedule is trusted to fire again; the runtime re-disables
        // (with a fresh reason) if it is still structurally broken.
        values.put("disabled_reason", null);
        return values;
    }

    /**
     * A schedule declares what runs unattended, so authoring one is a CONFIG act.
     * No isAdmin prefix: the walk's own admin row already answers for operators.
     *
     * AIDEV-NOTE: the FRESH walk stays here on purpose. This reads exactly like
     * {@link #writableBy} (which answers off the request memo) but it THROWS to gate a
     * WRITE, and the memo's documented staleness rule -- a grant written earlier in this
     * request is not seen -- is correct for a render and wrong for a gate.
     */
    static void requireManage(@NonNull AccessContext accessContext, int instanceId) {
        if (HohenheimAccess.hasInstanceCapability(
                accessContext, instanceId, HohenheimAccess.CONFIG)) {
            return;
        }
        throw Violations.ofForm(CmsSupport.violationText("schedule_not_allowed"));
    }

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
