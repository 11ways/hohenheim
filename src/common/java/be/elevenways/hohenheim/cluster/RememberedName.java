package be.elevenways.hohenheim.cluster;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * One record a master remembers of a linked server, as the page of its kind's names lists it.
 *
 * @param code its code on that server, empty for a kind without codes
 * @param name its name as that server titles it
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record RememberedName(@NonNull String code, @NonNull String name) {
}
