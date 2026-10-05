package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An app's record page leads with ONE verdict, and the list says the same: live and healthy, an error page visitors
 * get, and a workload that cannot start yet, each with the fix it needs.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
class AppOverviewJourneyTest extends HohenheimTestBase {

    @Test
    void aSiteLeadsWithWhatVisitorsGetAndTheListAgrees() throws Exception {
        Row healthy = site("app-journey-healthy");
        domain(healthy, "healthy.app-journey.test", false);
        Row broken = site("app-journey-broken");
        domain(broken, "broken.app-journey.test", true);
        try {
            // 1. A site lands on its overview, and a healthy one says where it is live, over the scheme that works.
            String page = adminGet(overview(healthy)).body();
            assertThat(page).as("step 1: the overview leads with an OK verdict")
                .contains("data-cms-record-health=\"ok\"");
            assertThat(page).as("step 1: which says the address visitors reach, over plain HTTP without a certificate")
                .contains("Live at http://healthy.app-journey.test");
            assertThat(page).as("step 1: the Addresses card lists the name")
                .contains("data-app-address=\"healthy.app-journey.test\"");
            assertThat(adminGet("/admin/sites/" + healthy.get(SiteModel.ID)).body())
                .as("step 1: the record page's lead line says what the site serves")
                .contains("data-cms-record-lead");

            // 1b. The overview heads with the record itself, and its actions are the heading's: ONE action band, never
            //     a second row of the same actions under the verdict.
            assertThat(page).as("step 1b: the overview draws the record heading")
                .contains("data-cms-record-head")
                .contains("<h1>app-journey-healthy</h1>");
            assertThat(page.split("data-cms-record-actions", -1).length - 1)
                .as("step 1b: exactly one action band on the page").isEqualTo(1);
            assertThat(page).as("step 1b: and no record-actions widget repeating it")
                .doesNotContain("cms-record-actions-widget");

            // 2. A name forced to HTTPS without a working certificate is the error page visitors get: said plainly,
            //    with the fix offered right there, and the address marked as not working.
            String brokenPage = adminGet(overview(broken)).body();
            assertThat(brokenPage).as("step 2: the verdict is broken")
                .contains("data-cms-record-health=\"broken\"");
            assertThat(brokenPage).as("step 2: in visitors' words")
                .contains("Visitors get an error page");
            assertThat(brokenPage).as("step 2: naming the address that fails")
                .contains("broken.app-journey.test has no working certificate");
            assertThat(brokenPage).as("step 2: and the fix leads to the site's addresses")
                .contains("/admin/sites/" + broken.get(SiteModel.ID) + "/page/" + SiteParts.DOMAINS_TAB);

            // 3. The list draws the same verdicts: one producer, so the glyph and the band never disagree.
            String list = adminGet("/admin/sites?q=app-journey").body();
            assertThat(list).as("step 3: the healthy site's row carries the OK glyph")
                .contains("data-cms-health=\"ok\"");
            assertThat(list).as("step 3: the broken one's the broken glyph")
                .contains("data-cms-health=\"broken\"");

            // 4. A switched-off site answers nobody, and the band offers switching it on.
            healthy.set(SiteModel.ENABLED, false);
            Models.get(SiteModel.class).save(healthy);
            String off = adminGet(overview(healthy)).body();
            assertThat(off).as("step 4: a switched-off site is an attention verdict")
                .contains("data-cms-record-health=\"attention\"")
                .contains("Switched off");
            assertThat(off).as("step 4: whose fix is the enable operation")
                .contains("/invoke/hohenheim.enable_site");
        } finally {
            HardDeletes.row(Models.get(SiteModel.class), broken);
            HardDeletes.row(Models.get(SiteModel.class), healthy);
        }
    }

    @Test
    void aWorkloadThatCannotStartSaysWhyAndWhereToFixIt() throws Exception {
        var servers = Models.get(ServerModel.class);
        int serverId = ServerModel.localServerId();
        HostFixtures.LocalHostState localBefore = HostFixtures.captureLocal();
        Row instance = instance("app-journey-workload");
        try {
            // 1. On an admitted host a stopped workload is not running, and deploying it is the fix.
            HostFixtures.admitLocal();
            String stopped = adminGet("/admin/instances/" + instance.get(InstanceModel.ID) + "/page/overview").body();
            assertThat(stopped).as("step 1: a stopped workload is an attention verdict")
                .contains("data-cms-record-health=\"attention\"")
                .contains("Not running");
            assertThat(stopped).as("step 1: and not a host problem")
                .doesNotContain("cannot start yet");

            // 2. Block its host: the verdict names the workload and says it cannot start, with the host's reason.
            Row server = servers.findById(serverId);
            server.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
            servers.save(server);
            String blocked = adminGet("/admin/instances/" + instance.get(InstanceModel.ID) + "/page/overview").body();
            assertThat(blocked).as("step 2: the verdict says it cannot start")
                .contains("app-journey-workload cannot start yet");

            // 3. Its fix is the host's page, where Check and admit lives.
            assertThat(blocked).as("step 3: the fix leads to the host")
                .contains("/admin/servers/" + serverId + "/page/overview");

            // 4. The instances list draws the same verdict.
            String list = adminGet("/admin/instances?q=app-journey-workload").body();
            assertThat(list).as("step 4: the row carries the attention glyph")
                .contains("data-cms-health=\"attention\"");
        } finally {
            localBefore.restore();
            HardDeletes.row(Models.get(InstanceModel.class), instance);
        }
    }

    private static String overview(Row site) {
        return "/admin/sites/" + site.get(SiteModel.ID) + "/page/overview";
    }

    private static Row site(String name) {
        Row site = Models.get(SiteModel.class).createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.ENABLED, true);
        Models.get(SiteModel.class).save(site);
        return site;
    }

    private static void domain(Row site, String hostname, boolean forceSsl) {
        SiteDomainModel domains = Models.get(SiteDomainModel.class);
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.FORCE_SSL, forceSsl);
        domains.save(domain);
    }

    private static Row instance(String name) {
        var instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "command", "sleep 60"));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        instances.save(row);
        return row;
    }
}
