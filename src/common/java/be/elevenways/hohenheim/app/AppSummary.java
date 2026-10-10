package be.elevenways.hohenheim.app;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.zenit.cms.common.render.table.HealthCellState;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One app as the dashboard's Apps band draws it: its health, its name, what it is and where, and its
 * badge: whether HTTPS works, or its verdict.
 *
 * @param detail what the app is and its address ("WordPress · shop.example.com")
 * @param url    the app's own page
 * @param https   what HTTPS gives its main address, in the Addresses list's words; null without an exact address
 *                or while the badge is the verdict
 * @param health  the verdict its record page leads with, as the Apps list's health cell draws it
 * @param verdict whether the badge reads the verdict: an app with a problem its HTTPS does not explain
 * @author Jelle De Loecker
 * @since  0.9.0
 */
@HawkeyeClass
public record AppSummary(
    @NonNull String name,
    @NonNull String detail,
    @NonNull String url,
    @Nullable StateLineCell https,
    @NonNull HealthCellState health,
    boolean verdict
) {
}
