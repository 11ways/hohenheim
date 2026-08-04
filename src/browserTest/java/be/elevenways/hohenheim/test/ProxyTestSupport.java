package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Shared plumbing for proxy dispatch tests: runtime boot, fresh DB per test, site+domain
 * fixtures, and raw-socket HTTP exchanges against a port-0 {@link ProxyServer}.
 */
public final class ProxyTestSupport {

    private ProxyTestSupport() {
    }

    /** One-time runtime boot for a test class; pair with a per-class initialized guard. */
    public static void bootRuntime() throws Exception {
        SiteTypes.boot();
        HohenheimEndpoints.init();
        // resetDatabase() already points the runtime at a fresh database AND initializes
        // it; the second HohenheimDatabase.init() this used to make was pure duplication.
        resetDatabase();
        HohenheimTestRuntime.ensureBooted();
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");
    }

    /** Point the runtime at a fresh temp SQLite file and re-init. */
    public static void resetDatabase() throws Exception {
        TestDatabases.freshDatabase();
    }

    /** Persist an enabled site of the given type with one domain. */
    public static void setupSiteWithDomain(String siteType, String hostname, String matchType,
                                    Map<String, Object> settings) {
        Row site = setupSite(siteType, "Test Site " + hostname,
            hostname.replaceAll("[^a-z0-9]+", "-"), settings);
        addDomain(site, hostname, matchType, null, false);
    }

    /** Persist an enabled site without domains; add them via {@link #addDomain}. */
    public static Row setupSite(String siteType, String siteName, String slug,
                         Map<String, Object> settings) {
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, siteName);
        site.set(SiteModel.SLUG, slug);
        site.set(SiteModel.SITE_TYPE, siteType);
        site.set(SiteModel.SETTINGS, settings);
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);
        return site;
    }

    /** Attach a domain to a site, optionally with a path prefix. */
    public static void addDomain(Row site, String hostname, String matchType,
                          String path, boolean stripPath) {
        var domainModel = Models.get(SiteDomainModel.class);
        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, matchType);
        domain.set(SiteDomainModel.FORCE_SSL, false);
        if (path != null) {
            domain.set(SiteDomainModel.PATH, path);
            domain.set(SiteDomainModel.STRIP_PATH, stripPath);
        }
        domainModel.save(domain);
    }

    /** Start a ProxyServer on an ephemeral HTTP port and return it. */
    public static ProxyServer startProxy() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        ProxyServer proxy = new ProxyServer();
        proxy.start();
        return proxy;
    }

    public static int httpPort(ProxyServer proxy) {
        return ((InetSocketAddress) proxy.getHttpListenerInfo().getAddress()).getPort();
    }

    /**
     * Raw HTTP/1.1 exchange; returns the full response (headers + body).
     * @param extraHeaderLines additional request header lines, without CRLF
     */
    public static String rawRequest(int port, String host, String path, String... extraHeaderLines)
            throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);

            StringBuilder request = new StringBuilder()
                .append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n");
            for (String line : extraHeaderLines) {
                request.append(line).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            StringBuilder response = new StringBuilder();
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }
            } catch (java.net.SocketTimeoutException e) {
                // A killed exchange may leave the connection open without EOF;
                // whatever was read so far is the observable response.
            }
            return response.toString();
        }
    }
}
