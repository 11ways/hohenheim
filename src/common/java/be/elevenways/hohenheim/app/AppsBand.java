package be.elevenways.hohenheim.app;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * The dashboard's Apps band as one placement draws it: its heading ("Apps" for the operator, "Your apps" on /manage)
 * over the apps.
 *
 * @param heading the band's heading in the placement's own words
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record AppsBand(
    @NonNull Microcopy heading,
    @NonNull List<AppSummary> apps
) {
}
