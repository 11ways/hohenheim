package be.elevenways.hohenheim.auth;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Registry;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Central registry for per-site auth-provider types. Drives the SiteAuthProviderModel's
 * RegistryMemberField, the admin form's polymorphic schema, and the proxy engine's gate
 * construction. Populated by the autoload index through SiteAuthProviders (parallel to UpstreamKinds).
 *
 * AIDEV-NOTE: every read goes through {@link #registry()}; {@link #REGISTRY} itself is only for fields that hold
 * the registry object (a RegistryMemberField) and for the discovery hook's writes.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class SiteAuthProviderTypeRegistry {

    public static final Registry<SiteAuthProviderType> REGISTRY =
        Registry.create(HohenheimIds.id("site_auth_provider_type"));

    private SiteAuthProviderTypeRegistry() {}

    /** Runs the autoload index first so every discovered member is registered. */
    public static @NonNull Registry<SiteAuthProviderType> registry() {
        Blast.ensureAutoLoaded();
        return REGISTRY;
    }
}
