package be.elevenways.hohenheim.auth;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.registry.Registry;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Central registry for per-site auth-provider types. Drives the SiteAuthProviderModel's
 * RegistryMemberField, the admin form's polymorphic schema, and the proxy engine's gate
 * construction. Populated by the autoload index through SiteAuthProviders (parallel to UpstreamKinds).
 *
 * AIDEV-NOTE: an autoloaded Registry: every read runs the index first, so {@link #REGISTRY} and {@link #registry()}
 * are the same object and no accessor calls Blast.ensureAutoLoaded.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class SiteAuthProviderTypeRegistry {

    public static final Registry<SiteAuthProviderType> REGISTRY =
        Registry.autoLoaded(HohenheimIds.id("site_auth_provider_type"));

    private SiteAuthProviderTypeRegistry() {}

    /** @return the live registry */
    public static @NonNull Registry<SiteAuthProviderType> registry() {
        return REGISTRY;
    }
}
