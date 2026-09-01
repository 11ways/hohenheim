package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceStats;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Stats tab PLOTS the hub's ring: whatever samples the server holds when the page is
 * rendered reach the four sparklines as a drawn path.
 *
 * Pinned defect (QA 2026-09-01): four empty sparklines. The template binds its charts to
 * what {@code InstanceStats.series} returns, and the hawkeye {@code {% let %}} lane
 * silently UNWRAPPED that live ref and re-wrapped a dead snapshot of it, so every listener
 * the chart attached subscribed to a copy nobody ever writes to. That half is fixed in the
 * compiler and proven by hawkeye's own {@code LetBoundLiveReferenceTest}, which pushes into
 * a returned ref after the render and watches the binding repaint.
 *
 * AIDEV-NOTE: this suite deliberately stops at the SEEDED plot, because the live half has a
 * SECOND, still-open defect that no assertion here can honestly cover. Driving it from a
 * real browser (see the QA notes) showed: hydration completes
 * ({@code window.__hawkeye_reactive_idle} goes true), the four sparklines stand, the console
 * carries no error -- and the page never creates a WebSocket at all
 * ({@code page.onWebSocket} records nothing in 15s). So {@code HohenheimStatsFunctions.follow}
 * never runs in the browser even though the bundle contains it (both {@code instance_stats}
 * and {@code /zenit/channel} are present in public/cms.js, so it is not tree-shaken and
 * {@code Blast.IS_TEAVM} folded true), and {@code ClientMain} did run ("Starting Client-Size
 * Zenit"). An empty socket list rules out the gateway, the handshake and the record
 * capability walk -- nothing client-side ever called {@code ChannelClient.open}. No hohenheim
 * browser test has ever exercised a zenit channel, so this lane was never proven. Whoever
 * picks that up: the question is why the client render does not execute the
 * {@code {% if running %}} body of hohenheim:cms/instance-stats.
 */
class InstanceStatsChartPlotTest extends HohenheimTestBase {

    private static int instanceId;
    private static String handle;

    @BeforeAll
    static void seed() {
        FakeNativeDaemons.register();
        int hostId = fakeHost("statschart-host");

        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, "statschart-web");
        row.set(InstanceModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
        row.set(InstanceModel.SERVER_ID, hostId);
        Models.get(InstanceModel.class).save(row);
        instanceId = row.get(InstanceModel.ID);

        new InstanceService().deploy(instanceId);
        handle = FakeNativeDaemons.handleOf(instanceId);
    }

    @AfterAll
    static void tearDown() {
        InstanceStats.shutdown();
        FakeNativeDaemons.resetStreams();
    }

    @Test
    void theHubsRingIsPlottedOnEverySeries() throws Exception {
        // 1. A server-side viewer opens the driver's stats lane, and real samples land in
        //    the hub's ring. This is the state a second tab arrives into: the ring is
        //    replayed to whoever attaches, which is what the page renders from.
        List<InstanceStats.Sample> seen = new CopyOnWriteArrayList<>();
        InstanceStats.Subscription viewer = InstanceStats.subscribe(instanceId, seen::add);
        try {
            FakeNativeDaemons.ScriptedStream stream = FakeNativeDaemons.STATS_STREAMS.get(handle);
            assertThat(stream)
                .as("step 1: the hub opened the driver's stats lane")
                .isNotNull();
            stream.push(sample(1_000_000_000L, 10_000_000_000L, 300, 400) + "\n");
            stream.push(sample(1_100_000_000L, 10_400_000_000L, 500, 600) + "\n");
            stream.push(sample(1_200_000_000L, 10_800_000_000L, 700, 800) + "\n");
            await("step 1: three samples reached the ring", () -> seen.size() >= 3);
            assertThat(InstanceStats.history(instanceId))
                .as("step 1: and the ring retained them for the next reader")
                .hasSizeGreaterThanOrEqualTo(3);

            // 2. The tab of a running instance renders its four charts.
            navigateToApp("/admin/instances/" + instanceId + "/page/stats");
            waitForHydration();
            assertThat(page.locator("pl-sparkline").count())
                .as("step 2: cpu, memory, received and transmitted -- four series")
                .isEqualTo(4);

            // 3. THE REGRESSION: the seed reaches the chart as a DRAWN PATH. The ref the
            //    template binds is the one InstanceStats.series returns; a chart bound to a
            //    dead snapshot of it -- or handed the ref object itself -- draws nothing.
            assertThat(lineOf(0))
                .as("step 3: the cpu series is plotted from the hub's ring")
                .isNotNull()
                .isNotEmpty();
            assertThat(lineOf(1))
                .as("step 3: and so is memory, which every sample carries")
                .isNotNull()
                .isNotEmpty();
            assertThat(lineOf(2))
                .as("step 3: and received")
                .isNotNull()
                .isNotEmpty();
            assertThat(lineOf(3))
                .as("step 3: and transmitted")
                .isNotNull()
                .isNotEmpty();
        } finally {
            viewer.close();
        }
    }

    // -- plumbing -------------------------------------------------------------

    /** The {@code d} of one sparkline's series line, by index in cpu/memory/rx/tx order. */
    private String lineOf(int index) {
        return page.locator("pl-sparkline path.series-line").nth(index).getAttribute("d");
    }

    /** One docker-shaped stats object; every number the decode reads is declared here. */
    private static String sample(long cpuUsage, long systemUsage, long rx, long tx) {
        return "{\"cpu_stats\":{\"cpu_usage\":{\"total_usage\":" + cpuUsage + "},"
            + "\"system_cpu_usage\":" + systemUsage + ",\"online_cpus\":4},"
            + "\"memory_stats\":{\"usage\":536870912,\"limit\":1073741824},"
            + "\"networks\":{\"eth0\":{\"rx_bytes\":" + rx + ",\"tx_bytes\":" + tx + "},"
            + "\"eth1\":{\"rx_bytes\":7,\"tx_bytes\":9}}}";
    }

    /** An admitted, tenant-accepting host the fake native kind answers for. */
    private static int fakeHost(String name) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(row);
        HostFixtures.acknowledgePosture(row);
        HostPreflight.store(name, new HostPreflight.Report(List.of(
            new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "fake daemon"),
            new HostPreflight.Check(IncusPreflight.KERNEL_LANE_CHECK,
                HostPreflight.STATUS_PASS, true, "fake kernel-truth lane")),
            Map.of("mem_total", 16L * 1024 * 1024 * 1024), true, Instant.now(), null));
        return Models.get(ServerModel.class).findByName(name).get(ServerModel.ID);
    }

    /** Bounded wait: the stats stream is pumped by a thread of its own. */
    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }
}
