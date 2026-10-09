package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.OnboardingStage;
import be.elevenways.hohenheim.OnboardingState;
import be.elevenways.hohenheim.OnboardingStep;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.database.ControlPlaneBackups;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.task.IsolationFindings;
import be.elevenways.hohenheim.server.task.VerifyWorkloadIsolation;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.HealthTone;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.TaskCatalog;
import be.elevenways.zenit.common.task.TaskDescriptor;
import be.elevenways.zenit.common.task.TaskStatus;
import be.elevenways.zenit.common.task.orm.SystemTaskHistoryModel;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static be.elevenways.hohenheim.test.ProxyTestSupport.addDomain;
import static be.elevenways.hohenheim.test.ProxyTestSupport.setupInstanceSite;
import static be.elevenways.hohenheim.test.ProxyTestSupport.setupSite;
import static be.elevenways.hohenheim.test.ProxyTestSupport.startProxy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The admin dashboard shows each problem once, at its root, with its one action: an open checklist step presents the
 * attention item stating its stage instead of the band repeating it, and an app its host holds back folds under that
 * host, which says how many apps wait for it, while the app's own verdict stays its own. Once the first app is online
 * the checklist retires and what its open steps stood for is the band's (board Main), beside the count tiles.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class DashboardAttentionJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "d8-fold-";
    private static final LocaleChain EN = LocaleChain.ofTags("en");

    /** Its own database: whether anything is online retires the checklist, and other classes leave live sites. */
    @BeforeAll
    static void seed() throws Exception {
        freshSeededDatabase();
    }

    @Test
    @Timeout(90)
    void eachProblemIsShownOnceAtItsRoot() throws Exception {
        HostFixtures.LocalHostState captured = HostFixtures.captureLocal();
        ProxyServer previous = ServerMain.getProxyServer();
        ProxyServer proxy = null;
        List<Row> instances = new ArrayList<>();
        List<Row> sites = new ArrayList<>();
        int local = ServerModel.localServerId();
        AttentionSubject localHost = AttentionSubject.host(local);
        try {
            // 1. A fresh install: the local host is enrolled but not admitted. Enrolment is done and names the host;
            //    admission is the one open host step, presenting the host's own item (its words and Check and admit),
            //    and the band does not repeat it, nor the backup destination the checklist asks for.
            HostFixtures.blockLocal();
            int baseline = heldBack(local);
            DashboardAttention.Reading fresh = DashboardAttention.read();
            OnboardingStep enrol = step(fresh, OnboardingStage.HOST);
            assertThat(enrol.state()).as("step 1: an enrolled host completes the enrolment step")
                .isEqualTo(OnboardingState.DONE);
            assertThat(say(enrol.detail())).as("step 1: naming the host, never repeating the admission step")
                .startsWith(ServerModel.nameOf(local) + ", ")
                .doesNotContain("admitted");
            OnboardingStep admit = step(fresh, OnboardingStage.ADMISSION);
            AttentionItem hostItem = rootOf(AttentionCollector.collect(), localHost);
            assertThat(hostItem).as("step 1: the unfolded collector still names the host").isNotNull();
            assertThat(hostItem.stage()).as("step 1: as the admission stage's condition")
                .isEqualTo(OnboardingStage.ADMISSION);
            assertThat(say(hostItem.title())).as("step 1: a host never admitted cannot run apps yet")
                .isEqualTo(ServerModel.nameOf(local) + " cannot run apps yet");
            assertThat(admit.state()).as("step 1: admission is open").isEqualTo(OnboardingState.BLOCKED);
            assertThat(say(admit.detail())).as("step 1: in the host item's words").isEqualTo(say(hostItem.detail()));
            assertThat(say(admit.action())).as("step 1: with its one action").isEqualTo("Check and admit");
            assertThat(admit.target().toUrl()).as("step 1: leading to the host")
                .isEqualTo(hostItem.target().toUrl());
            assertThat(rootOf(fresh.attention(), localHost)).as("step 1: the band does not repeat the host").isNull();
            assertThat(fresh.attention()).as("step 1: nor any item a step presents")
                .noneMatch(item -> item.stage() == OnboardingStage.ADMISSION)
                .noneMatch(item -> item.stage() == OnboardingStage.BACKUPS
                    && ControlPlaneBackups.configuredDestinationName() == null);
            if (ControlPlaneBackups.configuredDestinationName() == null) {
                assertThat(say(step(fresh, OnboardingStage.BACKUPS).action()))
                    .as("step 1: the backups step offers the band item's action").isEqualTo("Choose a backup target");
            }

            // 2. Two apps on the waiting host, each with a site the proxy routes: neither can start, so both sites
            //    answer an error page. Each app keeps its own verdict, whose cause half names the host.
            for (int i = 1; i <= 2; i++) {
                Row instance = instance(PREFIX + "app-" + i, local);
                instances.add(instance);
                Row site = setupInstanceSite(PREFIX + "site-" + i, PREFIX + "site-" + i, instance.get(InstanceModel.ID));
                sites.add(site);
                addDomain(site, "app-" + i + ".d8.test", "exact", null, false);
            }
            proxy = startProxy();
            ServerMain.adoptProxyServer(proxy);
            for (int i = 0; i < 2; i++) {
                Row site = Models.get(SiteModel.class).findById(sites.get(i).get(SiteModel.ID));
                AppHealth.Verdict verdict = AppHealth.siteReading(site);
                assertThat(verdict.health().tone()).as("step 2: the site keeps its verdict: an error page")
                    .isEqualTo(HealthTone.BROKEN);
                assertThat(verdict.cause()).as("step 2: caused by its host").isEqualTo(localHost);
                Row instance = Models.get(InstanceModel.class).findById(instances.get(i).get(InstanceModel.ID));
                assertThat(say(AppHealth.instances(false).read(instance, TenantConduits.operator()).headline()))
                    .as("step 2: the app's own page still says it cannot start")
                    .isEqualTo(PREFIX + "app-" + (i + 1) + " cannot start yet");
            }
            assertThat(heldBack(local)).as("step 2: the host holds both apps back").isEqualTo(baseline + 2);
            List<AttentionItem> unfolded = AttentionCollector.collect();
            assertThat(mine(unfolded)).as("step 2: unfolded, each site has its own item, caused by the host")
                .hasSize(2).allMatch(item -> localHost.equals(item.causedBy()));

            // 3. Folded: the sites' items leave the band, and the host's step says how many apps wait for it.
            DashboardAttention.Reading waiting = DashboardAttention.read();
            assertThat(mine(waiting.attention())).as("step 3: the band draws no app the host holds back").isEmpty();
            assertThat(say(step(waiting, OnboardingStage.ADMISSION).heldBack()))
                .as("step 3: the admission step names what the host holds back")
                .isEqualTo((baseline + 2) + " apps wait for it");
            assertThat(say(rootOf(unfolded, localHost).heldBack())).as("step 3: the host item says the same")
                .isEqualTo((baseline + 2) + " apps wait for it");

            // 4. Admitted, but its posture still refuses these apps (trusted only): the host is still their root,
            //    now in the gate's own words with a way to the host, and their items still fold under it.
            Row admittedOnly = Models.get(ServerModel.class).findById(local);
            admittedOnly.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
            admittedOnly.set(ServerModel.POSTURE, ServerModel.POSTURE_TRUSTED_ONLY);
            Models.get(ServerModel.class).save(admittedOnly);
            AttentionItem refusing = rootOf(AttentionCollector.collect(), localHost);
            assertThat(refusing).as("step 4: an admitted host that still holds apps back is their root").isNotNull();
            assertThat(say(refusing.detail())).as("step 4: saying why in the apps' own verdict words")
                .isEqualTo(say(AppHealth.siteHealth(Models.get(SiteModel.class).findById(sites.get(0).get(SiteModel.ID)))
                    .detail()));
            assertThat(say(refusing.action())).as("step 4: leading to the host").isEqualTo("Open " + ServerModel.nameOf(local));
            DashboardAttention.Reading refused = DashboardAttention.read();
            assertThat(mine(refused.attention())).as("step 4: the apps' items fold under it").isEmpty();
            assertThat(say(step(refused, OnboardingStage.ADMISSION).heldBack()))
                .as("step 4: the open admission step presents it, with what it holds back")
                .isEqualTo((baseline + 2) + " apps wait for it");

            // 5. Placeable: admission is done in its own words, no item names the host, and the sites' items come
            //    back as their own problem (their apps are stopped, nothing holds them back any more).
            HostFixtures.makeLocalPlaceable(16L * 1024);
            DashboardAttention.Reading admitted = DashboardAttention.read();
            OnboardingStep done = step(admitted, OnboardingStage.ADMISSION);
            assertThat(done.state()).as("step 5: admission is done").isEqualTo(OnboardingState.DONE);
            assertThat(done.detail().key()).as("step 5: in its own done words").isEqualTo("checklist_admit_done");
            assertThat(done.heldBack()).as("step 5: holding nothing back").isNull();
            assertThat(rootOf(AttentionCollector.collect(), localHost)).as("step 5: no item names the host").isNull();
            assertThat(mine(admitted.attention())).as("step 5: each site is its own app's problem again, caused by its "
                    + "stopped workload, which raises no item of its own, so nothing folds it").hasSize(2)
                .allMatch(item -> item.causedBy() != null && InstanceModel.MODEL_ID.equals(item.causedBy().model()));

            // 6. Its memory reading goes stale (measured 40 days ago, DEP9's Starfleet host): the chooser refuses it,
            //    so admission is open again and its step presents the host's item, with the fresh check that clears it
            //    and the way to the host's Overview; the band does not repeat it.
            HostPreflight.store(ServerModel.MODE_LOCAL, new HostPreflight.Report(List.of(),
                Map.of(HostPreflight.MEM_TOTAL_FACT, 16L * 1024 * 1024 * 1024), true,
                Now.instant().minus(Duration.ofDays(40)), null));
            DashboardAttention.Reading stale = DashboardAttention.read();
            OnboardingStep reopened = step(stale, OnboardingStage.ADMISSION);
            assertThat(reopened.state()).as("step 6: a host the gate refuses does not complete admission")
                .isEqualTo(OnboardingState.BLOCKED);
            AttentionItem staleRoot = rootOf(AttentionCollector.collect(), localHost);
            assertThat(staleRoot).as("step 6: the host is raised").isNotNull();
            assertThat(staleRoot.detail().key()).as("step 6: for its stale reading, in the gate's words")
                .isEqualTo("host_capacity_unproven");
            assertThat(say(staleRoot.title())).as("step 6: an admitted host the gate refuses takes no new apps")
                .isEqualTo(ServerModel.nameOf(local) + " takes no new apps");
            assertThat(say(reopened.detail())).as("step 6: the step presents it").isEqualTo(say(staleRoot.detail()));
            assertThat(say(reopened.action())).as("step 6: with a fresh check").isEqualTo("Check again");
            assertThat(reopened.target().toUrl()).as("step 6: leading to the host's Overview")
                .isEqualTo("/admin/servers/" + local + "/open");
            assertThat(rootOf(stale.attention(), localHost)).as("step 6: the band does not repeat it").isNull();

            // 7. The first app goes online (a website serving its visitors, board Main): the checklist retires, and
            //    what its open steps stood for is the band's from then on, each once: the host that takes no new apps
            //    and, while no off-host destination is chosen, the backups that stay on this machine.
            Row online = setupSite("hohenheim:static", PREFIX + "live", PREFIX + "live", Map.of("root_path", "/tmp"));
            sites.add(online);
            addDomain(online, "live.d8.test", "exact", null, false);
            assertThat(AppHealth.anyOnline()).as("step 7: a serving website is something online").isTrue();
            DashboardAttention.Reading retired = DashboardAttention.read();
            assertThat(retired.checklist()).as("step 7: the checklist retires once the first app is online").isEmpty();
            List<AttentionItem> hostItems = retired.attention().stream()
                .filter(item -> localHost.equals(item.about())).toList();
            assertThat(hostItems).as("step 7: the host the step presented is the band's item, once").hasSize(1);
            assertThat(say(hostItems.get(0).title())).as("step 7: still saying it takes no new apps")
                .isEqualTo(ServerModel.nameOf(local) + " takes no new apps");
            if (ControlPlaneBackups.configuredDestinationName() == null) {
                assertThat(retired.attention().stream().map(item -> say(item.title())).toList())
                    .as("step 7: the backups the step asked for are the band's item")
                    .containsOnlyOnce("Backups stay on this machine");
            }
            String dashboard = adminGet("/admin/dashboard").body();
            assertThat(dashboard).as("step 7: the rendered dashboard draws no checklist")
                .doesNotContain("data-onboarding-steps")
                .as("step 7: and the host in the band").contains(ServerModel.nameOf(local) + " takes no new apps");

            // 8. The count tiles (board Main): Apps, Hosts, Certificates and Backups, each with the line saying what
            //    its count holds, only where a fact backs it.
            assertThat(tile(dashboard, "apps")).as("step 8: the apps, live and with a problem, by their verdicts")
                .contains("href=\"/admin/apps\"").contains(">3<").contains("1 live, 2 with a problem");
            assertThat(tile(dashboard, "hosts")).as("step 8: the hosts, tallied by their standing")
                .contains("href=\"/admin/servers\"").contains(">1<").contains("1 refuses new apps");
            assertThat(tile(dashboard, "certificates")).as("step 8: no certificate, so no line about one")
                .contains("href=\"/admin/certificates\"").contains(">0<").doesNotContain("class=\"description\"");
            assertThat(tile(dashboard, "backups")).as("step 8: nothing backed up says so")
                .contains("href=\"/admin/instance-backups\"").contains("No app or database is backed up yet");
            // The one count the sidebar carries (board Main): the apps with a problem, as the Apps tile says them;
            // the inbox's unread alerts no longer badge Activity.
            Panel admin = Objects.requireNonNull(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN), "the admin panel");
            AccessContext operator = TenantConduits.operator();
            assertThat(admin.entryBySlug(AppParts.SLUG).navBadge(operator))
                .as("step 8: the sidebar badges Apps with its 2 problems, the tile's own count").isEqualTo(2L);
            assertThat(admin.entryBySlug("inbox").navBadge(operator))
                .as("step 8: the inbox's unread alerts no longer badge Activity").isNull();
            assertThat(dashboard).as("step 8: the tiles the board replaced are gone")
                .doesNotContain("href=\"/admin/access-lists\"").doesNotContain("Active bans");

            // 9. A root the band draws (a second host waiting while one is admitted, board Main) folds what it holds
            //    back too; a consequence whose root nobody shows stays, so a fold never hides a problem.
            AttentionSubject other = AttentionSubject.host(local + 1000);
            AttentionItem root = new AttentionItem(AttentionSeverity.WARNING, "server", Microcopy.literal("root"),
                null, null, null).about(other, Microcopy.literal("1 app waits for it"));
            AttentionItem held = new AttentionItem(AttentionSeverity.ERROR, "globe", Microcopy.literal("held"),
                null, null, null).causedBy(other);
            AttentionItem orphan = new AttentionItem(AttentionSeverity.ERROR, "globe", Microcopy.literal("orphan"),
                null, null, null).causedBy(AttentionSubject.host(local + 2000));
            assertThat(DashboardAttention.fold(admitted.checklist(), List.of(root, held, orphan)).attention())
                .as("step 9: the root stays, its consequence folds, a rootless consequence stays")
                .containsExactly(root, orphan);
        } finally {
            ServerMain.adoptProxyServer(previous);
            if (proxy != null) {
                proxy.stop();
            }
            for (Row site : sites) {
                HardDeletes.row(Models.get(SiteModel.class), Models.get(SiteModel.class).findById(site.get(SiteModel.ID)));
            }
            for (Row instance : instances) {
                HardDeletes.row(Models.get(InstanceModel.class), instance);
            }
            captured.restore();
        }
    }

    @Test
    @Timeout(120)
    void oneCauseIsOneItemCarryingItsRootsAction() throws Exception {
        HostFixtures.LocalHostState captured = HostFixtures.captureLocal();
        ProxyServer previous = ServerMain.getProxyServer();
        Boolean forceHttps = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Proxy.FORCE_HTTPS);
        ProxyServer proxy = null;
        List<Row> instances = new ArrayList<>();
        List<Row> sites = new ArrayList<>();
        List<Row> hosts = new ArrayList<>();
        SystemTaskHistoryModel registeredHistory = null;
        try {
            // Nothing holds an app back here: the local host takes new apps, so every root below is the app's own.
            HostFixtures.makeLocalPlaceable(16L * 1024);
            int local = ServerModel.localServerId();
            Row crashed = instance(PREFIX + "crashed", local);
            crashed.set(InstanceModel.STATUS, InstanceModel.STATUS_ERROR);
            Models.get(InstanceModel.class).save(crashed);
            instances.add(crashed);
            Row shop = setupInstanceSite(PREFIX + "shop", PREFIX + "shop", crashed.get(InstanceModel.ID));
            sites.add(shop);
            addDomain(shop, "shop.d11.test", "exact", null, false);
            Row application = application(PREFIX + "api", local);
            instances.add(application);
            Row api = setupInstanceSite(PREFIX + "api-site", PREFIX + "api-site", application.get(InstanceModel.ID));
            sites.add(api);
            addDomain(api, "api.d11.test", "exact", null, false);
            failedRelease(application.get(InstanceModel.ID), "the health probe never answered on port 3000");
            proxy = startProxy();
            ServerMain.adoptProxyServer(proxy);
            AttentionSubject workload = AttentionSubject.instance(crashed.get(InstanceModel.ID));

            // 1. A crashed workload behind a website (D10a's Shop): the site's verdict names the workload as its
            //    cause, so unfolded there are two items, the workload's (the root, saying what it keeps from its
            //    site's visitors) and the site's (caused by it)...
            assertThat(AppHealth.siteReading(fresh(shop)).cause())
                .as("step 1: the site's verdict names its workload as the cause").isEqualTo(workload);
            List<AttentionItem> unfolded = AttentionCollector.collect();
            AttentionItem root = rootOf(unfolded, workload);
            assertThat(root).as("step 1: the crashed workload is a root").isNotNull();
            assertThat(say(root.title())).as("step 1: titled by what happened, in the verdict's words")
                .isEqualTo(PREFIX + "crashed stopped after an error");
            assertThat(root.title().resolve(LocaleChain.ofTags("nl"), Zenit.getMessageResolver()))
                .as("step 1: in Dutch too").isEqualTo(PREFIX + "crashed is gestopt na een fout");
            assertThat(say(root.action())).as("step 1: with the workload's action").isEqualTo("Open the console");
            assertThat(say(root.heldBack())).as("step 1: saying what it keeps from its site's visitors")
                .isEqualTo("Visitors of its site get an error page");
            assertThat(causedBy(unfolded, workload)).as("step 1: unfolded, the site's item is caused by it")
                .singleElement().satisfies(item -> assertThat(say(item.title()))
                    .as("step 1: naming the app as the Apps list does, its workload's name (D13a)")
                    .isEqualTo("Visitors of " + PREFIX + "crashed get an error page"));

            // 2. ...and folded, the band draws the cause once: the workload's item with its action, never the site's.
            List<AttentionItem> band = DashboardAttention.read(unfolded, true).attention();
            assertThat(rootOf(band, workload)).as("step 2: the band keeps the workload's item").isNotNull();
            assertThat(causedBy(band, workload)).as("step 2: and folds the site's error page under it").isEmpty();
            String dashboard = adminGet("/admin/dashboard").body();
            assertThat(dashboard).as("step 2: the rendered dashboard says it once, at its root")
                .contains("Visitors of its site get an error page")
                .doesNotContain("Visitors of " + PREFIX + "crashed get an error page");

            // 3. A failed deploy of an application that does not run is the same shape: the deploy's item is the
            //    root, with its way to the deploy, and the application's site folds under it.
            AttentionSubject deployed = AttentionSubject.instance(application.get(InstanceModel.ID));
            AttentionItem deploy = rootOf(unfolded, deployed);
            assertThat(deploy).as("step 3: the failed deploy is a root").isNotNull();
            assertThat(say(deploy.title())).as("step 3: titled by what happened")
                .isEqualTo(PREFIX + "api's last deploy failed");
            assertThat(say(deploy.action())).as("step 3: with the deploy's action").isEqualTo("See the deploy");
            assertThat(say(deploy.detail())).as("step 3: saying why it failed")
                .isEqualTo("the health probe never answered on port 3000");
            assertThat(causedBy(unfolded, deployed)).as("step 3: unfolded, its site's item names it").hasSize(1);
            assertThat(causedBy(band, deployed)).as("step 3: folded, it does not").isEmpty();

            // 4. A stopped workload raises no item of its own, so its site's error page is the only item, never
            //    hidden: a fold needs a shown root.
            crashed.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
            Models.get(InstanceModel.class).save(crashed);
            List<AttentionItem> stopped = DashboardAttention.read(AttentionCollector.collect(), true).attention();
            assertThat(rootOf(stopped, workload)).as("step 4: a stopped workload is no item of its own").isNull();
            assertThat(causedBy(stopped, workload)).as("step 4: so its site's error page stays, once").hasSize(1);

            // 5. An address forced to HTTPS without a certificate is the root of its site's error page: the site's
            //    verdict names that address, and the address's own item is about it.
            Row docs = setupSite("hohenheim:static", PREFIX + "docs", PREFIX + "docs", Map.of("root_path", "/tmp"));
            sites.add(docs);
            Row forced = forcedDomain(docs, "docs.d11.test");
            AttentionSubject address = AttentionSubject.address(forced.get(SiteDomainModel.ID));
            assertThat(AppHealth.siteReading(fresh(docs)).cause())
                .as("step 5: the site's verdict names the forced address as its cause").isEqualTo(address);
            ServerMain.adoptProxyServer(null);
            List<AttentionItem> forcedItems = new ArrayList<>();
            ProxyAttention.forcedWithoutCertificate(forcedItems);
            ServerMain.adoptProxyServer(proxy);
            assertThat(rootOf(forcedItems, address)).as("step 5: the address's own item is that root").isNotNull();

            // 6. HTTPS cannot be served at all (no certificate is stored): one item says why in words, and names
            //    each site sent to HTTPS by what sends it there, never claiming a site forces what the setting
            //    forces (D10a: a catch-all and a certified name listed as sites "that force SSL").
            Row catchAll = setupSite("hohenheim:static", PREFIX + "catch-all", PREFIX + "catch-all",
                Map.of("root_path", "/tmp"));
            sites.add(catchAll);
            addDomain(catchAll, "**.d11.test", "wildcard", null, false);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.FORCE_HTTPS, true);
            proxy.reload();
            List<AttentionItem> https = new ArrayList<>();
            ProxyAttention.httpsUnavailableWithForceSsl(https);
            assertThat(https).as("step 6: HTTPS that cannot be served raises one item").hasSize(1);
            assertThat(say(https.get(0).detail())).as("step 6: saying why in words")
                .isEqualTo("No certificate is stored yet, so nothing can be answered over HTTPS.");
            assertThat(say(https.get(0).heldBack())).as("step 6: naming each site by what sends it to HTTPS")
                .isEqualTo("Visitors of " + PREFIX + "docs get an error page: their addresses force HTTPS. So do "
                    + "visitors of " + PREFIX + "catch-all: the Force HTTPS setting sends them there.");
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.FORCE_HTTPS, false);
            List<AttentionItem> ownOnly = new ArrayList<>();
            ProxyAttention.httpsUnavailableWithForceSsl(ownOnly);
            assertThat(say(ownOnly.get(0).heldBack())).as("step 6: without the setting only the forcing site is named")
                .isEqualTo("Visitors of " + PREFIX + "docs get an error page: their addresses force HTTPS.");

            // 7. A failing task reads by its worded name, with why its last run failed (never a class name or a
            //    stack trace) and the way to that run, whose page offers Run now.
            if (Models.get(SystemTaskHistoryModel.MODEL_ID) == null) {
                registeredHistory = new SystemTaskHistoryModel(Datasources.getDefault());
                Models.registerInstance(registeredHistory);
            }
            // The run's error is what the scheduler stores of a real sweep failure: its class, message and trace.
            IsolationFindings.IsolationUnresolved unresolved = catchThrowableOfType(
                IsolationFindings.IsolationUnresolved.class,
                () -> VerifyWorkloadIsolation.report(List.of(new VerifyWorkloadIsolation.HostOutcome("local", false,
                    List.of(), List.of(), List.of(), List.of("per-workload enforcement is off "
                        + "(security.nftables_enabled); 1 workload network can be neither verified nor repaired"),
                    IsolationFindings.enforcementOff(1)))).publish());
            IsolationFindings.forgetTransitionStateForTest(VerifyWorkloadIsolation.SWEEP);
            Row run = failedRun(VerifyWorkloadIsolation.ID.toString(), unresolved.getClass().getName() + ": "
                + unresolved.getMessage() + "\n    at be.elevenways.Example.run(Example.java:1)");
            List<AttentionItem> tasks = new ArrayList<>();
            AttentionCollector.failedTasks(tasks);
            AttentionItem task = tasks.stream().filter(item -> item.target() != null && item.target().toUrl()
                .equals("/admin/task-runs/" + run.get(SystemTaskHistoryModel.ID) + "/open")).findFirst().orElse(null);
            assertThat(task).as("step 7: the failed run raises an item leading to that run").isNotNull();
            assertThat(say(task.title())).as("step 7: titled by the task's worded name").isEqualTo("Check app isolation failed");
            assertThat(task.title().resolve(LocaleChain.ofTags("nl"), Zenit.getMessageResolver()))
                .as("step 7: in Dutch too").isEqualTo("Isolatie van apps controleren is mislukt");
            assertThat(say(task.detail())).as("step 7: with the finding in words, never the sweep's tokens")
                .isEqualTo("Could not confirm that what runs on local is kept apart: per-app firewall rules are "
                    + "switched off there, so its 1 app network can be neither checked nor repaired")
                .doesNotContain("UNCONFIRMED").doesNotContain("security.nftables_enabled");
            assertThat(unresolved.findings()).as("step 7: the token alerts and tests key on stays in the raw reading")
                .singleElement().asString().contains("isolation UNCONFIRMED");
            assertThat(say(task.action())).as("step 7: and the way to the run").isEqualTo("Show the run");

            // 8. A host whose key nobody confirmed takes no new apps: the refusal is worded (no quotes, no
            //    jargon), in en and nl, and its remedy is the item's action, the host page's key confirmation.
            Row phoenix = sshHost(PREFIX + "phoenix");
            hosts.add(phoenix);
            AttentionSubject phoenixHost = AttentionSubject.host(phoenix.get(ServerModel.ID));
            List<AttentionItem> hostItems = new ArrayList<>();
            HostAttention.hostsTakingNoApps(hostItems);
            AttentionItem keyItem = rootOf(hostItems, phoenixHost);
            assertThat(keyItem).as("step 8: the host is raised").isNotNull();
            assertThat(keyItem.detail().key()).as("step 8: for its unconfirmed key").isEqualTo("host_key_unverified");
            assertThat(say(keyItem.detail())).as("step 8: in words")
                .isEqualTo("Nobody has confirmed the host key of " + PREFIX + "phoenix yet, so Hohenheim cannot be "
                    + "sure it is talking to that machine; compare the key and confirm it first")
                .doesNotContain("'");
            assertThat(keyItem.detail().resolve(LocaleChain.ofTags("nl"), Zenit.getMessageResolver()))
                .as("step 8: and in Dutch").contains("hostsleutel van " + PREFIX + "phoenix");
            assertThat(say(keyItem.action())).as("step 8: its remedy is the action").isEqualTo("Confirm its host key");
            assertThat(keyItem.target().toUrl()).as("step 8: on the host's page")
                .isEqualTo("/admin/servers/" + phoenix.get(ServerModel.ID) + "/open");
        } finally {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.FORCE_HTTPS, forceHttps);
            ServerMain.adoptProxyServer(previous);
            if (proxy != null) {
                proxy.stop();
            }
            if (registeredHistory != null) {
                Models.unregisterInstance(registeredHistory);
            }
            for (Row site : sites) {
                HardDeletes.row(Models.get(SiteModel.class), Models.get(SiteModel.class).findById(site.get(SiteModel.ID)));
            }
            for (Row instance : instances) {
                HardDeletes.row(Models.get(InstanceModel.class), instance);
            }
            for (Row host : hosts) {
                HardDeletes.row(Models.get(ServerModel.class), host);
            }
            captured.restore();
        }
    }

    @Test
    void everyTaskAndEveryCountReadsInWords() throws Exception {
        ShippedCatalogs catalogs = new ShippedCatalogs();

        // 1. Every task the catalog holds, the framework's included, declares its worded name (core's task label),
        //    shipped in en and nl: a task without one fails here, never on the dashboard as a raw id.
        assertThat(TaskCatalog.get(VerifyWorkloadIsolation.ID)).as("step 1: the catalog is discovered").isNotNull();
        Set<String> paths = new HashSet<>();
        for (TaskDescriptor descriptor : TaskCatalog.all()) {
            Microcopy label = descriptor.declaredLabel();
            assertThat(label).as("step 1: task %s declares its label", descriptor.typePath()).isNotNull();
            for (String language : List.of("en", "nl")) {
                assertThat(catalogs.resolveSource(label.key(), LocaleChain.ofTags(language), label.filters()))
                    .as("step 1: task %s is named in %s", descriptor.typePath(), language).isNotNull();
            }
            assertThat(paths.add(descriptor.id().getPath()))
                .as("step 1: task %s's name key is its own", descriptor.typePath()).isTrue();
        }

        // 2. A sentence counting things says real plurals, in en and nl, never "(s)".
        Microcopy inUse = new ServerModel.References(1, 0, 0, 2, 1)
            .describe(Microcopy.of("server_in_use").withFilter("scope", "violations").withArg("name", "local"));
        assertThat(say(inUse)).as("step 2: each count in its own plural")
            .isEqualTo("Host local is still used by 1 stack, 0 databases, 0 shared database engines, 2 instances "
                + "and 1 port claim; remove or move them (release the claims) first");
        assertThat(inUse.resolve(LocaleChain.ofTags("nl"), Zenit.getMessageResolver()))
            .as("step 2: in Dutch too").contains("1 stack, 0 databases, 0 gedeelde database-engines, 2 instanties "
                + "en 1 poortclaim");
        Microcopy template = Microcopy.of("template_in_use").withFilter("scope", "violations").withArg("name", "Blog");
        assertThat(say(template.withArg("count", 1))).as("step 2: one").isEqualTo("Template Blog is still used by 1 instance");
        assertThat(template.withArg("count", 3).resolve(LocaleChain.ofTags("nl"), Zenit.getMessageResolver()))
            .as("step 2: several, in Dutch").isEqualTo("Sjabloon Blog wordt nog gebruikt door 3 instanties");

        // 3. No shipped sentence spells a pseudo-plural any more ("(s)", "(en)"); "http(s)" is a scheme, not a count.
        Pattern pseudo = Pattern.compile("[A-Za-z]\\((s|en|e|n)\\)");
        for (String language : List.of("en", "nl")) {
            try (InputStream input = DashboardAttentionJourneyTest.class
                    .getResourceAsStream("/META-INF/microcopy/" + language + ".json")) {
                assertThat(input).as("step 3: the %s catalog ships", language).isNotNull();
                String catalog = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("http(s)", "");
                Matcher found = pseudo.matcher(catalog);
                assertThat(found.find() ? catalog.substring(Math.max(0, found.start() - 60), found.end()) : null)
                    .as("step 3: the %s catalog counts in real plurals", language).isNull();
            }
        }
    }

    private static List<AttentionItem> causedBy(List<AttentionItem> items, AttentionSubject root) {
        return items.stream().filter(item -> root.equals(item.causedBy())).toList();
    }

    private static Row fresh(Row row) {
        return Models.get(SiteModel.class).findById(row.get(SiteModel.ID));
    }

    private static Row application(String name, int serverId) {
        Row app = Models.get(InstanceModel.class).createEmptyRow();
        app.set(InstanceModel.NAME, name);
        app.set(InstanceModel.KIND, "hohenheim:application");
        app.set(InstanceModel.SERVER_ID, serverId);
        app.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        app.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of()));
        InstanceKindHandler handler = InstanceKinds.getHandler("hohenheim:application");
        if (handler != null && handler.requiresRuntimeImage()) {
            Row image = Models.get(RuntimeImageModel.class).find()
                .where(RuntimeImageModel.NAME.eq("node-22")).first();
            app.set(InstanceModel.RUNTIME_IMAGE_ID, image == null ? null : image.get(RuntimeImageModel.ID));
        }
        Models.get(InstanceModel.class).save(app);
        return app;
    }

    private static void failedRelease(int applicationId, String reason) {
        Row row = Models.get(ReleaseOperationModel.class).createEmptyRow();
        row.set(ReleaseOperationModel.KIND, ReleaseOperationModel.KIND_RELEASE);
        row.set(ReleaseOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        row.set(ReleaseOperationModel.FOR_ID, applicationId);
        row.set(ReleaseOperationModel.STATUS, ReleaseOperationModel.STATUS_FAILED);
        row.set(ReleaseOperationModel.IMAGE_ID, "d11-failed");
        row.set(ReleaseOperationModel.FAILURE_REASON, reason);
        row.set(ReleaseOperationModel.STARTED_AT, Now.instant().minusSeconds(60));
        row.set(ReleaseOperationModel.FINISHED_AT, Now.instant());
        Models.get(ReleaseOperationModel.class).save(row);
    }

    private static Row forcedDomain(Row site, String hostname) {
        Row domain = Models.get(SiteDomainModel.class).createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domain.set(SiteDomainModel.FORCE_SSL, true);
        Models.get(SiteDomainModel.class).save(domain);
        return domain;
    }

    private static Row failedRun(String typePath, String error) {
        SystemTaskHistoryModel history = Models.get(SystemTaskHistoryModel.class);
        Row run = history.createEmptyRow();
        run.set(SystemTaskHistoryModel.TASK_TYPE, typePath);
        run.set(SystemTaskHistoryModel.STATUS, TaskStatus.FAILED.name());
        run.set(SystemTaskHistoryModel.STARTED_AT, Now.instant());
        run.set(SystemTaskHistoryModel.ENDED_AT, Now.instant());
        run.set(SystemTaskHistoryModel.ERROR, error);
        history.save(run);
        return run;
    }

    /** A remote Docker host admitted with an accepted posture whose host key nobody confirmed. */
    private static Row sshHost(String name) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        row.set(ServerModel.MODE, ServerModel.MODE_SSH);
        row.set(ServerModel.SSH_TARGET, "operator@" + name + ".invalid");
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(row);
        Row saved = Models.get(ServerModel.class).findById(row.get(ServerModel.ID));
        HostFixtures.acknowledgePosture(saved);
        return Models.get(ServerModel.class).findById(row.get(ServerModel.ID));
    }

    /** @return the rendered count tile counting this, up to its end */
    private static String tile(String dashboard, String key) {
        int start = dashboard.indexOf("data-hh-stat=\"" + key + "\"");
        assertThat(start).as("the dashboard draws the " + key + " tile").isNotNegative();
        return dashboard.substring(dashboard.lastIndexOf("<pl-stat-card", start),
            dashboard.indexOf("</pl-stat-card>", start));
    }

    private static int heldBack(int host) {
        AppHealth.HeldBack held = AppHealth.heldBackByHost().get(host);
        return held == null ? 0 : held.apps();
    }

    private static OnboardingStep step(DashboardAttention.Reading reading, OnboardingStage stage) {
        return reading.checklist().stream().filter(step -> step.stage() == stage).findFirst()
            .orElseThrow(() -> new AssertionError("the checklist has a " + stage + " step"));
    }

    private static AttentionItem rootOf(List<AttentionItem> items, AttentionSubject subject) {
        return items.stream().filter(item -> subject.equals(item.about())).findFirst().orElse(null);
    }

    private static List<AttentionItem> mine(List<AttentionItem> items) {
        return items.stream().filter(item -> say(item.title()).contains(PREFIX)).toList();
    }

    private static Row instance(String name, int serverId) {
        Row app = Models.get(InstanceModel.class).createEmptyRow();
        Map.of(InstanceModel.NAME.getName(), (Object) name,
            InstanceModel.KIND.getName(), "hohenheim:docker_container",
            InstanceModel.SETTINGS.getName(), new LinkedHashMap<>(Map.of("image", "alpine", "command", "sleep 60")),
            InstanceModel.STATUS.getName(), InstanceModel.STATUS_STOPPED,
            InstanceModel.SERVER_ID.getName(), serverId).forEach(app::set);
        Models.get(InstanceModel.class).save(app);
        return app;
    }

    private static String say(Microcopy copy) {
        return copy == null ? "" : copy.resolve(EN, Zenit.getMessageResolver());
    }
}
