package be.elevenways.hohenheim.instance;

import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The template families Hohenheim seeds (its starters), each with the name its templates are seeded under and the
 * icon its chooser card wears; any other template wears {@link #TEMPLATE_ICON}.
 *
 * AIDEV-NOTE: a starter is matched by its FAMILY name ("Name (variant)" folds into "Name", TemplateFamily) on a row
 * whose source is the starter source, so an operator's rename leaves the fact behind and the row reads as any other.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public enum StarterTemplate {

    WORDPRESS("WordPress", "globe"),
    MINECRAFT_SERVER("Minecraft server", "gamepad"),
    MINECRAFT_BACKEND("Minecraft backend", "server"),
    VELOCITY_PROXY("Velocity proxy", "diagram-project");

    /** The icon of every template that is no starter: the templates entry's own. */
    public static final String TEMPLATE_ICON = "clone";

    private final @NonNull String familyName;
    private final @NonNull String icon;

    StarterTemplate(@NonNull String familyName, @NonNull String icon) {
        this.familyName = familyName;
        this.icon = icon;
    }

    /** @return the name its templates are seeded under, without a "(variant)" */
    public @NonNull String familyName() {
        return this.familyName;
    }

    public @NonNull Icon icon() {
        return Icon.of(this.icon);
    }

    /** @return the starter seeded under this family name, null for any other */
    public static @Nullable StarterTemplate ofFamily(@NonNull String familyName) {
        for (StarterTemplate starter : values()) {
            if (starter.familyName.equals(familyName)) {
                return starter;
            }
        }
        return null;
    }
}
