package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.proxy.HostnamePatterns;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.proxy.RouteClaims;
import be.elevenways.zenit.common.orm.datasource.DuplicateKeyException;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regex hostname matching is case-insensitive (the request host is pre-folded), so two
 * case-variant spellings of one pattern are ONE route: the claim key folds case, the
 * intersect scan refuses the pair, and the dispatcher admits only one of them.
 */
class RegexCaseFoldTest {

    private static ProxyServer proxy;
    private static HttpServer firstUpstream;
    private static HttpServer secondUpstream;

    @BeforeAll
    static void boot() throws Exception {
        ProxyTestSupport.bootRuntime();
    }

    @AfterAll
    static void stop() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        if (firstUpstream != null) firstUpstream.stop(0);
        if (secondUpstream != null) secondUpstream.stop(0);
    }

    @Test
    @Timeout(30)
    void caseVariantRegexPatternsAreOneRoute() {
        // Step 1: the claim key folds a regex source to lowercase, so the unique index
        // and the reload-time claim registry can refuse the second spelling.
        assertThat(RouteClaims.keyOf("^App\\.rc\\.test$", "regex", null, null))
            .as("step 1: case-variant regex sources spell ONE claim key")
            .isEqualTo(RouteClaims.keyOf("^app\\.rc\\.test$", "regex", null, null));

        // Step 2: the write-time conflict scan sees them as intersecting.
        assertThat(HostnamePatterns.intersect(
                "^App\\.rc\\.test$", "regex", "^app\\.rc\\.test$", "regex"))
            .as("step 2: case-variant regex patterns intersect")
            .isTrue();
        assertThat(HostnamePatterns.intersect(
                "^app\\.rc\\.test$", "regex", "^api\\.rc\\.test$", "regex"))
            .as("step 2: genuinely different regex patterns stay undecidable (false)")
            .isFalse();
    }

    @Test
    @Timeout(30)
    void dispatcherAdmitsOneCaseVariantAndMatchesUppercaseLiterals() throws Exception {
        firstUpstream = respondingWith("first-claimant");
        secondUpstream = respondingWith("second-claimant");

        // An UPPERCASE literal in the stored pattern, proving matching is insensitive.
        Row first = ProxyTestSupport.setupSite("hohenheim:proxy", "Alpha Regex", "alpha-regex",
            Map.of("forward_host", "127.0.0.1",
                   "forward_port", firstUpstream.getAddress().getPort()));
        ProxyTestSupport.addDomain(first, "^App\\.rc\\.test$", "regex", null, false);
        Row second = ProxyTestSupport.setupSite("hohenheim:proxy", "Zeta Regex", "zeta-regex",
            Map.of("forward_host", "127.0.0.1",
                   "forward_port", secondUpstream.getAddress().getPort()));

        // The folded claim key collides in the live_route_key unique index, so the second
        // spelling is impossible to STORE while the first is live (pre-fix both were
        // admitted and arbitrary order decided who served).
        assertThatThrownBy(() ->
                ProxyTestSupport.addDomain(second, "^app\\.rc\\.test$", "regex", null, false))
            .as("the case-variant spelling of a claimed regex route is refused at write time "
                + "(conflict-scan Violations or the unique-index backstop)")
            .isInstanceOfAny(DuplicateKeyException.class, Violations.class);

        proxy = ProxyTestSupport.startProxy();
        int port = ProxyTestSupport.httpPort(proxy);

        assertThat(proxy.getDispatcher().getRegexRouteCount())
            .as("only the admitted pattern is in the route table")
            .isEqualTo(1);

        String served = ProxyTestSupport.rawRequest(port, "app.rc.test", "/");
        assertThat(served)
            .as("the uppercase-literal pattern matches the (always lowercase) request host")
            .contains("200").contains("first-claimant")
            .doesNotContain("second-claimant");
    }

    private static HttpServer respondingWith(String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        return server;
    }
}
