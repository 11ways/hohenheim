package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.auth.SiteAuthProviders;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the Proteus auth gate against a fake Proteus realm server: cold-start
 * redirect, verify-callback success, the authenticated session, the acpl persistent-cookie path,
 * and permission denial.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
class ProteusAuthGateTest {

    private static boolean initialized = false;
    private static ProxyServer proxy;
    private static int httpPort;
    private static HttpServer proteus;
    private static HttpServer upstream;

    @BeforeAll
    static void setup() throws Exception {
        if (initialized) {
            return;
        }
        initialized = true;

        File db = File.createTempFile("hohenheim-proteus", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());

        SiteTypes.boot();
        HohenheimEndpoints.init();
        HohenheimDatabase.init();
        HohenheimTestRuntime.ensureBooted();
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");
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

    /** A fake Proteus realm server returning canned responses for the four protocol calls. */
    private static HttpServer startFakeProteus(String permission) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        String claims = "\"permissions\":{\"nodes\":[{\"permission\":\"" + permission + "\",\"value\":true}]}";
        server.createContext("/realm/rc/api/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String json;
            if (path.endsWith("create_login_session")) {
                json = "{\"id\":\"rlid-1\",\"login_url\":\"http://proteus.example/login\"}";
            } else if (path.endsWith("remote_login_result")
                    || path.endsWith("persistent_cookie_login_result")) {
                json = "{\"success\":true,\"finished\":true,\"identity\":{\"handle\":\"bob\"}," + claims + "}";
            } else if (path.endsWith("register_persistent_cookie")) {
                json = "{\"success\":true,\"proteus_cookie_id\":\"pc-1\"}";
            } else {
                json = "{}";
            }
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static int setupGatedSite(String hostname, String requiredPermission) {
        var providerModel = Models.get(SiteAuthProviderModel.class);
        Row provider = providerModel.createEmptyRow();
        provider.set(SiteAuthProviderModel.NAME, "Proteus " + hostname);
        provider.set(SiteAuthProviderModel.PROVIDER_TYPE, "hohenheim:proteus");
        provider.set(SiteAuthProviderModel.CONFIG, Map.of(
            "endpoint", "http://127.0.0.1:" + proteus.getAddress().getPort() + "/",
            "realm_client", "rc",
            "access_key", "key",
            "authenticator", "password"));
        provider.set(SiteAuthProviderModel.REQUIRED_PERMISSION, requiredPermission);
        providerModel.save(provider);
        int providerId = provider.get(SiteAuthProviderModel.ID);

        var siteModel = Models.get(SiteModel.class);
        var domainModel = Models.get(SiteDomainModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Site " + hostname);
        site.set(SiteModel.SLUG, hostname.replace(".", "-"));
        site.set(SiteModel.SITE_TYPE, "hohenheim:proxy");
        site.set(SiteModel.SETTINGS, Map.of("forward_host", "127.0.0.1",
            "forward_port", upstream.getAddress().getPort()));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        site.set(SiteModel.AUTH_PROVIDER_ID, providerId);
        siteModel.save(site);
        int siteId = site.get(SiteModel.ID);

        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, "exact");
        domainModel.save(domain);
        return siteId;
    }

    @Test
    void proteusRedirectVerifyAndPersistentCookieFlow() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            byte[] body = "upstream-ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();

        proteus = startFakeProteus("hohenheim.site.gated");

        setupGatedSite("gated.test", "hohenheim.site.gated");
        setupGatedSite("denied.test", "hohenheim.site.elsewhere");  // claim won't satisfy this

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        proxy = new ProxyServer();
        proxy.start();
        httpPort = ((InetSocketAddress) proxy.getHttpListenerInfo().getAddress()).getPort();

        // 1. Cold start: redirect to Proteus + a pending session cookie.
        AtomicReference<String> sessionCookie = new AtomicReference<>();
        String cold = request("gated.test", "/", null, sessionCookie);
        assertThat(statusLine(cold)).contains("302");
        assertThat(headerValue(cold, "Location")).isEqualTo("http://proteus.example/login");
        assertThat(sessionCookie.get()).as("pending session cookie").isNotNull();

        // 2. Verify callback: success + permitted -> redirect to afterLogin + acpl cookie.
        AtomicReference<String> afterVerifyCookie = new AtomicReference<>();
        AtomicReference<String> acplCookie = new AtomicReference<>();
        String verify = requestCapturingCookies("gated.test", "/?proteus=verify",
            sessionCookie.get(), afterVerifyCookie, acplCookie);
        assertThat(statusLine(verify)).contains("302");
        assertThat(headerValue(verify, "Location")).isEqualTo("http://gated.test/");
        assertThat(acplCookie.get()).as("acpl persistent cookie auto-set").isNotNull();

        // 3. Authenticated session -> forwarded.
        String authed = request("gated.test", "/", sessionCookie.get(), new AtomicReference<>());
        assertThat(statusLine(authed)).contains("200");
        assertThat(authed).contains("upstream-ok");

        // 4. acpl persistent-cookie path establishes a fresh session -> forwarded.
        String viaAcpl = request("gated.test", "/", acplCookie.get(), new AtomicReference<>());
        assertThat(statusLine(viaAcpl)).contains("200");
        assertThat(viaAcpl).contains("upstream-ok");

        // 5. Permission denied: identity authenticates but lacks the required permission.
        AtomicReference<String> deniedSession = new AtomicReference<>();
        String deniedCold = request("denied.test", "/", null, deniedSession);
        assertThat(statusLine(deniedCold)).contains("302");
        String denied = request("denied.test", "/?proteus=verify", deniedSession.get(), new AtomicReference<>());
        assertThat(statusLine(denied)).contains("403");

        proxy.stop();
        proxy = null;
    }

    private String request(String host, String path, String cookie, AtomicReference<String> cookieOut)
            throws Exception {
        return requestCapturingCookies(host, path, cookie, cookieOut, new AtomicReference<>());
    }

    private String requestCapturingCookies(String host, String path, String cookie,
                                           AtomicReference<String> sessionOut,
                                           AtomicReference<String> acplOut) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", httpPort)) {
            socket.setSoTimeout(5000);
            StringBuilder req = new StringBuilder("GET ").append(path)
                .append(" HTTP/1.1\r\nHost: ").append(host).append("\r\n");
            if (cookie != null) {
                req.append("Cookie: ").append(cookie).append("\r\n");
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
            String response = buf.toString(StandardCharsets.UTF_8);
            for (String line : response.split("\r\n")) {
                if (line.isEmpty()) {
                    break;
                }
                if (line.regionMatches(true, 0, "Set-Cookie:", 0, 11)) {
                    String pair = line.substring(11).trim().split(";", 2)[0];
                    if (pair.startsWith("hh_site_session_id=")) {
                        sessionOut.set(pair);
                    } else if (pair.startsWith("acpl=")) {
                        acplOut.set(pair);
                    }
                }
            }
            return response;
        }
    }

    private static String statusLine(String response) {
        int eol = response.indexOf("\r\n");
        return eol < 0 ? response : response.substring(0, eol);
    }

    private static String headerValue(String response, String name) {
        for (String line : response.split("\r\n")) {
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }
}
