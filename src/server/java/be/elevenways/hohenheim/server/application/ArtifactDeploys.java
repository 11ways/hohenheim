package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ArtifactOperationModel;
import be.elevenways.hohenheim.model.ArtifactSourceModel;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.BootSettle;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.hohenheim.server.instance.DeployStartPolicy;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Immutable artifact ownership over the existing health-gated application release lane. */
public final class ArtifactDeploys {
    static final String BUILD_STAMP = "META-INF/blast/build-info.tsv";
    static final int MAX_STAMP_BYTES = 256 * 1024;
    private static final long MAX_INFLATED_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_DIRECTORY_BYTES = 16 * 1024 * 1024;

    private ArtifactDeploys() {}

    public static File directoryFor(int applicationId) {
        String dataPath = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        String base = dataPath == null || dataPath.isBlank() ? "/opt/hohenheim/data" : dataPath;
        return Path.of(base, "artifacts", InstanceModel.MODEL_ID.getPath(), String.valueOf(applicationId)).toFile();
    }

    public static Path uploadPathFor(int applicationId) throws IOException {
        // Fence even the HTTP streaming window: a boot sweep on a rival controller must
        // not mistake an old, still-uploading request for abandoned scratch.
        Row application = ApplicationReleases.requireApplication(applicationId);
        HostLeases.production().requireFence(
            ServerModel.canonicalServerId(application.get(InstanceModel.SERVER_ID)));
        Path uploads = directoryFor(applicationId).toPath().resolve("uploads");
        Files.createDirectories(uploads);
        return Files.createTempFile(uploads, "upload-", ".jar");
    }

    /** Persist before handing work to a thread. The uploaded bytes already have an exact identity. */
    public static Row accept(int siteId, int applicationId, Path upload) {
        var model = Models.get(ArtifactOperationModel.class);
        Row operation = model.createEmptyRow();
        operation.set(ArtifactOperationModel.SITE_ID, siteId);
        operation.set(ArtifactOperationModel.APPLICATION_ID, applicationId);
        operation.set(ArtifactOperationModel.ARTIFACT_SHA256, digestOf(upload));
        operation.set(ArtifactOperationModel.STATUS, ArtifactOperationModel.PENDING);
        model.save(operation);
        return operation;
    }

    /** Every accepted attempt, including pre-build refusal, reaches a durable terminal status. */
    public static void run(int operationId, Path upload, DeployTrigger trigger) {
        try {
            runAccepted(operationId, upload, trigger);
        } finally {
            deleteUpload(upload);
        }
    }

    private static void runAccepted(int operationId, Path upload, DeployTrigger trigger) {
        Row operation = Models.get(ArtifactOperationModel.class).findById(operationId);
        if (operation == null) {
            return;
        }
        int applicationId = operation.get(ArtifactOperationModel.APPLICATION_ID);
        InstanceOperationLock.production().exclusive(applicationId,
                InstanceOperationLock.Contention.QUEUE, () -> {
            boolean completed = false;
            try {
                operation.set(ArtifactOperationModel.STATUS, ArtifactOperationModel.RUNNING);
                Models.get(ArtifactOperationModel.class).save(operation);
                Row application = ApplicationReleases.requireApplication(applicationId);
                // The admission every deploy lane shares; on this background thread its power
                // half passes (the API asked CONFIG on the request thread), and the
                // databases-ready half is what keeps a release from booting without its
                // credentials.
                InstanceService.requireDeployAdmitted(applicationId);
                Microcopy declined = DeployStartPolicy.declineToStartStored(trigger,
                    ApplicationReleases.ownedServing(applicationId), application);
                if (declined != null) throw Violations.ofForm(declined);
                validateJar(upload);
                String digest = operation.get(ArtifactOperationModel.ARTIFACT_SHA256);
                Path artifact = own(upload, directoryFor(applicationId).toPath(), digest);
                Map<String, Object> overrides = facts(artifact, digest);
                String requestedFingerprint = ReleaseEngine.sourceFingerprint(applicationId,
                    ApplicationReleases.resolvedSettings(application, overrides));
                ApplicationReleases.Release release = ApplicationReleases.converge(applicationId, overrides);
                Row serving = ApplicationReleases.ownedServing(applicationId);
                Map<String, Object> settings = serving == null ? Map.of() : ApplicationReleases.storedSettings(serving);
                // A failed candidate can leave the old healthy release serving, including
                // the SAME JAR with old environment/runtime settings. Success requires the
                // entire requested source, not merely matching executable bytes.
                if (serving == null || release.instanceId() != serving.get(InstanceModel.ID)
                        || !digest.equals(settings.get("commit_sha"))
                        || !requestedFingerprint.equals(settings.get("source_fingerprint"))) {
                    finish(operation, ArtifactOperationModel.FAILED, "artifact_release_failed");
                    return;
                }
                operation.set(ArtifactOperationModel.INSTANCE_ID, release.instanceId());
                operation.set(ArtifactOperationModel.IMAGE_ID, String.valueOf(settings.get("image")));
                Db.currentOrDefault().withTransaction(transaction -> {
                    saveSource(applicationId, digest);
                    finish(operation, ArtifactOperationModel.SUCCEEDED, null);
                });
                completed = true;
                ActivityLog.record(Models.get(InstanceModel.class), applicationId,
                    InstanceService.ACTIVITY_DEPLOY_ACTION, trigger.word());
            } catch (IOException invalid) {
                finish(operation, ArtifactOperationModel.FAILED, "artifact_unreadable");
            } catch (RuntimeException failed) {
                // Never persist/log exception text: build and runtime errors can carry secrets.
                if (!completed) {
                    finish(operation, ArtifactOperationModel.FAILED, "artifact_release_failed");
                }
            }
        });
    }

