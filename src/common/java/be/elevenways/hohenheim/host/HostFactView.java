package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One stored preflight FACT with its own measurement provenance.
 *
 * @param measuredAtIso when the fact was last actually measured (never inherited from
 *                      {@code probed_at}); null for a pre-provenance record
 */
@HawkeyeClass
public record HostFactView(
    String name,
    String value,
    @Nullable String measuredAtIso
) {
}
