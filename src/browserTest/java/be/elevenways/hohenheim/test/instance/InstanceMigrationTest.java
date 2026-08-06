package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
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
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** name -> handle -> workload; the two "daemons". */
    private static final Map<String, Map<String, FakeWorkload>> DAEMONS =
        new ConcurrentHashMap<>();

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-migration-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        HohenheimTestRuntime.ensureBooted();
        FakeNativeKind.register();
        FakeVolumeKind.register();
        Db.run(datasource, () -> {
            alphaId = incusHost("mig-alpha");
            betaId = incusHost("mig-beta");
        });
    }

    private static int incusHost(String name) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(row);
        DAEMONS.putIfAbsent(name, new ConcurrentHashMap<>());
        return row.get(ServerModel.ID);
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

    private static Map<String, FakeWorkload> daemonOf(int serverId) {
        return DAEMONS.get(ServerModel.nameOf(serverId));
    }

    private static String handleOf(int instanceId) {
        return "fake-instance-" + instanceId;
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
            int id = instanceRecord("mover", alphaId, FakeNativeKind.ID.toString());
            String handle = handleOf(id);

            // 1. Deploy on alpha and give the workload distinguishable state.
            assertThat(service.deploy(id).state())
                .as("step 1: the workload deploys and runs on the source host")
                .isEqualTo(ContainerState.RUNNING);
            FakeWorkload source = daemonOf(alphaId).get(handle);
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
            FakeWorkload moved = daemonOf(betaId).get(handle);
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
            int id = instanceRecord("refusals", alphaId, FakeNativeKind.ID.toString());
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
                FakeVolumeKind.ID.toString());
            assertRefusal(() -> migrations.migrateTo(volumeKind, betaId),
                "migrate_unsupported",
                "step 3: a non-native driver cannot migrate");

            // 4. A FOREIGN same-named workload on the destination is the handle-
            //    collision hazard: refused, never converged over or deleted.
            FakeWorkload stranger = new FakeWorkload();
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
            int id = instanceRecord("crasher", alphaId, FakeNativeKind.ID.toString());
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
            int movable1 = instanceRecord("drain-a", gammaId, FakeNativeKind.ID.toString());
            int movable2 = instanceRecord("drain-b", gammaId, FakeNativeKind.ID.toString());
            int held = instanceRecord("drain-held", gammaId, FakeNativeKind.ID.toString());
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

    // -- the in-memory native runtime ------------------------------------------

    private static final class FakeWorkload {
        final Map<String, String> data = new LinkedHashMap<>();
        final List<String> snapshots = new ArrayList<>();
        boolean running;
        Identifier ownerModel;
        String ownerId;
    }

    private static final class FakeNativeRuntime
            implements InstanceRuntime, NativeSnapshotSupport {

        private final Map<String, FakeWorkload> daemon;

        FakeNativeRuntime(String serverName) {
            this.daemon = DAEMONS.computeIfAbsent(serverName,
                name -> new ConcurrentHashMap<>());
        }

        private static OwnerLabels.Owner ownerOf(InstanceSpec spec) throws IOException {
            OwnerLabels.Owner owner = OwnerLabels.parse(spec.ownerLabels());
            if (owner == null) {
                throw new IOException("spec without owner labels");
            }
            return owner;
        }

        @Override
        public @NonNull String create(@NonNull InstanceSpec spec) throws IOException {
            OwnerLabels.Owner owner = ownerOf(spec);
            FakeWorkload existing = this.daemon.get(spec.handle());
            if (existing != null) {
                if (!owner.model().equals(existing.ownerModel)
                        || !owner.id().equals(existing.ownerId)) {
                    throw new IOException("REFUSED: foreign workload under " + spec.handle());
                }
                return spec.handle();   // converge keeps state
            }
            FakeWorkload workload = new FakeWorkload();
            workload.ownerModel = owner.model();
            workload.ownerId = owner.id();
            this.daemon.put(spec.handle(), workload);
            return spec.handle();
        }

        @Override
        public void start(@NonNull String handle) throws IOException {
            require(handle).running = true;
        }

        @Override
        public void stop(@NonNull String handle, int graceSeconds) throws IOException {
            require(handle).running = false;
        }

        @Override
        public void destroy(@NonNull String handle) {
            this.daemon.remove(handle);
        }

        @Override
        public @NonNull InstanceStatus status(@NonNull String handle) {
            FakeWorkload workload = this.daemon.get(handle);
            if (workload == null) {
                return new InstanceStatus(ContainerState.ABSENT, null);
            }
            return new InstanceStatus(workload.running
                ? ContainerState.RUNNING : ContainerState.STOPPED, null);
        }

        private @NonNull FakeWorkload require(String handle) throws IOException {
            FakeWorkload workload = this.daemon.get(handle);
            if (workload == null) {
                throw new IOException("no workload " + handle);
            }
            return workload;
        }

        // -- NativeSnapshotSupport --------------------------------------------

        @Override
        public void createSnapshot(@NonNull InstanceSpec spec, @NonNull String name)
                throws IOException {
            require(spec.handle()).snapshots.add(name);
        }

        @Override
        public boolean snapshotExists(@NonNull InstanceSpec spec, @NonNull String name)
                throws IOException {
            return require(spec.handle()).snapshots.contains(name);
        }

        @Override
        public void restoreSnapshot(@NonNull InstanceSpec spec, @NonNull String name) {
        }

        @Override
        public void deleteSnapshot(@NonNull InstanceSpec spec, @NonNull String name)
                throws IOException {
            require(spec.handle()).snapshots.remove(name);
        }

        @Override
        public long exportBackup(@NonNull InstanceSpec spec, @NonNull Path destination,
                                 long maxBytes, boolean withSnapshots) throws IOException {
            FakeWorkload workload = require(spec.handle());
            StringBuilder text = new StringBuilder();
            workload.data.forEach((key, value) ->
                text.append("data ").append(key).append('=').append(value).append('\n'));
            if (withSnapshots) {
                workload.snapshots.forEach(snapshot ->
                    text.append("snapshot ").append(snapshot).append('\n'));
            }
            Files.writeString(destination, text.toString());
            return Files.size(destination);
        }

        @Override
        public void importBackup(@NonNull InstanceSpec spec, @NonNull Path archive)
                throws IOException {
            if (this.daemon.containsKey(spec.handle())) {
                throw new IOException("already exists: " + spec.handle());
            }
            OwnerLabels.Owner owner = ownerOf(spec);
            FakeWorkload workload = new FakeWorkload();
            workload.ownerModel = owner.model();
            workload.ownerId = owner.id();
            for (String line : Files.readAllLines(archive)) {
                if (line.startsWith("data ")) {
                    String[] pair = line.substring(5).split("=", 2);
                    workload.data.put(pair[0], pair.length > 1 ? pair[1] : "");
                } else if (line.startsWith("snapshot ")) {
                    workload.snapshots.add(line.substring(9));
                }
            }
            this.daemon.put(spec.handle(), workload);
        }

        @Override
        public @NonNull WorkloadClaim claimOf(@NonNull InstanceSpec spec) throws IOException {
            FakeWorkload workload = this.daemon.get(spec.handle());
            if (workload == null) {
                return WorkloadClaim.ABSENT;
            }
            OwnerLabels.Owner owner = ownerOf(spec);
            return owner.model().equals(workload.ownerModel)
                && owner.id().equals(workload.ownerId)
                ? WorkloadClaim.OURS : WorkloadClaim.FOREIGN;
        }

        @Override
        public @NonNull ImageIdentity imageIdentity(@NonNull InstanceSpec spec) {
            return new ImageIdentity(spec.image(), "fake-fingerprint");
        }
    }

    /** The migratable fake kind: native export/import over the in-memory daemons. */
    private static final class FakeNativeKind implements InstanceKindHandler {

        static final Identifier ID = Identifier.of("hohenheim", "fake_native");
        static final Schema SETTINGS_SCHEMA = new Schema();
        static final StringField IMAGE = SETTINGS_SCHEMA.addField(
            StringField.builder().name("image").build());
        private static boolean registered;

        static void register() {
            if (!registered) {
                registered = true;
                InstanceKinds.register(new FakeNativeKind());
                InstanceKinds.register(new FakeVolumeKind());
            }
        }

        @Override
        public @NonNull Identifier typeId() { return ID; }

        @Override
        public @NonNull String getDisplayName() { return "Fake native"; }

        @Override
        public @NonNull Microcopy getLabel() {
            return Microcopy.of("fake_native").withFilter("scope", "instance_kind");
        }

        @Override
        public String getDescription() { return "in-memory native test kind"; }

        @Override
        public Icon getIcon() { return Icon.of("flask"); }

        @Override
        public String getColor() { return "gray"; }

        @Override
        public Schema getSchema() { return SETTINGS_SCHEMA; }

        @Override
        public @NonNull String requiredRuntime() { return ServerModel.RUNTIME_INCUS; }

        @Override
        public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
            return new FakeNativeRuntime(serverName);
        }

        @Override
        public @NonNull InstanceSpec specFor(int instanceId,
                                             @NonNull Map<String, Object> settings) {
            return new InstanceSpec("fake-instance-" + instanceId,
                String.valueOf(settings.getOrDefault("image", "fake/image")), null,
                Map.of(), Map.of(), null, ResourceLimits.none(),
                new ContainerHardening.Profile("fake", List.of()),
                OwnerLabels.of(InstanceModel.MODEL_ID, instanceId));
        }
    }

    /** A kind whose runtime has NO native export/import: the migrate_unsupported lane. */
    private static final class FakeVolumeKind implements InstanceKindHandler {

        static final Identifier ID = Identifier.of("hohenheim", "fake_volume_only");

        static void register() {
            FakeNativeKind.register();
        }

        @Override
        public @NonNull Identifier typeId() { return ID; }

        @Override
        public @NonNull String getDisplayName() { return "Fake volume-only"; }

        @Override
        public @NonNull Microcopy getLabel() {
            return Microcopy.of("fake_volume_only").withFilter("scope", "instance_kind");
        }

        @Override
        public String getDescription() { return "in-memory non-native test kind"; }

        @Override
        public Icon getIcon() { return Icon.of("flask"); }

        @Override
        public String getColor() { return "gray"; }

        @Override
        public Schema getSchema() { return FakeNativeKind.SETTINGS_SCHEMA; }

        @Override
        public @NonNull String requiredRuntime() { return ServerModel.RUNTIME_INCUS; }

        @Override
        public @NonNull InstanceRuntime runtimeFor(@NonNull String serverName) {
            // Same daemon map, but WITHOUT NativeSnapshotSupport on the type.
            FakeNativeRuntime inner = new FakeNativeRuntime(serverName);
            return new InstanceRuntime() {
                @Override
                public @NonNull String create(@NonNull InstanceSpec spec) throws IOException {
                    return inner.create(spec);
                }

                @Override
                public void start(@NonNull String handle) throws IOException {
                    inner.start(handle);
                }

                @Override
                public void stop(@NonNull String handle, int graceSeconds)
                        throws IOException {
                    inner.stop(handle, graceSeconds);
                }

                @Override
                public void destroy(@NonNull String handle) throws IOException {
                    inner.destroy(handle);
                }

                @Override
                public @NonNull InstanceStatus status(@NonNull String handle) {
                    return inner.status(handle);
                }
            };
        }

        @Override
        public @NonNull InstanceSpec specFor(int instanceId,
                                             @NonNull Map<String, Object> settings) {
            return new InstanceSpec("fake-instance-" + instanceId,
                String.valueOf(settings.getOrDefault("image", "fake/image")), null,
                Map.of(), Map.of(), null, ResourceLimits.none(),
                new ContainerHardening.Profile("fake", List.of()),
                OwnerLabels.of(InstanceModel.MODEL_ID, instanceId));
        }
    }
}
