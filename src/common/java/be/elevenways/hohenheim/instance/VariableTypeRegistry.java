package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.registry.Registry;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Central registry of template-variable types. Drives the variable model's
 * RegistryMemberField, the per-type settings sub-form, and the server's typed field
 * building (VariableTypes).
 *
 * AIDEV-NOTE: an autoloaded Registry: every read runs the index first, so {@link #REGISTRY} and {@link #registry()}
 * are the same object and no accessor calls Blast.ensureAutoLoaded.
 */
public final class VariableTypeRegistry {

    public static final Registry<VariableTypeInfo> REGISTRY =
        Registry.autoLoaded(HohenheimIds.id("variable_type"));

    private VariableTypeRegistry() {}

    /** @return the live registry */
    public static @NonNull Registry<VariableTypeInfo> registry() {
        return REGISTRY;
    }
}
