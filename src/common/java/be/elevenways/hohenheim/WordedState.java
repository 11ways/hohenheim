package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A state badge that says itself in one word ("Works", "Waiting"), the usual shape of a state cell's vocabulary.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public interface WordedState extends StateBadge {

    /** @return the state in words */
    @NonNull Microcopy label();
}
