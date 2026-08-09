package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.RootDisk;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.task.ObserveInstanceDisk;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OBSERVED-disk signal against a REAL Incus daemon: a workload with an ENFORCED root
 * quota is measured, real growth moves the number, and the growth trips the attention
 * item. Every figure comes from the daemon; the host's own CLI is the arbiter.
 *
 * AIDEV-NOTE: this exists on Incus and NOT on Docker on purpose. Incus enforces the root
 * disk through a btrfs qgroup and reports {@code disk.root.total}/{@code usage} in its
 * instance state; Docker accepts {@code --storage-opt size=2G} on overlayfs and enforces
 * nothing, so there is no ceiling to be near and no honest percentage to compute. The
 * Docker half of the contract -- that its instances are simply never measured -- is proven
 * in {@link InstanceAttentionTest} step 4 instead of by a live test that would have to
 * assert an absence against a daemon.
 *
 * Every assertion is scoped to THIS class's own instance handle, never a daemon-wide count.
 */
class IncusDiskSignalLiveTest {

    private static final String HOST = "live-incus-disk";

    private static final String IMAGE = "alpine/3.22";

    /** Small enough that a few hundred MB of writes is a large, visible fraction. */
    private static final int ROOT_DISK_GB = 2;

    private static SqliteDatasource datasource;
    private static LiveIncusHost remote;

    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveIncusHost.configured();
        LiveLane.require(LiveLane.Need.INCUS_HOST, remote != null,
            "no live incus host enrolled at " + LiveIncusHost.CONFIG);

        File db = File.createTempFile("hohenheim-incus-disk-live", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();

        Db.run(datasource, () -> remote.enrollThroughProduct(HOST, "hohenheim-live-disk"));
    }

    @AfterAll
    static void tearDown() {
        if (remote != null) {
            System.out.println("=== cleanup: authorized_keys -> "
                + remote.releaseAuthorizedKeys());
            System.out.println("=== cleanup: incus trust -> " + remote.releaseTrustEntries());
        }
    }

