package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ProclogModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.sitetype.SiteHandlers;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.cms.common.CmsSettings;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.ContentSecurityPolicies;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0 / 0.1 stored-XSS gate, honest ingress: the payload is planted by an
 * ANONYMOUS request that traverses hohenheim's real tenant proxy listener (not
 * injected via child environment variables as ProclogRenderingTest does), lands
 * in the stored process log via the managed child's stdout, and must reach the
 * admin's browser as inert escaped text under the scoped admin CSP.
 *
 * Also pins the hohenheim-side CSP/bootstrap invariants: hohenheim's own panels
 * (/admin, /manage) carry the strict admin CSP, an unclaimed public path does not,
 * and the admin shell bootstraps exactly once with no inline onload handler.
 */
class ProclogProxyIngressTest extends HohenheimTestBase {

    /** Raw <script> in the request PATH (sent percent-encoded, decoded for the access log). */
    private static final String HOSTILE_PATH = "/<script>alert(1)</script>";
    private static final String HOSTILE_PATH_ENCODED = "/%3Cscript%3Ealert(1)%3C/script%3E";

    /** Raw <img onerror> in the User-Agent header (header values carry it verbatim). */
    private static final String HOSTILE_AGENT = "<img src=x onerror=alert(2)>";

    private static final String PROXY_HOST = "proclog-proxy.test";

    /** A tiny node HTTP upstream that access-logs the decoded path + User-Agent to stdout. */
    private static Path writeUpstreamScript() throws Exception {
        String js =
            "const http = require('http');\n"
            + "const portArg = (process.argv.find(a => a.startsWith('--port=')) || '').split('=')[1];\n"
            + "const port = parseInt(portArg, 10);\n"
            + "http.createServer((req, res) => {\n"
            + "  const ua = req.headers['user-agent'] || '';\n"
            + "  let path = req.url;\n"
            + "  try { path = decodeURIComponent(req.url); } catch (e) {}\n"
            + "  process.stdout.write('127.0.0.1 - - \"GET ' + path + ' HTTP/1.1\" 200 2 \"-\" \"' + ua + '\"\\n');\n"
            + "  res.writeHead(200, {'Content-Type': 'text/plain'});\n"
            + "  res.end('ok');\n"
            + "}).listen(port, '127.0.0.1');\n";
        Path script = Files.createTempFile("hh-proclog-upstream", ".js");
        Files.writeString(script, js);
        script.toFile().deleteOnExit();
        return script;
    }

    private static boolean nodeAvailable() {
        return new File("/usr/bin/node").canExecute() || new File("/usr/local/bin/node").canExecute();
    }

    /** Persist an enabled command site whose managed child is the logging node upstream. */
    private static int setupUpstreamSite(String scriptPath) {
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Proclog Proxy Site");
        site.set(SiteModel.SLUG, "proclog-proxy-site");
        site.set(SiteModel.SITE_TYPE, "hohenheim:command");
        site.set(SiteModel.SETTINGS, Map.of(
            "start_command", "node " + scriptPath,
            "port_argument", "--port",
            "wait_for_ready", false,
            "minimum_processes", 1));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);
        int siteId = site.get(SiteModel.ID);

