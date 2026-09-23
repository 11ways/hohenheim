package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.proxy.RoutingProblem;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.test.ProxyTestSupport.addDomain;
import static be.elevenways.hohenheim.test.ProxyTestSupport.bootRuntime;
import static be.elevenways.hohenheim.test.ProxyTestSupport.httpPort;
import static be.elevenways.hohenheim.test.ProxyTestSupport.rawRequest;
import static be.elevenways.hohenheim.test.ProxyTestSupport.setupSite;
import static be.elevenways.hohenheim.test.ProxyTestSupport.startProxy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An enabled site the route load cannot build is no longer only a log line: the dispatcher
 * records it, with its reason, where an attention surface can ask for it, and the healthy
 * sites of the same load keep routing.
 */
class RoutingProblemsTest {

    private static ProxyServer proxy;

    @BeforeAll
    static void boot() throws Exception {
        bootRuntime();
    }

    @AfterAll
    static void stop() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
    }

    @Test
    @Timeout(60)
    void sitesTheLoadCannotBuildAreRecordedNotJustLogged() throws Exception {
        var siteModel = Models.get(SiteModel.class);

        // A healthy site, so the load itself is proven to succeed around the broken ones.
        Row healthy = setupSite("hohenheim:redirect", "Problems Healthy", "problems-healthy",
            Map.of("target_url", "https://example.com/"));
        addDomain(healthy, "healthy.problems.test", "exact", null, false);

        // A site whose kind this build no longer knows (a stored row from another build);
        // written past validation, which would refuse it, and past the revision behaviour,
        // because this simulates a stored row rather than an edit anyone made.
        Row unknown = setupSite("hohenheim:redirect", "Problems Unknown", "problems-unknown",
            Map.of("target_url", "https://example.com/"));
        addDomain(unknown, "unknown.problems.test", "exact", null, false);
        siteModel.find().where(SiteModel.ID.eq(unknown.get(SiteModel.ID)))
            .assign(SiteModel.UPSTREAM_KIND, "hohenheim:retired_kind")
            .bypassBehaviours()
            .updateAll();

        // A site whose handler cannot be built: an upstream protocol token this build does
        // not know now fails closed instead of silently dialing HTTP/1.1.
        Row failing = setupSite("hohenheim:address", "Problems Failing", "problems-failing",
            Map.of("forward_host", "127.0.0.1", "forward_port", 9));
        addDomain(failing, "failing.problems.test", "exact", null, false);
        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("forward_host", "127.0.0.1");
        broken.put("forward_port", 9);
        broken.put("upstream_protocol", "h3");
        siteModel.find().where(SiteModel.ID.eq(failing.get(SiteModel.ID)))
            .assign(SiteModel.SETTINGS, broken)
            .bypassBehaviours()
            .updateAll();

        proxy = startProxy();
        int port = httpPort(proxy);
        List<RoutingProblem> problems = proxy.getDispatcher().routingProblems();

        // Step 1: the unknown kind is recorded against its site, marked unrouted.
        assertThat(problems)
            .as("step 1: the unknown kind is a recorded problem naming the kind")
            .anySatisfy(problem -> {
                assertThat(problem.siteId()).isEqualTo(unknown.get(SiteModel.ID));
                assertThat(problem.reason()).isEqualTo(RoutingProblem.Reason.UNKNOWN_KIND);
                assertThat(problem.reason().unrouted()).isTrue();
                assertThat(problem.detail()).isEqualTo("hohenheim:retired_kind");
            });

        // Step 2: the handler that threw is recorded with the cause.
        assertThat(problems)
            .as("step 2: the failed handler is a recorded problem naming the cause")
            .anySatisfy(problem -> {
                assertThat(problem.siteId()).isEqualTo(failing.get(SiteModel.ID));
                assertThat(problem.reason()).isEqualTo(RoutingProblem.Reason.HANDLER_FAILED);
                assertThat(problem.detail()).contains("h3");
            });

        // Step 3: the healthy site has no problem and still routes.
        Integer healthyId = healthy.get(SiteModel.ID);
        assertThat(problems).as("step 3: the healthy site is not blamed")
            .noneMatch(problem -> healthyId.equals(problem.siteId()));
        assertThat(rawRequest(port, "healthy.problems.test", "/"))
            .as("step 3: the healthy site still routes").contains("Location: https://example.com/");
        assertThat(rawRequest(port, "failing.problems.test", "/"))
            .as("step 3: the failed site is unrouted, not half-served").contains("404");
    }
}
