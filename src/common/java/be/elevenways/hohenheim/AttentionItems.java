package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeFunction;
import be.elevenways.zenit.common.annotation.ZenitAutoLoad;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
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
    public static @NonNull List<Map<String, Object>> attentionItems() {
        Supplier<List<Map<String, Object>>> current = provider;
        return current != null ? current.get() : List.of();
    }
}
