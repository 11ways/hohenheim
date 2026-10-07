package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A list cell that answers in a word and explains in a line: an address's "Points here", a certificate's state.
 *
 * @param state   the answer's token, rendered as {@code data-state}
 * @param variant the answer's badge variant
 * @param label   the short answer
 * @param detail  the line under it, null when the answer needs none
 * @param note    a verbatim line under that (a certificate's last renewal error), null when there is none
 * @author Jelle De Loecker
 * @since  0.2.0
 */
@HawkeyeClass
public record StateLineCell(
    @NonNull String state,
    @NonNull BadgeVariant variant,
    @NonNull Microcopy label,
    @Nullable Microcopy detail,
    @Nullable String note
) {
}
