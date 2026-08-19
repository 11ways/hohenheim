package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Read-only run history of instance schedules: which chain ran, what each step did,
 * and why a failed one failed. Runs are evidence -- born from the executor, deletable
 * for cleanup, never edited.
 */
public class InstanceScheduleRunResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(RecordScheduleRunModel.SCHEDULE_ID)
        .add(RecordScheduleRunModel.RECORD_ID)
        .add(RecordScheduleRunModel.STATUS)
        .add(RecordScheduleRunModel.TRIGGER)
        .add(RecordScheduleRunModel.ERROR)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(RecordScheduleRunModel.SCHEDULE_ID).build())
        .column(ColumnSpec.fromField(RecordScheduleRunModel.RECORD_ID).build())
        .column(ColumnSpec.fromField(RecordScheduleRunModel.STATUS).filterable()
            .subtext("error").build())
        .column(ColumnSpec.fromField(RecordScheduleRunModel.ERROR)
            .label(Microcopy.of("error").withFilter("scope", "instance_schedule"))
            .hidden().build())
        .column(ColumnSpec.fromField(RecordScheduleRunModel.TRIGGER).build())
        .column(ColumnSpec.virtual("steps",
            Microcopy.of("steps").withFilter("scope", "instance_schedule")).build())
        .column(ColumnSpec.fromField(RecordScheduleRunModel.STARTED_AT).subtext("ended_at").build())
        .column(ColumnSpec.fromField(RecordScheduleRunModel.ENDED_AT).hidden().build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_schedule_run"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("runs").withFilter("scope", "instance_schedule"); }
    @Override public @NonNull String slug() { return "instance-schedule-runs"; }
    @Override public @NonNull Model model() { return Models.get(RecordScheduleRunModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    /** The failure text is the only thing a run says in words. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(RecordScheduleRunModel.ERROR);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 20; }
    @Override public @NonNull Icon icon() { return Icon.of("clock-rotate-left"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> AccessDecision.allow(QueryPredicate.of(
            RecordScheduleRunModel.MODEL.eq(InstanceModel.MODEL_ID.toString())));
    }

    /** Runs are born from the executor, never a form. */
    @Override
    public boolean creatable() { return false; }

    /** A run is immutable evidence. */
    @Override
    public boolean updatable() { return false; }

    /** Compact per-step verdicts so "which step failed and why" reads from the list. */
    @Override
    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {
        if (!"steps".equals(column.name())) {
            return super.cellValue(row, column);
        }
        return describeSteps(row);
    }

    @SuppressWarnings("unchecked")
    static @NonNull String describeSteps(@NonNull Row run) {
        Object raw = run.get(RecordScheduleRunModel.STEP_RESULTS);

        if (!(raw instanceof Map<?, ?> map)
                || !(map.get(RecordScheduleRunModel.KEY_STEPS) instanceof List<?> steps)
                || steps.isEmpty()) {
            return "";
        }

        StringBuilder summary = new StringBuilder();

        for (Object entry : steps) {
            if (!(entry instanceof Map<?, ?> step)) {
                continue;
            }
            if (summary.length() > 0) {
                summary.append(" | ");
            }
            summary.append(step.get(RecordScheduleRunModel.KEY_POSITION))
                .append(':').append(step.get(RecordScheduleRunModel.KEY_ACTION))
                .append('=').append(step.get(RecordScheduleRunModel.KEY_STATUS));
            Object error = step.get(RecordScheduleRunModel.KEY_ERROR);
            if (error != null) {
                summary.append(" (").append(error).append(')');
            }
        }

        return summary.toString();
    }
}
