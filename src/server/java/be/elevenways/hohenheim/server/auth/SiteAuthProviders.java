package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.auth.SiteAuthProviderTypeRegistry;
import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Registration hook for the compile-time-discovered per-site auth-provider
 * types plus the server-side handler lookup (parallel to UpstreamKindHandlers). Concrete
 * SiteAuthProviderTypeHandler implementations arrive via the generated
 * BlastAutoLoadInit; nothing is registered manually.
 *
 * AIDEV-NOTE: a handler is read out of THE registry, never a private handler map beside it;
 * an entry that is not a server handler fails closed as "unknown type".
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class SiteAuthProviders {

    /**
     * Entries arrive via the generated BlastAutoLoadInit; force it so lookups
     * work regardless of which class the JVM touched first. MUST be the LAST
     * static field.
     */
    @SuppressWarnings("unused")
    private static final Object AUTO_LOAD_TRIGGER =
            be.elevenways.protoblast.generated.BlastAutoLoadInit.loaded;

    /** Compile-time discovery hook (BlastAutoLoadInit). */
    public static void register(SiteAuthProviderTypeHandler handler) {
        SiteAuthProviderTypeRegistry.REGISTRY.add(handler.typeId(), handler);
    }

    @Nullable
    public static SiteAuthProviderTypeHandler getHandler(@Nullable String typeIdentifier) {
        if (typeIdentifier == null) {
            return null;
        }
        Identifier id = Identifier.tryParse(typeIdentifier);
        return id != null && SiteAuthProviderTypeRegistry.REGISTRY.get(id) instanceof SiteAuthProviderTypeHandler handler
            ? handler : null;
    }

    private SiteAuthProviders() {}
}
