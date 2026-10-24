package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.instance.VariableKind;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.model.Retention;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.ArtifactDeploys;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.source.SiteSources;
import be.elevenways.hohenheim.server.backup.BackupArchive;
import be.elevenways.hohenheim.server.backup.BackupManifest;
import be.elevenways.hohenheim.server.backup.BackupManifest.FileEntry;
import be.elevenways.hohenheim.server.backup.BackupManifest.InstanceProfile;
import be.elevenways.hohenheim.server.backup.BackupManifest.TemplateRef;
import be.elevenways.hohenheim.server.backup.BackupManifest.VariableEntry;
import be.elevenways.hohenheim.server.backup.BackupTarget;
import be.elevenways.hohenheim.server.backup.BackupTargetKinds;
import be.elevenways.hohenheim.server.backup.UploadLedger;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.InstanceService.Resolved;
import be.elevenways.hohenheim.server.runtime.ImageIdentity;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.NativeSnapshotSupport;
import be.elevenways.hohenheim.server.runtime.VolumeSnapshotSupport;
import be.elevenways.hohenheim.server.runtime.PortPublication;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.hohenheim.server.util.FileTrees;
import be.elevenways.hohenheim.server.util.Sha256;
import be.elevenways.hohenheim.server.util.UtcStamp;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * bypasses any of it is a hole.
 */
public final class InstanceBackups {

