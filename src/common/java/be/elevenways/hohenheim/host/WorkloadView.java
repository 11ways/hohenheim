package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One workload holding this host: the same population
 * {@code ServerModel.refuseRemovalWhileOwned} counts, so drain/cordon/delete refusals are
 * legible BEFORE they fire.
 *
 * @param tier     {@code instance} / {@code stack} / {@code database}
 * @param bookedMb the memory this workload books on the host, null when it books nothing
 */
@HawkeyeClass
public record WorkloadView(
    String name,
    String tier,
    @Nullable EnumBadgeState status,
    @Nullable Integer bookedMb,
    String url
) {
}
