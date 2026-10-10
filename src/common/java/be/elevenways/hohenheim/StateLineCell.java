package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE state cell: a badge that answers in a word, and the lines that explain it underneath.
 *
 * AIDEV-NOTE: build it through {@link #of(WordedState, Microcopy)} (or {@link #of(StateBadge, Microcopy, Microcopy)}
 * for a badge reading a measured value), so the token, variant and word come from the state's enum member; DD4
 * collapsed five hand-rolled cell styles (string branches, {@code default} switches, a near-copy for an address's
 * HTTPS) onto this one record and {@code cell/state-line.hwk}.
 *
 * @param state   the state's token, rendered as {@code data-state}
 * @param variant the state's badge variant
 * @param label   the short answer on the badge
 * @param detail  the line under it, null when the answer needs none
 * @param link    a line linking to what stands behind the state (an address's certificate), null when there is none
 * @param url     where {@code link} leads, null when {@code link} is
 * @param note    the last line (a certificate's last renewal error, its expiry), null when there is none
 * @author Jelle De Loecker
 * @since  0.2.0
 */
@HawkeyeClass
public record StateLineCell(
    @NonNull String state,
    @NonNull BadgeVariant variant,
    @NonNull Microcopy label,
    @Nullable Microcopy detail,
    @Nullable String link,
    @Nullable String url,
    @Nullable Microcopy note
) {

    /** @return the cell of a state that words itself, with {@code detail} under its badge */
    public static @NonNull StateLineCell of(@NonNull WordedState state, @Nullable Microcopy detail) {
        return of(state, state.label(), detail);
    }

    /** @return the cell of a state whose badge reads {@code label} (a measured value) in the state's colour */
    public static @NonNull StateLineCell of(@NonNull StateBadge state, @NonNull Microcopy label,
                                            @Nullable Microcopy detail) {
        return new StateLineCell(state.token(), state.variant(), label, detail, null, null, null);
    }

    /** @return whether this cell shows {@code state}, so a reader of the cell compares members, never tokens */
    public boolean is(@NonNull StateBadge state) {
        return this.state.equals(state.token());
    }

    /** @return this cell with {@code note} as its last line */
    public @NonNull StateLineCell withNote(@Nullable Microcopy note) {
        return new StateLineCell(this.state, this.variant, this.label, this.detail, this.link, this.url, note);
    }

    /** @return this cell with a line reading {@code link} that leads to {@code url} */
    public @NonNull StateLineCell withLink(@NonNull String link, @NonNull String url) {
        return new StateLineCell(this.state, this.variant, this.label, this.detail, link, url, this.note);
    }
}
