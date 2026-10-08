package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.Poll;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
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
                .containsPattern("<h1[^>]*>app-journey-healthy</h1>");
            assertThat(page.split("data-cms-record-actions", -1).length - 1)
                .as("step 1b: exactly one action band on the page").isEqualTo(1);
            assertThat(page).as("step 1b: and no record-actions widget repeating it")
                .doesNotContain("cms-record-actions-widget");

            // 1c. The heading's first action opens the site where visitors reach it, in a new tab.
            assertThat(page).as("step 1c: Open site leads to the live address")
                .contains("href=\"http://healthy.app-journey.test")
                .contains("Open site");

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
            assertThat(brokenPage).as("step 2: a site visitors cannot reach offers no Open site")
                .doesNotContain("href=\"https://broken.app-journey.test")
                .doesNotContain("href=\"http://broken.app-journey.test");

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

    @Test
    void aWorkloadServedByASiteOpensThatSite() throws Exception {
        Row instance = instance("app-journey-served");
        Row site = site("app-journey-served-site");
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:instance");
        site.set(SiteModel.INSTANCE_ID, instance.get(InstanceModel.ID));
        Models.get(SiteModel.class).save(site);
        domain(site, "served.app-journey.test", false);
        Row bare = instance("app-journey-unserved");
        HostFixtures.LocalHostState localBefore = HostFixtures.captureLocal();
        try {
            // 1. A running workload on a host that takes new apps leads its heading with Open site, to the address of
            //    the site serving it. Open site shows only while the app serves (D7f: never for a stopped one), and
            //    since D9 an admitted host without a fresh memory reading takes nothing, so its workload cannot start.
            HostFixtures.makeLocalPlaceable(16L * 1024);
            instance.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
            Models.get(InstanceModel.class).save(instance);
            String served = adminGet("/admin/instances/" + instance.get(InstanceModel.ID) + "/page/overview").body();
            assertThat(served).as("step 1: Open site leads to the serving site's address")
                .contains("href=\"http://served.app-journey.test")
                .contains("Open site");

            // 2. A workload no site serves has nothing to open.
            String unserved = adminGet("/admin/instances/" + bare.get(InstanceModel.ID) + "/page/overview").body();
            assertThat(unserved).as("step 2: no Open site without a serving site")
                .doesNotContain("Open site");

            // 3. A workload that stopped after an error is broken: its address answers with an error page, so neither
            //    its page nor the site's offers Open site, and the health band carries the fix instead. The host
            //    takes new apps (step 1): on a host that cannot place it, "cannot start" is the verdict that leads.
            instance.set(InstanceModel.STATUS, InstanceModel.STATUS_ERROR);
            Models.get(InstanceModel.class).save(instance);
            String failed = adminGet("/admin/instances/" + instance.get(InstanceModel.ID) + "/page/overview").body();
            assertThat(failed).as("step 3: the workload's verdict is broken")
                .contains("data-cms-record-health=\"broken\"");
            assertThat(failed).as("step 3: a broken workload offers no Open site")
                .doesNotContain("Open site");
            String siteOfFailed = adminGet(overview(site)).body();
            assertThat(siteOfFailed).as("step 3: nor does the site serving it, whose visitors get the error page")
                .contains("data-cms-record-health=\"broken\"")
                .doesNotContain("Open site");

            // 4. A RUNNING workload is only as healthy as what its visitors get: once its address is forced to HTTPS
            //    without a working certificate, the workload's page, its list row and the site all say error page.
            instance.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
            Models.get(InstanceModel.class).save(instance);
            Row name = Models.get(SiteDomainModel.class).find()
                .where(SiteDomainModel.SITE_ID.eq(site.get(SiteModel.ID))).first();
            name.set(SiteDomainModel.FORCE_SSL, true);
            Models.get(SiteDomainModel.class).save(name);
            String forced = adminGet("/admin/instances/" + instance.get(InstanceModel.ID) + "/page/overview").body();
            assertThat(forced).as("step 4: the running workload's verdict is the error page its visitors get")
                .contains("data-cms-record-health=\"broken\"")
                .contains("Visitors get an error page")
                .doesNotContain("Live at");
            assertThat(adminGet("/admin/instances?q=app-journey-served").body())
                .as("step 4: and its list row carries the broken glyph").contains("data-cms-health=\"broken\"");

            // 5. The workload's band carries the site's two fixes (the App-Problem board): getting a certificate on the
            //    site's addresses, and stopping forcing HTTPS, which runs on the site, not on the workload. The page's
            //    old Refresh button is gone: loading the page reads the evidence afresh.
            Object siteId = site.get(SiteModel.ID);
            String fixes = forced.substring(forced.indexOf("data-cms-record-health"),
                forced.indexOf("</pl-alert>", forced.indexOf("data-cms-record-health")));
            assertThat(fixes).as("step 5: Get a certificate leads to the site's addresses")
                .contains("Get a certificate")
                .contains("/admin/sites/" + siteId + "/page/" + SiteParts.DOMAINS_TAB);
            assertThat(fixes).as("step 5: Stop forcing HTTPS runs on the site serving the workload")
                .contains("Stop forcing HTTPS")
                .contains("/admin/sites/invoke/hohenheim.stop_forcing_https?ids=" + siteId);
            assertThat(forced).as("step 5: no lone Refresh button under the band")
                .doesNotContain(">Refresh<");

            // 6. Stopping forcing HTTPS is that fix: the name is served over plain HTTP, the workload is live again,
            //    and the latch never forces it back by itself, since the operator chose "off".
            adminPostForm("/admin/sites/invoke/hohenheim.stop_forcing_https?ids=" + siteId, confirmed(""));
            Row unforced = Models.get(SiteDomainModel.class).findById(name.get(SiteDomainModel.ID));
            assertThat((Boolean) unforced.get(SiteDomainModel.FORCE_SSL)).as("step 6: HTTPS is no longer forced")
                .isFalse();
            assertThat((Boolean) unforced.get(SiteDomainModel.FORCE_SSL_AUTO))
                .as("step 6: and the certificate latch stays disarmed").isFalse();
            assertThat(adminGet("/admin/instances/" + instance.get(InstanceModel.ID) + "/page/overview").body())
                .as("step 6: the workload is live at its address over HTTP again")
                .contains("data-cms-record-health=\"ok\"")
                .contains("Live at http://served.app-journey.test");
        } finally {
            localBefore.restore();
            HardDeletes.row(Models.get(SiteModel.class), site);
            HardDeletes.row(Models.get(InstanceModel.class), bare);
            HardDeletes.row(Models.get(InstanceModel.class), instance);
        }
    }

    @Test
    void aProxyWhoseUpstreamDoesNotAnswerIsAnErrorPageBeforeAnyVisitor() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        Row site = ProxyTestSupport.setupSite("hohenheim:address", "app-journey-refused", "app-journey-refused",
            Map.of("forward_host", "127.0.0.1", "forward_port", closedPort));
        ProxyTestSupport.addDomain(site, "refused.app-journey.test", "exact", null, false);
        ProxyServer previous = ServerMain.getProxyServer();
        ProxyServer proxy = ProxyTestSupport.startProxy();
        ServerMain.adoptProxyServer(proxy);
        try {
            // 1. No visitor has asked yet, but the proxy probes the fixed upstream on its own: nothing listens there,
            //    so the overview and the list already say visitors get an error page.
            Poll.until("step 1: the proxy's own probe finds the closed upstream", Duration.ofSeconds(10), () -> {
                proxy.getDispatcher().probeUpstreams();
                return overviewHealth(site, "broken");
            });
            assertThat(adminGet(overview(site)).body()).as("step 1: the verdict names what visitors get")
                .contains("Visitors get an error page")
                .doesNotContain("Live at");
            assertThat(adminGet("/admin/sites?q=app-journey-refused").body())
                .as("step 1: and the list row agrees").contains("data-cms-health=\"broken\"");

            // 2. The upstream comes up: the next probe finds it answering and the app is live again.
            try (ServerSocket upstream = new ServerSocket(closedPort, 50, InetAddress.getLoopbackAddress())) {
                Poll.until("step 2: the next probe finds the upstream answering", Duration.ofSeconds(10), () -> {
                    proxy.getDispatcher().probeUpstreams();
                    return overviewHealth(site, "ok");
                });
            }

            // 3. It goes away again and a visitor asks first: their refused dial alone turns the verdict broken.
            String answer = ProxyTestSupport.rawRequest(ProxyTestSupport.httpPort(proxy), "refused.app-journey.test", "/");
            assertThat(answer).as("step 3: the visitor gets an error status").matches("(?s)HTTP/1\\.1 50[234].*");
            assertThat(adminGet(overview(site)).body()).as("step 3: the verdict is broken")
                .contains("data-cms-record-health=\"broken\"");
        } finally {
            ServerMain.adoptProxyServer(previous);
            proxy.stop();
            HardDeletes.row(Models.get(SiteModel.class), site);
        }
    }

    /** Whether the site's overview states this health tone; for polling, so a failed request is a failure. */
    private boolean overviewHealth(Row site, String tone) {
        try {
            return adminGet(overview(site)).body().contains("data-cms-record-health=\"" + tone + "\"");
        } catch (Exception failed) {
            throw new AssertionError("the overview of " + site.get(SiteModel.NAME) + " could not be read", failed);
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
