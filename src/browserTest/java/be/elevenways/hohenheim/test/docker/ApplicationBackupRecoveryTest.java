package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.instance.WorkloadIsolation;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.ArtifactDeploys;
import be.elevenways.hohenheim.server.backup.FilesystemBackupTarget;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerStreamConnection;
import be.elevenways.hohenheim.server.docker.DockerStreamTransport;
import be.elevenways.hohenheim.server.docker.DockerTransport;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.runtime.ImageIdentity;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.VolumeSnapshotSupport;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Public backup/restore orchestration with real archive crypto and real HTTP health probes.
 * The fixture is deliberately stateless: bind-volume bytes and kernel quota enforcement remain
 * live-lane proof, not something this in-memory Docker runtime pretends to implement. */
class ApplicationBackupRecoveryTest {
    private static final String IMAGE = "sha256:" + "a".repeat(64);
    private static final byte[] IMAGE_BYTES = "immutable exported image fixture".getBytes(StandardCharsets.UTF_8);

    @Test
    void restoreSurvivesSourceAndImageLossAndFailedCaptureRestartsSameRelease(@TempDir Path tmp)
            throws Exception {
        var datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        FakeDockerDaemon daemon = new FakeDockerDaemon();
        daemon.install();
        SnapshotRuntime runtime = new SnapshotRuntime(daemon.runtime());
        InstanceKinds.register(new SnapshotKind(runtime));
        ImageTransport transport = new ImageTransport(daemon);
        String priorData = HohenheimSettings.VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        DockerClient.overrideLocalTransportForTest(() -> transport);
        String priorStaging = HohenheimSettings.VALUES.getValue(HohenheimSettings.Backup.STAGING_PATH);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, tmp.resolve("data").toString());
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(tmp.resolve("ring.keys")));
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.STAGING_PATH, tmp.resolve("staging").toString());
        try {
            Db.run(datasource, () -> {
                HostFixtures.admitLocal();
                try {
                    Row image = Models.get(RuntimeImageModel.class).createEmptyRow();
                    image.set(RuntimeImageModel.NAME, "backup-runtime");
                    image.set(RuntimeImageModel.DOCKER_IMAGE, "fake/runtime:1");
                    image.set(RuntimeImageModel.BUILD_CONTEXT, "images/java-25");
                    image.set(RuntimeImageModel.DEFAULT_COMMAND, "java -jar app.jar");
                    image.set(RuntimeImageModel.DEFAULT_PORT, 8080);
                    Models.get(RuntimeImageModel.class).save(image);
                    Row app = Models.get(InstanceModel.class).createEmptyRow();
                    app.set(InstanceModel.NAME, "backup-upload-app");
                    app.set(InstanceModel.KIND, "hohenheim:application");
                    app.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
                    app.set(InstanceModel.RUNTIME_IMAGE_ID, image.get(RuntimeImageModel.ID));
                    app.set(InstanceModel.SETTINGS, Map.of());
                    Models.get(InstanceModel.class).save(app);
                    int appId = app.get(InstanceModel.ID);
                    new InstanceVariables().setValue(appId, null, "TOKEN",
                        InstanceVariableModel.KIND_SECRET, "secret-survives-host-loss");
                    Path jar = tmp.resolve("app.jar");
                    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
                        zip.putNextEntry(new ZipEntry("app.txt"));
                        zip.write("uploaded-source".getBytes(StandardCharsets.UTF_8));
                        zip.closeEntry();
                    }
                    byte[] source = Files.readAllBytes(jar);
                    ArtifactDeploys.restoreSource(appId, jar);
                    int servingId = ApplicationReleases.restore(appId, IMAGE, ignored -> {}).instanceId();
                    String servingHandle = FakeDockerDaemon.handleOf(servingId);
                    var target = new FilesystemBackupTarget(tmp.resolve("target"));
                    InstanceBackups backups = new InstanceBackups();

                    int stopsBeforeMismatch = daemon.callCount("stop:" + servingHandle);
                    new InstanceVariables().setValue(appId, null, "TOKEN",
                        InstanceVariableModel.KIND_SECRET, "unapplied-secret");
                    Throwable unappliedSecret = catchThrowable(() -> backups.backupNow(appId, null, target));
                    assertThat(unappliedSecret).isInstanceOf(Violations.class);
                    assertThat(unappliedSecret.toString()).doesNotContain("unapplied-secret");
                    assertThat(daemon.callCount("stop:" + servingHandle)).isEqualTo(stopsBeforeMismatch);
                    assertThat(new InstanceService().resolve(servingId).spec().env())
                        .containsEntry("TOKEN", "secret-survives-host-loss");
                    assertThat(Models.get(InstanceBackupModel.class).find()
                        .where(InstanceBackupModel.INSTANCE_ID.eq(appId)).count()).isZero();
                    // Returning to the running credentials makes the backup below coherent
                    // and, after source/image loss, demonstrably restorable.
                    new InstanceVariables().setValue(appId, null, "TOKEN",
                        InstanceVariableModel.KIND_SECRET, "secret-survives-host-loss");

                    runtime.failCapture = true;
                    assertThat(catchThrowable(() -> backups.backupNow(appId, null, target))).isNotNull();
                    assertThat(daemon.isRunning(servingHandle)).isTrue();
                    assertThat(ApplicationReleases.ownedServing(appId).get(InstanceModel.ID)).isEqualTo(servingId);
                    assertThat(Models.get(InstanceBackupModel.class).find()
                        .where(InstanceBackupModel.INSTANCE_ID.eq(appId))
                        .where(InstanceBackupModel.STATUS.eq(InstanceBackupModel.STATUS_COMPLETE)).count()).isZero();
                    assertThat(Models.get(InstanceBackupModel.class).find()
                        .where(InstanceBackupModel.INSTANCE_ID.eq(appId)).first()
                        .get(InstanceBackupModel.ERROR)).doesNotContain("secret-survives-host-loss");
                    runtime.failCapture = false;
                    int backupId = backups.backupNow(appId, null, target);
                    runtime.failStart = true;
                    assertThat(catchThrowable(() -> backups.backupNow(appId, null, target))).isNotNull();
                    assertThat(daemon.isRunning(servingHandle)).isFalse();
                    assertThat(Models.get(InstanceBackupModel.class).find()
                        .where(InstanceBackupModel.INSTANCE_ID.eq(appId))
                        .where(InstanceBackupModel.ERROR.eq(
                            "Backup failed: prior serving workload could not be restarted")).count()).isEqualTo(1);
                    runtime.failStart = false;
                    ApplicationReleases.inScopeUnchecked(appId, () -> new InstanceService().deploy(servingId));
                    Row backup = Models.get(InstanceBackupModel.class).findById(backupId);
                    Path sourcePath = ArtifactDeploys.acceptedArtifact(appId);
                    // The application is DESTROYED the way production destroys one: its
                    // releases go and the record is trashed, never hard-deleted -- a trashed
                    // row is what its backups, variables and artifact rows keep referencing
                    // under the enforced foreign keys.
                    new InstanceService().destroy(appId);
                    Files.deleteIfExists(sourcePath);
                    Files.delete(jar);
                    transport.hasImage = false;
                    InstanceBackups.Restored restored = backups.restoreToNew(backup, target,
                        "recovered-upload-app", ServerModel.localServerId());
                    assertThat(restored.instanceId()).isNotEqualTo(appId);
                    assertThat(restored.complete()).isTrue();
                    assertThat(Files.readAllBytes(ArtifactDeploys.acceptedArtifact(restored.instanceId())))
                        .isEqualTo(source);
                    int restoredRelease = ApplicationReleases.ownedServing(restored.instanceId()).get(InstanceModel.ID);
                    assertThat(new InstanceService().liveStatus(restoredRelease).running()).isTrue();
                    assertThat(new InstanceService().resolve(restoredRelease).spec().env())
                        .containsEntry("TOKEN", "secret-survives-host-loss");
                    Row secret = Models.get(InstanceVariableModel.class)
                        .findByInstanceId(restored.instanceId()).get(0);
                    assertThat(secret.get(InstanceVariableModel.KIND)).isEqualTo(InstanceVariableModel.KIND_SECRET);
                    assertThat(secret.get(InstanceVariableModel.PLAIN_VALUE)).isNull();
                    assertThat(transport.loaded).isEqualTo(IMAGE_BYTES);
                    String restoredHandle = FakeDockerDaemon.handleOf(restoredRelease);
                    int stopsBeforeDefaultChange = daemon.callCount("stop:" + restoredHandle);
                    Row changedRuntime = Models.get(RuntimeImageModel.class)
                        .findById(image.get(RuntimeImageModel.ID));
                    changedRuntime.set(RuntimeImageModel.DEFAULT_COMMAND, "java -jar unapplied.jar");
                    Models.get(RuntimeImageModel.class).save(changedRuntime);
                    assertThat(catchThrowable(() -> backups.backupNow(restored.instanceId(), null, target)))
                        .isInstanceOf(Violations.class);
                    assertThat(daemon.callCount("stop:" + restoredHandle)).isEqualTo(stopsBeforeDefaultChange);
                    assertThat(new InstanceService().liveStatus(restoredRelease).running()).isTrue();
                    assertThat(Models.get(InstanceBackupModel.class).find()
                        .where(InstanceBackupModel.INSTANCE_ID.eq(restored.instanceId())).count()).isZero();
                    ApplicationReleases.destroyFor(restored.instanceId());
                    Files.deleteIfExists(ArtifactDeploys.acceptedArtifact(restored.instanceId()));
                } catch (IOException failed) {
                    throw new IllegalStateException(failed);
                }
            });
        } finally {
            daemon.close();
            FakeDockerDaemon.restore();
            FieldEncryption.installKeyring(null);
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.STAGING_PATH, priorStaging);
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, priorData);
        }
    }

    private static final class SnapshotRuntime implements InstanceRuntime, VolumeSnapshotSupport {
        private final InstanceRuntime delegate;
        boolean failCapture;
        SnapshotRuntime(InstanceRuntime delegate) { this.delegate = delegate; }
        public String create(InstanceSpec spec) throws IOException { return delegate.create(spec); }
        public void start(String handle) throws IOException {
            if (failStart) throw new IOException("scripted restart failure");
            delegate.start(handle);
        }
        public void stop(String handle, int grace) throws IOException { delegate.stop(handle, grace); }
        public void destroy(String handle) throws IOException { delegate.destroy(handle); }
        public InstanceStatus status(String handle) { return delegate.status(handle); }
        public ImageIdentity imageIdentity(InstanceSpec spec) { return new ImageIdentity(spec.image(), IMAGE); }
        boolean failStart;
        public List<CapturedVolume> captureVolumes(InstanceSpec spec, Map<String, String> logical,
                                                   Path directory, long max) throws IOException {
            if (status(spec.handle()).running()) throw new IOException("capture attempted while writer runs");
            if (failCapture) throw new IOException("scripted capture failure");
            if (!logical.isEmpty()) throw new IOException("fixture does not model volume bytes");
            return List.of();
        }
        public void restoreVolumes(InstanceSpec spec, Map<String, String> logical, Map<String, Path> files)
                throws IOException {
            if (status(spec.handle()).running()) throw new IOException("restore attempted after first start");
            if (!logical.isEmpty() || !files.isEmpty()) throw new IOException("fixture does not model volumes");
        }
        public void removeVolumesForRestore(InstanceSpec spec, Map<String, String> logical,
                                             Collection<String> names) throws IOException {
            throw new IOException("fixture never removes volumes");
        }
    }

    private record SnapshotKind(SnapshotRuntime runtime) implements InstanceKindHandler {
        private static final ReleaseKind REAL = new ReleaseKind();
        public Identifier typeId() { return ReleaseKind.ID; }
        public String getDisplayName() { return REAL.getDisplayName(); }
        public Microcopy getLabel() { return REAL.getLabel(); }
        public Microcopy getDescription() { return REAL.getDescription(); }
        public Icon getIcon() { return REAL.getIcon(); }
        public String getColor() { return REAL.getColor(); }
        public Schema getSchema() { return REAL.getSchema(); }
        public boolean tenantAuthored() { return REAL.tenantAuthored(); }
        public boolean generatedOnly() { return REAL.generatedOnly(); }
        public WorkloadIsolation isolation() { return REAL.isolation(); }
        public InstanceRuntime runtimeFor(String server) { return runtime; }
        public InstanceSpec specFor(int id, Map<String, Object> settings) { return REAL.specFor(id, settings); }
        public int defaultFootprintMb(Map<String, Object> settings) { return REAL.defaultFootprintMb(settings); }
    }

    private static final class ImageTransport implements DockerTransport, DockerStreamTransport {
        private final FakeDockerDaemon daemon;
        boolean hasImage = true;
        byte[] loaded;
        ImageTransport(FakeDockerDaemon daemon) { this.daemon = daemon; }
        public byte[] roundTrip(byte[] request, long timeout) throws IOException {
            String text = new String(request, StandardCharsets.ISO_8859_1);
            if (text.startsWith("POST /images/load?")) {
                loaded = java.util.Arrays.copyOfRange(request, text.indexOf("\r\n\r\n") + 4, request.length);
                if (!java.util.Arrays.equals(loaded, IMAGE_BYTES)) throw new IOException("wrong imported image bytes");
                hasImage = true;
                return response(200, "{}");
            }
            if (text.startsWith("GET /images/" + IMAGE + "/json")) {
                return hasImage ? response(200, "{\"Id\":\"" + IMAGE + "\"}")
                    : response(404, "{\"message\":\"image lost\"}");
            }
            return daemon.roundTrip(request, timeout);
        }
        public byte[] roundTrip(byte[] request, long timeout, long maximum) throws IOException {
            byte[] response = roundTrip(request, timeout);
            if (response.length > maximum) throw new IOException("fixture response exceeds cap");
            return response;
        }
        public DockerStreamConnection openStream(byte[] request, long timeout) throws IOException {
            String text = new String(request, StandardCharsets.ISO_8859_1);
            if (text.startsWith("POST /images/load?")) {
                // The load lane STREAMS its tar as a chunked body (it used to be buffered
                // into one roundTrip): the answer is only available once the body ends.
                return new LoadConnection();
            }
            if (!text.startsWith("GET /images/") || !text.contains("/get HTTP/") || !hasImage) {
                throw new IOException("no exportable image");
            }
            ByteArrayInputStream bytes = new ByteArrayInputStream(response(200,
                new String(IMAGE_BYTES, StandardCharsets.UTF_8)));
            return new DockerStreamConnection() {
                private boolean closed;
                public int read(byte[] buffer, int offset, int length) { return bytes.read(buffer, offset, length); }
                public void write(byte[] data) throws IOException { throw new IOException("read-only export"); }
                public void close() { closed = true; }
                public boolean isReleased() { return closed; }
                public String diagnostics() { return ""; }
            };
        }
        /** Collects a chunked image-load body, verifies it, then answers like the daemon. */
        private final class LoadConnection implements DockerStreamConnection {
            private final java.io.ByteArrayOutputStream written = new java.io.ByteArrayOutputStream();
            private ByteArrayInputStream answer;
            private boolean closed;

            public synchronized int read(byte[] buffer, int offset, int length) throws IOException {
                while (answer == null && !closed) {
                    try {
                        wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted");
                    }
                }
                if (answer == null) {
                    throw new IOException("closed before the load body ended");
                }
                return answer.read(buffer, offset, length);
            }

            public synchronized void write(byte[] data) throws IOException {
                written.writeBytes(data);
                byte[] body = dechunked(written.toByteArray());
                if (body == null) {
                    return;
                }
                loaded = body;
                if (!java.util.Arrays.equals(loaded, IMAGE_BYTES)) {
                    closed = true;
                    notifyAll();
                    throw new IOException("wrong imported image bytes");
                }
                hasImage = true;
                answer = new ByteArrayInputStream(response(200, "{}"));
                notifyAll();
            }

            public synchronized void close() {
                closed = true;
                notifyAll();
            }

            public synchronized boolean isReleased() { return closed; }
            public String diagnostics() { return ""; }
        }

        /** The body of a COMPLETE chunked stream, or null while the zero chunk is missing. */
        private static byte[] dechunked(byte[] raw) {
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            int position = 0;
            while (true) {
                int lineEnd = indexOfCrlf(raw, position);
                if (lineEnd < 0) {
                    return null;
                }
                int size = Integer.parseInt(new String(raw, position, lineEnd - position,
                    StandardCharsets.ISO_8859_1).trim(), 16);
                int start = lineEnd + 2;
                if (size == 0) {
                    return raw.length >= start + 2 ? body.toByteArray() : null;
                }
                if (raw.length < start + size + 2) {
                    return null;
                }
                body.write(raw, start, size);
                position = start + size + 2;
            }
        }

        private static int indexOfCrlf(byte[] raw, int from) {
            for (int i = from; i + 1 < raw.length; i++) {
                if (raw[i] == '\r' && raw[i + 1] == '\n') {
                    return i;
                }
            }
            return -1;
        }

        private static byte[] response(int status, String body) {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            byte[] header = ("HTTP/1.1 " + status + " OK\r\nContent-Length: " + payload.length
                + "\r\nContent-Type: application/json\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
            byte[] result = java.util.Arrays.copyOf(header, header.length + payload.length);
            System.arraycopy(payload, 0, result, header.length, payload.length);
            return result;
        }
    }
}
