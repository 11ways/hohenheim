package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.source.GitProviderKindRegistry;
import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Registration hook for the compile-time-discovered git provider kinds plus the
 * server-side handler lookup (the InstanceKinds shape). Concrete {@link GitProviderKind}
 * implementations arrive via the generated BlastAutoLoadInit; nothing is registered
 * manually, and an unknown kind resolves to null so every caller fails CLOSED.
 *
 * AIDEV-NOTE: a handler is read out of THE registry, never a private handler map beside it.
 */
public final class GitProviderKinds {

    /**
     * Entries arrive via the generated BlastAutoLoadInit; force it so lookups work
     * regardless of which class the JVM touched first. MUST be the LAST static field.
     */
    @SuppressWarnings("unused")
    private static final Object AUTO_LOAD_TRIGGER =
            be.elevenways.protoblast.generated.BlastAutoLoadInit.loaded;

    private GitProviderKinds() {}

    /** Compile-time discovery hook (BlastAutoLoadInit). */
    public static void register(GitProviderKind kind) {
        GitProviderKindRegistry.REGISTRY.add(kind.typeId(), kind);
    }

    /** @return the handler for a stored kind token, or null for an undeclared one */
    public static @Nullable GitProviderKind getHandler(@Nullable String kindToken) {
        if (kindToken == null) {
            return null;
        }
        Identifier id = Identifier.tryParse(kindToken);
        return id != null && GitProviderKindRegistry.REGISTRY.get(id) instanceof GitProviderKind kind ? kind : null;
    }

    /** @return every declared kind's identifier, for tests and diagnostics */
    public static @NonNull Set<Identifier> declaredKinds() {
        Set<Identifier> kinds = new LinkedHashSet<>();
        for (Identifier id : GitProviderKindRegistry.REGISTRY.ids()) {
            if (GitProviderKindRegistry.REGISTRY.get(id) instanceof GitProviderKind) {
                kinds.add(id);
            }
        }
        return Set.copyOf(kinds);
    }
}
