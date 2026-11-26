package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.server.runtime.StatsReading;
import be.elevenways.hohenheim.server.traffic.AppCounters;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.hohenheim.test.application.FakeApps;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.traffic.common.TrafficGranularity;
import be.elevenways.zenit.traffic.common.TrafficHit;
import be.elevenways.zenit.traffic.server.TrafficQuery;
import be.elevenways.zenit.traffic.server.TrafficStore;
import be.elevenways.zenit.traffic.server.TrafficTotals;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An app's network use over time: the counters of two of its workloads add up under it, a counter reset counts what
 * moved since and invents nothing, a reading older than the last counts nothing, another app's bytes stay its own, the
 * totals survive a restart of the stores; app_network_read reads them through the real MCP endpoint beside what the
 * app's site answered visitors, refusing a wrong argument by its name and a customer another's app.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class AppNetworkJourneyTest extends HohenheimHttpTestBase {

    @Test
    void anAppsCountersAddUpOverResetsAndRestartsAndAreReadByTool() throws Exception {
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        try {
            TrafficCountingJourneyTest.countUnder(cleanUp, "apps-" + tag);
            int app = application(cleanUp, "network-" + tag);
            int foreign = application(cleanUp, "network-foreign-" + tag);
            // Two workloads of the app, a release and the one after it, each with its own counters.
            int blue = application(cleanUp, "network-blue-" + tag);
            int green = application(cleanUp, "network-green-" + tag);
            long at = Now.instant().toEpochMilli();

            // 1. A first reading only sets the baseline; what each workload's counters grow by after it counts, and
            //    two workloads of one app add up under it.
            AppCounters.observe(app, blue, reading(at, 1_000, 500));
            AppCounters.observe(app, green, reading(at, 50, 20));
            assertThat(bytes(app)).as("step 1: a baseline counts nothing").isEqualTo("received 0, sent 0");
            AppCounters.observe(app, blue, reading(at + 1_000, 1_600, 800));
            AppCounters.observe(app, green, reading(at + 1_000, 250, 120));
            assertThat(bytes(app)).as("step 1: both workloads under the app").isEqualTo("received 800, sent 400");

            // 2. A counter that went down was reset: what it holds now moved since and counts, nothing negative. A
            //    reading older than the last (the other reader's, overlapping) counts nothing.
            AppCounters.observe(app, blue, reading(at + 2_000, 100, 40));
            assertThat(bytes(app)).as("step 2: the reset counts what moved since").isEqualTo("received 900, sent 440");
            AppCounters.observe(app, blue, reading(at + 1_500, 9_000, 9_000));
            assertThat(bytes(app)).as("step 2: an older reading counts nothing").isEqualTo("received 900, sent 440");

            // 3. Another app's bytes are its own, and a transfer is no request.
            AppCounters.observe(foreign, foreign, reading(at, 10, 10));
            AppCounters.observe(foreign, foreign, reading(at + 1_000, 80, 40));
            assertThat(bytes(foreign)).as("step 3: the other app's own").isEqualTo("received 70, sent 30");
            assertThat(totals(app).requests()).as("step 3: no request counted").isZero();

            // 4. The stores stop as the process does and open again: the totals stay and the count continues.
            HohenheimTraffic.stop();
            HohenheimTraffic.start();
            assertThat(bytes(app)).as("step 4: kept over the restart").isEqualTo("received 900, sent 440");
            AppCounters.observe(app, blue, reading(at + 3_000, 300, 100));
            assertThat(bytes(app)).as("step 4: counting on").isEqualTo("received 1100, sent 500");

            // 5. An administrator reads it by tool, beside what the app's site answered visitors.
            Row site = ProxyTestSupport.setupInstanceSite("Network " + tag, "network-" + tag, app);
            int siteId = site.get(SiteModel.ID);
            cleanUp.push(() -> HardDeletes.byId(Models.get(SiteModel.class), siteId));
            Objects.requireNonNull(HohenheimTraffic.store(HohenheimTraffic.Counted.WEB)).record(new TrafficHit(
                Now.instant(), HohenheimTraffic.sourceOf(siteId), "network.test", "/", "GET", 200, 2_048, 10, 1_000,
                "127.0.0.1", null, null));
            URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
            McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
            AccessContext operator = TenantConduits.operator();
            String appCode = HohenheimMcpTest.code(InstanceModel.MODEL_ID, app, operator);
            String foreignCode = HohenheimMcpTest.code(InstanceModel.MODEL_ID, foreign, operator);
            String siteCode = HohenheimMcpTest.code(SiteModel.MODEL_ID, siteId, operator);
            McpHttpSession.Answer day = admin.call("app_network_read", Map.of("code", appCode));
            assertThat(day.error()).as("step 5: " + day.text()).isFalse();
            assertThat(day.text()).as("step 5: the day's bytes, per hour")
                .contains("received: 1100 bytes; sent: 500 bytes;").contains("per hour")
                .contains(siteCode + " 1 2048").contains("to visitors: 2048 bytes");
            assertThat(day.text()).as("step 5: only the steps that counted bytes, the empty ones left out")
                .contains("steps without bytes are left out").doesNotContain(": 0 0");
            McpHttpSession.Answer hour = admin.call("app_network_read", Map.of("code", appCode, "period", "hour"));
            assertThat(hour.error()).as("step 5: " + hour.text()).isFalse();
            assertThat(hour.text()).as("step 5: the hour per minute").contains("per minute")
                .contains("received: 1100 bytes");

            // 6. A wrong call is refused by the argument it names.
            McpHttpSession.Answer year = admin.call("app_network_read", Map.of("code", appCode, "period", "year"));
            assertThat(year.error()).as("step 6: " + year.text()).isTrue();
            assertThat(year.text()).as("step 6: the refusal names it").contains("period");

            // 7. A customer who manages the app reads it, and is refused another's.
            UUID customer = ApiSupport.user("network-" + tag + "@hohenheim.local", "Network customer");
            cleanUp.push(() -> Models.get(UserModel.class).delete(customer));
            RecordGrants.grant(GrantSubjectType.USER, customer, InstanceModel.MODEL_ID, app,
                HohenheimCapabilities.MANAGE, true);
            McpHttpSession own = HohenheimMcpTest.presetSession(server, endpoint, customer, "Network customer");
            McpHttpSession.Answer his = own.call("app_network_read", Map.of("code", appCode));
            assertThat(his.error()).as("step 7: " + his.text()).isFalse();
            assertThat(his.text()).as("step 7: his own app").contains("received: 1100 bytes");
            McpHttpSession.Answer theirs = own.call("app_network_read", Map.of("code", foreignCode));
            assertThat(theirs.error()).as("step 7: " + theirs.text()).isTrue();
            assertThat(theirs.text()).as("step 7: nothing of it").doesNotContain("received:");
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    /** @return an app record, deleted at the end */
    static int application(Deque<Runnable> cleanUp, String name) {
        int id = FakeApps.application(name, "v1");
        cleanUp.push(() -> HardDeletes.byId(Models.get(InstanceModel.class), id));
        return id;
    }

    /** @return a reading of cumulative counters at a moment, the stats lane's shape */
    static StatsReading reading(long at, long rxBytes, long txBytes) {
        return new StatsReading(at, null, 1, 0, 0, rxBytes, txBytes);
    }

    /** @return the app's bytes over the day around now, "received 800, sent 400" */
    private static String bytes(int app) {
        TrafficTotals totals = totals(app);
        return "received " + totals.bytesReceived() + ", sent " + totals.bytesSent();
    }

    private static TrafficTotals totals(int app) {
        TrafficStore store = Objects.requireNonNull(HohenheimTraffic.store(HohenheimTraffic.Counted.APPS));
        Instant now = Now.instant();
        return store.reader().totals(TrafficQuery.of(HohenheimTraffic.appSource(app), now.minus(Duration.ofDays(1)),
            now.plus(Duration.ofDays(1)), TrafficGranularity.DAY));
    }
}
