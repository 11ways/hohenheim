package be.elevenways.hohenheim.common.cms;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One release or workspace history row after the page applies its output visibility policy.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
@HawkeyeClass(alwaysBundle = true)
public record InstanceDeploymentView(@Nullable Integer id, @NonNull String status,
                                     @NonNull BadgeVariant statusVariant, @NonNull String reason,
                                     @NonNull String commit, @NonNull String duration, @NonNull String error,
                                     @NonNull String startedAtIso, @NonNull String log, boolean hasLog) {
}
