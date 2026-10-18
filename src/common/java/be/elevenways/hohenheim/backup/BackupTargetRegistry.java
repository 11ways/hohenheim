package be.elevenways.hohenheim.backup;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Registry;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Central registry for backup-target kinds. Drives the BackupTargetModel's
 * RegistryMemberField, the admin kind selector, and the server's target dispatch.
 *
 * AIDEV-NOTE: every read goes through {@link #registry()}; {@link #REGISTRY} itself is only for fields that hold
 * the registry object (a RegistryMemberField) and for the discovery hook's writes.
 */
public final class BackupTargetRegistry {

    public static final Registry<BackupTargetInfo> REGISTRY =
        Registry.create(HohenheimIds.id("backup_target_kind"));

    private BackupTargetRegistry() {}

    /** Runs the autoload index first so every discovered member is registered. */
    public static @NonNull Registry<BackupTargetInfo> registry() {
        Blast.ensureAutoLoaded();
        return REGISTRY;
    }
}
