package be.elevenways.hohenheim.server.runtime;

import java.io.IOException;
import java.util.List;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Capability: attachable extra devices (disks as daemon-side custom volumes, extra
 * NICs) with resize -- the declared-capability shape of {@link NativeSnapshotSupport}.
 * A runtime without it makes InstanceDevices refuse BY NAME; every method here is an
 * ENSURE (idempotent), because the deploy path reconciles the desired rows onto a
 * possibly-recreated workload.
 *
 * The network policy is part of this contract: an ensured NIC carries the same
 * verified isolation ACL as the primary one -- an extra NIC must never be a hole in
 * the tenant boundary.
 */
public interface DeviceAttachSupport {

    /**
     * Ensure a data disk of {@code sizeGb} exists (an owner-labelled custom volume
     * named for the workload and device) and is attached under {@code deviceName},
     * read-back verified.
     *
     * @throws IOException when the daemon refuses, or an existing same-named volume is
     *                     not attributed to this record
     */
    void ensureDisk(@NonNull InstanceSpec spec, @NonNull String deviceName, int sizeGb)
        throws IOException;

    /**
     * Grow (or shrink, where the daemon allows it) the disk behind {@code deviceName},
     * read-back verified.
     *
     * @throws IOException the daemon's own refusal verbatim -- notably "In use" while
     *                     the workload runs (block volumes resize stopped only) and the
     *                     shrink refusal
     */
    void resizeDisk(@NonNull InstanceSpec spec, @NonNull String deviceName, int sizeGb)
        throws IOException;

    /**
     * Ensure an extra NIC named {@code deviceName} is attached with the isolation
     * policy enforced and read-back verified (the daemon refuses a second NIC on the
     * primary network, so extra NICs land on the driver's managed secondary network).
     */
    void ensureNic(@NonNull InstanceSpec spec, @NonNull String deviceName) throws IOException;

    /**
     * Ensure the named install-media ISO volume is attached as a CD-ROM device under
     * {@code deviceName}, read-back verified. The media volume is OPERATOR-published
     * shared state: attaching references it, detaching ({@link #removeDevice} with
     * {@code hasVolume=false}) never deletes it.
     *
     * The implementation also owns the learned boot-order policy (the
     * prepare-windows-template findings): the workload's own root disk keeps the HIGHER
     * boot priority, so a blank disk falls through to the CD and the moment the OS makes
     * the disk bootable the installer stops being re-entered from the media.
     *
     * @throws IOException when the daemon refuses, the media volume is absent on this
     *                     host, or the workload flavour cannot boot media (containers)
     */
    void ensureCdrom(@NonNull InstanceSpec spec, @NonNull String deviceName,
                     @NonNull String mediaVolume) throws IOException;

    /**
     * Detach one device and delete its backing volume when one exists, VERIFIED:
     * confirmed removed or observed absent, the destroy contract.
     *
     * @throws IOException when the daemon is unreachable or refuses -- not gone
     */
    void removeDevice(@NonNull InstanceSpec spec, @NonNull String deviceName,
                      boolean hasVolume) throws IOException;

    /**
     * Delete the backing volumes of the named devices after the workload itself is
     * gone (the destroy path), each verified removed-or-absent.
     *
     * @throws IOException when any volume could not be confirmed gone
     */
    void deleteVolumes(@NonNull InstanceSpec spec, @NonNull List<String> deviceNames)
        throws IOException;

    /** The daemon-side size in GB of the disk behind {@code deviceName}, or null when absent. */
    @Nullable Integer diskSizeGb(@NonNull InstanceSpec spec, @NonNull String deviceName)
        throws IOException;
}
