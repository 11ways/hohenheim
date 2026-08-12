package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.backup.BackupArchive;
import be.elevenways.hohenheim.server.backup.BackupManifest;
import be.elevenways.hohenheim.server.backup.BackupManifest.FileEntry;
import be.elevenways.hohenheim.server.backup.BackupManifest.InstanceProfile;
import be.elevenways.hohenheim.server.backup.BackupManifest.TemplateRef;
import be.elevenways.hohenheim.server.backup.BackupManifest.VariableEntry;
import be.elevenways.hohenheim.server.backup.BackupTarget;
import be.elevenways.hohenheim.server.backup.BackupTargetKinds;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.InstanceService.Resolved;
import be.elevenways.hohenheim.server.orm.RecordStamp;
import be.elevenways.hohenheim.server.runtime.ImageIdentity;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.NativeSnapshotSupport;
import be.elevenways.hohenheim.server.runtime.VolumeSnapshotSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Portable, encrypted instance exports to a configured backup target, and their
 * restore-to-a-NEW-instance path. Capture is cold (the snapshot mechanism's explicit
 * consistency model); the workload is redeployed BEFORE the upload so backup
 * downtime is the copy, not the transfer.
 *
 * The whole honesty budget lives here: a row goes COMPLETE only after the TARGET
 * confirmed the committed artifact's sha256; any failure removes the artifact and
 * leaves a FAILED row, and restore refuses everything but COMPLETE. Restore-to-new
 * runs the FULL create story -- quota reservation via the ordinary save pipeline,
 * host admission, network policy, hardening, fenced deploy -- because a restore that
 * bypasses any of it is the hole the plan names.
 */
public final class InstanceBackups {

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final @NonNull InstanceService instances;

    public InstanceBackups() {
        this(new InstanceService());
    }

    InstanceBackups(@NonNull InstanceService instances) {
        this.instances = instances;
    }

