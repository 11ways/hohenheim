package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.backup.BackupArchive;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.InstanceService.Resolved;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.VolumeSnapshotSupport;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Driver-level snapshots of one instance's volumes: cold capture (the EXPLICIT
 * consistency model -- the workload is stopped for the copy, then restarted through
 * the ordinary deploy funnel so port claims stay honest) and verified in-place
 * restore. Snapshots live on the CONTROLLER host and die with it; they are not
 * backups and the model split enforces that distinction.
 *
 * AIDEV-NOTE: restore REFUSES before changing ANY live state: payload checksums,
 * the settings/inventory mapping and daemon capacity are all verified while the
 * workload still runs untouched. After the point of no return (volume removal) a
 * failure stamps ERROR and names itself -- it never reports success.
 */
public final class InstanceSnapshots {

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final @NonNull InstanceService instances;

    public InstanceSnapshots() {
        this(new InstanceService());
    }

    InstanceSnapshots(@NonNull InstanceService instances) {
        this.instances = instances;
    }

    /**
     * Capture every volume of the instance into a new snapshot (cold: a running
     * workload is stopped for the copy and redeployed after).
     *
     * @return the snapshot row id
     * @throws Violations naming the refusal or failure
     */
    public int create(int instanceId, @Nullable String note) {
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.SNAPSHOTS);
        Resolved resolved = this.instances.resolve(instanceId);
        InstanceOperationGuard.requireOperable(resolved.row());
        VolumeSnapshotSupport support = requireSupport(resolved);
        Map<String, String> volumes = logicalVolumes(resolved);
        if (volumes.isEmpty()) {
            throw refusal("snapshot_no_volumes", resolved.row(), null);
        }
        InstanceStatus live = resolved.runtime().status(resolved.spec().handle());
        requirePresent(live, resolved);
        boolean wasRunning = live.running();

        // Settle: the ordinary stop funnel releases the port claims verified.
        if (wasRunning) {
            TenantWrites.inAuthorizedOperation(() -> this.instances.stop(instanceId));
        }
        long fence = this.instances.leases().requireFence(resolved.serverId());
        InstanceOperationGuard.stamp(this.instances.leases(), instanceId, resolved.serverId(),
            fence, InstanceModel.STATUS_CAPTURING, resolved.row().get(InstanceModel.NAME));

        String stamp = STAMP.format(Instant.now());
        Path directory = snapshotRoot().resolve("instance-" + instanceId).resolve(stamp);
        Row snapshot = Models.get(InstanceSnapshotModel.class).createEmptyRow();
        snapshot.set(InstanceSnapshotModel.INSTANCE_ID, instanceId);
        snapshot.set(InstanceSnapshotModel.STATUS, InstanceSnapshotModel.STATUS_FAILED);
        snapshot.set(InstanceSnapshotModel.NOTE, note);
        snapshot.set(InstanceSnapshotModel.DIRECTORY, directory.toString());
        Models.get(InstanceSnapshotModel.class).save(snapshot);

