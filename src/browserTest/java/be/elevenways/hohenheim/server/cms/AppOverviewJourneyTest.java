package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.instance.InstanceStats;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.Poll;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ActivityVisibility;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.widget.common.data.UsageData;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
            assertThat(adminGet("/admin/instances?q=app-journey-served").body())
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

    @Test
    void aWorkloadsOverviewSaysWhatItUsesSinceWhenAndWhatItHolds() throws Exception {
        List<Runnable> cleanup = new ArrayList<>();
        Row instance = instance("app-journey-details");
        cleanup.add(() -> HardDeletes.row(Models.get(InstanceModel.class), instance));
        LocaleChain en = LocaleChain.ofTags("en");
        try {
            // 1. Memory and CPU are read live, while someone watches the Metrics tab: a stopped workload uses none, and
            //    a running one nobody watches is not measured, in words, never a zero.
            List<UsageData> idle = InstanceOverview.liveUsage(instance, List.of(), en, Zenit.getMessageResolver());
            assertThat(idle).as("step 1: a stopped workload's memory and CPU are not measured")
                .allMatch(usage -> !usage.measured());
            assertThat(idle.get(0).unmeasuredReason()).as("step 1: because it does not run")
                .isEqualTo("It is not running, so it uses none.");
            instance.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
            Models.get(InstanceModel.class).save(instance);
            assertThat(InstanceOverview.liveUsage(instance, List.of(), en, Zenit.getMessageResolver()).get(1)
                .unmeasuredReason()).as("step 1: or because nobody watches it now")
                .isEqualTo("Read live while its Metrics tab is open; nobody has it open now.");

            // 2. With samples held, memory is measured against its limit and CPU is the samples' mean (the first,
            //    which carries no CPU figure, left out) against the cores the daemon reports.
            long mib = 1024L * 1024L;
            List<InstanceStats.Sample> samples = List.of(
                new InstanceStats.Sample(1_000L, 0, 100 * mib, 1024 * mib, 0, 0, 2),
                new InstanceStats.Sample(2_000L, 24.0, 200 * mib, 1024 * mib, 0, 0, 2),
                new InstanceStats.Sample(3_000L, 36.0, 300 * mib, 1024 * mib, 0, 0, 2));
            List<UsageData> live = InstanceOverview.liveUsage(instance, samples, en, Zenit.getMessageResolver());
            assertThat(live.get(0).used()).as("step 2: memory is the newest reading").isEqualTo(300 * mib);
            assertThat(live.get(0).max()).as("step 2: of its limit").isEqualTo(1024 * mib);
            assertThat(live.get(1).used()).as("step 2: CPU is the mean, the first sample left out").isEqualTo(30L);
            assertThat(live.get(1).max()).as("step 2: of its two cores").isEqualTo(200L);
            assertThat(live.get(1).maxLabel()).as("step 2: said as cores").isEqualTo("2 cores");

            // 3. The overview draws Memory, Root disk and CPU, and Details say when it started
            //    (its last start's activity row), the database it uses with that database's state, and its backups.
            ActivityLog.record(Models.get(InstanceModel.class), instance.get(InstanceModel.ID),
                HohenheimActivityAction.DEPLOYED, "app-journey-details");
            assertThat(InstanceOverview.lastStartOf(instance.get(InstanceModel.ID)))
                .as("step 3: its last start is the newest start row").isNotNull();
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

            // 4. Recent folds one batch to one row (ActivitySources.onePerCommand): an SFTP session's uploads read as
            //    "Uploaded 2 files", in the file verb's own batch words.
            ActivityLog.inBatch("sftp:app-journey", () -> {
                ActivityLog.record(Models.get(InstanceModel.class), instance.get(InstanceModel.ID),
                    HohenheimActivityAction.FILES_UPLOAD, "/data/a.txt");
                ActivityLog.record(Models.get(InstanceModel.class), instance.get(InstanceModel.ID),
                    HohenheimActivityAction.FILES_UPLOAD, "/data/b.txt");
            });
            assertThat(HohenheimActivityAction.FILES_UPLOAD.batchLabel().withArg("count", 312)
                .resolve(en, Zenit.getMessageResolver())).as("step 4: a batch of uploads reads in words")
                .isEqualTo("Uploaded 312 files");
            assertThat(HohenheimActivityAction.FILES_DELETE.batchLabel().withArg("count", 1)
                .resolve(LocaleChain.ofTags("nl"), Zenit.getMessageResolver())).as("step 4: in Dutch, one")
                .isEqualTo("1 bestand verwijderd");
            assertThat(HohenheimActivityAction.DEPLOYED.batchLabel())
                .as("step 4: a verb written one at a time has none")
                .isNull();
            String recent = adminGet("/admin/instances/" + instance.get(InstanceModel.ID) + "/page/overview").body();
            assertThat(recent).as("step 4: the Recent card names the batch once").contains("Uploaded 2 files");
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
            ActivityLog.record(Models.get(InstanceModel.class), id, HohenheimActivityAction.DEPLOYED, null);
            assertThat(InstanceOverview.lastStartOf(id)).as("step 1: its last start is named").isNotNull();

            // 2. Its next start fails: the stored status says error, the verdict reads a start that failed with why,
            //    and Started names no older start beside it.
            ActivityLog.record(Models.get(InstanceModel.class), id, HohenheimActivityAction.WORKLOAD_START_FAILED,
                "image app-journey:missing was not found");
            status(id, InstanceModel.STATUS_ERROR);
            String failed = adminGet("/admin/instances/" + id + "/page/overview").body();
            assertThat(failed).as("step 2: the band reads a failed start in words, the failure's text after them")
                .contains("Could not start")
                .containsSubsequence("It never got to run.", "Technically: image app-journey:missing was not found")
                .doesNotContain("What refused it")
                .doesNotContain("Stopped after an error");
            assertThat(InstanceOverview.lastStartOf(id)).as("step 2: no start is named after a failed one").isNull();
            // A record left claiming running beside that failed start still names no start.
            status(id, InstanceModel.STATUS_RUNNING);
            assertThat(InstanceOverview.lastStartOf(id)).as("step 2: nor for a stale running claim").isNull();

            // 3. Hohenheim correcting a stored status is bookkeeping: its rows stay out of Recent (the full log lists
            //    them through the internal filter), so Recent reads what happened, the failed start.
            assertThat(HohenheimActivityAction.RECONCILED.visibility()).as("step 3: the reconcile verb is internal")
                .isEqualTo(ActivityVisibility.INTERNAL);
            ActivityLog.record(Models.get(InstanceModel.class), id, HohenheimActivityAction.RECONCILED,
                "running -> stopped");
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
            ActivityLog.record(Models.get(InstanceModel.class), id, HohenheimActivityAction.DEPLOYED, null);
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
            assertThat(InstanceOverview.lastStartOf(id)).as("step 4: it started, so Started names that start")
                .isNotNull();
        } finally {
            for (int i = cleanup.size() - 1; i >= 0; i--) {
                cleanup.get(i).run();
            }
        }
    }

    @Test
    void theDetailsSayWhatTheStatusMeansAndPortsShowOnlyWhileHeld() throws Exception {
        Row instance = instance("app-journey-words");
        int id = instance.get(InstanceModel.ID);
        try {
            // 1. Never started and never checked: the Details card says so in words, not "Created" beside "Never
            //    checked against the host"; its Status is the band's verdict, never the stored token's label.
            Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
                .assign(InstanceModel.STATUS, InstanceModel.STATUS_CREATED)
                .assign(InstanceModel.STATUS_OBSERVED_AT, null).bypassBehaviours().updateAll();
            String created = adminGet("/admin/instances/" + id + "/page/overview").body();
            int status = created.indexOf("widget-facts-term\">Status</dt>");
            String verdict = AppHealth.instances(false).read(Models.get(InstanceModel.class).findById(id),
                TenantConduits.operator()).headline().resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver());
            assertThat(created.substring(status, created.indexOf("</dd>", status)))
                .as("step 1: the Status reads the band's verdict").contains(verdict)
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
            //    reads why the port reaches no further than the host's own network.
            PortLedger.claim(ServerModel.localServerId(), null, 25566, "tcp", InstanceModel.MODEL_ID, id,
                "app journey");
            String ported = adminGet("/admin/instances/" + id + "/page/overview").body();
            assertThat(ported).as("step 3: the held port is listed under Ports")
                .contains("data-endpoint-port=\"25566\"")
                .contains(">Ports<")
                .contains("This host has no public address set");
        } finally {
            PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, id);
            HardDeletes.row(Models.get(InstanceModel.class), instance);
        }
    }

    private static void status(int instanceId, String status) {
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(instanceId))
            .assign(InstanceModel.STATUS, status).bypassBehaviours().updateAll();
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
