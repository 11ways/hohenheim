package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A counted noun in a real plural ("1 stack", "3 stacks"), for a sentence that counts several things at once: a
 * message has one plural selector, so each count rides in as its own phrase.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class HohenheimCounts {

    private HohenheimCounts() {
    }

    /**
     * @param noun  the counted noun's key under {@link HohenheimMicrocopy#COUNT} ("stacks")
     * @param count how many
     * @return the phrase, pluralized in the reader's locale
     */
    public static @NonNull Microcopy of(@NonNull String noun, long count) {
        return HohenheimMicrocopy.COUNT.of(noun).withArg("count", count);
    }
}
