package be.elevenways.hohenheim.common.cms;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.common.routing.RouteTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One stack service's configured file and bound edit target.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
@HawkeyeClass(alwaysBundle = true)
public record StackServiceFileView(@Nullable Integer id, @Nullable String path, @NonNull RouteTarget editTarget) {
}
