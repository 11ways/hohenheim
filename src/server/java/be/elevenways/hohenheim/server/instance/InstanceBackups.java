package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.backup.BackupArchive;
import be.elevenways.hohenheim.server.backup.BackupManifest;
import be.elevenways.hohenheim.server.backup.BackupTarget;
import be.elevenways.hohenheim.server.backup.BackupTargetKinds;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.InstanceService.Resolved;
import be.elevenways.hohenheim.server.runtime.ImageIdentity;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.NativeSnapshotSupport;
import be.elevenways.hohenheim.server.runtime.VolumeSnapshotSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                    InstanceSnapshots.maxArchiveBytes());
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
        String key = "instance-" + instanceId + "/" + stamp + ".hib";
        Row backup = Models.get(InstanceBackupModel.class).createEmptyRow();
        backup.set(InstanceBackupModel.INSTANCE_ID, instanceId);
        backup.set(InstanceBackupModel.TARGET_ID, targetId);
        backup.set(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_UPLOADING);
        backup.set(InstanceBackupModel.REMOTE_KEY, key);
        Models.get(InstanceBackupModel.class).save(backup);
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
            backup.set(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_COMPLETE);
            backup.set(InstanceBackupModel.SHA256, storedSha);
            backup.set(InstanceBackupModel.SIZE_BYTES, size);
            backup.set(InstanceBackupModel.SUMMARY, manifest.toSummary());
            Models.get(InstanceBackupModel.class).save(backup);
        } catch (IOException error) {
            // Cleanup must leave NOTHING a later restore would accept: remove the
            // artifact (and its staging debris) and leave the row FAILED.
            try {
                target.delete(key);
            } catch (IOException cleanupFailed) {
                Blast.log("BACKUP: could not remove partial artifact", key, ":",
                    InstanceSnapshots.describe(cleanupFailed));
            }
            backup.set(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_FAILED);
            backup.set(InstanceBackupModel.ERROR, InstanceSnapshots.describe(error));
            Models.get(InstanceBackupModel.class).save(backup);
            throw refusal("instance_backup_failed", resolved.row(), error);
        } finally {
            InstanceSnapshots.deleteRecursively(staging);
        }
        pruneForRetention(instanceId);
        Blast.log("BACKUP: instance", instanceId, "exported to", key);
        return backup.get(InstanceBackupModel.ID);
    }

    /**
     * Restore a completed backup to a NEW instance: verify everything (target sha,
     * GCM authentication, per-payload checksums, kind, admission, capacity) BEFORE
     * creating anything, then run the full create story -- record save (quota
     * reservation), volume restore, fenced deploy.
     *
     * @param newName name for the new instance, or null for a derived one
     * @param serverSpelling target host (any canonical spelling), or null for the
     *                       source instance's host
     * @return the NEW instance's id, deployed and running
     * @throws Violations naming the refusal or failure
     */
    public int restoreToNew(int backupId, @Nullable String newName,
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
    public int restoreToNew(@NonNull Row backup, @NonNull BackupTarget target,
                            @Nullable String newName, @Nullable Object serverSpelling) {
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
            Row record = Models.get(InstanceModel.class).createEmptyRow();
            record.set(InstanceModel.NAME, newName != null && !newName.isBlank()
                ? newName : manifest.instanceName() + "-restored-" + stamp);
            record.set(InstanceModel.KIND, manifest.kind());
            record.set(InstanceModel.SETTINGS, manifest.settings());
            record.set(InstanceModel.SERVER_ID, serverId);
            Models.get(InstanceModel.class).save(record);
            int newId = record.get(InstanceModel.ID);

            Resolved resolved = this.instances.resolve(newId);
            long fence = this.instances.leases().requireFence(resolved.serverId());
            InstanceOperationGuard.stamp(this.instances.leases(), newId, resolved.serverId(),
                fence, InstanceModel.STATUS_RESTORING, record.get(InstanceModel.NAME));
            try {
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
            Blast.log("BACKUP: restored backup", backup.get(InstanceBackupModel.ID),
                "to NEW instance", newId);
            return newId;
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
        String key = backup.get(InstanceBackupModel.REMOTE_KEY);
        if (key != null && !key.isBlank()) {
            try {
                BackupTargetKinds.targetFor(backup.get(InstanceBackupModel.TARGET_ID))
                    .delete(key);
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
        Integer retention = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Backup.RETENTION);
        if (retention == null || retention <= 0) {
            return;
        }
        List<Row> complete = Models.get(InstanceBackupModel.class).find()
            .where(InstanceBackupModel.INSTANCE_ID.eq(instanceId))
            .where(InstanceBackupModel.STATUS.eq(InstanceBackupModel.STATUS_COMPLETE))
            .orderBy(InstanceBackupModel.CREATED_AT, SortOrder.DESC)
            .all();
        for (int i = retention; i < complete.size(); i++) {
            try {
                delete(complete.get(i).get(InstanceBackupModel.ID));
            } catch (Violations pruneFailed) {
                Blast.log("BACKUP: retention could not remove backup",
                    complete.get(i).get(InstanceBackupModel.ID), "- kept for a later sweep");
            }
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
            "tcp", volumes);
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

    private static Violations refusal(String key, Row row, IOException cause) {
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
