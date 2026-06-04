package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.auth.SiteAuthProviderTypeRegistry;
import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Registers all built-in per-site auth-provider types and maintains the server-side handler map
 * (parallel to SiteTypes). Call register() once at boot, before the proxy loads its routes.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class SiteAuthProviders {

    private static final Map<Identifier, SiteAuthProviderTypeHandler> HANDLERS = new HashMap<>();

    public static void register() {
        // Provider types are registered here as they land (Proteus, Basic, ...).
    }

    public static void registerType(Identifier id, SiteAuthProviderTypeHandler handler) {
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
