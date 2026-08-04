package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.backup.BackupTarget;
import be.elevenways.hohenheim.server.backup.FilesystemBackupTarget;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceSnapshots;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceNetworks;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.LiveIdOffsets;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Phase 4's snapshot/backup gate against the REAL local daemon, asserting HOST and
 * FILESYSTEM state beside every API answer: a snapshot-restore round trip with real
 * marker data (restore REPLACES, never merges), an encrypted backup exported to a
 * filesystem target and restored to a genuinely NEW instance (own id, own port
 * claim, own quota reservation), a corrupted artifact refused BEFORE any live state
 * changes, and an interrupted upload that leaves nothing a restore would accept.
 *
 * WHAT IS REAL vs SIMULATED: the daemon, containers, volumes, archives, encryption
 * and target filesystem semantics are real; the OFF-HOST failure domain is
 * simulated here -- the filesystem target lives on this same machine. The off-host
 * clause is met by LiveOffHostBackupTest, which exports to and restores from a
 * genuinely different machine.
 */
class InstanceSnapshotBackupLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;
    private static Path workRoot;
    private static String previousSnapshotPath;
    private static String previousStagingPath;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-snapshot-backup-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // Unique per-class instance ids => unique daemon handles (no cross-class 409s).
        LiveIdOffsets.apply(datasource);
        HohenheimTestRuntime.ensureBooted();

        workRoot = Files.createTempDirectory("hohenheim-backup-test");
        previousSnapshotPath = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Backup.SNAPSHOT_PATH);
        previousStagingPath = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Backup.STAGING_PATH);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.SNAPSHOT_PATH,
            workRoot.resolve("snapshots").toString());
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.STAGING_PATH,
            workRoot.resolve("staging").toString());
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(
            workRoot.resolve("test-keyring.keys")));

        if (PrivateNetns.available()) {
            netns = new PrivateNetns();
            WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
        }
    }

    @AfterAll
    static void tearDown() {
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
            netns = null;
        }
        FieldEncryption.installKeyring(null);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.SNAPSHOT_PATH,
            previousSnapshotPath);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.STAGING_PATH,
            previousStagingPath);
        deleteRecursively(workRoot);
    }

    private static void assumeLiveDaemon(DockerClient docker) throws IOException {
        Assumptions.assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        Assumptions.assumeTrue(imagePresent(docker, "alpine:latest"),
            "alpine:latest not present locally");
        Assumptions.assumeTrue(netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");
    }

    private static int instanceRecord(String name, Map<String, Object> settings) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, settings);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static Map<String, Object> alpineSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine");
        settings.put("tag", "latest");
        settings.put("command", "sleep 300");
        settings.put("container_port", 8080);
        settings.put("volumes", Map.of("data", "/data"));
        settings.put("environment_variables", Map.of("GAME_TOKEN", "s3cret-token"));
        return settings;
    }

    private static String execRead(DockerClient docker, String handle, String command)
            throws IOException {
        DockerClient.ExecResult result = docker.exec(handle, List.of("sh", "-c", command));
        assertThat(result.exitCode()).as("exec '" + command + "' succeeds").isEqualTo(0);
        return result.stdout().trim();
    }

    private static void execRun(DockerClient docker, String handle, String command)
            throws IOException {
        DockerClient.ExecResult result = docker.exec(handle, List.of("sh", "-c", command));
        assertThat(result.exitCode())
            .as("exec '" + command + "' succeeds (stderr: " + result.stderr() + ")")
            .isEqualTo(0);
    }

    /**
     * The snapshot gate: marker in, snapshot, mutate, restore, marker back -- and the
     * post-snapshot file is GONE, because restore replaces instead of merging.
     */
    @Test
    void snapshotRestoreRoundTripWithRealData() throws IOException {
        DockerClient docker = new DockerClient();
        assumeLiveDaemon(docker);

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            int id = instanceRecord("snapshot-journey", alpineSettings());
            String handle = "hohenheim-instance-" + id;
            InstanceService service = new InstanceService();
            InstanceSnapshots snapshots = new InstanceSnapshots();
            try {
                // 1. Deploy and plant the marker INSIDE the volume.
                service.deploy(id);
                run(() -> execRun(docker, handle, "echo v1 > /data/marker"));

                // 2. Snapshot: cold capture, then the instance is RUNNING again.
                int snapshotId = snapshots.create(id, "before-mutation");
                Row snapshot = Models.get(InstanceSnapshotModel.class).findById(snapshotId);
                assertThat((String) snapshot.get(InstanceSnapshotModel.STATUS))
                    .as("step 2: snapshot row is complete")
                    .isEqualTo(InstanceSnapshotModel.STATUS_COMPLETE);
                Path payload = Path.of((String) snapshot.get(InstanceSnapshotModel.DIRECTORY))
                    .resolve("data.tar");
                assertThat(Files.isRegularFile(payload))
                    .as("step 2: the payload tar exists on the controller filesystem").isTrue();
                assertThat((Object) snapshot.get(InstanceSnapshotModel.TOTAL_BYTES))
                    .as("step 2: real bytes were captured")
                    .satisfies(total -> assertThat(((Number) total).longValue()).isGreaterThan(0));
                assertThat(service.liveStatus(id).state())
                    .as("step 2: the instance is RUNNING again after the cold capture")
                    .isEqualTo(ContainerState.RUNNING);

                // 3. Mutate the volume: change the marker AND add a new file.
                run(() -> execRun(docker, handle, "echo v2 > /data/marker"));
                run(() -> execRun(docker, handle, "echo after > /data/created-after-snapshot"));

                // 4. Restore: the marker is back to v1...
                snapshots.restore(snapshotId);
                assertThat(run(() -> execRead(docker, handle, "cat /data/marker")))
                    .as("step 4: restore brought the captured marker back").isEqualTo("v1");

                // 5. ...and the post-snapshot file is GONE (replace, not merge).
                assertThat(run(() -> execRead(docker, handle,
                        "test -f /data/created-after-snapshot && echo present || echo absent")))
                    .as("step 5: a file created after the capture does not survive restore")
                    .isEqualTo("absent");
                assertThat(service.liveStatus(id).state())
                    .as("step 5: the instance runs again after restore")
                    .isEqualTo(ContainerState.RUNNING);

                // 6. The protected status gates power actions: a capturing instance
                //    refuses deploy AND stop with the named violation.
                Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
                    .assign(InstanceModel.STATUS, InstanceModel.STATUS_CAPTURING).updateAll();
                Throwable busy = catchThrowable(() -> service.deploy(id));
                assertThat(busy).as("step 6: deploy refuses while capturing")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all()).anySatisfy(violation ->
                            assertThat(violation.message().key()).isEqualTo("instance_busy")));
                Throwable stopBusy = catchThrowable(() -> service.stop(id));
                assertThat(stopBusy).as("step 6: stop refuses while capturing")
                    .isInstanceOf(Violations.class);
                Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
                    .assign(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING).updateAll();

                snapshots.delete(snapshotId);
                assertThat(Files.exists(payload))
                    .as("step 7: deleting the snapshot removes its payload files").isFalse();
            } finally {
                try {
                    service.destroy(id);
                } catch (RuntimeException ignored) {
                    // cleanup below
                }
                cleanup(docker, handle, handle + "-vol-data");
            }
        });
    }

    /**
     * The backup gate: export to a configured target through the REAL resolution
     * chain, then restore to a genuinely NEW instance -- new id, its own port claim,
     * its own quota reservation, config and secret variables intact, data intact.
     */
    @Test
    void backupExportsAndRestoresToANewInstance() throws IOException {
        DockerClient docker = new DockerClient();
        assumeLiveDaemon(docker);

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            Path targetDir = workRoot.resolve("target");

            // A configured filesystem target (REAL target semantics, SIMULATED
            // failure domain: same machine).
            Row targetRow = Models.get(BackupTargetModel.class).createEmptyRow();
            targetRow.set(BackupTargetModel.NAME, "test-target");
            targetRow.set(BackupTargetModel.KIND, "hohenheim:filesystem");
            targetRow.set(BackupTargetModel.SETTINGS, Map.of("path", targetDir.toString()));
            Models.get(BackupTargetModel.class).save(targetRow);

            int id = instanceRecord("backup-source", alpineSettings());
            Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
                .assign(InstanceModel.BACKUP_TARGET_ID,
                    (Integer) targetRow.get(BackupTargetModel.ID))
                .updateAll();
            String handle = "hohenheim-instance-" + id;
            InstanceService service = new InstanceService();
            InstanceBackups backups = new InstanceBackups();
            String operatorBucket = HohenheimAccess.packSubjects(Set.of());
            Integer newId = null;
            String newHandle = null;
            try {
                // 1. Deploy + marker.
                service.deploy(id);
                run(() -> execRun(docker, handle, "echo precious > /data/marker"));

                // 2. Back up NOW through the configured target.
                int backupId = backups.backupNow(id);
                Row backup = Models.get(InstanceBackupModel.class).findById(backupId);
                assertThat((String) backup.get(InstanceBackupModel.STATUS))
                    .as("step 2: backup row is complete")
                    .isEqualTo(InstanceBackupModel.STATUS_COMPLETE);
                String key = backup.get(InstanceBackupModel.REMOTE_KEY);
                Path artifact = targetDir.resolve(key);
                assertThat(Files.isRegularFile(artifact))
                    .as("step 2: the committed artifact exists at the target").isTrue();
                assertThat(Files.exists(Path.of(artifact + ".part")))
                    .as("step 2: no staging debris at the target").isFalse();
                assertThat(service.liveStatus(id).state())
                    .as("step 2: the source instance runs again after the capture")
                    .isEqualTo(ContainerState.RUNNING);

                // 3. Restore to a NEW instance.
                long quotaBefore = InstanceQuota.usedBy(operatorBucket);
                newId = backups.restoreToNew(backupId, "backup-clone", null);
                newHandle = "hohenheim-instance-" + newId;
                assertThat(newId).as("step 3: the new instance has its own id")
                    .isNotEqualTo(id);
                Row restored = Models.get(InstanceModel.class).findById(newId);
                assertThat((String) restored.get(InstanceModel.STATUS))
                    .as("step 3: the new instance is running")
                    .isEqualTo(InstanceModel.STATUS_RUNNING);

                // 4. Its QUOTA reservation is its own (the restore path did not
                //    bypass the ledger).
                assertThat(InstanceQuota.usedBy(operatorBucket))
                    .as("step 4: restore-to-new consumed one quota slot")
                    .isEqualTo(quotaBefore + 1);

                // 5. Its PORT claim is its own.
                assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, newId))
                    .as("step 5: the new instance holds its own port claim").hasSize(1);

                // 6. Config and secret variables came through the encrypted manifest.
                Object settings = restored.get(InstanceModel.SETTINGS);
                assertThat(settings).isInstanceOfSatisfying(Map.class, map -> {
                    assertThat(map.get("image")).as("step 6: image config intact")
                        .isEqualTo("alpine");
                    assertThat(map.get("environment_variables"))
                        .as("step 6: secret variables intact")
                        .isInstanceOfSatisfying(Map.class, env ->
                            assertThat(env.get("GAME_TOKEN")).isEqualTo("s3cret-token"));
                });

                // 7. And the DATA is intact in the NEW instance's OWN volume.
                String finalNewHandle = newHandle;
                assertThat(run(() -> execRead(docker, finalNewHandle, "cat /data/marker")))
                    .as("step 7: the volume data restored into the new instance")
                    .isEqualTo("precious");
                assertThat(run(() -> (Object) docker.inspectVolume(finalNewHandle + "-vol-data")))
                    .as("step 7: the new instance has its own volume").isNotNull();

                // 8. CORRUPTION GATE: flip one byte of the committed artifact; restore
                //    must refuse BEFORE creating anything.
                byte[] bytes = run(() -> Files.readAllBytes(artifact));
                bytes[bytes.length - 40] ^= 0x01;
                run(() -> { Files.write(artifact, bytes); return null; });
                long instancesBefore = Models.get(InstanceModel.class).find().count();
                long quotaBeforeCorrupt = InstanceQuota.usedBy(operatorBucket);
                long containersBefore = countContainers(docker);
                Throwable refusal = catchThrowable(() ->
                    backups.restoreToNew(backupId, "never-born", null));
                assertThat(refusal).as("step 8: corrupted backup is a named refusal")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all()).anySatisfy(violation ->
                            assertThat(violation.message().key()).isEqualTo("backup_corrupt")));
                assertThat(Models.get(InstanceModel.class).find().count())
                    .as("step 8: no instance record was created").isEqualTo(instancesBefore);
                assertThat(InstanceQuota.usedBy(operatorBucket))
                    .as("step 8: no quota was spent").isEqualTo(quotaBeforeCorrupt);
                assertThat(countContainers(docker))
                    .as("step 8: no container appeared at the daemon").isEqualTo(containersBefore);
            } finally {
                destroyQuietly(service, id);
                cleanup(docker, handle, handle + "-vol-data");
                if (newId != null) {
                    destroyQuietly(service, newId);
                    cleanup(docker, newHandle, newHandle + "-vol-data");
                }
            }
        });
    }

    /**
     * The interrupted-upload gate: a store that dies mid-stream must leave NO row and
     * NO artifact a later restore would accept.
     */
    @Test
    void interruptedUploadLeavesNoValidLookingBackup() throws IOException {
        DockerClient docker = new DockerClient();
        assumeLiveDaemon(docker);

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            Path targetDir = workRoot.resolve("interrupted-target");
            FilesystemBackupTarget real = new FilesystemBackupTarget(targetDir);
            AtomicReference<String> attemptedKey = new AtomicReference<>();
            BackupTarget failing = new BackupTarget() {
                @Override public void healthCheck() throws IOException {
                    real.healthCheck();
                }
                @Override public void store(String key, Path file) throws IOException {
                    attemptedKey.set(key);
                    // Simulate a network death mid-upload: the staging name gets
                    // half the bytes, then the transfer dies.
                    Path committed = targetDir.resolve(key);
                    Files.createDirectories(committed.getParent());
                    byte[] bytes = Files.readAllBytes(file);
                    Files.write(Path.of(committed + ".part"),
                        java.util.Arrays.copyOf(bytes, bytes.length / 2));
                    throw new IOException("simulated network failure mid-upload");
                }
                @Override public String storedSha256(String key) throws IOException {
                    return real.storedSha256(key);
                }
                @Override public void retrieve(String key, Path destination) throws IOException {
                    real.retrieve(key, destination);
                }
                @Override public boolean exists(String key) throws IOException {
                    return real.exists(key);
                }
                @Override public void delete(String key) throws IOException {
                    real.delete(key);
                }
            };

            int id = instanceRecord("interrupted-backup", alpineSettings());
            String handle = "hohenheim-instance-" + id;
            InstanceService service = new InstanceService();
            InstanceBackups backups = new InstanceBackups();
            try {
                // 1. Deploy, then attempt the backup through the dying target.
                service.deploy(id);
                Throwable failure = catchThrowable(() -> backups.backupNow(id, null, failing));
                assertThat(failure).as("step 1: the interrupted upload is a named failure")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all()).anySatisfy(violation ->
                            assertThat(violation.message().key())
                                .isEqualTo("instance_backup_failed")));

                // 2. NO row a restore would accept: the only row is FAILED.
                List<Row> rows = Models.get(InstanceBackupModel.class).find()
                    .where(InstanceBackupModel.INSTANCE_ID.eq(id)).all();
                assertThat(rows).as("step 2: exactly one evidence row").hasSize(1);
                assertThat((String) rows.get(0).get(InstanceBackupModel.STATUS))
                    .as("step 2: and it is FAILED, not complete")
                    .isEqualTo(InstanceBackupModel.STATUS_FAILED);

                // 3. NO artifact at the target: neither committed nor staging debris.
                String key = attemptedKey.get();
                assertThat(key).as("step 3: the upload was attempted").isNotNull();
                assertThat(Files.exists(targetDir.resolve(key)))
                    .as("step 3: no committed artifact").isFalse();
                assertThat(Files.exists(Path.of(targetDir.resolve(key) + ".part")))
                    .as("step 3: the partial staging file was removed").isFalse();

                // 4. A restore of the failed row REFUSES.
                Throwable refusal = catchThrowable(() -> backups.restoreToNew(
                    rows.get(0), real, "never-born", null));
                assertThat(refusal).as("step 4: the failed row is not restorable")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all()).anySatisfy(violation ->
                            assertThat(violation.message().key())
                                .isEqualTo("backup_not_restorable")));

                // 5. The source instance is back up (the capture downtime ended).
                assertThat(service.liveStatus(id).state())
                    .as("step 5: the source instance runs again")
                    .isEqualTo(ContainerState.RUNNING);
            } finally {
                destroyQuietly(service, id);
                cleanup(docker, handle, handle + "-vol-data");
            }
        });
    }

    // -- plumbing -------------------------------------------------------------

    private interface ThrowingSupplier<T> {
        T get() throws IOException;
    }

    private interface ThrowingRunnable {
        void run() throws IOException;
    }

    private static <T> T run(ThrowingSupplier<T> body) {
        try {
            return body.get();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void run(ThrowingRunnable body) {
        try {
            body.run();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static long countContainers(DockerClient docker) {
        return run(() -> (long) docker.listContainers(true).size());
    }

    private static void destroyQuietly(InstanceService service, int id) {
        try {
            service.destroy(id);
        } catch (RuntimeException ignored) {
            // container cleanup below is the fallback
        }
    }

    private static void cleanup(DockerClient docker, String container, String volume) {
        try {
            docker.removeContainer(container, true);
        } catch (IOException ignored) {
            // already gone
        }
        try {
            docker.removeNetwork(InstanceNetworks.networkName(container));
        } catch (IOException ignored) {
            // never created
        }
        if (volume != null) {
            try {
                docker.removeVolume(volume, true);
            } catch (IOException ignored) {
                // already gone
            }
        }
    }

    private static boolean imagePresent(DockerClient docker, String tag) throws IOException {
        for (Object image : docker.listImages()) {
            Object repoTags = ((Map<?, ?>) image).get("RepoTags");
            if (repoTags instanceof List<?> tags && tags.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
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
}
