package be.elevenways.hohenheim.schedule;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.task.record.RunStatus;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The presentation facts for a record-schedule run status: one icon and colour per status
 * the framework declares.
 *
 * AIDEV-NOTE: the schedule-steps tab used to answer this with
 * {@code status == "completed" ? "success" : "destructive"}, which painted a RUNNING chain
 * red and made "completed with failures" indistinguishable from an outright failure. The
 * vocabulary itself now lives in {@link RunStatus}: this class walks its members and the
 * icon/colour switches are EXHAUSTIVE, so a status added upstream breaks the build here
 * instead of rendering as an unstyled token.
 */
public final class ScheduleRunStatuses {

    /**
     * The status vocabulary as an EnumField, so consumers render it through the SAME
     * EnumBadgeState path every stored enum column uses.
     */
    public static final EnumField FIELD = buildField();

    private ScheduleRunStatuses() {}

    private static @NonNull EnumField buildField() {
        EnumField.Builder builder = EnumField.builder("status");

        for (RunStatus status : RunStatus.values()) {
            builder.value(status.storageKey(), value -> value
                .displayName(status.label())
                .label(label(status))
                .icon(iconFor(status))
                .color(colorFor(status)));
        }

        return builder.build();
    }

    private static @NonNull String iconFor(@NonNull RunStatus status) {
        return switch (status) {
            case RUNNING -> "rotate";
            case COMPLETED -> "circle-check";
            case COMPLETED_WITH_FAILURES -> "triangle-exclamation";
            case ABORTED -> "ban";
            case FAILED -> "circle-xmark";
            case ABANDONED -> "link-slash";
        };
    }

    /** Only a clean completion is green; a partial one warns instead of claiming success. */
    private static @NonNull String colorFor(@NonNull RunStatus status) {
        return switch (status) {
            case RUNNING -> "info";
            case COMPLETED -> "success";
            case COMPLETED_WITH_FAILURES -> "warning";
            case ABORTED -> "secondary";
            case FAILED, ABANDONED -> "destructive";
        };
    }

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
    private static @NonNull Microcopy label(@NonNull RunStatus status) {
        return Microcopy.of(status.storageKey()).withFilter("scope", "schedule_run_status");
    }
}
