package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.ProxyAttention;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.proxy.RoutingProblem;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.microcopy.server.DefaultCatalogLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
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

        // Step 4: the dashboard projects the SAME recorded problems, one item each: the
        //         unrouted site is an error naming it and its cause, linked to its record.
        List<AttentionItem> items = new ArrayList<>();
        ProxyAttention.routingProblems(items, problems);
        assertThat(items).as("step 4: one attention item per recorded problem")
            .hasSize(problems.size());
        AttentionItem unknownItem = items.stream()
            .filter(item -> "Problems Unknown".equals(item.title().args().get("name")))
            .findFirst().orElseThrow(() -> new AssertionError("step 4: no item names the unknown-kind site"));
        assertThat(unknownItem.severity()).as("step 4: missing from routing is an error")
            .isEqualTo(AttentionSeverity.ERROR);
        assertThat(unknownItem.title().key()).as("step 4: titled as missing from routing")
            .isEqualTo("site_unrouted");
        assertThat(unknownItem.detail()).as("step 4: the detail explains itself").isNotNull();
        assertThat(unknownItem.detail().key()).as("step 4: the detail is the reason's own sentence")
            .isEqualTo("unknown_kind");
        assertThat(unknownItem.detail().args().get("detail"))
            .as("step 4: carrying the kind this build does not know").isEqualTo("hohenheim:retired_kind");
        assertThat(unknownItem.target()).as("step 4: the item links somewhere").isNotNull();
        assertThat(unknownItem.target().toUrl()).as("step 4: to the site's own record")
            .endsWith("/admin/sites/" + unknown.get(SiteModel.ID));
        assertThat(items).as("step 4: the healthy site raises nothing")
            .noneMatch(item -> "Problems Healthy".equals(item.title().args().get("name")));
    }

    /**
     * Every routing-problem reason reads as a sentence in both shipped locales, and its
     * severity is the reason's own unrouted fact.
     *
     * AIDEV-NOTE: the detail key is DERIVED from the member's name, which the manifest and
     * Java key scans cannot see, so this walk over the enum is the gate: a reason added
     * tomorrow fails here until it has copy.
     */
    @Test
    void everyRoutingReasonReadsAsASentenceInBothLocales() {
        DefaultCatalogLoader catalogs = new DefaultCatalogLoader();
        List<String> missing = new ArrayList<>();
        for (RoutingProblem.Reason reason : RoutingProblem.Reason.values()) {
            List<AttentionItem> items = new ArrayList<>();
            ProxyAttention.routingProblems(items,
                List.of(new RoutingProblem(7, "Reason Site", reason, "the cause")));

            // 1. One item, whose severity is the reason's own fact.
            assertThat(items).as("step 1: %s raises exactly one item", reason).hasSize(1);
            AttentionItem item = items.get(0);
            assertThat(item.severity()).as("step 1: %s severity follows unrouted()", reason)
                .isEqualTo(reason.unrouted() ? AttentionSeverity.ERROR : AttentionSeverity.WARNING);

            // 2. Title and detail both resolve to real copy in en AND nl.
            for (String tag : List.of("en", "nl")) {
                for (Microcopy copy : List.of(item.title(), item.detail())) {
                    String resolved = copy.resolve(LocaleChain.ofTags(tag), catalogs);
                    if (resolved.equals(copy.key())) {
                        missing.add(tag + " " + reason + " -> " + copy.key());
                    }
                }
            }
        }
        assertThat(missing).as("step 2: every routing reason has copy in en and nl").isEmpty();
    }
}
