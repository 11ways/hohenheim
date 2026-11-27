package be.elevenways.hohenheim.test;

import be.elevenways.hawkeye.common.form.FormChanges;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.ProtectedPathParts;
import be.elevenways.hohenheim.server.files.SiteFiles;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.hohenheim.server.upstream.kinds.SiteSharing;
import be.elevenways.hohenheim.server.upstream.kinds.StaticUpstreamKind;
import be.elevenways.hohenheim.site.ProtectPath;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.zenit.server.storage.LocalDiskStorageAdapter;
import be.elevenways.zenit.server.storage.Storages;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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
     * for a website without its scheme and a path without its slash, takes a path away, and reads the files fetched;
     * the site's manager, no administrator, then changes its sharing and listing by tool and is refused a host
     * setting and the trust by name, which the administrator still changes.
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

            // 6. A person managing the site, no administrator, changes what it shares and what its folders list by
            //    tool; a host setting and the trust are refused by name in the same call, and nothing of it is kept.
            UUID managerId = ApiSupport.user("sharing-tool-" + tag + "@hohenheim.local", "Sharing Tool Manager");
            RecordGrants.grant(GrantSubjectType.USER, managerId, SiteModel.MODEL_ID, siteId,
                HohenheimCapabilities.MANAGE, true);
            McpHttpSession manager = HohenheimMcpTest.presetSession(server, endpoint, managerId,
                "Sharing Tool Manager");
            McpHttpSession.Answer shared = manager.call("site_static_files_set", Map.of("code", code,
                SiteSharing.READABLE_BY, "any", StaticUpstreamKind.SHOW_HIDDEN_FILES.getName(), true,
                SiteSharing.BROWSER_KEEP, List.of(Map.of("path", "/beta/releases/", "keep", "year"))));
            assertThat(shared.error()).as("step 6: " + shared.text()).isFalse();
            Row managed = Models.get(SiteModel.class).findById(siteId);
            stored = (Map<?, ?>) managed.get(SiteModel.SETTINGS);
            assertThat(stored.get(SiteSharing.READABLE_BY)).as("step 6: any website").isEqualTo("any");
            assertThat(stored.get(StaticUpstreamKind.SHOW_HIDDEN_FILES.getName())).as("step 6: hidden files listed")
                .isEqualTo(true);
            assertThat(stored.get(SiteSharing.BROWSER_KEEP)).as("step 6: a path added, the other kept")
                .isEqualTo(Map.of("/beta/latest.json", "ask_again", "/beta/releases/", "year"));
            assertThat(stored.get(StaticUpstreamKind.OWN_FILES.getName())).as("step 6: the kind's own kept")
                .isEqualTo(true);
            assertThat(stored.get(StaticUpstreamKind.AUTOINDEX.getName())).as("step 6: a listing left out kept")
                .isNotEqualTo(false);
            assertThat((String) managed.get(SiteModel.NAME)).as("step 6: the name kept").isEqualTo("Shared tool " + tag);
            assertThat((Boolean) managed.get(SiteModel.ENABLED)).as("step 6: still on").isTrue();
            for (Map.Entry<String, Object> host : Map.<String, Object>of(StaticUpstreamKind.ROOT_PATH.getName(), "/etc",
                    SiteModel.TRUSTED_UPSTREAM.getName(), true).entrySet()) {
                McpHttpSession.Answer refused = manager.call("site_static_files_set", Map.of("code", code,
                    SiteSharing.READABLE_BY, "nobody", host.getKey(), host.getValue()));
                assertThat(refused.error()).as("step 6: %s refused %s", host.getKey(), refused.text()).isTrue();
                assertThat(refused.text()).as("step 6: named").contains(host.getKey());
            }
            managed = Models.get(SiteModel.class).findById(siteId);
            assertThat(((Map<?, ?>) managed.get(SiteModel.SETTINGS)).get(SiteSharing.READABLE_BY))
                .as("step 6: THE ASSERTION -- a refused call keeps nothing").isEqualTo("any");
            assertThat((Boolean) managed.get(SiteModel.TRUSTED_UPSTREAM)).as("step 6: no trust").isNotEqualTo(true);

            // 7. The administrator still changes the trust by the same tool.
            McpHttpSession.Answer trusted = admin.call("site_static_files_set", Map.of("code", code,
                SiteModel.TRUSTED_UPSTREAM.getName(), true));
            assertThat(trusted.error()).as("step 7: " + trusted.text()).isFalse();
            assertThat((Boolean) Models.get(SiteModel.class).findById(siteId).get(SiteModel.TRUSTED_UPSTREAM))
                .as("step 7: trusted by the administrator").isTrue();
        } finally {
            cleanUp.forEach(Runnable::run);
        }
    }

    /**
     * The site's form in a browser as the approved board draws it: the folded section after Advanced, the band, the
     * websites only for "These websites", the add buttons saying what they add, a path suggested from the site's own
     * files, its choice read in words before the save, and the quiet last row; saved, reopened, switched away and
     * saved again. Then the Traffic tab lists the files fetched across the screen. Both are screenshot for the board.
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
            putFiles(siteId);

            // 1. The section is folded and stands after the form's Advanced section.
            navigateToApp("/admin/sites/" + siteId);
            waitForHydration();
            String section = "pl-card.zf-form-section[data-section$='" + HohenheimFormSections.SHARING + "']";
            assertThat(page.locator(section).getAttribute("data-collapsed")).as("step 1: folded").isEqualTo("true");
            Boolean afterAdvanced = (Boolean) page.evaluate("([advanced, section]) => !!(document.querySelector("
                    + "advanced).compareDocumentPosition(document.querySelector(section))"
                    + " & Node.DOCUMENT_POSITION_FOLLOWING)",
                List.of("pl-card.zf-form-section[data-section='" + FormSection.ADVANCED_ID + "']", section));
            assertThat(afterAdvanced).as("step 1: after Advanced").isTrue();
            page.click(section + " pl-collapsible-trigger button");
            String band = section + " pl-toggle-group[name='settings." + SiteSharing.READABLE_BY + "']";
            page.waitForSelector(band);
            String websites = "pl-field[data-path='settings." + SiteSharing.READING_WEBSITES + "']";
            assertThat(page.locator(websites).isVisible()).as("step 1: no websites while nobody reads").isFalse();
            String privatePaths = section + " pl-field[data-zf-related-list='" + SiteSharing.PRIVATE_PATHS + "']";
            assertThat(page.locator(privatePaths + " pl-label").textContent().strip()).as("step 1: the third part")
                .isEqualTo("Private paths");
            assertThat(page.locator(privatePaths + " [data-related-empty]").textContent().strip())
                .as("step 1: none yet, in one quiet line").isEqualTo("No private paths.");
            assertThat(page.locator(privatePaths + " [data-related-actions] pl-button").textContent().strip())
                .as("step 1: the site's own Protect a path").isEqualTo("Protect a path");

            // 2. These websites shows the list; its button and the paths' say what they add; the last row is quiet.
            page.click(band + " pl-toggle-group-item[data-value='" + SiteSharing.Readers.LISTED.token() + "'] button");
            page.waitForSelector(websites, new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
            assertThat(page.locator(websites + " pl-button[data-array-add]").textContent().strip())
                .as("step 2: the websites' button").isEqualTo("Add website");
            page.click(websites + " pl-button[data-array-add]");
            page.locator(websites + " input[name='settings." + SiteSharing.READING_WEBSITES + "']:not([type='hidden'])")
                .last().fill(READER);
            assertThat(page.locator(websites + " [data-array-move-up]").count()).as("step 2: a website has no order")
                .isZero();
            assertThat(page.locator(websites + " [data-array-remove]").count()).as("step 2: but is removed").isOne();
            String keep = "pl-field[data-path='settings." + SiteSharing.BROWSER_KEEP + "']";
            assertThat(page.locator(keep + " pl-button[data-key-value-add]").textContent().strip())
                .as("step 2: the paths' button").isEqualTo("Add a path");
            assertThat(page.locator(keep + " [data-key-value-rest]").textContent())
                .as("step 2: the quiet last row").contains("Everything else").contains("Decide by itself");

            // 3. A path typed is suggested from the site's own files; its choice reads in words before the save.
            page.click(keep + " pl-button[data-key-value-add]");
            String path = keep + " pl-select[name$='.key']";
            page.locator(path + " .pl-select-field").last().click();
            page.locator("he-bottom .pl-select-popup[data-open] .pl-select-search input").fill("/beta/la");
            String suggested = "he-bottom .pl-select-popup[data-open] div[role='option']"
                + "[data-value='/beta/latest.json']";
            page.waitForSelector(suggested);
            page.click(suggested);
            page.waitForSelector("he-bottom .pl-select-popup[data-open]",
                new Page.WaitForSelectorOptions().setState(WaitForSelectorState.DETACHED));
            String choice = keep + " pl-select[name$='.value']";
            chooseOptionOf(choice, SiteSharing.Keep.ASK_AGAIN.token());
            assertThat(page.locator(choice + " .pl-select-value").first().textContent())
                .as("step 3: the choice in words before the save").contains("Always ask again");
            page.click(".cms-form-actions pl-button[type='submit']");
            page.waitForSelector("text=have been saved");
            Map<?, ?> stored = (Map<?, ?>) Models.get(SiteModel.class).findById(siteId).get(SiteModel.SETTINGS);
            assertThat(stored.get(SiteSharing.READABLE_BY)).as("step 3: saved").isEqualTo("listed");
            assertThat(stored.get(SiteSharing.READING_WEBSITES)).as("step 3: the website").isEqualTo(List.of(READER));
            assertThat(stored.get(SiteSharing.BROWSER_KEEP)).as("step 3: the suggested path")
                .isEqualTo(Map.of("/beta/latest.json", "ask_again"));
            assertThat(stored.get("own_files")).as("step 3: the kind's own settings kept").isEqualTo(true);

            // 4. Reopened, the values are kept on screen, and the path protected since is listed with how one gets in
            // and an Edit link to its page; switched away and back, the typed website is still there.
            Map<String, Object> protect = new LinkedHashMap<>();
            protect.put(ProtectPath.PATH.getName(), "/beta/");
            protect.put(ProtectPath.METHOD.getName(), ProtectPath.METHOD_SIGNED_LINK);
            OperationPipeline.invoke(OperationRequest.of(ProtectPath.OPERATION, CmsPlacementSurface.ADMIN_ACTION)
                .caller(TenantConduits.operator()).subjects(List.of(Models.get(SiteModel.class).findById(siteId)))
                .form(protect));
            Row beta = Objects.requireNonNull(Models.get(ProtectedPathModel.class).find()
                .where(ProtectedPathModel.SITE_ID.eq(siteId)).first(), "step 4: /beta/ is protected");
            navigateToApp("/admin/sites/" + siteId);
            waitForHydration();
            page.click(section + " pl-collapsible-trigger button");
            page.waitForSelector(band);
            assertThat(page.locator(band + " pl-toggle-group-item[data-value='" + SiteSharing.Readers.LISTED.token()
                + "'] button").getAttribute("aria-checked")).as("step 4: the choice").isEqualTo("true");
            String typedWebsite = websites + " input[name='settings." + SiteSharing.READING_WEBSITES
                + "']:not([type='hidden'])";
            assertThat(page.locator(typedWebsite).first().inputValue()).as("step 4: the website").isEqualTo(READER);
            assertThat(page.locator(choice + " .pl-select-value").first().textContent())
                .as("step 4: the path's choice in words").contains("Always ask again");
            String row = privatePaths + " [data-related-row]";
            assertThat(page.locator(row).count()).as("step 4: one private path").isOne();
            assertThat(page.locator(row + " [data-column='path']").textContent().strip()).as("step 4: its path")
                .isEqualTo("/beta");
            assertThat(page.locator(row + " [data-column='" + ProtectedPathParts.WAY_COLUMN + "']").textContent()
                .strip()).as("step 4: how one gets in, the way's own label").isEqualTo("With a signed link");
            assertThat(page.locator(row + " [data-related-opener]").textContent().strip()).as("step 4: Edit")
                .isEqualTo("Edit");
            assertThat(page.locator(row + " [data-related-opener]").getAttribute("href")).as("step 4: to its page")
                .contains(HohenheimSlugs.PROTECTED_PATHS + "/" + beta.get(ProtectedPathModel.ID));
            assertThat(page.locator(privatePaths + " [data-related-empty]").count()).as("step 4: no empty line")
                .isZero();
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/site-sharing-section.png"))
                .setFullPage(true));
            assertThat(page.locator(row + " input, " + row + " select, " + row + " pl-select").count())
                .as("step 4: read only, no second writer").isZero();
            // The path is no longer protected, so the files under /beta/ are fetched in step 6.
            Models.get(ProtectedPathModel.class).delete(beta.get(ProtectedPathModel.ID));
            page.click(band + " pl-toggle-group-item[data-value='" + SiteSharing.Readers.ANY.token() + "'] button");
            page.waitForSelector(websites, new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
            page.click(band + " pl-toggle-group-item[data-value='" + SiteSharing.Readers.LISTED.token() + "'] button");
            page.waitForSelector(websites, new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
            assertThat(page.locator(typedWebsite).first().inputValue()).as("step 4: kept within the form")
                .isEqualTo(READER);

            // 5. Any website saved: the websites typed before are dropped with the choice.
            page.click(band + " pl-toggle-group-item[data-value='" + SiteSharing.Readers.ANY.token() + "'] button");
            page.click(".cms-form-actions pl-button[type='submit']");
            page.waitForSelector("text=have been saved");
            stored = (Map<?, ?>) Models.get(SiteModel.class).findById(siteId).get(SiteModel.SETTINGS);
            assertThat(stored.get(SiteSharing.READABLE_BY)).as("step 5: any website").isEqualTo("any");
            assertThat(stored.containsKey(SiteSharing.READING_WEBSITES)).as("step 5: no websites kept").isFalse();
            assertThat(stored.get(SiteSharing.BROWSER_KEEP)).as("step 5: the path kept")
                .isEqualTo(Map.of("/beta/latest.json", "ask_again"));

            // 6. Files fetched on the Traffic tab, across the screen, after real requests.
            fetchSomeFiles(cleanUp, siteId, tag);
            TrafficCountingJourneyTest.counted(HohenheimTraffic.sourceOf(siteId), 6, began);
            navigateToApp(CmsRoutes.subpage(HohenheimSlugs.ADMIN, HohenheimSlugs.SITES, siteId,
                HohenheimSlugs.Tab.TRAFFIC).toUrl());
            waitForHydration();
            String table = "pl-card[data-hh-traffic-table='files']";
            page.waitForSelector(table);
            assertThat(page.locator("pl-grid " + table).count()).as("step 6: across the screen, not paired")
                .isZero();
            assertThat(page.locator(table + " pl-table-head").allTextContents().stream().map(String::strip).toList())
                .as("step 6: its columns").containsExactly("Path", "Fetched", "Sent", "Last fetched");
            assertThat(page.locator(table).textContent()).as("step 6: the files")
                .contains("/beta/releases/Tadoma-1.2.0.dmg").contains("/beta/latest.json")
                .doesNotContain("/beta/missing.json");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/site-traffic-files-fetched.png"))
                .setFullPage(true));
        } finally {
            cleanUp.forEach(Runnable::run);
        }
    }

    /**
     * The site's private paths as a person meets them: one quiet line on a site with none; "Protect a path" from the
     * part, with unsaved changes in the site's form, posts only its own fields, makes the path and asks before the
     * page leaves the unsaved form; the path then lists in one line with how one gets in; the Protection tab names the
     * way in its column; a person who manages the site without being an administrator sees and saves its sharing and
     * listing settings on /manage and no host setting, a crafted post of one changes nothing, and meets the paths on
     * its Protection tab there.
     */
    @Test
    void privatePathsListInOneLineAndAreProtectedFromThePartAlone() {
        String tag = HohenheimMcpTest.letters();
        Row site = ProxyTestSupport.setupSite("hohenheim:static", "Private paths " + tag, "private-" + tag,
            new LinkedHashMap<>(Map.of("own_files", true)));
        int siteId = site.get(SiteModel.ID);
        String section = "pl-card.zf-form-section[data-section$='" + HohenheimFormSections.SHARING + "']";
        String privatePaths = section + " pl-field[data-zf-related-list='" + SiteSharing.PRIVATE_PATHS + "']";
        String protectButton = privatePaths + " [data-related-actions] pl-button";
        String sheet = ".pl-sheet-panel[data-open]";
        List<String> posted = new CopyOnWriteArrayList<>();
        page.onRequest(request -> {
            if ("POST".equals(request.method())) {
                posted.add(request.url() + " " + request.postData());
            }
        });
        List<Dialog> dialogs = new CopyOnWriteArrayList<>();
        AtomicBoolean leave = new AtomicBoolean(false);
        page.onDialog(dialog -> {
            dialogs.add(dialog);
            if (leave.get()) {
                dialog.accept();
            } else {
                dialog.dismiss();
            }
        });

        // 1. A site with no protected path: the part's label, one quiet line and the button, nothing else.
        navigateToApp("/admin/sites/" + siteId);
        waitForHydration();
        page.click(section + " pl-collapsible-trigger button");
        page.waitForSelector(privatePaths + " [data-related-empty]");
        assertThat(page.locator(privatePaths + " [data-related-empty]").textContent().strip())
            .as("step 1: one quiet line").isEqualTo("No private paths.");
        assertThat(page.locator(privatePaths + " [data-related-row]").count()).as("step 1: no row").isZero();
        assertThat(page.locator(protectButton).textContent().strip()).as("step 1: the button")
            .isEqualTo("Protect a path");
        page.locator(privatePaths).screenshot(new Locator.ScreenshotOptions()
            .setPath(Path.of("build/site-sharing-private-paths-empty.png")));

        // 2. With an unsaved name in the site's form, Protect a path opens its own sheet: the site's form is neither
        //    submitted nor checked, and stays unsaved.
        String form = "form.cms-form-layout";
        String name = form + " pl-field[data-path='" + SiteModel.NAME.getName() + "'] input:not([type='hidden'])";
        String unsavedName = "Renamed " + tag;
        page.locator(name).fill(unsavedName);
        page.waitForSelector(form + "[" + FormChanges.UNSAVED_ATTRIBUTE + "]");
        String formUrl = page.url();
        page.click(protectButton);
        page.waitForSelector(sheet + " pl-field[data-path='" + ProtectPath.PATH.getName()
            + "'] input:not([type='hidden'])");
        assertThat(page.url()).as("step 2: the page stays").isEqualTo(formUrl);
        assertThat(page.locator(form + "[" + FormChanges.UNSAVED_ATTRIBUTE + "]").count())
            .as("step 2: the site's form is still unsaved").isOne();

        // 3. The sheet posts its own fields alone: the path is made, and the page asks before leaving the unsaved
        //    form for the path's page; staying keeps the typed name, which was never saved.
        page.locator(sheet + " pl-field[data-path='" + ProtectPath.PATH.getName() + "'] input:not([type='hidden'])")
            .fill("/private");
        chooseOptionOf(sheet + " pl-field[data-path='" + ProtectPath.METHOD.getName() + "'] pl-select",
            ProtectPath.METHOD_SIGNED_LINK);
        page.click(sheet + " pl-button[data-cms-confirm-ok]");
        page.waitForCondition(() -> Models.get(ProtectedPathModel.class).find()
            .where(ProtectedPathModel.SITE_ID.eq(siteId)).first() != null);
        page.waitForCondition(() -> !dialogs.isEmpty());
        assertThat(dialogs.getFirst().type()).as("step 3: the usual question before leaving")
            .isIn("confirm", "beforeunload");
        waitForSettledFrames();
        assertThat(page.url()).as("step 3: staying keeps the page").isEqualTo(formUrl);
        assertThat(page.locator(name).inputValue()).as("step 3: and the typed name").isEqualTo(unsavedName);
        assertThat((String) Models.get(SiteModel.class).findById(siteId).get(SiteModel.NAME))
            .as("step 3: which was never saved").isEqualTo("Private paths " + tag);
        assertThat(posted).as("step 3: the sheet posted").anyMatch(sent -> sent.contains("protect_path"));
        assertThat(posted).as("step 3: THE ASSERTION -- nothing posted the site's form fields")
            .noneMatch(sent -> sent.contains(unsavedName.replace(' ', '+'))
                || sent.contains(unsavedName.replace(" ", "%20")) || sent.contains(unsavedName));

        // 4. Leaving and opening the form again, the new path is one line: its path, how one gets in, Edit.
        leave.set(true);
        navigateToApp("/admin/sites/" + siteId);
        waitForHydration();
        page.click(section + " pl-collapsible-trigger button");
        String row = privatePaths + " [data-related-row]";
        page.waitForSelector(row);
        assertThat(page.locator(row + " [data-column='path']").textContent().strip()).as("step 4: its path")
            .isEqualTo("/private");
        assertThat(page.locator(row + " [data-column='path'][data-code]").count()).as("step 4: as typed text")
            .isOne();
        assertThat(page.locator(row + " [data-column='" + ProtectedPathParts.WAY_COLUMN + "']").textContent().strip())
            .as("step 4: how one gets in").isEqualTo("With a signed link");
        assertThat(page.locator(row + " [data-related-opener]").textContent().strip()).as("step 4: Edit")
            .isEqualTo("Edit");
        Boolean oneLine = (Boolean) page.evaluate("row => { const boxes = [...document.querySelector(row).children]"
            + ".map(child => child.getBoundingClientRect()); return Math.max(...boxes.map(box => box.top))"
            + " < Math.min(...boxes.map(box => box.bottom)); }", row);
        assertThat(oneLine).as("step 4: THE ASSERTION -- path, way and Edit on one line").isTrue();
        page.locator(section).screenshot(new Locator.ScreenshotOptions()
            .setPath(Path.of("build/site-sharing-section-private-path.png")));

        // 5. The Protection tab says how one gets in, in its own column.
        navigateToApp(CmsRoutes.subpage(HohenheimSlugs.ADMIN, HohenheimSlugs.SITES, siteId,
            HohenheimSlugs.PROTECTED_PATHS).toUrl());
        waitForHydration();
        String paths = "[data-cms-child-list='" + HohenheimSlugs.PROTECTED_PATHS + "']";
        page.waitForSelector(paths);
        assertThat(page.locator(paths).textContent()).as("step 5: the column and the way")
            .contains("How visitors get in").contains("/private").contains("With a signed link");
        page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/site-protection-tab.png"))
            .setFullPage(true));

        // 6. A person managing the site, no administrator, sees its Sharing section on /manage with the path and how
        //    one gets in, and its folders' listing settings, but no host setting: no folder, storage or trust.
        UUID managerId = ApiSupport.user("private-paths-" + tag + "@hohenheim.local", "Private Paths Manager");
        RecordGrants.grant(GrantSubjectType.USER, managerId, SiteModel.MODEL_ID, siteId,
            HohenheimCapabilities.MANAGE, true);
        String managerSession = sessionFor(managerId).token();
        navigateToAppAs("/" + HohenheimSlugs.MANAGE + "/sites/" + siteId, managerSession);
        waitForHydration();
        page.waitForSelector(name);
        assertThat(page.locator(section).count()).as("step 6: the Sharing section").isOne();
        assertThat(page.locator("pl-field[data-path='settings." + StaticUpstreamKind.AUTOINDEX.getName() + "']")
            .count()).as("step 6: what its folders list").isOne();
        for (String host : List.of(StaticUpstreamKind.ROOT_PATH.getName(), StaticUpstreamKind.STORAGE.getName(),
                StaticUpstreamKind.OWN_FILES.getName())) {
            assertThat(page.locator("pl-field[data-path='settings." + host + "']").count())
                .as("step 6: no %s for a manager", host).isZero();
        }
        assertThat(page.locator("pl-field[data-path='" + SiteModel.TRUSTED_UPSTREAM.getName() + "']").count())
            .as("step 6: no trust").isZero();
        page.click(section + " pl-collapsible-trigger button");
        page.waitForSelector(privatePaths + " [data-related-row]");
        assertThat(page.locator(privatePaths + " [data-related-row]").textContent()).as("step 6: the private path")
            .contains("/private").contains("With a signed link");
        page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/site-form-manage.png"))
            .setFullPage(true));

        // 7. The manager saves a sharing setting there; the kind and its own settings are kept as stored.
        String band = section + " pl-toggle-group[name='settings." + SiteSharing.READABLE_BY + "']";
        page.click(band + " pl-toggle-group-item[data-value='" + SiteSharing.Readers.ANY.token() + "'] button");
        page.click(".cms-form-actions pl-button[type='submit']");
        page.waitForSelector("text=have been saved");
        Row saved = Models.get(SiteModel.class).findById(siteId);
        Map<?, ?> settings = (Map<?, ?>) saved.get(SiteModel.SETTINGS);
        assertThat(settings.get(SiteSharing.READABLE_BY)).as("step 7: saved by the manager").isEqualTo("any");
        assertThat(settings.get(StaticUpstreamKind.OWN_FILES.getName())).as("step 7: the kind's own kept")
            .isEqualTo(true);
        assertThat((String) saved.get(SiteModel.UPSTREAM_KIND)).as("step 7: the kind kept")
            .isEqualTo("hohenheim:static");

        // 8. A crafted post of the same form naming a host setting, the trust and another kind changes nothing.
        navigateToAppAs("/" + HohenheimSlugs.MANAGE + "/sites/" + siteId, managerSession);
        waitForHydration();
        page.waitForSelector(name);
        page.locator(name).fill("Crafted " + tag);
        page.evaluate("form => { for (const [key, value] of [['settings."
            + StaticUpstreamKind.ROOT_PATH.getName() + "', '/etc'], ['" + SiteModel.TRUSTED_UPSTREAM.getName()
            + "', 'true'], ['" + SiteModel.UPSTREAM_KIND.getName() + "', 'hohenheim:address']]) {"
            + " const input = document.createElement('input'); input.type = 'hidden'; input.name = key;"
            + " input.value = value; document.querySelector(form).appendChild(input); } }", form);
        page.waitForResponse(response -> "POST".equals(response.request().method()),
            () -> page.click(".cms-form-actions pl-button[type='submit']"));
        Row crafted = Models.get(SiteModel.class).findById(siteId);
        assertThat(((Map<?, ?>) crafted.get(SiteModel.SETTINGS)).containsKey(StaticUpstreamKind.ROOT_PATH.getName()))
            .as("step 8: THE ASSERTION -- no folder of the host").isFalse();
        assertThat((Boolean) crafted.get(SiteModel.TRUSTED_UPSTREAM)).as("step 8: no trust").isNotEqualTo(true);
        assertThat((String) crafted.get(SiteModel.UPSTREAM_KIND)).as("step 8: the kind kept")
            .isEqualTo("hohenheim:static");

        // 9. The site's Protection tab lists the path with how one gets in.
        navigateToAppAs(CmsRoutes.subpage(HohenheimSlugs.MANAGE, HohenheimSlugs.SITES, siteId,
            HohenheimSlugs.PROTECTED_PATHS).toUrl(), managerSession);
        waitForHydration();
        page.waitForSelector(paths);
        assertThat(page.locator(paths).textContent()).as("step 9: the path and how one gets in")
            .contains("/private").contains("With a signed link");
        page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/site-protection-tab-manage.png"))
            .setFullPage(true));
    }

    // -- plumbing -------------------------------------------------------------

    /** Puts two files in the site's own files: the json the account page reads and an installer. */
    private static void putFiles(int siteId) throws Exception {
        Path files = storage.resolve(SiteFiles.prefixOf(siteId));
        write(files.resolve("beta/latest.json"), "{\"version\":\"1.2.0\"}");
        write(files.resolve("beta/releases/Tadoma-1.2.0.dmg"), "d".repeat(4096));
    }

    /**
     * Puts the two files in the site's own files and fetches them through the proxy: the installer twice whole and
     * once resumed as a range, the json twice, a missing file that counts as no file fetched.
     */
    private static void fetchSomeFiles(Deque<Runnable> cleanUp, int siteId, String tag) throws Exception {
        putFiles(siteId);
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
