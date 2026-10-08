package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The Hosts list's memory cell: the host's booked memory against its bookable budget, as a bar and in words.
 *
 * @param measured whether the host's capacity ledger has a fresh reading; without one there is no bar, only the words
 * @param percent  booked memory as a whole percentage of the budget, 0 when not measured
 * @param text     the amounts in words, or why there are none
 * @author Jelle De Loecker
 * @since  0.9.0
 */
@HawkeyeClass
public record HostMemoryCell(boolean measured, int percent, @NonNull Microcopy text) {
}
