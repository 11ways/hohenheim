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

    private SiteTypeRegistry() {}
}
