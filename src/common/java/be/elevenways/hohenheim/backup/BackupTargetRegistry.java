package be.elevenways.hohenheim.backup;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.registry.Registry;

/**
 * Central registry for backup-target kinds. Drives the BackupTargetModel's
 * RegistryEnumField, the admin kind selector, and the server's target dispatch.
 */
public final class BackupTargetRegistry {

    public static final Registry<BackupTargetInfo> REGISTRY =
        new Registry.Simple<>(Identifier.of("hohenheim", "backup_target_kinds"));

    /**
     * Entries arrive via the generated BlastAutoLoadInit (BackupTargetKindHandler is
     * discoverable); force it so direct REGISTRY consumers see the entries.
     * MUST be the LAST static field (re-entrant init reads REGISTRY above).
     */
    @SuppressWarnings("unused")
    private static final Object AUTO_LOAD_TRIGGER =
            be.elevenways.protoblast.generated.BlastAutoLoadInit.loaded;

    private BackupTargetRegistry() {}
}
