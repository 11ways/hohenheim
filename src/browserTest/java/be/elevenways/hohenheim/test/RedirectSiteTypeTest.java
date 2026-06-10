package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.proxy.ProxyServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static be.elevenways.hohenheim.test.ProxyTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redirect site type through the real dispatcher: status, Location, path
 * preservation, and the per-request delay.
 */
class RedirectSiteTypeTest {

    private static boolean initialized = false;
    private ProxyServer proxy;

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) return;
        initialized = true;
        bootRuntime();
    }

    @AfterEach
    void stopProxy() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
    }

    @Test
    void redirectsWithConfiguredStatusAndTarget() throws Exception {
        resetDatabase();
        setupSiteWithDomain("hohenheim:redirect", "redir.test", "exact", Map.of(
            "target_url", "https://target.example/landing",
            "http_status", "301"));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "redir.test", "/anything");

        assertThat(response).contains("301");
        assertThat(response).contains("Location: https://target.example/landing");
    }

    @Test
    void preservePathAppendsPathAndQuery() throws Exception {
        resetDatabase();
        setupSiteWithDomain("hohenheim:redirect", "redirpath.test", "exact", Map.of(
            "target_url", "https://target.example",
            "preserve_path", true));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "redirpath.test", "/sub/page?x=1");

        assertThat(response).contains("Location: https://target.example/sub/page?x=1");
    }

    @Test
    void delaySettingPostponesTheRedirect() throws Exception {
        resetDatabase();
        setupSiteWithDomain("hohenheim:redirect", "slowredir.test", "exact", Map.of(
            "target_url", "https://target.example/",
            "delay", 500));

        proxy = startProxy();

        long start = System.nanoTime();
        String response = rawRequest(httpPort(proxy), "slowredir.test", "/");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(response).contains("302");
        assertThat(response).contains("Location: https://target.example/");
        assertThat(elapsedMs).isGreaterThanOrEqualTo(400);
    }

    @Test
    void invalidTargetSchemeIsRejected() throws Exception {
        resetDatabase();
        setupSiteWithDomain("hohenheim:redirect", "badredir.test", "exact", Map.of(
            "target_url", "javascript:alert(1)"));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "badredir.test", "/");

        assertThat(response).contains("502");
        assertThat(response).doesNotContain("Location: javascript");
    }
}
