package be.elevenways.hohenheim.site;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Site-list hostname cell: the first hostname plus how many more answer for the site.
 *
 * @param primary   the site's first exact hostname, or null when it has none yet
 * @param moreCount how many further domain rows exist beyond the primary
 */
@HawkeyeClass
public record SiteHostnamesCell(
    @Nullable String primary,
    int moreCount
) {
}
