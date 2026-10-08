package be.elevenways.hohenheim.app;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * One capped budget of a tenant as the /manage usage card draws it (board Manage-Home).
 *
 * @param label   what is counted ("Memory")
 * @param used    how much of it the tenant holds, in bytes when {@code bytes}
 * @param limit   the cap the operator set, in the same unit
 * @param bytes   whether the amounts are sizes (worded as bytes) rather than counts
 * @param percent the share of the cap in use, 0 to 100
 * @author Jelle De Loecker
 * @since  0.9.0
 */
@HawkeyeClass
public record UsageLine(
    @NonNull Microcopy label,
    long used,
    long limit,
    boolean bytes,
    int percent
) {
}
