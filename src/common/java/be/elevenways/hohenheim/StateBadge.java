package be.elevenways.hohenheim;

import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The badge a derived state wears in a list cell: the token it renders and the variant it is drawn in.
 *
 * AIDEV-NOTE: a state cell's vocabulary is an enum implementing this (or {@link WordedState}), so its token, colour
 * and words are facts on the member and every builder of a {@link StateLineCell} names a member, never a literal.
 * Only a state whose badge reads a measured value (a backup's age) implements this alone.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public interface StateBadge {

    /** @return the stable token the cell renders as {@code data-state} */
    @NonNull String token();

    /** @return the badge variant the state wears */
    @NonNull BadgeVariant variant();
}
