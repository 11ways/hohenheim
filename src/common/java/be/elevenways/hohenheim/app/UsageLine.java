package be.elevenways.hohenheim.app;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * One budget of a tenant as the /manage usage card draws it: held against its cap, or uncapped.
 *
 * @param label   what is counted ("Memory")
 * @param used    how much of it the tenant holds, in bytes when {@code bytes}
 * @param limit   the cap the operator set, in the same unit
 * @param bytes   whether the amounts are sizes (worded as bytes) rather than counts
 * @param percent the share of the cap in use, 0 to 100
 * @param capped  whether the operator set a cap; an uncapped line states no amount and draws no bar
 * @author Jelle De Loecker
 * @since  0.9.0
 */
@HawkeyeClass
public record UsageLine(
    @NonNull Microcopy label,
    long used,
    long limit,
    boolean bytes,
    int percent,
    boolean capped
) {

    /** @return the line of a budget the operator set no cap on */
    public static @NonNull UsageLine uncapped(@NonNull Microcopy label) {
        return new UsageLine(label, 0L, 0L, false, 0, false);
    }
}
