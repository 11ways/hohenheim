package be.elevenways.hohenheim.backup;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.registry.Registry;

/**
 * Central registry for backup-target kinds. Drives the BackupTargetModel's
 * RegistryMemberField, the admin kind selector, and the server's target dispatch.
 */
public final class BackupTargetRegistry {

    public static final Registry<BackupTargetInfo> REGISTRY =
        Registry.create(HohenheimIds.id("backup_target_kind"));

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
