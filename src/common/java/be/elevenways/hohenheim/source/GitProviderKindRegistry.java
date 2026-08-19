package be.elevenways.hohenheim.source;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.registry.Registry;

/**
 * Central registry for all git provider kinds. Drives the GitProviderModel's
 * RegistryEnumField, its per-kind settings sub-schema, the admin UI kind selector and
 * the server's client construction -- one home, so adding a kind is one class.
 */
public final class GitProviderKindRegistry {

    public static final Registry<GitProviderKindInfo> REGISTRY =
        new Registry.Simple<>(Identifier.of("hohenheim", "git_provider_kinds"));

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
