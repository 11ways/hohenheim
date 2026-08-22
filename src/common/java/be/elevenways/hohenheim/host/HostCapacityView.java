package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The host's memory-capacity ledger for the overview page. {@code measured} false is an
 * EXPLICIT state: a zero usage bar would read as "empty host", which is backwards for a
 * host whose budget simply cannot be derived.
 *
 * @param measured      whether a fresh memory reading yields a budget at all
 * @param stale         true when a reading EXISTS but is older than the freshness bound
 *                      (so the operator sees "evidence too old", not "never measured")
 * @param budgetMb      the bookable budget (MB); 0 when unmeasured
 * @param bookedMb      what the instance tier currently holds
 * @param bookableMb    what is still bookable on this host
 * @param measuredAtIso when the memory reading was produced, null when never
 * @param maxAgeHours   the declared freshness bound ({@code capacity.facts_max_age_hours})
 */
@HawkeyeClass
public record HostCapacityView(
    boolean measured,
    boolean stale,
    int budgetMb,
    int bookedMb,
    int bookableMb,
    @Nullable String measuredAtIso,
    int maxAgeHours
) {
}
