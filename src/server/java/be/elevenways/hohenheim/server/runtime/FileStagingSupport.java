package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;

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
     * Write the files into the workload (before its first start, or into a PRESENT
     * container on the materialize-on-change path; Docker: the archive API, so remote
     * daemons work identically). The workload must carry {@code ownerLabels} -- a
     * same-named FOREIGN container is a loud refusal, never a push.
     *
     * @throws IOException when a path is unsafe, the workload is absent or not
     *                     attributably ours, or the daemon refuses -- staging is
     *                     all-or-nothing for the caller
     */
    void stageFiles(@NonNull String handle, @NonNull List<StagedFile> files,
                    @NonNull Map<String, String> ownerLabels) throws IOException;
}
