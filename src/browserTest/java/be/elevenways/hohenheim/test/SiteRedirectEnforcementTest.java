package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SiteRedirectModel;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.test.support.ServedApp;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Drives a site's own redirects through the proxy with real requests: an exact path and a prefix answer their
 * redirect before the upstream is reached, the rest of the site still serves, and the model refuses the rows that
 * would leave the site through a protocol-relative path, loop, or claim a path twice.
 */
class SiteRedirectEnforcementTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static final String HOST = "redirects.sr.test";

    private static ProxyServer proxy;
    private static HttpServer upstream;
    private static int port;
    private static final AtomicInteger upstreamHits = new AtomicInteger();

    @BeforeAll
    static void boot() throws Exception {
        HohenheimEndpoints.init();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            upstreamHits.incrementAndGet();
            byte[] body = "site-content".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        upstream.start();
    }

    @AfterAll
    static void stop() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        if (upstream != null) {
            upstream.stop(0);
            upstream = null;
        }
    }

    @Test
    @Timeout(60)
    void aSiteAnswersItsRedirectsBeforeItsUpstream() throws Exception {
        Row site = ProxyTestSupport.setupSite("hohenheim:address", "Redirect Site", "redirect-site",
            Map.of("forward_host", "127.0.0.1", "forward_port", upstream.getAddress().getPort()));
        ProxyTestSupport.addDomain(site, HOST, "exact", null, false);
        int siteId = site.get(SiteModel.ID);
        proxy = ProxyTestSupport.startProxy();
        port = ProxyTestSupport.httpPort(proxy);

        // 1. Without redirects everything serves.
        assertThat(request("/old")).as("step 1: the old page serves").contains("200").contains("site-content");

        // 2. An exact redirect answers 301 to its target, keeping the query, and only for that path.
        Row old = redirect(siteId, "/old/", SiteRedirectModel.MATCH_EXACT, "/new", null);
        assertThat((String) old.get(SiteRedirectModel.FROM_PATH)).as("step 2: stored without its trailing slash")
            .isEqualTo("/old");
        reload();
        int hits = upstreamHits.get();
        assertThat(request("/old")).as("step 2: the exact path redirects permanently")
            .contains("301").contains("Location: /new");
        assertThat(request("/old?x=1")).as("step 2: with its query").contains("Location: /new?x=1");
        assertThat(upstreamHits.get()).as("step 2: a redirect never reaches the upstream").isEqualTo(hits);
        assertThat(request("/old/sub")).as("step 2: an exact redirect leaves what is under it alone")
            .contains("200").contains("site-content");
        assertThat(request("/old/")).as("step 2: a trailing slash asks for the same path")
            .contains("301").contains("Location: /new");

        // 3. A prefix redirect carries the rest of the raw path to an outside address, encoded as it came.
        redirect(siteId, "/blog", SiteRedirectModel.MATCH_PREFIX, "https://news.example/posts", "302");
        // 3b. A same-site prefix target never yields a location a browser reads as another host.
        redirect(siteId, "/go", SiteRedirectModel.MATCH_PREFIX, "/", null);
        reload();
        assertThat(request("/blog/a%20b")).as("step 3: the rest of the path follows, still encoded")
            .contains("302").contains("Location: https://news.example/posts/a%20b");
        assertThat(request("/blog")).as("step 3: the prefix itself redirects to the target")
            .containsPattern("Location: https://news.example/posts\\r?\\n");
        assertThat(request("/blogger")).as("step 3: a prefix keeps segment boundaries")
            .contains("200").contains("site-content");
        assertThat(request("/go//evil.example")).as("step 3: //host after a / target folds back onto the site")
            .contains("301").doesNotContain("Location: //").doesNotContain("Location: /\\");

        // 4. The rows that would misbehave are refused before they are stored.
        assertThat(refusal(siteId, "/away", SiteRedirectModel.MATCH_EXACT, "//evil.example/x"))
            .as("step 4: a protocol-relative target would leave the site while reading as a path")
            .isEqualTo("redirect_target_invalid");
        assertThat(refusal(siteId, "/script", SiteRedirectModel.MATCH_EXACT, "javascript:alert(1)"))
            .as("step 4: only http(s) addresses and paths are targets").isEqualTo("redirect_target_invalid");
        assertThat(refusal(siteId, "/same", SiteRedirectModel.MATCH_EXACT, "/same"))
            .as("step 4: a redirect onto itself loops").isEqualTo("redirect_loops");
        assertThat(refusal(siteId, "/docs", SiteRedirectModel.MATCH_PREFIX, "/docs/v2"))
            .as("step 4: a prefix sending under itself loops").isEqualTo("redirect_loops");
        assertThat(refusal(siteId, "no-slash", SiteRedirectModel.MATCH_EXACT, "/x"))
            .as("step 4: a path starts with /").isEqualTo("redirect_path_invalid");
        assertThat(refusal(siteId, "/old", SiteRedirectModel.MATCH_PREFIX, "/elsewhere"))
            .as("step 4: one redirect per path and site").isEqualTo("redirect_path_taken");
        assertThat(refusal(siteId, "/crlf", SiteRedirectModel.MATCH_EXACT, "/a\r\nSet-Cookie: x=1"))
            .as("step 4: a control character never reaches a header").isEqualTo("redirect_target_invalid");
        redirect(siteId, "/ping", SiteRedirectModel.MATCH_EXACT, "/pong", null);
        assertThat(refusal(siteId, "/pong", SiteRedirectModel.MATCH_EXACT, "/ping"))
            .as("step 4: two redirects sending to each other loop").isEqualTo("redirect_loops");
        redirect(siteId, "/c1", SiteRedirectModel.MATCH_EXACT, "/c2", null);
        redirect(siteId, "/c2", SiteRedirectModel.MATCH_EXACT, "/c3", null);
        assertThat(refusal(siteId, "/c3", SiteRedirectModel.MATCH_EXACT, "/c1"))
            .as("step 4: a loop through any number of redirects").isEqualTo("redirect_loops");
        assertThat(refusal(siteId, "/a/../b", SiteRedirectModel.MATCH_EXACT, "/x"))
            .as("step 4: a path no request is canonically spelled as").isEqualTo("redirect_path_invalid");

        // 5. Removed, the path serves again.
        Models.get(SiteRedirectModel.class).delete((Integer) old.get(SiteRedirectModel.ID));
        reload();
        assertThat(request("/old")).as("step 5: the removed redirect is gone").contains("200").contains("site-content");

        // 6. Deleting the site for good takes its redirects with it.
        HardDeletes.byId(Models.get(SiteModel.class), siteId);
        assertThat(Models.get(SiteRedirectModel.class).findBySiteId(siteId))
            .as("step 6: no redirect outlives its site").isEmpty();
    }

    private static Row redirect(int siteId, String path, String match, String target, String status) {
        Row row = Models.get(SiteRedirectModel.class).createEmptyRow();
        row.set(SiteRedirectModel.SITE_ID, siteId);
        row.set(SiteRedirectModel.FROM_PATH, path);
        row.set(SiteRedirectModel.MATCH, match);
        row.set(SiteRedirectModel.TARGET, target);
        if (status != null) {
            row.set(SiteRedirectModel.HTTP_STATUS, status);
        }
        Models.get(SiteRedirectModel.class).save(row);
        return row;
    }

    /** @return the message key of the violation that refused the row */
    private static String refusal(int siteId, String path, String match, String target) {
        Violations refused = catchThrowableOfType(() -> redirect(siteId, path, match, target, null),
            Violations.class);
        assertThat((Throwable) refused).as("the row is refused").isNotNull();
        return refused.all().getFirst().message().key();
    }

    private static void reload() {
        proxy.getDispatcher().reloadRoutes();
    }

    private static String request(String path) throws Exception {
        return ProxyTestSupport.rawRequest(port, HOST, path);
    }
}
