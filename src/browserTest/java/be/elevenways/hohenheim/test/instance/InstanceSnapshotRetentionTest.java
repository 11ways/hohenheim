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
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Snapshot retention as a daemon-free journey over the shared fake native daemons:
 * completed captures beyond the configured count are pruned from the row AND from the
 * daemon, the newest survive, and a retention of 0 prunes nothing.
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
