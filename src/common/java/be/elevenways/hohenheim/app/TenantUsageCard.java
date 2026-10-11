package be.elevenways.hohenheim.app;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Objects;

/**
 * The /manage usage card: its lead and closing sentence, which name the installation's operator when one is
 * declared, and the budgets it lists.
 *
 * @param lead     who set the limits
 * @param needMore whom to ask for more
 * @param lines    each listed budget
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record TenantUsageCard(
    @NonNull Microcopy lead,
    @NonNull Microcopy needMore,
    @NonNull List<UsageLine> lines
) {

    public TenantUsageCard {
        Objects.requireNonNull(lead, "lead cannot be null");
        Objects.requireNonNull(needMore, "needMore cannot be null");
        lines = List.copyOf(lines);
    }
}
