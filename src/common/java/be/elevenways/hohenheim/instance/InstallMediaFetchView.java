package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One stored install-media fetch, as the Install media tab draws it.
 *
 * @param state   the state's label ({@link InstallMediaFetchState#label})
 * @param variant the badge variant ({@link InstallMediaFetchState#variant})
 * @param percent the downloaded share while the fetch is in flight and its size is known, else null
 * @param reason  the stored failure reason, null when there is none
 * @author Jelle De Loecker
 * @since 0.1.0
 */
@HawkeyeClass
public record InstallMediaFetchView(
    @NonNull String name,
    @NonNull Microcopy state,
    @NonNull String variant,
    boolean active,
    @Nullable Integer percent,
    @Nullable String reason
) {
}
