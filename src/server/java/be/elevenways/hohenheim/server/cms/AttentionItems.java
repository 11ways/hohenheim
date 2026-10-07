package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.routing.RouteTarget;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The construction helpers every dashboard attention collector shares.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class AttentionItems {

    private AttentionItems() {
    }

    /** An item the operator can only read: nothing to open from it. */
    static @NonNull AttentionItem item(@NonNull AttentionSeverity severity, @NonNull String icon,
                                       @NonNull Microcopy title, @Nullable Microcopy detail) {
        return new AttentionItem(severity, icon, title, detail, null, null);
    }

    /**
     * An item that leads somewhere, saying what going there does.
     *
     * @param action the worded fix ("Check and admit"), from {@link #action}
     */
    static @NonNull AttentionItem item(@NonNull AttentionSeverity severity, @NonNull String icon,
                                       @NonNull Microcopy title, @Nullable Microcopy detail,
                                       @NonNull RouteTarget target, @NonNull Microcopy action) {
        return new AttentionItem(severity, icon, title, detail, target, action);
    }

    /**
     * An item's worded fix, a catalog key under the {@code attention_action} scope.
     *
     * @param args name/value pairs, in that order
     */
    static @NonNull Microcopy action(@NonNull String key, Object... args) {
        return copy(key, "attention_action", args);
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

    /**
     * Each row's label grouped under its host, hosts in first-seen order: a per-host finding reads as one attention
     * item per host, naming what it counted there.
     */
    static <T> @NonNull Map<String, List<String>> byHost(@NonNull Iterable<T> rows,
                                                        @NonNull Function<T, String> host,
                                                        @NonNull Function<T, String> label) {
        Map<String, List<String>> labels = new LinkedHashMap<>();
        for (T row : rows) {
            labels.computeIfAbsent(host.apply(row), k -> new ArrayList<>()).add(label.apply(row));
        }
        return labels;
    }
}
