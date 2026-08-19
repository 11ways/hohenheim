package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.BasicCredentials;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
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

/**
 * Drives the access-rule TREE through the proxy with real requests: denied networks are
 * refused on the wire, nested groups compose, a credential leaf answers 401 only when it
 * could still change the verdict, disabled rules are ignored and an unknown rule type
 * denies.
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
    @Timeout(60)
    void accessRuleTreeEnforcesOnTheWire() throws Exception {
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

        // Step 2: an EMPTY list is inert. The root group has no children, and an empty
        // group passes -- attaching a list must never be an accidental lockout.
        var listModel = Models.get(AccessListModel.class);
        accessList = listModel.createEmptyRow();
        accessList.set(AccessListModel.NAME, "Test List");
        accessList.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ANY);
        listModel.save(accessList);
        var siteModel = Models.get(SiteModel.class);
        Row storedSite = siteModel.findById(site.get(SiteModel.ID));
        storedSite.set(SiteModel.ACCESS_LIST_ID, accessList.get(AccessListModel.ID));
        siteModel.save(storedSite);
        reload();
        assertThat(request("/"))
            .as("step 2: an empty rule tree lets the request through")
            .contains("200").contains("guarded-content");

        // Step 3: an allow rule naming this client lets it through.
        int allow = rule(null, AccessRuleModel.TYPE_IP_ALLOW, Map.of("network", "127.0.0.1"), true);
        reload();
        assertThat(request("/"))
            .as("step 3: an allowed client passes the gate (positive anchor)")
            .contains("200").contains("guarded-content");

        // Step 4: a DISABLED rule is ignored entirely -- the allow rule switched off leaves
        // an empty tree, which passes.
        setEnabled(allow, false);
        reload();
        assertThat(request("/"))
            .as("step 4: a disabled rule is skipped as though it were absent")
            .contains("200").contains("guarded-content");
        setEnabled(allow, true);

        // Step 5: a denied network is refused with 403 and never reaches the upstream. It
        // answers 403 and not 401: no credential in this tree could change the verdict.
        int deny = rule(null, AccessRuleModel.TYPE_IP_DENY, Map.of("network", "127.0.0.0/8"), true);
        setSatisfy(AccessListModel.SATISFY_ALL);
        reload();
        int hits = upstreamHits.get();
        String denied = request("/");
        assertThat(denied)
            .as("step 5: a denied network refuses with 403 and no challenge")
            .contains("403").doesNotContain("guarded-content").doesNotContain("WWW-Authenticate");
        assertThat(upstreamHits.get())
            .as("step 5: the refused request must not reach the upstream")
            .isEqualTo(hits);

        // Step 6: THE counterfactual an earlier fix closed -- an explicit NULL satisfy used
        // to switch the entire control off. The root still combines with the stored default.
        listModel.find().where(AccessListModel.ID.eq(accessList.get(AccessListModel.ID)))
            .assign(AccessListModel.SATISFY, null)
            .updateAll();
        reload();
        hits = upstreamHits.get();
        assertThat(request("/"))
            .as("step 6: a NULL satisfy folds to 'any', where the allow rule still passes")
            .contains("200");
        setSatisfy(AccessListModel.SATISFY_ALL);
        reload();

        // Step 7: a credential leaf beside the address rules. Under ALL it is the only thing
        // still undecided, so the gate challenges; a wrong password stays a challenge and the
        // right one passes.
        deleteRule(deny);
        int credential = rule(null, AccessRuleModel.TYPE_BASIC_AUTH,
            credentials("operator", "s3cret"), true);
        reload();
        assertThat(request("/"))
            .as("step 7: satisfy=all without credentials answers the 401 challenge")
            .contains("401").contains("WWW-Authenticate");
        assertThat(request("/", authHeader("operator", "wrong")))
            .as("step 7: a wrong password stays 401")
            .contains("401");
        assertThat(request("/", authHeader("operator", "s3cret")))
            .as("step 7: correct credentials plus an allowed network pass satisfy=all")
            .contains("200").contains("guarded-content");

        // Step 8: an address rule that already decides the outcome answers 403 WITHOUT
        // asking for a password, even though the tree carries a credential leaf.
        int denyAgain = rule(null, AccessRuleModel.TYPE_IP_DENY,
            Map.of("network", "127.0.0.1"), true);
        reload();
        assertThat(request("/", authHeader("operator", "s3cret")))
            .as("step 8: satisfy=all needs BOTH; a denied address refuses despite credentials")
            .contains("403");
        assertThat(request("/"))
            .as("step 8: and it does not challenge for a credential that cannot help")
            .contains("403").doesNotContain("WWW-Authenticate");
        deleteRule(denyAgain);

        // Step 9: NESTING. Root(all) = [ allow 127.0.0.1, group(any) = [ deny 10.0.0.0/8,
        // basic auth ] ]. The nested any passes on the deny leaf alone (this client is not
        // in 10/8), so no challenge is emitted at all.
        deleteRule(credential);
        int group = rule(null, AccessRuleModel.TYPE_GROUP,
            Map.of("satisfy", AccessListModel.SATISFY_ANY), true);
        int nestedDeny = rule(group, AccessRuleModel.TYPE_IP_DENY,
            Map.of("network", "10.0.0.0/8"), true);
        rule(group, AccessRuleModel.TYPE_BASIC_AUTH, credentials("nested", "nested-pass"), true);
        reload();
        assertThat(request("/"))
            .as("step 9: a nested any group satisfied by an address needs no credential")
            .contains("200").contains("guarded-content");

        // Step 10: make the nested deny FAIL (deny this very client). The nested any is then
        // pending on its credential leaf, and the challenge comes from inside the group.
        setData(nestedDeny, Map.of("network", "127.0.0.1"));
        reload();
        assertThat(request("/"))
            .as("step 10: a nested group with only a credential left challenges")
            .contains("401").contains("WWW-Authenticate");
        assertThat(request("/", authHeader("nested", "nested-pass")))
            .as("step 10: and the nested credential satisfies the whole tree")
            .contains("200").contains("guarded-content");

        // Step 11: an UNKNOWN rule type FAILS CLOSED. The column is written behind the
        // model (the hook refuses the vocabulary miss), which is exactly the corruption
        // the gate must survive.
        var ruleModel = Models.get(AccessRuleModel.class);
        ruleModel.find().where(AccessRuleModel.ID.eq(group))
            .assign(AccessRuleModel.TYPE, "shenanigans")
            .updateAll();
        reload();
        hits = upstreamHits.get();
        assertThat(request("/", authHeader("nested", "nested-pass")))
            .as("step 11: a rule type the proxy cannot evaluate denies")
            .contains("403").doesNotContain("guarded-content");
        assertThat(upstreamHits.get())
            .as("step 11: and the upstream is never reached")
            .isEqualTo(hits);

        // Step 12: the model refuses that same value through its own pipeline, and refuses
        // to ENABLE a rule whose data cannot answer a request.
        Row garbage = ruleModel.findById(group);
        garbage.set(AccessRuleModel.TYPE, "shenanigans");
        assertThatThrownBy(() -> ruleModel.save(garbage))
            .as("step 12: a type outside the vocabulary is a violation")
            .isInstanceOf(Violations.class);

        Row halfTyped = ruleModel.createEmptyRow();
        halfTyped.set(AccessRuleModel.ACCESS_LIST_ID, accessList.get(AccessListModel.ID));
        halfTyped.set(AccessRuleModel.TYPE, AccessRuleModel.TYPE_IP_ALLOW);
        halfTyped.set(AccessRuleModel.DATA, Map.of("network", "not-an-address"));
        halfTyped.set(AccessRuleModel.ENABLED, true);
        assertThatThrownBy(() -> ruleModel.save(halfTyped))
            .as("step 12: an ENABLED rule with unusable data is refused at save time")
            .isInstanceOf(Violations.class);

        halfTyped.set(AccessRuleModel.ENABLED, false);
        ruleModel.save(halfTyped);
        assertThat(halfTyped.get(AccessRuleModel.ID))
            .as("step 12: the same rule saves fine as a switched-off draft")
            .isNotNull();
    }

    private static void reload() {
        proxy.getDispatcher().reloadRoutes();
    }

    /** Persist one rule and return its id. */
    private static int rule(Integer parentId, String type, Map<String, Object> data, boolean enabled) {
        var model = Models.get(AccessRuleModel.class);
        Row row = model.createEmptyRow();
        row.set(AccessRuleModel.ACCESS_LIST_ID, accessList.get(AccessListModel.ID));
        row.set(AccessRuleModel.PARENT_ID, parentId);
        row.set(AccessRuleModel.TYPE, type);
        row.set(AccessRuleModel.DATA, new LinkedHashMap<>(data));
        row.set(AccessRuleModel.ENABLED, enabled);
        row.set(AccessRuleModel.SORT, 0);
        model.save(row);
        return row.get(AccessRuleModel.ID);
    }

    /** The stored shape of a credential leaf: the password is an argon2 hash. */
    private static Map<String, Object> credentials(String username, String password) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", username);
        data.put("password", BasicCredentials.hashIfNeeded(password));
        return data;
    }

    private static void setEnabled(int ruleId, boolean enabled) {
        var model = Models.get(AccessRuleModel.class);
        Row row = model.findById(ruleId);
        row.set(AccessRuleModel.ENABLED, enabled);
        model.save(row);
    }

    private static void setData(int ruleId, Map<String, Object> data) {
        var model = Models.get(AccessRuleModel.class);
        Row row = model.findById(ruleId);
        row.set(AccessRuleModel.DATA, new LinkedHashMap<>(data));
        model.save(row);
    }

    private static void deleteRule(int ruleId) {
        var model = Models.get(AccessRuleModel.class);
        for (Row child : model.findChildren(accessList.get(AccessListModel.ID), ruleId)) {
            deleteRule(child.get(AccessRuleModel.ID));
        }
        model.delete(model.findById(ruleId));
    }

    private static void setSatisfy(String satisfy) {
        var listModel = Models.get(AccessListModel.class);
        Row row = listModel.findById(accessList.get(AccessListModel.ID));
        row.set(AccessListModel.SATISFY, satisfy);
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