    public static void handoffFailed(Row operation) {
        finish(operation, ArtifactOperationModel.FAILED, "artifact_upload_failed");
    }

    private static void finish(Row operation, String status, @Nullable String error) {
        operation.set(ArtifactOperationModel.STATUS, status);
        operation.set(ArtifactOperationModel.ERROR, error);
        operation.set(ArtifactOperationModel.FINISHED_AT, Now.instant());
        Models.get(ArtifactOperationModel.class).save(operation);
    }

    /** No path from a request/record is trusted: ownership is derived from app + digest. */
    static Path own(Path upload, Path applicationDirectory, String digest) throws IOException {
        Path artifact = artifactPath(applicationDirectory, digest);
        Files.createDirectories(artifact.getParent());
        Files.setPosixFilePermissions(upload, PosixFilePermissions.fromString("r--r--r--"));
        try {
            // Atomic create-if-absent: duplicate uploads never replace bytes being read by a build.
            Files.createLink(artifact, upload);
        } catch (FileAlreadyExistsException duplicate) {
            if (!digest.equals(digestOf(artifact))) throw new IOException("Artifact identity mismatch");
        }
        return artifact;
    }

    private static Path artifactPath(Path applicationDirectory, String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid artifact identity");
        return applicationDirectory.resolve("sha256").resolve(digest).resolve("app.jar");
    }

    private static Map<String, Object> facts(Path artifact, String digest) {
        return Map.of("artifact_path", artifact.toAbsolutePath().toString(), "commit_sha", digest,
            "builder", BuildOperationModel.KIND_DOCKERFILE, "dockerfile", "Dockerfile");
    }

    /** Only a healthy release or authenticated restore into a new application promotes this pointer. */
    public static Map<String, Object> sourceOverrides(int applicationId) {
        Row source = Models.get(ArtifactSourceModel.class).findById(applicationId);
        if (source == null) return Map.of();
        String digest = source.get(ArtifactSourceModel.ARTIFACT_SHA256);
        return facts(artifactPath(directoryFor(applicationId).toPath(), digest), digest);
    }

    public static @Nullable Path acceptedArtifact(int applicationId) {
        Map<String, Object> source = sourceOverrides(applicationId);
        return source.isEmpty() ? null : Path.of((String) source.get("artifact_path"));
    }

    /** The rollback-aware backup source: identity from the serving spec, never the forward pointer. */
    public static @Nullable Path servingArtifact(int applicationId) {
        return InstanceOperationLock.production().exclusive(applicationId,
                InstanceOperationLock.Contention.QUEUE, () -> {
            Row serving = ApplicationReleases.ownedServing(applicationId);
            if (serving == null) return null;
            Map<String, Object> settings = ApplicationReleases.storedSettings(serving);
            if (settings.get("artifact_path") == null) return null;
            return artifactPath(directoryFor(applicationId).toPath(), (String) settings.get("commit_sha"));
        });
    }

    /** Operator-only encrypted backup restoration, never a deployment or fabricated success receipt. */
    public static void restoreSource(int newAppId, Path artifact) throws IOException {
        IOException[] failed = new IOException[1];
        InstanceOperationLock.production().exclusive(newAppId,
                InstanceOperationLock.Contention.QUEUE, () -> {
            try {
                restoreSourceLocked(newAppId, artifact);
            } catch (IOException unreadable) {
                failed[0] = unreadable;
            }
        });
        if (failed[0] != null) {
            throw failed[0];
        }
    }