    @Test
    void realDiskGrowthIsObservedAndTripsTheAttentionItem() {
        Db.run(datasource, () -> {
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("image", IMAGE);
            settings.put(RootDisk.SETTING, ROOT_DISK_GB);
            int id = instanceRecord("incus-disk", settings);
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
            InstanceService service = new InstanceService();

            try {
                // 1. Deploy with a DECLARED root quota the driver really enforces.
                InstanceStatus deployed = service.deploy(id);
                assertThat(deployed.state())
                    .as("step 1: deploy reports RUNNING").isEqualTo(ContainerState.RUNNING);
                assertThat(stored(id).get(InstanceModel.DISK_OBSERVED_AT))
                    .as("step 1: nothing has measured its disk yet").isNull();

                // 2. The sweeper measures it. The LIMIT it reads back must be the declared
                //    quota, and the usage must be a real, non-zero figure -- a driver that
                //    returned zeros would pass a weaker assertion than this.
                ObserveInstanceDisk.Observation first = ObserveInstanceDisk.observe(id);
                assertThat(first).as("step 2: the driver answered at all").isNotNull();
                assertThat(first.measured())
                    .as("step 2: and the answer was a MEASUREMENT, not a refusal").isTrue();
                assertThat(first.limitBytes())
                    .as("step 2: the enforced ceiling is the declared %s GB", ROOT_DISK_GB)
                    .isEqualTo(ROOT_DISK_GB * 1024L * 1024 * 1024);
                assertThat(first.usedBytes())
                    .as("step 2: and a real occupancy was read, below the ceiling")
                    .isPositive().isLessThan(first.limitBytes());
                assertThat((Long) stored(id).get(InstanceModel.DISK_USED_BYTES))
                    .as("step 2: the observation was stamped on the record")
                    .isEqualTo(first.usedBytes());

                // 3. A comfortable instance raises NOTHING. Without this half the item in
                //    step 5 would prove only that the collector always fires.
                assertThat(diskItems())
                    .as("step 3: a mostly-empty root disk raises no attention item")
                    .isEmpty();

                // 4. REAL GROWTH: write into the workload's own root and observe again.
                //    The number has to MOVE, and by roughly what was written -- a stale or
                //    cached figure passes step 2 and fails here.
                remote.exec(handle,
                    "dd if=/dev/zero of=/root/filler bs=1M count=1800 2>/dev/null;"
                        + " sync; echo written");
                ObserveInstanceDisk.Observation grown = ObserveInstanceDisk.observe(id);
                assertThat(grown).as("step 4: the driver answered again").isNotNull();
                assertThat(grown.usedBytes())
                    .as("step 4: the observed usage grew by roughly the 1800 MB written")
                    .isGreaterThan(first.usedBytes() + 1_500L * 1024 * 1024);

                // 5. And the growth trips the attention item -- the plan's "actual disk
                //    growth trips quota/attention" clause, end to end. At ~90% of the
                //    ceiling it is a WARNING: worth watching, not yet broken.
                assertThat(diskItems())
                    .as("step 5: the near-full instance is now the ONE item raised, as a"
                        + " warning at roughly 90%% of its ceiling")
                    .containsExactly("warning /admin/instances/" + id);

                // 6. Keep writing until the QUOTA ITSELF stops it. dd is expected to fail
                //    here -- that failure IS the enforcement, and it is why an Incus root
                //    disk has an honest percentage at all.
                remote.exec(handle,
                    "dd if=/dev/zero of=/root/filler2 bs=1M count=400 2>&1 | tail -1;"
                        + " sync; true");
                ObserveInstanceDisk.Observation full = ObserveInstanceDisk.observe(id);
                assertThat(full).as("step 6: the driver still answers on a full disk")
                    .isNotNull();
                assertThat(full.usedBytes())
                    .as("step 6: the workload could not exceed its enforced ceiling")
                    .isLessThanOrEqualTo(full.limitBytes());
                assertThat((double) full.usedBytes() / full.limitBytes())
                    .as("step 6: and it is now against that ceiling").isGreaterThan(0.95);
                assertThat(diskItems())
                    .as("step 6: which escalates the item from warning to ERROR")
                    .containsExactly("error /admin/instances/" + id);

                // 7. WITHOUT CORRUPTING THE INSTANCE: it is still running and still usable
                //    after being driven into its ceiling, and freeing space is enough to
                //    recover -- a filled quota is a bounded condition, not a broken one.
                assertThat(service.liveStatus(id).state())
                    .as("step 7: the instance is still running after filling its disk")
                    .isEqualTo(ContainerState.RUNNING);
                assertThat(remote.exec(handle, "rm -f /root/filler /root/filler2; sync;"
                        + " echo still-alive"))
                    .as("step 7: and still executes commands").contains("still-alive");
                ObserveInstanceDisk.observe(id);
                assertThat(diskItems())
                    .as("step 7: once the space is freed the item clears by itself")
                    .isEmpty();
            } catch (IOException daemon) {
                throw new RuntimeException(daemon);
            } finally {
                try {
                    service.destroy(id);
                } catch (RuntimeException ignored) {
                    // best effort
                }
                remote.forceDelete(handle);
            }
        });
    }

    /** The disk attention items as "severity url"; scoped by the caller's own ids. */
    private static List<String> diskItems() {
        List<AttentionItem> items = new ArrayList<>();
        AttentionCollector.instancesLowOnDisk(items);
        List<String> rendered = new ArrayList<>();
        for (AttentionItem item : items) {
            rendered.add(item.severity() + " " + item.url());
        }
        return rendered;
    }

    private static Row stored(int id) {
        return Models.get(InstanceModel.class).findById(id);
    }

    private static int instanceRecord(String name, Map<String, Object> settings) {
        Row host = Models.get(ServerModel.class).findByName(HOST);
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:incus_container");
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.SERVER_ID, host.get(ServerModel.ID));
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }
}
