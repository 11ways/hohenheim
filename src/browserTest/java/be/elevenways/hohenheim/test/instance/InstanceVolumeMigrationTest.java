package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.GameDomainModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.InstanceMigrations;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Cold migration over the VOLUME transport (the Docker tier's shape): the declared
 * volumes' data moves with the record, destination debris is removed before the restore
 * ever merges over it, source volumes do not survive the move, the game-pair and
 * publication locks refuse BY NAME on the surface and at submit, and a killed controller
 * settles both crash windows without split ownership -- volumes included.
 *
 * AIDEV-NOTE: pre-fix (2026-08-12) every step here was refused migrate_unsupported:
 * migration demanded NativeSnapshotSupport on both ends, which only Incus implements,
 * so no Docker workload could move at all. The fake volume kind mirrors the Docker
 * driver's load-bearing semantics -- create reuses an existing volume, destroy keeps
 * volumes, restore MERGES, removeVolumesForRestore refuses a foreign volume -- because
 * those are exactly the behaviours the transport must compensate for.
 */
class InstanceVolumeMigrationTest {

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        FakeNativeDaemons.register();
    }

    private static int host(String name) {
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

    /** A volume-kind instance with two declared logical volumes. */
    private static int volumeInstance(String name, int serverId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, FakeNativeDaemons.FakeVolumeSnapshotKind.ID.toString());
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image",
            "volumes", Map.of("data", "/var/data", "world", "/srv/world")));
        row.set(InstanceModel.SERVER_ID, serverId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

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

    private static FakeNativeDaemons.FakeVolume volumeOn(int serverId, int instanceId,
                                                         String logical) {
        return FakeNativeDaemons.volumesOf(serverId)
            .get(FakeNativeDaemons.volumeNameOf(instanceId, logical));
    }

    @Test
    void coldVolumeMigrationMovesDataOwnershipAndTheChargeInOneDirection() {
        Db.run(datasource, () -> {
            int src = host("vmig-src");
            int dst = host("vmig-dst");
            InstanceService service = new InstanceService();
            int id = volumeInstance("vol-mover", src);
            String handle = FakeNativeDaemons.handleOf(id);

            // 1. Deploy on the source and give BOTH volumes distinguishable state.
            assertThat(service.deploy(id).state())
                .as("step 1: the workload deploys and runs on the source host")
                .isEqualTo(ContainerState.RUNNING);
            volumeOn(src, id, "data").data.put("marker", "v1");
            volumeOn(src, id, "world").data.put("level", "overworld");
            assertThat(InstanceCapacity.bookedMbOn(src))
                .as("step 1: the source carries the workload's 128 MB charge")
                .isEqualTo(128);

            // 2. ATTACK SETUP: containerless volume DEBRIS on the destination -- an
            //    earlier attempt's leftover that the container-claim pre-flight cannot
            //    see. Docker create() silently REUSES an existing volume and a tar
            //    restore MERGES, so without the transport's pre-restore removal the
            //    stale key below survives into the "successfully" migrated workload.
            FakeNativeDaemons.FakeVolume stale = new FakeNativeDaemons.FakeVolume();
            stale.ownerModel = InstanceModel.MODEL_ID;
            stale.ownerId = String.valueOf(id);
            stale.data.put("stale", "from-a-previous-attempt");
            FakeNativeDaemons.volumesOf(dst)
                .put(FakeNativeDaemons.volumeNameOf(id, "data"), stale);

            // 3. Migrate: one call moves record, volume data and the memory charge.
            migrations().migrateTo(id, dst);
            Row row = Models.get(InstanceModel.class).findById(id);
            assertThat((Object) row.get(InstanceModel.SERVER_ID))
                .as("step 3: the record names the DESTINATION host").isEqualTo(dst);
            assertThat((Object) row.get(InstanceModel.MIGRATE_TARGET_ID))
                .as("step 3: the migration window is closed").isNull();
            assertThat((String) row.get(InstanceModel.STATUS))
                .as("step 3: a workload that was running is running again")
                .isEqualTo(InstanceModel.STATUS_RUNNING);

            // 4. Exactly one daemon holds the workload AND its volumes.
            assertThat(FakeNativeDaemons.daemonOf(src).containsKey(handle))
                .as("step 4: the source daemon holds no container under the handle")
                .isFalse();
            assertThat(volumeOn(src, id, "data"))
                .as("step 4: the source daemon holds NO leftover data volume -- tenant"
                    + " data left behind is debris a later move back would merge over")
                .isNull();
            assertThat(volumeOn(src, id, "world"))
                .as("step 4: nor the world volume").isNull();
            assertThat(FakeNativeDaemons.daemonOf(dst).get(handle).running)
                .as("step 4: the destination container runs").isTrue();
            assertThat(volumeOn(dst, id, "data").data)
                .as("step 4: the data volume arrived intact AND carries none of the"
                    + " stale debris -- a merge over the leftover is the lie shaped"
                    + " like a success")
                .containsEntry("marker", "v1")
                .doesNotContainKey("stale");
            assertThat(volumeOn(dst, id, "world").data)
                .as("step 4: the second volume arrived too")
                .containsEntry("level", "overworld");

            // 5. The memory charge moved with the record.
            assertThat(InstanceCapacity.bookedMbOn(src))
                .as("step 5: the source got its charge back").isEqualTo(0);
            assertThat(InstanceCapacity.bookedMbOn(dst))
                .as("step 5: the destination carries it now").isEqualTo(128);

            // 6. The moved workload is operable on its new host.
            service.stop(id);
            assertThat(FakeNativeDaemons.daemonOf(dst).get(handle).running)
                .as("step 6: a post-move stop lands on the destination daemon")
                .isFalse();
            service.destroy(id);
            assertThat(InstanceCapacity.bookedMbOn(dst))
                .as("step 6: destroy hands the charge back").isEqualTo(0);
        });
    }

    @Test
    void thePairAndPublicationLocksRefuseByNameAndReachTheSurvey() {
        Db.run(datasource, () -> {
            int src = host("vref-src");
            int dst = host("vref-dst");
            InstanceService service = new InstanceService();
            int backend = volumeInstance("vol-backend", src);
            int proxy = volumeInstance("vol-proxy", src);
            String backendHandle = FakeNativeDaemons.handleOf(backend);
            service.deploy(backend);
            volumeOn(src, backend, "data").data.put("marker", "precious");

            // 1. Baseline: an unpaired volume-kind workload IS eligible -- the exact
            //    product claim that was false pre-fix (everything Docker-shaped was
            //    refused migrate_unsupported).
            InstanceMigrations.Destination baseline = destinationFor(backend, dst);
            assertThat(baseline.eligible())
                .withFailMessage("step 1: a plain volume-kind workload must be an"
                    + " ELIGIBLE migration source; refusal was '%s'", baseline.refusal())
                .isTrue();

            // 2. A game-domain mapping makes the pair a unit: the SURVEY names the
            //    refusal beside every destination.
            Row mapping = Models.get(GameDomainModel.class).createEmptyRow();
            mapping.set(GameDomainModel.SITE_DOMAIN_ID, 999999);
            mapping.set(GameDomainModel.BACKEND_INSTANCE_ID, backend);
            mapping.set(GameDomainModel.PROXY_INSTANCE_ID, proxy);
            mapping.set(GameDomainModel.BACKEND_PORT, 25565);
            mapping.set(GameDomainModel.ENABLED, true);
            Models.get(GameDomainModel.class).save(mapping);
            InstanceMigrations.Destination paired = destinationFor(backend, dst);
            assertThat(paired.eligible())
                .as("step 2: a paired backend is ineligible on the survey").isFalse();
            assertThat(paired.refusal().key())
                .as("step 2: and the survey names the PAIR as the reason")
                .isEqualTo("migrate_game_paired");
            InstanceMigrations.Destination pairedProxy = destinationFor(proxy, dst);
            assertThat(pairedProxy.refusal().key())
                .as("step 2: the PROXY side of the pair is locked by the same name")
                .isEqualTo("migrate_game_paired");

            // 3. Submit refuses the same way, and the workload is untouched: moving
            //    the backend alone would break the host-local link network the create
            //    path itself refuses cross-host.
            assertRefusal(() -> migrations().migrateTo(backend, dst),
                "migrate_game_paired",
                "step 3: migrating a paired backend refuses BY NAME");
            assertThat(FakeNativeDaemons.daemonOf(src).get(backendHandle).running)
                .as("step 3: the refused workload was never stopped").isTrue();
            assertThat(FakeNativeDaemons.daemonOf(dst).containsKey(backendHandle))
                .as("step 3: nothing landed on the destination").isFalse();
            assertThat(volumeOn(src, backend, "data").data)
                .as("step 3: and the source data was never captured out from under it")
                .containsEntry("marker", "precious");

            // 4. Severing the mapping is the operator's explicit unlock: the same
            //    move now succeeds -- the refusal was about the PAIR, not the kind.
            Models.get(GameDomainModel.class).find()
                .where(GameDomainModel.ID.eq(mapping.get(GameDomainModel.ID)))
                .delete();
            migrations().migrateTo(backend, dst);
            assertThat((Object) Models.get(InstanceModel.class).findById(backend)
                    .get(InstanceModel.SERVER_ID))
                .as("step 4: the unpaired backend moved").isEqualTo(dst);
            assertThat(volumeOn(dst, backend, "data").data)
                .as("step 4: with its data").containsEntry("marker", "precious");

            // 5. A held publication refuses by name on the survey AND at submit: the
            //    published host port is a host-scoped claim DNS may point at.
            Row published = Models.get(InstanceModel.class).findById(proxy);
            published.set(InstanceModel.SETTINGS, Map.of("image", "fake/image",
                "volumes", Map.of("data", "/var/data", "world", "/srv/world"),
                "container_port", 25577));
            Models.get(InstanceModel.class).save(published);
            InstanceMigrations.Destination portHeld = destinationFor(proxy, dst);
            assertThat(portHeld.eligible())
                .as("step 5: a publishing workload is ineligible on the survey")
                .isFalse();
            assertThat(portHeld.refusal().key())
                .as("step 5: named as the publication, not a generic no")
                .isEqualTo("migrate_publication_present");
            assertRefusal(() -> migrations().migrateTo(proxy, dst),
                "migrate_publication_present",
                "step 5: submit refuses the publication by the same name");

            service.destroy(backend);
            service.destroy(proxy);
        });
    }

    @Test
    void killedControllerSettlesBothVolumeCrashWindowsWithoutSplitOwnership() {
        Db.run(datasource, () -> {
            int src = host("vcrash-src");
            int dst = host("vcrash-dst");
            InstanceService service = new InstanceService();
            int id = volumeInstance("vol-crasher", src);
            String handle = FakeNativeDaemons.handleOf(id);
            service.deploy(id);
            volumeOn(src, id, "data").data.put("marker", "precious");

            // 1. Controller dies right after the destination restore: BOTH daemons
            //    hold the container and the volumes.
            assertThat(catchThrowable(
                    () -> migrationsCrashingAt("imported").migrateTo(id, dst)))
                .as("step 1: the simulated kill escapes the failure net")
                .isInstanceOf(IllegalStateException.class);
            assertThat(FakeNativeDaemons.daemonOf(src).containsKey(handle))
                .as("step 1: source container present").isTrue();
            assertThat(FakeNativeDaemons.daemonOf(dst).containsKey(handle))
                .as("step 1: destination container present -- the split to kill").isTrue();
            assertThat(volumeOn(dst, id, "data"))
                .as("step 1: destination volumes present too").isNotNull();

            // 2. Boot recovery ROLLS BACK (the record's host still holds the data):
            //    the destination copy is removed WHOLE -- container AND volumes, or the
            //    next attempt's restore merges over the leftovers.
            InstanceMigrations.recoverInterrupted();
            Row row = Models.get(InstanceModel.class).findById(id);
            assertThat((Object) row.get(InstanceModel.SERVER_ID))
                .as("step 2: rollback keeps the record on the source").isEqualTo(src);
            assertThat((String) row.get(InstanceModel.STATUS))
                .as("step 2: settled STOPPED, never auto-started")
                .isEqualTo(InstanceModel.STATUS_STOPPED);
            assertThat(FakeNativeDaemons.daemonOf(dst).containsKey(handle))
                .as("step 2: the destination container is gone").isFalse();
            assertThat(volumeOn(dst, id, "data"))
                .as("step 2: and its volumes went with it -- a rollback that keeps the"
                    + " volumes leaves merge bait for the next attempt")
                .isNull();
            assertThat(volumeOn(src, id, "data").data)
                .as("step 2: the surviving copy keeps the data")
                .containsEntry("marker", "precious");

            // 3. Second window: killed AFTER the source copy (container + volumes) is
            //    removed -- the destination holds the only copy.
            assertThat(catchThrowable(
                    () -> migrationsCrashingAt("source_removed").migrateTo(id, dst)))
                .as("step 3: killed after source removal")
                .isInstanceOf(IllegalStateException.class);
            assertThat(FakeNativeDaemons.daemonOf(src).containsKey(handle))
                .as("step 3: source container already gone").isFalse();
            assertThat(volumeOn(src, id, "data"))
                .as("step 3: source volumes already gone").isNull();

            // 4. Recovery completes FORWARD: rolling back would aim the record at a
            //    host holding nothing and delete the only copy.
            InstanceMigrations.recoverInterrupted();
            row = Models.get(InstanceModel.class).findById(id);
            assertThat((Object) row.get(InstanceModel.SERVER_ID))
                .as("step 4: the handoff completed onto the destination").isEqualTo(dst);
            assertThat(volumeOn(dst, id, "data").data)
                .as("step 4: data intact on the completed side")
                .containsEntry("marker", "precious");

            // 5. The settled record deploys normally on its new host.
            service.deploy(id);
            assertThat(FakeNativeDaemons.daemonOf(dst).get(handle).running)
                .as("step 5: operable on the destination").isTrue();
            assertThat(volumeOn(dst, id, "data").data)
                .as("step 5: and the redeploy did not mint fresh empty volumes over"
                    + " the restored ones (Docker create reuses an existing volume)")
                .containsEntry("marker", "precious");
            service.destroy(id);
        });
    }

    @Test
    void aForeignDestinationVolumeRefusesTheMoveAndTheSourceRecovers() {
        Db.run(datasource, () -> {
            int src = host("vfor-src");
            int dst = host("vfor-dst");
            InstanceService service = new InstanceService();
            int id = volumeInstance("vol-collide", src);
            String handle = FakeNativeDaemons.handleOf(id);
            service.deploy(id);
            volumeOn(src, id, "data").data.put("marker", "ours");

            // 1. A FOREIGN same-named volume waits on the destination: a name
            //    collision, not debris -- removing or merging over it would destroy a
            //    stranger's data.
            FakeNativeDaemons.FakeVolume stranger = new FakeNativeDaemons.FakeVolume();
            stranger.ownerModel = InstanceModel.MODEL_ID;
            stranger.ownerId = "999999999";
            stranger.data.put("theirs", "untouchable");
            FakeNativeDaemons.volumesOf(dst)
                .put(FakeNativeDaemons.volumeNameOf(id, "data"), stranger);

            // 2. The migration FAILS LOUDLY at the import step and settles back.
            assertRefusal(() -> migrations().migrateTo(id, dst),
                "instance_migrate_failed",
                "step 2: a foreign destination volume refuses the move");

            // 3. The stranger's volume was never touched, and the workload came home:
            //    rolled back onto the source and restarted (it was running).
            assertThat(FakeNativeDaemons.volumesOf(dst)
                    .get(FakeNativeDaemons.volumeNameOf(id, "data")).data)
                .as("step 3: the foreign volume's data is untouched")
                .containsEntry("theirs", "untouchable");
            Row row = Models.get(InstanceModel.class).findById(id);
            assertThat((Object) row.get(InstanceModel.SERVER_ID))
                .as("step 3: the record still names the source").isEqualTo(src);
            assertThat((Object) row.get(InstanceModel.MIGRATE_TARGET_ID))
                .as("step 3: the window is closed").isNull();
            assertThat(FakeNativeDaemons.daemonOf(src).get(handle))
                .as("step 3: the source still holds the workload").isNotNull();
            assertThat(volumeOn(src, id, "data").data)
                .as("step 3: with its data").containsEntry("marker", "ours");

            FakeNativeDaemons.volumesOf(dst)
                .remove(FakeNativeDaemons.volumeNameOf(id, "data"));
            service.destroy(id);
        });
    }

    /** The survey row for one destination host, which must exist in the survey. */
    private static InstanceMigrations.Destination destinationFor(int instanceId,
                                                                 int serverId) {
        return migrations().destinationsFor(instanceId).stream()
            .filter(destination -> destination.serverId() == serverId)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "host " + serverId + " missing from the survey"));
    }

    private static void assertRefusal(Runnable action, String key, String description) {
        assertThat(catchThrowable(action::run))
            .as(description)
            .isInstanceOfSatisfying(Violations.class, violations ->
                assertThat(violations.all()).anySatisfy(violation ->
                    assertThat(violation.message().key()).isEqualTo(key)));
    }
}
