package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.proxy.auth.ProxyAuthThrottle;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Proteus gate against a fake realm: the login round trip bound to the browser that started
 * it, no stored state before an identity is verified, session rotation, the remember-me path,
 * permissions re-checked per gate on the stored claim, and sessions bound to the realm they were
 * minted under.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
class ProteusAuthGateTest {

    private static final String GATED = "hohenheim.site.gated";
    private static final String ADMIN = "hohenheim.site.admin";
    private static final Pattern RETURN_URL = Pattern.compile("\"return_url\"\\s*:\\s*\"([^\"]*)\"");

    private static boolean initialized = false;
    private static ProxyServer proxy;
    private static int httpPort;
    private static HttpServer proteus;
    private static HttpServer upstream;

    /** The permissions the fake realm grants the next identity it returns. */
    private static final AtomicReference<List<String>> GRANTED = new AtomicReference<>(List.of(GATED));
    /** The return URL the gate handed the fake realm on its latest create_login_session. */
    private static final AtomicReference<String> LAST_RETURN_URL = new AtomicReference<>();
    private static final AtomicInteger LOGIN_RESULT_POLLS = new AtomicInteger();
    private static final AtomicInteger LOGIN_SESSIONS_CREATED = new AtomicInteger();

    @BeforeAll
    static void setup() throws Exception {
        if (initialized) {
            return;
        }
        initialized = true;

        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");
        ProxyAuthThrottle.clearForTests();
    }

    @AfterAll
    static void cleanup() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        if (proteus != null) {
            proteus.stop(0);
        }
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    /** A fake Proteus realm answering the four protocol calls for every realm client. */
    private static HttpServer startFakeProteus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/realm/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            StringBuilder nodes = new StringBuilder();
            for (String permission : GRANTED.get()) {
                if (nodes.length() > 0) {
                    nodes.append(',');
                }
                nodes.append("{\"permission\":\"").append(permission).append("\",\"value\":true}");
            }
            String claims = "\"permissions\":{\"nodes\":[" + nodes + "]}";
            String json;
            if (path.endsWith("create_login_session")) {
                LOGIN_SESSIONS_CREATED.incrementAndGet();
                Matcher matcher = RETURN_URL.matcher(body);
                LAST_RETURN_URL.set(matcher.find()
                    ? matcher.group(1).replace("\\/", "/").replace("\\u0026", "&") : null);
                json = "{\"id\":\"rlid-" + LOGIN_SESSIONS_CREATED.get()
                    + "\",\"login_url\":\"http://proteus.example/login\"}";
            } else if (path.endsWith("remote_login_result")) {
                LOGIN_RESULT_POLLS.incrementAndGet();
                json = "{\"success\":true,\"finished\":true,\"identity\":{\"handle\":\"bob\"}," + claims + "}";
            } else if (path.endsWith("persistent_cookie_login_result")) {
                json = "{\"success\":true,\"finished\":true,\"identity\":{\"handle\":\"bob\"}," + claims + "}";
            } else if (path.endsWith("register_persistent_cookie")) {
                json = "{\"success\":true,\"proteus_cookie_id\":\"pc-1\"}";
            } else {
                json = "{}";
            }
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static int provider(String name, String realmClient, String requiredPermission) {
        var providerModel = Models.get(SiteAuthProviderModel.class);
        Row provider = providerModel.createEmptyRow();
        provider.set(SiteAuthProviderModel.NAME, name);
        provider.set(SiteAuthProviderModel.PROVIDER_TYPE, "hohenheim:proteus");
        provider.set(SiteAuthProviderModel.CONFIG, proteusConfig(realmClient));
        provider.set(SiteAuthProviderModel.REQUIRED_PERMISSION, requiredPermission);
        providerModel.save(provider);
        return provider.get(SiteAuthProviderModel.ID);
    }