    /** {@link #restoreSource}'s body; the caller holds the application's operation lock. */
    private static void restoreSourceLocked(int newAppId, Path artifact) throws IOException {
        ApplicationReleases.requireApplication(newAppId);
        if (acceptedArtifact(newAppId) != null || !ApplicationReleases.ownedInstances(newAppId).isEmpty()
                || Models.get(ArtifactOperationModel.class).find()
                    .where(ArtifactOperationModel.APPLICATION_ID.eq(newAppId)).first() != null) {
            throw new IllegalStateException("Artifact restore requires a new application");
        }
        validateJar(artifact);
        Path upload = uploadPathFor(newAppId);
        try {
            Files.copy(artifact, upload, StandardCopyOption.REPLACE_EXISTING);
            String digest = digestOf(upload);
            own(upload, directoryFor(newAppId).toPath(), digest);
            saveSource(newAppId, digest);
        } finally {
            deleteUpload(upload);
        }
    }

    private static void saveSource(int applicationId, String digest) {
        var model = Models.get(ArtifactSourceModel.class);
        Row source = model.createEmptyRow();
        source.set(ArtifactSourceModel.APPLICATION_ID, applicationId);
        source.set(ArtifactSourceModel.ARTIFACT_SHA256, digest);
        model.save(source);
    }

    public static @Nullable Map<String, Object> operation(int siteId, int applicationId, int operationId) {
        Row row = Models.get(ArtifactOperationModel.class).find()
            .where(ArtifactOperationModel.ID.eq(operationId))
            .where(ArtifactOperationModel.SITE_ID.eq(siteId))
            .where(ArtifactOperationModel.APPLICATION_ID.eq(applicationId)).first();
        if (row == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation_id", row.get(ArtifactOperationModel.ID));
        result.put("status", row.get(ArtifactOperationModel.STATUS));
        result.put("artifact_sha256", row.get(ArtifactOperationModel.ARTIFACT_SHA256));
        result.put("instance_id", row.get(ArtifactOperationModel.INSTANCE_ID));
        result.put("image_id", row.get(ArtifactOperationModel.IMAGE_ID));
        result.put("error", row.get(ArtifactOperationModel.ERROR));
        return result;
    }

    /** Report serving release identity, not the newest upload (which may have failed or been rolled back). */
    public static Map<String, Object> current(int applicationId) {
        return InstanceOperationLock.production().exclusive(applicationId,
                InstanceOperationLock.Contention.QUEUE, () -> {
            Row serving = ApplicationReleases.ownedServing(applicationId);
            Map<String, Object> settings = serving == null ? Map.of() : ApplicationReleases.storedSettings(serving);
            String digest = settings.get("artifact_path") == null ? null : (String) settings.get("commit_sha");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", digest == null ? "absent"
                : new InstanceService().liveStatus(applicationId).state() == ContainerState.RUNNING ? "running" : "stopped");
            result.put("artifact_sha256", digest);
            result.put("instance_id", digest == null ? null : serving.get(InstanceModel.ID));
            result.put("image_id", digest == null ? null : settings.get("image"));
            result.put("stamps", digest == null ? null : readStamp(artifactPath(directoryFor(applicationId).toPath(), digest)));
            return result;
        });
    }

    public static void recoverInterrupted() {
        for (Row operation : Models.get(ArtifactOperationModel.class).find()
                .where(ArtifactOperationModel.STATUS.in(ArtifactOperationModel.PENDING, ArtifactOperationModel.RUNNING)).all()) {
            Instant written = operation.get(ArtifactOperationModel.UPDATED_AT);
            if (written == null) written = operation.get(ArtifactOperationModel.CREATED_AT);
            if (BootSettle.writtenByThisProcess(written)) continue;
            int applicationId = operation.get(ArtifactOperationModel.APPLICATION_ID);
            // Boot settle reclaims a trashed application's scratch too (trashed included).
            Row application = StoredRows.byId(Models.get(InstanceModel.class), applicationId);
            if (application == null) {
                finish(operation, ArtifactOperationModel.INTERRUPTED, "artifact_interrupted");
                continue;
            }
            BootSettle.underBorrowedHostLease(HostLeases.production(),
                ServerModel.canonicalServerId(application.get(InstanceModel.SERVER_ID)), () -> {
                    InstanceOperationLock.production().exclusive(applicationId,
                            InstanceOperationLock.Contention.QUEUE, () -> {
                        finish(operation, ArtifactOperationModel.INTERRUPTED, "artifact_interrupted");
                        cleanupUploads(directoryFor(applicationId).toPath().resolve("uploads"), BootSettle.processStart());
                    });
                });
        }
        // A killed HTTP upload has no receipt yet. Reclaim those too, under the same host fence.
        for (Row application : Models.get(InstanceModel.class).find().withTrashed()
                .where(InstanceModel.KIND.eq("hohenheim:application")).all()) {
            int applicationId = application.get(InstanceModel.ID);
            Path uploads = directoryFor(applicationId).toPath().resolve("uploads");
            if (!Files.isDirectory(uploads)) continue;
            BootSettle.underBorrowedHostLease(HostLeases.production(),
                ServerModel.canonicalServerId(application.get(InstanceModel.SERVER_ID)), () ->
                    cleanupUploads(uploads, BootSettle.processStart()));
        }
    }

