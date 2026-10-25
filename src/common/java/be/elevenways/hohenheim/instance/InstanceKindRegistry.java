package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.registry.Registry;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Central registry for all instance kinds. Drives the InstanceModel's
 * RegistryMemberField, the admin UI kind selector, and the server's runtime dispatch.
 *
 * AIDEV-NOTE: an autoloaded Registry: every read runs the index first, so {@link #REGISTRY} and {@link #registry()}
 * are the same object and no accessor calls Blast.ensureAutoLoaded.
 */
public final class InstanceKindRegistry {

    public static final Registry<InstanceKindInfo> REGISTRY =
        Registry.autoLoaded(HohenheimIds.id("instance_kind"));

    private InstanceKindRegistry() {}

    /** @return the live registry */
    public static @NonNull Registry<InstanceKindInfo> registry() {
        return REGISTRY;
    }
}
