package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: a ProxySite in socket mode forwards through the loopback bridge to an AF_UNIX HTTP
 * upstream. Proves the unix-socket transport is wired through the real dispatcher + Undertow proxy.
 */
class ProxySocketDispatchTest {

    private static ProxyServer proxy;

    @BeforeAll
    static void boot() throws Exception {
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
    @Timeout(20)
    void socketModeProxiesToAfUnixUpstream() throws Exception {
        Path sock = Files.createTempFile("hh-proxy-sock", ".sock");
        Files.delete(sock);
        sock.toFile().deleteOnExit();

        String body = "afunix-proxy-ok";
        ServerSocketChannel unixServer = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        unixServer.bind(UnixDomainSocketAddress.of(sock.toString()));
        AtomicBoolean running = new AtomicBoolean(true);
        Thread.ofVirtual().start(() -> {
            while (running.get()) {
                try {
                    SocketChannel conn = unixServer.accept();
                    Thread.ofVirtual().start(() -> serveOnce(conn, body));
                } catch (IOException e) {
                    return;   // server closed
                }
            }
        });

        setupSocketSite("socket.test", sock.toString());

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        proxy = new ProxyServer();
        proxy.start();
        int httpPort = ((InetSocketAddress) proxy.getHttpListenerInfo().getAddress()).getPort();

        try (Socket client = new Socket("127.0.0.1", httpPort)) {
            client.setSoTimeout(5000);
            OutputStream out = client.getOutputStream();
            out.write("GET / HTTP/1.1\r\nHost: socket.test\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8));
            out.flush();

            String response = new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(response).contains("200").contains(body);
        } finally {
            running.set(false);
            unixServer.close();
        }
    }

    private static void serveOnce(SocketChannel conn, String body) {
        try (conn) {
            conn.read(ByteBuffer.allocate(8192));   // consume the request (small GET)
            String resp = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length()
                + "\r\nConnection: close\r\n\r\n" + body;
            conn.write(ByteBuffer.wrap(resp.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException ignored) {
        }
    }

    private static void setupSocketSite(String hostname, String socketPath) {
        var siteModel = Models.get(SiteModel.class);
        var domainModel = Models.get(SiteDomainModel.class);

        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Socket Site");
        site.set(SiteModel.SLUG, "socket-site");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:address");
        site.set(SiteModel.SETTINGS, Map.of("socket", socketPath));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);

        int siteId = site.get(SiteModel.ID);
        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, "exact");
        domain.set(SiteDomainModel.FORCE_SSL, false);
        domainModel.save(domain);
    }
}
