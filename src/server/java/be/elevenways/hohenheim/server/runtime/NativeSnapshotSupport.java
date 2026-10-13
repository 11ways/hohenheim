package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.instance.DeviceType;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
public interface NativeSnapshotSupport extends WorkloadAttribution {

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
     * @param withSnapshots true carries the daemon-side snapshots inside the tarball
     *                      (the migration lane, so snapshot records survive the move);
     *                      false exports the instance only (the backup lane's shape)
     * @return the exported size in bytes
     */
    long exportBackup(@NonNull InstanceSpec spec, @NonNull Path destination, long maxBytes,
                      boolean withSnapshots) throws IOException;

    /**
     * Export every data disk the workload's definition attaches as the daemon's own portable volume tarball in
     * {@code directory}: the instance export only NAMES them, so a backup carries their contents beside it.
     *
     * AIDEV-NOTE: each disk is captured crash-consistent on its own, right after the instance export; the set is
     * not one atomic point in time (the daemon snapshots each volume separately), which a guest writing across
     * disks during the capture would see.
     *
     * @param maxBytes cap on each exported disk, enforced during the download
     * @return the exported disks in definition order, empty for none
     * @throws IOException when a disk cannot be exported or its declared size is unknown
     */
    @NonNull List<ExportedDisk> exportDisks(@NonNull InstanceSpec spec, @NonNull Path directory, long maxBytes)
        throws IOException;

    /**
     * One exported data disk.
     *
     * @param device the device name in the workload's definition
     * @param size   the exported file's size in bytes
     * @param sizeGb the volume's declared size
     */
    record ExportedDisk(@NonNull String device, @NonNull Path file, long size, int sizeGb) {
    }

    /**
     * Import an exported tarball as a NEW stopped instance under {@code spec.handle()}
     * and stamp {@code spec}'s owner labels onto it -- the import carries the SOURCE
     * instance's labels, and an unattributable window would defeat every ownership
     * guard, so re-attribution is part of this contract, not a caller nicety.
     *
     * @param disks the exported data disks by device name: each lands first as a new volume under {@code spec}'s
     *              own device-volume naming, attributed to {@code spec}, and the imported device is pointed at it; a
     *              disk device without an entry keeps naming the source's volume
     * @param extraNics whether the definition may carry an extra network interface: the controller's shared objects
     *                  it names (the extra-NIC bridge) are ensured on this daemon before the import, as attach and
     *                  deploy ensure them, because the daemon validates every device while it creates the instance
     * @return the devices the imported definition carries that Hohenheim models as device rows, in definition order
     *         (empty for none): the export keeps them, so the new record's rows are written from this answer
     */
    @NonNull List<ImportedDevice> importBackup(@NonNull InstanceSpec spec, @NonNull Path archive,
                                               @NonNull Map<String, Path> disks, boolean extraNics)
        throws IOException;

    /**
     * One device an imported definition carries, as the device-row vocabulary names it.
     *
     * @param source the volume the device names (an ISO for a cdrom, a custom volume for a disk), null for a NIC
     */
    record ImportedDevice(@NonNull String name, @NonNull DeviceType type, @Nullable String source) {
    }

    /**
     * The image identity the workload actually runs (for the backup manifest).
     *
     * @throws IOException when the workload is absent or the daemon unreachable
     */
    @NonNull ImageIdentity imageIdentity(@NonNull InstanceSpec spec) throws IOException;
}
