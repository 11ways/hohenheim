package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.ServerZenitRuntime;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.util.Map;

/**
 * Tests proxy dispatch features: custom response headers,
 * WebSocket upgrade gating, and the boolean checkbox fix.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProxyDispatchTest {

    private static boolean initialized = false;
    private static ProxyServer proxy;
    private static int httpPort;

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) return;
        initialized = true;

        java.io.File db = new java.io.File("hohenheim.db");
        if (db.exists()) db.delete();

        be.elevenways.hohenheim.server.sitetype.SiteTypes.register();
        HohenheimEndpoints.init();
        HohenheimDatabase.init();
        ServerZenitRuntime.init();
        Zenit.getHawkeye().setClientScriptLocation("/hohenheim.js");
    }

    @AfterAll
    static void cleanup() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
    }

    private static void setupSiteWithDomain(String hostname, Map<String, Object> settings,
                                             Map<String, Object> domainOverrides) {
        var ds = HohenheimDatabase.datasource();
        var siteModel = new SiteModel(ds);
        var domainModel = new SiteDomainModel(ds);

        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Test Site " + hostname);
        site.set(SiteModel.SLUG, hostname.replace(".", "-"));
        site.set(SiteModel.SITE_TYPE, "hohenheim:proxy");
        site.set(SiteModel.SETTINGS, settings);
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);

        int siteId = site.get(SiteModel.ID);
        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, "exact");

        if (domainOverrides != null) {
            for (var entry : domainOverrides.entrySet()) {
                // Use reflection-free approach: set known fields
                switch (entry.getKey()) {
                    case "force_ssl" -> domain.set(SiteDomainModel.FORCE_SSL, (Boolean) entry.getValue());
                    case "hsts_enabled" -> domain.set(SiteDomainModel.HSTS_ENABLED, (Boolean) entry.getValue());
                    case "hsts_subdomains" -> domain.set(SiteDomainModel.HSTS_SUBDOMAINS, (Boolean) entry.getValue());
                    case "custom_headers" -> domain.set(SiteDomainModel.CUSTOM_HEADERS, entry.getValue());
                }
            }
        }

        domainModel.save(domain);
    }

    @Test
    @Order(1)
    void customHeadersAppearOnResponse() throws Exception {
        // Reset DB
        java.io.File db = new java.io.File("hohenheim.db");
        if (db.exists()) db.delete();
        HohenheimDatabase.init();

        // Create a site with custom response headers
        Map<String, Object> headers = Map.of(
            "X-Frame-Options", "DENY",
            "X-Content-Type-Options", "nosniff"
        );

        setupSiteWithDomain("headers.test", Map.of("forward_host", "127.0.0.1", "forward_port", 9999),
            Map.of("custom_headers", headers, "force_ssl", false));

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        proxy = new ProxyServer();
        proxy.start();

        var info = proxy.getHttpListenerInfo();
        assertThat(info).isNotNull();
        httpPort = ((InetSocketAddress) info.getAddress()).getPort();

        // Make a request to the proxy (upstream won't respond, but headers are set before dispatch)
        // Use raw socket to see response headers even on connection failure
        try (Socket socket = new Socket("127.0.0.1", httpPort)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: headers.test\r\nConnection: close\r\n\r\n").getBytes());
            out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
            }

            String resp = response.toString();
            // The response should contain our custom headers
            // (even though upstream is unavailable, the proxy sets response headers before forwarding)
            assertThat(resp).contains("X-Frame-Options: DENY");
            assertThat(resp).contains("X-Content-Type-Options: nosniff");
        }

        proxy.stop();
        proxy = null;
    }

    @Test
    @Order(2)
    void hstsHeaderOnResponse() throws Exception {
        java.io.File db = new java.io.File("hohenheim.db");
        if (db.exists()) db.delete();
        HohenheimDatabase.init();

        setupSiteWithDomain("hsts.test", Map.of("forward_host", "127.0.0.1", "forward_port", 9999),
            Map.of("hsts_enabled", true, "hsts_subdomains", true, "force_ssl", false));

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        proxy = new ProxyServer();
        proxy.start();

        var info = proxy.getHttpListenerInfo();
        httpPort = ((InetSocketAddress) info.getAddress()).getPort();

        try (Socket socket = new Socket("127.0.0.1", httpPort)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: hsts.test\r\nConnection: close\r\n\r\n").getBytes());
            out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
            }

            String resp = response.toString();
            assertThat(resp).contains("Strict-Transport-Security: max-age=31536000; includeSubDomains");
        }

        proxy.stop();
        proxy = null;
    }

    @Test
    @Order(3)
    void websocketUpgradeBlockedWhenDisabled() throws Exception {
        java.io.File db = new java.io.File("hohenheim.db");
        if (db.exists()) db.delete();
        HohenheimDatabase.init();

        // Create site with websocket_upgrade=false
        setupSiteWithDomain("nows.test",
            Map.of("forward_host", "127.0.0.1", "forward_port", 9999, "websocket_upgrade", false),
            Map.of("force_ssl", false));

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        proxy = new ProxyServer();
        proxy.start();

        var info = proxy.getHttpListenerInfo();
        httpPort = ((InetSocketAddress) info.getAddress()).getPort();

        // Send a WebSocket upgrade request
        try (Socket socket = new Socket("127.0.0.1", httpPort)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: nows.test\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes());
            out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String statusLine = reader.readLine();

            // Should get 403, not 101 (upgrade) or 502 (proxy failure)
            assertThat(statusLine).contains("403");
        }

        proxy.stop();
        proxy = null;
    }

    @Test
    @Order(4)
    void websocketUpgradeAllowedWhenEnabled() throws Exception {
        java.io.File db = new java.io.File("hohenheim.db");
        if (db.exists()) db.delete();
        HohenheimDatabase.init();

        // Create site with websocket_upgrade=true (default)
        setupSiteWithDomain("ws.test",
            Map.of("forward_host", "127.0.0.1", "forward_port", 9999, "websocket_upgrade", true),
            Map.of("force_ssl", false));

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        proxy = new ProxyServer();
        proxy.start();

        var info = proxy.getHttpListenerInfo();
        httpPort = ((InetSocketAddress) info.getAddress()).getPort();

        // Send a WebSocket upgrade request
        try (Socket socket = new Socket("127.0.0.1", httpPort)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: ws.test\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes());
            out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String statusLine = reader.readLine();

            // Should NOT get 403 -- it should try to proxy (502 from no upstream, or timeout)
            assertThat(statusLine).doesNotContain("403");
        }

        proxy.stop();
        proxy = null;
    }

    @Test
    @Order(5)
    void booleanSettingCanBeToggledOff() throws Exception {
        java.io.File db = new java.io.File("hohenheim.db");
        if (db.exists()) db.delete();
        HohenheimDatabase.init();

        // Verify that extractTypeSettings correctly handles unchecked booleans
        // by creating a site, then checking its settings persist correctly
        var ds = HohenheimDatabase.datasource();
        var siteModel = new SiteModel(ds);

        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Toggle Test");
        site.set(SiteModel.SLUG, "toggle-test");
        site.set(SiteModel.SITE_TYPE, "hohenheim:proxy");
        site.set(SiteModel.SETTINGS, Map.of(
            "forward_host", "127.0.0.1",
            "forward_port", 8080,
            "websocket_upgrade", false
        ));
        site.set(SiteModel.STATUS, "active");
        siteModel.save(site);

        Row loaded = siteModel.findById(site.get(SiteModel.ID));
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) loaded.get(SiteModel.SETTINGS);
        assertThat(settings.get("websocket_upgrade")).isEqualTo(false);
    }
}
