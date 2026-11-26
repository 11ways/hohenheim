package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SiteRedirectModel;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.server.dns.DnsServer;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.server.proxy.AccessLog;
import be.elevenways.hohenheim.server.proxy.AccessLogLine;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.hohenheim.site.UnknownHostAnswer;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.data.DataTable;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.traffic.common.StatusClass;
import be.elevenways.zenit.traffic.common.TrafficGranularity;
import be.elevenways.zenit.traffic.common.TrafficHit;
import be.elevenways.zenit.traffic.common.TrafficTable;
import be.elevenways.zenit.traffic.server.TopRead;
import be.elevenways.zenit.traffic.server.TrafficQuery;
import be.elevenways.zenit.traffic.server.TrafficStore;
import be.elevenways.zenit.traffic.server.TrafficTotals;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.Type;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hohenheim's traffic counting through the real proxy and DNS server, read back through the real MCP endpoint: every
 * answer a site gives is counted once under it (a page, a wrong-path 404 of its name, its redirect, an access-list
 * refusal) and a name nobody serves once as unserved, with the access log's own time, and the access log writes the
 * same answers under the same site; the count continues over a restart of the stores; a full queue, a failing disk
 * and stopped stores never slow a request and the dashboard says what they cost; a zone's questions are counted once
 * each and a zone transfer not at all; traffic_read and dns_zone_queries_read read them, every site at once for an
 * administrator, refusing a wrong argument by its name and a customer what he does not manage.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class TrafficCountingJourneyTest extends HohenheimHttpTestBase {

    private static final Duration WAIT = Duration.ofSeconds(15);

    /** A request answered at normal speed: the proxy's own work is milliseconds, so a second means it waited. */
    private static final Duration PROMPT = Duration.ofSeconds(1);

    private static final String FIREFOX = "Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0";

    private static final int UNSERVED = AccessLog.NO_SITE;

    @Test
    void everyAnswerOfASiteIsCountedOnceAndReadByTool() throws Exception {
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        Instant began = Now.instant();
        try {
            countUnder(cleanUp, "sites-" + tag);
            Path log = accessLogIn(cleanUp, tag);
            HttpServer upstream = upstream(cleanUp);
            int served = site(cleanUp, "served-" + tag, upstream, null);
            int pathed = site(cleanUp, "pathed-" + tag, upstream, "/app");
            int guarded = site(cleanUp, "guarded-" + tag, upstream, null);
            int timed = site(cleanUp, "timed-" + tag, upstream, null);
            int quiet = site(cleanUp, "quiet-" + tag, upstream, null);
            redirect(cleanUp, served, "/old");
            refuseThisMachine(cleanUp, guarded);
            ProxyServer proxy = ProxyTestSupport.startProxy();
            cleanUp.push(proxy::stop);
            int port = ProxyTestSupport.httpPort(proxy);

            // 1. Each answer, the ones given before the upstream too, is counted once under the site it belongs to.
            assertThat(ask(port, hostOf(served), "/ok", "User-Agent: " + FIREFOX)).as("step 1: a page")
                .contains(" 200 ");
            assertThat(ask(port, hostOf(served), "/missing")).as("step 1: the upstream's 404").contains(" 404 ");
            assertThat(ask(port, hostOf(served), "/old")).as("step 1: the site's redirect").containsPattern(" 30\\d ");
            assertThat(ask(port, hostOf(pathed), "/elsewhere")).as("step 1: a path no route serves").contains(" 404 ");
            assertThat(ask(port, hostOf(guarded), "/")).as("step 1: the access list refuses")
                .containsPattern(" 40\\d ").doesNotContain("counted");
            String nobody = "nobody-" + tag + ".traffic.test";
            String unknownHosts = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS);
            cleanUp.push(() -> Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS, unknownHosts));
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS,
                UnknownHostAnswer.NOT_FOUND.storageKey());
            assertThat(ask(port, nobody, "/")).as("step 1: the name nobody serves").contains(" 404 ");
            assertThat(ask(port, hostOf(timed), "/ok")).as("step 1: a timed page").contains(" 200 ");

            TrafficTotals servedTotals = counted(HohenheimTraffic.sourceOf(served), 3, began);
            assertThat(servedTotals.requests()).as("step 1: the site's three answers, once each").isEqualTo(3);
            assertThat(classes(servedTotals)).as("step 1: by status class").isEqualTo("2xx 1, 3xx 1, 4xx 1");
            assertThat(servedTotals.bytesSent()).as("step 1: what it sent").isPositive();
            assertThat(counted(HohenheimTraffic.sourceOf(pathed), 1, began).requests())
                .as("step 1: the wrong path of a known name, under the site serving the name").isEqualTo(1);
            assertThat(classes(counted(HohenheimTraffic.sourceOf(guarded), 1, began)))
                .as("step 1: the refusal, under the guarded site").isEqualTo("4xx 1");
            assertThat(unservedAsks(nobody, began)).as("step 1: the name nobody serves, once").isEqualTo(1);

            // 1b. The access log writes the same answers under the same sites, each with the time it took: the answers
            //     given before the upstream too, and the name nobody serves under no site.
            assertThat(loggedClasses(log, served, 3)).as("step 1b: the site's three answers")
                .containsExactly("2xx", "3xx", "4xx");
            assertThat(loggedClasses(log, pathed, 1)).as("step 1b: the wrong path of a known name")
                .containsExactly("4xx");
            assertThat(loggedClasses(log, guarded, 1)).as("step 1b: the refusal").containsExactly("4xx");
            assertThat(loggedClasses(log, UNSERVED, 1)).as("step 1b: the name nobody serves").containsExactly("4xx");

            // 2. The time counted is the access log's own: the line's time_ms and the site's one request agree.
            TrafficTotals timedTotals = counted(HohenheimTraffic.sourceOf(timed), 1, began);
            long logged = Objects.requireNonNull(logged(log, timed, 1).getFirst().millis());
            // time_ms is the reading cut to whole milliseconds; the median is within the summary's relative error.
            assertThat(Objects.requireNonNull(timedTotals.medianMillis())).as("step 2: one stopwatch, two readers")
                .isBetween(logged * 0.95, (logged + 1) * 1.05);

            // 3. The stores stop as the process does and open again: the count continues from what they wrote. The
            //    request after the restart comes from a tool no family names, which it names itself.
            HohenheimTraffic.stop();
            HohenheimTraffic.start();
            ask(port, hostOf(served), "/ok", "User-Agent: Proteus-Client/3");
            assertThat(counted(HohenheimTraffic.sourceOf(served), 4, began).requests())
                .as("step 3: the count continues over the restart").isEqualTo(4);

            // 4. An administrator reads it by tool: the totals, the steps and a top table.
            URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
            McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
            AccessContext operator = TenantConduits.operator();
            String servedCode = HohenheimMcpTest.code(SiteModel.MODEL_ID, served, operator);
            String guardedCode = HohenheimMcpTest.code(SiteModel.MODEL_ID, guarded, operator);
            McpHttpSession.Answer day = admin.call("traffic_read", Map.of("site", servedCode));
            assertThat(day.error()).as("step 4: " + day.text()).isFalse();
            assertThat(day.text()).as("step 4: the day's totals").contains("requests: 4;")
                .contains("answered: 2xx 2, 3xx 1, 4xx 1").contains("per hour").contains("visitors: 1");
            McpHttpSession.Answer pages = admin.call("traffic_read", Map.of("site", servedCode, "period", "hour",
                "top", "pages"));
            assertThat(pages.error()).as("step 4: " + pages.text()).isFalse();
            assertThat(pages.text()).as("step 4: the hour per minute, its pages").contains("per minute")
                .contains("/ok").contains("/missing").contains("/old");
            assertThat(pages.text()).as("step 4: the minutes without a request are left out, and it says so")
                .contains("steps without requests are left out").doesNotContainPattern("(?m)^\\S+: 0$");
            McpHttpSession.Answer browsers = admin.call("traffic_read", Map.of("site", servedCode, "top",
                "browsers"));
            assertThat(browsers.text()).as("step 4: the browsers, a tool no family names by the name it sent")
                .contains("Firefox").contains("Proteus-Client").doesNotContain("agent_").doesNotContain("tool:");
            assertThat(admin.call("traffic_read", Map.of("site", servedCode, "top", "visitors")).text())
                .as("step 4: the visitors in full").contains("127.0.0.1");
            McpHttpSession.Answer none = admin.call("traffic_read", Map.of("site",
                HohenheimMcpTest.code(SiteModel.MODEL_ID, quiet, operator)));
            assertThat(none.text()).as("step 4: a span without a request says so in one line")
                .contains("steps: none, no requests in this span").doesNotContain("steps, each");
            McpHttpSession.Answer everySite = admin.call("traffic_read", Map.of());
            assertThat(everySite.error()).as("step 4: " + everySite.text()).isFalse();
            assertThat(everySite.text()).as("step 4: every site at once, the name nobody serves among them")
                .contains("requests: 8;");

            // 5. A wrong call is refused by the argument it names.
            McpHttpSession.Answer year = admin.call("traffic_read", Map.of("site", servedCode, "period", "year"));
            assertThat(year.error()).as("step 5: " + year.text()).isTrue();
            assertThat(year.text()).as("step 5: the refusal names it").contains("period");
            McpHttpSession.Answer names = admin.call("traffic_read", Map.of("site", servedCode, "top", "names"));
            assertThat(names.error()).as("step 5: a zone's table on a site: " + names.text()).isTrue();
            assertThat(names.text()).as("step 5: the refusal names it").contains("top");

            // 6. A customer who manages one site reads it, and is refused another.
            UUID customer = ApiSupport.user("traffic-" + tag + "@hohenheim.local", "Traffic customer");
            cleanUp.push(() -> Models.get(UserModel.class).delete(customer));
            RecordGrants.grant(GrantSubjectType.USER, customer, SiteModel.MODEL_ID, served,
                HohenheimCapabilities.MANAGE, true);
            McpHttpSession own = HohenheimMcpTest.presetSession(server, endpoint, customer, "Traffic customer");
            McpHttpSession.Answer his = own.call("traffic_read", Map.of("site", servedCode));
            assertThat(his.error()).as("step 6: " + his.text()).isFalse();
            assertThat(his.text()).as("step 6: his own site").contains("requests: 4;");
            McpHttpSession.Answer foreign = own.call("traffic_read", Map.of("site", guardedCode));
            assertThat(foreign.error()).as("step 6: " + foreign.text()).isTrue();
            assertThat(foreign.text()).as("step 6: nothing of it").doesNotContain("requests:");
            McpHttpSession.Answer every = own.call("traffic_read", Map.of());
            assertThat(every.error()).as("step 6: every site is an administrator's read: " + every.text()).isTrue();
            assertThat(every.text()).as("step 6: nothing of it").doesNotContain("requests:");
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    @Test
    void countingNeverSlowsARequestAndTheDashboardSaysWhatItCost() throws Exception {
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        Instant began = Now.instant();
        try {
            countUnder(cleanUp, "steady-" + tag);
            HttpServer upstream = upstream(cleanUp);
            int steady = site(cleanUp, "steady-" + tag, upstream, null);
            ProxyServer proxy = ProxyTestSupport.startProxy();
            cleanUp.push(proxy::stop);
            int port = ProxyTestSupport.httpPort(proxy);
            String source = HohenheimTraffic.sourceOf(steady);
            promptly(port, hostOf(steady), "step 1: the first request");
            counted(source, 1, began);

            // 1. The counter stalls (its lock held, as a disk that hangs a write holds it) and its queue fills: the
            //    request is answered at once, its hit dropped and counted, and the dashboard says so.
            TrafficStore store = Objects.requireNonNull(HohenheimTraffic.store(HohenheimTraffic.Counted.WEB));
            String filler = "filler-" + tag;
            long droppedBefore = HohenheimTraffic.droppedHits();
            int offered = HohenheimTraffic.QUEUE_CAPACITY + 10;
            int accepted = 0;
            synchronized (store) {
                for (int i = 0; i < offered; i++) {
                    accepted += store.offer(new TrafficHit(Now.instant(), filler, "filler.test", "/", "GET", 200, 0,
                        -1, 0, "192.0.2.1", null, null)) ? 1 : 0;
                }
                promptly(port, hostOf(steady), "step 1: a request while the queue is full");
                long drops = offered - accepted + 1L;
                // Counted at the exchange's completion, which may follow the answer by a moment: still stalled then.
                Poll.value("step 1: the request's hit is dropped too", WAIT,
                    () -> HohenheimTraffic.droppedHits() - droppedBefore >= drops ? drops : null);
            }
            assertThat(accepted).as("step 1: the queue was full").isLessThan(offered);
            assertThat(counted(filler, accepted, began).requests()).as("step 1: what was queued is counted")
                .isEqualTo(accepted);
            assertThat(counted(source, 1, began).requests()).as("step 1: the dropped request is not").isEqualTo(1);
            assertThat(said()).as("step 1: the dashboard tells").contains("traffic_dropped");

            // 2. The disk fails (a file where the folder should be): the request is answered at once, counted in
            //    memory, and the dashboard says the totals cannot be written; a working place again clears it.
            Path broken = Files.createTempDirectory("hh-traffic-broken-" + tag);
            Files.writeString(broken.resolve("traffic"), "a file where the folder should be");
            String working = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, broken.toString());
            promptly(port, hostOf(steady), "step 2: a request while the disk fails");
            assertThat(counted(source, 1, began).requests()).as("step 2: counted in memory").isEqualTo(1);
            Poll.value("step 2: the failed write is told", WAIT, () -> Objects.requireNonNull(
                HohenheimTraffic.store(HohenheimTraffic.Counted.WEB)).writeProblem());
            assertThat(said()).as("step 2: the dashboard tells").contains("traffic_unwritten");
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, working);
            assertThat(counted(source, 1, began).requests()).as("step 2: the working place counts on").isEqualTo(1);
            assertThat(Objects.requireNonNull(HohenheimTraffic.store(HohenheimTraffic.Counted.WEB)).writeProblem())
                .as("step 2: nothing left unwritten").isNull();

            // 3. The stores stopped, as the process ending stops them: the request is answered at once and its hit
            //    dropped and counted.
            HohenheimTraffic.stop();
            long beforeStop = HohenheimTraffic.droppedHits();
            promptly(port, hostOf(steady), "step 3: a request after the stop");
            Poll.value("step 3: its hit dropped and counted", WAIT,
                () -> HohenheimTraffic.droppedHits() == beforeStop + 1 ? beforeStop + 1 : null);
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    @Test
    void aZonesQuestionsAreCountedOnceAndReadByTool() throws Exception {
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        Instant began = Now.instant();
        try {
            countUnder(cleanUp, "dns-" + tag);
            String origin = "traffic-" + tag + ".example";
            // Runs last: the zone store forgets the zone once its rows are gone.
            cleanUp.push(DnsZoneStore.INSTANCE::reload);
            int zone = DnsFixtures.createZone(origin);
            cleanUp.push(() -> HardDeletes.byId(Models.get(DnsZoneModel.class), zone));
            int www = DnsFixtures.record(zone, "www", DnsRecordModel.TYPE_A, "192.0.2.10");
            cleanUp.push(() -> HardDeletes.byId(Models.get(DnsRecordModel.class), www));
            DnsZoneStore.INSTANCE.reload();
            DnsServer dns = new DnsServer();
            dns.start("127.0.0.1", 0);
            cleanUp.push(dns::stop);
            int port = dns.getUdpPort();

            // 1. A zone transfer is no question and is not counted; two answers and a missing name are, once each.
            DnsFixtures.tcpQuery(port, origin, Type.AXFR);
            DnsFixtures.udpQuery(port, "www." + origin, Type.A, false, false);
            DnsFixtures.tcpQuery(port, "www." + origin, Type.A);
            DnsFixtures.udpQuery(port, "nope." + origin, Type.A, false, false);
            String source = HohenheimTraffic.zoneSource(zone);
            TrafficTotals totals = Poll.value("step 1: the zone's three questions", WAIT, () -> {
                TrafficTotals read = totalsOf(HohenheimTraffic.Counted.DNS, source, began);
                return read.requests() >= 3 ? read : null;
            });
            assertThat(totals.requests()).as("step 1: once each, the transfer left out").isEqualTo(3);
            assertThat(classes(totals)).as("step 1: answered and missing").isEqualTo("2xx 2, 4xx 1");

            // 2. An administrator reads them by tool, the zone named by its origin: the names, the missing one and
            //    the resolver.
            URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
            McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
            McpHttpSession.Answer asked = admin.call("dns_zone_queries_read", Map.of("dns_zone", origin,
                "top", "names"));
            assertThat(asked.error()).as("step 2: " + asked.text()).isFalse();
            assertThat(asked.text()).as("step 2: the totals and the names asked").contains("questions: 3;")
                .contains("answered: 2xx 2, 4xx 1").contains("www." + origin + " A");
            assertThat(admin.call("dns_zone_queries_read", Map.of("dns_zone", origin, "top", "missing_names"))
                .text()).as("step 2: the missing name").contains("nope." + origin + " A");
            assertThat(admin.call("dns_zone_queries_read", Map.of("dns_zone", origin, "top", "resolvers")).text())
                .as("step 2: the resolver").contains("127.0.0.1");

            // 3. A site's table on a zone is refused by its name; a customer is told nothing of the zone.
            McpHttpSession.Answer pages = admin.call("dns_zone_queries_read", Map.of("dns_zone", origin,
                "top", "pages"));
            assertThat(pages.error()).as("step 3: " + pages.text()).isTrue();
            assertThat(pages.text()).as("step 3: the refusal names it").contains("top");
            UUID customer = ApiSupport.user("traffic-dns-" + tag + "@hohenheim.local", "Zone customer");
            cleanUp.push(() -> Models.get(UserModel.class).delete(customer));
            McpHttpSession.Answer his = HohenheimMcpTest.presetSession(server, endpoint, customer, "Zone customer")
                .call("dns_zone_queries_read", Map.of("dns_zone", origin));
            assertThat(his.error()).as("step 3: " + his.text()).isTrue();
            assertThat(his.text()).as("step 3: nothing of it").doesNotContain("questions:");
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    /** Points the data path, and so the stores, at a folder of the test's own; the stores count again at the end. */
    static void countUnder(Deque<Runnable> cleanUp, String name) throws Exception {
        String dataPath = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        cleanUp.push(() -> {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, dataPath);
            HohenheimTraffic.start();
        });
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.DATA_PATH,
            Files.createTempDirectory("hh-traffic-" + name).toString());
    }

    /** @return the access log the proxy writes for this test, switched on */
    private static Path accessLogIn(Deque<Runnable> cleanUp, String tag) throws Exception {
        String path = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Logging.ACCESS_PATH);
        Boolean on = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Logging.ACCESS_TO_FILE);
        cleanUp.push(() -> {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Logging.ACCESS_PATH, path);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Logging.ACCESS_TO_FILE, on);
        });
        Path log = Files.createTempDirectory("hh-traffic-log-" + tag).resolve("access.log");
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Logging.ACCESS_PATH, log.toString());
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Logging.ACCESS_TO_FILE, true);
        return log;
    }

    /** @return an upstream answering 404 for /missing and a few bytes for anything else */
    private static HttpServer upstream(Deque<Runnable> cleanUp) throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            byte[] body = "counted".getBytes(StandardCharsets.UTF_8);
            boolean missing = exchange.getRequestURI().getPath().equals("/missing");
            exchange.sendResponseHeaders(missing ? 404 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        cleanUp.push(() -> upstream.stop(0));
        return upstream;
    }

    /**
     * @param path the one path its address serves, null for all
     * @return the id of a site forwarding to the upstream at one address of its own
     */
    private static int site(Deque<Runnable> cleanUp, String slug, HttpServer upstream, String path) {
        Row site = ProxyTestSupport.setupSite("hohenheim:address", "Traffic " + slug, slug,
            Map.of("forward_host", "127.0.0.1", "forward_port", upstream.getAddress().getPort()));
        int id = site.get(SiteModel.ID);
        cleanUp.push(() -> HardDeletes.byId(Models.get(SiteModel.class), id));
        ProxyTestSupport.addDomain(site, hostOf(id), "exact", path, false);
        return id;
    }

    private static String hostOf(int siteId) {
        return "site" + siteId + ".traffic.test";
    }

    /** A redirect of the site from the path to its root, answered before the upstream. */
    private static void redirect(Deque<Runnable> cleanUp, int siteId, String from) {
        int id = HohenheimDataToolsTest.record(SiteRedirectModel.class, cleanUp, row -> {
            row.set(SiteRedirectModel.SITE_ID, siteId);
            row.set(SiteRedirectModel.FROM_PATH, from);
            row.set(SiteRedirectModel.MATCH, SiteRedirectModel.MATCH_EXACT);
            row.set(SiteRedirectModel.TARGET, "/");
        });
        assertThat(id).as("the redirect is stored").isPositive();
    }

    /** An access list on the site that every address of this machine fails. */
    private static void refuseThisMachine(Deque<Runnable> cleanUp, int siteId) {
        int list = HohenheimDataToolsTest.record(AccessListModel.class, cleanUp, row -> {
            row.set(AccessListModel.NAME, "Traffic refusal " + siteId);
            row.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ALL);
        });
        HohenheimDataToolsTest.record(AccessRuleModel.class, cleanUp, row -> {
            row.set(AccessRuleModel.ACCESS_LIST_ID, list);
            row.set(AccessRuleModel.TYPE, AccessRuleModel.TYPE_IP_DENY);
            row.set(AccessRuleModel.DATA, new LinkedHashMap<>(Map.of("network", "127.0.0.0/8")));
            row.set(AccessRuleModel.ENABLED, true);
        });
        Row site = Models.get(SiteModel.class).findById(siteId);
        site.set(SiteModel.ACCESS_LIST_ID, list);
        Models.get(SiteModel.class).save(site);
        cleanUp.push(() -> {
            Row unguarded = Models.get(SiteModel.class).findById(siteId);
            unguarded.set(SiteModel.ACCESS_LIST_ID, null);
            Models.get(SiteModel.class).save(unguarded);
        });
    }

    private static String ask(int port, String host, String path, String... headers) throws Exception {
        return ProxyTestSupport.rawRequest(port, host, path, headers);
    }

    /** Asks the site's root and requires an answer at normal speed. */
    private static void promptly(int port, String host, String step) throws Exception {
        Now.Stopwatch took = Now.stopwatch();
        String answer = ask(port, host, "/");
        Duration elapsed = Duration.ofNanos(took.realNanos());
        System.out.println("TRAFFIC " + step + ": answered in " + elapsed.toMillis() + " ms");
        assertThat(answer).as(step + ": answered").contains(" 200 ");
        assertThat(elapsed).as(step + ": at normal speed").isLessThan(PROMPT);
    }

    /** @return the source's totals since the test began, once they hold at least this many requests */
    static TrafficTotals counted(String source, long requests, Instant began) {
        return Poll.value(source + " counts " + requests, WAIT, () -> {
            TrafficTotals read = totalsOf(HohenheimTraffic.Counted.WEB, source, began);
            return read.requests() >= requests ? read : null;
        });
    }

    static TrafficTotals totalsOf(HohenheimTraffic.Counted counted, String source, Instant began) {
        TrafficStore store = Objects.requireNonNull(HohenheimTraffic.store(counted), "the stores count");
        return store.reader().totals(TrafficQuery.of(source, began.minusSeconds(60), Now.instant().plusSeconds(60),
            TrafficGranularity.MINUTE));
    }

    /** @return how often the name nobody serves was asked, from the unserved source's table of names */
    private static long unservedAsks(String name, Instant began) {
        return Poll.value(name + " is counted as unserved", WAIT, () -> {
            TrafficStore store = Objects.requireNonNull(HohenheimTraffic.store(HohenheimTraffic.Counted.WEB));
            DataTable hosts = store.reader().top(TrafficQuery.of(HohenheimTraffic.UNSERVED, began.minusSeconds(60),
                Now.instant().plusSeconds(60), TrafficGranularity.MINUTE), TopRead.busiest(TrafficTable.HOSTS,
                TopRead.MAX_LIMIT), null, null);
            return hosts.lines().stream().filter(line -> line.groups().getLast().key().equals(name)).findFirst()
                .map(DataTable.Line::records).orElse(null);
        });
    }

    /** @return the status classes that answered anything, "2xx 1, 4xx 2" */
    private static String classes(TrafficTotals totals) {
        List<String> said = new ArrayList<>();
        totals.statusClasses().entrySet().stream().filter(entry -> entry.getValue() > 0)
            .sorted(Map.Entry.comparingByKey()).forEach(entry -> said.add(entry.getKey().storageKey() + " "
                + entry.getValue()));
        return String.join(", ", said);
    }

    /** @return the status classes of the site's lines in the access log, sorted, once it holds this many */
    private static List<String> loggedClasses(Path log, int siteId, int lines) {
        return logged(log, siteId, lines).stream().map(line -> StatusClass.of(line.status()).storageKey()).sorted()
            .toList();
    }

    /**
     * @param siteId the site, {@link #UNSERVED} for a name nobody serves
     * @return the site's lines in the access log that carry their time taken, once it holds this many
     */
    private static List<AccessLogLine> logged(Path log, int siteId, int lines) {
        return Poll.value("the access log holds " + lines + " lines of site " + siteId, WAIT, () -> {
            try {
                List<AccessLogLine> found = new ArrayList<>();
                for (String raw : Files.exists(log) ? Files.readAllLines(log) : List.<String>of()) {
                    AccessLogLine line = AccessLogLine.parse(raw);
                    if (line != null && Integer.valueOf(siteId).equals(line.siteId()) && line.millis() != null) {
                        found.add(line);
                    }
                }
                return found.size() >= lines ? found : null;
            } catch (Exception unreadable) {
                return null;
            }
        });
    }

    /** @return the keys of the dashboard's traffic items */
    private static List<String> said() {
        List<AttentionItem> items = new ArrayList<>();
        AttentionCollector.traffic(items);
        return items.stream().map(item -> item.title().key()).toList();
    }
}
