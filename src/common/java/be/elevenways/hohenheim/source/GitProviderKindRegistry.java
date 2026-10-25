package be.elevenways.hohenheim.source;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.registry.Registry;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Central registry for all git provider kinds. Drives the GitProviderModel's
 * RegistryMemberField, its per-kind settings sub-schema, the admin UI kind selector and
 * the server's client construction -- one home, so adding a kind is one class.
 *
 * AIDEV-NOTE: an autoloaded Registry: every read runs the index first, so {@link #REGISTRY} and {@link #registry()}
 * are the same object and no accessor calls Blast.ensureAutoLoaded.
 */
public final class GitProviderKindRegistry {

    public static final Registry<GitProviderKindInfo> REGISTRY =
        Registry.autoLoaded(HohenheimIds.id("git_provider_kind"));

    private GitProviderKindRegistry() {}

    /** @return the live registry */
    public static @NonNull Registry<GitProviderKindInfo> registry() {
        return REGISTRY;
    }
}
