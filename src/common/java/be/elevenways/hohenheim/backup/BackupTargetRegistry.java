package be.elevenways.hohenheim.backup;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.registry.Registry;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Central registry for backup-target kinds. Drives the BackupTargetModel's
 * RegistryMemberField, the admin kind selector, and the server's target dispatch.
 *
 * AIDEV-NOTE: an autoloaded Registry: every read runs the index first, so {@link #REGISTRY} and {@link #registry()}
 * are the same object and no accessor calls Blast.ensureAutoLoaded.
 */
public final class BackupTargetRegistry {

    public static final Registry<BackupTargetInfo> REGISTRY =
        Registry.autoLoaded(HohenheimIds.id("backup_target_kind"));

    private BackupTargetRegistry() {}

    /** @return the live registry */
    public static @NonNull Registry<BackupTargetInfo> registry() {
        return REGISTRY;
    }
}
