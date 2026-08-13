package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.server.BootSettle;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.backup.BackupArchive;
import be.elevenways.hohenheim.server.instance.InstanceService.Resolved;
import be.elevenways.hohenheim.server.orm.RecordStamp;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.NativeSnapshotSupport;
import be.elevenways.hohenheim.server.runtime.VolumeSnapshotSupport;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
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
import java.util.List;
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
        if (resolved.runtime() instanceof NativeSnapshotSupport nativeSupport) {
            return createNative(instanceId, resolved, nativeSupport, note);
        }
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

        Row snapshot = Models.get(InstanceSnapshotModel.class).createEmptyRow();
        snapshot.set(InstanceSnapshotModel.INSTANCE_ID, instanceId);
        snapshot.set(InstanceSnapshotModel.STATUS, InstanceSnapshotModel.STATUS_FAILED);
        snapshot.set(InstanceSnapshotModel.NOTE, note);
        Models.get(InstanceSnapshotModel.class).save(snapshot);
        // AIDEV-NOTE: the row id is part of the directory name for the reason createNative
        // spells out for the native name -- the stamp resolves to the SECOND, so two
        // captures of one instance inside the same second used to resolve to the SAME
        // directory, and then retention deleting one row's payload took the other row's
        // tars with it. Same hazard, same fix, both lanes.
        String stamp = STAMP.format(Instant.now());
        Path directory = snapshotRoot().resolve("instance-" + instanceId)
            .resolve(stamp + "-" + snapshot.get(InstanceSnapshotModel.ID));
        RecordStamp.on(Models.get(InstanceSnapshotModel.class), snapshot)
            .set(InstanceSnapshotModel.DIRECTORY, directory.toString())
            .write();

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
            // AIDEV-NOTE: a NARROW write, never Models.save of the row held here. The row
            // was created before the capture, which takes minutes, and the note is
            // operator-editable through InstanceSnapshotResource for that whole window --
            // a whole-row save rewinds the edit while reporting the capture complete.
            RecordStamp.on(Models.get(InstanceSnapshotModel.class), snapshot)
                .set(InstanceSnapshotModel.STATUS, InstanceSnapshotModel.STATUS_COMPLETE)
                .set(InstanceSnapshotModel.VOLUMES, inventory)
                .set(InstanceSnapshotModel.TOTAL_BYTES, total)
                .write();
        } catch (IOException error) {
            deleteRecursively(directory);
            RecordStamp.on(Models.get(InstanceSnapshotModel.class), snapshot)
                .set(InstanceSnapshotModel.ERROR, describe(error))
                .write();
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
        pruneForRetention(instanceId);
        return snapshot.get(InstanceSnapshotModel.ID);
    }

    /**
     * The NATIVE lane's capture: LIVE and crash-consistent (the storage driver's
     * atomic snapshot stands in for the stop -- {@link NativeSnapshotSupport}'s
     * declared consistency model), so the workload keeps running; the CAPTURING stamp
     * still gates rival power actions for the operation's duration.
     */
    private int createNative(int instanceId, @NonNull Resolved resolved,
                             @NonNull NativeSnapshotSupport support, @Nullable String note) {
        InstanceStatus live = resolved.runtime().status(resolved.spec().handle());
        requirePresent(live, resolved);
        String prior = live.running() ? InstanceModel.STATUS_RUNNING
            : InstanceModel.STATUS_STOPPED;
        long fence = this.instances.leases().requireFence(resolved.serverId());
        InstanceOperationGuard.stamp(this.instances.leases(), instanceId, resolved.serverId(),
            fence, InstanceModel.STATUS_CAPTURING, resolved.row().get(InstanceModel.NAME));

        Row snapshot = Models.get(InstanceSnapshotModel.class).createEmptyRow();
        snapshot.set(InstanceSnapshotModel.INSTANCE_ID, instanceId);
        snapshot.set(InstanceSnapshotModel.STATUS, InstanceSnapshotModel.STATUS_FAILED);
        snapshot.set(InstanceSnapshotModel.NOTE, note);
        Models.get(InstanceSnapshotModel.class).save(snapshot);
        // AIDEV-NOTE: the row id is part of the daemon-side name, and it has to be: the
        // stamp resolves to the SECOND, so two captures of one instance inside the same
        // second used to ask the daemon for the SAME snapshot name -- the second either
        // fails or aliases the first, and then retention deleting one row's payload takes
        // the other row's snapshot with it. That is why the row is saved first.
        String nativeName = "hib-" + STAMP.format(Instant.now())
            + "-" + snapshot.get(InstanceSnapshotModel.ID);
        RecordStamp.on(Models.get(InstanceSnapshotModel.class), snapshot)
            .set(InstanceSnapshotModel.NATIVE_NAME, nativeName)
            .write();
        try {
            support.createSnapshot(resolved.spec(), nativeName);
            RecordStamp.on(Models.get(InstanceSnapshotModel.class), snapshot)
                .set(InstanceSnapshotModel.STATUS, InstanceSnapshotModel.STATUS_COMPLETE)
                .write();
        } catch (IOException error) {
            RecordStamp.on(Models.get(InstanceSnapshotModel.class), snapshot)
                .set(InstanceSnapshotModel.ERROR, describe(error))
                .write();
            InstanceOperationGuard.stamp(this.instances.leases(), instanceId,
                resolved.serverId(), fence, prior, resolved.row().get(InstanceModel.NAME));
            throw refusal("instance_snapshot_failed", resolved.row(), error);
        }
        InstanceOperationGuard.stamp(this.instances.leases(), instanceId, resolved.serverId(),
            fence, prior, resolved.row().get(InstanceModel.NAME));
        Blast.log("SNAPSHOT: captured native snapshot", nativeName, "of instance", instanceId);
        pruneForRetention(instanceId);
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
        String nativeName = snapshot.get(InstanceSnapshotModel.NATIVE_NAME);
        if (nativeName != null && !nativeName.isBlank()) {
            restoreNative(instanceId, resolved, snapshot, nativeName);
            return;
        }
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
        RestoreCapacity.require(resolved.serverId(), total);

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
        recordRestore(instanceId, "snapshot #" + snapshotId);
        Blast.log("SNAPSHOT: restored snapshot", snapshotId, "onto instance", instanceId);
    }

    /** The activity action an in-place snapshot restore is recorded under. */
    public static final String ACTIVITY_RESTORE_ACTION = "restored_snapshot";

    /**
     * Record the restore on the INSTANCE record.
     *
     * AIDEV-NOTE: explicit, and in the authority rather than the CMS row action, because
     * an in-place restore is a REPLACE that destroys everything written since the
     * snapshot and writes its whole state through InstanceOperationGuard.stamp -- an
     * updateAll, which fires no write hooks. The ActivityLog.withAction wrapper that used
     * to be the only "accountability" here recorded literally nothing (withAction renames
     * hook-written rows; there were none), the same silent-success shape as the
     * OrphanActions claim.
     */
    private static void recordRestore(int instanceId, String detail) {
        ActivityLog.record(Models.get(InstanceModel.class), instanceId,
            ACTIVITY_RESTORE_ACTION, detail);
    }

    /**
     * The NATIVE lane's in-place restore: verify the daemon still HOLDS the snapshot
     * before any live state changes, settle the workload, roll back, redeploy.
     *
     * AIDEV-NOTE: no capacity check here on purpose -- a pool-resident rollback moves
     * no bytes onto the host (the snapshot already lives in the instance's own pool),
     * and the daemon's own operation is the authority that fails loudly if the pool
     * cannot complete it.
     */
    private void restoreNative(int instanceId, @NonNull Resolved resolved,
                               @NonNull Row snapshot, @NonNull String nativeName) {
        if (!(resolved.runtime() instanceof NativeSnapshotSupport support)) {
            throw Violations.ofForm(violationText("snapshots_unsupported")
                .withArg("kind", String.valueOf((Object) resolved.row().get(InstanceModel.KIND))));
        }
        // -- verification, BEFORE any live state changes -----------------------
        InstanceStatus live = resolved.runtime().status(resolved.spec().handle());
        requirePresent(live, resolved);
        try {
            if (!support.snapshotExists(resolved.spec(), nativeName)) {
                throw Violations.ofForm(violationText("snapshot_missing")
                    .withArg("snapshot", nativeName)
                    .withArg("name", resolved.row().get(InstanceModel.NAME)));
            }
        } catch (IOException unanswerable) {
            throw refusal("instance_restore_failed", resolved.row(), unanswerable);
        }
        boolean wasRunning = live.running();

        // -- the point of no return --------------------------------------------
        if (wasRunning) {
            TenantWrites.inAuthorizedOperation(() -> this.instances.stop(instanceId));
        }
        long fence = this.instances.leases().requireFence(resolved.serverId());
        InstanceOperationGuard.stamp(this.instances.leases(), instanceId, resolved.serverId(),
            fence, InstanceModel.STATUS_RESTORING, resolved.row().get(InstanceModel.NAME));
        try {
            support.restoreSnapshot(resolved.spec(), nativeName);
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
        recordRestore(instanceId, "native snapshot " + nativeName);
        Blast.log("SNAPSHOT: restored native snapshot", nativeName, "onto instance", instanceId);
    }

    /**
     * Count-based retention: keep the newest N COMPLETE snapshots of this instance and
     * remove the rest, payload included. Runs when a capture completes -- the same place
     * and the same rule as {@code InstanceBackups.pruneForRetention}, deliberately, so
     * there is ONE retention discipline rather than a second sweeper.
     *
     * AIDEV-NOTE: a prune that cannot reach the payload must not delete the row -- true of
     * BOTH lanes now (the daemon-side snapshot AND the controller-side tars), and the
     * refusal is caught here so a single stuck payload cannot fail the CAPTURE that just
     * succeeded. The row stays and the next completed capture tries again. The sweep runs
     * the UNCHECKED delete on purpose: re-asking for the capability per row made a refusal
     * indistinguishable from a stuck payload in this very catch. FAILED rows are never
     * counted and never pruned: they hold no payload and they are the evidence.
     *
     * AIDEV-NOTE: ordered by ID, not by created_at (the backup lane orders the same way
     * now). A native snapshot's name is stamped to the SECOND, so two captures inside one
     * second carry the same created_at and a timestamp sort would pick between them
     * arbitrarily -- which snapshot survives must not be arbitrary.
     */
    public void pruneForRetention(int instanceId) {
        Integer retention = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Backup.SNAPSHOT_RETENTION);
        if (retention == null || retention <= 0) {
            return;
        }
        List<Row> complete = Models.get(InstanceSnapshotModel.class).find()
            .where(InstanceSnapshotModel.INSTANCE_ID.eq(instanceId))
            .where(InstanceSnapshotModel.STATUS.eq(InstanceSnapshotModel.STATUS_COMPLETE))
            .orderBy(InstanceSnapshotModel.ID, SortOrder.DESC)
            .all();
        for (int i = retention; i < complete.size(); i++) {
            Object id = complete.get(i).get(InstanceSnapshotModel.ID);
            try {
                deleteAuthorized((Integer) id);
            } catch (Violations pruneFailed) {
                Blast.log("SNAPSHOT: retention could not remove snapshot", id,
                    "- kept for a later sweep");
            } catch (RuntimeException unexpected) {
                // The capture this sweep follows ALREADY SUCCEEDED. Letting anything the
                // retention hits escape would report that capture as failed while its
                // snapshot sits complete on the daemon -- a lie in the opposite direction.
                Blast.log("SNAPSHOT: retention hit an unexpected failure on snapshot", id,
                    "- kept for a later sweep:", describe(unexpected));
            }
        }
    }

    /**
     * Boot recovery: reclaim the payload of FAILED rows a killed controller left behind.
     *
     * A FAILED row normally holds no payload (the capture's catch removed it), so the
     * never-prune-FAILED rule is sound -- EXCEPT for a controller killed DURING a
     * capture, which leaves a FAILED-first row whose directory (volume lane) or
     * daemon-side snapshot (native lane) still exists with nothing left to reclaim it.
     * Same fence as {@code InstanceBackups.recoverInterrupted}: only rows whose last
     * write predates THIS process's start are touched -- captures are synchronous and
     * in-process, so such a row cannot belong to a live capture. The ROW is kept: it is
     * the evidence, and after this sweep it once again holds no payload.
     */
    public void recoverInterrupted() {
        List<Row> failed = Models.get(InstanceSnapshotModel.class).find()
            .where(InstanceSnapshotModel.STATUS.eq(InstanceSnapshotModel.STATUS_FAILED))
            .all();
        for (Row row : failed) {
            Instant written = row.get(InstanceSnapshotModel.UPDATED_AT);
            if (written == null) {
                written = row.get(InstanceSnapshotModel.CREATED_AT);
            }
            if (BootSettle.writtenByThisProcess(written)) {
                continue;   // written by THIS process: a capture in flight, not a corpse
            }
            Object id = row.get(InstanceSnapshotModel.ID);
            String directory = row.get(InstanceSnapshotModel.DIRECTORY);
            if (directory != null && !directory.isBlank()
                    && Files.exists(Path.of(directory))) {
                IOException undeletable = deleteRecursivelyReporting(Path.of(directory));
                if (undeletable != null) {
                    Blast.log("SNAPSHOT: could not reclaim interrupted capture", id,
                        "payload at", directory, "- retried at the next boot:",
                        describe(undeletable));
                } else {
                    Blast.log("SNAPSHOT: reclaimed interrupted capture", id,
                        "payload at", directory);
                }
            }
            String nativeName = row.get(InstanceSnapshotModel.NATIVE_NAME);
            if (nativeName != null && !nativeName.isBlank()) {
                reclaimNative(row, nativeName);
            }
        }
    }

    /** The native half of {@link #recoverInterrupted}: observed-absent is a no-op. */
    private void reclaimNative(@NonNull Row row, @NonNull String nativeName) {
        Object id = row.get(InstanceSnapshotModel.ID);
        int instanceId = row.get(InstanceSnapshotModel.INSTANCE_ID);
        Resolved resolved;
        try {
            resolved = this.instances.resolve(instanceId);
        } catch (Violations instanceGone) {
            return;   // a destroyed instance took its pool snapshots with it
        }
        if (!(resolved.runtime() instanceof NativeSnapshotSupport support)) {
            return;
        }
        try {
            if (support.snapshotExists(resolved.spec(), nativeName)) {
                support.deleteSnapshot(resolved.spec(), nativeName);
                Blast.log("SNAPSHOT: reclaimed interrupted native capture", id,
                    "(" + nativeName + ")");
            }
        } catch (IOException unreachable) {
            Blast.log("SNAPSHOT: could not reclaim interrupted native capture", id,
                "(" + nativeName + ") - retried at the next boot:", describe(unreachable));
        }
    }

    /** Remove a snapshot's payload (controller files or the daemon-side snapshot) and its row. */
    public void delete(int snapshotId) {
        HohenheimAccess.requireOperationCapability(
            instanceOf(snapshotId), HohenheimAccess.SNAPSHOTS);
        deleteAuthorized(snapshotId);
    }

    /**
     * The delete the retention sweep runs: the capability was already proven for the
     * INSTANCE by whoever entered the operation, and re-asking per snapshot only made the
     * sweep's {@code catch (Violations)} unable to tell a refusal from an unreachable
     * payload. The gate stays on every public entry point; it just is not asked twice.
     */
    private void deleteAuthorized(int snapshotId) {
        Row snapshot = Models.get(InstanceSnapshotModel.class).findById(snapshotId);
        if (snapshot == null) {
            return;
        }
        int instanceId = snapshot.get(InstanceSnapshotModel.INSTANCE_ID);
        String directory = snapshot.get(InstanceSnapshotModel.DIRECTORY);
        if (directory != null && !directory.isBlank()) {
            // The VOLUME lane's payload, and it refuses exactly as the native lane does:
            // this used to swallow every IO error and delete the row anyway, which left
            // the tars on disk with nothing left to point at them.
            requireRemoved(Path.of(directory), directory);
        }
        String nativeName = snapshot.get(InstanceSnapshotModel.NATIVE_NAME);
        if (nativeName != null && !nativeName.isBlank()) {
            deleteNativePayload(instanceId, nativeName);
        }
        Models.get(InstanceSnapshotModel.class).find()
            .where(InstanceSnapshotModel.ID.eq(snapshotId))
            .delete();
    }

    /** @throws Violations {@code snapshot_not_restorable} for a snapshot that is gone */
    private static int instanceOf(int snapshotId) {
        Row snapshot = Models.get(InstanceSnapshotModel.class).findById(snapshotId);
        if (snapshot == null) {
            throw Violations.ofForm(violationText("snapshot_not_restorable")
                .withArg("id", snapshotId));
        }
        return snapshot.get(InstanceSnapshotModel.INSTANCE_ID);
    }

    /**
     * Remove a controller-side payload directory or REFUSE, so the row outlives a payload
     * that is still on disk.
     *
     * @throws Violations {@code snapshot_delete_failed} naming the IO error
     */
    private static void requireRemoved(@NonNull Path root, @NonNull String label) {
        IOException failure = deleteRecursivelyReporting(root);
        if (failure != null) {
            throw Violations.ofForm(violationText("snapshot_delete_failed")
                .withArg("snapshot", label)
                .withArg("reason", describe(failure)));
        }
    }

    /**
     * Remove the daemon-side snapshot behind a native row. Observed-absent is success;
     * an unreachable or refusing daemon KEEPS the row -- deleting the record while the
     * pool still holds the payload would be a step that does less than it claims. A
     * destroyed instance took its pool snapshots with it, so an unresolvable record is
     * a verified no-op.
     */
    private void deleteNativePayload(int instanceId, @NonNull String nativeName) {
        Resolved resolved;
        try {
            resolved = this.instances.resolve(instanceId);
        } catch (Violations instanceGone) {
            return;
        }
        if (!(resolved.runtime() instanceof NativeSnapshotSupport support)) {
            return;
        }
        try {
            support.deleteSnapshot(resolved.spec(), nativeName);
        } catch (IOException error) {
            throw Violations.ofForm(violationText("snapshot_delete_failed")
                .withArg("snapshot", nativeName)
                .withArg("reason", describe(error)));
        }
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

    /**
     * Best effort, for the cleanup paths where a failure must not mask the failure being
     * cleaned up after (a failed capture's half-written directory, a staging area).
     * Anything that DELETES A ROW must use {@link #deleteRecursivelyReporting} instead.
     */
    static void deleteRecursively(@NonNull Path root) {
        deleteRecursivelyReporting(root);
    }

    /**
     * The same walk, but it ANSWERS: the first IO error, or null when the tree is gone.
     *
     * AIDEV-NOTE: this exists because the two lanes of one mechanism had opposite failure
     * semantics. deleteNativePayload turned an unreachable daemon into Violations BEFORE
     * the row delete, while the volume lane swallowed every IO error and deleted the row
     * anyway -- so an unremovable tar became an orphaned payload with nothing pointing at
     * it, reported as a successful prune.
     */
    static @Nullable IOException deleteRecursivelyReporting(@NonNull Path root) {
        if (!Files.exists(root)) {
            return null;
        }
        IOException[] first = new IOException[1];
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException failed) {
                    if (first[0] == null) {
                        first[0] = failed;
                    }
                }
            });
        } catch (IOException walkFailed) {
            return walkFailed;
        }
        return first[0];
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
