package be.elevenways.hohenheim.access;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.routing.RouteTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One place an access list guards, as its Rules tab names it: a whole app, or one path on an app.
 *
 * @param label  the app's name, or the path on its app
 * @param note   the words after the label ("(whole app)"), null for none
 * @param target the app's page, null when the viewer may not open it (the label is then plain text)
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
@HawkeyeClass
public record GuardedPlace(@NonNull Microcopy label, @Nullable Microcopy note, @Nullable RouteTarget target) {
}
