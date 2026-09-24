package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.routing.RouteTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The construction helpers every dashboard attention collector shares.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class AttentionItems {

    private AttentionItems() {
    }

    static @NonNull AttentionItem item(@NonNull AttentionSeverity severity, @NonNull String icon,
                                       @NonNull Microcopy title, @Nullable Microcopy detail,
                                       @Nullable RouteTarget target) {
        return new AttentionItem(severity, icon, title, detail, target);
    }

    /** A verbatim detail (an error message, a reason); blank folds to no detail at all. */
    static @Nullable Microcopy literal(@Nullable Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Microcopy.literal(String.valueOf(value));
    }

    /**
     * A catalog key under a {@code scope} filter.
     *
     * @param args name/value pairs, in that order
     */
    static @NonNull Microcopy copy(@NonNull String key, @NonNull String scope, Object... args) {
        Microcopy copy = Microcopy.of(key).withFilter("scope", scope);
        for (int i = 0; i + 1 < args.length; i += 2) {
            copy = copy.withArg(String.valueOf(args[i]), args[i + 1]);
        }
        return copy;
    }
}
