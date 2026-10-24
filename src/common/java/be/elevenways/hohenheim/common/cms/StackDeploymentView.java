package be.elevenways.hohenheim.common.cms;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One deployment's localized labels and verbatim captured output.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
@HawkeyeClass(alwaysBundle = true)
public record StackDeploymentView(@Nullable Integer id, @Nullable Microcopy statusLabel,
                                  @NonNull BadgeVariant statusVariant, @Nullable Microcopy reasonLabel,
                                  @NonNull String duration, @NonNull String error, @NonNull String startedAtIso,
                                  @NonNull String log, boolean hasLog) {
}