        var domainModel = Models.get(SiteDomainModel.class);
        Row domain = domainModel.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, siteId);
        domain.set(SiteDomainModel.HOSTNAME, PROXY_HOST);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domainModel.save(domain);
        return siteId;
    }

    /** One anonymous HTTP/1.1 exchange over the proxy's public listener; returns the response. */
    private static String proxiedRequest(int port) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            String request = "GET " + HOSTILE_PATH_ENCODED + " HTTP/1.1\r\n"
                + "Host: " + PROXY_HOST + "\r\n"
                + "User-Agent: " + HOSTILE_AGENT + "\r\n"
                + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String adminGetBody(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private HttpResponse<String> adminGet(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** The served document minus the hydration DRY payload (which legitimately carries the string). */
    private static String withoutHydrationData(String body) {
        int start = body.indexOf("<script type=\"application/dry\"");
        if (start < 0) return body;
        int end = body.indexOf("</script>", start);
        if (end < 0) return body;
        return body.substring(0, start) + body.substring(end + "</script>".length());
    }

    @Test
    void anonymousProxyRequestPlantsXssThatRendersInertInTheAdminViewer() throws Exception {
        LiveLane.require(LiveLane.Need.NODE, nodeAvailable(),
            "node is required for the upstream child");

        Path script = writeUpstreamScript();
        int siteId = setupUpstreamSite(script.toString());

        ProxyServer previousProxy = ServerMain.getProxyServer();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        ProxyServer proxy = new ProxyServer();
        proxy.start();
        ServerMain.adoptProxyServer(proxy);
        int proxyPort = ((InetSocketAddress) proxy.getHttpListenerInfo().getAddress()).getPort();
        boolean proxyStopped = false;

        try {
            // 1. An anonymous visitor drives the real proxy listener; retry while the child
            //    spawns and binds. The child logs the request it received to its stdout.
            String response = null;
            for (int attempt = 0; attempt < 40 && (response == null || !response.contains("ok")); attempt++) {
                try {
                    response = proxiedRequest(proxyPort);
                } catch (Exception ignored) {
                }
                if (response == null || !response.contains("ok")) {
                    Thread.sleep(300);
                }
            }
            assertThat(response).as("the anonymous request reached the managed upstream through the proxy")
                .isNotNull().contains("200").contains("ok");

            // 2. The child's stdout (an access-log line) is flushed to the stored proclog,
            //    keeping the visitor's payloads VERBATIM -- the defence is the render, not a
            //    sanitizer that would also mangle honest log output.
            ManagedProcessSiteHandler handler = SiteHandlers.managedProcess(siteId);
            assertThat(handler).as("the proxy owns a managed-process handler for the site").isNotNull();
            handler.flushProclogsNow();

            Row storedLog = null;
            for (int attempt = 0; attempt < 50 && storedLog == null; attempt++) {
                for (Row row : Models.get(ProclogModel.class).find()
                        .where(ProclogModel.SITE_ID.eq(siteId)).all()) {
                    String logText = row.get(ProclogModel.LOG_HTML);
                    if (logText != null && logText.contains(HOSTILE_AGENT)) {
                        storedLog = row;
                        break;
                    }
                }
                if (storedLog == null) {
                    Thread.sleep(100);
                    handler.flushProclogsNow();
                }
            }
            assertThat(storedLog).as("the child's captured stdout was persisted with the payloads").isNotNull();
            String stored = storedLog.get(ProclogModel.LOG_HTML);
            assertThat(stored).as("stored stdout is untouched")
                .contains(HOSTILE_PATH)
                .contains(HOSTILE_AGENT);
            int logId = storedLog.get(ProclogModel.ID);

            // 3. The served HTML escapes both payloads (the JavaScript-free path is safe too),
            //    and the strict admin CSP is stamped on the viewer.
            String viewerPath = "/admin/sites/" + siteId + "/page/processes?log=" + logId;
            HttpResponse<String> viewer = adminGet(viewerPath);
            assertThat(viewer.statusCode()).as("the proclog viewer renders").isEqualTo(200);
            String body = viewer.body();
            assertThat(body).as("both proxy-planted payloads render as escaped text")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .contains("&lt;img src=x onerror=alert(2)&gt;");
            assertThat(withoutHydrationData(body)).as("neither payload becomes live markup")
                .doesNotContain("<script>alert(1)")
                .doesNotContain("<img src=x onerror=");
            // The proclog viewer is not a page of its own: ?log= is a view of the PROCESSES
            // subpage, which is the one route that boots ghostty, so it is served the
            // terminal CSP variant rather than the shared admin default. That variant
            // widens exactly two directives (wasm compilation, data: fetches) and neither
            // touches what this gate rests on -- script-src still forbids inline script, so
            // a payload that did become markup still could not run.
            String viewerCsp = viewer.headers().firstValue("Content-Security-Policy").orElse(null);
            assertThat(viewerCsp)
                .as("the viewer carries the terminal CSP variant of its own route")
                .isEqualTo(ContentSecurityPolicies.STRICT_ADMIN_TERMINAL);
            assertThat(viewerCsp)
                .as("the variant permits wasm and nothing else on script-src")
                .contains("script-src 'self' 'wasm-unsafe-eval';");

            // Stop the proxy (and its live child) before the browser step: the live-process
            // ghostty terminal is incidental to the stored-log XSS gate. With no live
            // handler the viewer shows only the stored log, exactly as ProclogRenderingTest
            // does.
            proxy.stop();
            proxyStopped = true;
            ServerMain.adoptProxyServer(previousProxy);

            // 4. In a real browser under the CSP the log is a single text node: nothing built
            //    from it, nothing executed, and no CSP violation tripped.
            List<String> dialogs = new ArrayList<>();
            page.onDialog(dialog -> {
                dialogs.add(dialog.message());
                dialog.dismiss();
            });
            page.addInitScript("window.__cspViolations = [];"
                + "document.addEventListener('securitypolicyviolation', function (e) {"
                + "  window.__cspViolations.push(e.violatedDirective + '|' + e.blockedURI); });");
            navigateToApp(viewerPath);
            waitForHydration();

            assertThat(page.locator(".hh-proclog-content").count())
                .as("the stored-log card rendered").isEqualTo(1);
            assertThat(page.locator(".hh-proclog-content *").count())
                .as("the log content holds no elements at all, only text").isZero();
            assertThat(page.locator(".hh-proclog-content").innerText())
                .as("the payloads are readable as text")
                .contains(HOSTILE_PATH)
                .contains(HOSTILE_AGENT);
            assertThat(page.locator("img[src='x']").count())
                .as("the User-Agent payload never became an <img>").isZero();
            assertThat(dialogs).as("nothing in the log executed").isEmpty();
            Object violations = page.evaluate("() => (window.__cspViolations || []).length");
            assertThat(((Number) violations).intValue())
                .as("the escaped log tripped no CSP violation").isZero();
            assertThat(getCollectedErrors()).as("no page errors").isEmpty();
        } finally {
            if (!proxyStopped) {
                proxy.stop();
                ServerMain.adoptProxyServer(previousProxy);
            }
            var siteModel = Models.get(SiteModel.class);
            var domainModel = Models.get(SiteDomainModel.class);
            for (Row d : domainModel.findBySiteId(siteId)) domainModel.delete(d);
            Row site = siteModel.findById(siteId);
            if (site != null) siteModel.delete(site);
        }
    }

    /**
     * Hohenheim-side CSP + bootstrap pins. The generic mechanism (CSP liveness, injected
     * script/img blocked) is pinned by zenit-cms CspPanelBrowserTest and the auth families
     * by zenit-auth AuthFlowIntegrationTest; this pins that HOHENHEIM's own panels claim the
     * policy and that the shell bootstraps exactly once.
     */
    @Test
    void hohenheimPanelsCarryTheAdminCspAndBootstrapExactlyOnce() throws Exception {
        String expected = CmsSettings.VALUES.getValue(CmsSettings.CSP);
        assertThat(expected).as("the admin CSP default forbids inline script")
            .contains("script-src 'self'");

        // 1. Hohenheim's own panels (/admin, /manage) carry the scoped admin CSP.
        for (String path : List.of("/admin", "/admin/sites", "/manage")) {
            assertThat(adminGet(path).headers().firstValue("Content-Security-Policy").orElse(null))
                .as("panel route " + path + " carries the admin CSP").isEqualTo(expected);
        }

        // 2. An unclaimed public path gets NO CMS CSP (the global default stays opt-in).
        assertThat(adminGet("/definitely-not-a-hohenheim-panel")
                .headers().firstValue("Content-Security-Policy"))
            .as("an unclaimed path carries no admin CSP").isEmpty();

        // 3. The admin shell bootstraps exactly once: one bundle boot script, no inline
        //    onload="main()" (the pre-fix shape that dies silently under the strict CSP).
        String adminHtml = adminGetBody("/admin/sites");
        assertThat(countOccurrences(adminHtml, "/_hawkeye/boot.js"))
            .as("exactly one CSP-safe bootstrap script").isEqualTo(1);
        assertThat(adminHtml).as("no inline onload bootstrap handler survives")
            .doesNotContain("onload=");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
