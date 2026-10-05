package be.elevenways.hohenheim.app;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.widget.common.data.UsageData;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * One gauge of an app overview's Resources card: what is measured, and the framework's usage answer for it (NOT
 * MEASURED is an answer, never a bar at zero).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeClass
public record AppUsage(@NonNull Microcopy label, @NonNull UsageData usage) {
}
