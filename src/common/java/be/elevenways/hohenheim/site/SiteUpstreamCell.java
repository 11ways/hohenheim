package be.elevenways.hohenheim.site;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Site-list upstream cell: the kind as a badge plus one line saying where the traffic
 * goes -- the served instance (linked) for the instance kind, the kind's own summary
 * (address, directory, redirect target) otherwise.
 *
 * @param kindKey      the stored upstream kind value ({@code data-} identity)
 * @param kindLabel    the kind's translated label
 * @param icon         the kind's icon name, or null
 * @param color        the kind's badge color-set hue, or null for the default
 * @param summary      the kind's one-line target, or null
 * @param instanceName the served instance's display title (instance kind only)
 * @param instanceUrl  admin detail URL of that instance, or null
 */
@HawkeyeClass
public record SiteUpstreamCell(
    @NonNull String kindKey,
    @NonNull Microcopy kindLabel,
    @Nullable String icon,
    @Nullable String color,
    @Nullable String summary,
    @Nullable String instanceName,
    @Nullable String instanceUrl
) {
}
