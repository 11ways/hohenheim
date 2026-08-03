package be.elevenways.hohenheim.instance;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.registry.Registry;

/**
 * Central registry of template-variable types. Drives the variable model's
 * RegistryEnumField, the per-type settings sub-form, and the server's typed field
 * building (VariableTypes).
 */
public final class VariableTypeRegistry {

    public static final Registry<VariableTypeInfo> REGISTRY =
        new Registry.Simple<>(Identifier.of("hohenheim", "variable_types"));

    /**
     * Entries arrive via the generated BlastAutoLoadInit (VariableTypeHandler is
     * discoverable); force it so direct REGISTRY consumers see the entries.
     * MUST be the LAST static field (re-entrant init reads REGISTRY above).
     */
    @SuppressWarnings("unused")
    private static final Object AUTO_LOAD_TRIGGER =
            be.elevenways.protoblast.generated.BlastAutoLoadInit.loaded;

    private VariableTypeRegistry() {}
}
