package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.backup.BackupTarget;
import be.elevenways.hohenheim.server.backup.FilesystemBackupTarget;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceSnapshots;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.schedule.InstanceSnapshotAction;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.task.record.RecordSchedules;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The Phase 4 gate's snapshot/backup half ON THE INCUS DRIVER, against the real remote
 * daemon: a DEBIAN system container with a nightly snapshot schedule executed through
 * the real chain runner, a native snapshot-restore round trip (replace, not merge,
 * proven with in-container data over the host's own CLI), an off-host backup exported
 * and restored to a genuinely NEW instance with manifest/config/data intact, a
 * corrupted artifact refused before anything exists, and an interrupted upload that
 * leaves nothing a restore would accept.
 *
 * OFF-HOST is genuine here without a third machine: the instance runs on the REMOTE
 * Incus host and the filesystem target lives on this controller -- a directory on a
 * separate control-plane host, the plan's stated floor for a different failure domain
 * (unlike the Docker suite, whose instances share this machine and whose off-host
 * clause needs LiveOffHostBackupTest).
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class IncusSnapshotBackupLiveTest {

    private static final String HOST = "live-incus-snap";

    /** The gate names DEBIAN; the daemon caches the image after the first pull. */
    private static final String IMAGE = "debian/13";

    private static SqliteDatasource datasource;
    private static LiveIncusHost remote;
    private static String enrolledFingerprint;
    private static Path workRoot;
    private static String previousSnapshotPath;
    private static String previousStagingPath;

    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveIncusHost.configured();
        LiveLane.require(LiveLane.Need.INCUS_HOST, remote != null,
            "no live incus host enrolled at " + LiveIncusHost.CONFIG);

        File db = File.createTempFile("hohenheim-incus-snapshot-live", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();

        workRoot = Files.createTempDirectory("hohenheim-incus-backup-test");
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

        Db.run(datasource, () -> enrolledFingerprint =
            remote.enrollThroughProduct(HOST, "hohenheim-live-snapshot"));
    }

    @AfterAll
    static void tearDown() {
        if (remote != null) {
            System.out.println("=== cleanup: shared objects -> "
                + remote.releaseControllerSharedObjects());
            System.out.println("=== cleanup: authorized_keys -> "
                + remote.releaseAuthorizedKeys());
        }
        if (remote != null && enrolledFingerprint != null) {
            try {
                remote.removeTrustEntry(enrolledFingerprint);
            } catch (IOException ignored) {
                // nothing enrolled, nothing to remove
            }
        }
        FieldEncryption.installKeyring(null);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.SNAPSHOT_PATH,
            previousSnapshotPath);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.STAGING_PATH,
            previousStagingPath);
        deleteRecursively(workRoot);
    }

    private static int instanceRecord(String name) {
        Row host = Models.get(ServerModel.class).findByName(HOST);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", IMAGE);
        settings.put("memory_limit_mb", 256);
        settings.put("environment_variables", Map.of("GAME_TOKEN", "s3cret-token"));
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:incus_container");
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.SERVER_ID, host.get(ServerModel.ID));
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    @Test
    void nightlyScheduleSnapshotRestoreOffHostBackupAndBothRefusalsOnIncus() {
        Db.run(datasource, () -> {
            int id = instanceRecord("incus-gate");
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
            InstanceService service = new InstanceService();
            InstanceSnapshots snapshots = new InstanceSnapshots();
            InstanceBackups backups = new InstanceBackups();
            Integer newId = null;
            String newHandle = null;
            try {
                // 1. A DEBIAN system container through the product funnel, proven at
                //    the daemon over the host's own CLI, and writable inside.
                service.deploy(id);
                assertThat(infoOf(handle))
                    .as("step 1: the host's own CLI sees the Debian container running")
                    .contains("RUNNING");
                exec(handle, "echo v1 > /root/marker");
                assertThat(exec(handle, "cat /root/marker"))
                    .as("step 1: in-container data is writable and readable").isEqualTo("v1");

                // 2. THE NIGHTLY SNAPSHOT SCHEDULE: a real per-record cron schedule
                //    with the snapshot action, executed through the real chain runner
                //    (runNow is the sweeper's own execution lane).
                Row schedule = Models.get(RecordScheduleModel.class).createEmptyRow();
                schedule.set(RecordScheduleModel.MODEL, InstanceModel.MODEL_ID.toString());
                schedule.set(RecordScheduleModel.RECORD_ID, String.valueOf(id));
                schedule.set(RecordScheduleModel.NAME, "Nightly snapshot");
                schedule.set(RecordScheduleModel.CRON, "0 3 * * *");
                schedule.set(RecordScheduleModel.ENABLED, true);
                Models.get(RecordScheduleModel.class).save(schedule);
                int scheduleId = schedule.get(RecordScheduleModel.ID);
                Row step = Models.get(RecordScheduleStepModel.class).createEmptyRow();
                step.set(RecordScheduleStepModel.SCHEDULE_ID, scheduleId);
                step.set(RecordScheduleStepModel.POSITION, 1);
                step.set(RecordScheduleStepModel.ACTION, InstanceSnapshotAction.ID.toString());
                step.set(RecordScheduleStepModel.OFFSET_SECONDS, 0);
                step.set(RecordScheduleStepModel.FAILURE_POLICY,
                    RecordScheduleStepModel.POLICY_ABORT);
                step.set(RecordScheduleStepModel.PAYLOAD, Map.of("note", "nightly"));
                Models.get(RecordScheduleStepModel.class).save(step);

                Row run = new RecordSchedules(datasource).runNow(scheduleId);
                assertThat(run.get(RecordScheduleRunModel.STATUS))
                    .as("step 2: the nightly chain completed")
                    .isEqualTo(RecordScheduleRunModel.STATUS_COMPLETED);
                Row snapshot = Models.get(InstanceSnapshotModel.class).find()
                    .where(InstanceSnapshotModel.INSTANCE_ID.eq(id)).first();
                assertThat(snapshot).as("step 2: the schedule produced a snapshot row")
                    .isNotNull();
                assertThat((String) snapshot.get(InstanceSnapshotModel.STATUS))
                    .as("step 2: and it is complete")
                    .isEqualTo(InstanceSnapshotModel.STATUS_COMPLETE);
                String nativeName = snapshot.get(InstanceSnapshotModel.NATIVE_NAME);
                assertThat(nativeName).as("step 2: the native lane recorded the daemon name")
                    .isNotBlank();
                assertThat(query("/1.0/instances/" + handle + "/snapshots"))
                    .as("step 2: the DAEMON holds the snapshot in the instance's pool")
                    .contains(nativeName);
                assertThat(service.liveStatus(id).state())
                    .as("step 2: the capture was LIVE -- the workload never stopped")
                    .isEqualTo(ContainerState.RUNNING);

                // 3. Mutate: change the marker AND add a new file.
                exec(handle, "echo v2 > /root/marker");
                exec(handle, "echo after > /root/created-after-snapshot");

                // 4. Restore: the marker is back to v1, the post-snapshot file is
                //    GONE (a rootfs REPLACE, not a merge) -- and because the redeploy
                //    after restore runs the ordinary funnel, this also proves deploy
                //    CONVERGES instead of recreating from the image (a recreate would
                //    wipe the marker this step asserts).
                int snapshotId = snapshot.get(InstanceSnapshotModel.ID);
                snapshots.restore(snapshotId);
                assertThat(exec(handle, "cat /root/marker"))
                    .as("step 4: restore brought the captured marker back").isEqualTo("v1");
                assertThat(exec(handle,
                        "test -f /root/created-after-snapshot && echo present || echo absent"))
                    .as("step 4: a file created after the capture does not survive restore")
                    .isEqualTo("absent");
                assertThat(service.liveStatus(id).state())
                    .as("step 4: the instance runs again after restore")
                    .isEqualTo(ContainerState.RUNNING);

                // 5. The protected status gates power actions on this driver too.
                Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
                    .assign(InstanceModel.STATUS, InstanceModel.STATUS_CAPTURING).updateAll();
                assertThat(catchThrowable(() -> service.deploy(id)))
                    .as("step 5: deploy refuses while capturing")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all()).anySatisfy(violation ->
                            assertThat(violation.message().key()).isEqualTo("instance_busy")));
                Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
                    .assign(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING).updateAll();

                // 6. OFF-HOST BACKUP: the instance lives on the remote incus host; the
                //    configured filesystem target lives on THIS controller -- a
                //    different failure domain by construction.
                Path targetDir = workRoot.resolve("target");
                Row targetRow = Models.get(BackupTargetModel.class).createEmptyRow();
                targetRow.set(BackupTargetModel.NAME, "incus-test-target");
                targetRow.set(BackupTargetModel.KIND, "hohenheim:filesystem");
                targetRow.set(BackupTargetModel.SETTINGS, Map.of("path", targetDir.toString()));
                Models.get(BackupTargetModel.class).save(targetRow);
                Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
                    .assign(InstanceModel.BACKUP_TARGET_ID,
                        (Integer) targetRow.get(BackupTargetModel.ID))
                    .updateAll();

                int backupId = backups.backupNow(id);
                Row backup = Models.get(InstanceBackupModel.class).findById(backupId);
                assertThat((String) backup.get(InstanceBackupModel.STATUS))
                    .as("step 6: backup row is complete")
                    .isEqualTo(InstanceBackupModel.STATUS_COMPLETE);
                String key = backup.get(InstanceBackupModel.REMOTE_KEY);
                Path artifact = targetDir.resolve(key);
                assertThat(Files.isRegularFile(artifact))
                    .as("step 6: the committed artifact exists at the controller-side target")
                    .isTrue();
                assertThat(backup.get(InstanceBackupModel.SUMMARY))
                    .as("step 6: the manifest summary declares the payload kind")
                    .isInstanceOfSatisfying(Map.class, summary ->
                        assertThat(summary.get("payload")).isEqualTo("instance_export"));
                assertThat(query("/1.0/instances/" + handle + "/backups"))
                    .as("step 6: no temporary backup object was left at the daemon")
                    .isEqualTo("[]");
                assertThat(service.liveStatus(id).state())
                    .as("step 6: the source instance kept running (live capture)")
                    .isEqualTo(ContainerState.RUNNING);

                // 7. RESTORE TO A GENUINELY NEW INSTANCE: own id, own quota slot, the
                //    manifest's config and secret variable intact, the data intact,
                //    and -- the attribution half -- the daemon attributes the imported
                //    instance to the NEW record, not the source it was exported from.
                String operatorBucket = HohenheimAccess.packSubjects(Set.of());
                long quotaBefore = InstanceQuota.usedBy(operatorBucket);
                newId = backups.restoreToNew(backupId, "incus-clone", null);
                newHandle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, newId);
                assertThat(newId).as("step 7: the new instance has its own id")
                    .isNotEqualTo(id);
                Row restored = Models.get(InstanceModel.class).findById(newId);
                assertThat((String) restored.get(InstanceModel.STATUS))
                    .as("step 7: the new instance is running")
                    .isEqualTo(InstanceModel.STATUS_RUNNING);
                assertThat(InstanceQuota.usedBy(operatorBucket))
                    .as("step 7: restore-to-new consumed one quota slot")
                    .isEqualTo(quotaBefore + 1);
                assertThat(restored.get(InstanceModel.SETTINGS))
                    .as("step 7: config and secret variables came through the manifest")
                    .isInstanceOfSatisfying(Map.class, map -> {
                        assertThat(map.get("image")).isEqualTo(IMAGE);
                        assertThat(map.get("environment_variables"))
                            .isInstanceOfSatisfying(Map.class, env ->
                                assertThat(env.get("GAME_TOKEN")).isEqualTo("s3cret-token"));
                    });
                assertThat(exec(newHandle, "cat /root/marker"))
                    .as("step 7: the rootfs data restored into the new instance")
                    .isEqualTo("v1");
                String newLabels = query("/1.0/instances/" + newHandle);
                Map<String, String> expected = OwnerLabels.of(InstanceModel.MODEL_ID, newId);
                expected.forEach((label, value) ->
                    assertThat(newLabels)
                        .as("step 7: the daemon attributes the import to the NEW record"
                            + " (label %s)", label)
                        .contains("\"user." + label + "\": \"" + value + "\""));

                // 8. CORRUPTION GATE: flip one byte of the committed artifact; restore
                //    must refuse by name BEFORE anything exists -- no record, no quota
                //    slot, no instance at the daemon. Step 7 is this refusal's
                //    positive anchor: the same artifact restored fine before the flip.
                byte[] bytes = read(artifact);
                bytes[bytes.length - 40] ^= 0x01;
                write(artifact, bytes);
                long instancesBefore = Models.get(InstanceModel.class).find().count();
                long quotaBeforeCorrupt = InstanceQuota.usedBy(operatorBucket);
                Set<String> daemonBefore = daemonInstanceHandles();
                assertThat(catchThrowable(() ->
                        backups.restoreToNew(backupId, "never-born", null)))
                    .as("step 8: corrupted backup is a named refusal")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all()).anySatisfy(violation ->
                            assertThat(violation.message().key()).isEqualTo("backup_corrupt")));
                assertThat(Models.get(InstanceModel.class).find().count())
                    .as("step 8: no instance record was created").isEqualTo(instancesBefore);
                assertThat(InstanceQuota.usedBy(operatorBucket))
                    .as("step 8: no quota was spent").isEqualTo(quotaBeforeCorrupt);
                Set<String> appeared = daemonInstanceHandles();
                appeared.removeAll(daemonBefore);
                assertThat(appeared)
                    .as("step 8: no instance appeared at the daemon for the refused"
                        + " restore (appeared: %s)", appeared)
                    .noneMatch(IncusSnapshotBackupLiveTest::isOwnHandle);

                // 9. INTERRUPTED UPLOAD: a store that dies mid-stream leaves a FAILED
                //    row, no artifact, no staging debris, and the failed row refuses
                //    restore -- while the source instance keeps running.
                Path interruptedDir = workRoot.resolve("interrupted-target");
                FilesystemBackupTarget real = new FilesystemBackupTarget(interruptedDir);
                AtomicReference<String> attemptedKey = new AtomicReference<>();
                BackupTarget failing = new BackupTarget() {
                    @Override public void healthCheck() throws IOException {
                        real.healthCheck();
                    }
                    @Override public void store(String storeKey, Path file) throws IOException {
                        attemptedKey.set(storeKey);
                        Path committed = interruptedDir.resolve(storeKey);
                        Files.createDirectories(committed.getParent());
                        byte[] half = read(file);
                        Files.write(Path.of(committed + ".part"),
                            java.util.Arrays.copyOf(half, half.length / 2));
                        throw new IOException("simulated network failure mid-upload");
                    }
                    @Override public String storedSha256(String storeKey) throws IOException {
                        return real.storedSha256(storeKey);
                    }
                    @Override public void retrieve(String storeKey, Path destination)
                            throws IOException {
                        real.retrieve(storeKey, destination);
                    }
                    @Override public java.util.List<String> list(String prefix)
                            throws IOException {
                        return real.list(prefix);
                    }
                    @Override public boolean exists(String storeKey) throws IOException {
                        return real.exists(storeKey);
                    }
                    @Override public void delete(String storeKey) throws IOException {
                        real.delete(storeKey);
                    }
                };
                assertThat(catchThrowable(() -> backups.backupNow(id, null, failing)))
                    .as("step 9: the interrupted upload is a named failure")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all()).anySatisfy(violation ->
                            assertThat(violation.message().key())
                                .isEqualTo("instance_backup_failed")));
                List<Row> failed = Models.get(InstanceBackupModel.class).find()
                    .where(InstanceBackupModel.INSTANCE_ID.eq(id))
                    .where(InstanceBackupModel.STATUS.eq(InstanceBackupModel.STATUS_FAILED))
                    .all();
                assertThat(failed).as("step 9: exactly one FAILED evidence row").hasSize(1);
                String attempted = attemptedKey.get();
                assertThat(attempted).as("step 9: the upload was attempted").isNotNull();
                assertThat(Files.exists(interruptedDir.resolve(attempted)))
                    .as("step 9: no committed artifact").isFalse();
                assertThat(Files.exists(Path.of(interruptedDir.resolve(attempted) + ".part")))
                    .as("step 9: the partial staging file was removed").isFalse();
                assertThat(catchThrowable(() -> backups.restoreToNew(
                        failed.get(0), real, "never-born", null)))
                    .as("step 9: the failed row is not restorable")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all()).anySatisfy(violation ->
                            assertThat(violation.message().key())
                                .isEqualTo("backup_not_restorable")));
                assertThat(service.liveStatus(id).state())
                    .as("step 9: the source instance still runs")
                    .isEqualTo(ContainerState.RUNNING);

                // 10. Deleting the snapshot removes it AT THE DAEMON, and its row.
                snapshots.delete(snapshotId);
                assertThat(query("/1.0/instances/" + handle + "/snapshots"))
                    .as("step 10: the daemon-side snapshot is gone").isEqualTo("[]");
                assertThat(Models.get(InstanceSnapshotModel.class).findById(snapshotId))
                    .as("step 10: and so is the row").isNull();
            } finally {
                destroyQuietly(service, id);
                cleanupAtDaemon(handle);
                if (newId != null) {
                    destroyQuietly(service, newId);
                    cleanupAtDaemon(newHandle);
                }
            }
        });
    }

    // -- plumbing -------------------------------------------------------------

    private static String exec(String handle, String command) {
        try {
            return remote.exec(handle, command);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String query(String path) {
        try {
            return remote.query(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String infoOf(String handle) {
        try {
            return remote.instanceInfoOrError(handle);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Every instance handle the daemon currently carries, ours and other classes'. */
    private static Set<String> daemonInstanceHandles() {
        Set<String> handles = new TreeSet<>();
        Matcher matcher = Pattern.compile("/1\\.0/instances/([^\"?]+)")
            .matcher(query("/1.0/instances"));
        while (matcher.find()) {
            handles.add(matcher.group(1));
        }
        return handles;
    }

    /**
     * Whether a daemon handle names an id THIS class could have allocated.
     *
     * AIDEV-NOTE: this replaced a whole-daemon instance COUNT, which was wrong in two
     * ways at once. daystrom is shared by every live Incus class and browserTestIsolated
     * runs them in parallel forks, so a sibling class destroying its own workload inside
     * the window dropped the count and failed this step for a reason it says nothing
     * about (observed 2026-08-05: expected 6, got 5, while IncusCommunityAppLiveTest
     * destroyed instance 4543113 mid-window). Worse in the other direction: a count is
     * blind by construction -- one of OUR instances appearing while one of theirs
     * vanished leaves the count equal and the step reports success, which is exactly the
     * failure this gate exists to catch. Every handle carries this controller's identity
     * token, so judging only handles minted by THIS class's database is both race-free
     * and exact -- no id-proximity heuristic is involved any more.
     * IncusCommunityAppLiveTest's own-handles sweep is the same shape.
     */
    private static boolean isOwnHandle(String daemonHandle) {
        ControllerScope.Scoped scoped = ControllerScope.parse(daemonHandle);
        return scoped != null && scoped.isOurs()
            && ControllerScope.KIND_INSTANCE.equals(scoped.kind());
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void write(Path file, byte[] bytes) {
        try {
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void destroyQuietly(InstanceService service, int id) {
        try {
            service.destroy(id);
        } catch (RuntimeException ignored) {
            // daemon cleanup below is the fallback
        }
    }

    private static void cleanupAtDaemon(String handle) {
        remote.forceDelete(handle);
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
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
}
