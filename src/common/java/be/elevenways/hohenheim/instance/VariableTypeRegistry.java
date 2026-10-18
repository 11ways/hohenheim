package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Registry;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Central registry of template-variable types. Drives the variable model's
 * RegistryMemberField, the per-type settings sub-form, and the server's typed field
 * building (VariableTypes).
 *
 * AIDEV-NOTE: every read goes through {@link #registry()}; {@link #REGISTRY} itself is only for fields that hold
 * the registry object (a RegistryMemberField) and for the discovery hook's writes.
 */
public final class VariableTypeRegistry {

    public static final Registry<VariableTypeInfo> REGISTRY =
        Registry.create(HohenheimIds.id("variable_type"));

    private VariableTypeRegistry() {}

    /** Runs the autoload index first so every discovered member is registered. */
    public static @NonNull Registry<VariableTypeInfo> registry() {
        Blast.ensureAutoLoaded();
        return REGISTRY;
    }
}
