package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimStatsFunctions;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.hohenheim.HohenheimWidgets;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.instance.InstanceStats;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.test.FreePorts;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.hohenheim.test.RecordedChanges;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.wording.DateStyle;
import be.elevenways.protoblast.common.i18n.wording.Reader;
import be.elevenways.protoblast.common.i18n.wording.Wording;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.testsupport.text.InOrder;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.history.HistoryVisibility;
import be.elevenways.zenit.common.operation.OperationRunModel;
import be.elevenways.zenit.common.operation.OperationRunStatus;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.data.UsageData;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An app's record page leads with ONE verdict, and the list says the same: live and healthy, an error page visitors
 * get, and a workload that cannot start yet, each with the fix it needs. A workload's overview says what it uses,
 * since when it runs, the database it uses and its backups.
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
                .containsPattern("<h1[^>]*>app-journey-healthy(<wbr>)?</h1>");
            assertThat(page.split("data-cms-record-actions", -1).length - 1)
                .as("step 1b: exactly one action band on the page").isEqualTo(1);
            assertThat(page).as("step 1b: and no record-actions widget repeating it")
                .doesNotContain("cms-record-actions-widget");

            // 1c. The heading's first action opens the site where visitors reach it, in a new tab.
            assertThat(page).as("step 1c: Open site leads to the live address")
                .contains("href=\"http://healthy.app-journey.test")
                .contains("Open site");

            // 1d. The trail reads Apps / the site: the Apps list shows the site's row, so its own list is never a crumb
            //     ("Apps / Sites / site" was a defect fixed on purpose).
            InOrder.assertThat(trailOf(page)).as("step 1d: the trail leads to Apps, then names the site")
                .contains("\"/admin/" + HohenheimSlugs.APPS + "\"", "app-journey-healthy");
            assertThat(trailOf(page)).as("step 1d: and never through the Sites list")
                .doesNotContain("\"/admin/" + HohenheimSlugs.SITES + "\"");

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
            String list = adminGet("/admin/sites" + listQuery("name contains \"app-journey\"")).body();
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
            // 1. On an admitted host a stopped workload reads as stopped, never as a problem; deploying is the fix.
            HostFixtures.admitLocal();
            String stopped = adminGet("/admin/instances/" + instance.get(InstanceModel.ID) + "/page/overview").body();
            assertThat(stopped).as("step 1: a stopped workload is no attention verdict")
                .doesNotContain("data-cms-record-health=\"attention\"")
                .contains("data-cms-record-health=\"unknown\"")
                .contains("Stopped");
            assertThat(stopped).as("step 1: and not a host problem")
                .doesNotContain("Cannot start yet");

            // 2. Block its host: the verdict says it cannot start, with the host's reason (the heading names it).
            Row server = servers.findById(serverId);
            server.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
            servers.save(server);
            String blocked = adminGet("/admin/instances/" + instance.get(InstanceModel.ID) + "/page/overview").body();
            assertThat(blocked).as("step 2: the verdict says it cannot start")
                .contains("Cannot start yet")
                .doesNotContain("app-journey-workload cannot start yet");

            // 3. Its fix is the host's page, where Check and admit lives.
            assertThat(blocked).as("step 3: the fix leads to the host")
                .contains("/admin/servers/" + serverId + "/page/overview");

            // 4. The instances list draws the same verdict.
            String list = adminGet("/admin/instances" + listQuery("name = \"app-journey-workload\"")).body();
            assertThat(list).as("step 4: the row carries the attention glyph")
                .contains("data-cms-health=\"attention\"");
        } finally {
            localBefore.restore();
            HardDeletes.row(Models.get(InstanceModel.class), instance);
        }
    }

    /** A record nobody deployed yet reads as not deployed yet, with Deploy as its verb, and offers no Restart. */
    @Test
    void aNeverDeployedWorkloadIsNotDeployedYetAndOffersNoRestart() throws Exception {
        Row instance = instance("app-journey-never-deployed");
        int id = instance.get(InstanceModel.ID);
        HostFixtures.LocalHostState localBefore = HostFixtures.captureLocal();
        try {
            // 1. Just created on a host that takes it: nothing went wrong, so no attention verdict; it is not deployed
            //    yet, and Deploy is its fix. Restart, which needs something to stop, is not offered.
            HostFixtures.admitLocal();
            status(id, InstanceModel.STATUS_CREATED);
            String created = adminGet("/admin/instances/" + id + "/page/overview").body();
            assertThat(created).as("step 1: a never-deployed workload is no attention verdict")
                .doesNotContain("data-cms-record-health=\"attention\"")
                .contains("data-cms-record-health=\"unknown\"")
                .contains("Not deployed yet")
                .doesNotContain(">Not running<");
            assertThat(created).as("step 1: Deploy is offered")
                .contains("/invoke/hohenheim.start_instance");
            assertThat(created).as("step 1: Restart is not")
                .doesNotContain("/invoke/hohenheim.restart_instance");
            assertThat(statusFact(created)).as("step 1: the Details card's Status agrees with the band")
                .contains("Not deployed yet")
                .doesNotContain(">Unknown<");

            // 1b. While the run that put it online is still open, it is deploying: the band and the Status say so,
            //     and once that run ended it is not deployed yet again.
            Row run = openRun(id);
            try {
                String deploying = adminGet("/admin/instances/" + id + "/page/overview").body();
                assertThat(deploying).as("step 1b: the band says it is deploying")
                    .contains("data-cms-record-health=\"unknown\"").contains("Deploying")
                    .doesNotContain("Not deployed yet");
                assertThat(statusFact(deploying)).as("step 1b: and so does the Status").contains("Deploying");
            } finally {
                HardDeletes.row(Models.get(OperationRunModel.class), run);
            }
            assertThat(statusFact(adminGet("/admin/instances/" + id + "/page/overview").body()))
                .as("step 1b: the run gone, it is not deployed yet again").contains("Not deployed yet");

            // 2. Once it ran and was stopped, it reads as stopped (a deliberate stop is no problem), and Restart is
            //    offered again.
            status(id, InstanceModel.STATUS_STOPPED);
            String stopped = adminGet("/admin/instances/" + id + "/page/overview").body();
            assertThat(stopped).as("step 2: a stopped workload is no attention verdict")
                .doesNotContain("data-cms-record-health=\"attention\"")
                .contains("data-cms-record-health=\"unknown\"");
            assertThat(statusFact(stopped)).as("step 2: the Details card's Status says Stopped, not Needs attention")
                .contains("Stopped")
                .doesNotContain("Needs attention");
            assertThat(stopped).as("step 2: and offers Restart")
                .contains("/invoke/hohenheim.restart_instance");
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
            //    the site serving it. Open site shows only while the app serves (never for a stopped one), and
            //    an admitted host without a fresh memory reading takes nothing, so its workload cannot start.
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
            assertThat(adminGet("/admin/instances" + listQuery("name = \"app-journey-served\"")).body())
                .as("step 4: and its list row carries the broken glyph").contains("data-cms-health=\"broken\"");

            // 5. The workload's band carries the site's two fixes: getting a certificate on the
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
            String liveAgain = adminGet("/admin/instances/" + instance.get(InstanceModel.ID) + "/page/overview").body();
            assertThat(liveAgain).as("step 6: the workload is live at its address over HTTP again")
                .contains("data-cms-record-health=\"ok\"")
                .contains("Live at http://served.app-journey.test")
                .as("step 6: nothing ever deployed it, so the band names no deploy").doesNotContain("Last deployed");

            // 7. Once a deploy succeeded, the band's second line says when it finished, in the reader's words
            //    (board App-Overview: "Live at ..." over the last deploy).
            var builds = Models.get(BuildOperationModel.class);
            Row deploy = builds.createEmptyRow();
            deploy.set(BuildOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
            deploy.set(BuildOperationModel.FOR_ID, instance.get(InstanceModel.ID));
            deploy.set(BuildOperationModel.BUILDER_KIND, BuildOperationModel.KIND_WORKSPACE);
            deploy.set(BuildOperationModel.STATUS, BuildOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED));
            deploy.set(BuildOperationModel.FINISHED_AT, Instant.parse("2026-09-01T10:00:00Z"));
            builds.save(deploy);
            try {
                String deployed = adminGet("/admin/instances/" + instance.get(InstanceModel.ID) + "/page/overview")
                    .body();
                String band = deployed.substring(deployed.indexOf("data-cms-record-health"),
                    deployed.indexOf("</pl-alert>", deployed.indexOf("data-cms-record-health")));
                assertThat(band).as("step 7: the live band stays OK").contains("Live at http://served.app-journey.test")
                    .as("step 7: and its second line names the last deploy").contains("<pl-alert-description")
                    .contains("Last deployed ").contains("2026");
            } finally {
                builds.delete(deploy.get(BuildOperationModel.ID));
            }
        } finally {
            localBefore.restore();
            HardDeletes.row(Models.get(SiteModel.class), site);
            HardDeletes.row(Models.get(InstanceModel.class), bare);
            HardDeletes.row(Models.get(InstanceModel.class), instance);
        }
    }

    @Test
    void aProxyWhoseUpstreamDoesNotAnswerIsAnErrorPageBeforeAnyVisitor() throws Exception {
        int closedPort = FreePorts.tcpAndUdp();
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
            assertThat(adminGet("/admin/sites" + listQuery("name = \"app-journey-refused\"")).body())
                .as("step 1: and the list row agrees").contains("data-cms-health=\"broken\"");

            // 2. The upstream comes up: the next probe finds it answering and the app is live again.
            try (ServerSocket upstream = new ServerSocket(closedPort, 50, InetAddress.getLoopbackAddress())) {
                Poll.until("step 2: the next probe finds the upstream answering", Duration.ofSeconds(10), () -> {
                    proxy.getDispatcher().probeUpstreams();
                    return overviewHealth(site, "ok");
                });
            }

            // 3. It goes away again and a visitor asks first: their refused dial alone turns the verdict broken.
            String answer = ProxyTestSupport.rawRequest(ProxyTestSupport.httpPort(proxy),
                    "refused.app-journey.test", "/");
            assertThat(answer).as("step 3: the visitor gets an error status").matches("(?s)HTTP/1\\.1 50[234].*");
            assertThat(adminGet(overview(site)).body()).as("step 3: the verdict is broken")
                .contains("data-cms-record-health=\"broken\"");
        } finally {
            ServerMain.adoptProxyServer(previous);
            proxy.stop();
            HardDeletes.row(Models.get(SiteModel.class), site);
        }
    }

    @Test
    void aWorkloadsOverviewSaysWhatItUsesSinceWhenAndWhatItHolds() throws Exception {
        List<Runnable> cleanup = new ArrayList<>();
        Row instance = instance("app-journey-details");
        cleanup.add(() -> HardDeletes.row(Models.get(InstanceModel.class), instance));
        LocaleChain en = LocaleChain.ofTags("en");
        try {
            // 1. Memory and CPU are read live: a stopped workload uses none, said in words, never a zero; a running
            //    one gets the live gauges, whose element holds the stats feed while the overview is open.
            List<WidgetInstance> idle = InstanceOverview.liveGauges(instance, en, Zenit.getMessageResolver());
            assertThat(idle).as("step 1: a stopped workload's memory and CPU are not measured")
                .allMatch(gauge -> gauge.data() instanceof UsageData usage && !usage.measured());
            assertThat(((UsageData) idle.get(0).data()).unmeasuredReason()).as("step 1: because it does not run")
                .isEqualTo("It is not running, so it uses none.");
            instance.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
            Models.get(InstanceModel.class).save(instance);
            List<WidgetInstance> running = InstanceOverview.liveGauges(instance, en, Zenit.getMessageResolver());
            assertThat(running).as("step 1: a running one gets the one live widget").singleElement()
                .satisfies(gauge -> assertThat(gauge.typeId()).isEqualTo(HohenheimWidgets.INSTANCE_LIVE_USAGE.id()));

            // 2. A reading is measured the same way on both halves (the seed and the element): memory against its
            //    cap, else what the host booked; CPU is the newest window's share against the cores the daemon
            //    reports, and a single reading is no window yet.
            long mib = 1024L * 1024L;
            Map<String, Object> first = new InstanceStats.Sample(1_000L, 0, 100 * mib, 1024 * mib, 0, 0, 2).toMap();
            Map<String, Object> second = new InstanceStats.Sample(2_000L, 36.0, 300 * mib, 0, 0, 0, 2).toMap();
            UsageData memory = HohenheimStatsFunctions.memoryGauge(first, 512 * mib, "waiting", "no limit");
            assertThat(memory.used()).as("step 2: memory is the reading").isEqualTo(100 * mib);
            assertThat(memory.max()).as("step 2: of its cap").isEqualTo(1024 * mib);
            assertThat(HohenheimStatsFunctions.memoryGauge(second, 512 * mib, "waiting", "no limit").max())
                .as("step 2: with no cap, of what the host booked").isEqualTo(512 * mib);
            assertThat(HohenheimStatsFunctions.memoryGauge(second, null, "waiting", "no limit").unmeasuredReason())
                .as("step 2: and with neither, not measured").isEqualTo("no limit");
            assertThat(HohenheimStatsFunctions.memoryGauge(null, null, "waiting", "no limit").unmeasuredReason())
                .as("step 2: before any reading it waits").isEqualTo("waiting");
            Microcopy twoCores = Microcopy.literal("2 cores");
            assertThat(HohenheimStatsFunctions.cpuGauge(null, first, "waiting", "unreported", "first", twoCores)
                .unmeasuredReason()).as("step 2: one reading is no window").isEqualTo("first");
            UsageData cpu = HohenheimStatsFunctions.cpuGauge(first, second, "waiting", "unreported", "first",
                twoCores);
            assertThat(cpu.used()).as("step 2: CPU is the newest window's share").isEqualTo(36L);
            assertThat(cpu.max()).as("step 2: of its two cores").isEqualTo(200L);
            assertThat(cpu.maxLabel()).as("step 2: said as cores").isSameAs(twoCores);

            // 3. The overview draws Memory, Root disk and CPU, and Details say when it started (the start its record
            //    stamped), the database it uses with that database's state, and its backups.
            started(instance.get(InstanceModel.ID));
            DatabaseModel databases = Models.get(DatabaseModel.class);
            Row database = databases.createEmptyRow();
            Map.of(DatabaseModel.NAME.getName(), (Object) "app-journey-shop", DatabaseModel.ENGINE.getName(), "mysql",
                DatabaseModel.PLACEMENT.getName(), DatabaseModel.PLACEMENT_DEDICATED,
                DatabaseModel.DB_NAME.getName(), "shopdb", DatabaseModel.DB_USER.getName(), "shopuser",
                DatabaseModel.DB_PASSWORD.getName(), "shop-secret-password", DatabaseModel.EPHEMERAL.getName(), true,
                DatabaseModel.STATUS.getName(), DatabaseModel.STATUS_ACTIVE).forEach(database::set);
            databases.save(database);
            cleanup.add(() -> HardDeletes.row(databases, database));
            InstanceDatabaseModel links = Models.get(InstanceDatabaseModel.class);
            Row link = links.createEmptyRow();
            link.set(InstanceDatabaseModel.INSTANCE_ID, instance.get(InstanceModel.ID));
            link.set(InstanceDatabaseModel.DATABASE_ID, database.get(DatabaseModel.ID));
            link.set(InstanceDatabaseModel.ENV_PREFIX, "DB");
            links.save(link);
            cleanup.add(() -> HardDeletes.row(links, link));
            String page = adminGet("/admin/instances/" + instance.get(InstanceModel.ID) + "/page/overview").body();
            assertThat(page).as("step 3: Resources shows memory, the root disk and CPU")
                .contains("Memory").contains("Root disk").contains("CPU");
            assertThat(page).as("step 3: Details say when it started").contains("Started");
            assertThat(page).as("step 3: the database it uses, by name and engine, with its state while it does not "
                    + "serve, linked to its page")
                .contains("app-journey-shop (MySQL, not running)")
                .contains("/admin/databases/" + database.get(DatabaseModel.ID) + "/open");
            assertThat(page).as("step 3: and its backups").contains("No backup target, so none are made");

            // 4. Recent lists one row per action. An upload is one action since history v3 (an SFTP session keeps no
            //    batch), so two uploads are two rows, each in the file verb's own sentence.
            for (String path : List.of("/data/a.txt", "/data/b.txt")) {
                History.event(Models.get(InstanceModel.class), instance.get(InstanceModel.ID), Change.fact(
                    HohenheimActivityAction.FILES_UPLOAD, Map.of(HohenheimActivityAction.PATH_KEY, path)));
            }
            assertThat(HohenheimActivityAction.FILES_UPLOAD.batchLabel().withArg("count", 312)
                .resolve(en, Zenit.getMessageResolver())).as("step 4: a batch of uploads reads in words")
                .isEqualTo("Uploaded 312 files");
            assertThat(HohenheimActivityAction.FILES_DELETE.batchLabel().withArg("count", 1)
                .resolve(LocaleChain.ofTags("nl"), Zenit.getMessageResolver())).as("step 4: in Dutch, one")
                .isEqualTo("1 bestand verwijderd");
            assertThat(HohenheimActivityAction.DEPLOYED.batchLabel())
                .as("step 4: a verb written one at a time has none")
                .isNull();
            List<String> uploads = RecordedChanges.withFact(Models.get(InstanceModel.class),
                instance.get(InstanceModel.ID), HohenheimActivityAction.FILES_UPLOAD).stream()
                .map(RecordedChanges.Recorded::actionId).distinct().toList();
            assertThat(uploads).as("step 4: the two uploads are two actions").hasSize(2);
            String recent = adminGet("/admin/instances/" + instance.get(InstanceModel.ID) + "/page/overview").body();
            for (String upload : uploads) {
                assertThat(recent.split("href=\"/admin/activity/" + upload + "\"", -1).length - 1)
                    .as("step 4: the Recent card lists each upload as its own row").isEqualTo(1);
            }
            assertThat(recent).as("step 4: in the file verb's own sentence").contains("uploaded a file to");
        } finally {
            for (int i = cleanup.size() - 1; i >= 0; i--) {
                cleanup.get(i).run();
            }
        }
    }

    @Test
    void aFailedStartIsOneVerdictWithItsCause() throws Exception {
        List<Runnable> cleanup = new ArrayList<>();
        Row instance = instance("app-journey-verdict");
        int id = instance.get(InstanceModel.ID);
        cleanup.add(() -> HardDeletes.row(Models.get(InstanceModel.class), instance));
        HostFixtures.LocalHostState localBefore = HostFixtures.captureLocal();
        cleanup.add(localBefore::restore);
        ProxyServer previous = ServerMain.getProxyServer();
        try {
            // 1. A running workload Hohenheim started names that start under Started.
            HostFixtures.makeLocalPlaceable(16L * 1024);
            status(id, InstanceModel.STATUS_RUNNING);
            started(id);
            assertThat(adminGet("/admin/instances/" + id + "/page/overview").body())
                .as("step 1: its last start is named").contains(STARTED_FACT);

            // 2. Its next start fails: the stored status says error, the verdict reads a start that failed with why,
            //    and Started names no older start beside it.
            stopped(id, HohenheimActivityAction.WORKLOAD_START_FAILED, "image app-journey:missing was not found");
            String failed = adminGet("/admin/instances/" + id + "/page/overview").body();
            assertThat(failed).as("step 2: the band reads a failed start in words")
                .contains("Could not start")
                .doesNotContain("What refused it")
                .doesNotContain("Stopped after an error");
            InOrder.assertThat(failed)
                .as("step 2: the band reads a failed start in words, the failure's text after them")
                .contains("It never got to run.", "Technically: image app-journey:missing was not found");
            // A record left claiming running beside that failed start still names no start.
            status(id, InstanceModel.STATUS_RUNNING);
            assertThat(adminGet("/admin/instances/" + id + "/page/overview").body())
                .as("step 2: no start is named after a failed one, even for a stale running claim")
                .doesNotContain(STARTED_FACT);

            // 3. Hohenheim correcting a stored status is bookkeeping: its rows stay out of Recent (the full log lists
            //    them through the internal filter), so Recent reads what happened, the failed start.
            assertThat(HohenheimActivityAction.RECONCILED.visibility()).as("step 3: the reconcile verb is internal")
                .isEqualTo(HistoryVisibility.INTERNAL);
            History.event(Models.get(InstanceModel.class), id, Change.fact(HohenheimActivityAction.RECONCILED,
                Map.of(HohenheimActivityAction.FROM_KEY, InstanceModel.STATUS_RUNNING,
                    HohenheimActivityAction.TO_KEY, InstanceModel.STATUS_STOPPED,
                    HohenheimActivityAction.DAEMON_STATE_KEY,
                    HohenheimActivityAction.ObservedWorkload.STOPPED.token(),
                    HohenheimActivityAction.CRASH_LOOP_KEY, false)));
            String recent = adminGet("/admin/instances/" + id + "/page/overview").body();
            assertThat(recent).as("step 3: Recent leaves the correction out")
                .doesNotContain("in line with its host")
                .contains("app-journey-verdict could not be started");

            // 4. The workload runs, but its database does not and its site does not answer: the database is the
            //    cause, so the band says which database and the site's error page names it as its root.
            DatabaseModel databases = Models.get(DatabaseModel.class);
            Row database = databases.createEmptyRow();
            Map.of(DatabaseModel.NAME.getName(), (Object) "app-journey-verdict-db", DatabaseModel.ENGINE.getName(),
                "mysql", DatabaseModel.PLACEMENT.getName(), DatabaseModel.PLACEMENT_DEDICATED,
                DatabaseModel.DB_NAME.getName(), "verdictdb", DatabaseModel.DB_USER.getName(), "verdictuser",
                DatabaseModel.DB_PASSWORD.getName(), "verdict-secret-password", DatabaseModel.EPHEMERAL.getName(), true,
                DatabaseModel.STATUS.getName(), DatabaseModel.STATUS_ACTIVE).forEach(database::set);
            databases.save(database);
            cleanup.add(() -> HardDeletes.row(databases, database));
            InstanceDatabaseModel links = Models.get(InstanceDatabaseModel.class);
            Row link = links.createEmptyRow();
            link.set(InstanceDatabaseModel.INSTANCE_ID, id);
            link.set(InstanceDatabaseModel.DATABASE_ID, database.get(DatabaseModel.ID));
            link.set(InstanceDatabaseModel.ENV_PREFIX, "DB");
            links.save(link);
            cleanup.add(() -> HardDeletes.row(links, link));
            started(id);
            Row site = ProxyTestSupport.setupInstanceSite("app-journey-verdict-site", "app-journey-verdict-site", id);
            cleanup.add(() -> HardDeletes.row(Models.get(SiteModel.class), site));
            ProxyTestSupport.addDomain(site, "verdict.app-journey.test", "exact", null, false);
            ProxyServer proxy = ProxyTestSupport.startProxy();
            cleanup.add(proxy::stop);
            ServerMain.adoptProxyServer(proxy);
            cleanup.add(() -> ServerMain.adoptProxyServer(previous));
            Poll.until("step 4: the site's verdict names its database", Duration.ofSeconds(10), () ->
                AttentionSubject.database(database.get(DatabaseModel.ID)).equals(AppHealth.siteReading(
                    Models.get(SiteModel.class).findById(site.get(SiteModel.ID))).cause()));
            String down = adminGet("/admin/instances/" + id + "/page/overview").body();
            assertThat(down).as("step 4: the band says visitors get an error page because of the database")
                .contains("Visitors get an error page")
                .contains("Its database app-journey-verdict-db is not running.");
            assertThat(down).as("step 4: it started, so Started names that start").contains(STARTED_FACT);
        } finally {
            for (int i = cleanup.size() - 1; i >= 0; i--) {
                cleanup.get(i).run();
            }
        }
    }

    /**
     * devbox on Daystrom: it died with its host's reboot while its crash policy was None, and its overview read only
     * "No cause was recorded". An errored workload's overview now says when it stopped, why nothing restarted it and
     * what to do, from the facts its record holds.
     */
    @Test
    void anErroredWorkloadSaysWhenItStoppedWhyNothingRestartedItAndWhatToDo() throws Exception {
        Row instance = instance("app-journey-errored");
        int id = instance.get(InstanceModel.ID);
        // The day as a cookieless English reader reads it: on the office clock, in words.
        String today = Wording.day(Reader.of(LocaleChain.ofTags("en"), Zenit.getMessageResolver()), Now.instant(),
            DateStyle.LONG);
        String page = "/admin/instances/" + id + "/page/overview";
        HostFixtures.LocalHostState localBefore = HostFixtures.captureLocal();
        try {
            // The local host runs apps: a host refusing the start would be the verdict instead.
            HostFixtures.makeLocalPlaceable(16L * 1024);

            // 1. devbox as it stands: the reconciler corrected it to error before causes were recorded, with no crash
            //    loop in its note, so its crash policy did not restart it then; a data step set Restart since. M017
            //    stored that correction on the record.
            policy(id, InstanceModel.CRASH_RESTART);
            converted(id, HohenheimActivityAction.RestartWithheld.BY_POLICY);
            assertThat(adminGet(page).body()).as("step 1: when it stopped, why nothing restarted it, what to do")
                .contains("app-journey-errored stopped without a stop anyone asked for and was not restarted on "
                    + today + ". Its crash policy did not restart it then; it does now. Restart it to bring it back.")
                .doesNotContain("No cause was recorded for this error");

            // 2. The same correction with crash-loop protection in its note: that is why nothing restarted it.
            converted(id, HohenheimActivityAction.RestartWithheld.BY_CRASH_LOOP);
            assertThat(adminGet(page).body()).as("step 2: crash-loop protection held it back")
                .contains("Crash-loop protection held back further restarts: find what makes it exit, then deploy"
                    + " or restart it.");

            // 3. A stamped exit (the cause every stop now stores) while its policy is still None: the policy is why,
            //    and setting it to restart is named beside starting it.
            policy(id, InstanceModel.CRASH_NONE);
            stopped(id, HohenheimActivityAction.WORKLOAD_EXITED, "137");
            assertThat(adminGet(page).body()).as("step 3: the stamped exit, its code and the policy")
                .contains("app-journey-errored exited unexpectedly and was not restarted (exit code 137) on " + today
                    + ". Its crash policy does not restart it: restart it to bring it back, or set its crash policy"
                    + " to Restart on crash.");

            // 4. A stamped crash loop says the protection held it back, whatever its policy says now.
            policy(id, InstanceModel.CRASH_RESTART);
            stopped(id, HohenheimActivityAction.WORKLOAD_CRASH_LOOPED, null);
            assertThat(adminGet(page).body()).as("step 4: a stamped crash loop")
                .contains("on " + today + ". Crash-loop protection held back further restarts");
            // 5. No automatic restart came of reading it: the record is still in error.
            assertThat(String.valueOf((Object) Models.get(InstanceModel.class).findById(id).get(InstanceModel.STATUS)))
                .as("step 5: nothing restarted it").isEqualTo(InstanceModel.STATUS_ERROR);
        } finally {
            HardDeletes.row(Models.get(InstanceModel.class), instance);
            localBefore.restore();
        }
    }

    private static void policy(int instanceId, String policy) {
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(instanceId))
            .assign(InstanceModel.CRASH_POLICY, policy).bypassBehaviours().updateAll();
    }

    @Test
    void theDetailsSayWhatTheStatusMeansAndPortsShowOnlyWhileHeld() throws Exception {
        Row instance = instance("app-journey-words");
        int id = instance.get(InstanceModel.ID);
        try {
            // 1. Never started and never checked: the Details card says so in words, not "Created" beside "Never
            //    checked against the host"; its Status is the band's verdict in one word, never the stored token's
            //    label, and never the band's whole sentence, which outgrew the card.
            Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
                .assign(InstanceModel.STATUS, InstanceModel.STATUS_CREATED)
                .assign(InstanceModel.STATUS_OBSERVED_AT, null).bypassBehaviours().updateAll();
            String created = adminGet("/admin/instances/" + id + "/page/overview").body();
            RecordHealth verdict = AppHealth.instances(false).read(Models.get(InstanceModel.class).findById(id),
                TenantConduits.operator());
            assertThat(statusFact(created))
                .as("step 1: the Status says the band's verdict in one word")
                .contains(AppParts.stateWord(verdict).resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver()))
                .doesNotContain("Not started yet");
            assertThat(created).as("step 1: a workload never started reads so, and when it was last checked")
                .contains("Last checked")
                .contains("Not yet")
                .doesNotContain("Status confirmed")
                .doesNotContain("Never checked against the host");

            // 2. It holds no port: no Ports card at all, never "holds no port claim".
            assertThat(created).as("step 2: no port, no Ports card")
                .doesNotContain("data-endpoint-port")
                .doesNotContain("holds no port claim")
                .doesNotContain("Public endpoint");

            // 3. A port it holds on a host that declares no public address: the card is "Ports", and the operator
            //    reads what is missing (no bare port) and where to set it: the host's own page.
            PortLedger.claim(ServerModel.localServerId(), null, 25566, "tcp", InstanceModel.MODEL_ID, id,
                "app journey");
            String ported = adminGet("/admin/instances/" + id + "/page/overview").body();
            assertThat(ported).as("step 3: the held port is listed under Ports")
                .contains("data-endpoint-port=\"25566\"")
                .contains(">Ports<")
                .contains("Port 25566 has no public address yet")
                .doesNotContain("<code>25566</code>");
            assertThat(ported).as("step 3: THE ASSERTION -- with a link to where the address is set")
                .contains("data-endpoint-fix=\"1\"").contains("Set the host's public address")
                .contains("/admin/" + HohenheimSlugs.SERVERS + "/" + ServerModel.localServerId());

            // 4. A port published on the loopback alone never asks for a public address: only the host reaches it.
            PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, id);
            PortLedger.claim(ServerModel.localServerId(), PublishedPort.LOOPBACK, 25567, "tcp",
                InstanceModel.MODEL_ID, id, "app journey");
            String loopback = adminGet("/admin/instances/" + id + "/page/overview").body();
            assertThat(loopback).as("step 4: the loopback port says only the host reaches it, with no fix link")
                .contains("Port 25567 is published on 127.0.0.1 only")
                .doesNotContain("data-endpoint-fix=\"1\"").doesNotContain("Port 25567 has no public address");
        } finally {
            PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, id);
            HardDeletes.row(Models.get(InstanceModel.class), instance);
        }
    }

    /** An application whose release is still building reads as on its way, never as stopped with a Start fix. */
    @Test
    void anApplicationWhoseBuildRunsReadsAsDeploying() {
        var instances = Models.get(InstanceModel.class);
        Row app = instances.createEmptyRow();
        app.set(InstanceModel.NAME, "app-journey-building");
        app.set(InstanceModel.KIND, "hohenheim:application");
        app.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("repository_url",
            "https://git.example.test/acme/app.git")));
        app.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        instances.save(app);
        int id = app.get(InstanceModel.ID);
        var builds = Models.get(BuildOperationModel.class);
        Row build = builds.createEmptyRow();
        try {
            // 1. Created with nothing building: it is not running.
            assertThat(headlineOf(id)).as("step 1: nothing builds, so it is not running")
                .isNotEqualTo("deploying");

            // 2. Its build runs: the band says a deploy is under way.
            build.set(BuildOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
            build.set(BuildOperationModel.FOR_ID, id);
            build.set(BuildOperationModel.BUILDER_KIND, BuildOperationModel.KIND_DOCKERFILE);
            build.set(BuildOperationModel.STATUS, BuildOperationModel.LIFECYCLE.stored(OperationStatus.RUNNING));
            builds.save(build);
            assertThat(headlineOf(id)).as("step 2: a running build reads as deploying").isEqualTo("deploying");

            // 3. The build ended without a release: not running again.
            build.set(BuildOperationModel.STATUS, BuildOperationModel.LIFECYCLE.stored(OperationStatus.FAILED));
            builds.save(build);
            assertThat(headlineOf(id)).as("step 3: an ended build is no deploy").isNotEqualTo("deploying");
        } finally {
            if (build.get(BuildOperationModel.ID) != null) {
                builds.delete(build.get(BuildOperationModel.ID));
            }
            HardDeletes.byId(instances, id);
        }
    }

    /**
     * An application runs as its serving release: the release's status is the app's, never its own record's, which
     * nothing starts (a live app read "Not running" beside the release serving its visitors).
     */
    @Test
    void anApplicationReadsAsItsServingRelease() throws Exception {
        var instances = Models.get(InstanceModel.class);
        HostFixtures.LocalHostState localBefore = HostFixtures.captureLocal();
        // A host that takes an application: what follows is about its runtime, never its placement.
        HostFixtures.makeLocalPlaceable(16L * 1024);
        HostFixtures.giveLocalVolumeQuota();
        Row app = instances.createEmptyRow();
        app.set(InstanceModel.NAME, "app-journey-released");
        app.set(InstanceModel.KIND, "hohenheim:application");
        app.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("repository_url",
            "https://git.example.test/acme/released.git")));
        app.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        int[] release = new int[1];
        try {
            instances.save(app);
            int id = app.get(InstanceModel.ID);
            // 1. Nothing serves yet and nothing ever did: it is not deployed yet.
            assertThat(headlineOf(id)).as("step 1: no release ever, so it is not deployed yet").isEqualTo("not_deployed");

            // 2. Its serving release runs: the app is live, and its overview says so.
            ApplicationReleases.inScopeUnchecked(id, () -> {
                Row row = instances.createEmptyRow();
                row.set(InstanceModel.NAME, "app-journey-released");
                row.set(InstanceModel.KIND, "hohenheim:release");
                row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine")));
                row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
                row.set(InstanceModel.RUNTIME_ROLE, InstanceModel.ROLE_SERVING);
                instances.save(row);
                release[0] = row.get(InstanceModel.ID);
            });
            status(release[0], InstanceModel.STATUS_RUNNING);
            assertThat(headlineOf(id)).as("step 2: the running release is the app's state")
                .isNotEqualTo("not_running");
            assertThat(AppHealth.instanceServes(instances.findById(id))).as("step 2: and it serves").isTrue();
            String live = adminGet("/admin/instances/" + id + "/page/overview").body();
            assertThat(live).as("step 2: the overview's band reads healthy, its resources running")
                .contains("data-cms-record-health=\"ok\"")
                .doesNotContain("It is not running, so it uses none.");

            // 2b. The Instances list draws and filters the release's status: the app is among the running ones, read
            //     as Running there, and no longer among the never-started.
            String running = adminGet("/admin/instances?filter.status=running").body();
            String drawn = null;
            for (String part : running.split("<pl-table-row")) {
                int end = part.indexOf("</pl-table-row>");
                if (end >= 0 && part.substring(0, end).contains("app-journey-released")) {
                    drawn = part.substring(0, end);
                }
            }
            assertThat(drawn).as("step 2b: Status 'Running' keeps the live app, its row drawing it as running")
                .isNotNull().contains("Running").doesNotContain("Not started yet");
            assertThat(adminGet("/admin/instances?filter.status=created").body())
                .as("step 2b: Status 'Not started yet' leaves it out").doesNotContain("app-journey-released");
            // 2c. Its Metrics and Console tabs are the release's: live, never "not running".
            assertThat(adminGet("/admin/instances/" + id + "/page/stats").body())
                .as("step 2c: the Metrics tab follows the running release").contains("<hh-instance-stats");
            assertThat(adminGet("/admin/instances/" + id + "/page/console").body())
                .as("step 2c: the Console tab attaches to the running release").contains("<pl-terminal");

            // 2d. The serving release was built from the app's saved configuration: nothing waits for a deploy.
            Row serving = instances.findById(release[0]);
            Map<String, Object> built = new LinkedHashMap<>(InstanceModel.settingsOf(serving));
            built.put("source_fingerprint",
                ApplicationReleases.configuredFingerprint(instances.findById(id), serving));
            serving.set(InstanceModel.SETTINGS, built);
            ApplicationReleases.inScopeUnchecked(id, () -> instances.save(serving));
            assertThat(headlineOf(id)).as("step 2d: a release built from the saved configuration is up to date")
                .isNotEqualTo("changes_not_live");

            // 2e. A saved change the release was not built from is not live yet: the overview says so, Deploy fixes it.
            Row edited = instances.findById(id);
            Map<String, Object> changed = new LinkedHashMap<>(InstanceModel.settingsOf(edited));
            changed.put("health_path", "/healthz");
            edited.set(InstanceModel.SETTINGS, changed);
            instances.save(edited);
            assertThat(headlineOf(id)).as("step 2e: the saved change waits for a deploy")
                .isEqualTo("changes_not_live");
            String pending = adminGet("/admin/instances/" + id + "/page/overview").body();
            assertThat(pending).as("step 2e: the overview's band asks for attention")
                .contains("data-cms-record-health=\"attention\"");

            // 2f. The Configuration tab is headed by the app's own name, like its other tabs.
            assertThat(adminGet("/admin/instances/" + id).body()).as("step 2f: the form tab names the app")
                .doesNotContain("Edit instance");

            // 3. The release stopped: so is the app.
            status(release[0], InstanceModel.STATUS_STOPPED);
            assertThat(headlineOf(id)).as("step 3: a stopped release is a stopped app").isEqualTo("stopped");
            assertThat(adminGet("/admin/instances?filter.status=stopped").body())
                .as("step 3: Status 'Stopped' now keeps it").contains("app-journey-released");
        } finally {
            if (release[0] != 0) {
                // A generated row is the application's to remove: its own scope, as the release engine deletes.
                ApplicationReleases.inScopeUnchecked(app.get(InstanceModel.ID),
                    () -> HardDeletes.byId(instances, release[0]));
            }
            if (app.get(InstanceModel.ID) != null) {
                HardDeletes.byId(instances, app.get(InstanceModel.ID));
            }
            localBefore.restore();
        }
    }

    private static String headlineOf(int instanceId) {
        return AppHealth.instances(false).read(Models.get(InstanceModel.class).findById(instanceId),
            TenantConduits.operator()).headline().key();
    }

    /** @return the Details card's Status fact of an overview page, its term through its value */
    private static String statusFact(String page) {
        int status = page.indexOf("widget-facts-term\">Status</dt>");
        assertThat(status).as("the overview draws a Status fact").isNotNegative();
        return page.substring(status, page.indexOf("</dd>", status));
    }

    /** @return a put-online run of this record left open, as one still starting it leaves it */
    private static Row openRun(int instanceId) {
        OperationRunModel runs = Models.get(OperationRunModel.class);
        Row run = runs.createEmptyRow();
        run.set(OperationRunModel.OPERATION, PutOnline.PUT_MACHINE_ONLINE.id().toString());
        run.set(OperationRunModel.INVOCATION_ID, UUID.randomUUID());
        run.set(OperationRunModel.SUBJECT_TYPE, InstanceOperations.INSTANCE.id().toString());
        run.set(OperationRunModel.SUBJECT_KEY, String.valueOf(instanceId));
        run.set(OperationRunModel.STATUS, OperationRunStatus.RUNNING.storageKey());
        run.set(OperationRunModel.PROGRESS, 0.0);
        run.set(OperationRunModel.STARTED_AT, Now.instant());
        return runs.save(run);
    }

    private static void status(int instanceId, String status) {
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(instanceId))
            .assign(InstanceModel.STATUS, status).bypassBehaviours().updateAll();
    }

    /** The Details card's Started fact, drawn only while a start is known. */
    private static final String STARTED_FACT = "widget-facts-term\">Started</dt>";

    /** Stamps a start on the record now, as a deploy's outcome stamp does. */
    private static void started(int instanceId) {
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(instanceId))
            .assign(InstanceModel.STARTED_AT, Now.instant()).bypassBehaviours().updateAll();
    }

    /** Stamps ERROR with its cause's stoppage as the operation guard's ERROR stamps do: on the row, then in history. */
    private static void stopped(int instanceId, HohenheimActivityAction cause, String detail) {
        InstanceModel.withStoppage(Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(instanceId))
            .assign(InstanceModel.STATUS, InstanceModel.STATUS_ERROR), cause, detail).bypassBehaviours().updateAll();
        History.event(Models.get(InstanceModel.class), instanceId, InstanceModel.stoppageFact(cause, detail));
    }

    /** Stores a reconciler correction to ERROR from before causes, as M017 converts one. */
    private static void converted(int instanceId, HohenheimActivityAction.RestartWithheld withheld) {
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(instanceId))
            .assign(InstanceModel.STATUS, InstanceModel.STATUS_ERROR)
            .assign(InstanceModel.STOPPAGE_CAUSE, HohenheimActivityAction.RECONCILED.id().getPath())
            .assign(InstanceModel.STOPPAGE_DETAIL, null)
            .assign(InstanceModel.STOPPAGE_AT, Now.instant())
            .assign(InstanceModel.RESTART_WITHHELD, InstanceModel.keyOf(withheld))
            .bypassBehaviours().updateAll();
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
