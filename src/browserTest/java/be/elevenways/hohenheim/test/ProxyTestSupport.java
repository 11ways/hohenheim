package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.LinkedHashMap;
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
        return setupSite(siteType, siteName, slug, settings, null);
    }

    /**
     * Persist an enabled site whose one upstream IS this instance.
     *
     * The id rides the FIRST write on purpose: SiteModel refuses an instance upstream
     * with no instance to serve, so a fixture that saved the kind and then stamped the
     * id was writing a row the product has never accepted.
     */
    public static Row setupInstanceSite(String siteName, String slug, int instanceId) {
        return setupSite(InstanceUpstreamKind.ID.toString(), siteName, slug,
            new LinkedHashMap<>(), instanceId);
    }

    private static Row setupSite(String siteType, String siteName, String slug,
                                 Map<String, Object> settings, Integer instanceId) {
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, siteName);
        site.set(SiteModel.SLUG, slug);
        site.set(SiteModel.UPSTREAM_KIND, siteType);
        site.set(SiteModel.SETTINGS, settings);
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        if (instanceId != null) {
            site.set(SiteModel.INSTANCE_ID, instanceId);
        }
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
                readFramedResponse(reader, response);
            } catch (java.net.SocketTimeoutException e) {
                // A killed exchange may leave the connection open without EOF;
                // whatever was read so far is the observable response.
            }
            return response.toString();
        }
    }

    /**
     * Read one HTTP/1.1 response using its OWN framing, falling back to read-to-EOF.
     *
     * AIDEV-NOTE: this used to read to EOF unconditionally, relying on the appended
     * "Connection: close". A caller that sends its own Connection header (three do -- the
     * hop-by-hop test and the two websocket-upgrade tests) overrides it, so the server never
     * closed and every one of those calls ate the full 5s soTimeout, silently swallowed by
     * the catch above: 16.05s of SiteDispatcherTest's 17.42s body. Framing the read is also
     * STRICTLY STRONGER than waiting -- a genuine upstream hang used to be indistinguishable
     * from success, because "as much as arrived in 5s" and "the complete response" returned
     * the same string.
     */
    private static void readFramedResponse(BufferedReader reader, StringBuilder response)
            throws IOException {
        int contentLength = -1;
        boolean chunked = false;
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line).append("\n");
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.equals("content-length")) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                    contentLength = -1;
                }
            } else if (name.equals("transfer-encoding")) {
                chunked = value.toLowerCase(Locale.ROOT).contains("chunked");
            }
        }

        if (line == null) {
            return;
        }
        if (chunked) {
            // Terminating chunk is a "0" size line...
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
                if (line.trim().equals("0")) {
                    break;
                }
            }
            // ...and the TRAILER section follows it, ended by a blank line. Stopping at the
            // "0" would drop exactly the grpc-status/grpc-message headers the trailer
            // forwarding tests exist to assert.
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
                if (line.isEmpty()) {
                    break;
                }
            }
            return;
        }
        if (contentLength >= 0) {
            int remaining = contentLength;
            char[] buffer = new char[Math.min(Math.max(contentLength, 1), 8192)];
            while (remaining > 0) {
                int read = reader.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                response.append(buffer, 0, read);
                // Content-Length counts BYTES; the reader hands out chars. Decrement by the
                // encoded width so a multi-byte body still terminates on the declared length.
                remaining -= new String(buffer, 0, read).getBytes(StandardCharsets.UTF_8).length;
            }
            return;
        }
        // No self-framing: the appended Connection: close is the only terminator left.
        while ((line = reader.readLine()) != null) {
            response.append(line).append("\n");
        }
    }
}
