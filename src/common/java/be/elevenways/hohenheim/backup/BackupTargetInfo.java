package be.elevenways.hohenheim.backup;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.WordedKind;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Common backup-target-kind metadata (the InstanceKindInfo shape): lives in src/common
 * so the admin UI can enumerate kinds without server dependencies. The kind is the ONE
 * discriminator of a backup target ({@code filesystem} and {@code ssh} now; object
 * storage reserved), driving the RegistryMemberField and the schemaFrom settings form.
 */
public interface BackupTargetInfo extends WordedKind {

    @Override
    default @NonNull HohenheimMicrocopy labelScope() {
        return HohenheimMicrocopy.BACKUP_TARGET_KIND;
    }

    @Override
    default @NonNull HohenheimMicrocopy descriptionScope() {
        return HohenheimMicrocopy.BACKUP_TARGET_KIND_DESCRIPTION;
    }
}
