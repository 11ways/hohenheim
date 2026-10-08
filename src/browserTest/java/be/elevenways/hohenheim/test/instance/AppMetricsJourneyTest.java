package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceStats;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.Poll;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import com.microsoft.playwright.options.BoundingBox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Metrics tab of a running app (board App-Metrics): chart-sized live series, and every
 * reading in words -- CPU against the app's CPU limit, memory against its cap, received and
 * sent as a rate per second -- kept current by the live channel.
 */
class AppMetricsJourneyTest extends HohenheimTestBase {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static int instanceId;
    private static String handle;

    @BeforeAll
    static void seed() {
        FakeNativeDaemons.register();
        int hostId = HostFixtures.admittedIncusHost("appmetrics-host");

        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, "appmetrics-web");
        row.set(InstanceModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image", "cpu_limit", 2));
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
    void theMetricsTabPlotsChartSizedSeriesAndReadsEveryFigureInWords() throws Exception {
        List<InstanceStats.Sample> seen = new CopyOnWriteArrayList<>();
        InstanceStats.Subscription viewer = InstanceStats.subscribe(instanceId, seen::add);
        Duration offset = Now.offset();
        try {
            // 1. Three samples a second apart land in the hub's ring: the CPU delta is a full
            //    core of four, memory is 512 MiB of a 1 GiB cap, and both counters grow by 200
            //    bytes a second.
            FakeNativeDaemons.ScriptedStream stream = FakeNativeDaemons.STATS_STREAMS.get(handle);
            assertThat(stream).as("step 1: the hub opened the driver's stats lane").isNotNull();
            pushSpaced(stream, seen, 1, sample(1_000_000_000L, 10_000_000_000L, 300, 400));
            pushSpaced(stream, seen, 2, sample(1_100_000_000L, 10_400_000_000L, 500, 600));
            pushSpaced(stream, seen, 3, sample(1_200_000_000L, 10_800_000_000L, 700, 800));

            // 2. The tab draws its four series as charts: each takes its card's width at a
            //    chart's height, not the inline sparkline's 7.5rem by 2rem.
            navigateToApp("/admin/instances/" + instanceId + "/page/stats");
            waitForHydration();
            assertThat(page.locator("pl-sparkline[size='block']").count())
                .as("step 2: cpu, memory, received and sent are chart-sized").isEqualTo(4);
            BoundingBox cpuChart = page.locator("[data-stats-metric='cpu'] pl-sparkline").boundingBox();
            assertThat(cpuChart.width).as("step 2: the cpu chart spans its card").isGreaterThan(300);
            assertThat(cpuChart.height).as("step 2: and is chart-tall").isGreaterThanOrEqualTo(100);
            assertThat(page.locator("pl-sparkline path.series-line").nth(2).getAttribute("d"))
                .as("step 2: received plots as a rate from the ring's consecutive samples")
                .isNotEmpty();

            // 2b. Memory plots against its cap from zero: a steady 512 MiB of a 1 GiB cap is a level line at half
            //     the drawing's height (y 16 of the 1..31 band), never on the floor where it reads as nothing.
            String memoryLine = page.locator("[data-stats-metric='memory'] path.series-line").getAttribute("d");
            assertThat(memoryLine)
                .as("step 2b: the memory line sits at half height, against its cap")
                .matches("M0,16(L[0-9.]+,16)+");

            // 3. Every reading is in words: a full core is half of the 2-core limit, memory
            //    reads against its cap, and the counters read as a rate.
            assertThat(nowText("cpu")).as("step 3: cpu against the CPU limit").isEqualTo("50% of 2 cores");
            assertThat(nowText("memory")).as("step 3: memory against its cap").isEqualTo("512.0 MB of 1.0 GB");
            assertThat(nowText("rx")).as("step 3: received per second").matches("\\d+ B/s");
            assertThat(nowText("tx")).as("step 3: sent per second").matches("\\d+ B/s");
            assertThat(page.locator("[data-stats-contract]").textContent())
                .as("step 3: the page says the readings are live only")
                .contains("Nothing is kept");

            // 4. A sample arriving after the render updates the words through the live link: 200 KB more received
            //    reads in kilobytes per second (the seconds between the two samples include the page load).
            pushSpaced(stream, seen, 4, sample(1_300_000_000L, 11_200_000_000L, 200_700, 1_000));
            Poll.until("step 4: the received rate updated live", WAIT,
                () -> nowText("rx").matches("\\d+\\.\\d KB/s"));
        } finally {
            viewer.close();
            Now.setOffset(offset);
        }
    }

    /** Advances the clock a second, then pushes one sample and waits until the hub decoded it. */
    private static void pushSpaced(FakeNativeDaemons.ScriptedStream stream, List<InstanceStats.Sample> seen,
                                   int count, String sample) throws Exception {
        Now.setOffset(Now.offset().plus(Duration.ofSeconds(1)));
        stream.push(sample + "\n");
        Poll.until("sample " + count + " reached the ring", WAIT, () -> seen.size() >= count);
    }

    private String nowText(String metric) {
        return page.locator("[data-stats-now='" + metric + "']").textContent().trim();
    }

    /** One docker-shaped stats object; every number the decode reads is declared here. */
    private static String sample(long cpuUsage, long systemUsage, long rx, long tx) {
        return "{\"cpu_stats\":{\"cpu_usage\":{\"total_usage\":" + cpuUsage + "},"
            + "\"system_cpu_usage\":" + systemUsage + ",\"online_cpus\":4},"
            + "\"memory_stats\":{\"usage\":536870912,\"limit\":1073741824},"
            + "\"networks\":{\"eth0\":{\"rx_bytes\":" + rx + ",\"tx_bytes\":" + tx + "}}}";
    }
}
