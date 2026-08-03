package be.elevenways.hohenheim.instance;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.registry.Registry;

/**
 * Central registry for all instance kinds. Drives the InstanceModel's
 * RegistryEnumField, the admin UI kind selector, and the server's runtime dispatch.
 */
public final class InstanceKindRegistry {

    public static final Registry<InstanceKindInfo> REGISTRY =
        new Registry.Simple<>(Identifier.of("hohenheim", "instance_kinds"));

    /**
     * Entries arrive via the generated BlastAutoLoadInit (InstanceKindHandler is
     * discoverable); force it so direct REGISTRY consumers see the entries.
     * MUST be the LAST static field (re-entrant init reads REGISTRY above).
     */
    @SuppressWarnings("unused")
    private static final Object AUTO_LOAD_TRIGGER =
            be.elevenways.protoblast.generated.BlastAutoLoadInit.loaded;

    private InstanceKindRegistry() {}
}
