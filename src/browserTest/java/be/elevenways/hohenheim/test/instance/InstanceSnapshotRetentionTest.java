package be.elevenways.hohenheim.test.instance;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceSnapshots;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.server.HohenheimSystemPurposes;
import be.elevenways.hohenheim.test.RecordedChanges;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.test.support.HistoryTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Snapshot retention and the snapshot record's write discipline, as daemon-free
 * journeys over the shared fake native daemons: completed captures beyond the
 * configured count are pruned from the row AND from the daemon, the newest survive, a
 * retention of 0 prunes nothing, and a completing capture never rewinds a row someone
 * else wrote while it ran.
 *
 * AIDEV-NOTE: this is also the first daemon-free coverage of {@code InstanceSnapshots}
 * at all -- every other snapshot test needs a live Incus or Docker daemon.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class InstanceSnapshotRetentionTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.standalone();

    private static SqlDatasource datasource;
    private static int hostId;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = APP.database();
        // The volume lane writes REAL payloads, so the snapshot root has to be a place
        // this test owns rather than the developer's configured one.
        Path root = Files.createTempDirectory("hohenheim-snapshot-retention-root");
        root.toFile().deleteOnExit();
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.SNAPSHOT_PATH,
            root.toAbsolutePath().toString());
        FakeNativeDaemons.register();
        Db.run(datasource, () -> hostId = HostFixtures.admittedIncusHost("snap-retention-host"));
    }

    @Test
    void retentionKeepsTheNewestCapturesAndRemovesTheOlderOnesFromTheDaemonToo() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceSnapshots snapshots = new InstanceSnapshots();
            int instanceId = instanceRecord("snap-retention", hostId);
            service.deploy(instanceId);
            Map<String, FakeNativeDaemons.FakeWorkload> daemon =
                FakeNativeDaemons.daemonOf(hostId);
            String handle = FakeNativeDaemons.handleOf(instanceId);

            // 1. Retention of 3, and FIVE captures: the daemon holds every one of them
            //    while they are being taken, so the prune has something to remove.
            Zenit.SETTINGS_VALUES.setValue(
                HohenheimSettings.Backup.SNAPSHOT_RETENTION, 3);
            List<Integer> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                int id = snapshots.create(instanceId, "capture " + i);
                ids.add(id);
                names.add(Models.get(InstanceSnapshotModel.class).findById(id)
                    .get(InstanceSnapshotModel.NATIVE_NAME));
            }

            // 2. The POSITIVE anchor first: the newest three survive as rows AND as
            //    payloads on the daemon. A sweep that deleted everything would pass a
            //    "the old ones are gone" assertion on its own.
            for (int i = 2; i < 5; i++) {
                assertThat(Models.get(InstanceSnapshotModel.class).findById(ids.get(i)))
                    .as("step 2: capture %s is inside the retention window and must survive",
                        i + 1)
                    .isNotNull();
                assertThat(daemon.get(handle).snapshots)
                    .as("step 2: and its payload is still on the daemon")
                    .contains(names.get(i));
            }

            // 3. The two oldest are gone from BOTH -- a row deleted while the pool still
            //    holds the payload would be the silent-success shape this prune must not
            //    have. Scoped to THIS instance's own handle, never a daemon-wide count.
            for (int i = 0; i < 2; i++) {
                assertThat(Models.get(InstanceSnapshotModel.class).findById(ids.get(i)))
                    .as("step 3: capture %s fell outside the window and its row is gone",
                        i + 1)
                    .isNull();
                assertThat(daemon.get(handle).snapshots)
                    .as("step 3: and its payload is gone from the daemon, not merely delisted")
                    .doesNotContain(names.get(i));
            }
            assertThat(Models.get(InstanceSnapshotModel.class).find()
                    .where(InstanceSnapshotModel.INSTANCE_ID.eq(instanceId)).count())
                .as("step 3: exactly the retention count remains").isEqualTo(3);

            // 4. Retention OFF keeps everything: 0 must mean "keep", never "keep none".
            Zenit.SETTINGS_VALUES.setValue(
                HohenheimSettings.Backup.SNAPSHOT_RETENTION, 0);
            snapshots.create(instanceId, "capture 6");
            snapshots.create(instanceId, "capture 7");
            assertThat(Models.get(InstanceSnapshotModel.class).find()
                    .where(InstanceSnapshotModel.INSTANCE_ID.eq(instanceId)).count())
                .as("step 4: a retention of 0 prunes nothing").isEqualTo(5);

            Zenit.SETTINGS_VALUES.setValue(
                HohenheimSettings.Backup.SNAPSHOT_RETENTION, 7);
            service.destroy(instanceId);
        });
    }

    /**
     * The snapshot record's WRITE DISCIPLINE: a capture may never carry the rest of a
     * stale snapshot Row back to the database.
     *
     * The row is created BEFORE the capture and completed after it, and the note is
     * editable through {@code InstanceSnapshotResource} for that whole window -- so the
     * completion's {@code Models.save} used to rewind an operator's edit while reporting
     * the capture complete. This walks a row through exactly that interleaving and
     * asserts the PERSISTED values.
     */
    @Test
    void aCaptureNeverCarriesAStaleSnapshotRowBackToTheDatabase() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceSnapshots snapshots = new InstanceSnapshots();
            int instanceId = instanceRecord("snap-stale-row", hostId);
            service.deploy(instanceId);
            Zenit.SETTINGS_VALUES.setValue(
                HohenheimSettings.Backup.SNAPSHOT_RETENTION, 0);

            // 1. The operator renames the capture WHILE the daemon is taking it -- the
            //    admin form is open on the row for the whole window.
            int[] edited = new int[1];
            FakeNativeDaemons.DURING_SNAPSHOT.set(() -> {
                Row inFlight = Models.get(InstanceSnapshotModel.class).find()
                    .where(InstanceSnapshotModel.INSTANCE_ID.eq(instanceId))
                    .orderBy(InstanceSnapshotModel.ID, SortOrder.DESC)
                    .first();
                edited[0] = inFlight.get(InstanceSnapshotModel.ID);
                Models.get(InstanceSnapshotModel.class).find()
                    .where(InstanceSnapshotModel.ID.eq(edited[0]))
                    .assign(InstanceSnapshotModel.NOTE, "renamed while capturing")
                    .updateAll();
            });
            int snapshotId = snapshots.create(instanceId, "before 1.20 upgrade");
            assertThat(edited[0])
                .as("step 1: the edit reached the very row the capture is completing")
                .isEqualTo(snapshotId);

            // 2. STATE, read back from the database. The POSITIVE half first: the capture
            //    really completed and its daemon-side name really landed, so this cannot
            //    pass by writing nothing. The note survived beside it -- before the fix
            //    the persisted note was the capture's own creation-time value.
            Row persisted = Models.get(InstanceSnapshotModel.class).findById(snapshotId);
            assertThat(Map.of(
                    "status", String.valueOf((Object) persisted.get(InstanceSnapshotModel.STATUS)),
                    "named", String.valueOf(persisted.get(InstanceSnapshotModel.NATIVE_NAME) != null),
                    "note", String.valueOf((Object) persisted.get(InstanceSnapshotModel.NOTE))))
                .as("step 2: the capture completed AND the concurrent edit survived it")
                .isEqualTo(Map.of(
                    "status", InstanceSnapshotModel.STATUS_COMPLETE,
                    "named", "true",
                    "note", "renamed while capturing"));

            Zenit.SETTINGS_VALUES.setValue(
                HohenheimSettings.Backup.SNAPSHOT_RETENTION, 7);
            service.destroy(instanceId);
        });
    }

    /**
     * A snapshot's status says what its capture is doing: capturing while it runs (never failed, which an operator
     * reads as a capture that broke), complete once it finished, and failed only when it really failed.
     */
    @Test
    void aSnapshotReadsCapturingWhileItRunsAndFailedOnlyOnARealFailure() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceSnapshots snapshots = new InstanceSnapshots();
            int instanceId = instanceRecord("snap-status", hostId);
            service.deploy(instanceId);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.SNAPSHOT_RETENTION, 0);
            try {
                // 1. While the daemon takes the capture, the capture's own row reads capturing.
                List<String> whileRunning = new ArrayList<>();
                FakeNativeDaemons.DURING_SNAPSHOT.set(() -> whileRunning.add(
                    newest(instanceId).get(InstanceSnapshotModel.STATUS)));
                int completed = snapshots.create(instanceId, "status journey");
                assertThat(whileRunning).as("step 1: the running capture reads capturing, not failed")
                    .containsExactly(InstanceSnapshotModel.STATUS_CAPTURING);

                // 2. Once it finished, complete, and restorable.
                Row done = Models.get(InstanceSnapshotModel.class).findById(completed);
                assertThat((String) done.get(InstanceSnapshotModel.STATUS)).as("step 2: the capture completed")
                    .isEqualTo(InstanceSnapshotModel.STATUS_COMPLETE);
                assertThat(InstanceSnapshotModel.isRestorable(done)).as("step 2: and can be restored").isTrue();

                // 3. A capture the daemon refuses ends failed, naming what failed it, and cannot be restored.
                FakeNativeDaemons.DURING_SNAPSHOT.set(() -> {
                    throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("snapshots_unsupported")
                        .withArg("kind", "fake"));
                });
                assertThat(catchThrowable(() -> snapshots.create(instanceId, "refused")))
                    .as("step 3: the capture fails").isInstanceOf(Violations.class);
                Row refused = newest(instanceId);
                assertThat((String) refused.get(InstanceSnapshotModel.STATUS))
                    .as("step 3: the failed capture reads failed").isEqualTo(InstanceSnapshotModel.STATUS_FAILED);
                assertThat((String) refused.get(InstanceSnapshotModel.ERROR)).as("step 3: naming what failed it")
                    .isNotBlank();
                assertThat(InstanceSnapshotModel.isRestorable(refused)).as("step 3: and it is not restorable")
                    .isFalse();
            } finally {
                FakeNativeDaemons.DURING_SNAPSHOT.set(null);
                Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.SNAPSHOT_RETENTION, 7);
            }
            service.destroy(instanceId);
        });
    }

    /** The instance's newest snapshot row: by id, which two captures inside one second never share. */
    private static Row newest(int instanceId) {
        return Models.get(InstanceSnapshotModel.class).find()
            .where(InstanceSnapshotModel.INSTANCE_ID.eq(instanceId))
            .orderBy(InstanceSnapshotModel.ID, SortOrder.DESC)
            .first();
    }

    /**
     * The VOLUME lane's retention, and the failure it used to swallow. Both lanes of one
     * mechanism must agree: a payload that could not be removed KEEPS its row, because a
     * row deleted while its tars are still on disk is a prune that reports success for
     * work it did not do.
     *
     * AIDEV-NOTE: the daemon-free retention journey above runs the NATIVE lane only, so
     * the volume lane's prune had never executed in CI at all -- which is how the two
     * lanes drifted into opposite failure semantics unnoticed.
     */
    @Test
    void theVolumeLaneKeepsTheRowOfAPayloadItCouldNotRemove() throws IOException {
        // A denied directory is the only honest way to make a real filesystem refuse, and
        // root is denied nothing -- so this is a DECLARED host need, reported when unmet
        // rather than skipped in silence.
        Path probe = Files.createTempDirectory("hohenheim-snapshot-perm-probe");
        Files.writeString(probe.resolve("child"), "x");
        Files.setPosixFilePermissions(probe, PosixFilePermissions.fromString("r-xr-xr-x"));
        boolean canBeRefused;
        try {
            Files.delete(probe.resolve("child"));
            canBeRefused = false;
        } catch (IOException refused) {
            canBeRefused = true;
        }
        Files.setPosixFilePermissions(probe, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.deleteIfExists(probe.resolve("child"));
        Files.deleteIfExists(probe);
        LiveLane.require(LiveLane.Need.UNPRIVILEGED_FS, canBeRefused,
            "this process can delete anything (running as root): a refused payload removal"
                + " cannot be produced here");

        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceSnapshots snapshots = new InstanceSnapshots();
            int instanceId = volumeInstanceRecord("snap-volume-retention", hostId);
            service.deploy(instanceId);

            // 1. Retention of 1 over the VOLUME lane: the capture writes real tars under
            //    the snapshot root, and the older capture's row AND payload both go.
            Zenit.SETTINGS_VALUES.setValue(
                HohenheimSettings.Backup.SNAPSHOT_RETENTION, 1);
            int first = snapshots.create(instanceId, "volume capture 1");
            Path firstDir = Path.of((String) Models.get(InstanceSnapshotModel.class)
                .findById(first).get(InstanceSnapshotModel.DIRECTORY));
            assertThat(Files.exists(firstDir.resolve("data.tar")))
                .as("step 1: the volume lane really wrote a payload to disk").isTrue();
            snapshots.create(instanceId, "volume capture 2");
            assertThat(Map.of(
                    "row", String.valueOf(Models.get(InstanceSnapshotModel.class)
                        .findById(first) != null),
                    "payload", String.valueOf(Files.exists(firstDir))))
                .as("step 1: the pruned capture lost BOTH its row and its payload")
                .isEqualTo(Map.of("row", "false", "payload", "false"));

            // 2. Now a payload the controller cannot remove: its parent directory refuses
            //    the unlink. The capture that follows still SUCCEEDS -- a stuck payload
            //    from an earlier snapshot may never fail the one being taken.
            int stuck = snapshots.create(instanceId, "volume capture 3");
            Path stuckDir = Path.of((String) Models.get(InstanceSnapshotModel.class)
                .findById(stuck).get(InstanceSnapshotModel.DIRECTORY));
            // Deny writes on the SNAPSHOT's own directory: its tar cannot be unlinked
            // and the directory cannot be removed while it still holds one, but sibling
            // captures can still be created beside it.
            denyWrites(stuckDir);
            int after = snapshots.create(instanceId, "volume capture 4");
            assertThat((String) Models.get(InstanceSnapshotModel.class).findById(after)
                    .get(InstanceSnapshotModel.STATUS))
                .as("step 2: the new capture completed despite the stuck older payload")
                .isEqualTo(InstanceSnapshotModel.STATUS_COMPLETE);

            // 3. THE POINT: the row survives, because its payload does. Deleting the row
            //    here would orphan the tars with nothing left pointing at them.
            assertThat(Map.of(
                    "row", String.valueOf(Models.get(InstanceSnapshotModel.class)
                        .findById(stuck) != null),
                    "payload", String.valueOf(Files.exists(stuckDir.resolve("data.tar")))))
                .as("step 3: a payload that could not be removed KEEPS its row, so a later"
                    + " sweep still knows what to clean up")
                .isEqualTo(Map.of("row", "true", "payload", "true"));

            // 4. Once the filesystem lets go, the very next capture prunes it for real --
            //    the row was kept for a retry, not kept forever.
            allowWrites(stuckDir);
            snapshots.create(instanceId, "volume capture 5");
            assertThat(Map.of(
                    "row", String.valueOf(Models.get(InstanceSnapshotModel.class)
                        .findById(stuck) != null),
                    "payload", String.valueOf(Files.exists(stuckDir))))
                .as("step 4: the retry sweep removed both")
                .isEqualTo(Map.of("row", "false", "payload", "false"));

            Zenit.SETTINGS_VALUES.setValue(
                HohenheimSettings.Backup.SNAPSHOT_RETENTION, 7);
            service.destroy(instanceId);
        });
    }

    /**
     * Boot settle of interrupted captures: a controller killed DURING a capture leaves its row capturing with its
     * payload on disk or on the daemon. The settle marks such a row failed, naming the interruption, and removes its
     * payload; a failed row an older release left mid-capture (it stamped failed from the start) loses its payload
     * too. Only rows whose last write predates the process start are touched, so a capture this process has in
     * flight keeps both its status and its payload. The rows are KEPT as evidence.
     */
    @Test
    void bootSettleFailsAnInterruptedCaptureRemovesItsPayloadAndKeepsTheRow()
            throws IOException {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceSnapshots snapshots = new InstanceSnapshots();
            int instanceId = instanceRecord("snap-boot-reclaim", hostId);
            service.deploy(instanceId);
            Map<String, FakeNativeDaemons.FakeWorkload> daemon =
                FakeNativeDaemons.daemonOf(hostId);
            String handle = FakeNativeDaemons.handleOf(instanceId);
            java.time.Instant beforeThisProcess = java.time.Instant.ofEpochMilli(
                java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime())
                .minusSeconds(3600);

            // 1. A dead VOLUME capture: capturing row, directory on disk. A dead NATIVE capture of an older release:
            //    failed row, snapshot still held by the daemon. And a FRESH capturing row standing in for a capture
            //    this process has in flight.
            Path orphanDir;
            Path liveDir;
            try {
                orphanDir = Files.createTempDirectory("hohenheim-snap-orphan");
                Files.writeString(orphanDir.resolve("data.tar"), "half-written");
                liveDir = Files.createTempDirectory("hohenheim-snap-live");
                Files.writeString(liveDir.resolve("data.tar"), "being written NOW");
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
            int deadVolume = snapshotRow(instanceId, InstanceSnapshotModel.STATUS_CAPTURING, orphanDir.toString(),
                null, beforeThisProcess);
            daemon.get(handle).snapshots.add("hib-test-orphan");
            int deadNative = snapshotRow(instanceId, InstanceSnapshotModel.STATUS_FAILED, null, "hib-test-orphan",
                beforeThisProcess);
            int live = snapshotRow(instanceId, InstanceSnapshotModel.STATUS_CAPTURING, liveDir.toString(), null,
                null);

            // 2. The settle, under the identity ServerMain declares for its boot recovery jobs.
            ExecutionIdentity.runAsSystem(HohenheimSystemPurposes.BOOT, snapshots::recoverInterrupted);

            assertThat(Map.of(
                    "volumeRow", String.valueOf(Models.get(InstanceSnapshotModel.class)
                        .findById(deadVolume) != null),
                    "volumePayload", String.valueOf(Files.exists(orphanDir)),
                    "nativeRow", String.valueOf(Models.get(InstanceSnapshotModel.class)
                        .findById(deadNative) != null),
                    "nativePayload", String.valueOf(
                        daemon.get(handle).snapshots.contains("hib-test-orphan"))))
                .as("step 2: both dead captures lost their payload (disk AND daemon) but"
                    + " KEPT their rows -- the row is the evidence, the payload was the"
                    + " leak")
                .isEqualTo(Map.of("volumeRow", "true", "volumePayload", "false",
                    "nativeRow", "true", "nativePayload", "false"));
            Row interrupted = Models.get(InstanceSnapshotModel.class).findById(deadVolume);
            assertThat((String) interrupted.get(InstanceSnapshotModel.STATUS))
                .as("step 2: the interrupted capture reads failed, no longer capturing")
                .isEqualTo(InstanceSnapshotModel.STATUS_FAILED);
            assertThat((String) interrupted.get(InstanceSnapshotModel.ERROR))
                .as("step 2: naming the interruption").contains("Interrupted");
            assertThat((String) Models.get(InstanceSnapshotModel.class).findById(deadNative)
                    .get(InstanceSnapshotModel.STATUS))
                .as("step 2: the older release's failed row stays failed")
                .isEqualTo(InstanceSnapshotModel.STATUS_FAILED);
            assertThat(Files.exists(liveDir.resolve("data.tar")))
                .as("step 2: the FRESH row's payload -- a capture this process has in"
                    + " flight -- was left alone; settling it would destroy a capture"
                    + " mid-write")
                .isTrue();
            assertThat((String) Models.get(InstanceSnapshotModel.class).findById(live)
                    .get(InstanceSnapshotModel.STATUS))
                .as("step 2: and it still reads capturing")
                .isEqualTo(InstanceSnapshotModel.STATUS_CAPTURING);

            // 3. The settle's stamp names the system's boot work, never an anonymous writer or an account.
            assertThat(HistoryTestSupport.recordsOptional())
                .as("step 3: optional history must be recording or this step proves nothing")
                .isTrue();
            assertThat(RecordedChanges.lastUpdateOf(Models.get(InstanceSnapshotModel.class), deadVolume,
                    InstanceSnapshotModel.STATUS.getName()).attribution())
                .as("step 3: the interrupted capture's FAILED stamp is the system's boot work")
                .isEqualTo(RecordedChanges.systemWork(HohenheimSystemPurposes.BOOT));

            service.destroy(instanceId);
        });
    }

    /** A snapshot row in {@code status}; {@code writtenAt} null keeps save-time stamps (a live row). */
    private static int snapshotRow(int instanceId, String status, String directory, String nativeName,
                                   java.time.Instant writtenAt) {
        Row row = Models.get(InstanceSnapshotModel.class).createEmptyRow();
        row.set(InstanceSnapshotModel.INSTANCE_ID, instanceId);
        row.set(InstanceSnapshotModel.STATUS, status);
        row.set(InstanceSnapshotModel.DIRECTORY, directory);
        row.set(InstanceSnapshotModel.NATIVE_NAME, nativeName);
        Models.get(InstanceSnapshotModel.class).save(row);
        int id = row.get(InstanceSnapshotModel.ID);
        if (writtenAt != null) {
            Models.get(InstanceSnapshotModel.class).find()
                .where(InstanceSnapshotModel.ID.eq(id))
                .assign(InstanceSnapshotModel.CREATED_AT, writtenAt)
                .assign(InstanceSnapshotModel.UPDATED_AT, writtenAt)
                .updateAll();
        }
        return id;
    }

    private static void denyWrites(Path directory) {
        try {
            Files.setPosixFilePermissions(directory,
                PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (IOException failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static void allowWrites(Path directory) {
        try {
            Files.setPosixFilePermissions(directory,
                PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException failed) {
            throw new IllegalStateException(failed);
        }
    }

    /**
     * An in-place restore on the volume lane recreates the workload with the environment derived from the attached
     * database as it is NOW, so it records on the attachment the credential generation that environment carries, as
     * a deploy does; a workload the restore leaves stopped is not reported as running behind a password change.
     */
    @Test
    void aVolumeRestoreRecordsTheCredentialGenerationItsRecreatedWorkloadCarries() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceSnapshots snapshots = new InstanceSnapshots();
            Row database = Models.get(DatabaseModel.class).createEmptyRow();
            database.set(DatabaseModel.NAME, "snap-credentials-db");
            database.set(DatabaseModel.ENGINE, DatabaseModel.ENGINE_POSTGRES);
            database.set(DatabaseModel.PLACEMENT, DatabaseModel.PLACEMENT_DEDICATED);
            database.set(DatabaseModel.DB_NAME, "appdb");
            database.set(DatabaseModel.DB_USER, "appuser");
            database.set(DatabaseModel.DB_PASSWORD, "snapshot-secret-password-1");
            database.set(DatabaseModel.SERVER_ID, hostId);
            database.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
            Models.get(DatabaseModel.class).save(database);
            int databaseId = database.get(DatabaseModel.ID);
            int instanceId = volumeInstanceRecord("snap-credentials", hostId);
            Row link = Models.get(InstanceDatabaseModel.class).createEmptyRow();
            link.set(InstanceDatabaseModel.INSTANCE_ID, instanceId);
            link.set(InstanceDatabaseModel.DATABASE_ID, databaseId);
            link.set(InstanceDatabaseModel.ENV_PREFIX, "DB");
            Models.get(InstanceDatabaseModel.class).save(link);
            try {
                // 1. Deployed at generation 0 and captured; the capture hands the workload back through a deploy.
                service.deploy(instanceId);
                int snapshotId = snapshots.create(instanceId, "credentials");
                assertThat(deployedGeneration(link)).as("step 1 anchor: deployed with generation 0").isZero();

                // 2. Stopped, and the database's password changes twice: the attachment is behind.
                service.stop(instanceId);
                Models.get(DatabaseModel.class).find().where(DatabaseModel.ID.eq(databaseId))
                    .assign(DatabaseModel.CREDENTIAL_GENERATION, 2).bypassBehaviours().updateAll();
                assertThat(InstanceDatabaseLinks.staleCredentials(List.of(instanceId)))
                    .as("step 2 anchor: the stopped workload reads as behind the change").containsOnlyKeys(instanceId);

                // 3. The restore recreates it (left stopped, nothing deploys it) from the environment derived now:
                //    the attachment records generation 2.
                snapshots.restore(snapshotId);
                assertThat((String) Models.get(InstanceModel.class).findById(instanceId).get(InstanceModel.STATUS))
                    .as("step 3 anchor: the restore left it stopped").isEqualTo(InstanceModel.STATUS_STOPPED);
                assertThat(deployedGeneration(link)).as("step 3: the recreated workload's generation is recorded")
                    .isEqualTo(2);
                assertThat(InstanceDatabaseLinks.staleCredentials(List.of(instanceId)))
                    .as("step 3: and it no longer reads as behind").isEmpty();
            } finally {
                service.destroy(instanceId);
                HardDeletes.byId(Models.get(InstanceDatabaseModel.class), link.get(InstanceDatabaseModel.ID));
                HardDeletes.row(Models.get(DatabaseModel.class), database);
            }
        });
    }

    /** @return the generation the attachment last recorded */
    private static int deployedGeneration(Row link) {
        return InstanceDatabaseModel.deployedGenerationOf(Models.get(InstanceDatabaseModel.class)
            .findById(link.get(InstanceDatabaseModel.ID)));
    }

    /** An instance of the VOLUME-snapshot fake kind, with one logical volume declared. */
    private static int volumeInstanceRecord(String name, int serverId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND,
            FakeNativeDaemons.FakeVolumeSnapshotKind.ID.toString());
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image",
            "volumes", Map.of("data", "/var/data")));
        row.set(InstanceModel.SERVER_ID, serverId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static int instanceRecord(String name, int serverId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
        row.set(InstanceModel.SERVER_ID, serverId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }
}