    private static final Retention.InstanceCaptures RETENTION = new Retention.InstanceCaptures(
            InstanceBackupModel.class,
        InstanceBackupModel.ID, InstanceBackupModel.INSTANCE_ID, InstanceBackupModel.STATUS,
        InstanceBackupModel.STATUS_COMPLETE, HohenheimSettings.Backup.RETENTION, "BACKUP");

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
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimCapabilities.BACKUPS);
        Row row = requireRow(instanceId);
        Microcopy noTarget = backupRefusal(row);
        if (noTarget != null) {
            throw Violations.ofForm(noTarget);
        }
        Integer targetId = row.get(InstanceModel.BACKUP_TARGET_ID);
        return backupNow(instanceId, targetId, BackupTargetKinds.targetFor(targetId));
    }

    /**
     * Why the instance cannot be backed up at all, or null when it can: THE rule behind the refusal of
     * {@link #backupNow} and the reason its offer is disabled.
     *
     * AIDEV-NOTE: an application built from git or a build context is refused (decided round R3g): its backup lane
     * restores a serving release from the captured artifact, and such an application's artifact is a build of a
     * commit nothing here can reproduce. Backing up only its data volumes would be a new payload, not decided.
     */
    public static @Nullable Microcopy backupRefusal(@NonNull Row row) {
        String name = String.valueOf((Object) row.get(InstanceModel.NAME));
        if (sourceUnreproducible(row)) {
            return HohenheimMicrocopy.VIOLATIONS.of("instance_backup_source_unreproducible").withArg("name", name);
        }
        if (row.get(InstanceModel.BACKUP_TARGET_ID) != null) {
            return null;
        }
        // The target is the instance's own choice (no installation default), so the refusal says where it is made.
        return HohenheimMicrocopy.VIOLATIONS.of("instance_backup_target_none").withArg("name", name);
    }

    /** Whether the record is an application whose release is built from git or a build context. */
    private static boolean sourceUnreproducible(@NonNull Row row) {
        Map<String, Object> settings = RawValues.map(row.get(InstanceModel.SETTINGS));
        return InstanceKinds.isReleaseManaged(row.get(InstanceModel.KIND))
            && (SiteSources.hasRepository(settings) || settings.containsKey("build_context"));
    }

    /**
     * Explicit-target variant (tests, future re-target flows).
     *
     * AIDEV-NOTE: the backup's activity row is written HERE, once, whichever surface asked (the API, the admin, a
     * schedule step); no surface writes one of its own. A failed attempt writes one too, carrying the failure's own
     * reason: a scheduled backup that fails has no toast, so the log is where an operator reads why.
     */
    public int backupNow(int instanceId, @Nullable Integer targetId, @NonNull BackupTarget target) {
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimCapabilities.BACKUPS);
        Row owner = requireRow(instanceId);
        int backupId;
        try {
            if (InstanceKinds.isReleaseManaged(owner.get(InstanceModel.KIND))) {
                // QUEUED behind a running deploy, as the converge lock it replaces did: the
                // serving release a backup captures must be the one a finished converge left.
                backupId = this.instances.operations().exclusive(instanceId,
                    InstanceOperationLock.Contention.QUEUE,
                    () -> backupApplication(instanceId, targetId, target));
            } else {
                backupId = this.instances.operations().exclusive(instanceId,
                    InstanceOperationLock.Contention.REFUSE,
                    () -> backupResolved(instanceId, targetId, target, requireRow(instanceId),
                        this.instances.resolve(instanceId), false));
            }
        } catch (RuntimeException failed) {
            History.event(Models.get(InstanceModel.class), instanceId,
                Change.fact(HohenheimActivityAction.BACKUP_FAILED, Map.of(HohenheimActivityAction.REASON_KEY,
                    String.valueOf(HohenheimViolations.reasonOf(failed)))));
            throw failed;
        }
        History.event(Models.get(InstanceModel.class), instanceId,
            Change.fact(HohenheimActivityAction.BACKUP, Map.of(HohenheimActivityAction.BACKUP_KEY, backupId)));
        return backupId;
    }

    /** The application lane of {@link #backupNow}; the caller holds the application's lock. */
    private int backupApplication(int instanceId, @Nullable Integer targetId,
                                  @NonNull BackupTarget target) {
        Row owner = requireRow(instanceId);
        if (sourceUnreproducible(owner)) {
            throw Violations.ofForm(Objects.requireNonNull(backupRefusal(owner)));
        }
        Row serving = ApplicationReleases.ownedServing(instanceId);
        if (serving == null) {
            throw HohenheimViolations.instanceRefusal("instance_backup_failed", owner,
                new IOException("Application has no serving release to capture"));
        }
        int[] result = new int[1];
        ApplicationReleases.inScopeUnchecked(instanceId, () -> result[0] =
            backupResolved(instanceId, targetId, target, owner,
                this.instances.resolve(serving.get(InstanceModel.ID)), true));
        return result[0];
    }

    private int backupResolved(int instanceId, @Nullable Integer targetId,
                               BackupTarget target, Row owner, Resolved resolved,
                               boolean application) {
        int runtimeId = resolved.row().get(InstanceModel.ID);
        InstanceOperationGuard.requireOperable(owner);
        InstanceOperationGuard.requireOperable(resolved.row());
        NativeSnapshotSupport nativeSupport = resolved.runtime()
            instanceof NativeSnapshotSupport n ? n : null;
        VolumeSnapshotSupport support = nativeSupport == null
            ? InstanceSnapshots.requireSupport(resolved) : null;
        Map<String, String> volumes = application ? applicationVolumes(instanceId)
            : nativeSupport == null ? InstanceSnapshots.logicalVolumes(resolved) : Map.of();
        // Health BEFORE the capture: a dead target must not cost the workload downtime.
        try {
            target.healthCheck();
        } catch (IOException unhealthy) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("backup_target_unhealthy")
                .withArg("reason", HohenheimViolations.reasonOf(unhealthy)));
        }
        InstanceStatus live = resolved.liveStatus();
        InstanceSnapshots.requirePresent(live, resolved);
        boolean wasRunning = live.running();
        if (application) {
            requireCoherentApplication(owner, resolved);
        }

        String stamp = UtcStamp.now();
        Path staging = stagingRoot().resolve("backup-" + instanceId + "-" + stamp);
        BackupManifest.ApplicationEntry applicationEntry = null;
        ImageIdentity applicationImage = null;
        Map<String, Path> applicationFiles = new LinkedHashMap<>();
        if (application) {
            try {
                Files.createDirectories(staging);
                applicationImage = InstanceSnapshots.requireSupport(resolved).imageIdentity(resolved.spec());
                applicationEntry = captureApplication(owner, resolved, applicationImage,
                    staging, applicationFiles);
            } catch (IOException | RuntimeException error) {
                FileTrees.deleteQuietly(staging);
                failedRow(instanceId, targetId, null, "Application source/image capture failed");
                throw HohenheimViolations.instanceRefusal("instance_backup_failed", owner,
                    new IOException("Application source/image capture failed", error));
            }
        }

        // What the capture produced, filled in by the window's work.
        List<List<VolumeSnapshotSupport.CapturedVolume>> capturedHolder = new ArrayList<>(1);
        List<ImageIdentity> imageHolder = new ArrayList<>(1);
        List<NativeSnapshotSupport.ExportedDisk> disks = new ArrayList<>();
        // AIDEV-NOTE: the declared host-directory volumes ride every PLAIN backup, read on the host's volume
        // storage inside the same capture window (decided round r2r); an application's own lane already carries
        // its declarations and their contents, so it is not captured twice.
        List<InstanceVolumes.Archived> hostVolumes = new ArrayList<>();
        String serverName = ServerModel.nameOf(resolved.serverId());
        String payload;
        if (nativeSupport != null) {
            // -- capture phase (native lane: LIVE, crash-consistent) ------------
            // The daemon's own atomic export stands in for the stop; the CAPTURING
            // stamp still gates rival power actions for the operation's duration, and a
            // capture changes no data, so a failed one hands the record back as it was.
            String prior = wasRunning ? InstanceModel.STATUS_RUNNING
                : InstanceModel.STATUS_STOPPED;
            payload = BackupManifest.PAYLOAD_INSTANCE_EXPORT;
            try {
                InstanceMaintenanceWindow.run(this.instances, resolved,
                    new InstanceMaintenanceWindow.Plan(InstanceModel.STATUS_CAPTURING, false,
                        prior, false, InstanceMaintenanceWindow.Failure.HAND_BACK),
                    () -> {
                        Files.createDirectories(staging);
                        imageHolder.add(nativeSupport.imageIdentity(resolved.spec()));
                        Path export = staging.resolve("instance.tar");
                        long size = nativeSupport.exportBackup(resolved.spec(), export,
                            InstanceSnapshots.maxArchiveBytes(), false);
                        capturedHolder.add(List.of(new VolumeSnapshotSupport.CapturedVolume(
                            "instance", "/", export, size)));
                        disks.addAll(nativeSupport.exportDisks(resolved.spec(), staging,
                            InstanceSnapshots.maxArchiveBytes()));
                        hostVolumes.addAll(InstanceVolumes.archiveAll(instanceId, serverName, staging,
                            InstanceSnapshots.maxArchiveBytes()));
                    });
            } catch (IOException | RuntimeException error) {
                FileTrees.deleteQuietly(staging);
                failedRow(instanceId, targetId, null, HohenheimViolations.reasonOf(error));
                if (error instanceof Violations refused) {
                    throw refused;
                }
                throw HohenheimViolations.instanceRefusal("instance_backup_failed", resolved.row(), error);
            }
        } else {
            // -- capture phase (volume lane: COLD) ------------------------------
            // AIDEV-NOTE: the stop and the restart stay HERE rather than in the window's
            // plan: an application's serving release that cannot be started again is
            // stamped error on the APPLICATION record, a policy only this lane has. The
            // window still owns the protected status, so it can never outlive a failure.
            payload = BackupManifest.PAYLOAD_VOLUME_TARS;
            boolean stopped = false;
            Exception captureFailure = null;
            this.instances.leases().requireFence(resolved.serverId());
            ImageIdentity knownImage = applicationImage;
            try {
                if (wasRunning) {
                    TenantWrites.inAuthorizedOperation(() -> this.instances.stop(runtimeId));
                    stopped = true;
                }
                InstanceMaintenanceWindow.run(this.instances, resolved,
                    new InstanceMaintenanceWindow.Plan(InstanceModel.STATUS_CAPTURING, false,
                        InstanceModel.STATUS_STOPPED, false,
                        InstanceMaintenanceWindow.Failure.HAND_BACK),
                    () -> {
                        Files.createDirectories(staging);
                        imageHolder.add(application ? Objects.requireNonNull(knownImage)
                            : support.imageIdentity(resolved.spec()));
                        List<VolumeSnapshotSupport.CapturedVolume> volumeTars =
                            support.captureVolumes(resolved.spec(), volumes, staging,
                                InstanceSnapshots.maxArchiveBytes());
                        if (application && !volumes.keySet().equals(volumeTars.stream()
                                .map(VolumeSnapshotSupport.CapturedVolume::name)
                                .collect(java.util.stream.Collectors.toSet()))) {
                            throw new IOException("Application volume capture is incomplete");
                        }
                        capturedHolder.add(volumeTars);
                        if (!application) {
                            hostVolumes.addAll(InstanceVolumes.archiveAll(instanceId, serverName, staging,
                                InstanceSnapshots.maxArchiveBytes()));
                        }
                    });
            } catch (IOException | RuntimeException error) {
                captureFailure = error;
                throw HohenheimViolations.instanceRefusal("instance_backup_failed", owner,
                    new IOException("Volume capture failed; no complete backup was stored", error));
            } finally {
                try {
                    if (stopped) {
                        // AIDEV-NOTE: the RUNTIME the capture stopped comes back as it was, never a new
                        // build of it. deploy() folds a source-deploying workspace into WorkspaceBuilds
                        // (checkout + build + restart), so a workspace whose current source does not
                        // build could never be backed up; deployWorkload starts the last built image.
                        // An application's serving release keeps its own converge (deploy).
                        TenantWrites.inAuthorizedOperation(() -> {
                            if (application) {
                                this.instances.deploy(runtimeId);
                            } else {
                                this.instances.deployWorkload(runtimeId);
                            }
                        });
                    }
                } catch (RuntimeException restartFailed) {
                    FileTrees.deleteQuietly(staging);
                    if (application) {
                        InstanceOperationGuard.stampError(this.instances.leases(), instanceId,
                            resolved.serverId(), owner.get(InstanceModel.NAME),
                            InstanceOperationGuard.startFailureOf(restartFailed), restartFailed.getMessage());
                    }
                    failedRow(instanceId, targetId, null,
                        "The workload could not be started again after the capture: "
                            + HohenheimViolations.reasonOf(restartFailed));
                    if (captureFailure != null) {
                        restartFailed.addSuppressed(captureFailure);
                    }
                    throw HohenheimViolations.instanceRefusal("instance_backup_failed", owner,
                        new IOException("The workload could not be started again after the capture ("
                            + HohenheimViolations.reasonOf(restartFailed) + ")"
                            + (captureFailure != null ? "; volume capture also failed" : ""),
                            restartFailed));
                }
                if (captureFailure != null) {
                    FileTrees.deleteQuietly(staging);
                    failedRow(instanceId, targetId, null,
                        "Volume capture failed: " + HohenheimViolations.reasonOf(captureFailure));
                }
            }
        }
        List<VolumeSnapshotSupport.CapturedVolume> captured = capturedHolder.get(0);
        ImageIdentity image = imageHolder.get(0);

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
        backup.set(InstanceBackupModel.REMOTE_KEY, key);
        Models.get(InstanceBackupModel.class).saveWritten(backup);
        try {
            BackupManifest manifest = buildManifest(owner, image, payload, captured, disks, hostVolumes,
                applicationEntry);
            Map<String, Path> files = new LinkedHashMap<>(applicationFiles);
            for (VolumeSnapshotSupport.CapturedVolume volume : captured) {
                files.put(volume.file().getFileName().toString(), volume.file());
            }
            for (NativeSnapshotSupport.ExportedDisk disk : disks) {
                files.put(BackupArchive.DISK_PREFIX + disk.file().getFileName(), disk.file());
            }
            for (InstanceVolumes.Archived volume : hostVolumes) {
                files.put(BackupArchive.HOST_VOLUME_PREFIX + volume.file().getFileName(), volume.file());
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
            // Written once the instance's claim is let go: a client restoring the backup it saw complete is never
            // refused by this capture.
            backup.set(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_COMPLETE);
            backup.set(InstanceBackupModel.SHA256, storedSha);
            backup.set(InstanceBackupModel.SIZE_BYTES, size);
            backup.set(InstanceBackupModel.SUMMARY, manifest.toSummary());
            this.instances.operations().afterRelease(instanceId, () -> {
                publish(backup);
                pruneForRetention(instanceId, targetId, target);
            });
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
                    HohenheimViolations.reasonOf(cleanupFailed));
            }
            backup.set(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_FAILED);
            backup.set(InstanceBackupModel.ERROR, HohenheimViolations.reasonOf(error));
            this.instances.operations().afterRelease(instanceId, () -> publish(backup));
            if (error instanceof Violations refused) {
                throw refused;
            }
            throw HohenheimViolations.instanceRefusal("instance_backup_failed", resolved.row(), error);
        } finally {
            FileTrees.deleteQuietly(staging);
        }
        // The retention sweep follows the completed row above, once the claim is let go.
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
     * @param movedPorts  each fixed host port the target host already held, now one it picks ("game 30000/tcp")
     */
    public record Restored(int instanceId, @NonNull List<String> notRestored, @NonNull List<String> movedPorts) {

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
     * A copy's restored settings for a host: each fixed host port another owner already claims there turned into one
     * the host picks itself, every free fixed port kept as it was.
     *
     * AIDEV-NOTE: restore-to-new only (plan section 72): beside its original on the same host the copy's first deploy
     * was refused on the original's numbers. An in-place restore keeps its record's ports, which its own claims hold.
     * The single-port shape is read only for a kind publishing through it ({@link InstanceKindHandler#portSettings}).
     *
     * @param settings the settings to store, a copy when a port moved
     * @param moved    one "name port/protocol" per port that now takes a free one
     */
    private record PortsOnCopy(@NonNull Map<String, Object> settings, @NonNull List<String> moved) {

        /** @return the copy's ports on {@code serverId} */
        static @NonNull PortsOnCopy of(@NonNull InstanceKindHandler kind, @NonNull Map<String, Object> restored,
                                       int serverId) {
            Map<String, Object> settings = new LinkedHashMap<>(restored);
            List<String> moved = new ArrayList<>();
            List<PublishedPort> ports = new ArrayList<>();
            for (PublishedPort port : PublishedPort.of(restored)) {
                boolean held = port.hostPort() != null && PortLedger.rivalHolder(serverId,
                    port.loopback() ? PublishedPort.LOOPBACK : "", port.hostPort(), port.protocol(), null, null) != null;
                ports.add(held ? port.withoutHostPort() : port);
                if (held) {
                    moved.add(port.name() + " " + port.hostPort() + "/" + port.protocol());
                }
            }
            if (!moved.isEmpty()) {
                settings.put(PublishedPort.FIELD, PublishedPort.toSettings(ports));
            }
            String hostPort = DockerContainerKind.HOST_PORT.getName();
            PortPublication single = kind.portSettings().contains(hostPort)
                ? DockerContainerKind.publicationOf(restored) : null;
            Integer fixed = single == null ? null : single.declaredHostPort();
            if (fixed != null && PortLedger.rivalHolder(serverId, single.ledgerBindAddress(), fixed, single.protocol(),
                    null, null) != null) {
                settings.remove(hostPort);
                moved.add(hostPort + " " + fixed + "/" + single.protocol());
            }
            return new PortsOnCopy(moved.isEmpty() ? restored : settings, List.copyOf(moved));
        }
    }

    /**
     * The FIRST gate of every restore-to-new entry point, so a tenant learns nothing
     * beyond the refusal itself.
     *
     * Restore-to-new CREATES an instance, and it does so outside the creation funnel
     * (InstanceTemplates.createFromTemplate): no create authority, no placement
     * decision, no creator grant, and an image taken from the archive's manifest
     * rather than an approved template. Every one of those is a tenant-facing gate,
     * so this path is OPERATOR-ONLY until it routes through the funnel -- refused
     * loudly rather than quietly producing a record its creator cannot even see.
     *
     * @throws Violations when the write originates from a tenant
     */
    private static void refuseTenantRestore() {
        if (TenantWrites.isTenantOriginated()) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("backup_restore_operator_only"));
        }
    }

    /**
     * One host a restore could land on, as the restore's host pick shows it.
     *
     * @param refusal the placement gate's reason this host cannot take the restore, null when it can
     */
    public record Destination(int serverId, @NonNull String name, @Nullable Microcopy refusal) {
    }

    /**
     * Every host, each with the verdict {@link #restoreToNew} would give it for this backup's kind and install media.
     *
     * AIDEV-NOTE: the SAME call the restore's gate makes ({@link #destinationRefusal}), so the pick and the write
     * cannot disagree; the kind and the media come from the row's non-sensitive manifest summary, the archive is not
     * opened.
     */
    public static @NonNull List<Destination> destinationsFor(@NonNull Row backup) {
        Map<String, Object> summary = RawValues.map((Object) backup.get(InstanceBackupModel.SUMMARY));
        String kindId = RawValues.nonBlankString(summary.get("kind"));
        InstanceKindHandler kind = InstanceKinds.getHandler(kindId);
        List<String> installMedia = new ArrayList<>();
        for (Object medium : RawValues.list(summary.get("install_media"))) {
            String name = RawValues.nonBlankString(medium);
            if (name != null) {
                installMedia.add(name);
            }
        }
        List<Destination> destinations = new ArrayList<>();
        for (Row server : Models.get(ServerModel.class).find().all()) {
            Integer serverId = server.get(ServerModel.ID);
            if (serverId == null) {
                continue;
            }
            destinations.add(new Destination(serverId, String.valueOf((Object) server.get(ServerModel.NAME)),
                kind == null ? HohenheimMicrocopy.VIOLATIONS.of("instance_kind_unknown").withArg("kind", kindId)
                    : destinationRefusal(serverId, kind, Map.of(), installMedia)));
        }
        return destinations;
    }

    /**
     * Why this host cannot receive a restore of this kind: the kind-on-host placement verdict, then the install media
     * the backed-up machine had in its drives, which an import needs on the destination.
     *
     * AIDEV-NOTE: the import's own device override cannot drop the cdrom (Incus 7.3 refuses {@code type=none} on a
     * device with a boot priority), so a host without the ISO fails mid-import with "Storage volume not found". The
     * operator's way out is to detach the media and back up again, or to pick the host that holds them.
     *
     * @param settings the restored settings, empty where the archive is not opened (the pick)
     * @return the first refusal, null when the host takes it
     */
    private static @Nullable Microcopy destinationRefusal(int serverId, @NonNull InstanceKindHandler kind,
                                                          @NonNull Map<String, Object> settings,
                                                          @NonNull List<String> installMedia) {
        // The record does not exist yet, so the owner it will be CHARGED to is asked for through the create hook's
        // own derivation -- never a second spelling.
        Microcopy placement = InstancePlacement.kindRefusalOn(serverId, kind, InstanceQuota.creationBucket(), settings);
        if (placement != null || installMedia.isEmpty()) {
            return placement;
        }
        List<String> absent = new InstallMedia().absentOn(serverId, installMedia);
        return absent.isEmpty() ? null : HohenheimMicrocopy.VIOLATIONS.of("restore_install_media_missing")
            .withArg("media", String.join(", ", absent)).withArg("host", ServerModel.labelOf(serverId));
    }

    /**
     * Restore a completed backup to a NEW instance: verify everything (target sha,
     * GCM authentication, per-payload checksums, kind, admission, capacity) BEFORE
     * creating anything, then run the full create story -- record save (quota
     * reservation), control-plane re-materialization, volume restore, fenced deploy.
     *
     * @param newName name for the new instance, or null for a derived one
     * @param serverSpelling target host (any canonical spelling), or null to let placement pick one; never the
     *                       source host by default, because a restore elsewhere is the recovery for a dead one
     * @return the NEW instance, deployed and running, with whatever could not be restored
     * @throws Violations naming the refusal or failure
     */
    public @NonNull Restored restoreToNew(int backupId, @Nullable String newName,
                                          @Nullable Object serverSpelling) {
        // BEFORE resolving anything: a tenant must learn only that this lane is
        // operator-only. Resolving the target first made a broken target answer
        // backup_target_missing instead, which is a probe into control-plane state.
        refuseTenantRestore();
        Row backup = Models.get(InstanceBackupModel.class).findById(backupId);
        if (backup == null || !InstanceBackupModel.isRestorable(backup)) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("backup_not_restorable")
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
        refuseTenantRestore();
        if (!InstanceBackupModel.isRestorable(backup)) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("backup_not_restorable")
                .withArg("id", backup.get(InstanceBackupModel.ID)));
        }
        String key = backup.get(InstanceBackupModel.REMOTE_KEY);
        String stamp = UtcStamp.now();
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
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("backup_corrupt")
                    .withArg("reason", HohenheimViolations.reasonOf(corrupt)));
            }
            BackupManifest manifest = opened.manifest();
            InstanceKindHandler restoredKind = InstanceKinds.getHandler(manifest.kind());
            if (restoredKind == null) {
                throw Violations.ofField("kind", manifest.kind(),
                    HohenheimMicrocopy.VIOLATIONS.of("instance_kind_unknown").withArg("kind", manifest.kind()));
            }
            Map<String, Object> settings = InstanceKindFields.typedNumbers(InstanceModel.SETTINGS, manifest.kind(),
                manifest.restoredSettings(InstanceModel.SETTINGS.resolveSchemaForSiblingValue(manifest.kind())));
            int serverId = serverSpelling != null
                ? ServerModel.canonicalServerId(serverSpelling)
                : InstancePlacement.chooseForBucket(InstanceQuota.creationBucket(),
                    InstancePlacement.Workload.imported(restoredKind, settings), null);
            // A fixed host port the target host already holds (the original beside it) becomes one it picks.
            PortsOnCopy ports = PortsOnCopy.of(restoredKind, settings, serverId);
            Microcopy refused = destinationRefusal(serverId, restoredKind, ports.settings(), manifest.installMedia());
            if (refused != null) {
                throw Violations.ofForm(refused);
            }
            // The data disks count at their declared size: that is what each restored volume claims on the host.
            RestoreCapacity.require(serverId, Math.addExact(manifest.totalVolumeBytes(), manifest.totalDiskBytes()));
            RestoreCapacity.requireVolumeStorage(serverId, manifest.totalHostVolumeBytes());
            boolean application = InstanceKinds.isReleaseManaged(manifest.kind());
            Integer runtimeImageId = null;
            if (application) {
                requireApplicationInventory(manifest);
                RestoreCapacity.require(serverId, Math.addExact(manifest.totalVolumeBytes(),
                    Math.addExact(manifest.application().artifact().size(), manifest.application().image().size())));
                runtimeImageId = bindRuntimeImage(manifest.application().runtimeImage());
            } else if (manifest.application() != null) {
                throw new IllegalStateException("Application payload belongs to a non-application kind");
            } else if (manifest.profile() != null && manifest.profile().runtimeImage() != null) {
                // A workspace cannot exist without the runtime image it runs inside: re-bound by name, refused when
                // this controller's image of that name differs.
                runtimeImageId = bindRuntimeImage(manifest.profile().runtimeImage());
            }

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
            record.set(InstanceModel.SETTINGS, ports.settings());
            record.set(InstanceModel.SERVER_ID, serverId);
            record.set(InstanceModel.RUNTIME_IMAGE_ID, runtimeImageId);
            record.set(InstanceModel.CRASH_POLICY, profile != null
                ? profile.crashPolicy() : InstanceModel.CRASH_NONE);
            record.set(InstanceModel.TEMPLATE_ID, localTemplateId(profile, notRestored));
            record.set(InstanceModel.BACKUP_TARGET_ID, localTargetId(profile, notRestored));
            noteUnrestorableGrouping(profile, notRestored);
            // No game address, deliberately: the same name on this copy would take the players from the original
            // (GameAddresses); the operator points it here on the copy's Configuration when that is what they want.
            Models.get(InstanceModel.class).save(record);
            int newId = record.get(InstanceModel.ID);
            try {
                return landRestore(backup, manifest, opened, staging, profile, application, record, newId,
                    notRestored, ports.moved());
            } catch (RuntimeException failed) {
                abandonRestore(newId, failed);
                throw failed;
            }
        } finally {
            if (opened != null) {
                try {
                    Files.deleteIfExists(opened.zip());
                } catch (IOException ignored) {
                    // staging cleanup below covers it
                }
            }
            FileTrees.deleteQuietly(staging);
        }
    }

    /**
     * Land a verified archive in the record {@link #restoreToNew} just created: its table rows, then the payload,
     * then the first start through the ordinary deploy.
     *
     * @throws Violations naming the failure; the caller takes the record back
     */
    private @NonNull Restored landRestore(@NonNull Row backup, @NonNull BackupManifest manifest,
                                          BackupArchive.@NonNull Opened opened, @NonNull Path staging,
                                          @Nullable InstanceProfile profile, boolean application, @NonNull Row record,
                                          int newId, @NonNull List<String> notRestored,
                                          @NonNull List<String> movedPorts) {
        // The table-backed rows the settings map cannot carry, BEFORE resolve():
        // variable values substitute into the spec's command and environment, so a
        // spec resolved without them is a spec for a different workload. The host volumes
        // are declared here for the same reason: the spec binds what the record declares.
        restoreVariables(newId, profile);
        restoreFiles(newId, profile, notRestored);
        for (BackupManifest.HostVolumeEntry volume : manifest.hostVolumes()) {
            BackupManifest.VolumeDeclaration declared = volume.declaration();
            InstanceVolumes.declare(newId, declared.name(), declared.containerPath(), declared.quotaBytes(),
                declared.exclusive());
        }
        if (application) {
            try {
                requireRematerialized(profile, newId);
                for (BackupManifest.VolumeDeclaration volume : manifest.application().declarations()) {
                    InstanceVolumes.declare(newId, volume.name(), volume.containerPath(),
                        volume.quotaBytes(), volume.exclusive());
                }
                Map<String, Path> applicationFiles = BackupArchive.extractApplication(opened,
                    staging.resolve("application"));
                ArtifactDeploys.restoreSource(newId,
                    applicationFiles.get(manifest.application().artifact().file()));
                int serverId = record.get(InstanceModel.SERVER_ID);
                DockerClient docker = new ServerService().clientFor(serverId);
                this.instances.leases().requireFence(serverId);
                docker.loadImage(applicationFiles.get(manifest.application().image().file()));
                if (!manifest.imageId().equals(docker.inspectImage(manifest.imageId()).get("Id"))) {
                    throw new IOException("Imported runtime image identity does not match backup");
                }
                Map<String, Path> tars = BackupArchive.extractVolumes(opened,
                    staging.resolve("volumes"));
                ApplicationReleases.restore(newId, manifest.imageId(), candidate -> {
                    Map<String, String> declared = applicationVolumes(newId);
                    if (!declared.keySet().equals(tars.keySet())) {
                        throw new IOException("Restored application volume inventory differs from backup");
                    }
                    InstanceSnapshots.requireSupport(candidate).restoreVolumes(candidate.spec(),
                        declared, tars);
                });
                Restored restored = new Restored(newId, List.copyOf(notRestored), movedPorts);
                recordRestore(newId, backup.get(InstanceBackupModel.ID), restored);
                return restored;
            } catch (IOException | RuntimeException error) {
                throw HohenheimViolations.instanceRefusal("instance_restore_failed", record,
                    new IOException("Application restore failed; the new application is not"
                        + " a verified recovery", error));
            }
        }

        // Read BEFORE resolve derives the environment (InstanceDatabaseLinks.credentialGenerations says why).
        Map<Integer, Integer> credentials = InstanceDatabaseLinks.credentialGenerations(newId);
        Resolved resolved = this.instances.resolve(newId);
        resolved.requireImage();
        try {
            // A new record has nothing running: the window restores into it, and a
            // failure holds it in error (a named refusal like backup_payload_mismatch
            // included) until the caller takes it back, while success starts it through the ordinary deploy funnel.
            InstanceMaintenanceWindow.run(this.instances, resolved,
                new InstanceMaintenanceWindow.Plan(InstanceModel.STATUS_RESTORING, false,
                    InstanceModel.STATUS_STOPPED, true,
                    InstanceMaintenanceWindow.Failure.HOLD_ERROR),
                () -> restorePayload(profile, newId, manifest, resolved, credentials, opened, staging));
        } catch (IOException error) {
            throw HohenheimViolations.instanceRefusal("instance_restore_failed", record, error);
        }
        Restored restored = new Restored(newId, List.copyOf(notRestored), movedPorts);
        recordRestore(newId, backup.get(InstanceBackupModel.ID), restored);
        Blast.log("BACKUP: restored backup", backup.get(InstanceBackupModel.ID),
            "to NEW instance", newId, restored.complete()
                ? "(complete)" : "- NOT restored: " + restored.describeLosses());
        return restored;
    }

    /**
     * Take back what a failed restore-to-new made, through the ordinary destroy: the workload, the volumes its device
     * rows name and the record (its quota reservation with it).
     *
     * AIDEV-NOTE: decided in round r2p: a failed restore leaves nothing behind. The record it made never ran, so held
     * in error it only offered Restart over a workload that was never whole; the refusal the restore answers with is
     * what says what went wrong. Only when the destroy itself fails (an unreachable daemon) does the record stay, held
     * in error by that destroy, its failure attached to the restore's.
     */
    private void abandonRestore(int newId, @NonNull RuntimeException failure) {
        Row record = Models.get(InstanceModel.class).findById(newId);
        try {
            this.instances.destroy(newId);
            // An ordinary destroy keeps host volumes (a soft-deleted record's data survives it); these were made by
            // this restore alone, so they go with it.
            if (record != null && !InstanceVolumes.declaredFor(newId).isEmpty()) {
                InstanceVolumes.destroyAll(newId, ServerModel.nameOf(record.get(InstanceModel.SERVER_ID)));
            }
        } catch (RuntimeException cleanupFailed) {
            failure.addSuppressed(cleanupFailed);
            Blast.log("BACKUP: could not take back the record of a failed restore", newId, "-",
                cleanupFailed.getMessage());
        }
    }

    /**
     * Land a verified archive's payload in a freshly created record: count what the
     * database re-materialized, then rebuild the workload from the export (native lane)
     * or create it and restore its volume tars.
     *
     * @param credentials the attachments' credential generations, read before {@code resolved} derived its
     *                    environment: what the volume lane's create records, as a deploy does
     */
    private static void restorePayload(@Nullable InstanceProfile profile, int newId,
                                       @NonNull BackupManifest manifest,
                                       @NonNull Resolved resolved,
                                       @NonNull Map<Integer, Integer> credentials,
                                       BackupArchive.@NonNull Opened opened,
                                       @NonNull Path staging) throws IOException {
        // Count what the DATABASE now holds, before a single byte of payload moves: a
        // re-materialization that wrote less than the manifest declares must stop the
        // restore, never deploy a workload whose config files or variables silently did
        // not come back.
        requireRematerialized(profile, newId);
        // Host volumes land on the host's volume storage BEFORE the workload: an import validates its directory
        // devices against the paths, and a created container binds them.
        String serverName = ServerModel.nameOf(resolved.serverId());
        InstanceVolumes.restoreAll(newId, serverName,
            BackupArchive.extractHostVolumes(opened, staging.resolve("host-volumes")),
            resolved.handler().volumeOwnerUid(newId, serverName));
        if (BackupManifest.PAYLOAD_INSTANCE_EXPORT.equals(manifest.payload())) {
            // Native lane: the daemon rebuilds the whole instance from its own export;
            // the deploy after the window CONVERGES onto it (the incus driver never
            // replaces an owned instance from its image).
            if (!(resolved.runtime() instanceof NativeSnapshotSupport nativeSupport)) {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("backup_payload_mismatch")
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
            // The export keeps a machine's ISO drives, extra NICs and data disks (an import onto a host holding the
            // ISO lands them), so the new record gets the rows for what the daemon now holds, under the names it
            // holds them. The disks the backup carries get theirs FIRST: their quota is judged before anything lands.
            Map<String, Integer> diskSizes = new LinkedHashMap<>();
            manifest.disks().forEach(disk -> diskSizes.put(disk.device(), disk.sizeGb()));
            InstanceDevices.recordRestoredDisks(newId, diskSizes);
            Map<String, Path> disks = BackupArchive.extractDisks(opened, staging.resolve("disks"));
            // The ports are claimed on this host before the workload lands, so its proxies forward the numbers the
            // record now holds; the deploy that starts it reuses these claims.
            InstanceSpec spec = resolved.spec().withPreallocatedPorts(
                PortPublications.reserve(newId, resolved.serverId(), resolved.spec().publications()));
            InstanceDevices.recordImportedDevices(newId,
                nativeSupport.importBackup(spec, export, disks, manifest.mayCarryExtraNics()));
        } else {
            VolumeSnapshotSupport support = InstanceSnapshots.requireSupport(resolved);
            Map<String, String> volumes = InstanceSnapshots.logicalVolumes(resolved);
            resolved.runtime().create(resolved.spec());
            InstanceDatabaseLinks.stampDeployed(credentials);
            Map<String, Path> tars = BackupArchive.extractVolumes(opened,
                staging.resolve("volumes"));
            support.restoreVolumes(resolved.spec(), volumes, tars);
        }
    }

    /**
     * Remove a backup's artifact from its target and delete the row.
     *
     * AIDEV-NOTE: a backup outlives its instance; once the instance's id is cleared no grant can name it, so the
     * operator alone governs the row (the admin entry's survivingArchive parent offers it there).
     */
    public void delete(int backupId) {
        Row backup = Models.get(InstanceBackupModel.class).findById(backupId);
        if (backup == null) {
            return;
        }
        Integer instanceId = backup.get(InstanceBackupModel.INSTANCE_ID);
        if (instanceId == null) {
            HohenheimAccess.requireOperatorOperation();
        } else {
            HohenheimAccess.requireOperationCapability(instanceId, HohenheimCapabilities.BACKUPS);
        }
        LEDGER.delete(backup);
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
     * The sweep as {@code backupNow} runs it, carrying the target the completed backup used so same-target rows are
     * prunable even when their id cannot be re-resolved ({@link UploadLedger#delete(Row, Integer, BackupTarget)}).
     *
     * AIDEV-NOTE: each row is deleted without re-asking the BACKUPS capability: it was proven for the instance when the
     * operation was entered, and re-asking per row only made the sweep's {@code catch (Violations)} unable to tell a
     * refusal from an unreachable target. Same reasoning as {@code InstanceSnapshots.deleteAuthorized}.
     */
    private void pruneForRetention(int instanceId, @Nullable Integer currentTargetId,
                                   @Nullable BackupTarget currentTarget) {
        RETENTION.sweep(instanceId, old -> LEDGER.delete(old, currentTargetId, currentTarget));
    }

    /** The instance backup ledger's columns, for the boot settle and the delete. */
    private static final UploadLedger LEDGER = new UploadLedger(InstanceBackupModel.class, InstanceBackupModel.ID,
        InstanceBackupModel.TARGET_ID, InstanceBackupModel.STATUS, InstanceBackupModel.REMOTE_KEY,
        InstanceBackupModel.ERROR, InstanceBackupModel.CREATED_AT, InstanceBackupModel.UPDATED_AT, "BACKUP");

    /**
     * Boot recovery: settle every backup row a killed controller left {@code uploading} ({@link UploadLedger}).
     * Rows written by this process (the listener binds before this sweep runs) are live operations and are left
     * alone; if this process is killed too, the next boot settles them.
     */
    public static void recoverInterrupted() {
        LEDGER.settleInterrupted();
    }

    // -- internals ------------------------------------------------------------

    /** Persist what the upload staged on its row; a row deleted during the upload has nothing left to settle. */
    private static void publish(@NonNull Row backup) {
        Models.get(InstanceBackupModel.class).saveWrittenUnlessGone(backup);
    }

    private static Row requireRow(int instanceId) {
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        if (row == null) {
            throw new IllegalStateException("Instance is absent");
        }
        return row;
    }

    private static Map<String, String> applicationVolumes(int applicationId) {
        Map<String, String> volumes = new LinkedHashMap<>();
        for (Row volume : InstanceVolumes.declaredFor(applicationId)) {
            volumes.put(volume.get(InstanceVolumeModel.NAME),
                volume.get(InstanceVolumeModel.CONTAINER_PATH));
        }
        return volumes;
    }

    /** A serving image must never be paired with unapplied authored credentials/defaults. */
    private static void requireCoherentApplication(Row owner, Resolved serving) {
        int applicationId = owner.get(InstanceModel.ID);
        Path artifact = ArtifactDeploys.servingArtifact(applicationId);
        Map<String, Object> servingSettings = RawValues.map(serving.row().get(InstanceModel.SETTINGS));
        Map<String, Object> overrides = new LinkedHashMap<>(
            ArtifactDeploys.sourceOverrides(applicationId));
        // Keep the canonical artifact builder facts, but judge the SERVING source.
        // A code-only rollback changes the forward pointer without changing config.
        if (artifact != null) {
            overrides.put("artifact_path", artifact.toAbsolutePath().toString());
            overrides.put("commit_sha", servingSettings.get("commit_sha"));
        }
        String desired = ReleaseEngine.sourceFingerprint(applicationId,
            ApplicationReleases.resolvedSettings(owner, overrides));
        if (artifact == null || !desired.equals(servingSettings.get("source_fingerprint"))) {
            throw HohenheimViolations.instanceRefusal("instance_backup_failed", owner, new IOException(
                "Application configuration does not match its serving release. Settle"
                    + " unapplied settings, secrets or runtime defaults before taking a backup;"
                    + " refusing to combine a running image with different configuration"));
        }
    }

    private BackupManifest.ApplicationEntry captureApplication(Row owner, Resolved serving,
            ImageIdentity image, Path staging, Map<String, Path> files) throws IOException {
        int appId = owner.get(InstanceModel.ID);
        Path applicationDirectory = Files.createDirectories(staging.resolve("application"));
        Path accepted = ArtifactDeploys.servingArtifact(appId);
        if (accepted == null || !Files.isRegularFile(accepted)) {
            throw new IOException("Application has no accepted immutable uploaded artifact");
        }
        String digest = BackupArchive.sha256Of(accepted);
        Map<String, Object> servingSettings = RawValues.map(serving.row().get(InstanceModel.SETTINGS));
        if (!digest.equals(servingSettings.get("commit_sha"))) {
            throw new IOException("Accepted artifact is not the serving release source;"
                + " backup refuses a source/runtime mismatch");
        }
        for (Row release : ApplicationReleases.ownedInstances(appId)) {
            if (!Objects.equals(release.get(InstanceModel.ID), serving.row().get(InstanceModel.ID))
                    && this.instances.liveStatus(release.get(InstanceModel.ID)).running()) {
                throw new IOException("Another application release is still running;"
                    + " wait for its drain before taking a cold backup");
            }
        }
        if (Files.size(accepted) > InstanceSnapshots.maxArchiveBytes()) {
            throw new IOException("Application artifact exceeds the backup payload limit");
        }
        Path artifact = applicationDirectory.resolve("artifact.jar");
        Files.copy(accepted, artifact);
        if (!digest.equals(BackupArchive.sha256Of(artifact))) {
            throw new IOException("Accepted artifact changed during backup capture");
        }
        if (!Sha256.isImageId(image.id())) {
            throw new IOException("Serving application has no immutable runtime image identity");
        }
        Path imageTar = applicationDirectory.resolve("runtime-image.tar");
        new ServerService().clientFor(serving.serverId())
            .saveImage(image.id(), imageTar, InstanceSnapshots.maxArchiveBytes());
        files.put(BackupArchive.APPLICATION_PREFIX + "artifact.jar", artifact);
        files.put(BackupArchive.APPLICATION_PREFIX + "runtime-image.tar", imageTar);
        List<BackupManifest.VolumeDeclaration> declarations = new ArrayList<>();
        for (Row volume : InstanceVolumes.declaredFor(appId)) {
            declarations.add(new BackupManifest.VolumeDeclaration(
                volume.get(InstanceVolumeModel.NAME), volume.get(InstanceVolumeModel.CONTAINER_PATH),
                volume.get(InstanceVolumeModel.QUOTA_BYTES),
                Boolean.TRUE.equals(volume.get(InstanceVolumeModel.EXCLUSIVE))));
        }
        return new BackupManifest.ApplicationEntry(runtimeIdentity(RuntimeImages.requireFor(owner)),
            new BackupManifest.PayloadEntry("artifact.jar", digest, Files.size(artifact)),
            new BackupManifest.PayloadEntry("runtime-image.tar", BackupArchive.sha256Of(imageTar),
                Files.size(imageTar)), List.copyOf(declarations));
    }


    private static Map<String, Object> runtimeIdentity(Row image) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("name", image.get(RuntimeImageModel.NAME));
        identity.put("docker_image", image.get(RuntimeImageModel.DOCKER_IMAGE));
        identity.put("incus_image", image.get(RuntimeImageModel.INCUS_IMAGE));
        identity.put("build_context", image.get(RuntimeImageModel.BUILD_CONTEXT));
        identity.put("default_command", image.get(RuntimeImageModel.DEFAULT_COMMAND));
        identity.put("default_port", image.get(RuntimeImageModel.DEFAULT_PORT));
        identity.put("default_build_command", image.get(RuntimeImageModel.DEFAULT_BUILD_COMMAND));
        identity.put("workdir", image.get(RuntimeImageModel.WORKDIR));
        identity.put("shell", image.get(RuntimeImageModel.SHELL));
        identity.put("uid_mode", image.get(RuntimeImageModel.UID_MODE));
        return identity;
    }

    private static int bindRuntimeImage(Map<String, Object> identity) {
        String name = (String) identity.get("name");
        Row image = Models.get(RuntimeImageModel.class).findByName(name);
        if (image == null) {
            image = Models.get(RuntimeImageModel.class).createEmptyRow();
            image.set(RuntimeImageModel.NAME, name);
            image.set(RuntimeImageModel.DOCKER_IMAGE, (String) identity.get("docker_image"));
            image.set(RuntimeImageModel.INCUS_IMAGE, (String) identity.get("incus_image"));
            image.set(RuntimeImageModel.BUILD_CONTEXT, (String) identity.get("build_context"));
            image.set(RuntimeImageModel.DEFAULT_COMMAND, (String) identity.get("default_command"));
            image.set(RuntimeImageModel.DEFAULT_PORT, identity.get("default_port") instanceof Number n
                ? n.intValue() : null);
            image.set(RuntimeImageModel.DEFAULT_BUILD_COMMAND, (String) identity.get("default_build_command"));
            image.set(RuntimeImageModel.WORKDIR, (String) identity.get("workdir"));
            image.set(RuntimeImageModel.SHELL, (String) identity.get("shell"));
            image.set(RuntimeImageModel.UID_MODE, (String) identity.get("uid_mode"));
            Models.get(RuntimeImageModel.class).save(image);
        }
        if (!identity.equals(runtimeIdentity(image)) || !Boolean.TRUE.equals(image.get(RuntimeImageModel.ENABLED))) {
            throw new IllegalStateException("Runtime image '" + name
                + "' differs from the backup or is disabled; refusing numerical-id rebinding");
        }
        return image.get(RuntimeImageModel.ID);
    }

    private static void requireApplicationInventory(BackupManifest manifest) {
        BackupManifest.ApplicationEntry app = manifest.application();
        if (app == null || manifest.profile() == null
                || !BackupManifest.PAYLOAD_VOLUME_TARS.equals(manifest.payload())
                || !Sha256.isImageId(manifest.imageId())) {
            throw new IllegalStateException("Application backup lacks recoverable source/image inventory");
        }
        Map<String, String> declared = new LinkedHashMap<>();
        for (BackupManifest.VolumeDeclaration volume : app.declarations()) {
            declared.put(volume.name(), volume.containerPath());
        }
        Map<String, String> captured = new LinkedHashMap<>();
        for (BackupManifest.VolumeEntry volume : manifest.volumes()) {
            captured.put(volume.name(), volume.containerPath());
        }
        if (!declared.equals(captured)) {
            throw new IllegalStateException("Application backup volume declarations and payloads differ");
        }
    }

    private @NonNull BackupManifest buildManifest(
            @NonNull Row owner,
            @NonNull ImageIdentity image,
            @NonNull String payload,
            @NonNull List<VolumeSnapshotSupport.CapturedVolume> captured,
            @NonNull List<NativeSnapshotSupport.ExportedDisk> exportedDisks,
            @NonNull List<InstanceVolumes.Archived> archivedHostVolumes,
            BackupManifest.@Nullable ApplicationEntry application) throws IOException {
        int instanceId = owner.get(InstanceModel.ID);
        Set<String> subjects = HohenheimAccess.manageSubjectsOf(InstanceModel.MODEL_ID, instanceId);
        String ownership = subjects != null ? HohenheimAccess.packSubjects(subjects) : "";
        Map<String, Object> settings = RawValues.map(owner.get(InstanceModel.SETTINGS));
        Integer containerPort = settings.get("container_port") instanceof Number port
            ? port.intValue() : null;
        List<BackupManifest.VolumeEntry> volumes = new ArrayList<>();
        for (VolumeSnapshotSupport.CapturedVolume volume : captured) {
            volumes.add(new BackupManifest.VolumeEntry(volume.name(), volume.containerPath(),
                volume.file().getFileName().toString(),
                BackupArchive.sha256Of(volume.file()), volume.size()));
        }
        List<BackupManifest.DiskEntry> disks = new ArrayList<>();
        for (NativeSnapshotSupport.ExportedDisk disk : exportedDisks) {
            disks.add(new BackupManifest.DiskEntry(disk.device(), disk.sizeGb(), new BackupManifest.PayloadEntry(
                disk.file().getFileName().toString(), BackupArchive.sha256Of(disk.file()), disk.size())));
        }
        List<BackupManifest.HostVolumeEntry> hostVolumes = new ArrayList<>();
        for (InstanceVolumes.Archived volume : archivedHostVolumes) {
            hostVolumes.add(new BackupManifest.HostVolumeEntry(
                new BackupManifest.VolumeDeclaration(volume.name(), volume.containerPath(), volume.quotaBytes(),
                    volume.exclusive()),
                new BackupManifest.PayloadEntry(volume.file().getFileName().toString(),
                    BackupArchive.sha256Of(volume.file()), volume.size())));
        }
        return new BackupManifest(BackupManifest.FORMAT_VERSION, Now.instant().toString(),
            HostPreflight.controllerVersion(),
            String.valueOf((Object) owner.get(InstanceModel.NAME)),
            String.valueOf((Object) owner.get(InstanceModel.KIND)),
            payload, settings, image.reference(), image.id(), InstanceDevices.installMediaOf(instanceId),
            InstanceDevices.extraNicsOf(instanceId), ownership,
            containerPort,
            "tcp", volumes, disks, hostVolumes, profileOf(owner, instanceId), application);
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
            // Fail closed: a kind nobody recognizes is carried as a SECRET, never as plain.
            VariableKind kind = VariableKind.of(variable.get(InstanceVariableModel.KIND));
            String value = kind.isSecret()
                ? variable.get(InstanceVariableModel.SECRET_VALUE)
                : variable.get(InstanceVariableModel.PLAIN_VALUE);
            variables.add(new VariableEntry(key, kind.token(), value == null ? "" : value));
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

        // An application's runtime image rides its own entry; one authority per fact in the archive.
        Integer runtimeImageId = row.get(InstanceModel.RUNTIME_IMAGE_ID);
        Row runtimeImage = runtimeImageId != null && !InstanceKinds.isReleaseManaged(row.get(InstanceModel.KIND))
            ? Models.get(RuntimeImageModel.class).findById(runtimeImageId) : null;
        return new InstanceProfile(template,
            crashPolicy != null ? crashPolicy : InstanceModel.CRASH_NONE,
            environmentName(row.get(InstanceModel.ENVIRONMENT_ID)),
            targetName(row.get(InstanceModel.BACKUP_TARGET_ID)),
            List.copyOf(variables), List.copyOf(files),
            runtimeImage != null ? runtimeIdentity(runtimeImage) : null);
    }

    /** The grouping environment's name, or null when unset or dangling. */
    private static @Nullable String environmentName(@Nullable Integer environmentId) {
        Row row = Models.get(EnvironmentModel.class).findById(environmentId);
        return row != null ? row.get(EnvironmentModel.NAME) : null;
    }

    /** The backup destination's name, or null when unset or dangling. */
    private static @Nullable String targetName(@Nullable Integer targetId) {
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
            variables.storeValue(instanceId, null, variable.key(), variable.kind(),
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
        Map<String, Object> body = new LinkedHashMap<>();
        if (backupId != null) {
            body.put(HohenheimActivityAction.BACKUP_KEY, backupId);
        }
        if (!restored.complete()) {
            body.put(HohenheimActivityAction.LOSSES_KEY, restored.describeLosses());
        }
        if (!restored.movedPorts().isEmpty()) {
            body.put(HohenheimActivityAction.MOVED_PORTS_KEY, List.copyOf(restored.movedPorts()));
        }
        History.event(Models.get(InstanceModel.class), instanceId,
            Change.fact(HohenheimActivityAction.RESTORED_BACKUP, body));
    }


    /** A FAILED row for an attempt that stored nothing, written once the instance's claim is let go. */
    private void failedRow(int instanceId, @Nullable Integer targetId,
                           @Nullable String key, @NonNull String error) {
        Row backup = Models.get(InstanceBackupModel.class).createEmptyRow();
        backup.set(InstanceBackupModel.INSTANCE_ID, instanceId);
        backup.set(InstanceBackupModel.TARGET_ID, targetId);
        backup.set(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_FAILED);
        backup.set(InstanceBackupModel.REMOTE_KEY, key);
        backup.set(InstanceBackupModel.ERROR, error);
        this.instances.operations().afterRelease(instanceId, () -> Models.get(InstanceBackupModel.class).save(backup));
    }

    private static @NonNull EncryptionKeyring keyring() {
        return FieldEncryption.requireKeyring();
    }



    /** @return the configured backup staging root, which migrations stage through too */
    static @NonNull Path stagingRoot() {
        return Path.of(Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Backup.STAGING_PATH));
    }
}
