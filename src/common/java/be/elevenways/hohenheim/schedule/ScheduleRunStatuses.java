package be.elevenways.hohenheim.schedule;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The presentation facts for a record-schedule run status: one label, icon and colour per
 * status the framework can store.
 *
 * AIDEV-NOTE: {@code RecordScheduleRunModel.STATUS} is a plain StringField, so the six
 * statuses arrive as raw strings with no display facts attached. The schedule-steps tab
 * used to answer that with {@code status == "completed" ? "success" : "destructive"},
 * which painted a RUNNING chain red and made "completed with failures" and "aborted"
 * indistinguishable from an outright failure. This table is the ONE place hohenheim
 * classifies those strings, and {@code ScheduleRunStatusDriftTest} fails the build when
 * the framework declares a status it does not cover.
 */
public final class ScheduleRunStatuses {

    /**
     * The status vocabulary as an EnumField, so consumers render it through the SAME
     * EnumBadgeState path every stored enum column uses.
     */
    public static final EnumField FIELD = EnumField.builder("status")
        .value(RecordScheduleRunModel.STATUS_RUNNING, value -> value
            .displayName("Running").label(label(RecordScheduleRunModel.STATUS_RUNNING))
            .icon("rotate").color("info"))
        .value(RecordScheduleRunModel.STATUS_COMPLETED, value -> value
            .displayName("Completed").label(label(RecordScheduleRunModel.STATUS_COMPLETED))
            .icon("circle-check").color("success"))
        .value(RecordScheduleRunModel.STATUS_COMPLETED_WITH_FAILURES, value -> value
            .displayName("Completed with failures")
            .label(label(RecordScheduleRunModel.STATUS_COMPLETED_WITH_FAILURES))
            .icon("triangle-exclamation").color("warning"))
        .value(RecordScheduleRunModel.STATUS_ABORTED, value -> value
            .displayName("Aborted").label(label(RecordScheduleRunModel.STATUS_ABORTED))
            .icon("ban").color("secondary"))
        .value(RecordScheduleRunModel.STATUS_FAILED, value -> value
            .displayName("Failed").label(label(RecordScheduleRunModel.STATUS_FAILED))
            .icon("circle-xmark").color("destructive"))
        .value(RecordScheduleRunModel.STATUS_ABANDONED, value -> value
            .displayName("Abandoned").label(label(RecordScheduleRunModel.STATUS_ABANDONED))
            .icon("link-slash").color("destructive"))
        .build();

    private ScheduleRunStatuses() {}

    /**
     * The badge state for a stored status.
     *
     * @return null for a missing status; an unknown one renders as its own plain text
     *         (EnumBadgeState's honest-over-pretty stance), never as a wrong colour
     */
    public static @Nullable EnumBadgeState badgeFor(@Nullable Object status) {
        return status == null ? null : EnumBadgeState.of(FIELD, status);
    }

    /** The translation token for a status; the key IS the stored value. */
    private static @NonNull Microcopy label(@NonNull String status) {
        return Microcopy.of(status).withFilter("scope", "schedule_run_status");
    }
}
