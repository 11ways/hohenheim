package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.proxy.ProxyServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static be.elevenways.hohenheim.test.ProxyTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static site type through the real dispatcher: the autoindex-on default and the
 * empty-200 unconfigured-root response (both Node-ecstatic parity).
 */
class StaticSiteTypeTest {

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
    void unconfiguredRootRespondsEmpty200() throws Exception {
        resetDatabase();
        setupSiteWithDomain("hohenheim:static", "emptystatic.test", "exact", Map.of());

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "emptystatic.test", "/");

        assertThat(response).contains("200");
        assertThat(response).doesNotContain("No root path configured");
        assertThat(response).doesNotContain("502");
    }

    @Test
    void autoindexIsOnByDefault() throws Exception {
        resetDatabase();

        Path root = Files.createTempDirectory("hh-static-test");
        Files.writeString(root.resolve("hello.txt"), "hi");

        // No autoindex key at all: the default must show the listing.
        setupSiteWithDomain("hohenheim:static", "listing.test", "exact", Map.of(
            "root_path", root.toString()));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "listing.test", "/");

        assertThat(response).contains("200");
        assertThat(response).contains("hello.txt");
    }

    @Test
    void autoindexCanStillBeDisabled() throws Exception {
        resetDatabase();

        Path root = Files.createTempDirectory("hh-static-test");
        Files.writeString(root.resolve("hello.txt"), "hi");

        setupSiteWithDomain("hohenheim:static", "nolisting.test", "exact", Map.of(
            "root_path", root.toString(),
            "autoindex", false));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "nolisting.test", "/");

        assertThat(response).doesNotContain("hello.txt");
    }

    @Test
    void indexFileStillWinsOverTheListing() throws Exception {
        resetDatabase();

        Path root = Files.createTempDirectory("hh-static-test");
        Files.writeString(root.resolve("index.html"), "<h1>welcome</h1>");
        Files.writeString(root.resolve("secret.txt"), "x");

        setupSiteWithDomain("hohenheim:static", "indexed.test", "exact", Map.of(
            "root_path", root.toString()));

        proxy = startProxy();
        String response = rawRequest(httpPort(proxy), "indexed.test", "/");

        assertThat(response).contains("welcome");
        assertThat(response).doesNotContain("secret.txt");
    }
}
