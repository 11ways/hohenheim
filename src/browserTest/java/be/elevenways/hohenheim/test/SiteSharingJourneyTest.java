package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.files.SiteFiles;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.hohenheim.server.upstream.kinds.SiteSharing;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.zenit.server.storage.LocalDiskStorageAdapter;
import be.elevenways.zenit.server.storage.Storages;
import com.microsoft.playwright.Page;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static be.elevenways.hohenheim.test.ProxyTestSupport.headerValue;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may fetch what from a site: a page on another website reads a file the site serves, the browser keeps a file as
 * long as the site says, and the Traffic tab lists the files fetched. Through the real proxy on a site serving its own
 * files and on a site passing requests to an app, by tool through the real MCP endpoint, and in the site's form.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class SiteSharingJourneyTest extends HohenheimTestBase {

    private static final String READER = "https://account.tadoma.test";
    private static final Pattern SITE_CODE = Pattern.compile("S\\d+");

    private static Path storage;

    @BeforeAll
    static void mountStorage() throws Exception {
        storage = Files.createTempDirectory("hh-site-sharing");
        Storages.replace(SiteFiles.STORAGE, new LocalDiskStorageAdapter(storage));
    }

    @AfterAll
    static void unmountStorage() {
        Storages.unregister(SiteFiles.STORAGE);
    }

    /**
     * The proxy as a browser meets it: the listed website reads with its own name and Vary, another website reads
     * nothing, the advance question is answered, a 404 carries nothing, a kept file revalidates to a 304, a year on its
     * prefix only and the longest path winning; any website gets the wildcard; an app's own grant and the address's
     * hand-written header are left alone.
     */
    @Test
    void aSiteTellsBrowsersWhoMayReadItAndHowLongToKeepItsFiles() throws Exception {
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        try {
            // 0. A site serving its own files, read by one other website, its files kept per path.
            Map<String, Object> keep = new LinkedHashMap<>();
            keep.put("/beta/latest.json", SiteSharing.Keep.ASK_AGAIN.token());
            keep.put("/beta/releases/", SiteSharing.Keep.YEAR.token());
            keep.put("/beta/releases/old/", SiteSharing.Keep.DECIDE.token());
            Map<String, Object> settings = new LinkedHashMap<>(Map.of("own_files", true,
                SiteSharing.READABLE_BY, SiteSharing.Readers.LISTED.token(),
                SiteSharing.READING_WEBSITES, List.of(READER), SiteSharing.BROWSER_KEEP, keep));
            Row site = ProxyTestSupport.setupSite("hohenheim:static", "Shared " + tag, "shared-" + tag, settings);
            int siteId = site.get(SiteModel.ID);
            String host = "downloads-" + tag + ".share.test";
            ProxyTestSupport.addDomain(site, host, "exact", null, false);
            Path files = storage.resolve(SiteFiles.prefixOf(siteId));
            write(files.resolve("beta/latest.json"), "{\"version\":\"1.2.0\"}");
            write(files.resolve("beta/releases/Tadoma-1.2.0.dmg"), "dmg");
            write(files.resolve("beta/releases/old/Tadoma-1.1.4.dmg"), "old");
            write(files.resolve("notes.txt"), "notes");

            // A site passing requests to an app that grants one path itself, read by any website; and one address of
            // it whose hand-written response header names the reader.
            HttpServer app = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            app.createContext("/", exchange -> {
                if (exchange.getRequestURI().getPath().equals("/own")) {
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "https://app.test");
                }
                byte[] body = "app".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            app.start();
            cleanUp.push(() -> app.stop(0));
            Row proxied = ProxyTestSupport.setupSite("hohenheim:address", "Proxied " + tag, "proxied-" + tag,
                new LinkedHashMap<>(Map.of("forward_host", "127.0.0.1", "forward_port", app.getAddress().getPort(),
                    SiteSharing.READABLE_BY, SiteSharing.Readers.ANY.token(),
                    SiteSharing.BROWSER_KEEP, Map.of("/", SiteSharing.Keep.YEAR.token()))));
            String appHost = "app-" + tag + ".share.test";
            ProxyTestSupport.addDomain(proxied, appHost, "exact", null, false);
            String handHost = "hand-" + tag + ".share.test";
            Row hand = Models.get(SiteDomainModel.class).createEmptyRow();
            hand.set(SiteDomainModel.SITE_ID, proxied.get(SiteModel.ID));
            hand.set(SiteDomainModel.HOSTNAME, handHost);
            hand.set(SiteDomainModel.MATCH_TYPE, "exact");
            hand.set(SiteDomainModel.FORCE_SSL, false);
            hand.set(SiteDomainModel.RESPONSE_HEADERS, Map.of("Access-Control-Allow-Origin", "https://hand.test"));
            Models.get(SiteDomainModel.class).save(hand);

            ProxyServer proxy = ProxyTestSupport.startProxy();
            cleanUp.push(proxy::stop);
            int port = ProxyTestSupport.httpPort(proxy);

            // 1. The listed website reads the file under its own name, the answer varies on the website, and the
            //    browser asks again before every use, holding the file's validators.
            String listed = ProxyTestSupport.rawRequest(port, host, "/beta/latest.json", "Origin: " + READER);
            assertThat(listed).as("step 1: served").startsWith("HTTP/1.1 200");
            assertThat(headerValue(listed, "Access-Control-Allow-Origin")).as("step 1: the reader named, never a list")
                .isEqualTo(READER);
            assertThat(headerValue(listed, "Vary")).as("step 1: a list varies on the website").contains("Origin");
            assertThat(headerValue(listed, "Access-Control-Allow-Credentials")).as("step 1: no credentials").isNull();
            assertThat(headerValue(listed, "Cache-Control")).as("step 1: always ask again").isEqualTo("no-cache");
            String etag = headerValue(listed, "ETag");
            assertThat(etag).as("step 1: the file's validator").isNotBlank();
            assertThat(headerValue(listed, "Last-Modified")).as("step 1: and its date").isNotBlank();

            // 2. Another website reads nothing; the answer still varies on the website.
            String other = ProxyTestSupport.rawRequest(port, host, "/beta/latest.json",
                "Origin: https://elsewhere.test");
            assertThat(headerValue(other, "Access-Control-Allow-Origin")).as("step 2: another website, nothing")
                .isNull();
            assertThat(headerValue(other, "Vary")).as("step 2: still varies").contains("Origin");

            // 3. The browser's advance question is answered here: 204, the reader, GET and HEAD, the asked headers.
            String asked = ProxyTestSupport.rawRequestAs(port, "OPTIONS", host, "/beta/latest.json",
                "Origin: " + READER, "Access-Control-Request-Method: GET",
                "Access-Control-Request-Headers: x-tadoma-client");
            assertThat(asked).as("step 3: answered by Hohenheim").startsWith("HTTP/1.1 204");
            assertThat(headerValue(asked, "Access-Control-Allow-Origin")).as("step 3: the reader").isEqualTo(READER);
            assertThat(headerValue(asked, "Access-Control-Allow-Methods")).as("step 3: the reads")
                .contains("GET").contains("HEAD");
            assertThat(headerValue(asked, "Access-Control-Allow-Headers")).as("step 3: the asked headers")
                .isEqualTo("x-tadoma-client");
            assertThat(headerValue(asked, "Access-Control-Allow-Credentials")).as("step 3: never credentials").isNull();
            String foreign = ProxyTestSupport.rawRequestAs(port, "OPTIONS", host, "/beta/latest.json",
                "Origin: https://elsewhere.test", "Access-Control-Request-Method: GET");
            assertThat(headerValue(foreign, "Access-Control-Allow-Origin")).as("step 3: another website's question,"
                + " no grant").isNull();

            // 4. A failed answer carries nothing.
            String missing = ProxyTestSupport.rawRequest(port, host, "/beta/releases/missing.dmg",
                "Origin: " + READER);
            assertThat(missing).as("step 4: not found").startsWith("HTTP/1.1 404");
            assertThat(headerValue(missing, "Access-Control-Allow-Origin")).as("step 4: no grant on a 404").isNull();
            assertThat(headerValue(missing, "Cache-Control")).as("step 4: no keeping on a 404").isNull();

            // 5. Asking again with the validator answers 304, still readable by the reader, no file sent.
            String again = ProxyTestSupport.rawRequest(port, host, "/beta/latest.json", "Origin: " + READER,
                "If-None-Match: " + etag);
            assertThat(again).as("step 5: unchanged").startsWith("HTTP/1.1 304");
            assertThat(headerValue(again, "Access-Control-Allow-Origin")).as("step 5: a 304 is stamped too")
                .isEqualTo(READER);
            assertThat(again).as("step 5: no body").doesNotContain("1.2.0");

            // 6. A year on its prefix only, and the longest matching path wins.
            assertThat(headerValue(ProxyTestSupport.rawRequest(port, host, "/beta/releases/Tadoma-1.2.0.dmg"),
                "Cache-Control")).as("step 6: a year under /beta/releases/")
                .isEqualTo("public, max-age=31536000, immutable");
            assertThat(headerValue(ProxyTestSupport.rawRequest(port, host, "/beta/releases/old/Tadoma-1.1.4.dmg"),
                "Cache-Control")).as("step 6: the longer /beta/releases/old/ decides by itself").isNull();
            assertThat(headerValue(ProxyTestSupport.rawRequest(port, host, "/notes.txt"), "Cache-Control"))
                .as("step 6: everything else decides by itself").isNull();

            // 7. Any website: the wildcard; an app's own grant and the address's hand-written header win.
            String any = ProxyTestSupport.rawRequest(port, appHost, "/plain", "Origin: https://elsewhere.test");
            assertThat(headerValue(any, "Access-Control-Allow-Origin")).as("step 7: any website").isEqualTo("*");
            assertThat(headerValue(any, "Cache-Control")).as("step 7: the app's answers kept a year")
                .isEqualTo("public, max-age=31536000, immutable");
            assertThat(headerValue(ProxyTestSupport.rawRequest(port, appHost, "/own", "Origin: https://elsewhere.test"),
                "Access-Control-Allow-Origin")).as("step 7: the app's own grant is left alone")
                .isEqualTo("https://app.test");
            assertThat(headerValue(ProxyTestSupport.rawRequest(port, handHost, "/plain",
                "Origin: https://elsewhere.test"), "Access-Control-Allow-Origin"))
                .as("step 7: the hand-written header on the address wins").isEqualTo("https://hand.test");

            // 8. Nobody, the default: the site says nothing and the advance question goes to the site.
            Row quiet = Models.get(SiteModel.class).findById(siteId);
            Map<String, Object> nobody = new LinkedHashMap<>(settings);
            nobody.put(SiteSharing.READABLE_BY, SiteSharing.Readers.NOBODY.token());
            quiet.set(SiteModel.SETTINGS, nobody);
            Models.get(SiteModel.class).save(quiet);
            proxy.getDispatcher().reloadRoutes();
            assertThat(headerValue(ProxyTestSupport.rawRequest(port, host, "/beta/latest.json", "Origin: " + READER),
                "Access-Control-Allow-Origin")).as("step 8: nobody reads").isNull();
            assertThat(ProxyTestSupport.rawRequestAs(port, "OPTIONS", host, "/beta/latest.json", "Origin: " + READER,
                "Access-Control-Request-Method: GET")).as("step 8: the question is the site's").doesNotStartWith(
                "HTTP/1.1 204");
        } finally {
            cleanUp.forEach(Runnable::run);
        }
    }

    /**
     * An assistant sets what a site shares through the real MCP endpoint, reads it back, is refused by argument name
     * for a website without its scheme and a path without its slash, takes a path away, and reads the files fetched.
     */
    @Test
    void anAssistantSetsWhatASiteSharesAndReadsTheFilesFetched() throws Exception {
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        try {
            Instant began = Now.instant();
            TrafficCountingJourneyTest.countUnder(cleanUp, "sharing-" + tag);
            HohenheimTraffic.start();
            URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
            McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
            UUID adminId = TenantConduits.operatorUser().get(UserModel.ID);
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint, adminId, "Test Admin");

            // 1. Made by tool: its own files, one reading website, one path asked again.
            McpHttpSession.Answer made = admin.call("site_static_files_set", Map.of("name", "Shared tool " + tag,
                "own_files", true, SiteSharing.READABLE_BY, "listed", SiteSharing.READING_WEBSITES, List.of(READER),
                SiteSharing.BROWSER_KEEP, List.of(Map.of("path", "/beta/latest.json", "keep", "ask_again"),
                    Map.of("path", "/beta/releases/", "keep", "year"))));
            assertThat(made.error()).as("step 1: " + made.text()).isFalse();
            String code = code(made.text(), "step 1");
            int siteId = Integer.parseInt(Objects.requireNonNull(RecordCodes.keyOfCode(SiteModel.MODEL_ID, code)));
            Map<?, ?> stored = (Map<?, ?>) Models.get(SiteModel.class).findById(siteId).get(SiteModel.SETTINGS);
            assertThat(stored.get(SiteSharing.READABLE_BY)).as("step 1: stored").isEqualTo("listed");
            assertThat(stored.get(SiteSharing.READING_WEBSITES)).as("step 1: the website").isEqualTo(List.of(READER));
            assertThat(stored.get(SiteSharing.BROWSER_KEEP)).as("step 1: the paths")
                .isEqualTo(Map.of("/beta/latest.json", "ask_again", "/beta/releases/", "year"));

            // 2. The read states them.
            McpHttpSession.Answer read = admin.call("read", Map.of("codes", List.of(code)));
            assertThat(read.error()).as("step 2: " + read.text()).isFalse();
            assertThat(read.text()).as("step 2: the read states the sharing")
                .contains(SiteSharing.READABLE_BY).contains(READER).contains("/beta/latest.json");

            // 3. Refused by argument name: a website without its scheme, a path without its slash.
            McpHttpSession.Answer schemeless = admin.call("site_static_files_set", Map.of("code", code,
                SiteSharing.READING_WEBSITES, List.of("account.tadoma.test")));
            assertThat(schemeless.error()).as("step 3: " + schemeless.text()).isTrue();
            assertThat(schemeless.text()).as("step 3: named").contains(SiteSharing.READING_WEBSITES);
            McpHttpSession.Answer unrooted = admin.call("site_static_files_set", Map.of("code", code,
                SiteSharing.BROWSER_KEEP, List.of(Map.of("path", "beta/latest.json", "keep", "year"))));
            assertThat(unrooted.error()).as("step 3: " + unrooted.text()).isTrue();
            assertThat(unrooted.text()).as("step 3: named").contains(SiteSharing.BROWSER_KEEP);

            // 4. A path taken away; the rest stays.
            McpHttpSession.Answer removed = admin.call("site_static_files_set", Map.of("code", code,
                SiteSharing.BROWSER_KEEP, List.of(Map.of("path", "/beta/releases/", "remove", true))));
            assertThat(removed.error()).as("step 4: " + removed.text()).isFalse();
            stored = (Map<?, ?>) Models.get(SiteModel.class).findById(siteId).get(SiteModel.SETTINGS);
            assertThat(stored.get(SiteSharing.BROWSER_KEEP)).as("step 4: one path left")
                .isEqualTo(Map.of("/beta/latest.json", "ask_again"));
            assertThat(stored.get(SiteSharing.READING_WEBSITES)).as("step 4: the website kept")
                .isEqualTo(List.of(READER));

            // 5. Files fetched: whole downloads and a resumed range each count, a 404 and a HEAD do not.
            fetchSomeFiles(cleanUp, siteId, tag);
            TrafficCountingJourneyTest.counted(HohenheimTraffic.sourceOf(siteId), 6, began);
            McpHttpSession.Answer fetched = admin.call("traffic_read", Map.of("site", code, "period", "hour",
                "top", "files"));
            assertThat(fetched.error()).as("step 5: " + fetched.text()).isFalse();
            assertThat(fetched.text()).as("step 5: the files fetched")
                .contains("/beta/releases/Tadoma-1.2.0.dmg").contains("/beta/latest.json")
                .doesNotContain("/beta/missing.json");
        } finally {
            cleanUp.forEach(Runnable::run);
        }
    }

    /**
     * The site's form in a browser: the folded section after the kind's own settings, set, saved and reopened with
     * its values kept; then the Traffic tab lists the files fetched across the screen. Both are screenshot for the
     * board.
     */
    @Test
    void theSharingSectionIsSavedInTheFormAndTheTrafficTabListsTheFilesFetched() throws Exception {
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        try {
            Instant began = Now.instant();
            TrafficCountingJourneyTest.countUnder(cleanUp, "sharing-form-" + tag);
            HohenheimTraffic.start();
            Row site = ProxyTestSupport.setupSite("hohenheim:static", "Tadoma downloads " + tag, "form-" + tag,
                new LinkedHashMap<>(Map.of("own_files", true)));
            int siteId = site.get(SiteModel.ID);

            // 1. The section is folded; opened, it holds the three settings.
            navigateToApp("/admin/sites/" + siteId);
            waitForHydration();
            String section = "pl-card.zf-form-section[data-section$='" + HohenheimFormSections.SHARING + "']";
            assertThat(page.locator(section).getAttribute("data-collapsed")).as("step 1: folded").isEqualTo("true");
            Boolean afterAdvanced = (Boolean) page.evaluate("([advanced, section]) => !!(document.querySelector("
                    + "advanced).compareDocumentPosition(document.querySelector(section))"
                    + " & Node.DOCUMENT_POSITION_FOLLOWING)",
                List.of("pl-card.zf-form-section[data-section='" + FormSection.ADVANCED_ID + "']", section));
            System.out.println("SHARING section after the form's Advanced: " + afterAdvanced);
            page.click(section + " pl-collapsible-trigger button");
            page.waitForSelector(section + " pl-select[name='settings." + SiteSharing.READABLE_BY + "']");

            // 2. These websites, one website, and one path asked again; saved.
            chooseOption("settings." + SiteSharing.READABLE_BY, SiteSharing.Readers.LISTED.token());
            String websites = "pl-field[data-path='settings." + SiteSharing.READING_WEBSITES + "']";
            page.click(websites + " pl-button[data-array-add]");
            page.locator(websites + " input[name='settings." + SiteSharing.READING_WEBSITES + "']:not([type='hidden'])")
                .last().fill(READER);
            String keep = "pl-field[data-path='settings." + SiteSharing.BROWSER_KEEP + "']";
            page.click(keep + " pl-button[data-key-value-add]");
            page.locator(keep + " pl-input[name$='.key'] input").last().fill("/beta/latest.json");
            String choice = keep + " pl-select[name$='.value']";
            chooseOptionOf(choice, SiteSharing.Keep.ASK_AGAIN.token());
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/site-sharing-section.png"))
                .setFullPage(true));
            page.click(".cms-form-actions pl-button[type='submit']");
            page.waitForSelector("text=have been saved");
            Map<?, ?> stored = (Map<?, ?>) Models.get(SiteModel.class).findById(siteId).get(SiteModel.SETTINGS);
            assertThat(stored.get(SiteSharing.READABLE_BY)).as("step 2: saved").isEqualTo("listed");
            assertThat(stored.get(SiteSharing.READING_WEBSITES)).as("step 2: the website").isEqualTo(List.of(READER));
            assertThat(stored.get(SiteSharing.BROWSER_KEEP)).as("step 2: the path")
                .isEqualTo(Map.of("/beta/latest.json", "ask_again"));
            assertThat(stored.get("own_files")).as("step 2: the kind's own settings kept").isEqualTo(true);

            // 3. Reopened, the values are kept on screen.
            navigateToApp("/admin/sites/" + siteId);
            waitForHydration();
            assertThat(page.locator("pl-select[name='settings." + SiteSharing.READABLE_BY + "'] .pl-select-value")
                .textContent()).as("step 3: the choice").contains("These websites");
            assertThat(page.locator(websites + " input[name='settings." + SiteSharing.READING_WEBSITES
                + "']:not([type='hidden'])").first().inputValue()).as("step 3: the website").isEqualTo(READER);
            assertThat(page.locator(choice + " .pl-select-value").first().textContent())
                .as("step 3: the path's choice in words").contains("Always ask again");

            // 4. Files fetched on the Traffic tab, across the screen, after real requests.
            fetchSomeFiles(cleanUp, siteId, tag);
            TrafficCountingJourneyTest.counted(HohenheimTraffic.sourceOf(siteId), 6, began);
            navigateToApp(CmsRoutes.subpage(HohenheimSlugs.ADMIN, HohenheimSlugs.SITES, siteId,
                HohenheimSlugs.Tab.TRAFFIC).toUrl());
            waitForHydration();
            String table = "pl-card[data-hh-traffic-table='files']";
            page.waitForSelector(table);
            assertThat(page.locator("pl-grid " + table).count()).as("step 4: across the screen, not paired")
                .isZero();
            assertThat(page.locator(table + " pl-table-head").allTextContents().stream().map(String::strip).toList())
                .as("step 4: its columns").containsExactly("Path", "Fetched", "Sent", "Last fetched");
            assertThat(page.locator(table).textContent()).as("step 4: the files")
                .contains("/beta/releases/Tadoma-1.2.0.dmg").contains("/beta/latest.json")
                .doesNotContain("/beta/missing.json");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/site-traffic-files-fetched.png"))
                .setFullPage(true));
        } finally {
            cleanUp.forEach(Runnable::run);
        }
    }

    // -- plumbing -------------------------------------------------------------

    /**
     * Puts two files in the site's own files and fetches them through the proxy: the installer twice whole and once
     * resumed as a range, the json twice, a missing file that counts as no file fetched.
     */
    private static void fetchSomeFiles(Deque<Runnable> cleanUp, int siteId, String tag) throws Exception {
        Path files = storage.resolve(SiteFiles.prefixOf(siteId));
        write(files.resolve("beta/latest.json"), "{\"version\":\"1.2.0\"}");
        write(files.resolve("beta/releases/Tadoma-1.2.0.dmg"), "d".repeat(4096));
        String host = "fetch-" + siteId + "-" + tag + ".share.test";
        ProxyTestSupport.addDomain(Models.get(SiteModel.class).findById(siteId), host, "exact", null, false);
        ProxyServer proxy = ProxyTestSupport.startProxy();
        cleanUp.push(proxy::stop);
        int port = ProxyTestSupport.httpPort(proxy);
        String installer = "/beta/releases/Tadoma-1.2.0.dmg";
        assertThat(ProxyTestSupport.rawRequest(port, host, installer)).startsWith("HTTP/1.1 200");
        assertThat(ProxyTestSupport.rawRequest(port, host, installer)).startsWith("HTTP/1.1 200");
        assertThat(ProxyTestSupport.rawRequest(port, host, installer, "Range: bytes=2048-")).startsWith(
            "HTTP/1.1 206");
        assertThat(ProxyTestSupport.rawRequest(port, host, "/beta/latest.json")).startsWith("HTTP/1.1 200");
        assertThat(ProxyTestSupport.rawRequest(port, host, "/beta/latest.json")).startsWith("HTTP/1.1 200");
        assertThat(ProxyTestSupport.rawRequest(port, host, "/beta/missing.json")).startsWith("HTTP/1.1 404");
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String code(String answer, String step) {
        Matcher found = SITE_CODE.matcher(answer);
        assertThat(found.find()).as(step + ": the answer names the site's code: " + answer).isTrue();
        return found.group();
    }
}
