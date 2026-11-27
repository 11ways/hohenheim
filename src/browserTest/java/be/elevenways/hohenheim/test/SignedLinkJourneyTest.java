package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.AccessRuleParts;
import be.elevenways.hohenheim.server.files.SiteFiles;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.site.ProtectPath;
import be.elevenways.hohenheim.test.stack.StackServiceSecretsTest;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.ApiKeyModel;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.zenit.server.storage.LocalDiskStorageAdapter;
import be.elevenways.zenit.server.storage.Storages;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A folder of a site opened only by a signed link, on the wire: Roel's closed beta. The panel protects /beta/ of an
 * own-files site with a signed link and shows its secret once; a request without, with an expired, a tampered, a
 * wrong-secret or a too-long signature, or a POST, gets the not-found page; a valid one gets the file whole and by
 * range, and the rest of the site stays public. The secret is replaced (both work), the old one ended (only the new
 * one works), links are made by tool, and a proxied site behind the same protection never sees the two parameters.
 *
 * AIDEV-NOTE: every signature here is made with javax.crypto.Mac directly, never with the product's SignedLink, so
 * the documented form ("<path>\n<expires>", lowercase hex HMAC-SHA256, the secret's text as the key) is what the
 * proxy is proven to enforce.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class SignedLinkJourneyTest extends HohenheimHttpTestBase {

    private static final Pattern LINK = Pattern.compile("(https?://[^/\\s]+)?(/\\S*?)\\?expires=(\\d+)&signature="
        + "([0-9a-f]{64})");

    private static Path storage;

    @BeforeAll
    static void mountStorage() throws Exception {
        storage = Files.createTempDirectory("hh-signed-link");
        Storages.replace(SiteFiles.STORAGE, new LocalDiskStorageAdapter(storage));
    }

    @AfterAll
    static void unmountStorage() {
        Storages.unregister(SiteFiles.STORAGE);
    }

    @Test
    void aSignedLinkOpensAPrivateFolderOfAnyKindOfSite() throws Exception {
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        ProxyServer proxy = null;
        HttpServer upstream = null;
        AtomicInteger upstreamHits = new AtomicInteger();
        AtomicReference<String> upstreamQuery = new AtomicReference<>();
        try {
            // 0. An own-files site at an address holding an installer under /beta/ and a public file, and a proxied
            //    site whose app records the query it receives.
            Row site = ProxyTestSupport.setupSite("hohenheim:static", "Signed downloads " + tag, "signed-" + tag,
                new LinkedHashMap<String, Object>(Map.of("own_files", true)));
            int siteId = site.get(SiteModel.ID);
            cleanUp.push(() -> HardDeletes.byId(Models.get(SiteModel.class), siteId));
            String host = "downloads-" + tag + ".signed.test";
            ProxyTestSupport.addDomain(site, host, "exact", null, false);
            byte[] installer = bytes(300 * 1024);
            Path files = storage.resolve(SiteFiles.prefixOf(siteId));
            Files.createDirectories(files.resolve("beta/releases"));
            Files.write(files.resolve("beta/releases/app.dmg"), installer);
            Files.writeString(files.resolve("public.txt"), "public " + tag);
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            upstream.createContext("/", exchange -> {
                upstreamHits.incrementAndGet();
                upstreamQuery.set(String.valueOf(exchange.getRequestURI().getRawQuery()));
                byte[] body = ("app " + exchange.getRequestURI().getRawPath()).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            upstream.start();
            Row proxied = ProxyTestSupport.setupSite("hohenheim:address", "Signed app " + tag, "signed-app-" + tag,
                Map.of("forward_host", "127.0.0.1", "forward_port", upstream.getAddress().getPort()));
            int proxiedId = proxied.get(SiteModel.ID);
            cleanUp.push(() -> HardDeletes.byId(Models.get(SiteModel.class), proxiedId));
            String appHost = "app-" + tag + ".signed.test";
            ProxyTestSupport.addDomain(proxied, appHost, "exact", null, false);
            proxy = ProxyTestSupport.startProxy();
            int port = ProxyTestSupport.httpPort(proxy);

            // 1. The panel's "Protect a path" with a signed link lands on the path's Signed link tab, which shows the
            //    secret Hohenheim made once; it is stored as an envelope, never as its text.
            HttpResponse<String> protect = adminPostForm(siteInvoke(ProtectPath.OPERATION, siteId),
                confirmed("path=%2Fbeta&method=" + ProtectPath.METHOD_SIGNED_LINK));
            assertThat(protect.statusCode()).as("step 1: protected " + protect.body()).isIn(302, 303);
            Row path = Objects.requireNonNull(Models.get(ProtectedPathModel.class).find()
                .where(ProtectedPathModel.SITE_ID.eq(siteId)).first(), "step 1: the protected path");
            int pathId = path.get(ProtectedPathModel.ID);
            cleanUp.push(() -> forget(path));
            String tab = CmsRoutes.subpage(HohenheimSlugs.ADMIN, HohenheimSlugs.PROTECTED_PATHS, pathId,
                HohenheimSlugs.Tab.SIGNED_LINK).toUrl();
            assertThat(landingOf(protect)).as("step 1: onto the tab that shows the secret").isEqualTo(tab);
            Row rule = ruleOf(path);
            String first = secretOf(rule);
            assertThat(first).as("step 1: 32 random bytes as URL-safe text").matches("[A-Za-z0-9_-]{43}");
            String stored = String.join("", StackServiceSecretsTest.rawColumn(
                "SELECT data FROM access_rules WHERE id = ?", rule.get(AccessRuleModel.ID)));
            assertThat(stored).as("step 1: an envelope at rest").contains("zenc$").doesNotContain(first);
            String shown = rendered(adminGet(tab).body());
            assertThat(shown).as("step 1: shown once on the tab").contains(first)
                .contains("ends in " + first.substring(39));
            String again = rendered(adminGet(tab).body());
            assertThat(again).as("step 1: and never again").doesNotContain(first)
                .contains("ends in " + first.substring(39));
            // The tab's worked example is a real link: the same Mac computes its signature.
            Matcher example = LINK.matcher(again.replace("&amp;", "&"));
            assertThat(example.find()).as("step 1: the tab shows a worked example").isTrue();
            assertThat(example.group(4)).as("step 1: the example is signed as documented")
                .isEqualTo(sign(first, example.group(2), Long.parseLong(example.group(3))));
            proxy.getDispatcher().reloadRoutes();

            // 2. On the wire: everything but a valid, unexpired signature is the not-found page; a valid one opens
            //    the file whole and by range; the rest of the site stays public.
            String file = "/beta/releases/app.dmg";
            long soon = Now.instant().getEpochSecond() + 600;
            assertNotFound(ProxyTestSupport.rawRequest(port, host, file), "step 2: without a signature");
            assertNotFound(ProxyTestSupport.rawRequest(port, host, signed(first, file, Now.instant().getEpochSecond()
                - 60)), "step 2: expired");
            String valid = signed(first, file, soon);
            assertNotFound(ProxyTestSupport.rawRequest(port, host, tampered(valid)), "step 2: tampered");
            assertNotFound(ProxyTestSupport.rawRequest(port, host, signed("not-the-secret", file, soon)),
                "step 2: another secret");
            assertNotFound(ProxyTestSupport.rawRequest(port, host, signed(first, file, Now.instant().getEpochSecond()
                + 8 * 24 * 3600)), "step 2: more than seven days ahead");
            assertNotFound(ProxyTestSupport.rawRequest(port, host, signed(first, "/beta/releases/other.dmg", soon)
                .replace("other.dmg", "app.dmg")), "step 2: a signature of another file");
            assertNotFound(ProxyTestSupport.rawRequestAs(port, "POST", host, valid), "step 2: a POST");
            ProxyTestSupport.Counted whole = ProxyTestSupport.rawDownload(port, host, valid);
            assertThat(whole.head()).as("step 2: a valid link opens the file").startsWith("HTTP/1.1 200");
            assertThat(whole.bodyBytes()).as("step 2: whole").isEqualTo(installer.length);
            assertThat(ProxyTestSupport.rawRequest(port, host, valid, "Range: bytes=0-9"))
                .as("step 2: and by range").startsWith("HTTP/1.1 206")
                .containsIgnoringCase("content-range: bytes 0-9/" + installer.length);
            assertThat(ProxyTestSupport.rawRequest(port, host, "/public.txt")).as("step 2: outside /beta/ public")
                .startsWith("HTTP/1.1 200").contains("public " + tag);

            // 3. By tool: the secret is replaced, never answered; both secrets open links; the read says the way
            //    in and the secrets by their ends, never a secret, and neither does the rule's read or history.
            URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
            McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
            String pathCode = Objects.requireNonNull(RecordCodes.codeUnjudged(ProtectedPathModel.MODEL_ID,
                String.valueOf(pathId)));
            McpHttpSession.Answer replaced = admin.call("protected_path_set", Map.of("code", pathCode,
                "signed_link_secret", "replace"));
            assertThat(replaced.error()).as("step 3: " + replaced.text()).isFalse();
            String second = secretOf(ruleOf(path));
            assertThat(second).as("step 3: a new secret").isNotEqualTo(first);
            assertThat(replaced.text()).as("step 3: where it waits, never the secret").contains("Signed link")
                .doesNotContain(second).doesNotContain(first);
            proxy.getDispatcher().reloadRoutes();
            assertThat(ProxyTestSupport.rawRequest(port, host, valid)).as("step 3: the old secret still opens")
                .startsWith("HTTP/1.1 200");
            String withNew = signed(second, file, soon);
            assertThat(ProxyTestSupport.rawRequest(port, host, withNew)).as("step 3: and the new one")
                .startsWith("HTTP/1.1 200");
            String read = admin.call("read", Map.of("codes", List.of(pathCode))).text();
            assertThat(read).as("step 3: the read says the way in").contains("With a signed link")
                .contains(second.substring(39)).contains(first.substring(39))
                .doesNotContain(second).doesNotContain(first);
            String ruleCode = Objects.requireNonNull(RecordCodes.codeUnjudged(AccessRuleModel.MODEL_ID,
                String.valueOf((Object) rule.get(AccessRuleModel.ID))));
            assertThat(admin.call("read", Map.of("codes", List.of(ruleCode))).text()).as("step 3: the rule's read")
                .doesNotContain(second).doesNotContain(first);
            String siteCode = Objects.requireNonNull(RecordCodes.codeUnjudged(SiteModel.MODEL_ID,
                String.valueOf(siteId)));
            for (String code : List.of(siteCode, pathCode, ruleCode)) {
                assertThat(admin.call("history", Map.of("code", code)).text()).as("step 3: history of " + code)
                    .doesNotContain(second).doesNotContain(first);
            }
            McpHttpSession.Answer mixed = admin.call("protected_path_set", Map.of("code", pathCode,
                "signed_link_secret", "replace", "path", "/other"));
            assertThat(mixed.error()).as("step 3: a replace takes no path: " + mixed.text()).isTrue();
            assertThat(mixed.text()).as("step 3: refused by its name").contains("path");

            // 4. The old secret ended now: only the new one opens; ending again is refused.
            McpHttpSession.Answer ended = admin.call("protected_path_set", Map.of("code", pathCode,
                "signed_link_secret", "end_old"));
            assertThat(ended.error()).as("step 4: " + ended.text()).isFalse();
            proxy.getDispatcher().reloadRoutes();
            assertNotFound(ProxyTestSupport.rawRequest(port, host, valid), "step 4: the old secret ended");
            assertThat(ProxyTestSupport.rawRequest(port, host, withNew)).as("step 4: the new one opens")
                .startsWith("HTTP/1.1 200");
            assertThat(admin.call("protected_path_set", Map.of("code", pathCode, "signed_link_secret", "end_old"))
                .error()).as("step 4: no old secret left to end").isTrue();

            // 5. site_link_make answers one working link; a path outside, a length out of range and an unknown
            //    argument are refused by name.
            McpHttpSession.Answer made = admin.call("site_link_make", Map.of("protected_path", pathCode,
                "path", file, "minutes", 10));
            assertThat(made.error()).as("step 5: " + made.text()).isFalse();
            Matcher link = LINK.matcher(made.text());
            assertThat(link.find()).as("step 5: the answer carries the link: " + made.text()).isTrue();
            assertThat(link.group(4)).as("step 5: signed as documented")
                .isEqualTo(sign(second, link.group(2), Long.parseLong(link.group(3))));
            assertThat(ProxyTestSupport.rawRequest(port, host, link.group(2) + "?expires=" + link.group(3)
                + "&signature=" + link.group(4))).as("step 5: it opens the file").startsWith("HTTP/1.1 200");
            McpHttpSession.Answer outside = admin.call("site_link_make", Map.of("protected_path", pathCode,
                "path", "/public.txt"));
            assertThat(outside.error()).as("step 5: outside /beta/").isTrue();
            assertThat(outside.text()).as("step 5: named").contains("path");
            McpHttpSession.Answer tooLong = admin.call("site_link_make", Map.of("protected_path", pathCode,
                "path", file, "minutes", 20_000));
            assertThat(tooLong.error()).as("step 5: past seven days").isTrue();
            assertThat(tooLong.text()).as("step 5: named").contains("minutes");
            McpHttpSession.Answer unknown = admin.call("site_link_make", Map.of("protected_path", pathCode,
                "path", file, "secret", "x"));
            assertThat(unknown.error()).as("step 5: an unknown argument").isTrue();
            assertThat(unknown.text()).as("step 5: named").contains("secret");

            // 6. A proxied site protected by tool with a signed link: the secret waits for the caller, the folder
            //    refuses unsigned requests before the app, and a signed one reaches it without the two parameters.
            String proxiedCode = Objects.requireNonNull(RecordCodes.codeUnjudged(SiteModel.MODEL_ID,
                String.valueOf(proxiedId)));
            McpHttpSession.Answer guarded = admin.call("protected_path_set", Map.of("site", proxiedCode,
                "path", "/beta", "signed_link", true));
            assertThat(guarded.error()).as("step 6: " + guarded.text()).isFalse();
            Row appPath = Objects.requireNonNull(Models.get(ProtectedPathModel.class).find()
                .where(ProtectedPathModel.SITE_ID.eq(proxiedId)).first(), "step 6: the protected path");
            cleanUp.push(() -> forget(appPath));
            String appSecret = secretOf(ruleOf(appPath));
            assertThat(guarded.text()).as("step 6: never the secret").doesNotContain(appSecret);
            String appPathCode = Objects.requireNonNull(RecordCodes.codeUnjudged(ProtectedPathModel.MODEL_ID,
                String.valueOf((Object) appPath.get(ProtectedPathModel.ID))));
            assertThat(admin.call("read", Map.of("codes", List.of(appPathCode))).text())
                .as("step 6: the read says where the secret waits").contains("Signed link tab")
                .doesNotContain(appSecret);
            proxy.getDispatcher().reloadRoutes();
            int hits = upstreamHits.get();
            assertNotFound(ProxyTestSupport.rawRequest(port, appHost, "/beta/report?keep=1"),
                "step 6: unsigned, refused");
            assertThat(upstreamHits.get()).as("step 6: before the app").isEqualTo(hits);
            String appLink = signed(appSecret, "/beta/report", soon) + "&keep=1";
            assertThat(ProxyTestSupport.rawRequest(port, appHost, appLink)).as("step 6: signed, the app answers")
                .startsWith("HTTP/1.1 200").contains("app /beta/report");
            assertThat(upstreamQuery.get()).as("step 6: without the two parameters").isEqualTo("keep=1");
            assertThat(ProxyTestSupport.rawRequest(port, appHost, "/open?expires=1&signature=x"))
                .as("step 6: outside the folder the app gets every parameter").startsWith("HTTP/1.1 200");
            assertThat(upstreamQuery.get()).as("step 6: untouched there").isEqualTo("expires=1&signature=x");
        } finally {
            if (proxy != null) proxy.stop();
            if (upstream != null) upstream.stop(0);
            cleanUp.forEach(Runnable::run);
        }
    }

    @Test
    void aSignedLinkKeepsToItsWireAndHasNoSideDoor() throws Exception {
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        ProxyServer proxy = null;
        try {
            // 0. An own-files site with its own not-found page, an installer whose name has a space under /beta/.
            Row site = ProxyTestSupport.setupSite("hohenheim:static", "Signed wire " + tag, "signed-wire-" + tag,
                new LinkedHashMap<String, Object>(Map.of("own_files", true, "not_found_page", "missing.html")));
            int siteId = site.get(SiteModel.ID);
            cleanUp.push(() -> HardDeletes.byId(Models.get(SiteModel.class), siteId));
            String host = "wire-" + tag + ".signed.test";
            ProxyTestSupport.addDomain(site, host, "exact", null, false);
            Path files = storage.resolve(SiteFiles.prefixOf(siteId));
            Files.createDirectories(files.resolve("beta"));
            Files.writeString(files.resolve("beta/Tadoma Setup.exe"), "installer " + tag);
            Files.writeString(files.resolve("beta/app.bin"), "app " + tag);
            Files.writeString(files.resolve("missing.html"), "missing page " + tag);
            proxy = ProxyTestSupport.startProxy();
            int port = ProxyTestSupport.httpPort(proxy);
            URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
            McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
            String siteCode = Objects.requireNonNull(RecordCodes.codeUnjudged(SiteModel.MODEL_ID,
                String.valueOf(siteId)));
            assertThat(admin.call("protected_path_set", Map.of("site", siteCode, "path", "/beta", "signed_link", true))
                .error()).as("step 0: protected").isFalse();
            Row path = Objects.requireNonNull(Models.get(ProtectedPathModel.class).find()
                .where(ProtectedPathModel.SITE_ID.eq(siteId)).first(), "step 0: the protected path");
            int pathId = path.get(ProtectedPathModel.ID);
            cleanUp.push(() -> forget(path));
            Row rule = ruleOf(path);
            int ruleId = rule.get(AccessRuleModel.ID);
            int listId = path.get(ProtectedPathModel.ACCESS_LIST_ID);
            String secret = secretOf(rule);
            proxy.getDispatcher().reloadRoutes();

            // 1. Refused is the site's own answer for a path that does not exist, byte for byte.
            String missing = ProxyTestSupport.rawRequest(port, host, "/nothing-" + tag + ".bin");
            assertThat(missing).as("step 1: the site's own not-found page").startsWith("HTTP/1.1 404")
                .contains("missing page " + tag);
            String refused = ProxyTestSupport.rawRequest(port, host, "/beta/app.bin");
            assertNotFound(refused, "step 1: unsigned");
            assertThat(bodyOf(refused)).as("step 1: indistinguishable from a missing file").isEqualTo(bodyOf(missing));

            // 2. The wire: HEAD opens; a doubled parameter, uppercase hex and a decoded spelling are refused; a path
            //    with a space is signed as it appears in the address; a trailing parameter keeps working.
            long soon = Now.instant().getEpochSecond() + 600;
            String app = signed(secret, "/beta/app.bin", soon);
            String head = ProxyTestSupport.rawRequestAs(port, "HEAD", host, app);
            assertThat(head).as("step 2: HEAD with a valid link").startsWith("HTTP/1.1 200")
                .containsIgnoringCase("content-length: " + ("app " + tag).length()).doesNotContain("app " + tag);
            assertNotFound(ProxyTestSupport.rawRequest(port, host, app + "&expires=" + soon), "step 2: two expires");
            assertNotFound(ProxyTestSupport.rawRequest(port, host, app + "&signature=" + sign(secret, "/beta/app.bin",
                soon)), "step 2: two signatures");
            String hex = sign(secret, "/beta/app.bin", soon);
            assertNotFound(ProxyTestSupport.rawRequest(port, host, "/beta/app.bin?expires=" + soon + "&signature="
                + hex.toUpperCase(Locale.ROOT)), "step 2: uppercase hex");
            String spaced = "/beta/Tadoma%20Setup.exe";
            assertThat(ProxyTestSupport.rawRequest(port, host, signed(secret, spaced, soon)))
                .as("step 2: signed over the raw path").startsWith("HTTP/1.1 200").contains("installer " + tag);
            assertNotFound(ProxyTestSupport.rawRequest(port, host, spaced + "?expires=" + soon + "&signature="
                + sign(secret, "/beta/Tadoma Setup.exe", soon)), "step 2: signed over the decoded path");
            assertThat(ProxyTestSupport.rawRequest(port, host, app + "&x=1")).as("step 2: a trailing parameter")
                .startsWith("HTTP/1.1 200").contains("app " + tag);
            assertThat(rendered(adminGet(CmsRoutes.subpage(HohenheimSlugs.ADMIN, HohenheimSlugs.PROTECTED_PATHS, pathId,
                HohenheimSlugs.Tab.SIGNED_LINK).toUrl()).body())).as("step 2: the tab says how the path is signed")
                .contains("exactly as it appears in the address");

            // 3. No side door: the generic rule tool, the panel's rule form and add, and the automation API neither
            //    make, retype nor rewrite a signed link, and none of them shows its secret.
            String listCode = Objects.requireNonNull(RecordCodes.codeUnjudged(AccessListModel.MODEL_ID,
                String.valueOf(listId)));
            String ruleCode = Objects.requireNonNull(RecordCodes.codeUnjudged(AccessRuleModel.MODEL_ID,
                String.valueOf(ruleId)));
            McpHttpSession.Answer made = admin.call("access_rule_set", Map.of("access_list", listCode,
                "kind", AccessRuleModel.TYPE_SIGNED_LINK));
            assertThat(made.error()).as("step 3: access_rule_set makes no signed link: " + made.text()).isTrue();
            assertThat(made.text()).as("step 3: refused by its name").contains("kind").contains("Signed link tab");
            McpHttpSession.Answer typed = admin.call("access_rule_set", Map.of("code", ruleCode, "secret", "chosen"));
            assertThat(typed.error()).as("step 3: a secret is no argument").isTrue();
            assertThat(typed.text()).as("step 3: named").contains("secret");
            McpHttpSession.Answer retyped = admin.call("access_rule_set", Map.of("code", ruleCode, "kind", "ip_allow",
                "network", "10.0.0.0/8"));
            assertThat(retyped.error()).as("step 3: no retype by tool").isTrue();
            assertThat(retyped.text()).as("step 3: named").contains("kind");
            HttpResponse<String> edited = adminPostEdit("/admin/access-rules/" + ruleId,
                "type=signed_link&data.secret=chosen-by-hand&enabled=true");
            HttpResponse<String> retypedByForm = adminPostEdit("/admin/access-rules/" + ruleId,
                "type=ip_allow&data.network=10.0.0.0%2F8&enabled=true");
            assertThat(retypedByForm.statusCode()).as("step 3: no retype by the rule form").isEqualTo(422);
            int rules = Models.get(AccessRuleModel.class).findForAccessList(listId).size();
            String add = CmsRoutes.invoke(HohenheimSlugs.ADMIN, HohenheimSlugs.ACCESS_LISTS, AccessRuleParts.ADD.id())
                .with(CmsEndpoints.SUBJECT_PARAM, String.valueOf(listId))
                .with(CmsEndpoints.TAB_PARAM, HohenheimSlugs.Tab.RULES).toUrl();
            adminPostForm(add, confirmed("type=signed_link&data.secret=chosen-by-add&enabled=true"));
            UUID operatorId = TenantConduits.operatorUser().get(UserModel.ID);
            ApiKeyService.CreatedKey created = ApiKeyService.create(operatorId, "signed-wire-" + tag,
                List.of("hohenheim.*"), null);
            cleanUp.push(() -> ApiKeyService.revoke(operatorId, created.row().get(ApiKeyModel.ID)));
            String key = created.plaintext();
            HttpResponse<String> api = keyPost(key, "/api/v1/access-lists/" + listId + "/rules",
                "type=signed_link&data.secret=chosen-by-api&enabled=true");
            assertThat(api.statusCode()).as("step 3: the automation API makes none: " + api.body()).isEqualTo(422);
            assertThat(Models.get(AccessRuleModel.class).findForAccessList(listId)).as("step 3: no rule was added")
                .hasSize(rules);
            Row kept = Objects.requireNonNull(Models.get(AccessRuleModel.class).findById(ruleId));
            assertThat((Object) kept.get(AccessRuleModel.TYPE)).as("step 3: still a signed link")
                .isEqualTo(AccessRuleModel.TYPE_SIGNED_LINK);
            assertThat(secretOf(kept)).as("step 3: its secret as Hohenheim made it, after the form's "
                + edited.statusCode()).isEqualTo(secret);
            assertThat(keyGet(key, "/api/v1/access-lists/" + listId).body()).as("step 3: the API's read")
                .contains(AccessRuleModel.TYPE_SIGNED_LINK).doesNotContain(secret);
            assertThat(adminGet("/admin/access-rules/" + ruleId).body()).as("step 3: the rule's page")
                .doesNotContain(secret);
            assertThat(admin.call("list", Map.of("kind", "access_rule")).text()).as("step 3: the rule list")
                .doesNotContain(secret);

            // 4. An external script asks for a link over the HTTP API with its key; a path outside is refused by name.
            HttpResponse<String> link = keyPost(key, "/api/v1/protected-paths/" + pathId + "/links",
                "path=%2Fbeta%2Fapp.bin&minutes=5");
            assertThat(link.statusCode()).as("step 4: " + link.body()).isEqualTo(200);
            Matcher answered = LINK.matcher(link.body().replace("\\u0026", "&").replace("\\/", "/"));
            assertThat(answered.find()).as("step 4: the answer carries the link").isTrue();
            assertThat(answered.group(4)).as("step 4: signed as documented")
                .isEqualTo(sign(secret, answered.group(2), Long.parseLong(answered.group(3))));
            assertThat(ProxyTestSupport.rawRequest(port, host, answered.group(2) + "?expires=" + answered.group(3)
                + "&signature=" + answered.group(4))).as("step 4: it opens").startsWith("HTTP/1.1 200");
            HttpResponse<String> outside = keyPost(key, "/api/v1/protected-paths/" + pathId + "/links",
                "path=%2Fpublic.txt");
            assertThat(outside.statusCode()).as("step 4: outside the path").isEqualTo(422);
            assertThat(outside.body()).as("step 4: named").contains("path");

            // 5. A list mixing ways in: a request passes when the list's own any or all is met, a valid signed link
            //    being one way among them.
            McpHttpSession.Answer network = admin.call("access_rule_set", Map.of("access_list", listCode,
                "kind", "ip_allow", "network", "127.0.0.1/32"));
            assertThat(network.error()).as("step 5: " + network.text()).isFalse();
            String networkCode = HohenheimAppPartToolsTest.codeIn(network.text(), "RU");
            proxy.getDispatcher().reloadRoutes();
            assertThat(ProxyTestSupport.rawRequest(port, host, "/beta/app.bin")).as("step 5: any, the network alone")
                .startsWith("HTTP/1.1 200");
            admin.call("access_rule_set", Map.of("code", networkCode, "network", "10.9.0.0/16"));
            proxy.getDispatcher().reloadRoutes();
            assertNotFound(ProxyTestSupport.rawRequest(port, host, "/beta/app.bin"), "step 5: any, neither way");
            assertThat(ProxyTestSupport.rawRequest(port, host, app)).as("step 5: any, the link alone")
                .startsWith("HTTP/1.1 200");
            assertThat(admin.call("access_list_set", Map.of("code", listCode, "satisfy", "all")).error())
                .as("step 5: all").isFalse();
            proxy.getDispatcher().reloadRoutes();
            assertNotFound(ProxyTestSupport.rawRequest(port, host, app), "step 5: all, the link without the network");
            admin.call("access_rule_set", Map.of("code", networkCode, "network", "127.0.0.1/32"));
            proxy.getDispatcher().reloadRoutes();
            assertNotFound(ProxyTestSupport.rawRequest(port, host, "/beta/app.bin"),
                "step 5: all, the network without the link");
            assertThat(ProxyTestSupport.rawRequest(port, host, app)).as("step 5: all, both")
                .startsWith("HTTP/1.1 200");
            // A password is another way in: under any, a request without a link is asked for it.
            admin.call("access_list_set", Map.of("code", listCode, "satisfy", "any"));
            admin.call("access_rule_set", Map.of("code", networkCode, "enabled", false));
            assertThat(admin.call("access_rule_set", Map.of("access_list", listCode, "kind", "basic_auth",
                "username", "tester", "password", "pw-" + tag)).error()).as("step 5: a password").isFalse();
            proxy.getDispatcher().reloadRoutes();
            assertThat(ProxyTestSupport.rawRequest(port, host, "/beta/app.bin")).as("step 5: asked for the password")
                .startsWith("HTTP/1.1 401").containsIgnoringCase("WWW-Authenticate");
            assertThat(ProxyTestSupport.rawRequest(port, host, app)).as("step 5: or opened by the link")
                .startsWith("HTTP/1.1 200");
        } finally {
            if (proxy != null) proxy.stop();
            cleanUp.forEach(Runnable::run);
        }
    }

    /** @return a raw response's body, after its header block */
    private static String bodyOf(String response) {
        int start = response.indexOf("\r\n\r\n");
        return start < 0 ? "" : response.substring(start + 4);
    }

    /** @return the enabled signed-link rule of the path's list */
    private static Row ruleOf(Row path) {
        return Objects.requireNonNull(Models.get(AccessRuleModel.class).find()
            .where(AccessRuleModel.ACCESS_LIST_ID.eq(path.get(ProtectedPathModel.ACCESS_LIST_ID)))
            .and(AccessRuleModel.TYPE.eq(AccessRuleModel.TYPE_SIGNED_LINK)).first(), "a signed-link rule");
    }

    /** Removes a protected path and the list its protection made. */
    static void forget(Row path) {
        HardDeletes.byId(Models.get(ProtectedPathModel.class), path.get(ProtectedPathModel.ID));
        HardDeletes.byId(Models.get(AccessListModel.class), path.get(ProtectedPathModel.ACCESS_LIST_ID));
    }

    /** @return the rule's current secret as the ORM opens it */
    private static String secretOf(Row rule) {
        Row fresh = Objects.requireNonNull(Models.get(AccessRuleModel.class).findById(rule.get(AccessRuleModel.ID)));
        return String.valueOf(AccessRuleModel.dataOf(fresh).get(AccessRuleModel.SIGNED_LINK_SECRET.getName()));
    }

    /** @return the documented signature, computed apart from the product */
    private static String sign(String secret, String rawPath, long expires) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((rawPath + "\n" + expires).getBytes(StandardCharsets.UTF_8)));
    }

    /** @return the request target of a link to the path signed with the secret */
    private static String signed(String secret, String rawPath, long expires) throws Exception {
        return rawPath + "?expires=" + expires + "&signature=" + sign(secret, rawPath, expires);
    }

    /** @return the target with the signature's last digit changed */
    private static String tampered(String target) {
        char last = target.charAt(target.length() - 1);
        return target.substring(0, target.length() - 1) + (last == '0' ? '1' : '0');
    }

    private static void assertNotFound(String answer, String step) {
        assertThat(answer).as(step + ": the ordinary not-found page").startsWith("HTTP/1.1 404")
            .doesNotContain("WWW-Authenticate").doesNotContain("Forbidden");
    }

    /** @return bytes of a pattern, so a range reads what it should */
    private static byte[] bytes(int size) {
        byte[] content = new byte[size];
        for (int i = 0; i < size; i++) {
            content[i] = (byte) (i * 13 + 7);
        }
        return content;
    }
}
