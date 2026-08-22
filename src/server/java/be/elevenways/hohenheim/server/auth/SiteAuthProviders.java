package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.auth.SiteAuthProviderTypeRegistry;
import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Registration hook for the compile-time-discovered per-site auth-provider
 * types plus the server-side handler map (parallel to UpstreamKindHandlers). Concrete
 * SiteAuthProviderTypeHandler implementations arrive via the generated
 * BlastAutoLoadInit; nothing is registered manually.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class SiteAuthProviders {

    private static final Map<Identifier, SiteAuthProviderTypeHandler> HANDLERS = new HashMap<>();

    /**
     * Entries arrive via the generated BlastAutoLoadInit; force it so lookups
     * work regardless of which class the JVM touched first. MUST be the LAST
     * static field: the loader re-enters register() while this class is mid
     * init and needs HANDLERS assigned.
     */
    @SuppressWarnings("unused")
    private static final Object AUTO_LOAD_TRIGGER =
            be.elevenways.protoblast.generated.BlastAutoLoadInit.loaded;

    /** Compile-time discovery hook (BlastAutoLoadInit). */
    public static void register(SiteAuthProviderTypeHandler handler) {
        Identifier id = handler.typeId();
        SiteAuthProviderTypeRegistry.REGISTRY.add(id, handler);
        HANDLERS.put(id, handler);
    }

    @Nullable
    public static SiteAuthProviderTypeHandler getHandler(@Nullable String typeIdentifier) {
        if (typeIdentifier == null) {
            return null;
        }
        Identifier id = Identifier.tryParse(typeIdentifier);
        return id != null ? HANDLERS.get(id) : null;
    }

    private SiteAuthProviders() {}
}
