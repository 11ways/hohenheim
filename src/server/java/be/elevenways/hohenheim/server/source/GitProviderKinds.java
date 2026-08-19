package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.source.GitProviderKindRegistry;
import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registration hook for the compile-time-discovered git provider kinds plus the
 * server-side handler map (the InstanceKinds shape). Concrete {@link GitProviderKind}
 * implementations arrive via the generated BlastAutoLoadInit; nothing is registered
 * manually, and an unknown kind resolves to null so every caller fails CLOSED.
 */
public final class GitProviderKinds {

    private static final Map<Identifier, GitProviderKind> HANDLERS = new HashMap<>();

    /**
     * Entries arrive via the generated BlastAutoLoadInit; force it so lookups work
     * regardless of which class the JVM touched first. MUST be the LAST static field:
     * the loader re-enters register() while this class is mid init and needs HANDLERS
     * assigned.
     */
    @SuppressWarnings("unused")
    private static final Object AUTO_LOAD_TRIGGER =
            be.elevenways.protoblast.generated.BlastAutoLoadInit.loaded;

    private GitProviderKinds() {}

    /** Compile-time discovery hook (BlastAutoLoadInit). */
    public static void register(GitProviderKind kind) {
        Identifier id = kind.typeId();
        GitProviderKindRegistry.REGISTRY.add(id, kind);
        HANDLERS.put(id, kind);
    }

    /** @return the handler for a stored kind token, or null for an undeclared one */
    public static @Nullable GitProviderKind getHandler(@Nullable String kindToken) {
        if (kindToken == null) {
            return null;
        }
        Identifier id = Identifier.tryParse(kindToken);
        return id == null ? null : HANDLERS.get(id);
    }

    /** @return every declared kind's identifier, for tests and diagnostics */
    public static @NonNull Set<Identifier> declaredKinds() {
        return Set.copyOf(HANDLERS.keySet());
    }
}
