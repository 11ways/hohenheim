package be.elevenways.hohenheim.app;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.hohenheim.site.SiteTlsCell;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * One app as the dashboard's Apps band draws it (board Main): its name, what it is and where, and whether HTTPS works.
 *
 * @param detail what the app is and its address ("WordPress · shop.example.com")
 * @param url    the app's own page
 * @author Jelle De Loecker
 * @since  0.9.0
 */
@HawkeyeClass
public record AppSummary(
    @NonNull String name,
    @NonNull String detail,
    @NonNull String url,
    @NonNull SiteTlsCell https
) {
}
