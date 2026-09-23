package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.BasicCredentials;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Drives protected paths through the proxy with real requests: a guarded folder
 * challenges with 401 while the rest of the site serves, the prefix match keeps segment
 * boundaries, guards are ADDITIVE on top of the site's own list, nested guards all apply,
 * the model invariant refuses the rows that would guard nothing, and no spelling of a guarded
 * path (dot-segments, doubled or encoded separators) reaches the upstream past its guard.
 */
class ProtectedPathEnforcementTest {

    private static ProxyServer proxy;
    private static HttpServer upstream;
    private static int port;
    private static Row site;
    private static final AtomicInteger upstreamHits = new AtomicInteger();
    /** The request target the upstream last received, exactly as it arrived on its wire. */
    private static final AtomicReference<String> upstreamTarget = new AtomicReference<>();

    @BeforeAll
    static void boot() throws Exception {
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");

        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            upstreamHits.incrementAndGet();
            upstreamTarget.set(ex.getRequestURI().getRawPath());
            byte[] body = "site-content".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
    }

    @AfterAll
    static void stop() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        if (upstream != null) {
            upstream.stop(0);
            upstream = null;
        }
    }

    @Test
    @Timeout(60)
    void protectedPathsGuardFoldersOnTheWire() throws Exception {
        site = ProxyTestSupport.setupSite("hohenheim:address", "Folder Site", "folder-site",
            Map.of("forward_host", "127.0.0.1",
                   "forward_port", upstream.getAddress().getPort()));
        ProxyTestSupport.addDomain(site, "folders.pp.test", "exact", null, false);

        proxy = ProxyTestSupport.startProxy();
        port = ProxyTestSupport.httpPort(proxy);

        // Step 1: baseline -- everything serves.
        assertThat(request("/")).contains("200").contains("site-content");
        assertThat(request("/private/report.html")).contains("200");

        // Step 2: guard /private with a password list. The folder challenges with 401,
        // the rest of the site still serves, and the refusal never reaches the upstream.
        int familyList = list("Family");
        rule(familyList, credentials("kim", "hunter2"));
        protect("/private", familyList);
        reload();
        assertThat(request("/")).as("step 2: outside the folder nothing changed")
            .contains("200").contains("site-content");
        int hits = upstreamHits.get();
        assertThat(request("/private/report.html"))
            .as("step 2: the folder challenges")
            .contains("401").contains("WWW-Authenticate").doesNotContain("site-content");
        assertThat(request("/private"))
            .as("step 2: the folder itself challenges too")
            .contains("401");
        assertThat(upstreamHits.get())
            .as("step 2: refused requests never reach the upstream")
            .isEqualTo(hits);

        // Step 3: segment boundary -- /privateer is NOT under /private.
        assertThat(request("/privateer"))
            .as("step 3: the prefix match keeps segment boundaries")
            .contains("200").contains("site-content");

        // Step 4: correct credentials open the folder; wrong ones do not.
        assertThat(request("/private/report.html", authHeader("kim", "hunter2")))
            .as("step 4: the password opens the folder")
            .contains("200").contains("site-content");
        assertThat(request("/private/report.html", authHeader("kim", "wrong")))
            .as("step 4: a wrong password keeps challenging")
            .contains("401");

        // Step 5: guards are ADDITIVE on top of the site's own access list -- a site-wide
        // deny still refuses inside the folder even with the folder's password.
        int denyList = list("Deny");
        Row denyRule = Models.get(AccessRuleModel.class).createEmptyRow();
        denyRule.set(AccessRuleModel.ACCESS_LIST_ID, denyList);
        denyRule.set(AccessRuleModel.TYPE, AccessRuleModel.TYPE_IP_DENY);
        denyRule.set(AccessRuleModel.DATA, new LinkedHashMap<>(Map.of("network", "127.0.0.0/8")));
        denyRule.set(AccessRuleModel.ENABLED, true);
        denyRule.set(AccessRuleModel.SORT, 0);
        Models.get(AccessRuleModel.class).save(denyRule);
        Row storedSite = Models.get(SiteModel.class)
            .findById(site.get(SiteModel.ID));
        storedSite.set(SiteModel.ACCESS_LIST_ID, denyList);
        Models.get(SiteModel.class).save(storedSite);
        reload();
        assertThat(request("/private/report.html", authHeader("kim", "hunter2")))
            .as("step 5: the site-wide list still refuses inside the folder")
            .contains("403");
        storedSite.set(SiteModel.ACCESS_LIST_ID, null);
        Models.get(SiteModel.class).save(storedSite);
        reload();

        // Step 6: nested guards ALL apply -- /private/deep needs both passwords.
        int editorsList = list("Editors");
        rule(editorsList, credentials("sam", "letmein"));
        protect("/private/deep", editorsList);
        reload();
        assertThat(request("/private/deep/file", authHeader("kim", "hunter2")))
            .as("step 6: the outer password alone is not enough for the nested folder")
            .contains("401");
        assertThat(request("/private/other", authHeader("kim", "hunter2")))
            .as("step 6: a sibling under the outer guard needs only the outer password")
            .contains("200");

        // Step 7: the invariant refuses rows that would guard nothing, stores the
        // canonical spelling, and keeps one row per (site, path).
        assertThatThrownBy(() -> protect("/", familyList))
            .as("step 7: '/' is the site list's job, not a guardable prefix")
            .isInstanceOf(Violations.class);
        assertThatThrownBy(() -> protectWithoutList("/drafts"))
            .as("step 7: a guard without a list is refused")
            .isInstanceOf(Violations.class);
        int draftsId = protect("drafts/", familyList);
        Row drafts = Models.get(ProtectedPathModel.class).findById(draftsId);
        assertThat(drafts.get(ProtectedPathModel.PATH))
            .as("step 7: the stored path is the dispatcher's canonical spelling")
            .isEqualTo("/drafts");
        Object duplicateRefusal = catchThrowableOfType(
            () -> protect("/drafts", familyList), Violations.class);
        assertThat(duplicateRefusal)
            .as("step 7: the (site, path) pair is claimed once")
            .isNotNull();

        // Step 8: the canonical row from step 7 enforces on the wire like any other.
        reload();
        assertThat(request("/drafts/x")).as("step 8: the canonicalized guard enforces")
            .contains("401");
        assertThat(request("/drafts/x", authHeader("kim", "hunter2"))).contains("200");

        // Step 9: no spelling of a guarded path dodges its guard. A dot-segment in any form is
        // refused outright (no browser sends one; an upstream would resolve it back into the
        // guarded folder), and separator spellings an upstream reads as '/' are judged as '/'.
        int beforeBypass = upstreamHits.get();
        for (String dotted : new String[]{"/x/../private/report.html", "/./private/report.html",
                "/x/%2e%2e/private/report.html", "/x/%2E%2E/private/report.html",
                "/x/..;/private/report.html", "/private/./report.html"}) {
            assertThat(request(dotted))
                .as("step 9: dot-segment spelling %s is refused, never served", dotted)
                .contains("400").doesNotContain("site-content");
        }
        for (String collapsed : new String[]{"//private/report.html", "/private//report.html",
                "/private%2Freport.html", "/private%2freport.html", "/private%5Creport.html"}) {
            assertThat(request(collapsed))
                .as("step 9: separator spelling %s is still under /private and challenges", collapsed)
                .contains("401").doesNotContain("site-content");
        }
        assertThat(upstreamHits.get())
            .as("step 9: no bypass attempt reached the upstream")
            .isEqualTo(beforeBypass);
        assertThat(request("//private/report.html", authHeader("kim", "hunter2")))
            .as("step 9: the same spelling with the folder's password serves, so nothing legitimate broke")
            .contains("200").contains("site-content");
        assertThat(upstreamTarget.get())
            .as("step 9: the upstream receives ONE leading '/', never a network-path reference")
            .isEqualTo("/private/report.html");

        // Step 10: a leading '//' outside the guard is not a way in either. The guard judges
        // "/public/private/report.html" (not under /private), so the upstream must receive that
        // same path: forwarded verbatim, a URL parser would read host "public" and path
        // "/private/report.html" and serve the guarded folder without its password.
        assertThat(request("//public/private/report.html"))
            .as("step 10: a path outside the guard serves without a password")
            .contains("200").contains("site-content");
        assertThat(upstreamTarget.get())
            .as("step 10: the upstream receives the path the guard judged, not host 'public'")
            .isEqualTo("/public/private/report.html");
        assertThat(request("/%2F/private/report.html"))
            .as("step 10: an encoded leading separator is still judged under /private")
            .contains("401").doesNotContain("site-content");
    }

    private static void reload() {
        proxy.getDispatcher().reloadRoutes();
    }

    /** Persist one named access list and return its id. */
    private static int list(String name) {
        var model = Models.get(AccessListModel.class);
        Row row = model.createEmptyRow();
        row.set(AccessListModel.NAME, name);
        row.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ANY);
        model.save(row);
        return row.get(AccessListModel.ID);
    }

    /** Persist one basic-auth leaf on the list's root. */
    private static void rule(int listId, Map<String, Object> data) {
        var model = Models.get(AccessRuleModel.class);
        Row row = model.createEmptyRow();
        row.set(AccessRuleModel.ACCESS_LIST_ID, listId);
        row.set(AccessRuleModel.TYPE, AccessRuleModel.TYPE_BASIC_AUTH);
        row.set(AccessRuleModel.DATA, new LinkedHashMap<>(data));
        row.set(AccessRuleModel.ENABLED, true);
        row.set(AccessRuleModel.SORT, 0);
        model.save(row);
    }

    /** Persist one protected path on the test site and return its id. */
    private static int protect(String path, int listId) {
        var model = Models.get(ProtectedPathModel.class);
        Row row = model.createEmptyRow();
        row.set(ProtectedPathModel.SITE_ID, site.get(SiteModel.ID));
        row.set(ProtectedPathModel.PATH, path);
        row.set(ProtectedPathModel.ACCESS_LIST_ID, listId);
        model.save(row);
        return row.get(ProtectedPathModel.ID);
    }

    private static void protectWithoutList(String path) {
        var model = Models.get(ProtectedPathModel.class);
        Row row = model.createEmptyRow();
        row.set(ProtectedPathModel.SITE_ID, site.get(SiteModel.ID));
        row.set(ProtectedPathModel.PATH, path);
        model.save(row);
    }

    /** The stored shape of a credential leaf: the password is an argon2 hash. */
    private static Map<String, Object> credentials(String username, String password) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", username);
        data.put("password", BasicCredentials.hashIfNeeded(password));
        return data;
    }

    private static String authHeader(String user, String pass) {
        return "Authorization: Basic " + Base64.getEncoder()
            .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    private static String request(String path, String... headers) throws Exception {
        return ProxyTestSupport.rawRequest(port, "folders.pp.test", path, headers);
    }
}
