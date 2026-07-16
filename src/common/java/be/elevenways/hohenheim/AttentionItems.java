package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeFunction;
import be.elevenways.hawkeye.common.render.RenderContext;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.annotation.ZenitAutoLoad;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Bridge for the dashboard attention panel: the server installs a collector
 * at boot; template code reads it through {@code Hohenheim.attentionItems()}.
 * Without a provider (client bundle, partial boots) the list is empty.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
@ZenitAutoLoad
public final class AttentionItems {

    /** Item map keys: severity ("error"|"warning"), icon, title, detail, url (optional). */
    private static @Nullable Supplier<List<Map<String, Object>>> provider;

    private AttentionItems() {}

    public static void install(@NonNull Supplier<List<Map<String, Object>>> collector) {
        provider = collector;
    }

    @HawkeyeFunction(
        name = "attentionItems",
        namespace = "Hohenheim",
        description = "Operational states needing attention (error certs, down sites, failed databases/deploys/tasks)",
        returnType = List.class,
        returnsReference = false
    )
    public static @NonNull List<Map<String, Object>> attentionItems(RenderContext context) {
        Supplier<List<Map<String, Object>>> current = provider;
        if (current == null) {
            return List.of();
        }
        return current.get().stream().map(item -> {
            Map<String, Object> resolved = new LinkedHashMap<>(item);
            resolveCopy(resolved, "title", context);
            resolveCopy(resolved, "detail", context);
            return resolved;
        }).toList();
    }

    private static void resolveCopy(Map<String, Object> item, String key, RenderContext context) {
        if (item.get(key) instanceof Microcopy copy) {
            item.put(key, copy.resolve(context.getLocales(), context.getMessageResolver()));
        }
    }
}
