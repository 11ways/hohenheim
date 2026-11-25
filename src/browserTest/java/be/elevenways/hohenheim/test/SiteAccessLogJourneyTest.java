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

/**
 * A site's Access log, as the board's tab draws it and as an assistant reads it: real requests through the proxy land
 * in the log with the time they took; the tab shows its own site's lines in nine columns, follows a new line without a
 * navigation, narrows by path and downloads the lines it shows; a customer who manages the site reads it, never
 * another site's; the tab's database cost stays flat as lines and addresses grow; access_log_read reads the same
 * lines through the real MCP endpoint, filtered, refused a wrong argument by its name and another's site.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class SiteAccessLogJourneyTest extends HohenheimTestBase {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String LINE = "[data-hh-access-log-line]";

    @Test
    void aSiteManagerReadsFollowsNarrowsAndDownloadsItsSitesAccessLog() throws Exception {
        try (Fixture fixture = Fixture.start("tab")) {
            fixture.request(fixture.siteA, "/ok", "User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:133.0) "
                + "Gecko/20100101 Firefox/133.0", "Referer: https://from.example/page");
            fixture.request(fixture.siteA, "/missing");
            fixture.request(fixture.siteB, "/other-site");
            fixture.awaitLines(3);

            // 1. The tab draws the site's lines newest first in the board's nine columns, the refused one badged, the
            //    browser named from its user agent; the other site's line is not there.
            navigateToApp(tab(HohenheimSlugs.ADMIN, fixture.siteA));
            waitForHydration();
            awaitRows("step 1: the site's two lines", 2);
            assertThat(page.locator("pl-table-head").allTextContents().stream().map(String::trim).toList())
                .as("step 1: the nine columns").containsExactly("Time", "Visitor", "Method", "Path", "Status", "Size",
                    "Time taken", "Referrer", "Browser");
            String table = page.locator("[data-hh-access-log-lines]").textContent();
            InOrder.assertThat(table).as("step 1: newest first").contains("/missing", "/ok");
            assertThat(table).as("step 1: the browser in words").contains("Firefox, Linux");
            assertThat(table).as("step 1: never another site's line").doesNotContain("/other-site");
            assertThat(page.locator(LINE + "[data-hh-access-log-line='404'] pl-badge").count())
                .as("step 1: the refused line badged").isEqualTo(1);
            assertThat(page.locator("[data-hh-access-log-summary]").textContent())
                .as("step 1: how many of how many").contains("1 to 2 of 2, newest first");
            assertThat(page.locator("[data-hh-access-log-keeping]").textContent()).as("step 1: the keeping period")
                .contains("Lines are kept 90 days.");
            assertThat(page.locator("[data-hh-access-log-filters] pl-select[name='when']").textContent())
                .as("step 1: the tab opens on today").contains("Today");
            assertThat(page.locator("[data-hh-access-log-filters] pl-select[name='status']").textContent())
                .as("step 1: every status").contains("Status: all");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/site-access-log-tab.png"))
                .setFullPage(true));

            // 2. Following is on: a new request of the site appears without a navigation.
            String opened = page.url();
            fixture.request(fixture.siteA, "/arrived");
            awaitRows("step 2: the new line followed", 3);
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
            awaitRows("step 5: the customer reads his site's lines", 3);
            HttpResponse<String> foreign = httpGet(tab(HohenheimSlugs.MANAGE, fixture.siteB), session);
            assertThat(foreign.statusCode()).as("step 5: another site's tab is no page of his").isIn(403, 404);
            assertThat(foreign.body()).as("step 5: and shows nothing of it").doesNotContain("/other-site");

            // 6. The tab's database cost does not grow with the lines read or the site's addresses.
            for (int i = 0; i < 30; i++) fixture.request(fixture.siteB, "/many-" + i);
            ProxyTestSupport.addDomain(Models.get(SiteModel.class).findById(fixture.siteB),
                "more-" + fixture.tag + ".access.test", "exact", null, false);
            fixture.awaitLines(34);
            DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Datasources.getDefault());
            probe.requireFlat(() -> fetched(tab(HohenheimSlugs.ADMIN, fixture.siteA)),
                () -> fetched(tab(HohenheimSlugs.ADMIN, fixture.siteB)), "step 6: a few lines or many, one query set");
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

    private void fetched(String path) {
        try {
            assertThat(adminGet(path).statusCode()).as(path).isEqualTo(200);
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
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

    /** Two sites behind a running proxy and an upstream, their access log in a file of the test's own. */
    private static final class Fixture implements AutoCloseable {
        private final Deque<Runnable> cleanUp = new ArrayDeque<>();
        private final String tag = HohenheimMcpTest.letters();
        private Path log;
        private ProxyServer proxy;
        private int siteA;
        private int siteB;

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
            Map<String, Object> forward = Map.of("forward_host", "127.0.0.1", "forward_port",
                upstream.getAddress().getPort());
            this.siteA = this.site("a", forward);
            this.siteB = this.site("b", forward);
            this.proxy = ProxyTestSupport.startProxy();
            this.cleanUp.push(this.proxy::stop);
        }

        private int site(String letter, Map<String, Object> forward) {
            String slug = "access-" + letter + "-" + this.tag;
            Row site = ProxyTestSupport.setupSite("hohenheim:address", "Access " + letter + " " + this.tag, slug,
                forward);
            ProxyTestSupport.addDomain(site, hostOf(site.get(SiteModel.ID)), "exact", null, false);
            int id = site.get(SiteModel.ID);
            this.cleanUp.push(() -> HardDeletes.byId(Models.get(SiteModel.class), id));
            return id;
        }

        private String hostOf(int siteId) {
            return "site" + siteId + "-" + this.tag + ".access.test";
        }

        void request(int siteId, String path, String... headers) throws Exception {
            ProxyTestSupport.rawRequest(ProxyTestSupport.httpPort(this.proxy), this.hostOf(siteId), path, headers);
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
