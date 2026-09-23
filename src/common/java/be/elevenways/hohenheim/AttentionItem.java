package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.routing.RouteTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/** One typed operational issue rendered by the dashboard attention widget.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
@HawkeyeClass
public record AttentionItem(
        @NonNull AttentionSeverity severity,
        String icon,
        Microcopy title,
        @Nullable Microcopy detail,
        @Nullable RouteTarget target
) {

    /**
     * The legacy string spelling of the severity.
     *
     * AIDEV-NOTE: kept so collectors still passing "error"/"warning"/"info" compile unchanged;
     * new code passes an {@link AttentionSeverity} member. Remove once no caller spells a string.
     *
     * @throws IllegalArgumentException for a severity {@link AttentionSeverity#of} does not know
     */
    public AttentionItem(@NonNull String severity, String icon, Microcopy title,
                         @Nullable Microcopy detail, @Nullable RouteTarget target) {
        this(AttentionSeverity.of(severity), icon, title, detail, target);
    }
}
