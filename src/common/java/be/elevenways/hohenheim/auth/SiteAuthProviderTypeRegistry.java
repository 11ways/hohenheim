package be.elevenways.hohenheim.auth;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.registry.Registry;

/**
 * Central registry for per-site auth-provider types. Drives the SiteAuthProviderModel's
 * RegistryEnumField, the admin form's polymorphic schema, and the proxy engine's gate
 * construction. Populated at server boot by SiteAuthProviders (parallel to UpstreamKinds).
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class SiteAuthProviderTypeRegistry {

    public static final Registry<SiteAuthProviderType> REGISTRY =
        new Registry.Simple<>(Identifier.of("hohenheim", "site_auth_provider_types"));

    /**
     * Entries arrive via the generated BlastAutoLoadInit (the handler interface
     * is discoverable); force it so direct REGISTRY consumers see the entries.
     * MUST be the LAST static field (re-entrant init reads REGISTRY above).
     */
    @SuppressWarnings("unused")
    private static final Object AUTO_LOAD_TRIGGER =
            be.elevenways.protoblast.generated.BlastAutoLoadInit.loaded;

    private SiteAuthProviderTypeRegistry() {}
}
