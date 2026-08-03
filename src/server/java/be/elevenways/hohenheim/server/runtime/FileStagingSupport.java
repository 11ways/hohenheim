package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.List;

/**
 * The config-file half of the driver seam (the VolumeSnapshotSupport shape): a runtime
 * that can place rendered config files into a CREATED, not-yet-started workload
 * implements this beside {@link InstanceRuntime}. A driver that cannot simply does not
 * implement it, and the deploy REFUSES with a named violation when files exist --
 * never a silently unstaged config.
 */
public interface FileStagingSupport {

    /** One rendered file: absolute container path, final content, octal mode string. */
    record StagedFile(@NonNull String containerPath, @NonNull String content,
                      @NonNull String mode) {}

    /**
     * Write the files into the workload before it starts (Docker: the archive API, so
     * remote daemons work identically).
     *
     * @throws IOException when a path is unsafe, the workload is absent, or the daemon
     *                     refuses -- staging is all-or-nothing for the caller
     */
    void stageFiles(@NonNull String handle, @NonNull List<StagedFile> files) throws IOException;
}