    /**
     * Capture, archive, encrypt, upload and VERIFY one backup of the instance to its
     * configured target (or an explicit override).
     *
     * @return the backup row id (status complete)
     * @throws Violations naming the refusal or failure
     */
    public int backupNow(int instanceId) {
        // The capability gate BEFORE the target resolves: targetFor's violations name
        // the instance's backup-target configuration (and the ssh kind adds the host's
        // admission state), which is operator information. Resolving first handed that
        // to any caller who could merely SEE the instance, against the uniform-refusal
        // doctrine requireOperationCapability documents. The explicit-target overload
        // asks again; the double ask is idempotent and keeps that entry gated too.
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.BACKUPS);
        Resolved resolved = this.instances.resolve(instanceId);
        Integer targetId = resolved.row().get(InstanceModel.BACKUP_TARGET_ID);
        return backupNow(instanceId, targetId, BackupTargetKinds.targetFor(targetId));
    }

    /** Explicit-target variant (tests, future re-target flows). */
    public int backupNow(int instanceId, @Nullable Integer targetId, @NonNull BackupTarget target) {
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.BACKUPS);
        Resolved resolved = this.instances.resolve(instanceId);
        InstanceOperationGuard.requireOperable(resolved.row());
        NativeSnapshotSupport nativeSupport = resolved.runtime()
            instanceof NativeSnapshotSupport n ? n : null;
        VolumeSnapshotSupport support = nativeSupport == null
            ? InstanceSnapshots.requireSupport(resolved) : null;
        Map<String, String> volumes = nativeSupport == null
            ? InstanceSnapshots.logicalVolumes(resolved) : Map.of();
        // Health BEFORE the capture: a dead target must not cost the workload downtime.
        try {
            target.healthCheck();
        } catch (IOException unhealthy) {
            throw Violations.ofForm(violationText("backup_target_unhealthy")
                .withArg("reason", InstanceSnapshots.describe(unhealthy)));
        }
        InstanceStatus live = resolved.runtime().status(resolved.spec().handle());
        InstanceSnapshots.requirePresent(live, resolved);
        boolean wasRunning = live.running();

        String stamp = STAMP.format(Instant.now());
        Path staging = stagingRoot().resolve("backup-" + instanceId + "-" + stamp);
        List<VolumeSnapshotSupport.CapturedVolume> captured;
        ImageIdentity image;
        String payload;

        if (nativeSupport != null) {
            // -- capture phase (native lane: LIVE, crash-consistent) ------------
            // The daemon's own atomic export stands in for the stop; the CAPTURING
            // stamp still gates rival power actions for the operation's duration.
            String prior = wasRunning ? InstanceModel.STATUS_RUNNING
                : InstanceModel.STATUS_STOPPED;
            long fence = this.instances.leases().requireFence(resolved.serverId());
            InstanceOperationGuard.stamp(this.instances.leases(), instanceId,
                resolved.serverId(), fence, InstanceModel.STATUS_CAPTURING,
                resolved.row().get(InstanceModel.NAME));
            try {
                Files.createDirectories(staging);
                image = nativeSupport.imageIdentity(resolved.spec());
                Path export = staging.resolve("instance.tar");
                long size = nativeSupport.exportBackup(resolved.spec(), export,
                    InstanceSnapshots.maxArchiveBytes(), false);
                captured = List.of(new VolumeSnapshotSupport.CapturedVolume(
                    "instance", "/", export, size));
                payload = BackupManifest.PAYLOAD_INSTANCE_EXPORT;
            } catch (IOException error) {
                InstanceSnapshots.deleteRecursively(staging);
                InstanceOperationGuard.stamp(this.instances.leases(), instanceId,
                    resolved.serverId(), fence, prior,
                    resolved.row().get(InstanceModel.NAME));
                failedRow(instanceId, targetId, null, InstanceSnapshots.describe(error));
                throw refusal("instance_backup_failed", resolved.row(), error);
            }
            InstanceOperationGuard.stamp(this.instances.leases(), instanceId,
                resolved.serverId(), fence, prior, resolved.row().get(InstanceModel.NAME));
        } else {
            // -- capture phase (volume lane: COLD) ------------------------------
            payload = BackupManifest.PAYLOAD_VOLUME_TARS;
            if (wasRunning) {
                TenantWrites.inAuthorizedOperation(() -> this.instances.stop(instanceId));
            }
            long fence = this.instances.leases().requireFence(resolved.serverId());
            InstanceOperationGuard.stamp(this.instances.leases(), instanceId,
                resolved.serverId(), fence, InstanceModel.STATUS_CAPTURING,
                resolved.row().get(InstanceModel.NAME));
            try {
                Files.createDirectories(staging);
                image = support.imageIdentity(resolved.spec());
                captured = support.captureVolumes(resolved.spec(), volumes, staging,
                    InstanceSnapshots.maxArchiveBytes());
            } catch (IOException error) {
                InstanceSnapshots.deleteRecursively(staging);
                InstanceOperationGuard.stamp(this.instances.leases(), instanceId,
                    resolved.serverId(), fence, InstanceModel.STATUS_STOPPED,
                    resolved.row().get(InstanceModel.NAME));
                redeployBestEffort(instanceId, wasRunning);
                failedRow(instanceId, targetId, null, InstanceSnapshots.describe(error));
                throw refusal("instance_backup_failed", resolved.row(), error);
            }
            InstanceOperationGuard.stamp(this.instances.leases(), instanceId,
                resolved.serverId(), fence, InstanceModel.STATUS_STOPPED,
                resolved.row().get(InstanceModel.NAME));
            if (wasRunning) {
                TenantWrites.inAuthorizedOperation(() -> this.instances.deploy(instanceId));
            }
        }

        // -- archive + upload phase (workload already back up) ------------------
        // AIDEV-NOTE: the controller token leads the object key because a backup TARGET is
        // shared infrastructure too: two controllers whose instance #1 backed up in the same
        // second would otherwise write the same object. Existing rows keep their stored key
        // (remote_key is data, not a derivation), so nothing already uploaded is orphaned.
        Row backup = Models.get(InstanceBackupModel.class).createEmptyRow();
        backup.set(InstanceBackupModel.INSTANCE_ID, instanceId);
        backup.set(InstanceBackupModel.TARGET_ID, targetId);
        backup.set(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_UPLOADING);
        Models.get(InstanceBackupModel.class).save(backup);
        // AIDEV-NOTE: the row id is part of the object key, and the row is saved FIRST so
        // it can be -- the snapshot lanes' fix applied to the third lane. The stamp
        // resolves to the SECOND, so two backups of one instance inside the same second
        // used to write the SAME object (target.store overwrites): two COMPLETE rows over
        // one artifact, and retention deleting the older row removed the payload the
        // SURVIVING row still pointed at. Same hazard, same fix, all three lanes.
        String key = ControllerIdentity.token() + "/instance-" + instanceId + "/" + stamp
            + "-" + backup.get(InstanceBackupModel.ID) + ".hib";
        RecordStamp.on(Models.get(InstanceBackupModel.class), backup)
            .set(InstanceBackupModel.REMOTE_KEY, key)
            .write();
        try {
            BackupManifest manifest = buildManifest(resolved, image, payload, captured);
            Map<String, Path> files = new LinkedHashMap<>();
            for (VolumeSnapshotSupport.CapturedVolume volume : captured) {
                files.put(volume.file().getFileName().toString(), volume.file());
            }
            Path archive = staging.resolve("archive.hib");
            long size = BackupArchive.create(manifest, files, archive, keyring());
            String localSha = BackupArchive.sha256Of(archive);
            target.store(key, archive);
            // Verification observes TARGET state: the committed artifact, re-hashed
            // by/at the target -- never the bytes still in local staging.
            String storedSha = target.storedSha256(key);
            if (!storedSha.equals(localSha)) {
                throw new IOException("Stored artifact does not match what was uploaded"
                    + " (local sha256 " + localSha + ", target holds " + storedSha + ")");
            }
            // AIDEV-NOTE: a NARROW write, never Models.save of the row held here. The row
            // was created before the archive/encrypt/upload/verify phase, which is a
            // network transfer measured in minutes; a whole-row save rewrites every
            // column back to its creation-time value and reports the backup complete.
            RecordStamp.on(Models.get(InstanceBackupModel.class), backup)
                .set(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_COMPLETE)
                .set(InstanceBackupModel.SHA256, storedSha)
                .set(InstanceBackupModel.SIZE_BYTES, size)
                .set(InstanceBackupModel.SUMMARY, manifest.toSummary())
                .write();
        } catch (IOException | RuntimeException error) {
            // Cleanup must leave NOTHING a later restore would accept: remove the
            // artifact (and its staging debris) and leave the row FAILED.
            //
            // AIDEV-NOTE: RuntimeException is in the net because the target's own
            // refusals are Violations (the destination host quarantined mid-upload, say).
            // Catching only IOException left the row stuck in UPLOADING forever -- a
            // status that claims an upload is still running when nothing is. Nothing is
            // swallowed: a named refusal is rethrown UNCHANGED so its identity survives,
            // anything else becomes the instance_backup_failed refusal naming it.
            try {
                target.delete(key);
            } catch (IOException | RuntimeException cleanupFailed) {
                Blast.log("BACKUP: could not remove partial artifact", key, ":",
                    InstanceSnapshots.describe(cleanupFailed));
            }
            RecordStamp.on(Models.get(InstanceBackupModel.class), backup)
                .set(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_FAILED)
                .set(InstanceBackupModel.ERROR, InstanceSnapshots.describe(error))
                .write();
            if (error instanceof Violations refused) {
                throw refused;
            }
            throw refusal("instance_backup_failed", resolved.row(), error);
        } finally {
            InstanceSnapshots.deleteRecursively(staging);
        }
        pruneForRetention(instanceId, targetId, target);
        Blast.log("BACKUP: instance", instanceId, "exported to", key);
        return backup.get(InstanceBackupModel.ID);
    }

    /**
     * The outcome of a restore-to-new: the record that was created, and everything the
     * archive named that this controller could NOT bring back.
     *
     * AIDEV-NOTE: the list is the whole point of the return type. A restore that drops a
     * template binding, a variable row or a config file and answers with a bare id is the
     * silent-degradation shape this lane was built to remove -- so the id never travels
     * without the losses beside it, and the CMS toast renders both. The entries are
     * operator DIAGNOSTICS (the register of {@code InstanceBackupModel.ERROR} and the
     * activity details), not localized UI copy: they name records, paths and versions
     * this controller does not have, which no catalog can translate.
     *
     * @param notRestored one sentence per thing the archive declared and restore skipped
     */
    public record Restored(int instanceId, @NonNull List<String> notRestored) {

        /** Whether the restored record is everything the archive described. */
        public boolean complete() {
            return this.notRestored.isEmpty();
        }

        /** The losses as one operator-readable line ("" when there are none). */
        public @NonNull String describeLosses() {
            return String.join("; ", this.notRestored);
        }
    }

    /**
     * Restore a completed backup to a NEW instance: verify everything (target sha,
     * GCM authentication, per-payload checksums, kind, admission, capacity) BEFORE
     * creating anything, then run the full create story -- record save (quota
     * reservation), control-plane re-materialization, volume restore, fenced deploy.
     *
     * @param newName name for the new instance, or null for a derived one
     * @param serverSpelling target host (any canonical spelling), or null for the
     *                       source instance's host
     * @return the NEW instance, deployed and running, with whatever could not be restored
     * @throws Violations naming the refusal or failure
     */
    public @NonNull Restored restoreToNew(int backupId, @Nullable String newName,
                                          @Nullable Object serverSpelling) {
        Row backup = Models.get(InstanceBackupModel.class).findById(backupId);
        if (backup == null || !InstanceBackupModel.STATUS_COMPLETE.equals(
                backup.get(InstanceBackupModel.STATUS))) {
            throw Violations.ofForm(violationText("backup_not_restorable")
                .withArg("id", backupId));
        }
        BackupTarget target = BackupTargetKinds.targetFor(
            backup.get(InstanceBackupModel.TARGET_ID));
        return restoreToNew(backup, target, newName, serverSpelling);
    }

    /** Explicit-target variant (tests, future re-target flows). */
    public @NonNull Restored restoreToNew(@NonNull Row backup, @NonNull BackupTarget target,
                                          @Nullable String newName,
                                          @Nullable Object serverSpelling) {
        // Restore-to-new CREATES an instance, and it does so outside the creation funnel
        // (InstanceTemplates.createFromTemplate): no create authority, no placement
        // decision, no creator grant, and an image taken from the archive's manifest
        // rather than an approved template. Every one of those is a tenant-facing gate,
        // so this path is OPERATOR-ONLY until it routes through the funnel -- refused
        // loudly rather than quietly producing a record its creator cannot even see.
        if (TenantWrites.isTenantOriginated()) {
            throw Violations.ofForm(violationText("backup_restore_operator_only"));
        }
        if (!InstanceBackupModel.STATUS_COMPLETE.equals(
                backup.get(InstanceBackupModel.STATUS))) {
            throw Violations.ofForm(violationText("backup_not_restorable")
                .withArg("id", backup.get(InstanceBackupModel.ID)));
        }
        String key = backup.get(InstanceBackupModel.REMOTE_KEY);
        String stamp = STAMP.format(Instant.now());
        Path staging = stagingRoot().resolve("restore-" + backup.get(InstanceBackupModel.ID)
            + "-" + stamp);
        BackupArchive.Opened opened = null;
        try {
            // -- verification, before ANYTHING exists --------------------------
            Path archive = staging.resolve("archive.hib");
            try {
                Files.createDirectories(staging);
                target.retrieve(key, archive);
                String recordedSha = backup.get(InstanceBackupModel.SHA256);
                String actualSha = BackupArchive.sha256Of(archive);
                if (recordedSha != null && !recordedSha.equals(actualSha)) {
                    throw new IOException("Retrieved artifact does not match the recorded"
                        + " sha256 (recorded " + recordedSha + ", retrieved " + actualSha
                        + "). The backup is corrupt and is refused whole -- nothing was"
                        + " restored from it");
                }
                opened = BackupArchive.openVerified(archive, staging, keyring());
            } catch (IOException corrupt) {
                throw Violations.ofForm(violationText("backup_corrupt")
                    .withArg("reason", InstanceSnapshots.describe(corrupt)));
            }
            BackupManifest manifest = opened.manifest();
            if (InstanceKinds.getHandler(manifest.kind()) == null) {
                throw Violations.ofField("kind", manifest.kind(),
                    violationText("instance_kind_unknown").withArg("kind", manifest.kind()));
            }
            int serverId = serverSpelling != null
                ? ServerModel.canonicalServerId(serverSpelling)
                : sourceServerId(backup);
            HostAdmission.requireInstancePlacement(serverId);
            RestoreCapacity.require(serverId, manifest.totalVolumeBytes());

            // -- create the NEW record (quota reserves in the save pipeline) ----
            // AIDEV-NOTE: the columns BESIDE name/kind/settings/host are set here, before
            // the save, not stamped afterwards: crash policy and template binding are
            // ordinary create-time facts, and a record that exists for even one write
            // without them is a record whose hooks judged the wrong instance. Everything
            // the archive named but this controller cannot honour lands in notRestored
            // instead of being dropped -- see Restored.
            List<String> notRestored = new ArrayList<>();
            InstanceProfile profile = manifest.profile();
            if (profile == null) {
                notRestored.add("this backup carries a version " + manifest.version()
                    + " manifest, which predates the template/variable/config-file"
                    + " inventory: the restored instance has no template binding, no"
                    + " variables and no config files, and its crash policy fell back to"
                    + " " + InstanceModel.CRASH_NONE);
            }
            Row record = Models.get(InstanceModel.class).createEmptyRow();
            record.set(InstanceModel.NAME, newName != null && !newName.isBlank()
                ? newName : manifest.instanceName() + "-restored-" + stamp);
            record.set(InstanceModel.KIND, manifest.kind());
            record.set(InstanceModel.SETTINGS, manifest.settings());
            record.set(InstanceModel.SERVER_ID, serverId);
            record.set(InstanceModel.CRASH_POLICY, profile != null
                ? profile.crashPolicy() : InstanceModel.CRASH_NONE);
            record.set(InstanceModel.TEMPLATE_ID, localTemplateId(profile, notRestored));
            record.set(InstanceModel.BACKUP_TARGET_ID, localTargetId(profile, notRestored));
            noteUnrestorableGrouping(profile, notRestored);
            Models.get(InstanceModel.class).save(record);
            int newId = record.get(InstanceModel.ID);

            // The table-backed rows the settings map cannot carry, BEFORE resolve():
            // variable values substitute into the spec's command and environment, so a
            // spec resolved without them is a spec for a different workload.
            restoreVariables(newId, profile);
            restoreFiles(newId, profile, notRestored);

            Resolved resolved = this.instances.resolve(newId);
            long fence = this.instances.leases().requireFence(resolved.serverId());
            InstanceOperationGuard.stamp(this.instances.leases(), newId, resolved.serverId(),
                fence, InstanceModel.STATUS_RESTORING, record.get(InstanceModel.NAME));
            try {
                // Count what the DATABASE now holds, before a single byte of payload
                // moves: a re-materialization that wrote less than the manifest declares
                // must stop the restore, never deploy a workload whose config files or
                // variables silently did not come back.
                requireRematerialized(profile, newId);
                if (BackupManifest.PAYLOAD_INSTANCE_EXPORT.equals(manifest.payload())) {
                    // Native lane: the daemon rebuilds the whole instance from its own
                    // export; the deploy below CONVERGES onto it (the incus driver
                    // never replaces an owned instance from its image).
                    if (!(resolved.runtime() instanceof NativeSnapshotSupport nativeSupport)) {
                        throw Violations.ofForm(violationText("backup_payload_mismatch")
                            .withArg("payload", manifest.payload())
                            .withArg("kind", manifest.kind()));
                    }
                    Map<String, Path> tars = BackupArchive.extractVolumes(opened,
                        staging.resolve("volumes"));
                    Path export = tars.get("instance");
                    if (export == null) {
                        throw new IOException("Backup archive carries no 'instance'"
                            + " payload entry for its instance_export manifest");
                    }
                    nativeSupport.importBackup(resolved.spec(), export);
                } else {
                    VolumeSnapshotSupport support = InstanceSnapshots.requireSupport(resolved);
                    Map<String, String> volumes = InstanceSnapshots.logicalVolumes(resolved);
                    resolved.runtime().create(resolved.spec());
                    Map<String, Path> tars = BackupArchive.extractVolumes(opened,
                        staging.resolve("volumes"));
                    support.restoreVolumes(resolved.spec(), volumes, tars);
                }
            } catch (IOException error) {
                InstanceOperationGuard.stamp(this.instances.leases(), newId,
                    resolved.serverId(), fence, InstanceModel.STATUS_ERROR,
                    record.get(InstanceModel.NAME));
                throw refusal("instance_restore_failed", record, error);
            }
            InstanceOperationGuard.stamp(this.instances.leases(), newId, resolved.serverId(),
                fence, InstanceModel.STATUS_STOPPED, record.get(InstanceModel.NAME));
            TenantWrites.inAuthorizedOperation(() -> this.instances.deploy(newId));
            Restored restored = new Restored(newId, List.copyOf(notRestored));
            recordRestore(newId, backup.get(InstanceBackupModel.ID), restored);
            Blast.log("BACKUP: restored backup", backup.get(InstanceBackupModel.ID),
                "to NEW instance", newId, restored.complete()
                    ? "(complete)" : "- NOT restored: " + restored.describeLosses());
            return restored;
        } finally {
            if (opened != null) {
                try {
                    Files.deleteIfExists(opened.zip());
                } catch (IOException ignored) {
                    // staging cleanup below covers it
                }
            }
            InstanceSnapshots.deleteRecursively(staging);
        }
    }

    /** Remove a backup's artifact from its target and delete the row. */
    public void delete(int backupId) {
        Row backup = Models.get(InstanceBackupModel.class).findById(backupId);
        if (backup == null) {
            return;
        }
        HohenheimAccess.requireOperationCapability(
            backup.get(InstanceBackupModel.INSTANCE_ID), HohenheimAccess.BACKUPS);
        deleteAuthorized(backup, null, null);
    }

    /**
     * The delete the retention sweep runs: the BACKUPS capability was proven for the
     * instance when the operation was entered, and re-asking per row only made the
     * sweep's {@code catch (Violations)} unable to tell a refusal from an unreachable
     * target. Same reasoning as {@code InstanceSnapshots.deleteAuthorized}.
     *
     * AIDEV-NOTE: {@code currentTarget} exists for the explicit-target flow: a row whose
     * TARGET_ID cannot be re-resolved (null, from the tests/re-target overload) made
     * {@code targetFor} throw here, so retention kept the row FOREVER while its artifact
     * stayed on the target. When the sweep runs inside a backup that holds the very
     * target such a row was written to, that in-hand handle is used instead.
     *
     * @param currentTargetId the target id the calling operation used, or null
     * @param currentTarget   the resolved target of the calling operation, or null
     */
    private void deleteAuthorized(@NonNull Row backup, @Nullable Integer currentTargetId,
                                  @Nullable BackupTarget currentTarget) {
        int backupId = backup.get(InstanceBackupModel.ID);
        String key = backup.get(InstanceBackupModel.REMOTE_KEY);
        if (key != null && !key.isBlank()) {
            try {
                Integer rowTargetId = backup.get(InstanceBackupModel.TARGET_ID);
                BackupTarget target = currentTarget != null
                        && Objects.equals(rowTargetId, currentTargetId)
                    ? currentTarget
                    : BackupTargetKinds.targetFor(rowTargetId);
                target.delete(key);
            } catch (IOException | Violations unreachable) {
                throw Violations.ofForm(violationText("backup_delete_failed")
                    .withArg("reason", unreachable instanceof IOException io
                        ? InstanceSnapshots.describe(io) : "target unavailable"));
            }
        }
        Models.get(InstanceBackupModel.class).find()
            .where(InstanceBackupModel.ID.eq(backupId))
            .delete();
    }

    /**
     * Count-based retention, the managed-database pattern applied per instance: keep
     * the newest N COMPLETE backups, remove older artifacts and rows. Failed rows are
     * kept as evidence (they hold no artifact).
     */
    public void pruneForRetention(int instanceId) {
        pruneForRetention(instanceId, null, null);
    }

    /**
     * The sweep as {@code backupNow} runs it, carrying the target the completed backup
     * used so same-target rows are prunable even when their id cannot be re-resolved.
     *
     * AIDEV-NOTE: ordered by ID, not created_at -- two backups inside one second carry
     * the same created_at and a timestamp sort would pick the survivor arbitrarily
     * (the snapshot lanes' rule, {@code InstanceSnapshots.pruneForRetention}).
     */
    private void pruneForRetention(int instanceId, @Nullable Integer currentTargetId,
                                   @Nullable BackupTarget currentTarget) {
        Integer retention = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Backup.RETENTION);
        if (retention == null || retention <= 0) {
            return;
        }
        List<Row> complete = Models.get(InstanceBackupModel.class).find()
            .where(InstanceBackupModel.INSTANCE_ID.eq(instanceId))
            .where(InstanceBackupModel.STATUS.eq(InstanceBackupModel.STATUS_COMPLETE))
            .orderBy(InstanceBackupModel.ID, SortOrder.DESC)
            .all();
        for (int i = retention; i < complete.size(); i++) {
            Object id = complete.get(i).get(InstanceBackupModel.ID);
            try {
                deleteAuthorized(complete.get(i), currentTargetId, currentTarget);
            } catch (Violations pruneFailed) {
                Blast.log("BACKUP: retention could not remove backup", id,
                    "- kept for a later sweep");
            } catch (RuntimeException unexpected) {
                // The backup this sweep follows already succeeded; see the same guard in
                // InstanceSnapshots.pruneForRetention.
                Blast.log("BACKUP: retention hit an unexpected failure on backup", id,
                    "- kept for a later sweep:", InstanceSnapshots.describe(unexpected));
            }
        }
    }

    /**
     * Boot recovery: settle every backup row a killed controller left {@code uploading}.
     *
     * A row is settled only when its last write predates THIS process's start: an upload
     * is a synchronous in-process operation, so no upload recorded before this process
     * existed can still be running -- an exact fence, not an age guess. Rows written by
     * this process (the listener binds before this sweep runs) are live operations and
     * are left alone; if this process is killed too, the next boot settles them. The
     * possibly-committed artifact is removed (absent is success, the target's delete
     * contract); a target that refuses keeps the key in the FAILED row's error text so
     * the evidence still names what may survive remotely.
     */
    public static void recoverInterrupted() {
        Instant processStart = Instant.ofEpochMilli(
            ManagementFactory.getRuntimeMXBean().getStartTime());
        List<Row> stuck = Models.get(InstanceBackupModel.class).find()
            .where(InstanceBackupModel.STATUS.eq(InstanceBackupModel.STATUS_UPLOADING))
            .all();
        for (Row row : stuck) {
            Instant written = row.get(InstanceBackupModel.UPDATED_AT);
            if (written == null) {
                written = row.get(InstanceBackupModel.CREATED_AT);
            }
            if (written != null && !written.isBefore(processStart)) {
                continue;   // written by THIS process: a live upload, not a corpse
            }
            Object id = row.get(InstanceBackupModel.ID);
            String key = row.get(InstanceBackupModel.REMOTE_KEY);
            String error = "Interrupted by a controller restart mid-upload";
            if (key != null && !key.isBlank()) {
                try {
                    BackupTargetKinds.targetFor(row.get(InstanceBackupModel.TARGET_ID))
                        .delete(key);
                } catch (IOException | Violations unreachable) {
                    error += "; the possibly-committed artifact could not be removed"
                        + " and may remain on the target under " + key;
                    Blast.log("BACKUP: could not remove interrupted upload", key, ":",
                        InstanceSnapshots.describe(unreachable));
                }
            }
            RecordStamp.on(Models.get(InstanceBackupModel.class), row)
                .set(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_FAILED)
                .set(InstanceBackupModel.ERROR, error)
                .write();
            Blast.log("BACKUP: settled interrupted upload row", id, "as failed");
        }
    }

    // -- internals ------------------------------------------------------------

    private @NonNull BackupManifest buildManifest(
            @NonNull Resolved resolved,
            @NonNull ImageIdentity image,
            @NonNull String payload,
            @NonNull List<VolumeSnapshotSupport.CapturedVolume> captured) throws IOException {
        int instanceId = resolved.row().get(InstanceModel.ID);
        Set<String> subjects = HohenheimAccess.manageSubjectsOf(InstanceModel.MODEL_ID, instanceId);
        String ownership = subjects != null ? HohenheimAccess.packSubjects(subjects) : "";
        Map<String, Object> settings = resolved.row().get(InstanceModel.SETTINGS)
                instanceof Map<?, ?> map ? castSettings(map) : Map.of();
        Integer containerPort = settings.get("container_port") instanceof Number port
            ? port.intValue() : null;
        List<BackupManifest.VolumeEntry> volumes = new ArrayList<>();
        for (VolumeSnapshotSupport.CapturedVolume volume : captured) {
            volumes.add(new BackupManifest.VolumeEntry(volume.name(), volume.containerPath(),
                volume.file().getFileName().toString(),
                BackupArchive.sha256Of(volume.file()), volume.size()));
        }
        return new BackupManifest(BackupManifest.FORMAT_VERSION, Instant.now().toString(),
            HostPreflight.controllerVersion(),
            String.valueOf((Object) resolved.row().get(InstanceModel.NAME)),
            String.valueOf((Object) resolved.row().get(InstanceModel.KIND)),
            payload, settings, image.reference(), image.id(), ownership, containerPort,
            "tcp", volumes, profileOf(resolved.row(), instanceId));
    }

    /**
     * Capture the control-plane facts beside the settings map: the template binding, the
     * crash policy, the groupings, and the table-backed variable and config-file rows.
     *
     * The settings map stays RAW on purpose (see {@link BackupManifest}); this is where
     * the values it does not contain come from. Secret values ride here in plaintext,
     * inside the whole-archive encryption -- the same protection the settings map has
     * always had, and deliberately not a second encryption path.
     *
     * AIDEV-NOTE: only the instance's OWN variable rows are captured, never the
     * environment's. Those are the deploy-time BASELINE of a grouping the restore cannot
     * re-establish (restore-to-new charges the RESTORING actor, so the ProjectGuards
     * owner-match would refuse the grouping); folding them in would convert inherited
     * values into owned ones and quietly change what the record IS.
     *
     * AIDEV-NOTE: a TEMPLATE_ID whose template row is gone captures no binding at all.
     * Recording a dangling id would let the restore re-plant the same dangling pointer,
     * and there is no name to re-bind by. The resource refuses deleting a template still
     * in use, so this is a repair case, not a normal one.
     */
    private static @NonNull InstanceProfile profileOf(@NonNull Row row, int instanceId) {
        TemplateRef template = null;
        Integer templateId = row.get(InstanceModel.TEMPLATE_ID);
        if (templateId != null) {
            Row stored = Models.get(InstanceTemplateModel.class).findById(templateId);
            if (stored != null) {
                Integer templateVersion = stored.get(InstanceTemplateModel.VERSION);
                template = new TemplateRef(templateId,
                    String.valueOf((Object) stored.get(InstanceTemplateModel.NAME)),
                    templateVersion != null ? templateVersion : 1);
            }
        }
        String crashPolicy = row.get(InstanceModel.CRASH_POLICY);

        List<VariableEntry> variables = new ArrayList<>();
        for (Row variable : Models.get(InstanceVariableModel.class).findByInstanceId(instanceId)) {
            String key = variable.get(InstanceVariableModel.KEY);
            if (key == null || key.isBlank()) {
                continue;
            }
            boolean secret = InstanceVariableModel.KIND_SECRET
                .equals(variable.get(InstanceVariableModel.KIND));
            String value = secret
                ? variable.get(InstanceVariableModel.SECRET_VALUE)
                : variable.get(InstanceVariableModel.PLAIN_VALUE);
            variables.add(new VariableEntry(key, secret
                ? InstanceVariableModel.KIND_SECRET : InstanceVariableModel.KIND_PLAIN,
                value == null ? "" : value));
        }

        List<FileEntry> files = new ArrayList<>();
        for (Row file : Models.get(InstanceFileModel.class).findByInstanceId(instanceId)) {
            String path = file.get(InstanceFileModel.CONTAINER_PATH);
            if (path == null || path.isBlank()) {
                continue;
            }
            String content = file.get(InstanceFileModel.CONTENT);
            String mode = file.get(InstanceFileModel.MODE);
            files.add(new FileEntry(path, content == null ? "" : content,
                mode == null || mode.isBlank() ? "0644" : mode,
                file.get(InstanceFileModel.GENERATED_BY)));
        }

        return new InstanceProfile(template,
            crashPolicy != null ? crashPolicy : InstanceModel.CRASH_NONE,
            environmentName(row.get(InstanceModel.ENVIRONMENT_ID)),
            targetName(row.get(InstanceModel.BACKUP_TARGET_ID)),
            List.copyOf(variables), List.copyOf(files));
    }

    /** The grouping environment's name, or null when unset or dangling. */
    private static @Nullable String environmentName(@Nullable Integer environmentId) {
        if (environmentId == null) {
            return null;
        }
        Row row = Models.get(EnvironmentModel.class).findById(environmentId);
        return row != null ? row.get(EnvironmentModel.NAME) : null;
    }

    /** The backup destination's name, or null when unset or dangling. */
    private static @Nullable String targetName(@Nullable Integer targetId) {
        if (targetId == null) {
            return null;
        }
        Row row = Models.get(BackupTargetModel.class).findById(targetId);
        return row != null ? row.get(BackupTargetModel.NAME) : null;
    }

    // -- restore-side re-materialization ---------------------------------------

    /**
     * The template this controller may bind the restored record to: the manifest's id,
     * but only when the local template with that id carries the SAME name.
     *
     * An archive is portable by design, so on another controller that id names a
     * different catalog entry -- binding by id alone would attach the instance to an
     * unrelated template and hand {@code InstanceImagePolicy} a bogus authority to judge
     * against. A mismatch is reported, never guessed at.
     */
    private static @Nullable Integer localTemplateId(
            @Nullable InstanceProfile profile, @NonNull List<String> notRestored) {
        TemplateRef template = profile != null ? profile.template() : null;
        if (template == null) {
            return null;
        }
        Row local = Models.get(InstanceTemplateModel.class).findById(template.id());
        if (local != null && template.name().equals(local.get(InstanceTemplateModel.NAME))) {
            return template.id();
        }
        notRestored.add("template binding to '" + template.name() + "' (#" + template.id()
            + ", version " + template.version() + "): "
            + (local == null ? "no template with that id exists on this controller"
                : "template #" + template.id() + " here is '"
                    + local.get(InstanceTemplateModel.NAME) + "'"));
        return null;
    }

    /** The source's backup destination, re-bound BY NAME (ids do not travel between controllers). */
    private static @Nullable Integer localTargetId(
            @Nullable InstanceProfile profile, @NonNull List<String> notRestored) {
        String name = profile != null ? profile.backupTargetName() : null;
        if (name == null) {
            return null;
        }
        Row local = Models.get(BackupTargetModel.class).find()
            .where(BackupTargetModel.NAME.eq(name)).first();
        if (local != null) {
            return local.get(BackupTargetModel.ID);
        }
        notRestored.add("backup destination '" + name + "': no target with that name exists"
            + " on this controller, so the restored instance backs up nowhere until one"
            + " is chosen");
        return null;
    }

    /**
     * Report the project grouping as unrestorable.
     *
     * AIDEV-NOTE: this is a STRUCTURAL limit, not an omission. The environment guard
     * (ProjectGuards) refuses a grouping whose project does not own the record, and
     * restore-to-new charges the RESTORING operator rather than the source's owner (the
     * operator-only refusal above says so). Re-binding the environment would therefore
     * either be refused by the guard or require the restore to adopt the source's
     * ownership -- a different decision than this method is allowed to make. So it is
     * NAMED instead of silently dropped, and an operator re-groups deliberately.
     */
    private static void noteUnrestorableGrouping(
            @Nullable InstanceProfile profile, @NonNull List<String> notRestored) {
        String environment = profile != null ? profile.environmentName() : null;
        if (environment != null) {
            notRestored.add("environment grouping '" + environment + "': a restore is"
                + " charged to the restoring operator, so the project that owns that"
                + " environment does not own this record; re-group it by hand");
        }
    }

    /**
     * Re-create the instance's own variable rows through the ordinary write funnel, so
     * secrets land in the encrypted carrier and a live console starts redacting them.
     */
    private static void restoreVariables(int instanceId,
                                         @Nullable InstanceProfile profile) {
        if (profile == null) {
            return;
        }
        InstanceVariables variables = new InstanceVariables();
        for (VariableEntry variable : profile.variables()) {
            variables.setValue(instanceId, null, variable.key(), variable.kind(),
                variable.value());
        }
    }

    /**
     * Re-create the instance's config-file rows (the {@code copyFiles} shape a template
     * create uses). GENERATED rows are deliberately NOT re-created: they belong to a
     * DECLARING record this restore is not a copy of, their attribution may only be
     * written inside the GeneratedRows system scope, and a hand-authored row on a
     * generated path is exactly what the owning tier refuses next time it materializes.
     */
    private static void restoreFiles(int instanceId,
                                     @Nullable InstanceProfile profile,
                                     @NonNull List<String> notRestored) {
        if (profile == null) {
            return;
        }
        InstanceFileModel files = Models.get(InstanceFileModel.class);
        for (FileEntry file : profile.files()) {
            if (file.generatedBy() != null) {
                notRestored.add("config file " + file.containerPath() + ": generated by "
                    + file.generatedBy() + ", so it belongs to that record and is"
                    + " re-materialized by it, never copied");
                continue;
            }
            Row row = files.createEmptyRow();
            row.set(InstanceFileModel.INSTANCE_ID, instanceId);
            row.set(InstanceFileModel.CONTAINER_PATH, file.containerPath());
            row.set(InstanceFileModel.CONTENT, file.content());
            row.set(InstanceFileModel.MODE, file.mode());
            files.save(row);
        }
    }

    /**
     * Count what the DATABASE holds against what the manifest declared, before any
     * payload moves.
     *
     * AIDEV-NOTE: this counts stored rows rather than trusting the loop above, because
     * the defect it guards is precisely "a step did less than it claimed and reported
     * success" -- {@code InstanceService.stageConfigFiles} returns early on an empty
     * file list, so a restore that materialized nothing would deploy a workload with no
     * configuration and no refusal anywhere. A version-1 manifest declares nothing and
     * is therefore never accused of losing anything.
     *
     * @throws IOException naming the shortfall; the caller stamps the record ERROR
     */
    private static void requireRematerialized(@Nullable InstanceProfile profile,
                                              int instanceId) throws IOException {
        if (profile == null) {
            return;
        }
        int declaredFiles = 0;
        for (FileEntry file : profile.files()) {
            if (file.generatedBy() == null) {
                declaredFiles++;
            }
        }
        int storedFiles = Models.get(InstanceFileModel.class).findByInstanceId(instanceId).size();
        if (storedFiles != declaredFiles) {
            throw new IOException("The backup declares " + declaredFiles + " restorable"
                + " config file(s) but the restored instance holds " + storedFiles
                + "; refusing to deploy a workload without the configuration its backup"
                + " carried");
        }
        int declaredVariables = profile.variables().size();
        int storedVariables = Models.get(InstanceVariableModel.class)
            .findByInstanceId(instanceId).size();
        if (storedVariables != declaredVariables) {
            throw new IOException("The backup declares " + declaredVariables + " variable(s)"
                + " but the restored instance holds " + storedVariables
                + "; refusing to deploy a workload without the values its backup carried");
        }
    }

    /** The activity action a restore-to-new is recorded under on the NEW record. */
    public static final String ACTIVITY_RESTORE_ACTION = "restored_backup";

    /**
     * Record the restore on the NEW instance, naming its losses.
     *
     * AIDEV-NOTE: explicit, in the authority rather than the CMS row action, for the
     * reason {@code InstanceSnapshots.recordRestore} documents -- and because the losses
     * must outlive the toast that showed them once. The activity row is where an
     * operator finds out, a week later, why the restored instance has no template.
     */
    private static void recordRestore(int instanceId, @Nullable Object backupId,
                                      @NonNull Restored restored) {
        ActivityLog.record(Models.get(InstanceModel.class), instanceId,
            ACTIVITY_RESTORE_ACTION, restored.complete()
                ? "backup #" + backupId
                : "backup #" + backupId + " -- NOT restored: " + restored.describeLosses());
    }

    private void redeployBestEffort(int instanceId, boolean wasRunning) {
        if (!wasRunning) {
            return;
        }
        try {
            TenantWrites.inAuthorizedOperation(() -> this.instances.deploy(instanceId));
        } catch (RuntimeException redeployFailed) {
            Blast.log("BACKUP: could not restart instance", instanceId,
                "after a failed capture:", InstanceSnapshots.describe(redeployFailed));
        }
    }

    private static void failedRow(int instanceId, @Nullable Integer targetId,
                                  @Nullable String key, @NonNull String error) {
        Row backup = Models.get(InstanceBackupModel.class).createEmptyRow();
        backup.set(InstanceBackupModel.INSTANCE_ID, instanceId);
        backup.set(InstanceBackupModel.TARGET_ID, targetId);
        backup.set(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_FAILED);
        backup.set(InstanceBackupModel.REMOTE_KEY, key);
        backup.set(InstanceBackupModel.ERROR, error);
        Models.get(InstanceBackupModel.class).save(backup);
    }

    /** The source instance's host, trashed included (the backup outlives the instance). */
    private static int sourceServerId(@NonNull Row backup) {
        Row instance = Models.get(InstanceModel.class)
            .findById(backup.get(InstanceBackupModel.INSTANCE_ID));
        return ServerModel.canonicalServerId(instance != null
            ? instance.get(InstanceModel.SERVER_ID) : null);
    }

    private static @NonNull EncryptionKeyring keyring() {
        return FieldEncryption.requireKeyring();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castSettings(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Violations refusal(String key, Row row, Exception cause) {
        return Violations.ofForm(violationText(key)
            .withArg("name", String.valueOf((Object) row.get(InstanceModel.NAME)))
            .withArg("reason", InstanceSnapshots.describe(cause)));
    }

    private static Microcopy violationText(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }

    private static Path stagingRoot() {
        return Path.of(HohenheimSettings.VALUES.getValue(HohenheimSettings.Backup.STAGING_PATH));
    }
}
