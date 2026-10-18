package be.elevenways.hohenheim.source;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Registry;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Central registry for all git provider kinds. Drives the GitProviderModel's
 * RegistryMemberField, its per-kind settings sub-schema, the admin UI kind selector and
 * the server's client construction -- one home, so adding a kind is one class.
 *
 * AIDEV-NOTE: every read goes through {@link #registry()}; {@link #REGISTRY} itself is only for fields that hold
 * the registry object (a RegistryMemberField) and for the discovery hook's writes.
 */
public final class GitProviderKindRegistry {

    public static final Registry<GitProviderKindInfo> REGISTRY =
        Registry.create(HohenheimIds.id("git_provider_kind"));

    private GitProviderKindRegistry() {}

    /** Runs the autoload index first so every discovered member is registered. */
    public static @NonNull Registry<GitProviderKindInfo> registry() {
        Blast.ensureAutoLoaded();
        return REGISTRY;
    }
}
