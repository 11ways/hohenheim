package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Registry;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Central registry for all instance kinds. Drives the InstanceModel's
 * RegistryMemberField, the admin UI kind selector, and the server's runtime dispatch.
 *
 * AIDEV-NOTE: every read goes through {@link #registry()}; {@link #REGISTRY} itself is only for fields that hold
 * the registry object (a RegistryMemberField) and for the discovery hook's writes.
 */
public final class InstanceKindRegistry {

    public static final Registry<InstanceKindInfo> REGISTRY =
        Registry.create(HohenheimIds.id("instance_kind"));

    private InstanceKindRegistry() {}

    /** Runs the autoload index first so every discovered member is registered. */
    public static @NonNull Registry<InstanceKindInfo> registry() {
        Blast.ensureAutoLoaded();
        return REGISTRY;
    }
}
