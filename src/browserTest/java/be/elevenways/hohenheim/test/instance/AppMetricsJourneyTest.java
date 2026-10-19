package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimStatsFunctions;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceStats;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.generation.ModelGenerationModel;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.http.SessionCookies;
import be.elevenways.zenit.test.support.DatasourceProbe;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Metrics tab of a running app: chart-sized live series, and every
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
            assertThat(nowText("memory")).as("step 3: memory against its cap").isEqualTo("512 MB of 1 GB");
            assertThat(nowText("rx")).as("step 3: received per second").matches("\\d+ B/s");
            assertThat(nowText("tx")).as("step 3: sent per second").matches("\\d+ B/s");
            assertThat(page.locator("[data-stats-contract]").textContent())
                .as("step 3: the page says the readings are live only")
                .contains("Nothing is kept");

            // 4. A sample arriving after the render updates the words through the live link: 200 KB more received
            //    reads as the rate between the two samples' own moments (the seconds between them include the page
            //    load), in the size words a rate takes. A push is the hub's frame on the link, no server render, so
            //    it validates no translation.
            Now.setOffset(Now.offset().plus(Duration.ofSeconds(1)));
            DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Models.get(InstanceModel.class)
                .getResolvedDatasource());
            probe.reset();
            stream.push(sample(1_300_000_000L, 11_200_000_000L, 200_700, 1_000) + "\n");
            Poll.until("sample 4 reached the ring", WAIT, () -> seen.size() >= 4);
            String expected = rateWords(seen.get(2), seen.get(3));
            page.waitForFunction("w => document.querySelector(\"[data-stats-now='rx']\").textContent.trim() === w",
                expected, new Page.WaitForFunctionOptions().setTimeout(WAIT.toMillis()));
            assertThat(nowText("rx")).as("step 4: the received rate updated live").isEqualTo(expected)
                .endsWith(" KB/s");
            assertThat(probe.queriesAgainst(ModelGenerationModel.MODEL_ID))
                .as("step 4: a live push validates no translation").isZero();
        } finally {
            viewer.close();
            Now.setOffset(offset);
        }
    }

    @Test
    void theOverviewOpensTheFeedWhileItIsViewedAndReadsMemoryAndCpu() throws Exception {
        InstanceStats.shutdown();
        FakeNativeDaemons.resetStreams();
        Duration offset = Now.offset();
        try {
            // 1. Nobody has the Metrics tab open. Viewing the overview is itself a viewer of the live feed: the hub
            //    opens the driver's stats lane for it, and until the first reading the gauges say they wait.
            navigateToApp("/admin/instances/" + instanceId + "/page/overview");
            waitForHydration();
            Poll.until("step 1: the overview holds the hub's feed", WAIT,
                () -> InstanceStats.viewers(instanceId) == 1 && FakeNativeDaemons.STATS_STREAMS.get(handle) != null);
            assertThat(gaugeText("memory")).as("step 1: memory waits for its first reading, in words")
                .contains("Waiting for its first live reading.");

            // 2. Two readings a second apart: memory is measured against its cap, and CPU is the window between them,
            //    a full core of four.
            FakeNativeDaemons.ScriptedStream stream = FakeNativeDaemons.STATS_STREAMS.get(handle);
            pushHeld(stream, 1, sample(1_000_000_000L, 10_000_000_000L, 300, 400));
            pushHeld(stream, 2, sample(1_100_000_000L, 10_400_000_000L, 500, 600));
            awaitGauge("step 2: the memory gauge reads the live sample", "memory", "512 MB", "1 GB");
            awaitGauge("step 2: the CPU gauge reads the window", "cpu", "100%", "4 cores");
            Object gap = page.evaluate("() => document.querySelector(\"[data-live-gauge='cpu'] .widget-usage\")"
                + ".getBoundingClientRect().top - document.querySelector(\"[data-live-gauge='memory'] .widget-usage\")"
                + ".getBoundingClientRect().bottom");
            assertThat(((Number) gap).doubleValue())
                .as("step 2: the CPU gauge keeps apart from the memory gauge above it, as gauges in a card do")
                .isGreaterThanOrEqualTo(16.0);

            // 3. Leaving the overview releases the feed: no viewer is left, so the daemon stream closes.
            navigateToApp("/admin/instances");
            waitForHydration();
            Poll.until("step 3: nobody holds the feed once the overview is left", WAIT,
                () -> InstanceStats.viewers(instanceId) == 0);
        } finally {
            Now.setOffset(offset);
            // Whatever step stopped, no live link or hub session outlives this test.
            navigateToApp("/admin");
            InstanceStats.shutdown();
        }
    }

    /**
     * A Metrics tab whose live link opens only after a sample arrived still reads that sample, and plots each reading
     * once: the hub hands a new link the readings it holds, and the page folds only those newer than its seed.
     */
    @Test
    void aTabWhoseLinkOpensAfterASampleReadsItAndPlotsEachReadingOnce() throws Exception {
        InstanceStats.shutdown();
        FakeNativeDaemons.resetStreams();
        List<InstanceStats.Sample> seen = new CopyOnWriteArrayList<>();
        InstanceStats.Subscription viewer = InstanceStats.subscribe(instanceId, seen::add);
        Duration offset = Now.offset();
        List<Route> held = new CopyOnWriteArrayList<>();
        Predicate<String> bundle = url -> url.contains("/cms.js");
        try {
            // 1. Three samples land in the hub's ring before the tab is opened.
            FakeNativeDaemons.ScriptedStream stream = FakeNativeDaemons.STATS_STREAMS.get(handle);
            pushSpaced(stream, seen, 1, sample(1_000_000_000L, 10_000_000_000L, 300, 400));
            pushSpaced(stream, seen, 2, sample(1_100_000_000L, 10_400_000_000L, 500, 600));
            pushSpaced(stream, seen, 3, sample(1_200_000_000L, 10_800_000_000L, 700, 800));

            // 2. The tab renders from the ring, but its bundle is held at the wire, so no element of it has opened
            //    its live link: the hub's one viewer is this test's own.
            page.route(bundle, held::add);
            page.context().addCookies(List.of(new Cookie(SessionCookies.name(), sessionToken)
                .setDomain("localhost").setPath("/")));
            page.navigate(baseUrl() + "/admin/instances/" + instanceId + "/page/stats",
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT));
            page.waitForCondition(() -> held.size() == 1);
            assertThat(InstanceStats.viewers(instanceId)).as("step 2: the page holds no link yet").isEqualTo(1);

            // 3. A fourth sample arrives while the page has no link to hear it on.
            pushSpaced(stream, seen, 4, sample(1_300_000_000L, 11_200_000_000L, 200_700, 1_000));

            // 4. THE ASSERTION: the bundle is released, the link opens after the sample, and the page still reads it.
            held.get(0).resume();
            page.unroute(bundle);
            waitForHydration();
            String expected = rateWords(seen.get(2), seen.get(3));
            Poll.until("step 4: the late link read the sample it missed (" + expected + ")", WAIT,
                () -> nowText("rx").equals(expected));

            // 5. Each reading is plotted once: the readings the page was seeded with are not drawn a second time.
            assertThat(pointsOf("memory")).as("step 5: four readings, four points").isEqualTo(4);
        } finally {
            viewer.close();
            Now.setOffset(offset);
            navigateToApp("/admin");
            InstanceStats.shutdown();
        }
    }

    /**
     * A viewer attaching while the hub fans a new sample out receives that sample exactly once, after the readings
     * the hub replayed to it: no reading falls between the replay and the live feed.
     */
    @Test
    void aViewerAttachingWhileASampleFansOutReceivesItExactlyOnce() throws Exception {
        InstanceStats.shutdown();
        FakeNativeDaemons.resetStreams();
        List<InstanceStats.Sample> first = new CopyOnWriteArrayList<>();
        InstanceStats.Subscription firstViewer = InstanceStats.subscribe(instanceId, first::add);
        InstanceStats.Subscription lateViewer = null;
        Duration offset = Now.offset();
        try {
            // 1. Two samples are held in the ring.
            FakeNativeDaemons.ScriptedStream stream = FakeNativeDaemons.STATS_STREAMS.get(handle);
            pushSpaced(stream, first, 1, sample(1_000_000_000L, 10_000_000_000L, 300, 400));
            pushSpaced(stream, first, 2, sample(1_100_000_000L, 10_400_000_000L, 500, 600));

            // 2. A second viewer attaches, and while the hub hands it the readings it holds the third sample is read:
            //    the hub either fans it out at once or holds it until the replay is done.
            List<InstanceStats.Sample> late = new CopyOnWriteArrayList<>();
            AtomicBoolean interleaved = new AtomicBoolean();
            lateViewer = InstanceStats.subscribe(instanceId, sample -> {
                if (interleaved.compareAndSet(false, true)) {
                    Now.setOffset(Now.offset().plus(Duration.ofSeconds(1)));
                    stream.push(sample(1_200_000_000L, 10_800_000_000L, 700, 800) + "\n");
                    Poll.until("step 2: the third sample was fanned out or waits for the replay", WAIT,
                        () -> first.size() >= 3 || pumpWaitsOnTheHub());
                }
                late.add(sample);
            });

            // 3. THE ASSERTION: the late viewer holds every reading, the third exactly once and last.
            Poll.until("step 3: the late viewer received the third sample", WAIT, () -> late.size() >= 3);
            assertThat(late.stream().map(InstanceStats.Sample::rxBytes).toList())
                .as("step 3: replayed readings, then the live one, each once").containsExactly(300L, 500L, 700L);
        } finally {
            if (lateViewer != null) {
                lateViewer.close();
            }
            firstViewer.close();
            Now.setOffset(offset);
            InstanceStats.shutdown();
        }
    }

    /** The words the page says for the received rate between two samples, from their own moments. */
    private static String rateWords(InstanceStats.Sample before, InstanceStats.Sample after) {
        Long rate = HohenheimStatsFunctions.rate(before.toMap(), after.toMap(), "rx");
        assertThat(rate).as("two samples a moment apart have a rate").isNotNull();
        return HohenheimMicrocopy.INSTANCE_STATS.of("per_second").withArg("amount", rate)
            .resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver());
    }

    /** @return the points the metric's chart draws */
    private int pointsOf(String metric) {
        String line = page.locator("[data-stats-metric='" + metric + "'] path.series-line").getAttribute("d");
        return line == null || line.isEmpty() ? 0 : line.split("L").length;
    }

    /** @return whether the hub's stream pump is blocked on its session, waiting to record what it read */
    private static boolean pumpWaitsOnTheHub() {
        for (Map.Entry<Thread, StackTraceElement[]> thread : Thread.getAllStackTraces().entrySet()) {
            if (thread.getKey().getState() == Thread.State.BLOCKED && Arrays.stream(thread.getValue())
                    .anyMatch(frame -> frame.getClassName().endsWith("InstanceStats$Session")
                        && frame.getMethodName().equals("pump"))) {
                return true;
            }
        }
        return false;
    }

    /** Waits until the gauge reads every expected part, failing with what it does read. */
    private void awaitGauge(String step, String metric, String... parts) throws Exception {
        try {
            Poll.until(step, WAIT, () -> List.of(parts).stream().allMatch(gaugeText(metric)::contains));
        } catch (AssertionError timeout) {
            throw new AssertionError(step + ": the gauge reads \"" + gaugeText(metric) + "\"", timeout);
        }
    }

    /** Advances the clock a second, then pushes one sample and waits until the hub holds it. */
    private static void pushHeld(FakeNativeDaemons.ScriptedStream stream, int count, String sample) throws Exception {
        Now.setOffset(Now.offset().plus(Duration.ofSeconds(1)));
        stream.push(sample + "\n");
        Poll.until("sample " + count + " reached the ring", WAIT,
            () -> InstanceStats.history(instanceId).size() >= count);
    }

    private String gaugeText(String metric) {
        return page.locator("[data-live-gauge='" + metric + "']").textContent().trim();
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
