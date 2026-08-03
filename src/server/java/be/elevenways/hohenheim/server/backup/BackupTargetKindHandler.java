package be.elevenways.hohenheim.server.backup;

import be.elevenways.hohenheim.backup.BackupTargetInfo;
import be.elevenways.protoblast.common.annotation.BlastDiscoverable;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.Map;

/**
 * Server-side half of a backup-target kind: builds the {@link BackupTarget} for a
 * configured record. Implementations are discovered at compile time and register
 * themselves via {@code typeId()} (the InstanceKindHandler shape).
 */
@BlastDiscoverable(registrar = "be.elevenways.hohenheim.server.backup.BackupTargetKinds#register")
public interface BackupTargetKindHandler extends BackupTargetInfo {

    /**
     * The target for one configured record's settings.
     *
     * @throws IOException when the settings are incomplete for this kind
     */
    @NonNull BackupTarget targetFor(@NonNull Map<String, Object> settings) throws IOException;
}
