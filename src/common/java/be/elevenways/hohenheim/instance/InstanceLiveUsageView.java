package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.widget.common.WidgetInstance;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * What a running instance's overview draws its live memory and CPU gauges from: the hub's newest two readings as the
 * seed, and the two gauges (their labels) the readings fill.
 *
 * @param instanceId    the instance whose live stats link the gauges hold while the overview is open
 * @param latestSeed    the newest reading the hub held when the page rendered, in its wire shape, or null
 * @param previousSeed  the reading before it, or null
 * @param bookedBytes   what the host booked for it, measured against when the runtime reports no memory cap
 * @param memoryGauge   the memory gauge, unfilled
 * @param cpuGauge      the CPU gauge, unfilled
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeClass
public record InstanceLiveUsageView(
    int instanceId,
    @Nullable Map<String, Object> latestSeed,
    @Nullable Map<String, Object> previousSeed,
    @Nullable Long bookedBytes,
    @NonNull WidgetInstance memoryGauge,
    @NonNull WidgetInstance cpuGauge
) {
}
