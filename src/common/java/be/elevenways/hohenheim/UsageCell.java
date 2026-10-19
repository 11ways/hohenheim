package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A usage list cell: how full one thing is (a host's booked memory against its budget, its disk, a volume against its
 * size limit), as a bar and in words.
 *
 * @param measured whether a reading with a limit exists; without one there is no bar, only the words
 * @param percent  the used share as a whole percentage, 0 when there is no bar
 * @param text     the amounts in words, or why there are none
 * @author Jelle De Loecker
 * @since  0.9.0
 */
@HawkeyeClass
public record UsageCell(boolean measured, int percent, @NonNull Microcopy text) {
}
