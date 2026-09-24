package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * The live half of an Incus host's Install media tab: the pool's media and the fetches, read in one go.
 *
 * AIDEV-NOTE: ONE shape for the page render and for the live re-read ({@link InstallMediaLive#read}),
 * so the region a fetch's progress updates in place can never draw differently from the page that
 * first rendered it. The forms around it are deliberately NOT part of it: a re-read must never touch
 * what the operator is typing.
 *
 * @param loadError the host's own answer when its media could not be listed, null otherwise
 * @author Jelle De Loecker
 * @since 0.1.0
 */
@HawkeyeClass
public record InstallMediaView(
    @NonNull List<InstallMediumView> media,
    @NonNull List<InstallMediaFetchView> fetches,
    @Nullable String loadError
) {
}
