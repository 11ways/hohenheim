package be.elevenways.hohenheim.schedule;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One recent run of a record schedule, as the Steps tab renders it.
 *
 * {@code status} carries the label, icon and colour {@link ScheduleRunStatuses} declares,
 * so the template never classifies a status itself.
 *
 * @param error the chain-level failure text, blank when there is none
 */
@HawkeyeClass
public record ScheduleRunView(
    int id,
    @Nullable EnumBadgeState status,
    @NonNull String startedAt,
    @NonNull String summary,
    @NonNull String error
) {
}
