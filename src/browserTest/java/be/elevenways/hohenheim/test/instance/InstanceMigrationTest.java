package be.elevenways.hohenheim.test.instance;

import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceMigrations;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.ImageIdentity;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.NativeSnapshotSupport;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Cold migration and host drain as daemon-free journeys over an in-memory native
 * runtime: the move carries the data and the snapshots, refusals leave the workload
 * untouched, drain reports honestly, and a killed controller settles WITHOUT split
 * ownership in both crash windows (rollback and forward completion).
 */
class InstanceMigrationTest {

    private static SqliteDatasource datasource;
    private static int alphaId;
    private static int betaId;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-migration-test", ".db");
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
        FakeNativeDaemons.register();
        Db.run(datasource, () -> {
            alphaId = incusHost("mig-alpha");
            betaId = incusHost("mig-beta");
        });
    }

    /**
     * A daemon-free host record in the state placement demands: admitted, accepting
     * tenant workloads, and carrying a stored preflight that PROVED its kernel-truth lane.
     *
     * AIDEV-NOTE: the stored report is not decoration. Since kernel-truth verification
     * became an admission requirement, a tenant-accepting host with no proven lane is
     * refused at placement by name (`host_kernel_lane_unproven`) -- which is what an
     * ADMITTED record with no preflight at all always was in production: impossible,
     * because `requireAdmittable` demands `preflight_ok`. It goes through the real store
     * funnel rather than hand-writing the capabilities shape here.
     */
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
            // mem_total is what the capacity budget is read from: an admitted host
            // always carries it in production, and placement skips one that does not.
            Map.of("mem_total", 16L * 1024 * 1024 * 1024), true, Instant.now(), null));
        return Models.get(ServerModel.class).findByName(name).get(ServerModel.ID);
    }

    private static int instanceRecord(String name, int serverId, String kind) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, kind);
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
        row.set(InstanceModel.SERVER_ID, serverId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    /** Daemon-free migrations: the capacity probe (a live-daemon question) is stubbed. */
    private static InstanceMigrations migrations() {
        return new InstanceMigrations(new InstanceService(), step -> {},
            (serverId, bytes) -> {});
    }

    private static InstanceMigrations migrationsCrashingAt(String crashStep) {
        return new InstanceMigrations(new InstanceService(), step -> {
            if (crashStep.equals(step)) {
                throw new IllegalStateException("controller killed at " + step);
            }
        }, (serverId, bytes) -> {});
    }

    private static Map<String, FakeNativeDaemons.FakeWorkload> daemonOf(int serverId) {
        return FakeNativeDaemons.daemonOf(serverId);
    }

    private static String handleOf(int instanceId) {
        return FakeNativeDaemons.handleOf(instanceId);
    }

    /** Every workload on this daemon whose handle belongs to THIS test's records. */
    private static List<String> ownHandlesOn(int serverId, List<Integer> ids) {
        List<String> present = new ArrayList<>();
        for (int id : ids) {
            if (daemonOf(serverId).containsKey(handleOf(id))) {
                present.add(handleOf(id));
            }
        }
        return present;
    }

    @Test
    void coldMigrationMovesDataSnapshotsAndOwnershipInOneDirection() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceMigrations migrations = migrations();
            int id = instanceRecord("mover", alphaId, FakeNativeDaemons.FakeNativeKind.ID.toString());
            String handle = handleOf(id);

            // 1. Deploy on alpha and give the workload distinguishable state.
            assertThat(service.deploy(id).state())
                .as("step 1: the workload deploys and runs on the source host")
                .isEqualTo(ContainerState.RUNNING);
            FakeNativeDaemons.FakeWorkload source = daemonOf(alphaId).get(handle);
            source.data.put("marker", "v1");
            source.snapshots.add("nightly-1");

            // 2. Migrate to beta: one call moves data, snapshots and the record.
            migrations.migrateTo(id, betaId);
            Row row = Models.get(InstanceModel.class).findById(id);
            assertThat((Object) row.get(InstanceModel.SERVER_ID))
                .as("step 2: the record names the DESTINATION host")
                .isEqualTo(betaId);
            assertThat((Object) row.get(InstanceModel.MIGRATE_TARGET_ID))
                .as("step 2: the migration window is closed")
                .isNull();
            assertThat((String) row.get(InstanceModel.STATUS))
                .as("step 2: a workload that was running is running again")
                .isEqualTo(InstanceModel.STATUS_RUNNING);

            // 3. Exactly ONE daemon holds the workload, and it is the destination.
            assertThat(daemonOf(alphaId).containsKey(handle))
                .as("step 3: the source daemon holds NOTHING under the handle"
                    + " (a move that leaves the source copy is the silent-success shape)")
                .isFalse();
            FakeNativeDaemons.FakeWorkload moved = daemonOf(betaId).get(handle);
            assertThat(moved).as("step 3: the destination daemon holds it").isNotNull();
            assertThat(moved.running).as("step 3: and it runs").isTrue();
            assertThat(moved.data.get("marker"))
                .as("step 3: with the data written on the source intact")
                .isEqualTo("v1");
            assertThat(moved.snapshots)
                .as("step 3: and the pool-resident snapshots carried along")
                .containsExactly("nightly-1");

            // 4. The moved workload is fully operable on its new host.
            service.stop(id);
            assertThat(daemonOf(betaId).get(handle).running)
                .as("step 4: a post-move stop lands on the destination daemon")
                .isFalse();
            service.destroy(id);
            assertThat(daemonOf(betaId).containsKey(handle))
                .as("step 4: a post-move destroy removes the destination workload")
                .isFalse();
        });
    }

    @Test
    void refusalsAreNamedAndLeaveTheWorkloadUntouched() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceMigrations migrations = migrations();
            int id = instanceRecord("refusals", alphaId, FakeNativeDaemons.FakeNativeKind.ID.toString());
            String handle = handleOf(id);
            service.deploy(id);

            // 1. Same host: named refusal, nothing moved.
            assertRefusal(() -> migrations.migrateTo(id, alphaId), "migrate_same_host",
                "step 1: migrating onto the current host refuses");

            // 2. Attached devices: their volumes do not travel; refused BY NAME, and
            //    the workload keeps running exactly where it was.
            Row device = Models.get(InstanceDeviceModel.class).createEmptyRow();
            device.set(InstanceDeviceModel.INSTANCE_ID, id);
            device.set(InstanceDeviceModel.TYPE, InstanceDeviceModel.TYPE_DISK);
            device.set(InstanceDeviceModel.NAME, "data0");
            device.set(InstanceDeviceModel.SIZE_GB, 1);
            Models.get(InstanceDeviceModel.class).save(device);
            assertRefusal(() -> migrations.migrateTo(id, betaId), "migrate_devices_present",
                "step 2: a device-bearing workload is unmovable, by name");
            assertThat(daemonOf(alphaId).get(handle).running)
                .as("step 2: the refused workload was never stopped")
                .isTrue();
            assertThat(daemonOf(betaId).containsKey(handle))
                .as("step 2: and nothing landed on the destination")
                .isFalse();
            Models.get(InstanceDeviceModel.class).find()
                .where(InstanceDeviceModel.ID.eq(device.get(InstanceDeviceModel.ID)))
                .delete();

            // 3. A driver without whole-instance export/import refuses by name.
            int volumeKind = instanceRecord("no-native", alphaId,
                FakeNativeDaemons.FakeVolumeKind.ID.toString());
            assertRefusal(() -> migrations.migrateTo(volumeKind, betaId),
                "migrate_unsupported",
                "step 3: a non-native driver cannot migrate");

            // 4. A FOREIGN same-named workload on the destination is the handle-
            //    collision hazard: refused, never converged over or deleted.
            FakeNativeDaemons.FakeWorkload stranger = new FakeNativeDaemons.FakeWorkload();
            stranger.ownerModel = InstanceModel.MODEL_ID;
            stranger.ownerId = "999999999";
            stranger.data.put("theirs", "untouchable");
            daemonOf(betaId).put(handle, stranger);
            assertRefusal(() -> migrations.migrateTo(id, betaId),
                "migrate_destination_occupied",
                "step 4: a foreign workload under our handle refuses the migration");
            assertThat(daemonOf(betaId).get(handle).data.get("theirs"))
                .as("step 4: the stranger's data was never touched")
                .isEqualTo("untouchable");
            daemonOf(betaId).remove(handle);

            // 5. While MIGRATING, deploy and stop refuse (the protected-status gate).
            Row row = Models.get(InstanceModel.class).findById(id);
            row.set(InstanceModel.STATUS, InstanceModel.STATUS_MIGRATING);
            row.set(InstanceModel.MIGRATE_TARGET_ID, betaId);
            Models.get(InstanceModel.class).save(row);
            assertRefusal(() -> service.deploy(id), "instance_busy",
                "step 5: deploy refuses mid-migration");
            assertRefusal(() -> service.stop(id), "instance_busy",
                "step 5: stop refuses mid-migration");
            row.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
            row.set(InstanceModel.MIGRATE_TARGET_ID, null);
            Models.get(InstanceModel.class).save(row);
            service.destroy(id);
            service.destroy(volumeKind);
        });
    }

    @Test
    void killedControllerSettlesBothCrashWindowsWithoutSplitOwnership() {
        Db.run(datasource, () -> {
            int id = instanceRecord("crasher", alphaId, FakeNativeDaemons.FakeNativeKind.ID.toString());
            String handle = handleOf(id);
            new InstanceService().deploy(id);
            daemonOf(alphaId).get(handle).data.put("marker", "precious");

            // 1. Controller dies right after the destination import: both daemons
            //    hold a copy, the record still points at the source.
            Throwable killed = catchThrowable(
                () -> migrationsCrashingAt("imported").migrateTo(id, betaId));
            assertThat(killed)
                .as("step 1: the simulated kill escapes the migration's failure net")
                .isInstanceOf(IllegalStateException.class);
            Row row = Models.get(InstanceModel.class).findById(id);
            assertThat((String) row.get(InstanceModel.STATUS))
                .as("step 1: the record is left mid-migration, exactly like a dead"
                    + " controller leaves it")
                .isEqualTo(InstanceModel.STATUS_MIGRATING);
            assertThat(daemonOf(alphaId).containsKey(handle))
                .as("step 1: source copy present").isTrue();
            assertThat(daemonOf(betaId).containsKey(handle))
                .as("step 1: destination copy present -- the split this wave must kill")
                .isTrue();

            // 2. Boot recovery settles it: the record's host still holds the data,
            //    so the settle ROLLS BACK -- destination copy removed, one owner.
            InstanceMigrations.recoverInterrupted();
            row = Models.get(InstanceModel.class).findById(id);
            assertThat((Object) row.get(InstanceModel.SERVER_ID))
                .as("step 2: rollback keeps the record on the source host")
                .isEqualTo(alphaId);
            assertThat((String) row.get(InstanceModel.STATUS))
                .as("step 2: settled STOPPED, never auto-started")
                .isEqualTo(InstanceModel.STATUS_STOPPED);
            assertThat((Object) row.get(InstanceModel.MIGRATE_TARGET_ID))
                .as("step 2: the migration window is closed").isNull();
            assertThat(daemonOf(betaId).containsKey(handle))
                .as("step 2: the destination copy is GONE -- exactly one daemon holds"
                    + " the workload")
                .isFalse();
            assertThat(daemonOf(alphaId).get(handle).data.get("marker"))
                .as("step 2: with the data intact on the surviving copy")
                .isEqualTo("precious");

            // 3. Second window: the controller dies AFTER the source copy is removed
            //    but BEFORE the handoff -- the record points at a host holding nothing.
            Throwable killedLate = catchThrowable(
                () -> migrationsCrashingAt("source_removed").migrateTo(id, betaId));
            assertThat(killedLate).isInstanceOf(IllegalStateException.class);
            assertThat(daemonOf(alphaId).containsKey(handle))
                .as("step 3: source copy already gone").isFalse();
            assertThat(daemonOf(betaId).containsKey(handle))
                .as("step 3: destination holds the only copy").isTrue();

            // 4. Recovery must complete FORWARD here: rolling back would point the
            //    record at a host with nothing and delete the only copy.
            InstanceMigrations.recoverInterrupted();
            row = Models.get(InstanceModel.class).findById(id);
            assertThat((Object) row.get(InstanceModel.SERVER_ID))
                .as("step 4: the handoff is completed onto the destination")
                .isEqualTo(betaId);
            assertThat((String) row.get(InstanceModel.STATUS))
                .as("step 4: settled STOPPED").isEqualTo(InstanceModel.STATUS_STOPPED);
            assertThat((Object) row.get(InstanceModel.MIGRATE_TARGET_ID))
                .as("step 4: window closed").isNull();
            assertThat(daemonOf(betaId).get(handle).data.get("marker"))
                .as("step 4: data intact on the completed side")
                .isEqualTo("precious");

            // 5. The settled record deploys normally on its new host.
            new InstanceService().deploy(id);
            assertThat(daemonOf(betaId).get(handle).running)
                .as("step 5: the settled record is operable on the destination")
                .isTrue();
            new InstanceService().destroy(id);
        });
    }

    @Test
    void drainMovesEverythingMovableAndReportsTheRestByName() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceMigrations migrations = migrations();
            int gammaId = incusHost("mig-gamma");
            int movable1 = instanceRecord("drain-a", gammaId, FakeNativeDaemons.FakeNativeKind.ID.toString());
            int movable2 = instanceRecord("drain-b", gammaId, FakeNativeDaemons.FakeNativeKind.ID.toString());
            int held = instanceRecord("drain-held", gammaId, FakeNativeDaemons.FakeNativeKind.ID.toString());
            List<Integer> ids = List.of(movable1, movable2, held);
            service.deploy(movable1);
            service.deploy(movable2);
            service.deploy(held);
            Row device = Models.get(InstanceDeviceModel.class).createEmptyRow();
            device.set(InstanceDeviceModel.INSTANCE_ID, held);
            device.set(InstanceDeviceModel.TYPE, InstanceDeviceModel.TYPE_DISK);
            device.set(InstanceDeviceModel.NAME, "data0");
            device.set(InstanceDeviceModel.SIZE_GB, 1);
            Models.get(InstanceDeviceModel.class).save(device);

            // 1. Drain refuses on a host that is not cordoned -- deliberate two-step.
            assertRefusal(() -> migrations.drain(gammaId), "drain_requires_cordon",
                "step 1: drain demands the cordon first");

            // 2. Cordon, then drain: the movable pair moves, the device-bearing one is
            //    refused BY NAME and left running; the report says INCOMPLETE.
            Row gamma = Models.get(ServerModel.class).findById(gammaId);
            gamma.set(ServerModel.ADMISSION, ServerModel.ADMISSION_CORDONED);
            Models.get(ServerModel.class).save(gamma);
            InstanceMigrations.DrainReport report = migrations.drain(gammaId);
            assertThat(report.moved()).as("step 2: both movable workloads moved")
                .hasSize(2);
            assertThat(report.refused())
                .as("step 2: the unmovable one is reported, not skipped")
                .hasSize(1);
            assertThat(report.refused().get(0).name()).isEqualTo("drain-held");
            assertThat(report.refused().get(0).detail())
                .as("step 2: the refusal carries its NAMED reason")
                .contains("device");
            assertThat(report.complete())
                .as("step 2: a drain that left a workload behind says so")
                .isFalse();
            assertThat(daemonOf(gammaId).get(handleOf(held)).running)
                .as("step 2: the refused workload keeps running untouched -- drain is"
                    + " never authority to stop a tenant's workload")
                .isTrue();
            assertThat(ownHandlesOn(gammaId, ids))
                .as("step 2: only the refused workload remains on the source")
                .containsExactly(handleOf(held));
            for (int id : List.of(movable1, movable2)) {
                Row row = Models.get(InstanceModel.class).findById(id);
                assertThat((String) row.get(InstanceModel.STATUS))
                    .as("step 2: a moved workload is RUNNING on its new host")
                    .isEqualTo(InstanceModel.STATUS_RUNNING);
                assertThat(daemonOf((int) row.get(InstanceModel.SERVER_ID))
                        .get(handleOf(id)).running).isTrue();
                assertThat((Object) row.get(InstanceModel.SERVER_ID))
                    .as("step 2: and its record no longer names the drained host")
                    .isNotEqualTo(gammaId);
            }

            // 2b. The drain is ACCOUNTABLE, and that cannot ride on the model hooks:
            //     every write a migration makes is a fenced updateAll, which fires none.
            //     So each MOVED workload carries its own row naming both hosts, and the
            //     host carries one for the drain -- an INCOMPLETE drain included.
            assertThat(ActivityLog.isInstalled())
                .as("step 2b: the activity log must be installed or this proves nothing")
                .isTrue();
            String gammaName = ServerModel.nameOf(gammaId);
            for (int id : List.of(movable1, movable2)) {
                Row moveRow = Models.get(ActivityModel.class).find()
                    .where(ActivityModel.MODEL.eq(InstanceModel.MODEL_ID.toString()))
                    .where(ActivityModel.RECORD_ID.eq(String.valueOf(id)))
                    .where(ActivityModel.ACTION.eq(InstanceMigrations.ACTIVITY_MIGRATE_ACTION))
                    .orderBy(ActivityModel.ID, SortOrder.DESC).first();
                assertThat(moveRow)
                    .as("step 2b: the move of instance %s is recorded on its own record", id)
                    .isNotNull();
                assertThat((String) moveRow.get(ActivityModel.DETAIL))
                    .withFailMessage("step 2b: the record must name where the workload came"
                        + " FROM; detail was '%s'", moveRow.get(ActivityModel.DETAIL))
                    .contains(gammaName);
            }
            Row drainRow = Models.get(ActivityModel.class).find()
                .where(ActivityModel.MODEL.eq(ServerModel.MODEL_ID.toString()))
                .where(ActivityModel.RECORD_ID.eq(String.valueOf(gammaId)))
                .where(ActivityModel.ACTION.eq(InstanceMigrations.ACTIVITY_DRAIN_ACTION))
                .orderBy(ActivityModel.ID, SortOrder.DESC).first();
            assertThat(drainRow)
                .as("step 2b: the drain itself is recorded on the host record").isNotNull();
            assertThat((String) drainRow.get(ActivityModel.DETAIL))
                .withFailMessage("step 2b: an incomplete drain must SAY it left something"
                    + " behind; detail was '%s'", drainRow.get(ActivityModel.DETAIL))
                .contains("refused 1").contains("INCOMPLETE");

            // 3. Detach the device and drain again: the host ends holding NONE.
            Models.get(InstanceDeviceModel.class).find()
                .where(InstanceDeviceModel.ID.eq(device.get(InstanceDeviceModel.ID)))
                .delete();
            InstanceMigrations.DrainReport second = migrations.drain(gammaId);
            assertThat(second.moved()).hasSize(1);
            assertThat(second.complete())
                .as("step 3: the drained host holds no live instances")
                .isTrue();
            assertThat(ownHandlesOn(gammaId, ids))
                .as("step 3: and its daemon holds none of this test's workloads")
                .isEmpty();

            for (int id : ids) {
                new InstanceService().destroy(id);
            }
        });
    }

    private static void assertRefusal(Runnable action, String key, String description) {
        assertThat(catchThrowable(action::run))
            .as(description)
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(violations.all()).anySatisfy(violation ->
                    assertThat(violation.message().key()).isEqualTo(key)));
    }

}
