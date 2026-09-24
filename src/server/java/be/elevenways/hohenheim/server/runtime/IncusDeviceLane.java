package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.server.runtime.IncusDefinitions.ROOT_DEVICE;
import static be.elevenways.hohenheim.server.runtime.IncusDefinitions.USER_PREFIX;

/**
 * The volume and device lane of {@link IncusInstanceRuntime}: the root disk's size, extra
 * disks, NICs and install media, each written onto the instance and read back.
 *
 * AIDEV-NOTE: split out of IncusInstanceRuntime mechanically; the runtime still implements
 * {@link DeviceAttachSupport}, {@link RootDiskSizeSupport} and {@link RootDiskUsageSupport}
 * and delegates here, so callers probing for those capabilities see no change.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
final class IncusDeviceLane {

    /** Boot priority of the workload's own root disk while install media is attached. */
    static final String ROOT_BOOT_PRIORITY = "10";

    /** Boot priority of an attached install-media CD-ROM (below the root disk's). */
    static final String CDROM_BOOT_PRIORITY = "5";

    private final @NonNull IncusInstanceRuntime runtime;
    private final @NonNull IncusClient incus;
    private final @NonNull IncusNetworkPolicy policy;
    private final @NonNull Egress egress;
    private final @NonNull IncusWorkloadType type;

    IncusDeviceLane(@NonNull IncusInstanceRuntime runtime, @NonNull IncusClient incus,
                    @NonNull IncusNetworkPolicy policy, @NonNull Egress egress,
                    @NonNull IncusWorkloadType type) {
        this.runtime = runtime;
        this.incus = incus;
        this.policy = policy;
        this.egress = egress;
        this.type = type;
    }

    // -- the root disk ----------------------------------------------------------

    /** The root disk device override: the default profile's pool, our declared size. */
    @NonNull Map<String, Object> rootDevice(int sizeGb) throws IOException {
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("type", "disk");
        device.put("path", "/");
        device.put("pool", managedPoolName());
        device.put("size", sizeGb + "GiB");
        return device;
    }

    /** Read back what the daemon DECLARES for the root device after a write that set it. */
    void verifyRootDiskDeclared(@NonNull InstanceSpec spec) throws IOException {
        Integer declared = spec.rootDiskGb();
        if (declared == null) {
            return;
        }
        Integer actual = rootDiskGb(spec);
        if (actual == null || !actual.equals(declared)) {
            throw new IOException("Root disk of '" + spec.handle() + "' was accepted at "
                + declared + "GiB but the daemon reports " + actual + "GiB");
        }
    }

    @Nullable Integer rootDiskGb(@NonNull InstanceSpec spec) throws IOException {
        Map<String, Object> instance = this.incus.instance(spec.handle());
        if (instance.get("devices") instanceof Map<?, ?> devices
                && devices.get(ROOT_DEVICE) instanceof Map<?, ?> root) {
            return IncusDefinitions.parseSizeGb(root.get("size"));
        }
        return null;
    }

    /**
     * The daemon's OBSERVED root-disk figures, straight out of the instance state.
     *
     * AIDEV-NOTE: {@code total} is 0 when the workload declares no root size, and it is
     * passed through as 0 rather than substituted with the pool's capacity. A workload with
     * no enforced ceiling genuinely has no percentage, and inventing one would produce a
     * reassuring "3% used" for storage nothing is rationing.
     */
    RootDiskUsageSupport.@Nullable DiskUsage rootDiskUsage(@NonNull InstanceSpec spec)
            throws IOException {
        Map<String, Object> state;
        try {
            state = this.incus.instanceState(spec.handle());
        } catch (IncusClient.ApiException e) {
            if (e.isNotFound()) {
                return null;   // observed absent: no workload to measure
            }
            throw e;
        }
        if (!"Running".equalsIgnoreCase(String.valueOf(state.get("status")))) {
            return null;
        }
        if (!(state.get("disk") instanceof Map<?, ?> disks
                && disks.get(ROOT_DEVICE) instanceof Map<?, ?> root)) {
            return null;
        }
        Object usage = root.get("usage");
        if (!(usage instanceof Number used)) {
            return null;   // no figure is not a zero figure
        }
        long total = root.get("total") instanceof Number number ? number.longValue() : 0;
        return new RootDiskUsageSupport.DiskUsage(used.longValue(), Math.max(0, total));
    }

    void resizeRootDisk(@NonNull InstanceSpec spec, int sizeGb) throws IOException {
        ContainerState state = this.runtime.status(spec.handle()).state();
        if (state != ContainerState.STOPPED) {
            // The load-bearing guard, not a convenience: a running grow is ACCEPTED and
            // not performed, and the accepted config then blocks the correct retry.
            throw new IOException("REFUSED to resize the root disk of '" + spec.handle()
                + "': the workload is " + state + " and a root disk can only be resized"
                + " while it is STOPPED. Stop it and deploy again.");
        }
        Integer current = rootDiskGb(spec);
        if (current != null && sizeGb < current) {
            throw new IOException("REFUSED to shrink the root disk of '" + spec.handle()
                + "' from " + current + "GiB to " + sizeGb + "GiB: a root disk can only"
                + " grow. Create a smaller workload and migrate the data instead.");
        }
        putDevice(spec.handle(), ROOT_DEVICE, rootDevice(sizeGb));
        Integer actual = rootDiskGb(spec);
        if (actual == null || actual != sizeGb) {
            throw new IOException("Resize of the root disk of '" + spec.handle() + "' to "
                + sizeGb + "GiB did not take: the daemon reports " + actual + "GiB");
        }
    }

    /**
     * Bring an EXISTING workload's root disk to its declared size, or refuse by name.
     *
     * A declaration that already matches costs one read and does nothing; a declaration
     * that is absent leaves the daemon alone entirely (the knob is opt-in, and clearing
     * it must not silently shrink anything).
     *
     * AIDEV-NOTE: known limitation, and it belongs to the daemon rather than to us. If
     * something OUTSIDE this product grows a RUNNING instance's root device, Incus 7.3
     * records the new size and does not apply it, and every API read-back then echoes
     * the recorded value. This reconcile would see "already at the declared size" and
     * skip -- correctly, by every fact it can obtain. Refusing a running grow HERE is
     * what keeps the product from creating that state; it cannot repair one it did not
     * create. The only detection is inside the guest.
     */
    void reconcileRootDisk(@NonNull InstanceSpec spec) throws IOException {
        Integer declared = spec.rootDiskGb();
        if (declared == null) {
            return;
        }
        Integer current = rootDiskGb(spec);
        if (current != null && current.equals(declared)) {
            return;
        }
        resizeRootDisk(spec, declared);
    }

    // -- extra devices -------------------------------------------------------------

    void ensureDisk(@NonNull InstanceSpec spec, @NonNull String deviceName, int sizeGb)
            throws IOException {
        String pool = managedPoolName();
        String volumeName = IncusInstanceRuntime.volumeNameOf(spec, deviceName);
        Map<String, Object> existing = this.incus.customVolume(pool, volumeName);
        if (existing == null) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("size", sizeGb + "GiB");
            // The attribution doctrine holds for volumes exactly as for workloads:
            // owner labels land at CREATE, so a crash between volume and device leaves
            // the volume attributable and a same-named stranger is refused below.
            spec.ownerLabels().forEach((key, value) -> config.put(USER_PREFIX + key, value));
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("name", volumeName);
            definition.put("content_type", "block");
            definition.put("config", config);
            this.incus.createCustomVolume(pool, definition);
        } else {
            requireOwnedVolume(spec, volumeName, existing);
        }

        Map<String, Object> device = new LinkedHashMap<>();
        device.put("type", "disk");
        device.put("pool", pool);
        device.put("source", volumeName);
        putDevice(spec.handle(), deviceName, device);
        requireDevicePresent(spec.handle(), deviceName);
    }

    void resizeDisk(@NonNull InstanceSpec spec, @NonNull String deviceName, int sizeGb)
            throws IOException {
        String pool = managedPoolName();
        String volumeName = IncusInstanceRuntime.volumeNameOf(spec, deviceName);
        Map<String, Object> existing = this.incus.customVolume(pool, volumeName);
        if (existing == null) {
            throw new IOException("Volume '" + volumeName + "' does not exist on pool '"
                + pool + "'; nothing to resize");
        }
        requireOwnedVolume(spec, volumeName, existing);
        Map<String, Object> config = new LinkedHashMap<>();
        if (existing.get("config") instanceof Map<?, ?> current) {
            current.forEach((key, value) -> config.put(String.valueOf(key), value));
        }
        config.put("size", sizeGb + "GiB");
        this.incus.updateCustomVolume(pool, volumeName, Map.of("config", config));
        // Read-back verification: "the API said yes" and "the daemon did it" are
        // independent facts for a resize too.
        Integer actual = diskSizeGb(spec, deviceName);
        if (actual == null || actual != sizeGb) {
            throw new IOException("Resize of volume '" + volumeName + "' to " + sizeGb
                + "GiB did not take: the daemon reports " + actual + "GiB");
        }
    }

    void ensureNic(@NonNull InstanceSpec spec, @NonNull String deviceName) throws IOException {
        // The same throwing appliers as the primary NIC: ACL verified in the daemon,
        // the extra bridge verified managed-with-subnet, BEFORE the device lands.
        this.policy.ensureIsolationAcl();
        this.policy.ensureExtraNetwork();
        this.runtime.stampPresence();
        putDevice(spec.handle(), deviceName,
            this.policy.extraNicDevice(this.egress, spec.networkLimitMbit()));
        this.runtime.verifyIsolated(spec);
    }

    /**
     * AIDEV-NOTE: the boot-order policy is ENCODED here, not exposed as a knob, and the
     * numbers are the measured ones from docs/prepare-windows-template.md step 5: the
     * firmware only lists a CD that carries a boot.priority, and the DISK must hold the
     * HIGHER one -- while it is blank the firmware falls through to the CD, and the
     * moment the OS makes it bootable the installer stops being re-entered from the
     * media (booting the CD first again after the first-phase reboot strands Windows
     * Setup on its "started an upgrade" question, a full boot cycle to discover).
     */
    void ensureCdrom(@NonNull InstanceSpec spec, @NonNull String deviceName,
                     @NonNull String mediaVolume) throws IOException {
        if (this.type != IncusWorkloadType.VIRTUAL_MACHINE) {
            throw new IOException("Instance '" + spec.handle() + "' is a "
                + this.type.apiType() + "; only a virtual machine can boot install media");
        }
        String pool = managedPoolName();
        Map<String, Object> media = this.incus.customVolume(pool, mediaVolume);
        if (media == null) {
            throw new IOException("Install media volume '" + mediaVolume + "' does not"
                + " exist on pool '" + pool + "' of this host; import the ISO on this"
                + " host first (the media surface on the server record).");
        }
        if (!"iso".equals(media.get("content_type"))) {
            throw new IOException("Volume '" + mediaVolume + "' on pool '" + pool
                + "' is not an ISO volume (content_type "
                + media.get("content_type") + "); refusing to attach it as install media");
        }
        Map<String, Object> cdrom = new LinkedHashMap<>();
        cdrom.put("type", "disk");
        cdrom.put("pool", pool);
        cdrom.put("source", mediaVolume);
        cdrom.put("boot.priority", CDROM_BOOT_PRIORITY);
        putDevice(spec.handle(), deviceName, cdrom);
        ensureRootBootPriority(spec.handle());
        requireDevicePresent(spec.handle(), deviceName);
    }

    /** Stamp the root disk's boot priority ABOVE the media's (see ensureCdrom's note). */
    private void ensureRootBootPriority(@NonNull String handle) throws IOException {
        Map<String, Object> instance = this.incus.instance(handle);
        if (instance.get("devices") instanceof Map<?, ?> devices
                && devices.get(ROOT_DEVICE) instanceof Map<?, ?> root
                && ROOT_BOOT_PRIORITY.equals(root.get("boot.priority"))) {
            return;
        }
        this.incus.editInstance(handle, (config, devices) -> {
            Map<String, Object> root = new LinkedHashMap<>();
            if (devices.get(ROOT_DEVICE) instanceof Map<?, ?> existing) {
                existing.forEach((key, value) -> root.put(String.valueOf(key), value));
            } else {
                // No instance-level root override yet (the profile's root applies): mint
                // the minimal one so the priority has a device to ride on.
                root.put("type", "disk");
                root.put("path", "/");
                root.put("pool", managedPoolName());
            }
            root.put("boot.priority", ROOT_BOOT_PRIORITY);
            devices.put(ROOT_DEVICE, root);
        });
    }

    void removeDevice(@NonNull InstanceSpec spec, @NonNull String deviceName, boolean hasVolume)
            throws IOException {
        Map<String, Object> instance;
        try {
            instance = this.incus.instance(spec.handle());
        } catch (IncusClient.ApiException e) {
            if (!e.isNotFound()) {
                throw e;
            }
            instance = null;   // workload gone: only the volume can remain
        }
        if (instance != null && instance.get("devices") instanceof Map<?, ?> devices
                && devices.get(deviceName) != null) {
            this.incus.editInstance(spec.handle(), (config, current) -> current.remove(deviceName));
        }
        if (hasVolume) {
            deleteVolumes(spec, List.of(deviceName));
        }
    }

    void deleteVolumes(@NonNull InstanceSpec spec, @NonNull List<String> deviceNames)
            throws IOException {
        String pool = managedPoolName();
        for (String deviceName : deviceNames) {
            String volumeName = IncusInstanceRuntime.volumeNameOf(spec, deviceName);
            Map<String, Object> existing = this.incus.customVolume(pool, volumeName);
            if (existing == null) {
                continue;   // observed absent, which is what delete exists to establish
            }
            // Never a stranger's data over a name collision -- the volume-side twin of
            // the workload attribution refusal.
            requireOwnedVolume(spec, volumeName, existing);
            try {
                this.incus.deleteCustomVolume(pool, volumeName);
            } catch (IncusClient.ApiException e) {
                if (!e.isNotFound()) {
                    throw e;
                }
            }
            if (this.incus.customVolume(pool, volumeName) != null) {
                throw new IOException("Volume '" + volumeName + "' still exists on pool '"
                    + pool + "' after its delete was accepted");
            }
        }
    }

    @Nullable Integer diskSizeGb(@NonNull InstanceSpec spec, @NonNull String deviceName)
            throws IOException {
        Map<String, Object> volume = this.incus.customVolume(managedPoolName(),
            IncusInstanceRuntime.volumeNameOf(spec, deviceName));
        if (volume == null || !(volume.get("config") instanceof Map<?, ?> config)) {
            return null;
        }
        return IncusDefinitions.parseSizeGb(config.get("size"));
    }

    /** Write ONE device onto the instance definition (read-modify-write, NIC untouched). */
    private void putDevice(@NonNull String handle, @NonNull String deviceName,
                           @NonNull Map<String, Object> device) throws IOException {
        this.incus.editInstance(handle, (config, devices) -> devices.put(deviceName, device));
    }

    /** Read the instance back and require the device the write just claimed to add. */
    private void requireDevicePresent(@NonNull String handle, @NonNull String deviceName)
            throws IOException {
        Map<String, Object> instance = this.incus.instance(handle);
        boolean present = instance.get("devices") instanceof Map<?, ?> devices
            && devices.get(deviceName) != null;
        if (!present) {
            throw new IOException("Device '" + deviceName + "' of '" + handle
                + "' was accepted but does not read back on the instance");
        }
    }

    /** @throws IOException when the volume's user.* labels do not attribute it to this record */
    private static void requireOwnedVolume(@NonNull InstanceSpec spec,
                                           @NonNull String volumeName,
                                           @NonNull Map<String, Object> volume)
            throws IOException {
        OwnerLabels.Owner want = OwnerLabels.parse(spec.ownerLabels());
        OwnerLabels.Owner actual = IncusDefinitions.ownerOf(volume);
        if (!OwnerLabels.matches(actual, want)) {
            throw new IOException("REFUSED to touch volume '" + volumeName + "': the daemon"
                + " does not attribute it to this record ("
                + IncusDefinitions.foreignOwner(actual)
                + "). A same-named foreign volume is a name collision, not a leftover.");
        }
    }

    private @NonNull String managedPoolName() throws IOException {
        return IncusDefinitions.managedPoolName(this.incus);
    }
}
