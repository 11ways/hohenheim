package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.instance.DeviceType;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.time.Now;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.server.runtime.IncusDefinitions.USER_PREFIX;

/**
 * The snapshot, backup and image lane of {@link IncusInstanceRuntime}: the daemon's own
 * pool-resident snapshots, whole-instance export and import, and publishing a stopped
 * workload as an image.
 *
 * AIDEV-NOTE: split out of IncusInstanceRuntime mechanically; the runtime still implements
 * {@link NativeSnapshotSupport} and {@link ImagePublishSupport} and delegates here.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
final class IncusArchiveLane {

    private final @NonNull IncusInstanceRuntime runtime;
    private final @NonNull IncusClient incus;
    private final @NonNull IncusNetworkPolicy policy;
    private final @NonNull Egress egress;

    IncusArchiveLane(@NonNull IncusInstanceRuntime runtime, @NonNull IncusClient incus,
                     @NonNull IncusNetworkPolicy policy, @NonNull Egress egress) {
        this.runtime = runtime;
        this.incus = incus;
        this.policy = policy;
        this.egress = egress;
    }

    void createSnapshot(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException {
        this.incus.createSnapshot(spec.handle(), snapshotName);
    }

    boolean snapshotExists(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException {
        try {
            this.incus.snapshot(spec.handle(), snapshotName);
            return true;
        } catch (IncusClient.ApiException e) {
            if (e.isNotFound()) {
                return false;
            }
            throw e;
        }
    }

    void restoreSnapshot(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException {
        this.incus.restoreSnapshot(spec.handle(), snapshotName);
    }

    void deleteSnapshot(@NonNull InstanceSpec spec, @NonNull String snapshotName)
            throws IOException {
        try {
            this.incus.deleteSnapshot(spec.handle(), snapshotName);
        } catch (IncusClient.ApiException e) {
            if (!e.isNotFound()) {
                throw e;   // refused: NOT gone
            }
            // 404 = observed absent, which is what delete exists to establish.
        }
    }

    long exportBackup(@NonNull InstanceSpec spec, @NonNull Path destination, long maxBytes,
                      boolean withSnapshots) throws IOException {
        return exportThroughTemporary(spec.handle(),
            backupName -> this.incus.createBackup(spec.handle(), backupName, !withSnapshots),
            backupName -> this.incus.exportBackup(spec.handle(), backupName, destination, maxBytes),
            backupName -> this.incus.deleteBackup(spec.handle(), backupName));
    }

    /**
     * Export the volume behind each data disk the definition attaches (the classification
     * {@link IncusDeviceLane#importedDevices} reads back), files named by position so no device name reaches a path.
     */
    @NonNull List<NativeSnapshotSupport.ExportedDisk> exportDisks(@NonNull InstanceSpec spec, @NonNull Path directory,
                                                                  long maxBytes) throws IOException {
        Map<String, Object> instance = this.incus.instance(spec.handle());
        List<NativeSnapshotSupport.ExportedDisk> exported = new ArrayList<>();
        for (NativeSnapshotSupport.ImportedDevice device : IncusDeviceLane.importedDevices(instance)) {
            if (device.type() != DeviceType.DISK || device.source() == null) {
                continue;
            }
            String volume = device.source();
            String named = IncusDeviceLane.poolOf(instance, device.name());
            String pool = named != null ? named : IncusDefinitions.managedPoolName(this.incus);
            Map<String, Object> definition = this.incus.customVolume(pool, volume);
            if (definition == null) {
                throw new IOException("Data disk '" + device.name() + "' of '" + spec.handle() + "' names volume '"
                    + volume + "', which pool '" + pool + "' does not hold");
            }
            Integer sizeGb = definition.get("config") instanceof Map<?, ?> config
                ? IncusDefinitions.parseSizeGb(config.get("size")) : null;
            if (sizeGb == null || sizeGb <= 0) {
                throw new IOException("Data disk '" + device.name() + "' (volume '" + volume + "') declares no size,"
                    + " so a restore could not claim it on its host; give the volume a size");
            }
            Path file = directory.resolve("disk-" + (exported.size() + 1) + ".tar");
            long size = exportThroughTemporary(volume,
                backupName -> this.incus.createVolumeBackup(pool, volume, backupName),
                backupName -> this.incus.exportVolumeBackup(pool, volume, backupName, file, maxBytes),
                backupName -> this.incus.deleteVolumeBackup(pool, volume, backupName));
            exported.add(new NativeSnapshotSupport.ExportedDisk(device.name(), file, size, sizeGb));
        }
        return exported;
    }

    /** One call against a named daemon-side backup object. */
    @FunctionalInterface
    private interface BackupStep<T> {
        T apply(@NonNull String backupName) throws IOException;
    }

    /** A step that answers nothing. */
    @FunctionalInterface
    private interface BackupAction {
        void run(@NonNull String backupName) throws IOException;
    }

    /**
     * Create a daemon-side backup object, stream its export, and remove it whatever happened: the object is a
     * TEMPORARY (the export tarball is the artifact), and leaving it behind would silently fill the pool.
     *
     * @return the exported size in bytes
     */
    private long exportThroughTemporary(@NonNull String subject, @NonNull BackupAction create,
                                        @NonNull BackupStep<Long> export, @NonNull BackupAction delete)
            throws IOException {
        String backupName = "hib-" + Now.millis();
        create.run(backupName);
        try {
            return export.apply(backupName);
        } finally {
            try {
                delete.run(backupName);
            } catch (IOException cleanupFailed) {
                Blast.log("INCUS: could not remove temporary backup object", backupName,
                    "of", subject, ":", cleanupFailed.getMessage());
            }
        }
    }

    @NonNull List<NativeSnapshotSupport.ImportedDevice> importBackup(@NonNull InstanceSpec spec,
                                                                     @NonNull Path archive,
                                                                     @NonNull Map<String, Path> disks,
                                                                     boolean extraNics)
            throws IOException {
        // AIDEV-NOTE: the controller's shared objects are ensured BEFORE the import, on THIS
        // daemon, through the runtime's one ensure. The archive carries the source's device
        // config: its eth0 names this controller's isolation ACL (`security.acls`) and an
        // extra NIC names its `hhx-<token>` bridge, and the daemon validates every device
        // while creating the instance record, so a destination that has never placed one of
        // our workloads (or released them with its last instance) refused the whole import:
        // `Network ACL "hohenheim-<token>-isolation" does not exist` (F1, cold migration
        // daystrom -> nightstrom, 2026-08-29) and `Failed to load network "hhx-<token>"`
        // (restore of a VM with an extra NIC, round r2o). This is the SAME ensure create and
        // attach use (conditional write, verified read-back), never a copy of the source's
        // objects -- and doing it here is strictly safer than after the import: a conditional
        // UPDATE of a stale ACL retriggers every referencing NIC, and before the import the
        // clone that could carry a duplicate MAC does not exist yet.
        this.runtime.ensureSharedObjects(extraNics);
        importWithDisks(spec, archive, disks);
        // Re-identification is part of the import contract: the tarball carries the
        // SOURCE instance's user.* labels (until they are replaced the import is
        // attributed to the wrong record -- a crash inside the window leaves an
        // instance the NEW record's next deploy refuses as foreign, visible operator
        // cleanup, never silent adoption) AND the source's volatile NIC MACs, which
        // the daemon refuses beside the still-running source ("MAC address already
        // defined on another NIC"). Dropping the hwaddr keys makes the daemon mint
        // fresh ones at start.
        Map<String, Object> existing = this.incus.instance(spec.handle());
        boolean carriedMacs = existing.get("config") instanceof Map<?, ?> current
            && current.keySet().stream().map(String::valueOf).anyMatch(IncusDefinitions::isVolatileMac);
        IncusClient.DefinitionEdit reidentify = (config, devices) -> {
            config.keySet().removeIf(IncusDefinitions::isVolatileMac);
            spec.ownerLabels().forEach((key, value) -> config.put(USER_PREFIX + key, value));
        };
        // AIDEV-NOTE: the MAC strip is its OWN write, BEFORE the post-import
        // ensureIsolationAcl, and the order is load-bearing. Between import and the
        // strip the clone and its source share a MAC at the daemon, and ANY ACL write in
        // that window makes the daemon re-trigger every referencing NIC and fail 409 on
        // the duplicate (observed live on the source instance, not the clone). This
        // write touches only the clone's own definition -- devices unchanged -- so it
        // cannot trip over other instances.
        if (carriedMacs) {
            this.incus.editInstance(spec.handle(), reidentify);
        }
        // An imported instance re-joins the fleet's isolation exactly like a fresh one:
        // its NIC gets the verified ACL override, so a backup made before isolation
        // existed cannot land an unisolated container. The pre-import ensure above
        // already converged the ACL, so this call writes nothing and is the read-back
        // VERIFICATION that the daemon still carries every tenant-range reject.
        this.policy.ensureIsolationAcl();
        // eth0 and the proxies are rewritten as a converge writes them: the pin the import override placed on this
        // bridge is kept, and every proxy connects to it (the export's proxies still name the source's address).
        this.incus.editInstance(spec.handle(), (config, devices) -> {
            reidentify.apply(config, devices);
            IncusDefinitions.stampNetworkMode(spec, config);
            this.runtime.applyNetworkDevices(spec, devices);
        });
        this.runtime.verifyIsolated(spec);
        // Read off the SAME definition the import landed (the edits above touch config and network devices only).
        return IncusDeviceLane.importedDevices(existing);
    }

    /**
     * Land each disk as a new volume of {@code spec}'s device-volume naming, attributed to {@code spec}, then import
     * the instance with those devices pointed at them and its host-directory devices at {@code spec}'s own
     * directories; a failure takes back every volume this call landed.
     *
     * AIDEV-NOTE: the volumes land FIRST because the daemon validates every device while it creates the imported
     * instance: a disk still naming the source's volume fails the import on a host without it ("Storage volume not
     * found"), and on the source's own host it would share the source's disk. A host-directory device is the same
     * hazard one level down: the export names the SOURCE record's directory, so it is re-pointed through the one
     * derivation create uses ({@link IncusDefinitions#bindDevices}); the caller has restored the directories.
     */
    private void importWithDisks(@NonNull InstanceSpec spec, @NonNull Path archive, @NonNull Map<String, Path> disks)
            throws IOException {
        String pool = disks.isEmpty() ? null : IncusDefinitions.managedPoolName(this.incus);
        List<String> landed = new ArrayList<>();
        Map<String, Map<String, String>> repoint = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Path> disk : disks.entrySet()) {
                String volume = IncusInstanceRuntime.volumeNameOf(spec, disk.getKey());
                if (this.incus.customVolume(pool, volume) != null) {
                    throw new IOException("Volume '" + volume + "' already exists on pool '" + pool
                        + "'; a restore lands a data disk only on a volume it creates");
                }
                this.incus.importVolumeBackup(pool, volume, disk.getValue());
                landed.add(volume);
                attribute(spec, pool, volume);
                repoint.put(disk.getKey(), Map.of("pool", pool, "source", volume));
            }
            // eth0's pinned address is the one key the daemon validates against THIS host at import (its bridge
            // subnet); a proxy's connect is checked only at start, and an override naming a device the export does
            // not carry refuses the whole import, so the proxies are rewritten after it instead.
            repoint.put(IncusNetworkPolicy.NIC, this.runtime.importedNic(spec));
            IncusDefinitions.bindDevices(spec).forEach((device, definition) -> {
                if (definition instanceof Map<?, ?> bind && bind.get("source") instanceof String source) {
                    repoint.put(device, Map.of("source", source));
                }
            });
            this.incus.importInstance(archive, spec.handle(), repoint);
        } catch (IOException | RuntimeException failed) {
            for (String volume : landed) {
                try {
                    this.incus.deleteCustomVolume(pool, volume);
                } catch (IOException cleanupFailed) {
                    failed.addSuppressed(cleanupFailed);
                }
            }
            throw failed;
        }
    }

    /** Replace the owner labels an imported volume carried from its source with {@code spec}'s, read back. */
    private void attribute(@NonNull InstanceSpec spec, @NonNull String pool, @NonNull String volume)
            throws IOException {
        Map<String, Object> imported = this.incus.customVolume(pool, volume);
        Map<String, Object> config = new LinkedHashMap<>();
        if (imported != null && imported.get("config") instanceof Map<?, ?> current) {
            current.forEach((key, value) -> config.put(String.valueOf(key), value));
        }
        spec.ownerLabels().forEach((key, value) -> config.put(USER_PREFIX + key, value));
        this.incus.updateCustomVolume(pool, volume, Map.of("config", config));
        Map<String, Object> readBack = this.incus.customVolume(pool, volume);
        if (readBack == null) {
            throw new IOException("Imported volume '" + volume + "' is gone from pool '" + pool + "'");
        }
        IncusDeviceLane.requireOwnedVolume(spec, volume, readBack);
    }

    /**
     * @throws IOException for a RUNNING or absent workload: publishing a live rootfs
     *         would capture a torn filesystem, and Incus's own refusal for that case is
     *         less specific than the state this driver can already read
     */
    @NonNull String publishImage(@NonNull InstanceSpec spec, @NonNull String alias,
                                 @Nullable String description) throws IOException {
        ContainerState state = this.runtime.status(spec.handle()).state();
        if (state != ContainerState.STOPPED) {
            throw new IOException("REFUSED to publish '" + spec.handle() + "' as an image:"
                + " the workload is " + state + " and only a STOPPED workload captures a"
                + " consistent filesystem. Stop it first.");
        }
        return this.incus.publishImage(spec.handle(), alias, description);
    }
}
