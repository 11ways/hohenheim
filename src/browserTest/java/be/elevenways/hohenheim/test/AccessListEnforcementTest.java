package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.zenit.auth.server.PasswordHasher;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the access-list gate through the proxy with real requests: denied IPs are refused
 * on the wire, satisfy=all requires both conditions, basic auth answers 401, and a NULL
 * satisfy column no longer disables the whole control (the row is what enforces).
 */
class AccessListEnforcementTest {

    private static ProxyServer proxy;
    private static HttpServer upstream;
    private static int port;
    private static Row site;
    private static Row accessList;
    private static final AtomicInteger upstreamHits = new AtomicInteger();

    @BeforeAll
    static void boot() throws Exception {
        SiteTypes.boot();
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");

        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            upstreamHits.incrementAndGet();
            byte[] body = "guarded-content".getBytes(StandardCharsets.UTF_8);
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
    @Timeout(30)
    void accessListGateEnforcesOnTheWire() throws Exception {
        site = ProxyTestSupport.setupSite("hohenheim:proxy", "Guarded Site", "guarded-site",
            Map.of("forward_host", "127.0.0.1",
                   "forward_port", upstream.getAddress().getPort()));
        ProxyTestSupport.addDomain(site, "guarded.acl.test", "exact", null, false);

        proxy = ProxyTestSupport.startProxy();
        port = ProxyTestSupport.httpPort(proxy);

        // Step 1: no access list at all -- the site serves (baseline anchor).
        assertThat(request("/"))
            .as("step 1: without an access list the site serves")
            .contains("200").contains("guarded-content");

        // Step 2: an allow list naming this client still lets it through.
        var listModel = Models.get(AccessListModel.class);
        accessList = listModel.createEmptyRow();
        accessList.set(AccessListModel.NAME, "Test List");
        accessList.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ANY);
        accessList.set(AccessListModel.ALLOWED_IPS, "127.0.0.1");
        listModel.save(accessList);
        var siteModel = Models.get(SiteModel.class);
        Row storedSite = siteModel.findById(site.get(SiteModel.ID));
        storedSite.set(SiteModel.ACCESS_LIST_ID, accessList.get(AccessListModel.ID));
        siteModel.save(storedSite);
        reload();
        assertThat(request("/"))
            .as("step 2: an allowed client passes the gate (positive anchor)")
            .contains("200").contains("guarded-content");

        // Step 3: a denied IP is refused with 403 and never reaches the upstream.
        setList(AccessListModel.ALLOWED_IPS, null);
        setList(AccessListModel.DENIED_IPS, "127.0.0.1");
        reload();
        int hits = upstreamHits.get();
        assertThat(request("/"))
            .as("step 3: a denied IP is refused")
            .contains("403").doesNotContain("guarded-content");
        assertThat(upstreamHits.get())
            .as("step 3: the refused request must not reach the upstream")
            .isEqualTo(hits);

        // Step 4: THE ATTACK (pre-fix counterfactual): an explicit NULL satisfy used to make
        // hasAccessList() false and switch the entire control off. The row still enforces.
        listModel.find().where(AccessListModel.ID.eq(accessList.get(AccessListModel.ID)))
            .assign(AccessListModel.SATISFY, null)
            .updateAll();
        reload();
        hits = upstreamHits.get();
        assertThat(request("/"))
            .as("step 4: a NULL satisfy column must NOT disable the access list")
            .contains("403").doesNotContain("guarded-content");
        assertThat(upstreamHits.get())
            .as("step 4: the upstream stays unreached with a NULL satisfy")
            .isEqualTo(hits);

        // Step 5: satisfy=all with allow-listed IP plus basic auth: auth is still required.
        setList(AccessListModel.SATISFY, AccessListModel.SATISFY_ALL);
        setList(AccessListModel.DENIED_IPS, null);
        setList(AccessListModel.ALLOWED_IPS, "127.0.0.1");
        setList(AccessListModel.BASIC_AUTH_USER, "operator");
        setList(AccessListModel.BASIC_AUTH_PASS, PasswordHasher.hash("s3cret"));
        reload();
        assertThat(request("/"))
            .as("step 5: satisfy=all without credentials answers the 401 challenge")
            .contains("401").contains("WWW-Authenticate");
        assertThat(request("/", authHeader("operator", "wrong")))
            .as("step 5: a wrong password stays 401")
            .contains("401");
        assertThat(request("/", authHeader("operator", "s3cret")))
            .as("step 5: correct credentials plus an allowed IP pass satisfy=all")
            .contains("200").contains("guarded-content");

        // Step 6: satisfy=all with a denied IP refuses even with correct credentials.
        setList(AccessListModel.ALLOWED_IPS, null);
        setList(AccessListModel.DENIED_IPS, "127.0.0.1");
        reload();
        assertThat(request("/", authHeader("operator", "s3cret")))
            .as("step 6: satisfy=all needs BOTH conditions; a denied IP refuses despite auth")
            .contains("403");

        // Step 7: the model refuses a garbage satisfy value and folds blank to the default.
        Row garbage = listModel.findById(accessList.get(AccessListModel.ID));
        garbage.set(AccessListModel.SATISFY, "sometimes");
        assertThatThrownBy(() -> listModel.save(garbage))
            .as("step 7: a satisfy outside the vocabulary is a violation")
            .isInstanceOf(Violations.class);
        Row blank = listModel.findById(accessList.get(AccessListModel.ID));
        blank.set(AccessListModel.SATISFY, " ");
        listModel.save(blank);
        String storedSatisfy = listModel.findById(accessList.get(AccessListModel.ID))
            .get(AccessListModel.SATISFY);
        assertThat(storedSatisfy)
            .as("step 7: a blank satisfy folds to the default")
            .isEqualTo(AccessListModel.SATISFY_ANY);
    }

    private static void reload() {
        proxy.getDispatcher().reloadRoutes();
    }

    private static void setList(be.elevenways.zenit.common.orm.field.Field<?, ?> field, String value) {
        var listModel = Models.get(AccessListModel.class);
        Row row = listModel.findById(accessList.get(AccessListModel.ID));
        row.set(field.getName(), value);
        listModel.save(row);
    }

    private static String authHeader(String user, String pass) {
        return "Authorization: Basic " + Base64.getEncoder()
            .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    private static String request(String path, String... headers) throws Exception {
        return ProxyTestSupport.rawRequest(port, "guarded.acl.test", path, headers);
    }
}
