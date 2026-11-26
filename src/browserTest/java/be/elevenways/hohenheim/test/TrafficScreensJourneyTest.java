package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.dns.DnsServer;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.hohenheim.site.UnknownHostAnswer;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.DatasourceProbe;
import be.elevenways.zenit.traffic.server.TrafficStore;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.Type;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The traffic screens of board FO124 through real requests: a site's Traffic tab shows the requests its site answered
 * in figures, a chart and its top tables, switches its period and steps back and forth by links, and is read by a
 * customer who manages the site, never another's or the server's report; the server's Traffic report ranks every site
 * by name and lists the names nobody serves, with Settings a link to the Traffic settings; a DNS zone's Queries tab
 * shows its questions, its names and who asked; each screen's database cost and file reads stay flat as sites and
 * rows grow.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class TrafficScreensJourneyTest extends HohenheimTestBase {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static final String FIREFOX = "Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0";

    @Test
    void aSiteManagerReadsHisSitesTrafficByPeriod() throws Exception {
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        Instant began = Now.instant();
        try (SiteAccessLogJourneyTest.Fixture fixture = SiteAccessLogJourneyTest.Fixture.start("traffic-tab")) {
            TrafficCountingJourneyTest.countUnder(cleanUp, "tab-" + fixture.tag);
            fixture.request(fixture.siteA, "/ok", "User-Agent: " + FIREFOX, "Referer: https://from.example/page");
            fixture.request(fixture.siteA, "/missing");
            fixture.request(fixture.siteA, "/items/12345/view", "User-Agent: Proteus-Client/3");
            fixture.request(fixture.siteB, "/other-site");
            TrafficCountingJourneyTest.counted(HohenheimTraffic.sourceOf(fixture.siteA), 3, began);

            // 1. The tab draws the site's day: its figures, the requests per hour by status, its tables, each row a
            //    name a person reads; never the other site's path.
            page.setViewportSize(1280, 800);
            navigateToApp(tab(HohenheimSlugs.ADMIN, fixture.siteA, ""));
            waitForHydration();
            assertThat(page.locator("[data-hh-traffic-figure='requests']").textContent()).as("step 1: requests")
                .contains("3");
            assertThat(page.locator("[data-hh-traffic-chart='steps'] pl-chart").count()).as("step 1: the chart")
                .isEqualTo(1);
            String pages = page.locator("[data-hh-traffic-table='pages']").textContent();
            assertThat(pages).as("step 1: the top pages, an id counted as :id").contains("/ok")
                .contains("/items/:id/view").doesNotContain("/other-site");
            assertThat(page.locator("[data-hh-traffic-table='referrers']").textContent())
                .as("step 1: the referrer's host").contains("from.example").doesNotContain("/page");
            assertThat(page.locator("[data-hh-traffic-table='browsers']").textContent())
                .as("step 1: browsers by name, a tool no family names by its own").contains("Firefox")
                .contains("Proteus-Client").doesNotContain("agent_").doesNotContain("tool:");
            assertThat(page.locator("[data-hh-traffic-table='statuses']").textContent()).as("step 1: statuses")
                .contains("404");
            assertThat(page.locator("[data-hh-traffic-table='visitors']").textContent()).as("step 1: whole address")
                .contains("127.0.0.1");
            assertThat(page.locator("[data-hh-traffic-table='pages'] a").first().getAttribute("href"))
                .as("step 1: a path leads to its access log lines")
                .contains("/" + HohenheimSlugs.Tab.ACCESS_LOG).contains("path=");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/site-traffic-tab.png"))
                .setFullPage(true));

            // 2. The period switch: an hour per minute, and the hour before, which counted nothing and says so.
            page.locator("[data-hh-traffic-periods] pl-nav-item[href*='period=hour'] a").first().click();
            page.waitForURL(url -> url.contains("period=hour"), new Page.WaitForURLOptions()
                .setTimeout(WAIT.toMillis()));
            waitForHydration();
            assertThat(page.locator("[data-hh-traffic-chart='steps'] pl-chart").count()).as("step 2: the hour")
                .isEqualTo(1);
            assertThat(page.locator("[data-hh-traffic-next] [disabled], [data-hh-traffic-next][disabled]").count())
                .as("step 2: no next while the span ends now").isPositive();
            page.locator("[data-hh-traffic-previous] a").first().click();
            page.waitForURL(url -> url.contains("until="), new Page.WaitForURLOptions().setTimeout(WAIT.toMillis()));
            waitForHydration();
            assertThat(page.locator("[data-hh-traffic-empty]").textContent()).as("step 2: the hour before")
                .contains("No requests in this period");
            assertThat(page.locator("[data-hh-traffic-next] a").getAttribute("href")).as("step 2: and back")
                .contains("period=hour");

            // 3. A customer who manages the site reads its tab on /manage; another site's tab and the server's
            //    report are refused him.
            UUID customer = ApiSupport.user("traffic-tab-" + fixture.tag + "@hohenheim.local", "Traffic customer");
            cleanUp.push(() -> Models.get(UserModel.class).delete(customer));
            RecordGrants.grant(GrantSubjectType.USER, customer, SiteModel.MODEL_ID, fixture.siteA,
                HohenheimCapabilities.MANAGE, true);
            String session = sessionFor(customer).token();
            navigateToAppAs(tab(HohenheimSlugs.MANAGE, fixture.siteA, ""), session);
            waitForHydration();
            assertThat(page.locator("[data-hh-traffic-figure='requests']").textContent())
                .as("step 3: his site's requests").contains("3");
            HttpResponse<String> foreign = httpGet(tab(HohenheimSlugs.MANAGE, fixture.siteB, ""), session);
            assertThat(foreign.statusCode()).as("step 3: another site's tab is no page of his").isIn(403, 404);
            assertThat(foreign.body()).as("step 3: and shows nothing of it").doesNotContain("/other-site");
            assertThat(httpGet(CmsRoutes.list(HohenheimSlugs.ADMIN, HohenheimSlugs.TRAFFIC).toUrl(), session)
                .statusCode()).as("step 3: the server's report is no page of his").isNotEqualTo(200);

            // 4. The tab's cost does not grow with the rows it lists: one query set and the same files read for a
            //    site of few requests and one of many paths and addresses.
            for (int i = 0; i < 30; i++) fixture.request(fixture.siteB, "/many-" + i);
            TrafficCountingJourneyTest.counted(HohenheimTraffic.sourceOf(fixture.siteB), 31, began);
            DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Datasources.getDefault());
            probe.requireFlat(() -> adminFetched(tab(HohenheimSlugs.ADMIN, fixture.siteA, "")),
                () -> adminFetched(tab(HohenheimSlugs.ADMIN, fixture.siteB, "")),
                "step 4: few rows or many, one query set");
            long few = fileReads(() -> adminFetched(tab(HohenheimSlugs.ADMIN, fixture.siteA, "?period=week")));
            assertThat(fileReads(() -> adminFetched(tab(HohenheimSlugs.ADMIN, fixture.siteB, "?period=week"))))
                .as("step 4: the same files read for many rows").isEqualTo(few);
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    @Test
    void anAdministratorReadsEverySitesTrafficOnTheReport() throws Exception {
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        Instant began = Now.instant();
        try (SiteAccessLogJourneyTest.Fixture fixture = SiteAccessLogJourneyTest.Fixture.start("traffic-report")) {
            TrafficCountingJourneyTest.countUnder(cleanUp, "report-" + fixture.tag);
            String unknownHosts = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS);
            cleanUp.push(() -> Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS, unknownHosts));
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.UNKNOWN_HOSTS,
                UnknownHostAnswer.NOT_FOUND.storageKey());
            fixture.request(fixture.siteA, "/ok");
            fixture.request(fixture.siteA, "/ok");
            fixture.request(fixture.siteB, "/b-page");
            String nobody = "nobody-" + fixture.tag + ".traffic.test";
            ProxyTestSupport.rawRequest(ProxyTestSupport.httpPort(fixture.proxy), nobody, "/");
            TrafficCountingJourneyTest.counted(HohenheimTraffic.UNSERVED, 1, began);
            TrafficCountingJourneyTest.counted(HohenheimTraffic.sourceOf(fixture.siteA), 2, began);
            String report = CmsRoutes.list(HohenheimSlugs.ADMIN, HohenheimSlugs.TRAFFIC).toUrl();

            // 1. The report: every site ranked by its name, the top pages across sites with their site, the names
            //    nobody serves; its tabs Report and Settings, which opens the settings page on the Traffic group.
            page.setViewportSize(1280, 800);
            navigateToApp(report);
            waitForHydration();
            String nameA = "Access a " + fixture.tag;
            assertThat(page.locator("[data-hh-traffic-table='sites_by_requests']").textContent())
                .as("step 1: the sites by name").contains(nameA).contains("Access b " + fixture.tag)
                .contains("Names nobody serves");
            assertThat(page.locator("[data-hh-traffic-table='sites_by_requests'] a").first().getAttribute("href"))
                .as("step 1: a site leads to its Traffic tab").contains("/" + HohenheimSlugs.Tab.TRAFFIC);
            assertThat(page.locator("[data-hh-traffic-table='pages']").textContent())
                .as("step 1: the top pages across sites").contains(nameA).contains("/ok");
            assertThat(page.locator("[data-hh-traffic-table='unserved_names']").textContent())
                .as("step 1: the name nobody serves").contains(nobody);
            List<String> tabs = page.locator("[data-hh-traffic-tabs] pl-nav-item").allTextContents();
            assertThat(tabs).as("step 1: Report and Settings, no Activity yet").hasSize(2);
            assertThat(tabs.get(0)).as("step 1: Report first").contains("Report");
            assertThat(tabs.get(1)).as("step 1: then Settings").contains("Settings");
            assertThat(page.locator("[data-hh-traffic-tabs] pl-nav-item").last().getAttribute("href"))
                .as("step 1: Settings is the settings page's Traffic group").contains("/settings")
                .contains("traffic");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/traffic-report.png"))
                .setFullPage(true));

            // 2. Its cost stays flat as sites and their traffic grow: the sites' names are one query.
            DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Datasources.getDefault());
            int quiet = probe.warmedRoundTrips(() -> adminFetched(report));
            for (String letter : List.of("c", "d", "e")) {
                int grown = fixture.site(letter);
                fixture.request(grown, "/grown");
                TrafficCountingJourneyTest.counted(HohenheimTraffic.sourceOf(grown), 1, began);
            }
            assertThat(probe.warmedRoundTrips(() -> adminFetched(report))).as("step 2: more sites, one query set")
                .isEqualTo(quiet);

            // 3. An hour that counted nothing says so in one line.
            HttpResponse<String> quietHour = adminGet(report + "?period=hour&until=2000-01-03T10:00");
            assertThat(quietHour.statusCode()).as("step 3: an empty span is a page").isEqualTo(200);
            assertThat(quietHour.body()).as("step 3: and says nothing was asked").contains("No requests in this period");
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    @Test
    void anAdministratorReadsAZonesQuestionsOnItsQueriesTab() throws Exception {
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        Instant began = Now.instant();
        String tag = HohenheimMcpTest.letters();
        try {
            TrafficCountingJourneyTest.countUnder(cleanUp, "queries-" + tag);
            String origin = "queries-" + tag + ".example";
            cleanUp.push(DnsZoneStore.INSTANCE::reload);
            int zone = DnsFixtures.createZone(origin);
            cleanUp.push(() -> HardDeletes.byId(Models.get(DnsZoneModel.class), zone));
            int www = DnsFixtures.record(zone, "www", DnsRecordModel.TYPE_A, "192.0.2.10");
            cleanUp.push(() -> HardDeletes.byId(Models.get(DnsRecordModel.class), www));
            DnsZoneStore.INSTANCE.reload();
            DnsServer dns = new DnsServer();
            dns.start("127.0.0.1", 0);
            cleanUp.push(dns::stop);
            DnsFixtures.udpQuery(dns.getUdpPort(), "www." + origin, Type.A, false, false);
            DnsFixtures.udpQuery(dns.getUdpPort(), "www." + origin, Type.A, false, false);
            DnsFixtures.udpQuery(dns.getUdpPort(), "nope." + origin, Type.A, false, false);
            Poll.value("the zone's three questions", WAIT, () -> TrafficCountingJourneyTest.totalsOf(
                HohenheimTraffic.Counted.DNS, HohenheimTraffic.zoneSource(zone), began).requests() >= 3 ? 3 : null);
            String queries = CmsRoutes.subpage(HohenheimSlugs.ADMIN, HohenheimSlugs.DNS_ZONES, zone,
                HohenheimSlugs.Tab.QUERIES).toUrl();

            // 1. The tab: its questions and outcomes, the names asked with their type, the missing one, who asked.
            page.setViewportSize(1280, 800);
            navigateToApp(queries);
            waitForHydration();
            assertThat(page.locator("[data-hh-traffic-figure='questions']").textContent()).as("step 1: questions")
                .contains("3");
            assertThat(page.locator("[data-hh-traffic-figure='answered']").textContent()).as("step 1: answered")
                .contains("2");
            assertThat(page.locator("[data-hh-traffic-figure='no_such_name']").textContent())
                .as("step 1: no such name").contains("1");
            String names = page.locator("[data-hh-traffic-table='names']").textContent();
            assertThat(names).as("step 1: the name and its type apart").contains("www." + origin)
                .doesNotContain("www." + origin + " A");
            assertThat(page.locator("[data-hh-traffic-table='missing_names']").textContent())
                .as("step 1: the missing name").contains("nope." + origin);
            assertThat(page.locator("[data-hh-traffic-table='resolvers']").textContent()).as("step 1: who asked")
                .contains("127.0.0.1");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/dns-zone-queries.png"))
                .setFullPage(true));

            // 2. A customer is told nothing of the zone's questions.
            UUID customer = ApiSupport.user("traffic-queries-" + tag + "@hohenheim.local", "Zone customer");
            cleanUp.push(() -> Models.get(UserModel.class).delete(customer));
            HttpResponse<String> his = httpGet(queries, sessionFor(customer).token());
            assertThat(his.statusCode()).as("step 2: no page of his").isNotEqualTo(200);
            assertThat(his.body()).as("step 2: nothing of it").doesNotContain("nope." + origin);
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    /** @return a site's Traffic tab on a panel, with this query */
    private static String tab(String panel, int siteId, String query) {
        return CmsRoutes.subpage(panel, HohenheimSlugs.SITES, siteId, HohenheimSlugs.Tab.TRAFFIC).toUrl() + query;
    }

    /** @return the files the web store opened while the work ran */
    private static long fileReads(Runnable work) {
        TrafficStore store = Objects.requireNonNull(HohenheimTraffic.store(HohenheimTraffic.Counted.WEB));
        long before = store.fileReads();
        work.run();
        return store.fileReads() - before;
    }
}
