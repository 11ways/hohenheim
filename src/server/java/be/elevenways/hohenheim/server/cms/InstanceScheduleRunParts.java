package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.InstanceChildDeletes;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.data.RowScope;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import be.elevenways.zenit.common.task.record.RecordScheduleRuns;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The read-only run history of instance schedules from parts: which chain ran, what each step did, and why a failed
 * one failed. Runs are evidence: born from the executor, deletable for cleanup, never edited.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceScheduleRunParts {

    /** The entry's slug. */
    public static final String SLUG = "instance-schedule-runs";

    /** The computed per-step verdict column. */
    private static final String STEPS = "steps";

    private InstanceScheduleRunParts() {
    }

    /** @return the operator's run history of every instance schedule */
    public static @NonNull PanelResource<Row> admin() {
        FormSpec form = FormSpec.builder()
            .add(RecordScheduleRunModel.SCHEDULE_ID)
            .add(RecordScheduleRunModel.RECORD_ID)
            .add(RecordScheduleRunModel.STATUS)
            .add(RecordScheduleRunModel.TRIGGER)
            .add(RecordScheduleRunModel.ERROR)
            .build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(RecordScheduleRunModel.SCHEDULE_ID).build())
            .column(ColumnSpec.fromField(RecordScheduleRunModel.RECORD_ID).build())
            .column(ColumnSpec.fromField(RecordScheduleRunModel.STATUS).filterable().subtext("error").build())
            .column(ColumnSpec.fromField(RecordScheduleRunModel.ERROR)
                .label(Microcopy.of("error").withFilter("scope", "instance_schedule"))
                .hidden().build())
            .column(ColumnSpec.fromField(RecordScheduleRunModel.TRIGGER).build())
            .column(ColumnSpec.virtual(STEPS, Microcopy.of("steps").withFilter("scope", "instance_schedule")).build())
            .column(ColumnSpec.fromField(RecordScheduleRunModel.STARTED_AT).subtext("ended_at").build())
            .column(ColumnSpec.fromField(RecordScheduleRunModel.ENDED_AT).hidden().build())
            .build();
        return PanelResource.builder(HohenheimIds.id("instance_schedule_run"), SLUG,
                SubjectType.record(RecordScheduleRunModel.MODEL_ID))
            .label(Microcopy.of("runs").withFilter("scope", "instance_schedule"))
            .recordLabel(Microcopy.of("run").withFilter("scope", "instance_schedule"))
            .icon(Icon.of("clock-rotate-left"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(20)
            .showInNav(false)
            // The run table is zenit's, shared by every host model's schedules: this entry is the instances' runs.
            .scope(RowScope.within(() -> RecordScheduleRunModel.MODEL.eq(InstanceModel.MODEL_ID.toString())))
            // Compact per-step verdicts, so "which step failed and why" reads from the list.
            .reads(ResourceReads.rows()
                .mapCells((run, column) -> STEPS.equals(column.name()) ? describeSteps(run) : null)
                .title(InstanceScheduleRunParts::title))
            .form(ResourceForm.<Row>of(form).build())
            // The failure text is the only thing a run says in words.
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).search(RecordScheduleRunModel.ERROR).build())
            // Runs are born from the executor and never edited; deleting one is cleanup.
            .writes(ResourceMutations.rows().delete(InstanceChildDeletes.SCHEDULE_RUN).build())
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /** @return each step's position, action, status and error, the one summary the list and the Steps tab share */
    static @NonNull String describeSteps(@NonNull Row run) {
        StringBuilder summary = new StringBuilder();
        for (RecordScheduleRuns.Step step : RecordScheduleRuns.steps(run)) {
            if (summary.length() > 0) {
                summary.append(" | ");
            }
            summary.append(step.position())
                .append(':').append(step.action())
                .append('=').append(step.status() == null ? null : step.status().storageKey());
            if (step.error() != null) {
                summary.append(" (").append(step.error()).append(')');
            }
        }
        return summary.toString();
    }

    /**
     * A run has nothing but its outcome and when it happened, so it is titled by the outcome plus the run's own
     * number: two runs of one schedule differ by nothing else a heading can carry.
     */
    private static @Nullable String title(@NonNull Row run) {
        String status = CmsSupport.enumLabel(RecordScheduleRunModel.STATUS, run.get(RecordScheduleRunModel.STATUS));
        String title = status == null ? null : CmsSupport.resolvedText(
            Microcopy.of("run_title").withFilter("scope", "instance_schedule")
                .withArg("id", String.valueOf((Object) run.get(RecordScheduleRunModel.ID)))
                .withArg("status", status));
        return title != null && !title.isBlank() ? title : null;
    }
}
