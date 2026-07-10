package be.elevenways.hohenheim.sitetype;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.registry.Registry;

/**
 * Central registry for all site types. Drives the SiteModel's RegistryEnumField,
 * the admin UI type selector, and the proxy engine's handler dispatch.
 */
public class SiteTypeRegistry {

    public static final Registry<SiteTypeInfo> REGISTRY =
        new Registry.Simple<>(Identifier.of("hohenheim", "site_types"));

    /**
     * Entries arrive via the generated BlastAutoLoadInit (SiteTypeHandler is
     * discoverable); force it so direct REGISTRY consumers see the entries.
     * MUST be the LAST static field (re-entrant init reads REGISTRY above).
     */
    @SuppressWarnings("unused")
    private static final Object AUTO_LOAD_TRIGGER =
            be.elevenways.protoblast.generated.BlastAutoLoadInit.loaded;

    private SiteTypeRegistry() {}
}
