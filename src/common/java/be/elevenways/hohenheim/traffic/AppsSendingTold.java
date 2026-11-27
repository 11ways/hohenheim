package be.elevenways.hohenheim.traffic;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * What the "Apps sending" picture tells its page after the draw that shows a reading.
 *
 * @param picture the picture in words, for its accessible name
 * @param spoken  the one line a screen reader is told of the reading
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeClass
public record AppsSendingTold(@NonNull String picture, @NonNull Microcopy spoken) {
}
