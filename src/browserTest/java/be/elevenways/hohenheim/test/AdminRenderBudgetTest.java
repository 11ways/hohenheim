package be.elevenways.hohenheim.test;

import be.elevenways.zenit.auth.server.AuthCookieSupport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a single admin page costs the SERVER, with no browser in the picture.
 *
 * AIDEV-NOTE: this exists because the browser suite could not answer "is the page slow, or is
 * the test slow?". A Playwright navigation bundles server render, transfer, parse and hydrate
 * into one number, and running a class alone on an idle box flatters it -- the same class
 * measured 17.6s filtered and 39.7s in the full suite, and a method nobody had touched moved
 * 4.0s -> 2.2s between the two. Timing a plain HTTP GET separates the server's own cost out,
 * and THAT is the number a real user waits for too.
 */
class AdminRenderBudgetTest extends HohenheimTestBase {

    /** Pages every operator hits, cheapest first. */
    private static final List<String> PAGES = List.of(
        "/admin/dashboard",
        "/admin/settings",
        "/admin/sites",
        "/admin/certificates",
        "/admin/activity");

    private static final int WARMUP = 2;
    private static final int SAMPLES = 5;

    @Test
    void everyAdminPageRendersWithinItsServerBudget() throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        Map<String, Long> median = new LinkedHashMap<>();

        for (String path : PAGES) {
            for (int i = 0; i < WARMUP; i++) {
                fetch(client, path);
            }
            List<Long> samples = new ArrayList<>();
            for (int i = 0; i < SAMPLES; i++) {
                samples.add(fetch(client, path));
            }
            samples.sort(Long::compare);
            median.put(path, samples.get(samples.size() / 2));
        }

        long bundleBytes = fetchSize(client, "/cms.js");

        StringBuilder report = new StringBuilder("\nADMIN RENDER BUDGET (median of "
            + SAMPLES + " after " + WARMUP + " warmups, no browser)\n"
            + "  client bundle /cms.js = " + bundleBytes + " bytes\n");
        median.forEach((path, ms) -> report.append(String.format("  %6d ms  %s%n", ms, path)));
        System.out.println(report);

        assertThat(median)
            .as("no admin page may take seconds to render server-side: %s", report)
            .allSatisfy((path, ms) -> assertThat(ms).isLessThan(1500L));

        // AIDEV-NOTE: the bundle bound is the guard that actually earns its keep. hohenheim
        // shipped 23,986,504 bytes here until 2026-08-12 -- six times orcono/QQ/thoth -- because
        // its teavm block never got the obfuscated/BALANCED settings every sibling app has.
        // Measured client cost was 311-1166ms per navigation against 35-229ms of server render,
        // so the browser suite spent most of its life parsing JavaScript. 8MB is generous
        // headroom over the 4.3MB the settings buy; if this trips, check those two flags before
        // raising it.
        assertThat(bundleBytes)
            .as("the admin client bundle must stay optimized (teavm obfuscated + BALANCED)")
            .isLessThan(8L * 1024 * 1024);
    }

    private long fetchSize(HttpClient client, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray()).body().length;
    }

    private long fetch(HttpClient client, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .GET().build();
        long start = System.nanoTime();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        assertThat(response.statusCode()).as("%s must render", path).isEqualTo(200);
        return elapsed;
    }
}
