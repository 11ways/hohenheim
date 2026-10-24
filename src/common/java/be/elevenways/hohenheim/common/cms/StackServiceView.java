package be.elevenways.hohenheim.common.cms;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * One service's live-state presentation and bound configuration links.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
@HawkeyeClass(alwaysBundle = true)
public record StackServiceView(@Nullable Integer id, @Nullable String name, @Nullable String image, boolean enabled,
                               @NonNull String state, @NonNull Microcopy stateLabel, @NonNull BadgeVariant stateVariant,
                               @NonNull RouteTarget editTarget, @NonNull String ports,
                               @NonNull List<StackServiceFileView> files, @NonNull RouteTarget addFileTarget) {
}
