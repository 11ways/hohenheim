package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.registry.Identifier;

/**
 * The stored type id of {@link HohenheimWidgets#ONBOARDING}, kept for existing call sites.
 *
 * AIDEV-NOTE: the widget type itself is declared once in {@link HohenheimWidgets}; this class only
 * aliases its id. New code reads {@code HohenheimWidgets.ONBOARDING.id()}; once no call site names this
 * class it can be deleted.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class OnboardingWidget {

    public static final Identifier ID = HohenheimWidgets.ONBOARDING.id();

    private OnboardingWidget() {
    }
}