    static void cleanupUploads(Path uploads, Instant before) {
        if (!Files.isDirectory(uploads)) return;
        try (var files = Files.list(uploads)) {
            files.filter(path -> {
                try { return Files.getLastModifiedTime(path).toInstant().isBefore(before); }
                catch (IOException ignored) { return false; }
            }).forEach(ArtifactDeploys::deleteUpload);
        } catch (IOException ignored) { /* Next boot can reclaim scratch. */ }
    }

    static String digestOf(Path artifact) {
        try {
            return SecureTokens.sha256Hex(artifact);
        } catch (Exception failed) {
            throw Violations.ofForm(Microcopy.of("artifact_unreadable").withFilter("scope", "violations"));
        }
    }

    /** Bound the central directory BEFORE ZipFile allocates it; ZIP64 exceeds this lane's entry limits. */
    private static void checkDirectory(Path artifact) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(artifact.toFile(), "r")) {
            int length = (int) Math.min(file.length(), 65557);
            byte[] end = new byte[length];
            file.seek(file.length() - length);
            file.readFully(end);
            for (int i = length - 22; i >= 0; i--) {
                if (little(end, i, 4) != 0x06054b50L || i + 22 + little(end, i + 20, 2) != length) continue;
                long entries = little(end, i + 10, 2);
                long bytes = little(end, i + 12, 4);
                long offset = little(end, i + 16, 4);
                if (entries == 0 || entries == 65535 || bytes > MAX_DIRECTORY_BYTES
                        || little(end, i + 4, 4) != 0 || little(end, i + 8, 2) != entries
                        || offset + bytes != file.length() - length + i) throw new IOException("Unsupported archive bounds");
                return;
            }
            throw new IOException("Missing archive directory");
        }
    }

    private static long little(byte[] bytes, int offset, int count) {
        long value = 0;
        for (int i = 0; i < count; i++) value |= (bytes[offset + i] & 255L) << (i * 8);
        return value;
    }

    /** Read every member with bounded inflation; parsing never executes application code. */
    static void validateJar(Path artifact) throws IOException {
        checkDirectory(artifact);
        try (ZipFile jar = new ZipFile(artifact.toFile())) {
            var entries = jar.entries();
            long total = 0;
            int count = 0;
            byte[] buffer = new byte[65536];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > 65534 || entry.getSize() > MAX_INFLATED_BYTES - total) throw new IOException("Archive too large");
                var crc = new CRC32();
                long size = 0;
                try (InputStream in = jar.getInputStream(entry)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        size += read;
                        crc.update(buffer, 0, read);
                        if (total > MAX_INFLATED_BYTES) throw new IOException("Archive too large");
                    }
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("Corrupt archive member");
            }
        }
    }

    static @Nullable String readStamp(Path artifact) {
        try {
            checkDirectory(artifact);
            try (ZipFile jar = new ZipFile(artifact.toFile())) {
                ZipEntry entry = jar.getEntry(BUILD_STAMP);
                if (entry == null || entry.getSize() > MAX_STAMP_BYTES) return null;
                try (InputStream in = jar.getInputStream(entry)) {
                    byte[] bytes = in.readNBytes(MAX_STAMP_BYTES + 1);
                    return bytes.length > MAX_STAMP_BYTES ? null : new String(bytes, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException unreadable) { return null; }
    }

    public static void deleteUpload(@Nullable Path upload) {
        if (upload == null) return;
        try { Files.deleteIfExists(upload); } catch (IOException ignored) { /* Boot recovery reclaims scratch. */ }
    }
}