        try {
            Files.createDirectories(directory);
            var captured = support.captureVolumes(resolved.spec(), volumes, directory,
                maxArchiveBytes());
            Map<String, Map<String, Object>> inventory = new LinkedHashMap<>();
            long total = 0;
            for (VolumeSnapshotSupport.CapturedVolume volume : captured) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", volume.containerPath());
                entry.put("file", volume.file().getFileName().toString());
                entry.put("sha256", BackupArchive.sha256Of(volume.file()));
                entry.put("size", volume.size());
                inventory.put(volume.name(), entry);
                total += volume.size();
            }
            snapshot.set(InstanceSnapshotModel.STATUS, InstanceSnapshotModel.STATUS_COMPLETE);
            snapshot.set(InstanceSnapshotModel.VOLUMES, inventory);
            snapshot.set(InstanceSnapshotModel.TOTAL_BYTES, total);
            Models.get(InstanceSnapshotModel.class).save(snapshot);
        } catch (IOException error) {
            deleteRecursively(directory);
            snapshot.set(InstanceSnapshotModel.ERROR, describe(error));
            Models.get(InstanceSnapshotModel.class).save(snapshot);
            // A failed CAPTURE changed no volume data: hand the workload back rather
            // than leaving it down over a snapshot that did not happen.
            InstanceOperationGuard.stamp(this.instances.leases(), instanceId,
                resolved.serverId(), fence, InstanceModel.STATUS_STOPPED,
                resolved.row().get(InstanceModel.NAME));
            redeployBestEffort(instanceId, wasRunning);
            throw refusal("instance_snapshot_failed", resolved.row(), error);
        }

        InstanceOperationGuard.stamp(this.instances.leases(), instanceId, resolved.serverId(),
            fence, InstanceModel.STATUS_STOPPED, resolved.row().get(InstanceModel.NAME));
        if (wasRunning) {
            TenantWrites.inAuthorizedOperation(() -> this.instances.deploy(instanceId));
        }
        Blast.log("SNAPSHOT: captured instance", instanceId, "into", directory.toString());
        return snapshot.get(InstanceSnapshotModel.ID);
    }

    /**
     * Restore a snapshot IN PLACE: verify everything first (checksums, inventory
     * mapping, capacity), then replace the instance's volumes with the captured
     * contents -- a REPLACE, never a merge, so files created after the snapshot are
     * gone afterwards.
     *
     * @throws Violations naming the refusal or failure
     */
    public void restore(int snapshotId) {
        Row snapshot = Models.get(InstanceSnapshotModel.class).findById(snapshotId);
        if (snapshot == null
                || !InstanceSnapshotModel.STATUS_COMPLETE.equals(
                    snapshot.get(InstanceSnapshotModel.STATUS))) {
            throw Violations.ofForm(violationText("snapshot_not_restorable")
                .withArg("id", snapshotId));
        }
        int instanceId = snapshot.get(InstanceSnapshotModel.INSTANCE_ID);
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.SNAPSHOTS);
        Resolved resolved = this.instances.resolve(instanceId);
        InstanceOperationGuard.requireOperable(resolved.row());
        VolumeSnapshotSupport support = requireSupport(resolved);
        Map<String, String> volumes = logicalVolumes(resolved);

        // -- verification, BEFORE any live state changes -----------------------
        Path directory = Path.of(String.valueOf(
            (Object) snapshot.get(InstanceSnapshotModel.DIRECTORY)));
        Map<String, Map<String, Object>> inventory = inventoryOf(snapshot);
        Map<String, Path> tars = new LinkedHashMap<>();
        long total = 0;
        for (Map.Entry<String, Map<String, Object>> entry : inventory.entrySet()) {
            String declaredPath = volumes.get(entry.getKey());
            Object capturedPath = entry.getValue().get("path");
            if (declaredPath == null || !declaredPath.equals(capturedPath)) {
                throw Violations.ofForm(violationText("snapshot_mismatch")
                    .withArg("volume", entry.getKey())
                    .withArg("name", resolved.row().get(InstanceModel.NAME)));
            }
            Path file = directory.resolve(String.valueOf(entry.getValue().get("file")));
            String expectedSha = String.valueOf(entry.getValue().get("sha256"));
            long expectedSize = entry.getValue().get("size") instanceof Number n
                ? n.longValue() : -1;
            String actualSha;
            long actualSize;
            try {
                actualSize = Files.size(file);
                actualSha = BackupArchive.sha256Of(file);
            } catch (IOException missing) {
                throw Violations.ofForm(violationText("snapshot_corrupt")
                    .withArg("volume", entry.getKey())
                    .withArg("reason", describe(missing)));
            }
            if (actualSize != expectedSize || !actualSha.equals(expectedSha)) {
                throw Violations.ofForm(violationText("snapshot_corrupt")
                    .withArg("volume", entry.getKey())
                    .withArg("reason", "expected sha256 " + expectedSha + " (" + expectedSize
                        + " bytes), found " + actualSha + " (" + actualSize + " bytes)"));
            }
            tars.put(entry.getKey(), file);
            total += actualSize;
        }
        RestoreCapacity.require(resolved.serverId(),
            new ServerService().clientFor(ServerModel.nameOf(resolved.serverId())), total);

        InstanceStatus live = resolved.runtime().status(resolved.spec().handle());
        requirePresent(live, resolved);
        boolean wasRunning = live.running();

        // -- the point of no return --------------------------------------------
        if (wasRunning) {
            TenantWrites.inAuthorizedOperation(() -> this.instances.stop(instanceId));
        }
        long fence = this.instances.leases().requireFence(resolved.serverId());
        InstanceOperationGuard.stamp(this.instances.leases(), instanceId, resolved.serverId(),
            fence, InstanceModel.STATUS_RESTORING, resolved.row().get(InstanceModel.NAME));
        try {
            resolved.runtime().destroy(resolved.spec().handle());
            support.removeVolumesForRestore(resolved.spec(), volumes, tars.keySet());
            resolved.runtime().create(resolved.spec());
            support.restoreVolumes(resolved.spec(), volumes, tars);
        } catch (IOException error) {
            InstanceOperationGuard.stamp(this.instances.leases(), instanceId,
                resolved.serverId(), fence, InstanceModel.STATUS_ERROR,
                resolved.row().get(InstanceModel.NAME));
            throw refusal("instance_restore_failed", resolved.row(), error);
        }
        InstanceOperationGuard.stamp(this.instances.leases(), instanceId, resolved.serverId(),
            fence, InstanceModel.STATUS_STOPPED, resolved.row().get(InstanceModel.NAME));
        if (wasRunning) {
            TenantWrites.inAuthorizedOperation(() -> this.instances.deploy(instanceId));
        }
        Blast.log("SNAPSHOT: restored snapshot", snapshotId, "onto instance", instanceId);
    }

    /** Remove a snapshot's payload files and its row. */
    public void delete(int snapshotId) {
        Row snapshot = Models.get(InstanceSnapshotModel.class).findById(snapshotId);
        if (snapshot == null) {
            return;
        }
        HohenheimAccess.requireOperationCapability(
            snapshot.get(InstanceSnapshotModel.INSTANCE_ID), HohenheimAccess.SNAPSHOTS);
        String directory = snapshot.get(InstanceSnapshotModel.DIRECTORY);
        if (directory != null && !directory.isBlank()) {
            deleteRecursively(Path.of(directory));
        }
        Models.get(InstanceSnapshotModel.class).find()
            .where(InstanceSnapshotModel.ID.eq(snapshotId))
            .delete();
    }

    /** Restart after a failed capture: best effort, the original failure stays primary. */
    private void redeployBestEffort(int instanceId, boolean wasRunning) {
        if (!wasRunning) {
            return;
        }
        try {
            TenantWrites.inAuthorizedOperation(() -> this.instances.deploy(instanceId));
        } catch (RuntimeException redeployFailed) {
            Blast.log("SNAPSHOT: could not restart instance", instanceId,
                "after a failed capture:", describe(redeployFailed));
        }
    }

    // -- shared with InstanceBackups ------------------------------------------

    /** The snapshot half of the driver seam, or a loud named refusal. */
    static @NonNull VolumeSnapshotSupport requireSupport(@NonNull Resolved resolved) {
        if (resolved.runtime() instanceof VolumeSnapshotSupport support) {
            return support;
        }
        throw Violations.ofForm(violationText("snapshots_unsupported")
            .withArg("kind", String.valueOf((Object) resolved.row().get(InstanceModel.KIND))));
    }

    /** Logical volume name to container path, from the instance settings. */
    static @NonNull Map<String, String> logicalVolumes(@NonNull Resolved resolved) {
        Object settings = resolved.row().get(InstanceModel.SETTINGS);
        Object declared = settings instanceof Map<?, ?> map ? map.get("volumes") : null;
        Map<String, String> volumes = new LinkedHashMap<>();
        EnvVars.toMap(declared).forEach((name, path) -> {
            if (path != null && !path.isBlank()) {
                volumes.put(name, path);
            }
        });
        return volumes;
    }

    /** Refuse an ABSENT or UNREACHABLE workload with a named violation. */
    static void requirePresent(@NonNull InstanceStatus live, @NonNull Resolved resolved) {
        if (live.state() == ContainerState.ABSENT) {
            throw Violations.ofForm(violationText("instance_workload_absent")
                .withArg("name", resolved.row().get(InstanceModel.NAME)));
        }
        if (live.state() == ContainerState.UNREACHABLE) {
            throw Violations.ofForm(violationText("instance_unreachable")
                .withArg("name", resolved.row().get(InstanceModel.NAME)));
        }
    }

    static long maxArchiveBytes() {
        Integer capMb = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Backup.MAX_ARCHIVE_MB);
        return (capMb == null || capMb <= 0 ? 1024L : capMb.longValue()) * 1024 * 1024;
    }

    static @NonNull String describe(@NonNull Exception error) {
        return error.getMessage() != null ? error.getMessage() : error.toString();
    }

    static void deleteRecursively(@NonNull Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static Path snapshotRoot() {
        return Path.of(HohenheimSettings.VALUES.getValue(HohenheimSettings.Backup.SNAPSHOT_PATH));
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Map<String, Object>> inventoryOf(@NonNull Row snapshot) {
        Object raw = snapshot.get(InstanceSnapshotModel.VOLUMES);
        if (raw instanceof Map<?, ?> map) {
            Map<String, Map<String, Object>> typed = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (value instanceof Map<?, ?> entry) {
                    typed.put(String.valueOf(key), (Map<String, Object>) entry);
                }
            });
            return typed;
        }
        return Map.of();
    }

    private static Violations refusal(String key, Row row, @Nullable Exception cause) {
        Microcopy text = violationText(key)
            .withArg("name", String.valueOf((Object) row.get(InstanceModel.NAME)));
        if (cause != null) {
            text = text.withArg("reason", describe(cause));
        }
        return Violations.ofForm(text);
    }

    private static Microcopy violationText(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
