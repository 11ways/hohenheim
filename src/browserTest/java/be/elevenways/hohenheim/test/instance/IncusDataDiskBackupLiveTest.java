package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.InstanceDevices;
import be.elevenways.hohenheim.server.instance.InstanceMigrations;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A VM's data disk and extra NIC travel with its backup AND with its cold migration, live on TWO real Incus hosts: a
 * marker written into the disk's volume on daystrom (from the host side, at a fixed offset of the raw block image, so
 * no guest agent is involved) is read back from the restored or moved machine's volume on nightstrom, the device points
 * at that volume, the extra NIC lands on a host that did not hold this controller's extra-NIC bridge (the import
 * ensures it first), the record has both rows, deleting the backup removes the one artifact that carried the disk, a
 * migration leaves no volume on the source, and destroying the machines removes their volumes and releases the bridge
 * on both hosts.
 *
 * Skips (never fails) when the second host is not enrolled in
 * {@code ~/.config/hohenheim-livehost/incus.properties} ({@code url_b} keys).
 *
 * AIDEV-NOTE: the marker path assumes a file-backed pool (btrfs or dir: {@code custom/<project>_<volume>/root.img}),
 * which both test hosts run; a zfs or lvm pool would need the block device instead.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class IncusDataDiskBackupLiveTest {

    private static final String HOST_A = "live-disk-a";
    private static final String HOST_B = "live-disk-b";
    private static final String VM_IMAGE = "alpine/3.22/cloud";
    private static final String DEVICE = "data";
    private static final String NIC = "eth1";

    /** Where the marker sits in the raw disk image: past any partition table a guest could write. */
    private static final long MARKER_OFFSET = 1024 * 1024;

    private static LiveIncusHost remoteA;
    private static LiveIncusHost remoteB;
    private static SqlDatasource datasource;
    private static String fingerprintA;
    private static String fingerprintB;

    @BeforeAll
    static void setUp() throws Exception {
        remoteA = LiveIncusHost.requirePrimary();
        remoteB = LiveIncusHost.requireSecondary();
        // ONE database per test class: the controller identity (and so every daemon resource name) resolves
        // through the current datasource.
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        Db.run(datasource, () -> {
            fingerprintA = remoteA.enrollThroughProduct(HOST_A, "hohenheim-live-disk-a");
            fingerprintB = remoteB.enrollThroughProduct(HOST_B, "hohenheim-live-disk-b");
        });
    }

    @AfterAll
    static void tearDown() {
        for (LiveIncusHost remote : new LiveIncusHost[] {remoteA, remoteB}) {
            if (remote != null) {
                System.out.println("=== cleanup: shared objects -> " + remote.releaseControllerSharedObjects());
                System.out.println("=== cleanup: authorized_keys -> " + remote.releaseAuthorizedKeys());
            }
        }
        try {
            if (remoteA != null && fingerprintA != null) {
                remoteA.removeTrustEntry(fingerprintA);
            }
            if (remoteB != null && fingerprintB != null) {
                remoteB.removeTrustEntry(fingerprintB);
            }
        } catch (IOException cleanup) {
            System.out.println("live-disk trust cleanup failed: " + cleanup.getMessage());
        }
    }

    @Test
    void aVmsDataDiskTravelsWithItsBackupToAnotherHost() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceBackups backups = new InstanceBackups();
            int hostAId = Models.get(ServerModel.class).findByName(HOST_A).get(ServerModel.ID);
            int hostBId = Models.get(ServerModel.class).findByName(HOST_B).get(ServerModel.ID);
            IncusClient daemonA = new ServerService().incusClientFor(HOST_A);
            IncusClient daemonB = new ServerService().incusClientFor(HOST_B);
            int vmId = vmRecord("disk-source", hostAId);
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, vmId);
            String marker = "HHDISK-" + Now.millis();
            Integer restoredId = null;
            String restoredHandle = null;
            Path targetDir = createTempDirectory();
            try {
                // 1. A VM with a 1 GB data disk and an extra NIC deploys on daystrom: the device rows land first, the
                //    deploy's reconcile creates the volume, ensures this controller's extra-NIC bridge and attaches
                //    both, and the VM runs.
                InstanceDevices devices = new InstanceDevices(service);
                devices.attachDisk(vmId, DEVICE, 1);
                devices.attachNic(vmId, NIC);
                assertThat(service.deploy(vmId).running()).as("step 1: the VM runs on " + HOST_A).isTrue();
                Disk source = diskOf(daemonA, handle);
                assertThat(source.volume()).as("step 1: the disk is the record's own volume")
                    .isEqualTo(handle + "-" + DEVICE);
                String bridge = IncusNetworkPolicy.extraNetwork();
                assertThat(nicNetworkOf(daemonA, handle)).as("step 1: the extra NIC rides the controller's bridge")
                    .isEqualTo(bridge);

                // 2. The marker is written into the disk from the host side and reads back there.
                host(remoteA, "sh", "-c", "'printf " + marker + " | dd of=" + source.image()
                    + " bs=1 seek=" + MARKER_OFFSET + " conv=notrunc,fsync status=none'");
                assertThat(readMarker(remoteA, source, marker.length()))
                    .as("step 2: the marker is on the source disk").isEqualTo(marker);

                // 3. A backup of the RUNNING VM carries the disk: its row names it.
                int backupId = offHostBackup(vmId, targetDir);
                Row backup = Models.get(InstanceBackupModel.class).findById(backupId);
                assertThat(RawValues.map((Object) backup.get(InstanceBackupModel.SUMMARY)).get("disks"))
                    .as("step 3: the backup names the data disk it carries").isEqualTo(List.of(DEVICE));

                // 4. Restored onto nightstrom, which holds no bridge of this controller: the restore ensures it before
                //    the daemon validates the archived NIC. The new machine's disk is a volume of its OWN naming there,
                //    holding the marker; the record has both rows; and the machine runs (its deploy re-ensured the
                //    disk, which refuses a volume not attributed to the record, so running proves the attribution).
                assertThat(daemonB.network(bridge)).as("step 4: " + HOST_B + " holds no " + bridge + " before")
                    .isNull();
                restoredId = backups.restoreToNew(backupId, "disk-restored", HOST_B).instanceId();
                restoredHandle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, restoredId);
                assertThat((Object) Models.get(InstanceModel.class).findById(restoredId).get(InstanceModel.SERVER_ID))
                    .as("step 4: the restored record lives on " + HOST_B).isEqualTo(hostBId);
                Disk landed = diskOf(daemonB, restoredHandle);
                assertThat(landed.volume()).as("step 4: the restored disk points at the copy's own volume")
                    .isEqualTo(restoredHandle + "-" + DEVICE);
                assertThat(readMarker(remoteB, landed, marker.length()))
                    .as("step 4: the restored volume holds the marker written on " + HOST_A).isEqualTo(marker);
                assertThat(nicNetworkOf(daemonB, restoredHandle))
                    .as("step 4: the restored NIC rides the bridge the restore ensured").isEqualTo(bridge);
                assertThat(daemonB.network(bridge)).as("step 4: " + HOST_B + " now holds " + bridge).isNotNull();
                Map<String, Row> rows = new LinkedHashMap<>();
                for (Row row : Models.get(InstanceDeviceModel.class).find()
                        .where(InstanceDeviceModel.INSTANCE_ID.eq(restoredId)).all()) {
                    rows.put(row.get(InstanceDeviceModel.NAME), row);
                }
                assertThat(rows).as("step 4: the restored record has the disk's and the NIC's rows")
                    .containsOnlyKeys(DEVICE, NIC);
                assertThat((String) rows.get(DEVICE).get(InstanceDeviceModel.TYPE))
                    .isEqualTo(InstanceDeviceModel.TYPE_DISK);
                assertThat((Integer) rows.get(DEVICE).get(InstanceDeviceModel.SIZE_GB)).isEqualTo(1);
                assertThat((String) rows.get(NIC).get(InstanceDeviceModel.TYPE))
                    .isEqualTo(InstanceDeviceModel.TYPE_NIC);
                assertThat(String.valueOf(daemonB.instance(restoredHandle).get("status")))
                    .as("step 4: the restored machine runs").isEqualTo("Running");

                // 5. Deleting the backup removes the one artifact that carried the disk.
                backups.delete(backupId);
                assertThat(artifactsIn(targetDir)).as("step 5: the target holds no artifact any more").isEmpty();

                // 6. Destroying both machines removes both disk volumes and releases the bridge on both hosts.
                service.destroy(restoredId);
                service.destroy(vmId);
                assertThat(daemonB.customVolume(landed.pool(), landed.volume()))
                    .as("step 6: the restored disk volume is gone from " + HOST_B).isNull();
                assertThat(daemonA.customVolume(source.pool(), source.volume()))
                    .as("step 6: the source disk volume is gone from " + HOST_A).isNull();
                assertThat(daemonB.network(bridge)).as("step 6: " + HOST_B + " released " + bridge).isNull();
                assertThat(daemonA.network(bridge)).as("step 6: " + HOST_A + " released " + bridge).isNull();
            } catch (IOException daemon) {
                throw new RuntimeException(daemon);
            } finally {
                remoteA.forceDelete(handle);
                if (restoredHandle != null) {
                    remoteB.forceDelete(restoredHandle);
                }
                deleteRecursively(targetDir);
            }
        });
    }

    @Test
    void aVmsDataDiskAndExtraNicMoveWithItsMigration() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceMigrations migrations = new InstanceMigrations();
            int hostAId = Models.get(ServerModel.class).findByName(HOST_A).get(ServerModel.ID);
            int hostBId = Models.get(ServerModel.class).findByName(HOST_B).get(ServerModel.ID);
            IncusClient daemonA = new ServerService().incusClientFor(HOST_A);
            IncusClient daemonB = new ServerService().incusClientFor(HOST_B);
            int vmId = vmRecord("disk-mover", hostAId);
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, vmId);
            String volume = handle + "-" + DEVICE;
            String marker = "HHMIG-" + Now.millis();
            try {
                // 1. A VM with a 1 GB data disk and an extra NIC runs on daystrom.
                InstanceDevices devices = new InstanceDevices(service);
                devices.attachDisk(vmId, DEVICE, 1);
                devices.attachNic(vmId, NIC);
                assertThat(service.deploy(vmId).running()).as("step 1: the VM runs on " + HOST_A).isTrue();
                Disk source = diskOf(daemonA, handle);
                assertThat(source.volume()).as("step 1: the disk is the record's own volume").isEqualTo(volume);
                String bridge = IncusNetworkPolicy.extraNetwork();

                // 2. The marker is written into the disk from the host side and reads back there.
                host(remoteA, "sh", "-c", "'printf " + marker + " | dd of=" + source.image()
                    + " bs=1 seek=" + MARKER_OFFSET + " conv=notrunc,fsync status=none'");
                assertThat(readMarker(remoteA, source, marker.length()))
                    .as("step 2: the marker is on the source disk").isEqualTo(marker);

                // 3. Migrated to nightstrom, which holds no bridge of this controller: the disk lands as the same
                //    record's volume there holding the marker, the NIC rides the bridge the import ensured, and the
                //    machine runs again (its deploy re-ensured the disk, which refuses a volume not attributed to the
                //    record, so running proves the attribution).
                assertThat(daemonB.network(bridge)).as("step 3: " + HOST_B + " holds no " + bridge + " before")
                    .isNull();
                migrations.migrateTo(vmId, hostBId);
                assertThat((Object) Models.get(InstanceModel.class).findById(vmId).get(InstanceModel.SERVER_ID))
                    .as("step 3: the record lives on " + HOST_B).isEqualTo(hostBId);
                Disk landed = diskOf(daemonB, handle);
                assertThat(landed.volume()).as("step 3: the moved disk is the record's own volume there")
                    .isEqualTo(volume);
                assertThat(readMarker(remoteB, landed, marker.length()))
                    .as("step 3: the moved volume holds the marker written on " + HOST_A).isEqualTo(marker);
                assertThat(nicNetworkOf(daemonB, handle))
                    .as("step 3: the moved NIC rides the bridge the import ensured").isEqualTo(bridge);
                assertThat(String.valueOf(daemonB.instance(handle).get("status")))
                    .as("step 3: the moved machine runs").isEqualTo("Running");
                assertThat(devices.rowsFor(vmId)).extracting(row -> (String) row.get(InstanceDeviceModel.NAME))
                    .as("step 3: the record keeps both device rows").containsExactlyInAnyOrder(DEVICE, NIC);

                // 4. Nothing of it stays on daystrom: no instance, no disk volume.
                assertThat(catchThrowable(() -> daemonA.instance(handle)))
                    .as("step 4: no instance left on " + HOST_A)
                    .isInstanceOfSatisfying(IncusClient.ApiException.class,
                        missing -> assertThat(missing.isNotFound()).isTrue());
                assertThat(daemonA.customVolume(source.pool(), volume))
                    .as("step 4: the source disk volume is gone from " + HOST_A).isNull();

                // 5. Destroying the moved machine removes its volume and releases the bridge on both hosts.
                service.destroy(vmId);
                assertThat(daemonB.customVolume(landed.pool(), volume))
                    .as("step 5: the moved disk volume is gone from " + HOST_B).isNull();
                assertThat(daemonB.network(bridge)).as("step 5: " + HOST_B + " released " + bridge).isNull();
                assertThat(daemonA.network(bridge)).as("step 5: " + HOST_A + " released " + bridge).isNull();
            } catch (IOException daemon) {
                throw new RuntimeException(daemon);
            } finally {
                for (LiveIncusHost remote : new LiveIncusHost[] {remoteA, remoteB}) {
                    remote.forceDelete(handle);
                    try {
                        remote.hostCommand("sh", "-c", "'incus storage volume delete default " + volume
                            + " 2>/dev/null || true'");
                    } catch (IOException cleanup) {
                        System.out.println("live-disk volume cleanup failed: " + cleanup.getMessage());
                    }
                }
            }
        });
    }

    /** A data disk device as the daemon holds it, and the raw image file behind its volume on a file-backed pool. */
    private record Disk(String pool, String volume) {
        String image() {
            return "/var/lib/incus/storage-pools/" + this.pool + "/custom/default_" + this.volume + "/root.img";
        }
    }

    private static Disk diskOf(IncusClient daemon, String handle) throws IOException {
        Map<?, ?> devices = (Map<?, ?>) daemon.instance(handle).get("devices");
        Map<?, ?> device = (Map<?, ?>) devices.get(DEVICE);
        assertThat(device).as(handle + " carries the '" + DEVICE + "' device").isNotNull();
        return new Disk(String.valueOf(device.get("pool")), String.valueOf(device.get("source")));
    }

    /** @return the network the instance's extra NIC device names */
    private static String nicNetworkOf(IncusClient daemon, String handle) throws IOException {
        Map<?, ?> devices = (Map<?, ?>) daemon.instance(handle).get("devices");
        Map<?, ?> nic = (Map<?, ?>) devices.get(NIC);
        assertThat(nic).as(handle + " carries the '" + NIC + "' device").isNotNull();
        return String.valueOf(nic.get("network"));
    }

    private static String readMarker(LiveIncusHost remote, Disk disk, int length) {
        return host(remote, "dd", "if=" + disk.image(), "bs=1", "skip=" + MARKER_OFFSET, "count=" + length,
            "status=none");
    }

    private static String host(LiveIncusHost remote, String... command) {
        try {
            return remote.hostCommand(command);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static int vmRecord(String name, int hostId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:vm");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", VM_IMAGE, "memory_limit_mb", 512)));
        row.set(InstanceModel.SERVER_ID, hostId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    /** A filesystem backup target on the controller + one COMPLETE backup of the VM. */
    private static int offHostBackup(int instanceId, Path targetDir) {
        Row target = Models.get(BackupTargetModel.class).createEmptyRow();
        target.set(BackupTargetModel.NAME, "disk-live-target");
        target.set(BackupTargetModel.KIND, "hohenheim:filesystem");
        target.set(BackupTargetModel.SETTINGS, new LinkedHashMap<>(Map.of("path", targetDir.toString())));
        Models.get(BackupTargetModel.class).save(target);
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        instance.set(InstanceModel.BACKUP_TARGET_ID, target.get(BackupTargetModel.ID));
        Models.get(InstanceModel.class).save(instance);
        return new InstanceBackups().backupNow(instanceId);
    }

    private static List<Path> artifactsIn(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("disk-backup-target");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // best effort
        }
    }
}
