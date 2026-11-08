package be.elevenways.hohenheim.server.build;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.ServedApp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A static site answers an address naming no file with its own not-found page and a 404 status: a folder of files
 * through the proxy, and a static site built from git through the server the generated Dockerfile configures.
 */
class StaticNotFoundPageTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static ProxyServer proxy;

    @AfterAll
    static void stop() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
    }

    @Test
    @Timeout(60)
    void aFolderSiteAnswersAMissingAddressWithItsNotFoundPage() throws Exception {
        HohenheimEndpoints.init();
        Path parent = Files.createTempDirectory("hh-static-404");
        Path root = Files.createDirectory(parent.resolve("site"));
        Files.writeString(root.resolve("index.html"), "home-page");
        Map<String, Object> settings = new LinkedHashMap<>(Map.of("root_path", root.toString(), "autoindex", false));
        Row site = ProxyTestSupport.setupSite("hohenheim:static", "Static 404", "static-404", settings);
        ProxyTestSupport.addDomain(site, "static.nf.test", "exact", null, false);
        proxy = ProxyTestSupport.startProxy();
        int port = ProxyTestSupport.httpPort(proxy);

        // 1. Without a page of its own a missing address is the plain 404.
        assertThat(ProxyTestSupport.rawRequest(port, "static.nf.test", "/"))
            .as("step 1: the home page serves").startsWith("HTTP/1.1 200").contains("home-page");
        assertThat(ProxyTestSupport.rawRequest(port, "static.nf.test", "/missing"))
            .as("step 1: a missing address is the plain 404").startsWith("HTTP/1.1 404").contains("Not Found");

        // 2. A 404.html in the folder is the site's page, answered with the 404 status.
        Files.writeString(root.resolve("404.html"), "our-own-404");
        assertThat(ProxyTestSupport.rawRequest(port, "static.nf.test", "/missing"))
            .as("step 2: the folder's 404.html, still a 404").startsWith("HTTP/1.1 404").contains("our-own-404");

        // 3. A named page wins, and one outside the folder is never served.
        Files.writeString(root.resolve("lost.html"), "lost-page");
        settings.put("not_found_page", "lost.html");
        site.set(SiteModel.SETTINGS, settings);
        Models.get(SiteModel.class).save(site);
        proxy.getDispatcher().reloadRoutes();
        assertThat(ProxyTestSupport.rawRequest(port, "static.nf.test", "/missing"))
            .as("step 3: the named page").startsWith("HTTP/1.1 404").contains("lost-page");
        settings.put("not_found_page", "../outside.html");
        Files.writeString(parent.resolve("outside.html"), "outside-secret");
        site.set(SiteModel.SETTINGS, settings);
        Models.get(SiteModel.class).save(site);
        proxy.getDispatcher().reloadRoutes();
        assertThat(ProxyTestSupport.rawRequest(port, "static.nf.test", "/missing"))
            .as("step 3: a page outside the folder is never served").startsWith("HTTP/1.1 404")
            .doesNotContain("outside-secret");

        // 4. A hidden page is served only by a site that shows hidden files.
        Files.writeString(root.resolve(".secret-404.html"), "hidden-page");
        settings.put("not_found_page", ".secret-404.html");
        site.set(SiteModel.SETTINGS, settings);
        Models.get(SiteModel.class).save(site);
        proxy.getDispatcher().reloadRoutes();
        assertThat(ProxyTestSupport.rawRequest(port, "static.nf.test", "/missing"))
            .as("step 4: a hidden page stays hidden").startsWith("HTTP/1.1 404").doesNotContain("hidden-page");
    }

    @Test
    void aGitStaticSitePointsItsServerAtItsOwnNotFoundPage() throws IOException {
        // 4. The server's own default is a 404.html beside its binary; the Dockerfile points it into the output.
        assertThat(StaticSiteBuilder.dockerfile(new BuildRequest.StaticSite("", "", "", "dist", ""), "dist"))
            .as("step 4: the site's 404.html by default").contains("SERVER_ERROR_PAGE_404=/public/404.html");
        assertThat(StaticSiteBuilder.dockerfile(new BuildRequest.StaticSite("", "", "", "dist", "errors/nf.html"),
                "dist"))
            .as("step 4: a named page").contains("SERVER_ERROR_PAGE_404=/public/errors/nf.html");
        assertThatThrownBy(() -> StaticSiteBuilder.dockerfile(
                new BuildRequest.StaticSite("", "", "", "dist", "../etc/passwd"), "dist"))
            .as("step 4: a page outside the output is refused").isInstanceOf(IOException.class);
    }
}
