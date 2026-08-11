package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.routing.RouteTarget;
import org.checkerframework.checker.nullness.qual.Nullable;

/** One typed operational issue rendered by the dashboard attention widget.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
@HawkeyeClass
public record AttentionItem(
        String severity,
        String icon,
        Microcopy title,
        @Nullable Microcopy detail,
        @Nullable RouteTarget target
) {}
