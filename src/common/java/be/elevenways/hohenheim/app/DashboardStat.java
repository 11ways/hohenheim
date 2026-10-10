package be.elevenways.hohenheim.app;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One count tile of the admin dashboard: what it counts, the count, and a line saying what the count
 * holds ("3 live, 1 with a problem"), linking to the list it counts. Every word is resolved for the viewer.
 *
 * @param key    the stable token naming what the tile counts
 * @param detail what the count holds, null where no fact backs a line
 * @param url    the list the tile counts
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record DashboardStat(
    @NonNull String key,
    @NonNull String label,
    @NonNull String value,
    @Nullable String detail,
    @NonNull String icon,
    @NonNull String url
) {
}
