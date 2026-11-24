package be.elevenways.hohenheim.cluster;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Instant;

/**
 * A join code just issued, as the Servers page shows it the one time it is shown.
 *
 * @param code       the code in groups of four ("7KQ2-M9XD-4TRA")
 * @param until      until when it is valid
 * @param renumbered how many things on this server get a new code when it joins
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record JoinCodeShown(@NonNull String code, @NonNull Instant until, int renumbered) {
}
