package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: a managed Node site with {@code use_ports=false} listens on a unix socket and is
 * proxied through the loopback bridge. Exercises socket allocation, the PATH_TO_SOCKET child
 * contract, and the per-process bridge transport. Skipped if node is not installed.
 */
class ManagedNodeSocketTest {

    private static ProxyServer proxy;
    private static boolean nodeAvailable;

    @BeforeAll
    static void boot() throws Exception {
        nodeAvailable = new File("/usr/local/bin/node").canExecute()
            || new File("/usr/bin/node").canExecute();

        SiteTypes.boot();
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");
    }

    @AfterAll
    static void stop() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
    }

    @Test
    @Timeout(40)
    void nodeSiteWithUsePortsFalseServesOverUnixSocket() throws Exception {
        LiveLane.require(LiveLane.Need.NODE, nodeAvailable,
            "node is not installed");

        // A tiny HTTP server that binds the unix socket given in PATH_TO_SOCKET.
        String nodeScript =
            "const http = require('http');\n"
            + "const fs = require('fs');\n"
            + "const sock = process.env.PATH_TO_SOCKET;\n"
            + "try { fs.unlinkSync(sock); } catch (e) {}\n"
            + "http.createServer((req, res) => { res.writeHead(200); res.end('node-unix-ok'); }).listen(sock);\n";
        Path scriptFile = Files.createTempFile("hh-node-sock", ".js");
        Files.writeString(scriptFile, nodeScript);
        scriptFile.toFile().deleteOnExit();

        setupNodeSocketSite("nodesock.test", scriptFile.toString());

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        proxy = new ProxyServer();
        proxy.start();
        int httpPort = ((InetSocketAddress) proxy.getHttpListenerInfo().getAddress()).getPort();

        // Retry: the child needs a moment to spawn + bind, and its bridge is opened after the
        // startup grace window. The proxy keeps the child alive between attempts.
        String response = null;
        for (int attempt = 0; attempt < 30 && (response == null || !response.contains("node-unix-ok")); attempt++) {
            try (Socket client = new Socket("127.0.0.1", httpPort)) {
                client.setSoTimeout(5000);
                client.getOutputStream().write(
                    "GET / HTTP/1.1\r\nHost: nodesock.test\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                response = new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
            if (response == null || !response.contains("node-unix-ok")) {
                Thread.sleep(500);
            }
        }

        assertThat(response).as("served over unix socket via the bridge")
            .contains("200").contains("node-unix-ok");
    }

    private static void setupNodeSocketSite(String hostname, String scriptPath) {
        var siteModel = Models.get(SiteModel.class);
        var domainModel = Models.get(SiteDomainModel.class);

        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Node Socket Site");
        site.set(SiteModel.SLUG, "node-socket-site");
        site.set(SiteModel.SITE_TYPE, "hohenheim:node");
        site.set(SiteModel.SETTINGS, Map.of(
            "script", scriptPath,
            "use_ports", false,
            "wait_for_ready", false,
            "minimum_processes", 1));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);

        int siteId = site.get(SiteModel.ID);
        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, "exact");
        domainModel.save(domain);
    }
}
