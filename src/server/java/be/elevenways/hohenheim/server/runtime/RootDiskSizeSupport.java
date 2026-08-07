package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;

/**
 * Capability: a per-workload ROOT disk quota the daemon really enforces -- the
 * declared-capability shape of {@link DeviceAttachSupport}, for the one device a
 * workload cannot detach. A runtime without it must refuse a spec that declares
 * {@code rootDiskGb} BY NAME; it must never accept the number and ignore it.
 *
 * AIDEV-NOTE: the root disk is deliberately NOT an {@code instance_devices} row. It has
 * no attach/detach lifecycle, it cannot be moved between workloads, and it is created by
 * the image rather than by us -- modelling it as a device would give it the wrong quota
 * release, the wrong reconcile and a detach that cannot exist. It is a SETTING with its
 * own charge (InstanceRootDiskQuota) into the SAME disk-GB bucket, so an owner's disk cap
 * covers what they actually occupy.
 */
public interface RootDiskSizeSupport {

    /**
     * The size in GB the DAEMON reports for the workload's root device, or null when it
     * declares none (inheriting the profile/image default).
     *
     * AIDEV-NOTE: this reads the daemon's DECLARED value, which on Incus 7.3 is not the
     * same fact as the volume's real size -- see {@link #resizeRootDisk} for the
     * measurement. Never quote it as proof a resize landed while the workload ran.
     */
    @Nullable Integer rootDiskGb(@NonNull InstanceSpec spec) throws IOException;

    /**
     * Grow the workload's root disk to {@code sizeGb}.
     *
     * MEASURED on Incus 7.3 + btrfs (daystrom, 2026-08-07): growing a RUNNING VM's root
     * device returns success and updates the daemon's config while the backing volume
     * and the guest's block device stay at the old size -- and because Incus then keys
     * the work off that config value, the correct retry to the same size while stopped
     * is silently skipped, so the lie is permanent. Every implementation must therefore
     * refuse unless the workload is stopped.
     *
     * @throws IOException when the workload is not stopped, when the request would
     *                     shrink the volume (block volumes cannot shrink), or when the
     *                     daemon refuses -- its own message verbatim
     */
    void resizeRootDisk(@NonNull InstanceSpec spec, int sizeGb) throws IOException;
}
