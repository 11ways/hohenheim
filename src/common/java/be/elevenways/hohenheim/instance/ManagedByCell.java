package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * "Managed by" list cell of a generated instance: the owning product record, linked.
 *
 * @param icon      the owning resource's icon name, or null for a plain label
 * @param ownerKind the owning resource's label (tooltip and screen-reader context)
 * @param ownerName the owning record's display title
 * @param ownerUrl  admin detail URL of the owning record, or null when it has no surface
 */
@HawkeyeClass
public record ManagedByCell(
    @Nullable String icon,
    @NonNull Microcopy ownerKind,
    @NonNull String ownerName,
    @Nullable String ownerUrl
) {
}
