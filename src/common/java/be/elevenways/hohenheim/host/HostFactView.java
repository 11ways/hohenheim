package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One stored preflight FACT with its own measurement provenance.
 *
 * @param name          the stored fact name
 * @param label         the fact in words; a name this build does not declare keeps its stored spelling
 * @param value         the value as a reader reads it (a byte count as a size), else as stored
 * @param measuredAtIso when the fact was last actually measured (never inherited from
 *                      {@code probed_at}); null for a pre-provenance record
 */
@HawkeyeClass
public record HostFactView(
    String name,
    Microcopy label,
    String value,
    @Nullable String measuredAtIso
) {
}
