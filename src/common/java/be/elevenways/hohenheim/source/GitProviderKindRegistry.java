package be.elevenways.hohenheim.source;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.registry.Registry;

/**
 * Central registry for all git provider kinds. Drives the GitProviderModel's
 * RegistryMemberField, its per-kind settings sub-schema, the admin UI kind selector and
 * the server's client construction -- one home, so adding a kind is one class.
 */
public final class GitProviderKindRegistry {

    public static final Registry<GitProviderKindInfo> REGISTRY =
        Registry.create(HohenheimIds.id("git_provider_kind"));

    /**
     * Entries arrive via the generated BlastAutoLoadInit (GitProviderKind is
     * discoverable); force it so direct REGISTRY consumers see the entries.
     * MUST be the LAST static field (re-entrant init reads REGISTRY above).
     */
    @SuppressWarnings("unused")
    private static final Object AUTO_LOAD_TRIGGER =
            be.elevenways.protoblast.generated.BlastAutoLoadInit.loaded;

    private GitProviderKindRegistry() {}
}
