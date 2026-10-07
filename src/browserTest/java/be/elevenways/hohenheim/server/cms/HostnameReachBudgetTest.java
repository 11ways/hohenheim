package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.tls.HostnameReach;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.OutboundFixture;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A resolver that never answers holds up neither one reader nor a whole Addresses page: a reader waits at most one
 * lookup's bound, a page's rows share one budget, and the lookup keeps running into the cache for the next view.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
class HostnameReachBudgetTest extends HohenheimTestBase {

    @Test
    void aResolverThatNeverAnswersDelaysNoReaderPastItsBudget() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        InetAddress here = InetAddress.getByName("203.0.113.77");
        String[] names = new String[4];
        for (int i = 0; i < names.length; i++) {
            names[i] = "hang" + i + "-" + suffix + ".test";
        }
        var servers = Models.get(ServerModel.class);
        Row local = servers.findById(ServerModel.localServerId());
        String declared = local.get(ServerModel.PUBLIC_IPV4);
        try (OutboundFixture first = OutboundFixture.pendingResolution(names[0], here);
             OutboundFixture second = OutboundFixture.pendingResolution(names[1], here);
             OutboundFixture third = OutboundFixture.pendingResolution(names[2], here);
             OutboundFixture fourth = OutboundFixture.pendingResolution(names[3], here)) {
            local.set(ServerModel.PUBLIC_IPV4, here.getHostAddress());
            servers.save(local);

            // 1. One reader of a name the resolver holds waits its own bound, then reads "checking".
            long started = System.nanoTime();
            HostnameReach.Reach waited = HostnameReach.recent(names[0], 200);
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(waited.verdict()).as("step 1: an unanswered lookup reads as checking")
                .isEqualTo(HostnameReach.Verdict.CHECKING);
            assertThat(waitedMs).as("step 1: the reader waited about its bound, not the resolver's silence")
                .isBetween(150L, 1_000L);

            // 2. A second reader of the same name shares the running lookup instead of starting another.
            assertThat(HostnameReach.recent(names[0], 0).verdict()).as("step 2: still checking, without waiting")
                .isEqualTo(HostnameReach.Verdict.CHECKING);
            assertThat(first.waitingResolutions()).as("step 2: one resolution for the name, not one per reader")
                .isEqualTo(1);

            // 3. The Addresses page with four unanswered names renders within one page budget, not a wait per row,
            //    and says which names are still being checked.
            Row site = site("reach-" + suffix);
            for (String name : names) {
                domain(site, name);
            }
            started = System.nanoTime();
            HttpResponse<String> page = adminGet("/admin/domains?q=" + suffix);
            long pageMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(page.statusCode()).as("step 3: the Addresses list renders").isEqualTo(200);
            assertThat(page.body()).as("step 3: unanswered names read as checking")
                .contains("data-state=\"checking\"");
            assertThat(pageMs).as("step 3: the rows shared one budget; a wait per row would take %d ms",
                    names.length * HostnameReach.LOOKUP_WAIT_MS)
                .isLessThan(DomainParts.REACH_RENDER_BUDGET_MS + HostnameReach.LOOKUP_WAIT_MS);

            // 4. Once the resolver answers, the lookups that kept running land in the cache: the next reader gets the
            //    answer without waiting.
            first.release();
            second.release();
            third.release();
            fourth.release();
            for (String name : names) {
                assertThat(HostnameReach.recent(name, HostnameReach.LOOKUP_WAIT_MS).verdict())
                    .as("step 4: %s answers once the resolver does", name)
                    .isEqualTo(HostnameReach.Verdict.POINTS_HERE);
            }
            assertThat(HostnameReach.recent(names[3], 0).verdict()).as("step 4: from the cache, without waiting")
                .isEqualTo(HostnameReach.Verdict.POINTS_HERE);
        } finally {
            local = servers.findById(ServerModel.localServerId());
            local.set(ServerModel.PUBLIC_IPV4, declared);
            servers.save(local);
        }
    }

    private static Row site(String name) {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, false);
        sites.save(site);
        return site;
    }

    private static void domain(Row site, String hostname) {
        SiteDomainModel domains = Models.get(SiteDomainModel.class);
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.FORCE_SSL, false);
        domains.save(domain);
    }
}
