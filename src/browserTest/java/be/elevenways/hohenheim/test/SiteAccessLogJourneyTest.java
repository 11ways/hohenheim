package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.test.support.DatasourceProbe;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import com.sun.net.httpserver.HttpServer;
import be.elevenways.protoblast.testsupport.text.InOrder;
import be.elevenways.protoblast.testsupport.time.Poll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * A site's Access log, as the board's tab draws it and as an assistant reads it: real requests through the proxy land
 * in the log with the time they took; the tab shows its own site's lines in nine columns, follows a new line without a
 * navigation, narrows by path and downloads the lines it shows; a customer who manages the site reads it, never
 * another site's; the tab's database cost stays flat as lines and addresses grow; a long path stands on one line, cut;
 * a picked range shows its two ends without a reload and reads once both are set; access_log_read reads the same
 * lines through the real MCP endpoint, filtered, refused a wrong argument by its name and another's site.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class SiteAccessLogJourneyTest extends HohenheimTestBase {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String LINE = "[data-hh-access-log-line]";
    private static final String LONG_PATH = "/migadmin/lang/legacy/legacy/filechecksum/" + "a".repeat(240);

    @Test
    void aSiteManagerReadsFollowsNarrowsAndDownloadsItsSitesAccessLog() throws Exception {
        try (Fixture fixture = Fixture.start("tab")) {
            fixture.request(fixture.siteA, "/ok", "User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:133.0) "
                + "Gecko/20100101 Firefox/133.0", "Referer: https://from.example/page");
            fixture.request(fixture.siteA, "/missing");
            fixture.request(fixture.siteA, LONG_PATH, "User-Agent: Mozilla/5.0 (compatible; CensysInspect/1.1;"
                + " +https://about.censys.io/)");
            fixture.request(fixture.siteB, "/other-site");
            fixture.awaitLines(4);

            // 1. The tab draws the site's lines newest first in the board's nine columns, the refused one badged, the
            //    browser named from its user agent with its version, a scanner by its own name, the time taken in
            //    milliseconds; the other site's line is not there.
            page.setViewportSize(1280, 800);
            navigateToApp(tab(HohenheimSlugs.ADMIN, fixture.siteA));
            waitForHydration();
            awaitRows("step 1: the site's three lines", 3);
            assertThat(page.locator("pl-table-head").allTextContents().stream().map(String::trim).toList())
                .as("step 1: the nine columns").containsExactly("Time", "Visitor", "Method", "Path", "Status", "Size",
                    "Time taken", "Referrer", "Browser");
            String table = page.locator("[data-hh-access-log-lines]").textContent();
            InOrder.assertThat(table).as("step 1: newest first").contains("/migadmin/", "/missing", "/ok");
            assertThat(page.locator("[data-hh-access-log-browser]").allTextContents().stream().map(String::trim)
                .toList()).as("step 1: the browser and the scanner in words")
                .contains("Firefox 133, Linux", "CensysInspect 1.1");
            assertThat(table).as("step 1: never another site's line").doesNotContain("/other-site");
            assertThat(page.locator("[data-hh-access-log-took]").allTextContents()).as("step 1: milliseconds")
                .allSatisfy(took -> assertThat(took.trim()).matches("\\d+ ms"));
            assertThat(page.locator(LINE + "[data-hh-access-log-line='404'] pl-badge").count())
                .as("step 1: the refused line badged").isEqualTo(1);
            assertThat(page.locator("[data-hh-access-log-summary]").textContent())
                .as("step 1: how many of how many, and of which period").contains("1 to 3 of 3 today, newest first");
            assertThat(page.locator("[data-hh-access-log-keeping]").textContent()).as("step 1: the keeping period")
                .contains("Lines are kept 90 days.");
            assertThat(page.locator("[data-hh-access-log-filters] pl-select[name='when']").textContent())
                .as("step 1: the tab opens on today").contains("Today");
            assertThat(page.locator("[data-hh-access-log-filters] pl-select[name='status']").textContent())
                .as("step 1: every status").contains("Status: all");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/site-access-log-tab.png"))
                .setFullPage(true));

            // 1b. The long path stands on one line in the free width, at least 350 px beside the panel's rail at
            //     1280 px, cut by an ellipsis, whole in its title and its text: its row is as tall as a short path's.
            String longPath = LINE + ":has([title='" + LONG_PATH + "'])";
            assertThat(page.locator(longPath + " [data-hh-access-log-path]").textContent().trim())
                .as("step 1b: the whole path in the document").isEqualTo(LONG_PATH);
            assertThat((Boolean) page.locator(longPath + " [data-hh-access-log-path]")
                .evaluate("cell => cell.scrollWidth > cell.clientWidth")).as("step 1b: cut where its column ends")
                .isTrue();
            assertThat(page.locator(longPath).boundingBox().height).as("step 1b: one line tall")
                .isCloseTo(page.locator(LINE + ":has([title='/ok'])").boundingBox().height,
                    offset(1.0));
            double pathWidth = page.locator(longPath + " [data-hh-access-log-path]").boundingBox().width;
            assertThat(pathWidth).as("step 1b: the path has room; the columns: %s", page.locator("pl-table-head")
                .evaluateAll("heads => heads.map(head => head.textContent.trim() + ' '"
                    + " + Math.round(head.getBoundingClientRect().width)).join(', ')")).isGreaterThanOrEqualTo(350.0);
            // The widest IPv4 address, in the visitor's own face, fits the column on one line.
            assertThat((Boolean) page.locator(LINE + " [data-hh-access-log-visitor]").first().evaluate("cell => {"
                + " const face = getComputedStyle(cell.querySelector('span'));"
                + " const pen = document.createElement('canvas').getContext('2d'); pen.font = face.font;"
                + " const style = getComputedStyle(cell); const room = cell.clientWidth"
                + " - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);"
                + " return pen.measureText('255.255.255.255').width <= room + 0.5; }"))
                .as("step 1c: the widest IPv4 address fits its column on one line").isTrue();

            // 1c. A visitor's address shows whole on one line of its column, the referrer only the host the visitor
            //     came from, and the "Time taken" head stands on one line as the others do.
            assertThat(page.locator(LINE + " [data-hh-access-log-visitor]").allTextContents().stream()
                .map(String::trim).toList()).as("step 1c: the whole address").containsOnly("127.0.0.1");
            assertThat((Integer) page.locator(LINE + " [data-hh-access-log-visitor] span").first()
                .evaluate("span => span.getClientRects().length")).as("step 1c: on one line").isEqualTo(1);
            assertThat(page.locator(LINE + " [data-hh-access-log-referrer]").allTextContents().stream()
                .map(String::trim).toList()).as("step 1c: the referrer's host").contains("from.example")
                .noneMatch(referrer -> referrer.contains("/"));
            // The lines its words stand on: the distinct tops of the rectangles its contents draw.
            assertThat((Integer) page.locator("[data-hh-access-log-took-head]").evaluate("head => { const words ="
                + " document.createRange(); words.selectNodeContents(head); return new Set([...words.getClientRects()]"
                + ".filter(box => box.width > 0).map(box => Math.round(box.top))).size; }"))
                .as("step 1c: Time taken on one line").isEqualTo(1);

            // 2. Following is on: a new request of the site appears without a navigation.
            String opened = page.url();
            fixture.request(fixture.siteA, "/arrived");
            awaitRows("step 2: the new line followed", 4);
            assertThat(page.locator(LINE).first().textContent()).as("step 2: on top").contains("/arrived");
            assertThat(page.url()).as("step 2: no navigation").isEqualTo(opened);

            // 3. Path contains narrows the lines through the filter form, and the address says so.
            page.locator("[data-hh-access-log-filters] pl-input[name='path'] input").fill("/missing");
            page.waitForURL(url -> url.contains("path=") && url.contains("missing"), new Page.WaitForURLOptions()
                .setTimeout(WAIT.toMillis()));
            waitForHydration();
            awaitRows("step 3: the one matching line", 1);

            // 4. The download carries the lines the filters show, as the log holds them, without the site field.
            Download download = page.waitForDownload(() -> page.locator("[data-hh-access-log-download] a").click());
            String file = Files.readString(download.path(), StandardCharsets.UTF_8);
            assertThat(file.lines().toList()).as("step 4: the filtered line").hasSize(1);
            assertThat(file).as("step 4: the line as logged").contains("\"GET /missing HTTP/1.1\" 404")
                .contains(" time_ms=").doesNotContain(" site=").doesNotContain("/ok ");

            // 5. A customer who manages the site reads its tab on /manage; another site's tab is refused him.
            UUID customer = ApiSupport.user("access-log-" + fixture.tag + "@hohenheim.local", "Access log customer");
            fixture.cleanUp.push(() -> Models.get(UserModel.class).delete(customer));
            RecordGrants.grant(GrantSubjectType.USER, customer, SiteModel.MODEL_ID, fixture.siteA,
                HohenheimCapabilities.MANAGE, true);
            String session = sessionFor(customer).token();
            navigateToAppAs(tab(HohenheimSlugs.MANAGE, fixture.siteA), session);
            waitForHydration();
            awaitRows("step 5: the customer reads his site's lines", 4);
            HttpResponse<String> foreign = httpGet(tab(HohenheimSlugs.MANAGE, fixture.siteB), session);
            assertThat(foreign.statusCode()).as("step 5: another site's tab is no page of his").isIn(403, 404);
            assertThat(foreign.body()).as("step 5: and shows nothing of it").doesNotContain("/other-site");

            // 6. The tab's database cost does not grow with the lines read or the site's addresses.
            for (int i = 0; i < 30; i++) fixture.request(fixture.siteB, "/many-" + i);
            ProxyTestSupport.addDomain(Models.get(SiteModel.class).findById(fixture.siteB),
                "more-" + fixture.tag + ".access.test", "exact", null, false);
            fixture.awaitLines(35);
            DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Datasources.getDefault());
            probe.requireFlat(() -> adminFetched(tab(HohenheimSlugs.ADMIN, fixture.siteA)),
                () -> adminFetched(tab(HohenheimSlugs.ADMIN, fixture.siteB)),
                "step 6: a few lines or many, one query set");

            // 7. A range you pick: its From and To appear at once, without a navigation and with the lines as they
            //    were; nothing is read until both hold a value, then the tab reads the range.
            navigateToApp(tab(HohenheimSlugs.ADMIN, fixture.siteA));
            waitForHydration();
            awaitRows("step 7: today's lines", 4);
            String today = page.url();
            page.click("[data-hh-access-log-filters] pl-select[name='when'] .pl-select-field");
            page.click("he-bottom .pl-select-popup[data-open] div[role='option'][data-value='range']");
            page.locator("[data-hh-access-log-filters] pl-input[name='from'] input").waitFor();
            assertThat(page.locator("[data-hh-access-log-filters] pl-input[name='to'] input").isVisible())
                .as("step 7: both ends appear").isTrue();
            page.locator("[data-hh-access-log-filters] pl-input[name='from'] input").fill("2000-01-01T00:00");
            waitForReactiveIdle();
            assertThat(page.url()).as("step 7: one end read nothing").isEqualTo(today);
            awaitRows("step 7: the lines as they were", 4);
            page.locator("[data-hh-access-log-filters] pl-input[name='to'] input").fill("2100-01-01T00:00");
            page.waitForURL(url -> url.contains("from=") && url.contains("to="), new Page.WaitForURLOptions()
                .setTimeout(WAIT.toMillis()));
            waitForHydration();
            assertThat(page.locator("[data-hh-access-log-summary]").textContent())
                .as("step 7: the range read, naming no period").contains("1 to 4 of 4, newest first");
        }
    }

    @Test
    void anAssistantReadsASitesAccessLogByTool() throws Exception {
        try (Fixture fixture = Fixture.start("tool")) {
            fixture.request(fixture.siteA, "/ok", "User-Agent: probe/2.0");
            fixture.request(fixture.siteA, "/missing");
            fixture.request(fixture.siteB, "/other-site");
            fixture.awaitLines(3);
            URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
            McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
            UUID adminId = TenantConduits.operatorUser().get(UserModel.ID);
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint, adminId, "Test Admin");
            AccessContext operator = TenantConduits.operator();
            String codeA = HohenheimMcpTest.code(SiteModel.MODEL_ID, fixture.siteA, operator);
            String codeB = HohenheimMcpTest.code(SiteModel.MODEL_ID, fixture.siteB, operator);

            // 1. The site's lines newest first, as the log holds them, never another site's.
            McpHttpSession.Answer all = admin.call("access_log_read", Map.of("site", codeA));
            assertThat(all.error()).as("step 1: " + all.text()).isFalse();
            assertThat(all.text()).as("step 1: which of how many").contains("lines 1 to 2 of 2, newest first");
            InOrder.assertThat(all.text()).as("step 1: newest first").contains("/missing", "/ok");
            assertThat(all.text()).as("step 1: as logged, with the time taken").contains("\"probe/2.0\" time_ms=")
                .doesNotContain(" site=").doesNotContain("/other-site");

            // 2. Narrowed by status class, exact code, path and a range past now.
            assertThat(admin.call("access_log_read", Map.of("site", codeA, "status", "4xx")).text())
                .as("step 2: 4xx").contains("/missing").doesNotContain("/ok ");
            assertThat(admin.call("access_log_read", Map.of("site", codeA, "status_code", 200)).text())
                .as("step 2: 200").contains("/ok ").doesNotContain("/missing");
            assertThat(admin.call("access_log_read", Map.of("site", codeA, "path", "miss")).text())
                .as("step 2: path").contains("/missing").doesNotContain("/ok ");
            assertThat(admin.call("access_log_read", Map.of("site", codeA, "from", "2099-01-01T00:00:00Z")).text())
                .as("step 2: nothing after now").contains("lines: none match");

            // 3. A wrong call is refused by the argument it names.
            McpHttpSession.Answer wrong = admin.call("access_log_read", Map.of("site", codeA, "lines", 5));
            assertThat(wrong.error()).as("step 3: " + wrong.text()).isTrue();
            assertThat(wrong.text()).as("step 3: the refusal names it").contains("lines");
            McpHttpSession.Answer badStatus = admin.call("access_log_read", Map.of("site", codeA, "status", "9xx"));
            assertThat(badStatus.error()).as("step 3: " + badStatus.text()).isTrue();
            assertThat(badStatus.text()).as("step 3: the refusal names it").contains("status");

            // 4. A customer who manages one site reads it, and is refused the other one.
            UUID customer = ApiSupport.user("access-log-tool-" + fixture.tag + "@hohenheim.local", "Log customer");
            fixture.cleanUp.push(() -> Models.get(UserModel.class).delete(customer));
            RecordGrants.grant(GrantSubjectType.USER, customer, SiteModel.MODEL_ID, fixture.siteA,
                HohenheimCapabilities.MANAGE, true);
            McpHttpSession own = HohenheimMcpTest.presetSession(server, endpoint, customer, "Log customer");
            assertThat(own.call("access_log_read", Map.of("site", codeA)).text()).as("step 4: his own site")
                .contains("/missing");
            McpHttpSession.Answer foreign = own.call("access_log_read", Map.of("site", codeB));
            assertThat(foreign.error()).as("step 4: " + foreign.text()).isTrue();
            assertThat(foreign.text()).as("step 4: nothing of it").doesNotContain("/other-site");
        }
    }

    /** @return the tab's address on a panel */
    private static String tab(String panel, int siteId) {
        return CmsRoutes.subpage(panel, HohenheimSlugs.SITES, siteId, HohenheimSlugs.Tab.ACCESS_LOG).toUrl();
    }

    private void awaitRows(String step, int count) {
        try {
            page.waitForCondition(() -> page.locator(LINE).count() == count,
                new Page.WaitForConditionOptions().setTimeout(WAIT.toMillis()));
        } catch (RuntimeException timedOut) {
            // The assertion below names what the page showed instead.
        }
        assertThat(page.locator(LINE).count()).as(step).isEqualTo(count);
    }

    /**
     * Two sites behind a running proxy and an upstream, their access log in a file of the test's own.
     *
     * AIDEV-NOTE: TrafficScreensJourneyTest boots its sites through this fixture too.
     */
    static final class Fixture implements AutoCloseable {
        final Deque<Runnable> cleanUp = new ArrayDeque<>();
        final String tag = HohenheimMcpTest.letters();
        private Path log;
        ProxyServer proxy;
        int siteA;
        int siteB;
        /** Where a site of the fixture forwards: its upstream. */
        private Map<String, Object> forward;
        /** The requests sent, so each waits for its own line. */
        private int sent;
        static Fixture start(String name) throws Exception {
            Fixture fixture = new Fixture();
            try {
                fixture.boot(name);
            } catch (Exception | Error failed) {
                fixture.close();
                throw failed;
            }
            return fixture;
        }

        private void boot(String name) throws Exception {
            String path = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Logging.ACCESS_PATH);
            Boolean on = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Logging.ACCESS_TO_FILE);
            this.cleanUp.push(() -> {
                Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Logging.ACCESS_PATH, path);
                Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Logging.ACCESS_TO_FILE, on);
            });
            this.log = Files.createTempDirectory("hh-access-" + name).resolve("access.log");
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Logging.ACCESS_PATH, this.log.toString());
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Logging.ACCESS_TO_FILE, true);
            HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            upstream.createContext("/", exchange -> {
                byte[] body = "answered".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/missing") ? 404 : 200,
                    body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            upstream.start();
            this.cleanUp.push(() -> upstream.stop(0));
            this.forward = Map.of("forward_host", "127.0.0.1", "forward_port", upstream.getAddress().getPort());
            this.siteA = this.site("a");
            this.siteB = this.site("b");
            this.proxy = ProxyTestSupport.startProxy();
            this.cleanUp.push(this.proxy::stop);
        }

        /** @return a new site forwarding to the fixture's upstream at an address of its own */
        int site(String letter) {
            String slug = "access-" + letter + "-" + this.tag;
            Row site = ProxyTestSupport.setupSite("hohenheim:address", "Access " + letter + " " + this.tag, slug,
                this.forward);
            ProxyTestSupport.addDomain(site, hostOf(site.get(SiteModel.ID)), "exact", null, false);
            int id = site.get(SiteModel.ID);
            this.cleanUp.push(() -> HardDeletes.byId(Models.get(SiteModel.class), id));
            if (this.proxy != null) {
                // A site made while the proxy runs is routed once the proxy reads its routes again.
                this.proxy.getDispatcher().reloadRoutes();
            }
            return id;
        }

        String hostOf(int siteId) {
            return "site" + siteId + "-" + this.tag + ".access.test";
        }

        /**
         * Sends one request and waits for its line: the line lands after the client read the response, so a next
         * request could otherwise be logged first.
         */
        void request(int siteId, String path, String... headers) throws Exception {
            ProxyTestSupport.rawRequest(ProxyTestSupport.httpPort(this.proxy), this.hostOf(siteId), path, headers);
            this.awaitLines(++this.sent);
        }

        /** Waits until the log holds this many lines; a line lands once its response is sent. */
        void awaitLines(int count) {
            Poll.value("the access log holds " + count + " lines", WAIT, () -> {
                try {
                    return Files.exists(this.log) && Files.readAllLines(this.log).size() >= count ? count : null;
                } catch (IOException unreadable) {
                    return null;
                }
            });
        }

        @Override
        public void close() {
            while (!this.cleanUp.isEmpty()) {
                try {
                    this.cleanUp.pop().run();
                } catch (RuntimeException ignored) {
                    // Every other clean-up still runs.
                }
            }
        }
    }
}
