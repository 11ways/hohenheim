package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The NATIVE snapshot half of the driver seam, for runtimes whose persistent state is
 * the whole instance (an Incus system container's rootfs) rather than named volumes:
 * point-in-time snapshots live AT THE DAEMON in the instance's own storage pool, and a
 * backup is the daemon's own whole-instance export tarball. A driver implements
 * exactly one of this or {@link VolumeSnapshotSupport}; a driver with neither gets the
 * named {@code snapshots_unsupported} refusal.
 *
 * AIDEV-NOTE: consistency model. The volume lane captures COLD (the services stop the
 * workload for the copy); this lane captures LIVE and is CRASH-CONSISTENT -- the
 * storage driver's own atomic snapshot stands in for the stop, which is the standard
 * LXD/Incus/Proxmox nightly-snapshot semantic. That difference is this interface's
 * declared contract, not a per-call option.
 */
public interface NativeSnapshotSupport {

    /**
     * Create the named daemon-side snapshot of the instance (running or stopped).
     *
     * @throws IOException when the workload is absent or the daemon refuses
     */
    void createSnapshot(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException;

    /**
     * Whether the named snapshot exists at the daemon RIGHT NOW -- restore verifies
     * this before touching any live state.
     *
     * @throws IOException when the daemon cannot be asked (absent answers false)
     */
    boolean snapshotExists(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException;

    /**
     * Roll the STOPPED instance back to the named snapshot (a REPLACE of its whole
     * state, never a merge). The caller settles the workload first.
     */
    void restoreSnapshot(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException;

    /**
     * Remove the named daemon-side snapshot; observed-absent is success, an
     * unreachable or refusing daemon is not.
     */
    void deleteSnapshot(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException;

    /**
     * Export the whole instance as the daemon's own portable backup tarball
     * (crash-consistent, pool-format-independent), written to {@code destination}.
     *
     * @param maxBytes cap on the exported artifact, enforced during the download
     * @return the exported size in bytes
     */
    long exportBackup(@NonNull InstanceSpec spec, @NonNull Path destination, long maxBytes)
            throws IOException;

    /**
     * Import an exported tarball as a NEW stopped instance under {@code spec.handle()}
     * and stamp {@code spec}'s owner labels onto it -- the import carries the SOURCE
     * instance's labels, and an unattributable window would defeat every ownership
     * guard, so re-attribution is part of this contract, not a caller nicety.
     */
    void importBackup(@NonNull InstanceSpec spec, @NonNull Path archive) throws IOException;

    /**
     * The image identity the workload actually runs (for the backup manifest).
     *
     * @throws IOException when the workload is absent or the daemon unreachable
     */
    @NonNull ImageIdentity imageIdentity(@NonNull InstanceSpec spec) throws IOException;
}
