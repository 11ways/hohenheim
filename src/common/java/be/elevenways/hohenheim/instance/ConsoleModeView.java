package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * One mode of an instance's Console tab as its mode switch draws it: a link to that mode's own route.
 *
 * @param key    the mode's route slug, also its stable test hook
 * @param url    the mode's route, built from the hosting panel's subpage endpoint
 * @param hint   one line saying what this mode is for, shown beside the switch while it is active
 * @author Jelle De Loecker
 * @since 0.1.0
 */
@HawkeyeClass
public record ConsoleModeView(
    @NonNull String key,
    @NonNull Microcopy label,
    @NonNull String url,
    @NonNull Microcopy hint,
    boolean active
) {
}
