package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceSnapshots;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Snapshot retention and the snapshot record's write discipline, as daemon-free
 * journeys over the shared fake native daemons: completed captures beyond the
 * configured count are pruned from the row AND from the daemon, the newest survive, a
 * retention of 0 prunes nothing, and a completing capture never rewinds a row someone
 * else wrote while it ran.
 *
 * AIDEV-NOTE: this is also the first daemon-free coverage of {@code InstanceSnapshots}
 * at all -- every other snapshot test needs a live Incus or Docker daemon, which the
 * Proxmox-use inventory named as a gap under item 7.
 */
class InstanceSnapshotRetentionTest {

    private static SqliteDatasource datasource;
    private static int hostId;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-snapshot-retention-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
        // The volume lane writes REAL payloads, so the snapshot root has to be a place
        // this test owns rather than the developer's configured one.
        Path root = Files.createTempDirectory("hohenheim-snapshot-retention-root");
        root.toFile().deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.SNAPSHOT_PATH,
            root.toAbsolutePath().toString());
        FakeNativeDaemons.register();
        Db.run(datasource, () -> hostId = incusHost("snap-retention-host"));
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
            HohenheimSettings.VALUES.setValue(
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
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Backup.SNAPSHOT_RETENTION, 0);
            snapshots.create(instanceId, "capture 6");
            snapshots.create(instanceId, "capture 7");
            assertThat(Models.get(InstanceSnapshotModel.class).find()
                    .where(InstanceSnapshotModel.INSTANCE_ID.eq(instanceId)).count())
                .as("step 4: a retention of 0 prunes nothing").isEqualTo(5);

            HohenheimSettings.VALUES.setValue(
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
            HohenheimSettings.VALUES.setValue(
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

            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Backup.SNAPSHOT_RETENTION, 7);
            service.destroy(instanceId);
        });
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
            HohenheimSettings.VALUES.setValue(
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

            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Backup.SNAPSHOT_RETENTION, 7);
            service.destroy(instanceId);
        });
    }

    /**
     * Boot reclaim of interrupted captures: a FAILED row normally holds no payload, but a
     * controller killed DURING a capture leaves one that does (the FAILED-first write
     * discipline), and the never-prune-FAILED rule then preserves that payload forever.
     * The settle removes the payload of rows whose last write predates the process start
     * and KEEPS the rows as evidence; fresh FAILED rows (a capture in flight) are never
     * touched.
     */
    @Test
    void bootReclaimRemovesTheOrphanedPayloadOfAnInterruptedCaptureAndKeepsTheRow()
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

            // 1. A dead VOLUME capture: FAILED row, directory on disk. A dead NATIVE
            //    capture: FAILED row, snapshot still held by the daemon. And a FRESH
            //    failed row standing in for a capture this process has in flight.
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
            int deadVolume = failedRow(instanceId, orphanDir.toString(), null,
                beforeThisProcess);
            daemon.get(handle).snapshots.add("hib-test-orphan");
            int deadNative = failedRow(instanceId, null, "hib-test-orphan",
                beforeThisProcess);
            failedRow(instanceId, liveDir.toString(), null, null);

            // 2. The settle.
            snapshots.recoverInterrupted();

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
            assertThat(Files.exists(liveDir.resolve("data.tar")))
                .as("step 2: the FRESH row's payload -- a capture this process has in"
                    + " flight -- was left alone; settling it would destroy a capture"
                    + " mid-write")
                .isTrue();

            service.destroy(instanceId);
        });
    }

    /** A FAILED snapshot row; {@code writtenAt} null keeps save-time stamps (a live row). */
    private static int failedRow(int instanceId, String directory, String nativeName,
                                 java.time.Instant writtenAt) {
        Row row = Models.get(InstanceSnapshotModel.class).createEmptyRow();
        row.set(InstanceSnapshotModel.INSTANCE_ID, instanceId);
        row.set(InstanceSnapshotModel.STATUS, InstanceSnapshotModel.STATUS_FAILED);
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

    private static int incusHost(String name) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(row);
        HostPreflight.store(name, new HostPreflight.Report(List.of(
            new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "fake daemon"),
            new HostPreflight.Check(IncusPreflight.KERNEL_LANE_CHECK,
                HostPreflight.STATUS_PASS, true, "fake kernel-truth lane")),
            Map.of("mem_total", 16L * 1024 * 1024 * 1024), true, Instant.now(), null));
        return Models.get(ServerModel.class).findByName(name).get(ServerModel.ID);
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
