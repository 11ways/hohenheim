package be.elevenways.hohenheim.app;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.hohenheim.site.SiteTlsCell;
import be.elevenways.zenit.cms.common.render.table.HealthCellState;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * One app as the dashboard's Apps band draws it (board Main): its health, its name, what it is and where, and whether
 * HTTPS works.
 *
 * @param detail what the app is and its address ("WordPress · shop.example.com")
 * @param url    the app's own page
 * @param health the verdict its record page leads with, as the Apps list's health cell draws it
 * @author Jelle De Loecker
 * @since  0.9.0
 */
@HawkeyeClass
public record AppSummary(
    @NonNull String name,
    @NonNull String detail,
    @NonNull String url,
    @NonNull SiteTlsCell https,
    @NonNull HealthCellState health
) {
}
