package be.elevenways.hohenheim.test.instance;

import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.incus.IncusKernelIsolation;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.InstanceMigrations;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Phase 8 cross-host gate clauses, live on TWO real Incus hosts (daystrom +
 * nightstrom): a VM running on the source with data written into it ends up running
 * on the destination with that data intact, the source holds nothing, the record
 * names the new host, isolation is enforced in the DESTINATION KERNEL (nft, not the
 * daemon's config view) with a negative probe and a positive anchor, an off-host
 * backup restores onto a DIFFERENT host, and a controller killed mid-migration
 * settles without split ownership.
 *
 * Skips (never fails) when the second host is not enrolled in
 * {@code ~/.config/hohenheim-livehost/incus.properties} ({@code url_b} keys).
 */
class IncusColdMigrationLiveTest {

    private static final String HOST_A = "live-mig-a";
    private static final String HOST_B = "live-mig-b";
    private static final String VM_IMAGE = "alpine/3.22/cloud";
    private static final String PEER_IMAGE = "alpine/3.22";
    private static final long AGENT_TIMEOUT_MS = 600_000;

    private static LiveIncusHost remoteA;
    private static LiveIncusHost remoteB;
    private static SqliteDatasource datasource;
    private static String fingerprintA;
    private static String fingerprintB;

    @BeforeAll
    static void setUp() throws Exception {
        remoteA = LiveIncusHost.configured();
        assumeTrue(remoteA != null, "no live incus host enrolled at " + LiveIncusHost.CONFIG);
        remoteB = LiveIncusHost.configuredSecondary();
        assumeTrue(remoteB != null,
            "no SECOND live incus host (url_b) enrolled at " + LiveIncusHost.CONFIG);

        File db = File.createTempFile("hohenheim-incus-migration-live", ".db");
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

        Db.run(datasource, () -> {
            // The ceremony INCLUDES the ssh admin lane: kernel-truth verification is an
            // admission requirement for a tenant-accepting host, on both ends of a
            // migration. The fixture hands back every credential it installs.
            fingerprintA = remoteA.enrollThroughProduct(HOST_A, "hohenheim-live-mig-a");
            fingerprintB = remoteB.enrollThroughProduct(HOST_B, "hohenheim-live-mig-b");
        });
    }

    @AfterAll
    static void tearDown() {
        if (remoteA != null) {
            System.out.println("live-mig cleanup A key: "
                + remoteA.releaseAuthorizedKeys());
        }
        if (remoteB != null) {
            System.out.println("live-mig cleanup B key: "
                + remoteB.releaseAuthorizedKeys());
        }
        try {
            if (remoteA != null && fingerprintA != null) {
                remoteA.removeTrustEntry(fingerprintA);
            }
            if (remoteB != null && fingerprintB != null) {
                remoteB.removeTrustEntry(fingerprintB);
            }
        } catch (IOException cleanup) {
            System.out.println("live-mig trust cleanup failed: " + cleanup.getMessage());
        }
    }

    @Test
    void vmDrainsAcrossHostsRestoresToNewHostAndSurvivesAKilledController()
            throws Exception {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceMigrations migrations = new InstanceMigrations();
            int hostAId = Models.get(ServerModel.class).findByName(HOST_A)
                .get(ServerModel.ID);
            int hostBId = Models.get(ServerModel.class).findByName(HOST_B)
                .get(ServerModel.ID);
            int vmId = vmRecord("mig-mover", hostAId);
            int peerId = peerRecord("mig-peer", hostBId);
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, vmId);
            String peerHandle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, peerId);
            Integer restoredId = null;
            String restoredHandle = null;
            java.nio.file.Path targetDir;
            try {
                targetDir = Files.createTempDirectory("mig-backup-target");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            try {
                // 1. The VM deploys and RUNS on the source host, agent up.
                assertThat(service.deploy(vmId).running())
                    .as("step 1: the VM deploys and runs on " + HOST_A)
                    .isTrue();
                awaitTrue("VM agent up on " + HOST_A, AGENT_TIMEOUT_MS,
                    () -> "ready".equals(execQuietly(remoteA, handle, "echo ready")));

                // 2. Distinguishable state: marker v1, a daemon-side snapshot, an
                //    OFF-HOST backup of that state, then marker v2 -- so the backup
                //    lane (v1) and the migration lane (v2) are distinguishable.
                exec(remoteA, handle, "echo v1 > /root/marker && sync");
                exec(remoteA, handle, "true");
                snapshotThroughDaemon(remoteA, handle, "mig-snap");
                int backupId = offHostBackup(vmId, targetDir);
                exec(remoteA, handle, "echo v2 > /root/marker && sync");

                // 3. The peer container runs on the DESTINATION host (the negative
                //    probe's vantage point).
                assertThat(service.deploy(peerId).running())
                    .as("step 3: the peer runs on " + HOST_B)
                    .isTrue();

                // 4. Cordon the source, then DRAIN it through the cold-migration
                //    policy: the one live record on it moves, the drain is complete.
                setAdmission(hostAId, ServerModel.ADMISSION_CORDONED);
                InstanceMigrations.DrainReport report = migrations.drain(hostAId);
                assertThat(report.moved())
                    .as("step 4: the drain moved exactly our workload")
                    .hasSize(1);
                assertThat(report.moved().get(0).instanceId()).isEqualTo(vmId);
                assertThat(report.refused())
                    .as("step 4: nothing was refused")
                    .isEmpty();
                assertThat(report.complete())
                    .as("step 4: the drained host holds no live instance records")
                    .isTrue();

                // 5. The record names the NEW host and the workload runs there.
                Row row = Models.get(InstanceModel.class).findById(vmId);
                assertThat((Object) row.get(InstanceModel.SERVER_ID))
                    .as("step 5: the record names " + HOST_B)
                    .isEqualTo(hostBId);
                assertThat((Object) row.get(InstanceModel.MIGRATE_TARGET_ID))
                    .as("step 5: the migration window is closed")
                    .isNull();
                assertThat((String) row.get(InstanceModel.STATUS))
                    .as("step 5: a workload that was running is running again")
                    .isEqualTo(InstanceModel.STATUS_RUNNING);

                // 6. The SOURCE daemon holds nothing under the handle (scoped to OUR
                //    handle -- other forks share these daemons).
                try {
                    assertThat(remoteA.instanceInfoOrError(handle))
                        .as("step 6: the source daemon no longer knows the handle")
                        .contains("ERROR");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                // 7. Data intact ON THE DESTINATION, snapshots carried, agent up.
                awaitTrue("migrated VM agent up on " + HOST_B, AGENT_TIMEOUT_MS,
                    () -> "ready".equals(execQuietly(remoteB, handle, "echo ready")));
                assertThat(exec(remoteB, handle, "cat /root/marker"))
                    .as("step 7: the data written on the source (v2, post-backup) is"
                        + " intact on the destination")
                    .isEqualTo("v2");
                assertThat(query(remoteB, "/1.0/instances/" + handle + "/snapshots"))
                    .as("step 7: the pool-resident snapshot travelled with the workload")
                    .contains("mig-snap");

                // 8. KERNEL truth on the destination: nft names the LIVE tap of the
                //    migrated NIC with reject rules -- read on nightstrom's kernel,
                //    never the daemon's config view. Both the raw ruleset and the
                //    product verifier (over the enrolled ssh lane) must agree.
                String tap = liveTapOf(remoteB, handle);
                String nft;
                try {
                    nft = remoteB.hostCommand("nft", "list", "table", "bridge", "incus");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                // Printed on purpose: the destination-side KERNEL ruleset is the
                // artifact the gate clause asks to see, not only to assert on.
                System.out.println("DESTINATION KERNEL (nft list table bridge incus on "
                    + HOST_B + "), live tap " + tap + ":\n" + nft);
                assertThat(nft)
                    .as("step 8: nightstrom's kernel carries rules naming the live tap "
                        + tap + " -- ruleset:\n" + nft)
                    .contains(tap);
                Row hostB = Models.get(ServerModel.class).findByName(HOST_B);
                IncusKernelIsolation kernel = IncusKernelIsolation.forServer(hostB);
                assertThat(kernel.available())
                    .as("step 8: the kernel-truth lane is AVAILABLE on the destination"
                        + " (enrolled ssh admin lane)")
                    .isTrue();
                try {
                    kernel.enforce(handle);
                } catch (IOException unisolated) {
                    throw new AssertionError("step 8: the product verifier refuses the"
                        + " migrated workload's kernel isolation: "
                        + unisolated.getMessage(), unisolated);
                }

                // 9. NEGATIVE: the peer on the destination cannot reach the migrated
                //    VM; POSITIVE ANCHOR: the same probe reaches the internet by
                //    address literal, so the negative measured policy, not a dead NIC.
                String vmIp = addressOf(remoteB, handle);
                assertThat(canReach(remoteB, peerHandle, vmIp))
                    .as("step 9: the peer cannot reach the migrated VM at " + vmIp
                        + " on the destination host")
                    .isFalse();
                assertThat(canReach(remoteB, peerHandle, "1.1.1.1"))
                    .as("step 9: while the very same probe reaches 1.1.1.1, so the"
                        + " peer's NIC is alive and the block above is the policy")
                    .isTrue();

                // 10. RESTORE TO A NEW HOST: refused while the target is cordoned,
                //     then lands on the (uncordoned) OLD source host with the
                //     backed-up state v1 -- the backup lane, not the migration lane.
                InstanceBackups backups = new InstanceBackups();
                Throwable cordoned = catchThrowable(() -> backups.restoreToNew(
                    backupId, "mig-restored", HOST_A));
                assertThat(cordoned)
                    .as("step 10: restore onto a CORDONED host refuses by name")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all()).anySatisfy(violation ->
                            assertThat(violation.message().key())
                                .isEqualTo("host_not_admitted")));
                setAdmission(hostAId, ServerModel.ADMISSION_ADMITTED);
                restoredId = backups.restoreToNew(backupId, "mig-restored", HOST_A);
                restoredHandle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, restoredId);
                Row restored = Models.get(InstanceModel.class).findById(restoredId);
                assertThat((Object) restored.get(InstanceModel.SERVER_ID))
                    .as("step 10: the restored instance lives on the DIFFERENT host"
                        + " the operator named")
                    .isEqualTo(hostAId);
                String rh = restoredHandle;
                awaitTrue("restored VM agent up on " + HOST_A, AGENT_TIMEOUT_MS,
                    () -> "ready".equals(execQuietly(remoteA, rh, "echo ready")));
                assertThat(exec(remoteA, restoredHandle, "cat /root/marker"))
                    .as("step 10: the restore carries the BACKED-UP state (v1), not"
                        + " the migrated state (v2)")
                    .isEqualTo("v1");

                // 11. KILLED CONTROLLER, live: the crash lands after the destination
                //     import, leaving copies on BOTH real daemons and the record
                //     mid-migration -- exactly the split this wave must kill.
                int rid = restoredId;
                Throwable killed = catchThrowable(() -> new InstanceMigrations(
                    new InstanceService(), step -> {
                        if ("imported".equals(step)) {
                            throw new IllegalStateException("controller killed live");
                        }
                    }).migrateTo(rid, hostBId));
                assertThat(killed)
                    .as("step 11: the simulated kill escaped the failure net")
                    .isInstanceOf(IllegalStateException.class);
                assertThat((String) Models.get(InstanceModel.class).findById(restoredId)
                        .get(InstanceModel.STATUS))
                    .as("step 11: the record is left MIGRATING, as a dead controller"
                        + " leaves it")
                    .isEqualTo(InstanceModel.STATUS_MIGRATING);
                assertThat(instancePresent(remoteB, restoredHandle))
                    .as("step 11: the destination daemon holds the imported copy")
                    .isTrue();
                assertThat(instancePresent(remoteA, restoredHandle))
                    .as("step 11: while the source daemon still holds the original")
                    .isTrue();

                // 12. Boot recovery settles it: the record's host still holds the
                //     workload, so the settle ROLLS BACK -- the destination copy is
                //     removed from the real daemon and exactly ONE host holds it.
                InstanceMigrations.recoverInterrupted();
                Row settled = Models.get(InstanceModel.class).findById(restoredId);
                assertThat((Object) settled.get(InstanceModel.SERVER_ID))
                    .as("step 12: rollback keeps the record on " + HOST_A)
                    .isEqualTo(hostAId);
                assertThat((String) settled.get(InstanceModel.STATUS))
                    .as("step 12: settled STOPPED, never auto-started")
                    .isEqualTo(InstanceModel.STATUS_STOPPED);
                assertThat((Object) settled.get(InstanceModel.MIGRATE_TARGET_ID))
                    .as("step 12: window closed")
                    .isNull();
                assertThat(instancePresent(remoteB, restoredHandle))
                    .as("step 12: the destination copy is GONE from the real daemon")
                    .isFalse();
                assertThat(instancePresent(remoteA, restoredHandle))
                    .as("step 12: the source still holds the one true copy")
                    .isTrue();

                // 13. Verified teardown through the product; the daemons end holding
                //     NONE of this class's handles.
                service.destroy(vmId);
                service.destroy(peerId);
                service.destroy(restoredId);
                assertThat(instancePresent(remoteA, handle)).isFalse();
                assertThat(instancePresent(remoteA, restoredHandle)).isFalse();
                assertThat(instancePresent(remoteB, handle)).isFalse();
                assertThat(instancePresent(remoteB, peerHandle)).isFalse();
                assertThat(instancePresent(remoteB, restoredHandle)).isFalse();
            } finally {
                remoteA.forceDelete(handle);
                remoteB.forceDelete(handle);
                remoteB.forceDelete(peerHandle);
                if (restoredHandle != null) {
                    remoteA.forceDelete(restoredHandle);
                    remoteB.forceDelete(restoredHandle);
                }
                deleteRecursively(targetDir);
            }
        });
    }

    // -- record helpers ---------------------------------------------------------

    private static int vmRecord(String name, int hostId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:incus_vm");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", VM_IMAGE, "memory_limit_mb", 512)));
        row.set(InstanceModel.SERVER_ID, hostId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static int peerRecord(String name, int hostId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:incus_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", PEER_IMAGE, "memory_limit_mb", 128)));
        row.set(InstanceModel.SERVER_ID, hostId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static void setAdmission(int serverId, String admission) {
        Row row = Models.get(ServerModel.class).findById(serverId);
        if (ServerModel.ADMISSION_ADMITTED.equals(admission)) {
            HostAdmission.requireAdmittable(row);
        }
        row.set(ServerModel.ADMISSION, admission);
        Models.get(ServerModel.class).save(row);
    }

    /** A filesystem backup target on the controller + one COMPLETE backup of the VM. */
    private static int offHostBackup(int instanceId, java.nio.file.Path targetDir) {
        Row target = Models.get(BackupTargetModel.class).createEmptyRow();
        target.set(BackupTargetModel.NAME, "mig-live-target");
        target.set(BackupTargetModel.KIND, "hohenheim:filesystem");
        target.set(BackupTargetModel.SETTINGS,
            new LinkedHashMap<>(Map.of("path", targetDir.toString())));
        Models.get(BackupTargetModel.class).save(target);
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        instance.set(InstanceModel.BACKUP_TARGET_ID, target.get(BackupTargetModel.ID));
        Models.get(InstanceModel.class).save(instance);
        return new InstanceBackups().backupNow(instanceId);
    }

    // -- daemon truth helpers -----------------------------------------------------

    private static void snapshotThroughDaemon(LiveIncusHost remote, String handle,
                                              String snapshot) {
        try {
            remote.hostCommand("incus", "snapshot", "create", handle, snapshot);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean instancePresent(LiveIncusHost remote, String handle) {
        try {
            return !remote.instanceInfoOrError(handle).startsWith("ERROR");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String query(LiveIncusHost remote, String path) {
        try {
            return remote.query(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** The live host-side interface name of the workload's NIC (volatile truth). */
    private static String liveTapOf(LiveIncusHost remote, String handle) {
        String name = query(remote, "/1.0/instances/" + handle)
            .lines()
            .filter(line -> line.contains("volatile.eth0.host_name"))
            .findFirst()
            .map(line -> line.replaceAll(".*:\\s*\"?([A-Za-z0-9_-]+)\"?,?\\s*$", "$1"))
            .orElse("");
        assertThat(name)
            .as("the daemon names a live host interface for " + handle)
            .isNotBlank();
        return name;
    }

    private static String addressOf(LiveIncusHost remote, String handle) {
        long deadline = System.currentTimeMillis() + 60_000;
        while (true) {
            String out = execQuietly(remote, handle,
                "ip -o -f inet addr show eth0 | awk '{print $4}' | cut -d/ -f1 | head -1");
            if (out != null && !out.isBlank()) {
                return out.trim();
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("no IPv4 ever appeared on " + handle);
            }
            sleep();
        }
    }

    private static boolean canReach(LiveIncusHost remote, String fromHandle,
                                    String address) {
        String out = execQuietly(remote, fromHandle,
            "ping -c1 -W2 " + address + " >/dev/null 2>&1 && echo REACH || echo NOPE");
        return out != null && out.trim().endsWith("REACH");
    }

    private static String exec(LiveIncusHost remote, String handle, String command) {
        try {
            return remote.exec(handle, command);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Exec that folds refusals to null: the agent-up polls ride this. */
    private static String execQuietly(LiveIncusHost remote, String handle, String command) {
        try {
            return remote.exec(handle, command);
        } catch (IOException notReady) {
            return null;
        }
    }

    private static void awaitTrue(String what, long timeoutMs, Supplier<Boolean> probe) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(probe.get())) {
                return;
            }
            sleep();
        }
        throw new AssertionError("timed out after " + timeoutMs + "ms waiting for: " + what);
    }

    private static void sleep() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting");
        }
    }

    private static void deleteRecursively(java.nio.file.Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}