    private static Map<String, Object> proteusConfig(String realmClient) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("endpoint", "http://127.0.0.1:" + proteus.getAddress().getPort() + "/");
        config.put("realm_client", realmClient);
        config.put("access_key", "key");
        config.put("authenticator", "password");
        return config;
    }

    private static int gatedSite(String hostname, int providerId) {
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Site " + hostname);
        site.set(SiteModel.SLUG, hostname.replace(".", "-"));
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:address");
        site.set(SiteModel.SETTINGS, Map.of("forward_host", "127.0.0.1",
            "forward_port", upstream.getAddress().getPort()));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        site.set(SiteModel.AUTH_PROVIDER_ID, providerId);
        siteModel.save(site);
        int siteId = site.get(SiteModel.ID);

        var domainModel = Models.get(SiteDomainModel.class);
        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, "exact");
        domain.set(SiteDomainModel.FORCE_SSL, false);
        domainModel.save(domain);
        return siteId;
    }

    /** Guard {@code path} on the site with one auth_provider leaf demanding {@code permission}. */
    private static void guardWithProviderLeaf(int siteId, String path, int providerId, String permission) {
        var lists = Models.get(AccessListModel.class);
        Row list = lists.createEmptyRow();
        list.set(AccessListModel.NAME, "Leaf " + path);
        list.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ANY);
        lists.save(list);
        int listId = list.get(AccessListModel.ID);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put(AccessRuleModel.PROVIDER_ID.getName(), providerId);
        data.put(AccessRuleModel.PROVIDER_REQUIRED_PERMISSION.getName(), permission);
        var rules = Models.get(AccessRuleModel.class);
        Row rule = rules.createEmptyRow();
        rule.set(AccessRuleModel.ACCESS_LIST_ID, listId);
        rule.set(AccessRuleModel.TYPE, AccessRuleModel.TYPE_AUTH_PROVIDER);
        rule.set(AccessRuleModel.DATA, data);
        rule.set(AccessRuleModel.ENABLED, true);
        rule.set(AccessRuleModel.SORT, 0);
        rules.save(rule);

        var guarded = Models.get(ProtectedPathModel.class);
        Row row = guarded.createEmptyRow();
        row.set(ProtectedPathModel.SITE_ID, siteId);
        row.set(ProtectedPathModel.PATH, path);
        row.set(ProtectedPathModel.ACCESS_LIST_ID, listId);
        guarded.save(row);
    }

    @Test
    void loginIsBoundToItsBrowserRotatesAndHonoursEveryGatesPermission() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            byte[] body = "upstream-ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
        proteus = startFakeProteus();

        int gatedProvider = provider("Proteus gated", "rc", GATED);
        int gatedSite = gatedSite("gated.test", gatedProvider);
        guardWithProviderLeaf(gatedSite, "/admin", gatedProvider, ADMIN);
        gatedSite("denied.test", provider("Proteus denied", "rc", "hohenheim.site.elsewhere"));

        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        proxy = new ProxyServer();
        proxy.start();
        httpPort = ((InetSocketAddress) proxy.getHttpListenerInfo().getAddress()).getPort();

        // 1. Cold start: a redirect to Proteus and a sealed pending-login cookie, but NO session:
        //    nothing is stored for a visitor who has not proven anything yet.
        Response cold = request("gated.test", "/");
        assertThat(cold.status()).as("step 1: an anonymous visitor is sent to Proteus").isEqualTo(302);
        assertThat(cold.header("Location")).as("step 1: to the realm's login page")
            .isEqualTo("http://proteus.example/login");
        assertThat(cold.cookie("hh_site_session_id"))
            .as("step 1: no session is minted before an identity is verified").isNull();
        String pending = cold.cookie("hh_site_login");
        assertThat(pending).as("step 1: the pending login rides a browser cookie").isNotBlank();
        String returnUrl = LAST_RETURN_URL.get();
        assertThat(returnUrl).as("step 1: the return URL carries the verify marker and a state")
            .contains("proteus=verify").contains("proteus_state=");
        String returnPath = pathAndQuery(returnUrl);

        // 2. The browser that COMPLETED the login without having started it (a phished victim,
        //    or a forged link): the state alone logs nobody in.
        int pollsBefore = LOGIN_RESULT_POLLS.get();
        Response stranger = request("gated.test", returnPath);
        assertThat(stranger.status()).as("step 2: a browser without the pending login starts its own")
            .isEqualTo(302);
        assertThat(stranger.cookie("hh_site_session_id"))
            .as("step 2: and gets no session from somebody else's login").isNull();
        assertThat(LOGIN_RESULT_POLLS.get()).as("step 2: the realm is not even asked").isEqualTo(pollsBefore);

        // 3. The INITIATOR without the state (the phisher polling the login his victim completed),
        //    and the initiator holding the state of ANOTHER login: both refused before polling.
        Response initiator = request("gated.test", "/?proteus=verify", cookie("hh_site_login", pending));
        assertThat(initiator.status()).as("step 3: the pending cookie alone does not verify").isEqualTo(302);
        assertThat(initiator.cookie("hh_site_session_id")).as("step 3: no session for it").isNull();
        String otherState = pathAndQuery(LAST_RETURN_URL.get());
        Response swapped = request("gated.test", otherState, cookie("hh_site_login", pending));
        assertThat(swapped.cookie("hh_site_session_id"))
            .as("step 3: a state minted for another login does not verify this one").isNull();
        assertThat(LOGIN_RESULT_POLLS.get()).as("step 3: the realm is still not asked").isEqualTo(pollsBefore);

        // 4. The initiating browser returning with its own state: verified, a session, the acpl
        //    remember-me cookie, the pending cookie spent, and back to the page without our params.
        Response verified = request("gated.test", returnPath, cookie("hh_site_login", pending));
        assertThat(verified.status()).as("step 4: a verified login redirects back").isEqualTo(302);
        assertThat(verified.header("Location")).as("step 4: to the page, minus the gate's own parameters")
            .isEqualTo("http://gated.test/");
        String session = verified.cookie("hh_site_session_id");
        assertThat(session).as("step 4: a session is minted now").isNotBlank();
        assertThat(verified.cookie("acpl")).as("step 4: and the remember-me cookie").isNotBlank();
        assertThat(verified.clears("hh_site_login")).as("step 4: the pending login is spent").isTrue();
        assertThat(LOGIN_RESULT_POLLS.get()).as("step 4: the realm was asked exactly once")
            .isEqualTo(pollsBefore + 1);
        String acpl = verified.cookie("acpl");

        // 5. The session admits the site.
        Response admitted = request("gated.test", "/", cookie("hh_site_session_id", session));
        assertThat(admitted.status()).as("step 5: the session is forwarded").isEqualTo(200);
        assertThat(admitted.body()).as("step 5: to the upstream").contains("upstream-ok");

        // 6. The SAME provider guards /admin with a leaf demanding a permission this identity was
        //    never granted: the site-level session must not satisfy it.
        Response admin = request("gated.test", "/admin", cookie("hh_site_session_id", session));
        assertThat(admin.status()).as("step 6: the leaf re-checks its own permission, so it asks again")
            .isEqualTo(302);
        assertThat(admin.body()).as("step 6: the upstream is never reached").doesNotContain("upstream-ok");

        // 7. The realm grants the permission; logging in through the leaf ROTATES the session: the
        //    old token dies, the new one admits both the site and /admin.
        GRANTED.set(List.of(GATED, ADMIN));
        String adminPending = admin.cookie("hh_site_login");
        String adminReturn = pathAndQuery(LAST_RETURN_URL.get());
        assertThat(adminReturn).as("step 7: the leaf's login returns to /admin").startsWith("/admin?");
        Response adminVerified = request("gated.test", adminReturn,
            cookie("hh_site_session_id", session) + "; hh_site_login=" + adminPending);
        String rotated = adminVerified.cookie("hh_site_session_id");
        assertThat(rotated).as("step 7: a fresh session is minted").isNotBlank().isNotEqualTo(session);
        assertThat(request("gated.test", "/admin", cookie("hh_site_session_id", rotated)).status())
            .as("step 7: the new session satisfies the leaf").isEqualTo(200);
        assertThat(request("gated.test", "/", cookie("hh_site_session_id", session)).status())
            .as("step 7: the session presented at login was revoked").isEqualTo(302);

        // 8. The remember-me cookie alone logs the visitor straight in.
        Response viaAcpl = request("gated.test", "/", cookie("acpl", acpl));
        assertThat(viaAcpl.status()).as("step 8: acpl establishes a session").isEqualTo(200);
        assertThat(viaAcpl.body()).as("step 8: and forwards").contains("upstream-ok");

        // 9. An identity without the site's permission is refused at verify.
        Response deniedCold = request("denied.test", "/");
        Response denied = request("denied.test", pathAndQuery(LAST_RETURN_URL.get()),
            cookie("hh_site_login", deniedCold.cookie("hh_site_login")));
        assertThat(denied.status()).as("step 9: a verified identity lacking the permission gets 403")
            .isEqualTo(403);

        // 10. Re-pointing the provider at another realm ends every session it minted.
        var providerModel = Models.get(SiteAuthProviderModel.class);
        Row stored = providerModel.findById(gatedProvider);
        stored.set(SiteAuthProviderModel.CONFIG, proteusConfig("rc-elsewhere"));
        providerModel.save(stored);
        proxy.reload();
        assertThat(request("gated.test", "/", cookie("hh_site_session_id", rotated)).status())
            .as("step 10: a session minted under the old realm no longer admits").isEqualTo(302);

        proxy.stop();
        proxy = null;
    }

    private static String cookie(String name, String value) {
        return name + "=" + value;
    }

    private static String pathAndQuery(String url) {
        URI uri = URI.create(url);
        return uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
    }

    private Response request(String host, String path) throws Exception {
        return request(host, path, null);
    }

    private Response request(String host, String path, String cookieHeader) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", httpPort)) {
            socket.setSoTimeout(5000);
            StringBuilder req = new StringBuilder("GET ").append(path)
                .append(" HTTP/1.1\r\nHost: ").append(host).append("\r\n");
            if (cookieHeader != null) {
                req.append("Cookie: ").append(cookieHeader).append("\r\n");
            }
            req.append("Connection: close\r\n\r\n");
            socket.getOutputStream().write(req.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return Response.parse(buf.toString(StandardCharsets.UTF_8));
        }
    }

    /** A raw HTTP response: status, header lines and body. */
    private record Response(int status, List<String> headers, String body) {

        static Response parse(String raw) {
            int split = raw.indexOf("\r\n\r\n");
            String head = split < 0 ? raw : raw.substring(0, split);
            String body = split < 0 ? "" : raw.substring(split + 4);
            String[] lines = head.split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            List<String> headers = new ArrayList<>(List.of(lines).subList(1, lines.length));
            return new Response(status, headers, body);
        }

        String header(String name) {
            for (String line : this.headers) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                    return line.substring(colon + 1).trim();
                }
            }
            return null;
        }

        /** Whether this response expires the named cookie. */
        boolean clears(String name) {
            for (String line : this.headers) {
                if (line.regionMatches(true, 0, "Set-Cookie:", 0, 11)
                        && line.substring(11).trim().startsWith(name + "=")
                        && line.toLowerCase().contains("max-age=0")) {
                    return true;
                }
            }
            return false;
        }

        /** The value this response sets for a cookie, or null when it does not set it. */
        String cookie(String name) {
            for (String line : this.headers) {
                if (line.regionMatches(true, 0, "Set-Cookie:", 0, 11)) {
                    String pair = line.substring(11).trim().split(";", 2)[0];
                    if (pair.startsWith(name + "=")) {
                        return pair.substring(name.length() + 1);
                    }
                }
            }
            return null;
        }
    }
}
