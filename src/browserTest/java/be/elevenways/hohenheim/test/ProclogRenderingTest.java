package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.ProclogModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.process.ManagedProcess;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.process.PortAllocator;
import be.elevenways.hohenheim.server.process.ProcessMonitor;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin proclog viewer against a hostile child log: a stored process log is
 * the child's stdout verbatim and an anonymous visitor writes part of it (the
 * request path and User-Agent an access log records), so it must reach the
 * admin's browser as inert TEXT.
 */
class ProclogRenderingTest extends HohenheimTestBase {

    /** What an anonymous visitor puts in the request line of a proxied site. */
    private static final String HOSTILE_PATH = "/<script>alert(1)</script>";

    /** ...and in the User-Agent header the same access-log line records. */
    private static final String HOSTILE_AGENT = "<img src=x onerror=alert(2)>";

    private static ManagedProcessSiteHandler handler;

    @AfterAll
    static void reapChild() {
        if (handler != null) {
            handler.destroy();
            handler = null;
        }
    }

    /** A managed child that writes one access-log line carrying the visitor's payloads. */
    private static final class AccessLogHandler extends ManagedProcessSiteHandler {

        AccessLogHandler(int siteId, Map<String, Object> settings) {
            super(siteId, "proclog-xss-" + siteId, settings, new PortAllocator(), new ProcessMonitor());
        }

        @Override
        protected List<String> buildCommand(String listenTarget) {
            // The payloads travel through the environment, so no shell quoting can
            // neuter them: what the child prints is byte-for-byte what arrived.
            return List.of("sh", "-c",
                "printf '127.0.0.1 - - \"GET %s HTTP/1.1\" 200 12 \"-\" \"%s\"\\n' "
                    + "\"$REQUEST_PATH\" \"$REQUEST_AGENT\"; sleep 600");
        }

        @Override
        protected Map<String, String> buildRuntimeEnvironment(int port) {
            return Map.of();
        }

        @Override
        protected File getWorkingDirectory() {
            return new File(System.getProperty("java.io.tmpdir"));
        }
    }

    private static void awaitCondition(String what, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    private String adminGetBody(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("the proclog viewer renders").isEqualTo(200);
        return response.body();
    }

    /**
     * The served document minus the hydration payload. The DRY blob legitimately
     * carries the string verbatim: it is the raw-text content of a
     * {@code <script type="application/dry">}, where the HTML parser builds no
     * elements and the only way out is a literal {@code </script}, which DRY
     * escapes (asserted separately).
     */
    private static String withoutHydrationData(String body) {
        int start = body.indexOf("<script type=\"application/dry\"");
        assertThat(start).as("the hydration payload is present").isGreaterThan(-1);
        int end = body.indexOf("</script>", start);
        assertThat(end).as("the hydration payload is closed").isGreaterThan(start);
        return body.substring(0, start) + body.substring(end + "</script>".length());
    }

    /**
     * Walks one hostile access-log line from the child's stdout to the admin's
     * browser: stored verbatim, served escaped, and inert in a real page.
     */
    @Test
    void anonymousPayloadsInAChildLogReachTheAdminPageAsInertText() throws Exception {
        // 1. A managed site whose child logs a request an anonymous visitor shaped.
        var siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Proclog XSS Site");
        site.set(SiteModel.SLUG, "proclog-xss-site");
        site.set(SiteModel.SITE_TYPE, "hohenheim:command");
        site.set(SiteModel.SETTINGS, Map.of("command", "unused-in-this-test"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);
        int siteId = site.get(SiteModel.ID);

        Map<String, Object> settings = new HashMap<>();
        settings.put("wait_for_ready", false);
        settings.put("minimum_processes", 1);
        settings.put("environment_variables", Map.of(
            "REQUEST_PATH", HOSTILE_PATH,
            "REQUEST_AGENT", HOSTILE_AGENT));
        handler = new AccessLogHandler(siteId, settings);
        handler.startMinimumServers();

        awaitCondition("child logged the hostile request line", () -> {
            List<ManagedProcess> processes = handler.getProcesses();
            return !processes.isEmpty() && processes.get(0).getLogText().contains(HOSTILE_AGENT);
        });

        // 2. The stored log keeps the payloads VERBATIM -- the defence is the render,
        //    not a sanitizer that would also mangle honest log output.
        handler.flushProclogsNow();
        List<Row> logs = Models.get(ProclogModel.class).find()
            .where(ProclogModel.SITE_ID.eq(siteId)).all();
        assertThat(logs).as("the flush persisted the child's captured stdout").hasSize(1);
        String stored = logs.get(0).get(ProclogModel.LOG_HTML);
        assertThat(stored).as("stored stdout is untouched")
            .contains(HOSTILE_PATH)
            .contains(HOSTILE_AGENT);
        Integer logId = logs.get(0).get(ProclogModel.ID);

        // 3. The served HTML escapes both payloads, so the no-JavaScript path is safe too.
        String viewerPath = "/admin/sites/" + siteId + "/page/processes?log=" + logId;
        String body = adminGetBody(viewerPath);
        assertThat(body).as("both payloads render as escaped text")
            .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
            .contains("&lt;img src=x onerror=alert(2)&gt;");
        assertThat(body).as("the hydration payload cannot break out of its script element")
            .contains("<\\/script>");
        assertThat(withoutHydrationData(body)).as("neither payload becomes live markup")
            .doesNotContain("<script>alert(1)")
            .doesNotContain("<img src=x onerror=");

        // 4. Without the ?log= selection the stored-log card is absent entirely.
        assertThat(withoutHydrationData(adminGetBody(
                "/admin/sites/" + siteId + "/page/processes")))
            .as("no log selected, no log content")
            .doesNotContain("&lt;script&gt;alert(1)&lt;/script&gt;");

        // 5. In a real browser the log is a single text node: no element is built from
        //    it anywhere in the document, and nothing in it executes.
        List<String> dialogs = new ArrayList<>();
        page.onDialog(dialog -> {
            dialogs.add(dialog.message());
            dialog.dismiss();
        });
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
        assertThat(getCollectedErrors()).as("no page errors").isEmpty();
    }
}
