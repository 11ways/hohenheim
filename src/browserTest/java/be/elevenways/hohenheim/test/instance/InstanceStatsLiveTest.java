package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceStats;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.network.PrivateNetns;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live-stats consumer against a REAL daemon: samples really arrive, they carry real
 * numbers off a container that is really busy, the hub keeps ONE stream for many viewers,
 * and it tears that stream down when the last viewer leaves.
 *
 * The CPU assertion is the built-in counterfactual: a container spinning a shell loop must
 * report NON-ZERO cpu. Every plausible way to get the decode wrong -- reading the daemon's
 * zeroed {@code precpu_stats}, forgetting the delta, reading the wrong key -- lands on zero
 * or on a nonsense constant, and a hub that reported a steady 0% while a core burned would
 * be exactly the observability theater this tier cannot afford.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class InstanceStatsLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-instance-stats-live-test", ".db");
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
        if (PrivateNetns.available()) {
            netns = new PrivateNetns();
            WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
        }
    }

    @AfterAll
    static void tearDown() {
        InstanceStats.shutdown();
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
            netns = null;
        }
    }

    @Test
    void liveSamplesArriveAndTheStreamIsSharedThenTornDown() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, "alpine:latest");
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");

        AtomicReference<String> handleRef = new AtomicReference<>();
        try {
            Db.run(datasource, () -> {
                HostFixtures.admitLocal();
                // A busy loop, so the CPU reading has something real to report.
                Map<String, Object> settings = new LinkedHashMap<>();
                settings.put("image", "alpine");
                settings.put("tag", "latest");
                // The command setting splits on whitespace, so the burn loop carries none.
                settings.put("command", "sh -c yes>/dev/null");
                int id = instanceRecord("stats-live", settings);
                handleRef.set(ControllerScope.handle(ControllerScope.KIND_INSTANCE, id));
                new InstanceService().deploy(id);

                List<InstanceStats.Sample> first = new CopyOnWriteArrayList<>();
                List<InstanceStats.Sample> second = new CopyOnWriteArrayList<>();
                InstanceStats.Subscription firstView;
                InstanceStats.Subscription secondView;
                try {
                    // 1. A viewer attaches and real samples arrive within a few seconds.
                    firstView = InstanceStats.subscribe(id, first::add);
                    waitFor(() -> first.size() >= 3, 25_000);
                    assertThat(first.size())
                        .as("step 1: the daemon's stats stream really delivers samples")
                        .isGreaterThanOrEqualTo(3);

                    // 2. The numbers are REAL: memory is non-zero and a spinning container
                    //    burns measurable CPU. The first sample carries no CPU by design
                    //    (no previous sample to delta against), so the check is on a later
                    //    one -- reporting the lifetime average there would be a lie.
                    assertThat(first.get(0).cpuPercent())
                        .as("step 2: the FIRST sample has no delta and honestly reports 0")
                        .isEqualTo(0d);
                    assertThat(first.stream().skip(1).mapToDouble(InstanceStats.Sample::cpuPercent).max()
                            .orElse(0d))
                        .as("step 2: a spinning container reports non-zero CPU")
                        .isGreaterThan(1d);
                    assertThat(first.get(first.size() - 1).memoryBytes())
                        .as("step 2: memory is the daemon's real working set")
                        .isGreaterThan(0L);

                    // 3. A SECOND viewer shares the one stream and is replayed the ring, so
                    //    it has a chart immediately instead of a blank minute.
                    secondView = InstanceStats.subscribe(id, second::add);
                    assertThat(second.size())
                        .as("step 3: a joining viewer is replayed the retained ring")
                        .isGreaterThanOrEqualTo(3);
                    int beforeShared = first.size();
                    waitFor(() -> first.size() > beforeShared, 15_000);
                    assertThat(first.size())
                        .as("step 3: and the original viewer keeps receiving (one shared stream)")
                        .isGreaterThan(beforeShared);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                // 4. The ring is BOUNDED: history never exceeds the declared window, no
                //    matter how long the stream runs.
                assertThat(InstanceStats.history(id).size())
                    .as("step 4: retained history stays inside the declared window")
                    .isLessThanOrEqualTo(InstanceStats.HISTORY);

                // 5. The last viewer leaving tears the daemon stream down -- an orphaned
                //    per-second stream per instance is a real socket and a real cost.
                firstView.close();
                assertThat(InstanceStats.history(id))
                    .as("step 5: one viewer leaving keeps the shared stream alive")
                    .isNotEmpty();
                secondView.close();
                assertThat(InstanceStats.history(id))
                    .as("step 5: the LAST viewer leaving drops the session entirely")
                    .isEmpty();

                new InstanceService().destroy(id);
            });
        } finally {
            cleanup(docker, handleRef.get());
        }
    }

    // -- plumbing -------------------------------------------------------------

    private static void waitFor(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static int instanceRecord(String name, Map<String, Object> settings) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, settings);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static void cleanup(DockerClient docker, String container) {
        if (container == null) {
            return;
        }
        try {
            docker.removeContainer(container, true);
        } catch (IOException ignored) {
            // already gone
        }
        try {
            docker.removeNetwork(WorkloadNetworks.networkName(container));
        } catch (IOException ignored) {
            // a deploy that never reached the network has none to remove
        }
    }
}
