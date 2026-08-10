package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.auth.SiteAuthProviders;
import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.setting.ServerSettings;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the per-site Basic auth gate: challenge, reject, admit, the
 * session-cookie fast path (which also confirms Set-Cookie survives the proxied response),
 * and the trust gating of the session cookie's Secure flag.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
class ProxyAuthGateTest {

    private static boolean initialized = false;
    private static ProxyServer proxy;
    private static int httpPort;

    @BeforeAll
    static void setup() throws Exception {
        if (initialized) {
            return;
        }
        initialized = true;

        SiteTypes.boot();
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");
    }

    @AfterAll
    static void cleanup() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
    }

    @Test
    void basicGateChallengesRejectsAndAdmits() throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            byte[] body = "upstream-ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();

        var providerModel = Models.get(SiteAuthProviderModel.class);
        Row provider = providerModel.createEmptyRow();
        provider.set(SiteAuthProviderModel.NAME, "Basic Gate");
        provider.set(SiteAuthProviderModel.PROVIDER_TYPE, "hohenheim:basic");
        provider.set(SiteAuthProviderModel.CONFIG, new BasicAuthProviderType().normalizeConfigForSave(
            Map.of("credentials", Map.of("alice", "s3cret")), null));
        providerModel.save(provider);
        int providerId = provider.get(SiteAuthProviderModel.ID);

        var siteModel = Models.get(SiteModel.class);
        var domainModel = Models.get(SiteDomainModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Gated Site");
        site.set(SiteModel.SLUG, "gated");
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
        domain.set(SiteDomainModel.HOSTNAME, "gated.test");
        domain.set(SiteDomainModel.MATCH_TYPE, "exact");
        domain.set(SiteDomainModel.FORCE_SSL, false);
        domainModel.save(domain);

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        proxy = new ProxyServer();
        proxy.start();
        httpPort = ((InetSocketAddress) proxy.getHttpListenerInfo().getAddress()).getPort();

        // 1. No credentials -> 401 challenge.
        String noAuth = request("gated.test", null, null);
        assertThat(statusLine(noAuth)).contains("401");
        assertThat(noAuth).containsIgnoringCase("WWW-Authenticate");

        // 2. Wrong credentials -> 401.
        assertThat(statusLine(request("gated.test", basic("alice", "nope"), null))).contains("401");

        // 3. Correct credentials -> forwarded (200) + session cookie issued.
        String ok = request("gated.test", basic("alice", "s3cret"), null);
        assertThat(statusLine(ok)).contains("200");
        assertThat(ok).contains("upstream-ok");
        String setCookie = headerValue(ok, "Set-Cookie");
        assertThat(setCookie).as("session cookie should survive the proxied response").isNotNull();
        assertThat(setCookie).contains("hh_site_session_id=");

        // 4. Session cookie alone (no Basic header) -> fast-path allow.
        String cookiePair = setCookie.split(";", 2)[0];
        String viaCookie = request("gated.test", null, cookiePair);
        assertThat(statusLine(viaCookie)).contains("200");
        assertThat(viaCookie).contains("upstream-ok");

        // 5..7 assert on the RENDERED Set-Cookie text, not an internal flag: the whole bug
        // class is a cookie that claims to be Secure and is not (or the reverse). This
        // listener faces the open internet, so the ONLY peer allowed to state the client's
        // proto is one holding a configured X-Hohenheim-Key.
        boolean hadAssume = ServerSettings.VALUES.hasValue(ServerSettings.Network.ASSUME_HTTPS);
        Boolean previousAssume = ServerSettings.VALUES.getValue(ServerSettings.Network.ASSUME_HTTPS);
        List<String> previousKeys = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS);
        ServerSettings.VALUES.setValue(ServerSettings.Network.ASSUME_HTTPS, false);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, List.of("front-key"));

        try {
            // 5. An UNAUTHENTICATED peer claiming https must NOT get a Secure cookie:
            // believing it would let the session id travel back over cleartext.
            String spoofed = headerValue(
                request("gated.test", basic("alice", "s3cret"), null,
                    "X-Forwarded-Proto: https", "X-Hohenheim-Key: wrong-key"),
                "Set-Cookie");
            assertThat(spoofed).as("step 5: the cookie is still issued").isNotNull();
            assertThat(spoofed)
                .as("step 5: an unauthenticated peer's X-Forwarded-Proto must not mark the cookie Secure")
                .doesNotContainIgnoringCase("Secure");

            // 6. The same header from a key-authenticated remote hohenheim must.
            String trusted = headerValue(
                request("gated.test", basic("alice", "s3cret"), null,
                    "X-Forwarded-Proto: https", "X-Hohenheim-Key: front-key"),
                "Set-Cookie");
            assertThat(trusted)
                .as("step 6: a trusted remote proxy's X-Forwarded-Proto must mark the cookie Secure")
                .containsIgnoringCase("Secure");

            // 7. network.assume_https is the operator declaring TLS terminates in front of
            // hohenheim; no header is involved, so nothing can spoof it.
            ServerSettings.VALUES.setValue(ServerSettings.Network.ASSUME_HTTPS, true);
            String assumed = headerValue(
                request("gated.test", basic("alice", "s3cret"), null), "Set-Cookie");
            assertThat(assumed)
                .as("step 7: assume_https marks the cookie Secure with no header at all")
                .containsIgnoringCase("Secure");
        } finally {
            ServerSettings.VALUES.setValue(ServerSettings.Network.ASSUME_HTTPS,
                hadAssume ? previousAssume : null);
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS, previousKeys);
        }

        proxy.stop();
        proxy = null;
        upstream.stop(0);
    }

    private static String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    private String request(String host, String authHeader, String cookie,
                           String... extraHeaders) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", httpPort)) {
            socket.setSoTimeout(4000);
            StringBuilder req = new StringBuilder("GET / HTTP/1.1\r\nHost: ").append(host).append("\r\n");
            if (authHeader != null) {
                req.append("Authorization: ").append(authHeader).append("\r\n");
            }
            if (cookie != null) {
                req.append("Cookie: ").append(cookie).append("\r\n");
            }
            for (String header : extraHeaders) {
                req.append(header).append("\r\n");
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
            return buf.toString(StandardCharsets.UTF_8);
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
