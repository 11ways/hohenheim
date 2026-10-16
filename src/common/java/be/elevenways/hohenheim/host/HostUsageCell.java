package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A Hosts list usage cell: how full one of the host's resources is (its booked memory against its budget, its disk's
 * used bytes against its size), as a bar and in words.
 *
 * @param measured whether a fresh reading exists; without one there is no bar, only the words
 * @param percent  the used share as a whole percentage, 0 when not measured
 * @param text     the amounts in words, or why there are none
 * @author Jelle De Loecker
 * @since  0.9.0
 */
@HawkeyeClass
public record HostUsageCell(boolean measured, int percent, @NonNull Microcopy text) {
}
