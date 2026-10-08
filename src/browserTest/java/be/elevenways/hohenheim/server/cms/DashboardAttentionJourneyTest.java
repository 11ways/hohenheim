package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.OnboardingStage;
import be.elevenways.hohenheim.OnboardingState;
import be.elevenways.hohenheim.OnboardingStep;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.database.ControlPlaneBackups;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.resource.HealthTone;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.test.ProxyTestSupport.addDomain;
import static be.elevenways.hohenheim.test.ProxyTestSupport.setupInstanceSite;
import static be.elevenways.hohenheim.test.ProxyTestSupport.startProxy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin dashboard shows each problem once, at its root, with its one action: an open checklist step presents the
 * attention item stating its stage instead of the band repeating it, and an app its host holds back folds under that
 * host, which says how many apps wait for it, while the app's own verdict stays its own.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class DashboardAttentionJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "d8-fold-";
    private static final LocaleChain EN = LocaleChain.ofTags("en");

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
                assertThat(verdict.heldBy()).as("step 2: caused by its host").isEqualTo(local);
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
            assertThat(mine(admitted.attention())).as("step 5: each site is its own problem again").hasSize(2)
                .allMatch(item -> item.causedBy() == null);

            // 6. A root the band draws (a second host waiting while one is admitted, board Main) folds what it holds
            //    back too; a consequence whose root nobody shows stays, so a fold never hides a problem.
            AttentionSubject other = AttentionSubject.host(local + 1000);
            AttentionItem root = new AttentionItem(AttentionSeverity.WARNING, "server", Microcopy.literal("root"),
                null, null, null).about(other, Microcopy.literal("1 app waits for it"));
            AttentionItem held = new AttentionItem(AttentionSeverity.ERROR, "globe", Microcopy.literal("held"),
                null, null, null).causedBy(other);
            AttentionItem orphan = new AttentionItem(AttentionSeverity.ERROR, "globe", Microcopy.literal("orphan"),
                null, null, null).causedBy(AttentionSubject.host(local + 2000));
            assertThat(DashboardAttention.fold(admitted.checklist(), List.of(root, held, orphan)).attention())
                .as("step 6: the root stays, its consequence folds, a rootless consequence stays")
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
