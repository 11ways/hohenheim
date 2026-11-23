package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SiteOutageModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.ApplicationUpstreams;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.notification.SiteOutages;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.proxy.SiteDispatcher;
import be.elevenways.hohenheim.server.sitetype.SiteAvailability;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.test.application.FakeApps;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.delivery.DeliveryChannel;
import be.elevenways.zenit.delivery.server.transport.DsnOrigin;
import be.elevenways.zenit.delivery.server.transport.TransportTypes;
import be.elevenways.zenit.notifications.server.NotificationDispatcher;
import be.elevenways.zenit.notifications.server.NotificationInboxModel;
import be.elevenways.zenit.notifications.server.Notifications;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.test.support.DatasourceProbe;
import be.elevenways.zenit.test.support.OutboundFixture;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.zenit.test.support.TestAccessContexts;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outage watch of the sites apps serve, over {@link FakeDockerDaemon} and a running proxy: an app alerts once when
 * it stopped answering after it was seen answering, once when it answers again, and never for an app nobody deployed,
 * one that is stopped or one being deployed; a stop inside an outage keeps it open.
 *
 * AIDEV-NOTE: time is MOVED ({@link Now#setOffset}), never waited for; the only waits are the bounded polls for the
 * proxy's own probe round, which runs on its lane. Every channel here lists no event, so it receives every event, the
 * two new ones included. A webhook body in this lane carries each text as its catalog key (ScheduledBackupAlertTest
 * reads the same), so the webhook proves which event went out and how often, and the administrator's inbox item,
 * resolved in English, proves the words.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class SiteOutagesTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static SqlDatasource datasource;
    private static FakeApps lane;
    private static FakeDockerDaemon daemon;
    private static ProxyServer proxy;
    private static HttpServer receiver;
    private static OutboundFixture destination;
    private static final List<String> BODIES = new CopyOnWriteArrayList<>();
    private static final AtomicInteger TAGS = new AtomicInteger(1);

    private final List<Runnable> cleanup = new ArrayList<>();
    private Duration offset;

    @BeforeAll
    static void setUp() throws Exception {
        HohenheimEndpoints.init();
        datasource = APP.database();
        lane = FakeApps.install(datasource);
        daemon = lane.daemon();

        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", exchange -> {
            BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        int port = receiver.getAddress().getPort();
        // Webhooks refuse loopback destinations: the fixture resolves a public-looking host to the stub.
        destination = OutboundFixture.route("outage-watch.example.test", port);
        // Inline delivery, so a message has arrived when the sweep returns.
        Notifications.install(new NotificationDispatcher(Map.of(DeliveryChannel.WEBHOOK,
            List.of(TransportTypes.create("webhook://default", DsnOrigin.OPERATOR))), 1, true));
        Db.run(datasource, () -> {
            NotificationChannelModel channels = Models.get(NotificationChannelModel.class);
            Row channel = channels.createEmptyRow();
            channel.set(NotificationChannelModel.NAME, "outage-watch");
            channel.set(NotificationChannelModel.KIND, NotificationChannelModel.KIND_WEBHOOK);
            channel.set(NotificationChannelModel.FORMAT, NotificationChannelModel.FORMAT_GENERIC);
            channel.set(NotificationChannelModel.URL, "http://" + destination.host() + ":" + port + "/hook");
            // An existing channel that never chose: it receives every event, the two new ones too.
            channel.set(NotificationChannelModel.EVENTS, List.of());
            channels.save(channel);
            // The administrator whose inbox holds every alert, worded for a person.
            UUID admin = ApiSupport.user("outage-admin-" + UUID.randomUUID() + "@hohenheim.local", "Outage Admin");
            GrantService.createDirectGrant(GrantSubjectType.USER, admin, HohenheimSources.ADMIN_ACCESS.value(), true);
        });

        // The proxy is the test's own and handed to every sweep, never adopted: a class beside this one may adopt
        // and restore the process-wide proxy while this one runs.
        proxy = ProxyTestSupport.startProxy();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (proxy != null) {
            proxy.stop();
        }
        Notifications.install(null);
        if (destination != null) {
            destination.close();
        }
        if (receiver != null) {
            receiver.stop(0);
        }
        Db.run(datasource, () -> HardDeletes.where(Models.get(NotificationChannelModel.class),
            NotificationChannelModel.NAME.eq("outage-watch")));
        if (lane != null) {
            lane.close();
        }
    }

    @AfterEach
    void restore() {
        Db.run(datasource, () -> {
            for (int i = this.cleanup.size() - 1; i >= 0; i--) {
                this.cleanup.get(i).run();
            }
        });
        this.cleanup.clear();
        if (this.offset != null) {
            Now.setOffset(this.offset);
        }
    }

    /**
     * An app nobody deployed, then one already down at its first reading, never alerts; seen answering, then down
     * for two minutes, it alerts once, and once more when it answered two sweeps in a row, all without a route
     * reload or a recorded change. A second sweeper over the same rows, and a fresh reading as a restarted detector
     * makes (the watch keeps no state outside its table), repeat nothing.
     */
    @Test
    void anAppThatWasAnsweringAlertsOnceWhenItStopsAndOnceWhenItAnswersAgain() {
        this.offset = Now.offset();
        Db.run(datasource, () -> {
            int app = application("outage-shop");
            int siteId = site(app, "outage-shop");
            Row outside = ProxyTestSupport.setupSite("hohenheim:address", "outage-outside", "outage-outside",
                Map.of("forward_host", "127.0.0.1", "forward_port", 9));
            ProxyTestSupport.addDomain(outside, "outside.outage.test", "exact", null, false);
            this.cleanup.add(() -> HardDeletes.row(Models.get(SiteModel.class), outside));
            int outsideId = outside.get(SiteModel.ID);
            proxy.reload();
            // What step 12 compares against: the handler the route load built, the site's history and updated_at.
            SiteRequestHandler handler = dispatcher().findHandlerBySiteId(siteId);
            int changes = changesOf(siteId);
            Instant updated = stored(siteId).get(SiteModel.UPDATED_AT);
            int downBefore = sent(NotificationEvents.SITE_DOWN);
            int recoveredBefore = sent(NotificationEvents.SITE_RECOVERED);

            // 1. An app whose site never served: ten sweeps over ten minutes, nothing sent, nothing remembered.
            for (int i = 0; i < 10; i++) {
                SiteOutages.sweep(dispatcher());
                later(Duration.ofMinutes(1));
            }
            assertThat(sent(NotificationEvents.SITE_DOWN)).as("step 1: a never-deployed app never alerts")
                .isEqualTo(downBefore);
            assertThat(outage(siteId)).as("step 1: and nothing is remembered").isNull();

            // 2. Deployed, but already refusing its port at the first reading: never seen answering, never alerts.
            deploy(app);
            kill(app);
            probed(siteId, false);
            SiteOutages.sweep(dispatcher());
            later(Duration.ofMinutes(5));
            SiteOutages.sweep(dispatcher());
            assertThat(sent(NotificationEvents.SITE_DOWN)).as("step 2: down from the first reading on, nothing")
                .isEqualTo(downBefore);
            assertThat(outage(siteId)).as("step 2: never seen answering").isNull();

            // 3. Deployed again and answering: the first up reading is silent and remembered once.
            deploy(app);
            probed(siteId, true);
            SiteOutages.sweep(dispatcher());
            assertThat(state(siteId).get(0)).as("step 3: seen answering").isNotNull();
            assertThat(state(siteId).subList(1, 3)).as("step 3: and no outage").containsOnly((Instant) null);
            List<Instant> seen = state(siteId);
            Instant seenWritten = outage(siteId).get(SiteOutageModel.UPDATED_AT);
            SiteOutages.sweep(dispatcher());
            assertThat(state(siteId)).as("step 3: steady state writes nothing").isEqualTo(seen);
            assertThat((Instant) outage(siteId).get(SiteOutageModel.UPDATED_AT)).as("step 3: not even a stamp")
                .isEqualTo(seenWritten);

            // 4. Its port refuses, read by the proxy's own probe with no visitor: down for one minute, nothing.
            kill(app);
            probed(siteId, false);
            SiteOutages.sweep(dispatcher());
            assertThat(state(siteId).get(1)).as("step 4: the outage starts").isNotNull();
            later(Duration.ofMinutes(1));
            SiteOutages.sweep(dispatcher());
            assertThat(sent(NotificationEvents.SITE_DOWN)).as("step 4: one minute is not enough").isEqualTo(downBefore);

            // 5. Past two minutes: exactly one message, naming the app and why. A second sweeper that read the same
            //    rows before the first acted sends nothing (10).
            later(Duration.ofSeconds(70));
            SiteOutages.Observation stale = SiteOutages.observe(dispatcher());
            SiteOutages.sweep(dispatcher());
            assertThat(state(siteId).get(2)).as("step 5: the outage is alerted").isNotNull();
            assertThat(sent(NotificationEvents.SITE_DOWN)).as("step 5: one message").isEqualTo(downBefore + 1);
            Row down = item(NotificationEvents.SITE_DOWN, siteId);
            assertThat((String) down.get(NotificationInboxModel.TITLE)).as("step 5: about the app, not a host")
                .isEqualTo("App outage-shop stopped answering");
            assertThat((String) down.get(NotificationInboxModel.BODY)).as("step 5: and why")
                .startsWith("outage-shop answered before and has not answered since")
                .endsWith("The app refuses connections on its port.");
            SiteOutages.act(stale);
            assertThat(sent(NotificationEvents.SITE_DOWN)).as("step 10: the second sweeper sends nothing")
                .isEqualTo(downBefore + 1);

            // 6, 11. More sweeps, and a fresh reading as a restarted detector makes: nothing more.
            later(Duration.ofMinutes(3));
            SiteOutages.sweep(dispatcher());
            SiteOutages.act(SiteOutages.observe(dispatcher()));
            assertThat(sent(NotificationEvents.SITE_DOWN)).as("step 6: still one message").isEqualTo(downBefore + 1);

            // 7. It answers again: the first answer is not enough, the second sweep in a row sends one message with
            //    how long it did not answer, worded as hours, minutes and seconds.
            deploy(app);
            probed(siteId, true);
            SiteOutages.sweep(dispatcher());
            assertThat(sent(NotificationEvents.SITE_RECOVERED)).as("step 7: one answer is not yet a recovery")
                .isEqualTo(recoveredBefore);
            later(Duration.ofMinutes(1));
            SiteOutages.Observation staleAgain = SiteOutages.observe(dispatcher());
            SiteOutages.sweep(dispatcher());
            SiteOutages.act(staleAgain);
            assertThat(sent(NotificationEvents.SITE_RECOVERED)).as("step 7: one recovery")
                .isEqualTo(recoveredBefore + 1);
            Row again = item(NotificationEvents.SITE_RECOVERED, siteId);
            assertThat((String) again.get(NotificationInboxModel.TITLE)).as("step 7: about the app")
                .isEqualTo("App outage-shop answers again");
            assertThat((String) again.get(NotificationInboxModel.BODY)).as("step 7: with how long, in words")
                .matches("It did not answer for (\\d+h)?\\d+m(\\d+s)?\\.");
            assertThat(state(siteId).subList(1, 3)).as("step 7: the outage is over").containsOnly((Instant) null);
            SiteOutages.sweep(dispatcher());
            assertThat(sent(NotificationEvents.SITE_DOWN) + sent(NotificationEvents.SITE_RECOVERED))
                .as("step 7: two messages in all").isEqualTo(downBefore + recoveredBefore + 2);

            // 12. No sweep rebuilt the routes or recorded a change, and the site's updated_at never moved.
            assertThat(dispatcher().findHandlerBySiteId(siteId)).as("step 12: the same handler serves")
                .isNotNull().isSameAs(handler);
            assertThat(changesOf(siteId)).as("step 12: no change recorded").isEqualTo(changes);
            assertThat((Instant) stored(siteId).get(SiteModel.UPDATED_AT)).as("step 12: updated_at kept")
                .isEqualTo(updated);

            // 14. A site forwarding to an outside address that refuses is down too, and is never watched.
            probed(outsideId, false);
            later(Duration.ofMinutes(5));
            SiteOutages.sweep(dispatcher());
            assertThat(sent(NotificationEvents.SITE_DOWN)).as("step 14: an outside address never alerts")
                .isEqualTo(downBefore + 1);
            assertThat(outage(outsideId)).as("step 14: nor is anything remembered").isNull();
        });
    }

    /**
     * A stopped app never starts or advances an outage but keeps an alerted one open: a sweep inside a Restart, between
     * its stop and its start, still lets "answers again" go out, a crash read at its settled stop whose restart is
     * refused still alerts, and an app its operator stopped alerts only once a later start fails. Leaving the watch
     * ends an outage silently, and deleting a site takes its outage with it. An app serving two sites names each
     * address.
     */
    @Test
    void aStopNeverStartsAnOutageButKeepsAnAlertedOneOpen() {
        this.offset = Now.offset();
        Db.run(datasource, () -> {
            InstanceService instances = new InstanceService();

            // 1. An app with two sites, alerted on both, each message naming its own address.
            int restarted = application("outage-restart");
            int first = site(restarted, "outage-restart-a");
            int second = site(restarted, "outage-restart-b");
            int crashed = application("outage-crash");
            int crashSite = site(crashed, "outage-crash");
            int stopped = application("outage-stopped");
            int stoppedSite = site(stopped, "outage-stopped");
            proxy.reload();
            for (int app : List.of(restarted, crashed, stopped)) {
                deploy(app);
            }
            probed(first, true);
            probed(second, true);
            probed(crashSite, true);
            probed(stoppedSite, true);
            SiteOutages.sweep(dispatcher());
            int downBefore = sent(NotificationEvents.SITE_DOWN);
            int recoveredBefore = sent(NotificationEvents.SITE_RECOVERED);
            kill(restarted);
            probed(first, false);
            probed(second, false);
            SiteOutages.sweep(dispatcher());
            later(Duration.ofSeconds(130));
            SiteOutages.sweep(dispatcher());
            assertThat(sent(NotificationEvents.SITE_DOWN)).as("step 1: one message per site")
                .isEqualTo(downBefore + 2);
            assertThat((String) item(NotificationEvents.SITE_DOWN, first).get(NotificationInboxModel.TITLE))
                .as("step 1: the first names its address")
                .isEqualTo("App outage-restart stopped answering at outage-restart-a.outage.test");
            assertThat((String) item(NotificationEvents.SITE_DOWN, second).get(NotificationInboxModel.TITLE))
                .as("step 1: the second its own")
                .isEqualTo("App outage-restart stopped answering at outage-restart-b.outage.test");

            // 2. Restart, the real operation, with a sweep between its stop and its start: the stopped moment keeps
            //    the alerted outage, and the answers after the start send exactly one "answers again" per site.
            List<SiteAvailability> inside = new ArrayList<>();
            List<Instant> keptInside = new ArrayList<>();
            daemon.duringNextStart(() -> {
                SiteOutages.Observation between = SiteOutages.observe(dispatcher());
                inside.add(between.readings().get(first));
                SiteOutages.act(between);
                keptInside.addAll(state(first));
            });
            OperationPipeline.invoke(OperationRequest.of(InstanceOperations.RESTART, CmsPlacementSurface.ADMIN_ACTION)
                .caller(TestAccessContexts.allAllowed())
                .subjects(List.of(Models.get(InstanceModel.class).findById(restarted)))).value();
            assertThat(inside).as("step 2: the sweep ran inside the restart, at its stopped moment")
                .containsExactly(SiteAvailability.STOPPED);
            assertThat(keptInside.get(2)).as("step 2: the stopped moment keeps the alerted outage").isNotNull();
            assertThat(keptInside.get(0)).as("step 2: and the observed up").isNotNull();
            probed(first, true);
            probed(second, true);
            SiteOutages.sweep(dispatcher());
            later(Duration.ofMinutes(1));
            SiteOutages.sweep(dispatcher());
            SiteOutages.sweep(dispatcher());
            assertThat(sent(NotificationEvents.SITE_RECOVERED)).as("step 2: one recovery per site")
                .isEqualTo(recoveredBefore + 2);
            assertThat((String) item(NotificationEvents.SITE_RECOVERED, first).get(NotificationInboxModel.TITLE))
                .as("step 2: naming its address")
                .isEqualTo("App outage-restart answers again at outage-restart-a.outage.test");

            // 3. A crash settled to stopped and read there, whose restart is then refused: it still alerts, once.
            int crashRelease = serving(crashed);
            kill(crashed);
            instances.stop(crashRelease);
            SiteOutages.sweep(dispatcher());
            assertThat(state(crashSite).get(0)).as("step 3: the settled stop keeps the observed up").isNotNull();
            failStart(crashRelease);
            probed(crashSite, false);
            SiteOutages.sweep(dispatcher());
            later(Duration.ofSeconds(130));
            SiteOutages.sweep(dispatcher());
            assertThat(sent(NotificationEvents.SITE_DOWN)).as("step 3: the refused restart alerts")
                .isEqualTo(downBefore + 3);
            assertThat((String) item(NotificationEvents.SITE_DOWN, crashSite).get(NotificationInboxModel.TITLE))
                .as("step 3: one app, so no address").isEqualTo("App outage-crash stopped answering");

            // 4. An app its operator stopped: no alert however long it stays stopped; a later start that fails does.
            ApplicationReleases.stopFor(stopped);
            SiteOutages.sweep(dispatcher());
            later(Duration.ofMinutes(5));
            SiteOutages.sweep(dispatcher());
            assertThat(sent(NotificationEvents.SITE_DOWN)).as("step 4: a stopped app never alerts")
                .isEqualTo(downBefore + 3);
            assertThat(state(stoppedSite).get(1)).as("step 4: no outage runs while it is stopped").isNull();
            failStart(serving(stopped));
            probed(stoppedSite, false);
            SiteOutages.sweep(dispatcher());
            later(Duration.ofSeconds(130));
            SiteOutages.sweep(dispatcher());
            assertThat(sent(NotificationEvents.SITE_DOWN)).as("step 4: the failed start alerts")
                .isEqualTo(downBefore + 4);
            assertThat(item(NotificationEvents.SITE_DOWN, stoppedSite)).as("step 4: about that app's site").isNotNull();

            // 5. Switched off inside its outage, the site leaves the watch: its outage goes, silently.
            Row site = stored(stoppedSite);
            site.set(SiteModel.ENABLED, false);
            Models.get(SiteModel.class).save(site);
            SiteOutages.sweep(dispatcher());
            assertThat(outage(stoppedSite)).as("step 5: forgotten").isNull();
            assertThat(sent(NotificationEvents.SITE_DOWN) + sent(NotificationEvents.SITE_RECOVERED))
                .as("step 5: and nothing said").isEqualTo(downBefore + recoveredBefore + 6);

            // 6. Deleting a site inside its outage takes the outage with it.
            assertThat(outage(crashSite)).as("step 6: the crashed app's site holds its outage").isNotNull();
            HardDeletes.byId(Models.get(SiteModel.class), crashSite);
            assertThat(outage(crashSite)).as("step 6: the delete removed it").isNull();
        });
    }

    /** A deploy under way reads as nothing at all, however long the site was down before it. */
    @Test
    void aDeployUnderWayNeverAlerts() {
        this.offset = Now.offset();
        Db.run(datasource, () -> {
            int app = application("outage-deploying");
            int siteId = site(app, "outage-deploying");
            proxy.reload();
            deploy(app);
            probed(siteId, true);
            SiteOutages.sweep(dispatcher());
            kill(app);
            probed(siteId, false);
            SiteOutages.sweep(dispatcher());
            int downBefore = sent(NotificationEvents.SITE_DOWN);

            // 1. Three minutes later a deploy starts; every sweep inside it reads nothing, so nothing is sent.
            later(Duration.ofMinutes(3));
            List<Integer> inside = new ArrayList<>();
            daemon.duringNextStart(() -> {
                SiteOutages.sweep(dispatcher());
                SiteOutages.sweep(dispatcher());
                inside.add(sent(NotificationEvents.SITE_DOWN) - downBefore);
            });
            deploy(app);
            assertThat(inside).as("step 1: the sweeps ran inside the deploy, and sent nothing").containsExactly(0);

            // 2. The new version answers: the unalerted outage just ends.
            probed(siteId, true);
            SiteOutages.sweep(dispatcher());
            assertThat(sent(NotificationEvents.SITE_DOWN)).as("step 2: never a message").isEqualTo(downBefore);
            assertThat(state(siteId).subList(1, 3)).as("step 2: the outage is over").containsOnly((Instant) null);
        });
    }

    /** One sweep costs the same round trips for two sites as for twenty, spread over more apps. */
    @Test
    void aSweepCostsTheSameRoundTripsForTwoAndForTwentySites() {
        Db.run(datasource, () -> {
            List<Integer> apps = new ArrayList<>();
            List<Integer> sites = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int app = application("outage-count-" + i);
                apps.add(app);
                sites.add(site(app, "outage-count-" + i));
                deploy(app);
            }
            int few = steadySweep(sites);

            for (int i = 2; i < 5; i++) {
                int app = application("outage-count-" + i);
                apps.add(app);
                deploy(app);
            }
            for (int i = 0; sites.size() < 20; i++) {
                int app = apps.get(i % apps.size());
                sites.add(site(app, "outage-count-extra-" + i));
            }
            int many = steadySweep(sites);
            assertThat(few).as("a steady sweep reads sites, outages, apps, serving releases and deploys under way,"
                + " and writes nothing").isLessThanOrEqualTo(6);
            assertThat(many).as("flat as sites and apps grow").isEqualTo(few);
        });
    }

    // -- fixtures --------------------------------------------------------------

    /** The round trips of one steady-state sweep over these up sites. */
    private int steadySweep(List<Integer> sites) {
        proxy.reload();
        for (int siteId : sites) {
            probed(siteId, true);
        }
        SiteOutages.sweep(dispatcher());
        DatasourceProbe probe = DatasourceProbe.on(datasource);
        probe.reset();
        SiteOutages.sweep(dispatcher());
        return probe.roundTrips();
    }

    /** An authored app with an image the fake daemon answers for; destroyed after the test. */
    private int application(String name) {
        int id = FakeApps.application(name, "v1");
        this.cleanup.add(() -> ApplicationReleases.destroyFor(id));
        return id;
    }

    /** A site serving this app at {@code <slug>.outage.test}; removed after the test. */
    private int site(int applicationId, String slug) {
        Row site = ProxyTestSupport.setupInstanceSite(slug, slug, applicationId);
        ProxyTestSupport.addDomain(site, slug + ".outage.test", "exact", null, false);
        this.cleanup.add(() -> HardDeletes.row(Models.get(SiteModel.class), site));
        return site.get(SiteModel.ID);
    }

    /** Deploy a NEW version: a converge of an unchanged configuration keeps what serves, so each deploy moves the tag. */
    private static void deploy(int applicationId) {
        FakeApps.setSettings(applicationId, "v" + TAGS.incrementAndGet());
        ApplicationReleases.converge(applicationId, Map.of());
        // The old version drains after the switch: the deploy is under way until that settles.
        Poll.until("the deploy of app " + applicationId + " settles", Duration.ofSeconds(15),
            () -> ApplicationReleases.deployingAmong(List.of(applicationId)).isEmpty());
    }

    private static int serving(int applicationId) {
        return ApplicationReleases.ownedServing(applicationId).get(InstanceModel.ID);
    }

    /** The serving version's process exits with nobody watching: its port is unbound, its record still says running. */
    private static void kill(int applicationId) {
        daemon.killWorkload(FakeDockerDaemon.handleOf(serving(applicationId)));
    }

    /** A start that fails: the record reads error, as a refused restart stamps it, and the proxy is told. */
    private static void failStart(int releaseId) {
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(releaseId))
            .assign(InstanceModel.STATUS, InstanceModel.STATUS_ERROR).bypassBehaviours().updateAll();
        ApplicationUpstreams.invalidateForInstance(releaseId);
    }

    /** Run the proxy's own probe round until it read this answer for the site, with no visitor. */
    private static void probed(int siteId, boolean answered) {
        Poll.until("the probe reads site " + siteId + " as " + answered, Duration.ofSeconds(10), () -> {
            dispatcher().probeUpstreams();
            return Objects.equals(dispatcher().lastDialAnswered(siteId), answered);
        });
    }

    private static SiteDispatcher dispatcher() {
        return proxy.getDispatcher();
    }

    /** Move the clock on; the test's own offset is put back after it. */
    private static void later(Duration duration) {
        Now.setOffset(Now.offset().plus(duration));
    }

    private static Row stored(int siteId) {
        return Models.get(SiteModel.class).find().withTrashed().noCache().where(SiteModel.ID.eq(siteId)).first();
    }

    /** @return the site's outage row, null while the watch remembers nothing of it */
    private static Row outage(int siteId) {
        return Models.get(SiteOutageModel.class).find().noCache().where(SiteOutageModel.SITE_ID.eq(siteId)).first();
    }

    /** @return up_seen_at, down_since and down_alerted_at, in that order, all null without a row */
    private static List<Instant> state(int siteId) {
        Row outage = outage(siteId);
        if (outage == null) {
            return Arrays.asList(null, null, null);
        }
        return Arrays.asList(outage.get(SiteOutageModel.UP_SEEN_AT), outage.get(SiteOutageModel.DOWN_SINCE),
            outage.get(SiteOutageModel.DOWN_ALERTED_AT));
    }

    private static int changesOf(int siteId) {
        return History.ofRecord(Models.get(SiteModel.class), siteId, TestAccessContexts.allAllowed(), null, 100)
            .entries().size();
    }

    /** @return how many webhook messages of this event the channel received so far */
    private static int sent(NotificationEvents event) {
        String needle = "\"event\":\"" + event.token() + "\"";
        int count = 0;
        for (String body : BODIES) {
            if (body.contains(needle)) {
                count++;
            }
        }
        return count;
    }

    /** @return the administrator's inbox item of this event about this site, null while none came */
    private static Row item(NotificationEvents event, int siteId) {
        return Models.get(NotificationInboxModel.class).find().noCache()
            .where(NotificationInboxModel.REPEAT_KEY.eq(event.token() + ":" + Alerts.about(SiteModel.MODEL_ID, siteId)))
            .first();
    }
}
