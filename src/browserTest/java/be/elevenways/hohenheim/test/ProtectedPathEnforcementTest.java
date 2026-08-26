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

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Drives protected paths through the proxy with real requests: a guarded folder
 * challenges with 401 while the rest of the site serves, the prefix match keeps segment
 * boundaries, guards are ADDITIVE on top of the site's own list, nested guards all apply,
 * and the model invariant refuses the rows that would guard nothing.
 */
class ProtectedPathEnforcementTest {

    private static ProxyServer proxy;
    private static HttpServer upstream;
    private static int port;
    private static Row site;
    private static final AtomicInteger upstreamHits = new AtomicInteger();

    @BeforeAll
    static void boot() throws Exception {
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");

        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            upstreamHits.incrementAndGet();
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
