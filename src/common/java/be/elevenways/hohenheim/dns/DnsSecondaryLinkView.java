package be.elevenways.hohenheim.dns;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.Nullable;

/** One secondary's replication facts and semantic freshness look. */
@HawkeyeClass(alwaysBundle = true)
public record DnsSecondaryLinkView(@Nullable String peerName, @Nullable String transferHost, RouteTarget editTarget,
                                   String servedSerial, String probeError, String probedAtIso, String axfrSerial,
                                   String axfrAtIso, String notifyAtIso, String notifyOutcome, Microcopy freshnessLabel,
                                   BadgeVariant freshnessVariant) {
}
