package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * One ISO volume in an Incus host's managed pool, as the Install media tab lists it.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
@HawkeyeClass
public record InstallMediumView(
    @NonNull String name,
    @NonNull String description
) {
}
