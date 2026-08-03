package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The snapshot half of the driver seam: a runtime that can capture and restore an
 * instance's persistent volumes implements this BESIDE {@link InstanceRuntime}. A
 * driver that cannot snapshot simply does not implement it, and the services refuse
 * with a named violation -- never a silent no-op. Incus implements this natively
 * (its own snapshot/export API); the Docker driver implements it over the archive
 * endpoints.
 *
 * All methods speak LOGICAL volume names (the instance settings' own keys), never
 * materialized daemon names -- a backup restored to a NEW instance carries the same
 * logical names onto differently-materialized volumes.
 */
public interface VolumeSnapshotSupport {

    /** One captured volume payload: which volume, where it mounts, and the tar written. */
    record CapturedVolume(@NonNull String name, @NonNull String containerPath,
                          @NonNull Path file, long size) {}

    /** The workload's resolved image identity (mutable ref + immutable daemon id). */
    record ImageIdentity(@NonNull String reference, @Nullable String id) {}

    /**
     * Capture each logical volume's contents into {@code directory} as one tar per
     * volume. The workload must EXIST at the daemon (running or stopped); consistency
     * is the CALLER's contract -- the services stop the workload first (cold capture).
     *
     * @param logicalVolumes logical name to container path
     * @param maxBytesPerVolume per-volume size cap, enforced during the read
     * @throws IOException when the workload is absent, a volume cannot be read, or a
     *                     capture exceeds the cap
     */
    @NonNull List<CapturedVolume> captureVolumes(@NonNull InstanceSpec spec,
                                                 @NonNull Map<String, String> logicalVolumes,
                                                 @NonNull Path directory,
                                                 long maxBytesPerVolume) throws IOException;

    /**
     * Write captured payloads back into the workload's volumes. The workload must exist
     * and be STOPPED, with freshly recreated volumes -- extraction merges, so restoring
     * over live contents would be a lie shaped like a success.
     *
     * @param tars logical volume name to payload tar
     */
    void restoreVolumes(@NonNull InstanceSpec spec,
                        @NonNull Map<String, String> logicalVolumes,
                        @NonNull Map<String, Path> tars) throws IOException;

    /**
     * Remove the materialized volumes behind the given logical names so a restore can
     * recreate them empty. Refuses (IOException) any volume the daemon does not
     * attribute to this record via its owner labels -- a restore must never delete a
     * stranger's data because of a name collision.
     *
     * @throws IOException when a volume is foreign, in use, or the daemon is unreachable
     */
    void removeVolumesForRestore(@NonNull InstanceSpec spec,
                                 @NonNull Map<String, String> logicalVolumes,
                                 @NonNull Collection<String> names) throws IOException;

    /**
     * The image identity the workload actually runs (for the backup manifest).
     *
     * @throws IOException when the workload is absent or the daemon unreachable
     */
    @NonNull ImageIdentity imageIdentity(@NonNull InstanceSpec spec) throws IOException;
}
