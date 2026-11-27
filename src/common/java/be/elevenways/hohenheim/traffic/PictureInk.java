package be.elevenways.hohenheim.traffic;

import be.elevenways.domino.common.DominoElement;
import be.elevenways.zenit3d.common.shape.ThemeColors;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The theme colours every live traffic picture draws its rows and words in, packed Rgba, resolved once at mount.
 *
 * @param box   a row's ground
 * @param edge  a row's outline
 * @param guide a faint line between rows
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public record PictureInk(int box, int edge, int guide, int text, int muted) {

    /** @return the theme's colours as they stand on the surface now */
    public static @NonNull PictureInk of(@NonNull DominoElement surface) {
        return new PictureInk(ThemeColors.resolve(surface, "var(--pl-color-surface-raised)"),
            ThemeColors.resolve(surface, "var(--pl-color-border)"),
            ThemeColors.resolve(surface, "var(--pl-color-border-muted)"),
            ThemeColors.resolve(surface, "var(--pl-color-text)"),
            ThemeColors.resolve(surface, "var(--pl-color-text-muted)"));
    }
}
