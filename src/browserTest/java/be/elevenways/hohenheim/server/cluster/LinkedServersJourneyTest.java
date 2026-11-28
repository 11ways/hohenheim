package be.elevenways.hohenheim.server.cluster;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.cluster.ClusterEndpoints;
import be.elevenways.hohenheim.cluster.JoinRefusal;
import be.elevenways.hohenheim.cluster.LinkRole;
import be.elevenways.hohenheim.cluster.LinkState;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.auth.ProteusSignIn;
import be.elevenways.hohenheim.server.cms.AppStates;
import be.elevenways.hohenheim.server.cms.ClusterAttention;
import be.elevenways.hohenheim.server.cms.LinkedServerParts;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.MessageResolvers;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.util.Uuids;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.ai.server.mcp.host.McpScopes;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.ZenitAuthPrincipalKind;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.identity.IdentityProviderRegistry;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.code.CodeGrantModel;
import be.elevenways.zenit.common.orm.code.RecordCodeBehaviour;
import be.elevenways.zenit.common.orm.code.RecordCodeKinds;
import be.elevenways.zenit.common.orm.code.RecordCodeModel;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.peer.PeerModelSyncModel;
import be.elevenways.zenit.common.peer.PeerRecordModel;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.common.security.CredentialPresets;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.common.setting.SettingDefinition;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.delivery.DeliveryChannel;
import be.elevenways.zenit.delivery.server.transport.DsnOrigin;
import be.elevenways.zenit.delivery.server.transport.TransportTypes;
import be.elevenways.zenit.notifications.server.NotificationDispatcher;
import be.elevenways.zenit.notifications.server.Notifications;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.zenit.server.peer.CodeHomes;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.server.peer.PeerRecords;
import be.elevenways.zenit.server.peer.SharedRowsPage;
import be.elevenways.zenit.server.security.SecureTokens;
import be.elevenways.zenit.server.setting.ServerSettings;
import be.elevenways.zenit.test.support.DatasourceProbe;
import be.elevenways.zenit.test.support.OutboundFixture;
import be.elevenways.zenit.test.support.OutboundTlsStub;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.zenit.test.support.TestAccessContexts;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Two real Hohenheims linked end to end: this JVM's served app is the master Robbedoes, a forked JVM is Kuifje; they
 * join with a one-time code, check each other, alert past the outage grace when one stops and again when it answers,
 * refuse another database at the address, part with and without the other side answering, make one link at a time,
 * undo a join whose confirmation never came on both sides by themselves, and refuse what the one-master rule and the
 * join code forbid; a sweep asks silent servers at the same time. The master keeps its own record of what Kuifje has,
 * its rows read a page at a time over the link. Kuifje takes its code numbers from the master: joining renumbers it,
 * and it makes things from its blocks while the master is silent.
 * AIDEV-NOTE: each side reaches the other the way production does, an https call through its one pinned fetcher:
 * the other's host name is an OutboundFixture TLS stub routed to a TlsRelay in front of the other's plain port. Time
 * is moved (Now.setOffset), never waited for. Alerts are counted at a webhook channel (inline delivery), as
 * SiteOutagesTest counts its own.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class LinkedServersJourneyTest {

    private static final String MASTER_HOST = "robbedoes.cluster.test";
    private static final String CHILD_HOST = "kuifje.cluster.test";
    private static final String CHILD_ADDRESS = "https://" + CHILD_HOST;
    private static final String ALERT_HOST = "cluster-alerts.example.test";

    /** The master's own site, so its numbers run past the ones a fresh Kuifje had. */
    private static final String OWN_SITE = "cluster-codes-own-site";

    /** The master's own site in the journey of carried tool calls. */
    private static final String CARRIED_SITE = "cluster-carried-own-site";

    /** The Proteus subject of the person signed in on both servers, and of one signed in on the master alone. */
    private static final String PERSON = "4242";
    private static final String STRANGER = "5151";

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app()
        .withSetting(HohenheimSettings.Cluster.NAME, "Robbedoes")
        .withSetting(ServerSettings.Network.MAIN_URL, "https://" + MASTER_HOST);

    private static final List<String> BODIES = new CopyOnWriteArrayList<>();
    private static HttpServer receiver;
    private static OutboundFixture alerts;
    private static TlsRelay relay;
    private static OutboundTlsStub child;

    @BeforeAll
    static void setUp() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", exchange -> {
            BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        int port = receiver.getAddress().getPort();
        alerts = OutboundFixture.route(ALERT_HOST, port);
        Notifications.install(new NotificationDispatcher(Map.of(DeliveryChannel.WEBHOOK,
            List.of(TransportTypes.create("webhook://default", DsnOrigin.OPERATOR))), 1, true));
        NotificationChannelModel channels = Models.get(NotificationChannelModel.class);
        Row channel = channels.createEmptyRow();
        channel.set(NotificationChannelModel.NAME, "cluster-alerts");
        channel.set(NotificationChannelModel.KIND, NotificationChannelModel.KIND_WEBHOOK);
        channel.set(NotificationChannelModel.FORMAT, NotificationChannelModel.FORMAT_GENERIC);
        channel.set(NotificationChannelModel.URL, "http://" + ALERT_HOST + ":" + port + "/hook");
        channel.set(NotificationChannelModel.EVENTS, List.of());
        channels.save(channel);

        // The master's calls to Kuifje: its name over TLS, relayed to whichever child listens now (none yet).
        relay = TlsRelay.open();
        child = OutboundFixture.routeTls(CHILD_HOST, relay.port());
        relay.start(child.serverContext(), closedPort());
    }

    @AfterAll
    static void tearDown() throws Exception {
        Now.setOffset(Duration.ZERO);
        if (child != null) {
            child.close();
        }
        if (relay != null) {
            relay.close();
        }
        Notifications.install(null);
        if (alerts != null) {
            alerts.close();
        }
        if (receiver != null) {
            receiver.stop(0);
        }
        HardDeletes.where(Models.get(NotificationChannelModel.class),
            NotificationChannelModel.NAME.eq("cluster-alerts"));
        HardDeletes.where(Models.get(SiteModel.class), SiteModel.NAME.in(List.of(OWN_SITE, CARRIED_SITE)));
        awaitSyncs();
        for (Row linked : Models.get(LinkedServerModel.class).find().all()) {
            ClusterRecords.forget(linked.get(LinkedServerModel.ID));
        }
        Models.get(LinkedServerModel.class).find().delete();
    }

    /**
     * The master keeps its own record of everything Kuifje has: it takes it all in at the add, hears of every change
     * on Kuifje at once, keeps it while Kuifje is silent, notices once that Kuifje's database was put back to an
     * earlier state and applies nothing then, forgets it with the link, and hands Kuifje's rows to nobody but its
     * master.
     */
    @Test
    void theMasterRemembersWhatALinkedServerHas(@TempDir Path directory, TestReporter reporter) throws Exception {
        Path kuifjeDb = directory.resolve("kuifje.db");
        ForkedHohenheim kuifje = boot(directory, kuifjeDb, true, reporter);
        relay.retarget(kuifje.port());
        try {
            // 1. Kuifje holds a site before it is linked, and the join card counts every coded thing there as
            //    getting a new code; the master has a site of its own. The add takes in everything Kuifje has, in the
            //    background, and the kept counts per kind equal Kuifje's own; the list's Sites column is Kuifje's own
            //    count.
            String early = after("site ", kuifje.ask("site early-site"));
            String earlyCode = after("code ", kuifje.ask("code " + SiteModel.MODEL_ID + " " + early));
            assertThat(Integer.parseInt(after("renumbers ", kuifje.ask("renumbers"))))
                .as("step 1: the join card counts every coded thing on Kuifje").isEqualTo(codesOf(kuifje).size());
            String ownSite = masterSite(OWN_SITE);
            add(codeOf(kuifje.ask("offer")));
            awaitSyncs();
            Row server = only();
            String peer = ClusterRecords.peerKey(server);
            String masterKey = server.get(LinkedServerModel.LINK_KEY);
            String own = kuifje.ask("counts");
            assertThat(kept(peer)).as("step 1: the master keeps what Kuifje has, kind by kind").isEqualTo(own);
            assertThat(PeerRecords.read(peer, SiteModel.MODEL_ID, early)).as("step 1: the site made before the link")
                .isNotNull();
            assertThat((Object) server.get(LinkedServerModel.IN_STEP_SINCE)).as("step 1: in step").isNotNull();
            assertThat((Integer) server.get(LinkedServerModel.SITES)).as("step 1: Kuifje's own count of sites")
                .isEqualTo(Integer.parseInt(countOf(own, SiteModel.MODEL_ID)));

            // 1b. The join renumbered Kuifje: its site has a new code and the old one still names it there. No
            //     current code of Kuifje is one of the master's, and the master answers that each of Kuifje's lives
            //     on Kuifje and its own site here.
            String renamed = after("code ", kuifje.ask("code " + SiteModel.MODEL_ID + " " + early));
            assertThat(renamed).as("step 1b: the site has a new code").isNotEqualTo(earlyCode).startsWith("S");
            assertThat(kuifje.ask("key-of " + SiteModel.MODEL_ID + " " + earlyCode))
                .as("step 1b: the old code still names it on Kuifje").isEqualTo("key " + early);
            List<String> kuifjeCodes = codesOf(kuifje);
            assertThat(kuifjeCodes).as("step 1b: Kuifje's codes and the master's are disjoint").contains(renamed)
                .doesNotContainAnyElementsOf(masterCodes());
            for (String each : kuifjeCodes) {
                assertThat(CodeHomes.peerOf(each)).as("step 1b: %s lives on Kuifje", each).isEqualTo(peer);
            }
            String ownCode = RecordCodes.codeUnjudged(SiteModel.MODEL_ID, ownSite);
            assertThat(ownCode).as("step 1b: the master's site has a code").isNotNull();
            assertThat(CodeHomes.peerOf(ownCode)).as("step 1b: the master's own site lives here").isNull();

            // 2. A site made on Kuifje through its real operation is known here at once: Kuifje nudges, the master
            //    syncs; nobody runs the minute check.
            String made = after("site ", kuifje.ask("site later-site"));
            settle(kuifje);
            PeerRecords.KeptRecord kept = PeerRecords.read(peer, SiteModel.MODEL_ID, made);
            assertThat(kept).as("step 2: the new site is kept").isNotNull();
            assertThat(kept.title()).as("step 2: under its name").isEqualTo("later-site");
            //    Beside the record, the master holds the state Kuifje tells of each of its apps, told at once too: the
            //    new site is an app of its own there, under its name, with the verdict Kuifje's own list leads with.
            Map<String, Object> told = toldState(peer, "website-" + made);
            assertThat(told).as("step 2: the master holds the new app's state").isNotNull();
            assertThat(told.get("name")).as("step 2: under its name").isEqualTo("later-site");
            assertThat(told.get("source")).as("step 2: a website").isEqualTo("website");
            assertThat(((Map<?, ?>) told.get("health")).get("tone")).as("step 2: with Kuifje's verdict").isNotNull();

            // 3. A change and a removal there are followed.
            assertThat(kuifje.ask("describe " + made + " described-on-kuifje")).isEqualTo("ok");
            settle(kuifje);
            assertThat(PeerRecords.read(peer, SiteModel.MODEL_ID, made).document())
                .as("step 3: the change is kept").contains("described-on-kuifje");
            assertThat(kuifje.ask("unsite " + made)).startsWith("unsite ");
            settle(kuifje);
            assertThat(PeerRecords.read(peer, SiteModel.MODEL_ID, made)).as("step 3: the removal is followed").isNull();
            assertThat(toldState(peer, "website-" + made)).as("step 3: its app's state went with it").isNull();
            assertThat(kept(peer)).as("step 3: in step again").isEqualTo(kuifje.ask("counts"));

            //    The master's statements per sync stay flat as Kuifje's rows grow: one site or six, in one commit.
            DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Models.get(PeerRecordModel.class)
                .getResolvedDatasource());
            ForkedHohenheim writer = kuifje;
            DatasourceProbe.Statements one = probe.statementsOf(() -> grow(writer, 1));
            DatasourceProbe.Statements six = probe.statementsOf(() -> grow(writer, 6));
            for (String table : List.of(PeerRecordModel.TABLE, PeerModelSyncModel.TABLE)) {
                assertThat(six.executedNaming(table)).as("step 3: statements on %s for six sites as for one", table)
                    .isEqualTo(one.executedNaming(table)).isPositive();
            }

            //    The wire pages: Kuifje's sites read three at a time through the master's own wire come over more
            //    than one page, each once, exactly the sites the master keeps.
            LinkWire wire = new LinkWire(CHILD_ADDRESS, masterKey);
            List<String> paged = new ArrayList<>();
            int pages = 0;
            String cursor = null;
            do {
                SharedRowsPage page = wire.rows(SiteModel.MODEL_ID, cursor, 3);
                page.rows().forEach(row -> paged.add(row.key()));
                cursor = page.next();
                pages++;
            } while (cursor != null && pages < 100);
            assertThat(cursor).as("step 3: the pages end").isNull();
            assertThat(pages).as("step 3: more than one page").isGreaterThan(1);
            assertThat(paged).as("step 3: every site once, the ones the master keeps").doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(keptKeys(peer, SiteModel.MODEL_ID));

            // 4. A secret value of an app variable is readable through the master's record and not in its table.
            String secret = "kept-secret-" + SecureTokens.randomToken(8);
            String variable = after("variable ", kuifje.ask("secret " + secret));
            settle(kuifje);
            assertThat(PeerRecords.read(peer, InstanceVariableModel.MODEL_ID, variable).document())
                .as("step 4: readable through the record").contains(secret);
            assertThat(storedDocument(peer, InstanceVariableModel.MODEL_ID, variable))
                .as("step 4: encrypted in the master's table").doesNotContain(secret);

            // 4b. The master stops answering Kuifje: an app made there still gets a code, from a block the master
            //     granted it at the join; the master names Kuifje as where that code lives.
            assertThat(kuifje.ask("cut")).isEqualTo("ok");
            String appCode = after("app ", kuifje.ask("app offline-app")).split(" ")[1];
            assertThat(appCode).as("step 4b: the app has a code").startsWith("AP");
            assertThat(CodeHomes.peerOf(appCode)).as("step 4b: its number lies in Kuifje's blocks").isEqualTo(peer);

            // 4c. With its sites' numbers used up and the master still silent, a new site is refused in the declared
            //     words, and nothing is written.
            long left = Long.parseLong(after("left ", kuifje.ask("left S")));
            assertThat(left).as("step 4c: numbers left before").isPositive();
            assertThat(kuifje.ask("sites " + left)).isEqualTo("ok");
            String full = kuifje.ask("counts");
            assertThat(kuifje.ask("sites 1")).as("step 4c: refused in words").isEqualTo("refused No numbers are left"
                + " for new sites; this server gets more from its master when that answers.");
            assertThat(kuifje.ask("counts")).as("step 4c: nothing written").isEqualTo(full);

            // 4d. The master answers again: Kuifje's minute check brings a new block, and a site is made.
            assertThat(kuifje.ask("mend")).isEqualTo("ok");
            assertThat(kuifje.ask("probe")).as("step 4d: Kuifje reaches its master")
                .startsWith("rows Robbedoes|managed_by|reachable|");
            assertThat(kuifje.ask("left S")).as("step 4d: a new block").isNotEqualTo("left 0");
            after("site ", kuifje.ask("site after-the-block"));
            settle(kuifje);

            // 5. Kuifje is down: everything kept stays, and so do its counts.
            String keptBefore = kept(peer);
            Integer sitesBefore = only().get(LinkedServerModel.SITES);
            kuifje.close();
            LinkedServers.sweep();
            awaitSyncs();
            assertThat(stateOf(only())).as("step 5: Kuifje does not answer").isEqualTo(LinkState.NOT_ANSWERING);
            assertThat(kept(peer)).as("step 5: everything kept stays").isEqualTo(keptBefore);
            assertThat((Integer) only().get(LinkedServerModel.SITES)).as("step 5: its count stays")
                .isEqualTo(sitesBefore);
            assertThat(toldState(peer, "website-" + early)).as("step 5: the state Kuifje last told stays")
                .isNotNull();

            // 6. Kuifje's database is put back to an earlier state: the copy taken now, before one more site the
            //    master takes in. The state, the attention item and the alert come once; nothing kept changes.
            Path earlier = directory.resolve("kuifje-earlier.db");
            copy(kuifjeDb, earlier);
            kuifje = boot(directory, kuifjeDb, false, reporter);
            relay.retarget(kuifje.port());
            after("site ", kuifje.ask("site after-copy"));
            settle(kuifje);
            String keptBeforeRestore = kept(peer);
            kuifje.close();
            Path restored = directory.resolve("kuifje-restored.db");
            copy(earlier, restored);
            kuifje = boot(directory, restored, false, reporter);
            relay.retarget(kuifje.port());
            int restoredBefore = count("linked_server_restored_subject");
            LinkedServers.sweep();
            awaitSyncs();
            assertThat(stateOf(only())).as("step 6: restored to an earlier state").isEqualTo(LinkState.RESTORED);
            assertThat(english(LinkState.RESTORED.label(null))).isEqualTo("Restored to an earlier state");
            assertThat(ClusterRecords.kinds(only().get(LinkedServerModel.WENT_BACK))).as("step 6: the sites went back")
                .contains(SiteModel.MODEL_ID);
            List<AttentionItem> items = new ArrayList<>();
            ClusterAttention.linkedServers(items);
            assertThat(items).as("step 6: one attention item").hasSize(1);
            assertThat(english(items.getFirst().title())).isEqualTo("Kuifje was restored to an earlier state");
            LinkedServers.sweep();
            awaitSyncs();
            assertThat(count("linked_server_restored_subject") - restoredBefore).as("step 6: one alert, once")
                .isEqualTo(1);
            assertThat(kept(peer)).as("step 6: nothing kept changes").isEqualTo(keptBeforeRestore);

            // 7. Refusals at the three calls. An administrator signed in on Kuifje (a session cookie, never a link
            //    key) is refused 403 before any call answers: the framework's CSRF guard exempts a self-authenticating
            //    call for non-interactive callers only. Generations and rows answer the called server's master
            //    alone: that administrator's own API key and a wrong key are 401; Kuifje, a server the master manages,
            //    presenting its own valid key to the master's generations is 403. Changed and codes answer only a
            //    server the called one manages: the master presenting its valid key to Kuifje's is 403.
            String cookie = after("session ", kuifje.ask("session"));
            String panel = "/admin/" + HohenheimSlugs.SERVERS;
            assertThat(statusAtKuifje(kuifje, panel, cookie, false)).as("step 7: the cookie signs the administrator in")
                .isEqualTo(200);
            assertThat(statusAtKuifje(kuifje, panel, null, false)).as("step 7: nobody is signed in without it")
                .isNotEqualTo(200);
            for (Endpoint<Object> call : List.of(ClusterEndpoints.GENERATIONS, ClusterEndpoints.ROWS,
                    ClusterEndpoints.CHANGED, ClusterEndpoints.CODES)) {
                assertThat(statusAtKuifje(kuifje, call.toUrl(), cookie, true))
                    .as("step 7: %s, a signed-in administrator", call.toUrl()).isEqualTo(403);
            }
            List<String> notLinkKeys = List.of(after("api-key ", kuifje.ask("api-key")), SecureTokens.randomToken(32));
            for (String key : notLinkKeys) {
                assertThat(statusOf(LinkCalls.generations(CHILD_ADDRESS, key))).as("step 7: generations, no link key")
                    .isEqualTo(401);
                assertThat(statusOf(LinkCalls.rows(CHILD_ADDRESS, key, SiteModel.MODEL_ID.toString(), null, 10)))
                    .as("step 7: rows, no link key").isEqualTo(401);
                assertThat(statusOf(LinkCalls.changed(CHILD_ADDRESS, key))).as("step 7: changed, no link key")
                    .isEqualTo(401);
                assertThat(statusOf(LinkCalls.codes(CHILD_ADDRESS, key, List.of(), null)))
                    .as("step 7: codes, no link key").isEqualTo(401);
            }
            assertThat(kuifje.ask("read-master")).as("step 7: a managed server reads nothing of its master")
                .isEqualTo("status 403");
            assertThat(statusOf(LinkCalls.changed(CHILD_ADDRESS, masterKey)))
                .as("step 7: a master does not nudge the server it manages").isEqualTo(403);
            assertThat(statusOf(LinkCalls.codes(CHILD_ADDRESS, masterKey, List.of(), null)))
                .as("step 7: a master takes no code numbers from the server it manages").isEqualTo(403);
            assertThat(statusOf(LinkCalls.generations(CHILD_ADDRESS, masterKey)))
                .as("step 7: its master reads it").isEqualTo(200);

            // 8. Removing the link forgets everything kept of Kuifje.
            assertThat(remove(only())).isEqualTo(LinkedServers.Removal.LEFT.token());
            awaitSyncs();
            assertThat(kept(peer)).as("step 8: nothing of Kuifje is kept").isEqualTo("counts ");
            assertThat(PeerRecords.read(peer, SiteModel.MODEL_ID, early)).isNull();
        } finally {
            kuifje.close();
        }
    }

    /**
     * An assistant connected to the master works on Kuifje's app by its code as if it lived on the master: the call
     * is carried to Kuifje and runs there as the same person's own account, found by the Proteus sign-in both servers
     * share, its answer unchanged; a change made there is in the master's record before the answer, and Kuifje's
     * history credits the person through the master. A code of the master never leaves it; a call naming both
     * servers' things, a person with no account on Kuifje or no Proteus sign-in here, another realm and a silent
     * Kuifje are refused in words, with nothing written there; and Kuifje's key cannot speak for anybody on its master.
     */
    @Test
    void aToolCallAboutKuifjesAppRunsThereAsThePerson(@TempDir Path directory, TestReporter reporter)
            throws Exception {
        ForkedHohenheim kuifje = boot(directory, directory.resolve("kuifje.db"), true, reporter);
        relay.retarget(kuifje.port());
        Runnable proteusOff = proteusOn();
        try {
            // 1. Kuifje is linked and holds an app; the master names Kuifje as where its code lives. The master's
            //    administrator signed in with Proteus on both servers, as the same subject; a key of the "Hohenheim
            //    assistant" preset connects to the master.
            add(codeOf(kuifje.ask("offer")));
            awaitSyncs();
            String peer = ClusterRecords.peerKey(only());
            String[] app = after("app ", kuifje.ask("app carried-app")).split(" ");
            String appKey = app[0];
            String appCode = app[1];
            settle(kuifje);
            assertThat(CodeHomes.peerOf(appCode)).as("step 1: the app lives on Kuifje").isEqualTo(peer);
            // A run's code is a code like any other: Kuifje numbers its runs from its master's blocks, so run_read of a
            // run started there is carried there as an app's code is.
            assertThat(Long.parseLong(after("left ", kuifje.ask("left RUN")))).as("step 1: Kuifje numbers its runs"
                + " from its master's blocks").isPositive();
            UUID operatorId = TenantConduits.operatorUser().get(UserModel.ID);
            ForkedHohenheim.linkProteus(operatorId, PERSON);
            String person = after("person ", kuifje.ask("person " + PERSON));
            McpHostServer server = McpHostServers.routed(HohenheimMcpModule.SERVER_NAME);
            CredentialPresets.Preset preset = server.credentialPreset(TenantConduits.operator());
            assertThat(preset).as("step 1: the preset is offered").isNotNull();
            List<String> scopes = ApiKeyService.presetScopes(TenantConduits.operator(), preset);
            McpHttpSession master = McpHttpSession.open(McpHttpSession.endpoint(APP.port(),
                HohenheimMcpModule.SERVER_NAME), bearer(ApiKeyService.create(TenantConduits.operator(), operatorId,
                "cluster-carried", scopes, null).plaintext()));

            // 2. A read of Kuifje's app on the master answers what Kuifje's own connection answers the same person
            //    holding a key of the same scopes there.
            McpHttpSession.Answer read = master.call("read", Map.of("codes", List.of(appCode)));
            assertThat(read.error()).as("step 2: read " + read.text()).isFalse();
            McpHttpSession own = McpHttpSession.open(McpHttpSession.endpoint(kuifje.port(),
                HohenheimMcpModule.SERVER_NAME), bearer(after("mcp-key ", kuifje.ask("mcp-key "
                + String.join(",", scopes)))));
            assertThat(read.text()).as("step 2: Kuifje's own answer").contains(appCode)
                .isEqualTo(own.call("read", Map.of("codes", List.of(appCode))).text());

            // 3. A write on the master is made on Kuifje: the master's record of Kuifje is in step when it answers,
            //    and Kuifje's history credits the person, through the master.
            McpHttpSession.Answer set = master.call("variable_set",
                Map.of("owner", appCode, "set", List.of(Map.of("key", "GREETING", "value", "carried"))));
            assertThat(set.error()).as("step 3: set " + set.text()).isFalse();
            assertThat(kuifje.ask("variables " + appKey)).as("step 3: written on Kuifje")
                .isEqualTo("variables GREETING=carried");
            assertThat(kept(peer)).as("step 3: the master's record is in step").isEqualTo(kuifje.ask("counts"));
            assertThat(kuifje.ask("last-action")).as("step 3: Kuifje credits the person through the master")
                .isEqualTo("action " + person + " " + PrincipalRef.account(UUID.fromString(person)).storedKind() + " "
                    + ZenitAuthPrincipalKind.HOST_CREDENTIAL.id() + " " + Uuids.v5(UUID.fromString(person),
                    "Robbedoes"));
            settle(kuifje);

            // 4. A code of the master's own site is read on the master: nothing is sent to Kuifje. A read naming that
            //    site and Kuifje's app is refused in words, and nothing is sent either.
            String ownCode = RecordCodes.codeUnjudged(SiteModel.MODEL_ID, masterSite(CARRIED_SITE));
            int sent = relay.accepted();
            McpHttpSession.Answer here = master.call("read", Map.of("codes", List.of(ownCode)));
            assertThat(here.error()).as("step 4: read here " + here.text()).isFalse();
            assertThat(here.text()).as("step 4: the master's site").contains(CARRIED_SITE);
            McpHttpSession.Answer both = master.call("read", Map.of("codes", List.of(appCode, ownCode)));
            assertThat(both.error()).as("step 4: both servers refused").isTrue();
            assertThat(both.text()).as("step 4: in words").isEqualTo("The records this call names are kept on"
                + " different servers. Name the records of one server per call.");
            assertThat(relay.accepted()).as("step 4: no call reached Kuifje").isEqualTo(sent);

            // 5. A person with a Proteus sign-in here and no account on Kuifje is refused in words, and Kuifje writes
            //    nothing; a person without a Proteus sign-in here is refused before anything is sent.
            UUID stranger = ApiSupport.user("cluster-stranger@cluster.test", "Cluster Stranger");
            ForkedHohenheim.linkProteus(stranger, STRANGER);
            McpHttpSession.Answer refused = McpHttpSession.open(McpHttpSession.endpoint(APP.port(),
                HohenheimMcpModule.SERVER_NAME), bearer(ApiKeyService.create(stranger, "cluster-stranger",
                List.of(McpScopes.server(HohenheimMcpModule.SERVER_NAME), "*"), null).plaintext()))
                .call("variable_set",
                    Map.of("owner", appCode, "set", List.of(Map.of("key", "GREETING", "value", "stranger"))));
            assertThat(refused.error()).as("step 5: refused").isTrue();
            assertThat(refused.text()).as("step 5: in words")
                .isEqualTo("You have no account on Kuifje. Sign in there with Proteus once; nothing was changed.");
            assertThat(kuifje.ask("variables " + appKey)).as("step 5: Kuifje wrote nothing")
                .isEqualTo("variables GREETING=carried");
            UUID passwordOnly = ApiSupport.user("cluster-password@cluster.test", "Cluster Password");
            int before = relay.accepted();
            McpHttpSession.Answer unnamed = McpHttpSession.open(McpHttpSession.endpoint(APP.port(),
                HohenheimMcpModule.SERVER_NAME), bearer(ApiKeyService.create(passwordOnly, "cluster-password",
                List.of(McpScopes.server(HohenheimMcpModule.SERVER_NAME), "*"), null).plaintext()))
                .call("read", Map.of("codes", List.of(appCode)));
            assertThat(unnamed.text()).as("step 5: no Proteus sign-in here").isEqualTo("Acting on Kuifje from here"
                + " needs a person who signs in with Proteus, here and on Kuifje. Sign in here with Proteus once, then"
                + " use a key of that account.");
            assertThat(relay.accepted()).as("step 5: nothing was sent for it").isEqualTo(before);

            // 6. Kuifje, with the key it holds for its master, cannot have the master run anything as a person.
            assertThat(kuifje.ask("tool-master " + ownCode + " " + PERSON))
                .as("step 6: a managed server speaks for nobody on its master").isEqualTo("status 403");

            // 7. A master signing people in with another realm, which vouches for the person here, names nobody on
            //    Kuifje.
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.AuthProteus.REALM_CLIENT, "elsewhere");
            ForkedHohenheim.vouchedBy(operatorId, ProteusSignIn.realm());
            McpHttpSession.Answer realm = master.call("read", Map.of("codes", List.of(appCode)));
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.AuthProteus.REALM_CLIENT,
                ForkedHohenheim.PROTEUS_REALM_CLIENT);
            ForkedHohenheim.vouchedBy(operatorId, ProteusSignIn.realm());
            assertThat(realm.text()).as("step 7: another realm").isEqualTo("Kuifje does not sign people in with this"
                + " server's Proteus realm, so nobody can act on it from here; nothing was changed.");

            // 8. Kuifje does not answer: the call says so, naming it, and nothing is answered from the master's
            //    record.
            relay.retarget(closedPort());
            McpHttpSession.Answer silent = master.call("read", Map.of("codes", List.of(appCode)));
            relay.retarget(kuifje.port());
            assertThat(silent.error()).as("step 8: an error").isTrue();
            assertThat(silent.text()).as("step 8: in words").isEqualTo("Kuifje does not answer; nothing was changed.");

            assertThat(remove(only())).isEqualTo(LinkedServers.Removal.LEFT.token());
        } finally {
            proteusOff.run();
            kuifje.close();
        }
    }

    /**
     * Offers Proteus sign-in on the master in the realm Kuifje signs people in with.
     *
     * @return what puts the master's sign-in back as it was
     */
    private static Runnable proteusOn() {
        Boolean enabled = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.AuthProteus.ENABLED);
        List<SettingDefinition<String>> texts = List.of(HohenheimSettings.AuthProteus.ENDPOINT,
            HohenheimSettings.AuthProteus.REALM_CLIENT, HohenheimSettings.AuthProteus.ACCESS_KEY,
            HohenheimSettings.AuthProteus.REQUIRED_PERMISSION);
        Map<SettingDefinition<String>, String> before = new LinkedHashMap<>();
        texts.forEach(setting -> before.put(setting, Zenit.SETTINGS_VALUES.getValue(setting)));
        List<IdentityProviderRegistry.Entry> providers = new ArrayList<>();
        IdentityProviderRegistry.all()
            .forEach(provider -> providers.add(IdentityProviderRegistry.get(provider.slug())));
        ForkedHohenheim.proteusSettings();
        if (IdentityProviderRegistry.get(ProteusSignIn.SLUG) == null) {
            ProteusSignIn.registerIfConfigured();
        }
        return () -> {
            before.forEach(Zenit.SETTINGS_VALUES::setValue);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.AuthProteus.ENABLED, enabled);
            IdentityProviderRegistry.clear();
            providers.forEach(entry -> IdentityProviderRegistry.register(entry.provider(), entry.sink()));
        };
    }

    private static String[] bearer(String key) {
        return new String[] {"Authorization", "Bearer " + key};
    }

    /** @return Kuifje's current allocated codes */
    private static List<String> codesOf(ForkedHohenheim kuifje) throws Exception {
        String codes = after("codes ", kuifje.ask("codes"));
        return codes.isEmpty() ? List.of() : List.of(codes.split(";"));
    }

    /** @return the master's own current allocated codes */
    private static List<String> masterCodes() {
        List<String> codes = new ArrayList<>();
        for (Row code : new RecordCodeModel(Models.get(LinkedServerModel.class).getResolvedDatasource()).find()
                .noCache().where(RecordCodeModel.NUMBER.isNotNull()).where(RecordCodeModel.RETIRED_AT.isNull())
                .all()) {
            codes.add(code.get(RecordCodeModel.CODE));
        }
        return codes;
    }

    /** Asserts the master answers that each code lives on that linked server; null: nothing here has it. */
    private static void livesOn(List<String> codes, String peer, String step) {
        assertThat(codes).as(step + ": Kuifje has codes").isNotEmpty();
        for (String each : codes) {
            assertThat(CodeHomes.peerOf(each)).as("%s: where %s lives", step, each).isEqualTo(peer);
        }
    }

    /** @return whether a grant of the master that never came into force holds the code's number */
    private static boolean voidGrantHolds(String code) {
        RecordCodeBehaviour.Allocated kind = RecordCodeKinds.allocatedOf(code);
        long number = kind.number(code);
        return new CodeGrantModel(Models.get(LinkedServerModel.class).getResolvedDatasource()).find().noCache()
            .where(CodeGrantModel.PREFIX.eq(kind.prefix())).where(CodeGrantModel.FIRST.lte(number))
            .where(CodeGrantModel.LAST.gte(number)).where(CodeGrantModel.CONFIRMED_AT.isNull()).count() == 1;
    }

    /** @return the key of the master's own site of that name, made once */
    private static String masterSite(String name) {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name);
        site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp/" + name));
        site.set(SiteModel.ENABLED, false);
        sites.save(site);
        return String.valueOf((Object) site.get(SiteModel.ID));
    }

    /** Makes {@code count} sites on Kuifje in one commit and waits until the master took them in. */
    private static void grow(ForkedHohenheim kuifje, int count) {
        try {
            assertThat(kuifje.ask("sites " + count)).isEqualTo("ok");
            settle(kuifje);
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    /** Waits until Kuifje told its master of every change, and the master's syncs it caused ended. */
    private static void settle(ForkedHohenheim kuifje) throws Exception {
        assertThat(kuifje.ask("settle")).as("Kuifje told its master").isEqualTo("settled true");
        awaitSyncs();
    }

    /** Waits, the framework's way, until every sync this master asked ended. */
    private static void awaitSyncs() {
        assertThat(ClusterRecords.awaitIdle(Duration.ofSeconds(60))).as("the master's syncs ended").isTrue();
    }

    /** @return what the master keeps of a linked server, kind by kind, as Kuifje's "counts" answers its own */
    private static String kept(String peer) {
        List<String> counts = new ArrayList<>();
        PeerRecords.counts(peer).forEach((model, count) -> {
            if (count > 0) {
                counts.add(model + "=" + count);
            }
        });
        Collections.sort(counts);
        return "counts " + String.join(";", counts);
    }

    /** @return the keys of the records (up to 500) the master keeps of one kind of a linked server */
    private static List<String> keptKeys(String peer, Identifier model) {
        return PeerRecords.page(peer, model, null, 500).entries().stream().map(PeerRecords.KeptEntry::key).toList();
    }

    /** @return the state a linked server last told of one of its apps, as the master keeps it; null when none */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toldState(String peer, String app) {
        PeerRecords.KeptRecord kept = PeerRecords.read(peer, AppStates.KIND, app);
        return kept == null ? null : (Map<String, Object>) Zenit.DRY.parseJsonTree(kept.document());
    }

    /**
     * @param cookie the Cookie header sent, null for none
     * @param post   whether it is a cluster call (a POST with an empty JSON body and no link key) or a page read
     * @return the status Kuifje answers on its own port, no redirect followed
     */
    private static int statusAtKuifje(ForkedHohenheim kuifje, String path, String cookie, boolean post)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + kuifje.port() + path));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        if (post) {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
        }
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    /** @return the count one kind has in a "counts" answer */
    private static String countOf(String counts, Identifier model) {
        for (String entry : counts.substring("counts ".length()).split(";")) {
            if (entry.startsWith(model + "=")) {
                return entry.substring(entry.indexOf('=') + 1);
            }
        }
        return "0";
    }

    /** @return what an answer says after its word */
    private static String after(String word, String answer) {
        assertThat(answer).as("the child answered %s", word.trim()).startsWith(word);
        return answer.substring(word.length());
    }

    /** @return the HTTP status a call was answered with, -1 when nothing came back */
    private static int statusOf(LinkCalls.Answer answer) {
        return answer instanceof LinkCalls.Answered answered ? answered.status() : -1;
    }

    /** @return the master's stored document column of one kept record, as its table holds it */
    private static String storedDocument(String peer, Identifier model, String key) {
        StringField peerField = StringField.builder("peer").build();
        StringField modelField = StringField.builder("model").build();
        StringField recordKey = StringField.builder("record_key").build();
        TextField document = TextField.builder().name("document").build();
        List<Row> rows = new ArrayList<>();
        Db.run(Models.get(PeerRecordModel.class).getResolvedDatasource(), () -> rows.addAll(
            new FrozenModel(PeerRecordModel.TABLE, peerField, modelField, recordKey, document).find()
                .where(peerField.eq(peer)).where(modelField.eq(model.toString())).where(recordKey.eq(key)).all()));
        assertThat(rows).as("one stored record").hasSize(1);
        return String.valueOf((Object) rows.getFirst().get(document));
    }

    @Test
    void twoHohenheimsJoinCheckEachOtherAndPart(@TempDir Path directory, TestReporter reporter) throws Exception {
        Path kuifjeDb = directory.resolve("kuifje.db");
        ForkedHohenheim kuifje = boot(directory, kuifjeDb, true, reporter);
        relay.retarget(kuifje.port());
        try {
            // 1. The managed side issues a join code: twelve letters shown as three groups of four.
            String code = codeOf(kuifje.ask("offer"));
            assertThat(code).as("step 1: the code is shown in groups").matches("[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}");

            // 2. The master adds it: both sides hold the link, each pinning the other's controller identity.
            assertThat(add(code)).as("step 2: the add answers the name").isEqualTo("Kuifje");
            Row kuifjeRow = only();
            assertThat(LinkedServers.roleOf(kuifjeRow)).as("step 2: the master manages Kuifje")
                .isEqualTo(LinkRole.MANAGES);
            assertThat((String) kuifjeRow.get(LinkedServerModel.REMOTE_TOKEN)).as("step 2: Kuifje's identity pinned")
                .isEqualTo(kuifje.token());
            assertThat((String) kuifjeRow.get(LinkedServerModel.BASE_URL)).isEqualTo(CHILD_ADDRESS);
            assertThat(kuifje.ask("rows")).as("step 2: Kuifje is managed by Robbedoes, its identity pinned")
                .isEqualTo("rows Robbedoes|managed_by|reachable|-|" + ControllerIdentity.token());

            // 3. After a probe each side reads the other as reachable.
            assertThat(LinkedServers.sweep()).as("step 3: the master asks Kuifje").isEqualTo(1);
            assertThat(stateOf(only())).as("step 3: the master reads Kuifje reachable").isEqualTo(LinkState.REACHABLE);
            assertThat(kuifje.ask("probe")).as("step 3: Kuifje reads its master reachable")
                .isEqualTo("rows Robbedoes|managed_by|reachable|-|" + ControllerIdentity.token());

            // 4. Check now asks at once.
            assertThat(check(only())).as("step 4: Check now answers the state").isEqualTo(LinkState.REACHABLE.token());

            // 5. Kuifje stops: the master says at once that it does not answer since then, and asks it every minute,
            //    but alerts nobody and raises no item inside the grace a site outage waits too; past it, one alert
            //    and one item, and a further failing check alerts no more.
            kuifje.close();
            int downBefore = count("linked_server_down_subject");
            assertThat(LinkedServers.sweep()).as("step 5: the sweep asks a failing server too").isEqualTo(1);
            Row silent = only();
            assertThat(stateOf(silent)).as("step 5: does not answer").isEqualTo(LinkState.NOT_ANSWERING);
            assertThat((Object) silent.get(LinkedServerModel.FAILING_SINCE)).as("step 5: since when").isNotNull();
            Now.setOffset(Duration.ofMinutes(1));
            assertThat(LinkedServers.sweep()).as("step 5: asked again a minute later").isEqualTo(1);
            assertThat(count("linked_server_down_subject") - downBefore).as("step 5: one failed check alerts nobody")
                .isZero();
            List<AttentionItem> items = new ArrayList<>();
            ClusterAttention.linkedServers(items);
            assertThat(items).as("step 5: no attention item inside the grace").isEmpty();
            Now.setOffset(Alerts.DOWN_AFTER);
            LinkedServers.sweep();
            check(only());
            assertThat(count("linked_server_down_subject") - downBefore).as("step 5: one alert past the grace")
                .isEqualTo(1);
            ClusterAttention.linkedServers(items);
            assertThat(items).as("step 5: one attention item").hasSize(1);
            assertThat(english(items.getFirst().title())).as("step 5: it names the server and since when")
                .startsWith("Kuifje does not answer since");

            // 6. Kuifje is back: the next sweep reads it, the recovery alerts once, and the item is gone. A failure
            //    that ends inside the grace (the address answers nothing for one check) alerts neither way.
            kuifje = boot(directory, kuifjeDb, false, reporter);
            relay.retarget(kuifje.port());
            int recoveredBefore = count("linked_server_recovered_subject");
            assertThat(LinkedServers.sweep()).as("step 6: asked at the next sweep").isEqualTo(1);
            assertThat(stateOf(only())).as("step 6: reachable again").isEqualTo(LinkState.REACHABLE);
            assertThat(count("linked_server_recovered_subject") - recoveredBefore).as("step 6: one recovery alert")
                .isEqualTo(1);
            items.clear();
            ClusterAttention.linkedServers(items);
            assertThat(items).as("step 6: nothing needs attention").isEmpty();
            relay.retarget(closedPort());
            LinkedServers.sweep();
            assertThat(stateOf(only())).as("step 6: a short failure").isEqualTo(LinkState.NOT_ANSWERING);
            relay.retarget(kuifje.port());
            LinkedServers.sweep();
            assertThat(stateOf(only())).as("step 6: over inside the grace").isEqualTo(LinkState.REACHABLE);
            assertThat(count("linked_server_down_subject") - downBefore).as("step 6: no stop alert for it")
                .isEqualTo(1);
            assertThat(count("linked_server_recovered_subject") - recoveredBefore)
                .as("step 6: no recovery alert without a stop alert").isEqualTo(1);
            Now.setOffset(Duration.ZERO);

            // 7. Another database answers at the address (a copy with its own identity): "not the server that
            //    joined", alerted at once since it never heals by itself, and nothing further is sent there, neither
            //    by the sweep, Check now nor Remove.
            kuifje.close();
            Path copyDb = directory.resolve("kuifje-copy.db");
            copy(kuifjeDb, copyDb);
            kuifje = boot(directory, copyDb, true, reporter);
            relay.retarget(kuifje.port());
            int downBeforeCopy = count("linked_server_down_subject");
            LinkedServers.sweep();
            Row other = only();
            assertThat(stateOf(other)).as("step 7: not the server that joined").isEqualTo(LinkState.OTHER_SERVER);
            assertThat(english(LinkState.OTHER_SERVER.label(null))).isEqualTo("Not the server that joined");
            assertThat(count("linked_server_down_subject") - downBeforeCopy).as("step 7: alerted at once")
                .isEqualTo(1);
            // A sync an earlier good answer asked may still be on its way: it ends before the count is taken.
            awaitSyncs();
            int sent = relay.accepted();
            assertThat(LinkedServers.sweep()).as("step 7: the sweep asks it nothing").isZero();
            assertThat(check(other)).isEqualTo(LinkState.OTHER_SERVER.token());
            assertThat(remove(other)).as("step 7: removed here only").isEqualTo(LinkedServers.Removal.NOT_SENT.token());
            assertThat(relay.accepted()).as("step 7: nothing was sent there").isEqualTo(sent);
            assertThat(LinkedServers.all()).isEmpty();
            // The copy still holds the link; removing it there finds no link here any more.
            assertThat(kuifje.ask("remove-master")).isEqualTo("removed " + LinkedServers.Removal.LEFT.token());
            assertThat(kuifje.ask("rows")).isEqualTo("rows -");

            // 8. Remove from the master with Kuifje up: both sides forget the link, and the codes Kuifje made while
            //    linked live on no server here any more: nothing here has them.
            add(codeOf(kuifje.ask("offer")));
            List<String> removedCodes = codesOf(kuifje);
            livesOn(removedCodes, ClusterRecords.peerKey(only()), "step 8: before removing");
            assertThat(remove(only())).as("step 8: removed on both")
                .isEqualTo(LinkedServers.Removal.LEFT.token());
            assertThat(kuifje.ask("rows")).as("step 8: Kuifje forgot it too").isEqualTo("rows -");
            livesOn(removedCodes, null, "step 8: removed");
            // And with Kuifje down: the master forgets it and says Kuifje still holds it until removed there.
            add(codeOf(kuifje.ask("offer")));
            List<String> downCodes = codesOf(kuifje);
            livesOn(downCodes, ClusterRecords.peerKey(only()), "step 8: before removing a silent server");
            kuifje.close();
            assertThat(remove(only())).as("step 8: removed here only")
                .isEqualTo(LinkedServers.Removal.KEPT_THERE.token());
            assertThat(LinkedServers.all()).isEmpty();
            livesOn(downCodes, null, "step 8: a silent server removed");
            kuifje = boot(directory, copyDb, false, reporter);
            relay.retarget(kuifje.port());
            assertThat(kuifje.ask("rows")).as("step 8: Kuifje still holds the link")
                .startsWith("rows Robbedoes|managed_by|");
            assertThat(kuifje.ask("remove-master")).isEqualTo("removed " + LinkedServers.Removal.LEFT.token());

            // 9. One link is made at a time. Two joins reach Kuifje at the same instant, each with a valid code of
            //    its own: exactly one links a master, the other is refused in words. A join Robbedoes never
            //    recorded (these come from the test, not its add) leaves only void grants here: Kuifje's codes from
            //    them live on no server Robbedoes knows. Two adds on the master at once: exactly one links Kuifje,
            //    the other is refused in words.
            String first = codeOf(kuifje.ask("offer 1"));
            String second = codeOf(kuifje.ask("offer 2"));
            List<Object> joins = atOnce(() -> LinkCalls.join(CHILD_ADDRESS, joinBody(first)),
                () -> LinkCalls.join(CHILD_ADDRESS, joinBody(second)));
            List<Integer> statuses = new ArrayList<>();
            String refusal = null;
            for (Object join : joins) {
                LinkCalls.Answered answered = (LinkCalls.Answered) join;
                statuses.add(answered.status());
                if (answered.status() != 200) {
                    refusal = english(JoinRefusal.byToken(String.valueOf(answered.body().get("refusal"))).message());
                }
            }
            assertThat(statuses).as("step 9: one join links, one is refused").containsExactlyInAnyOrder(200, 409);
            assertThat(refusal).as("step 9: in words").isEqualTo(english(JoinRefusal.HAS_MASTER.message()));
            assertThat(kuifje.ask("rows")).as("step 9: Kuifje holds one master")
                .startsWith("rows Robbedoes|managed_by|").doesNotContain(";");
            List<String> unrecorded = codesOf(kuifje);
            assertThat(unrecorded).as("step 9: the join renumbered Kuifje").isNotEmpty();
            for (String each : unrecorded) {
                assertThat(voidGrantHolds(each)).as("step 9: %s lies in a void grant", each).isTrue();
                assertThat(CodeHomes.peerOf(each)).as("step 9: %s lives on no server here", each).isNull();
            }
            // That master's keys are the test's, not Robbedoes': removing it there finds no link here.
            assertThat(kuifje.ask("remove-master")).isEqualTo("removed " + LinkedServers.Removal.LEFT.token());
            String third = codeOf(kuifje.ask("offer 1"));
            String fourth = codeOf(kuifje.ask("offer 2"));
            List<Object> adds = atOnce(() -> addOrRefusal(third), () -> addOrRefusal(fourth));
            assertThat(adds).as("step 9: one add links Kuifje, the other is refused in words")
                .containsExactlyInAnyOrder("Kuifje", "refused Another linked server already has this name.");
            assertThat(kuifje.ask("rows")).as("step 9: Kuifje holds one master")
                .startsWith("rows Robbedoes|managed_by|").doesNotContain(";");
            assertThat(remove(only())).isEqualTo(LinkedServers.Removal.LEFT.token());

            // 10. The answer of a join is lost on its way back (the master's relay cuts it off): Kuifje committed the
            //     link, the master holds none and says Kuifje did not answer. Kuifje says it is joining, not
            //     confirmed, and a refused check inside the window keeps it; past the window its next check undoes
            //     the join by itself: no master, its own numbering again, the app's new code kept and its old
            //     spelling still naming it, the master's grant void. A fresh code then joins again, confirmed at once.
            awaitSyncs();
            String[] lostApp = after("app ", kuifje.ask("app lost-answer-app")).split(" ");
            String lost = codeOf(kuifje.ask("offer"));
            relay.dropAnswers(true);
            try {
                assertThat(refusedAdd(lost)).as("step 10: the master heard no answer")
                    .startsWith(CHILD_ADDRESS + " did not answer.");
            } finally {
                relay.dropAnswers(false);
            }
            assertThat(LinkedServers.all()).as("step 10: the master holds no link").isEmpty();
            String joining = "rows Robbedoes|managed_by|" + LinkState.JOINING.token() + "|";
            assertThat(kuifje.ask("rows")).as("step 10: Kuifje is joining, not confirmed").startsWith(joining);
            String renumbered = after("code ", kuifje.ask("code " + InstanceModel.MODEL_ID + " " + lostApp[0]));
            assertThat(renumbered).as("step 10: the join renumbered the app").isNotEqualTo(lostApp[1]);
            assertThat(voidGrantHolds(renumbered)).as("step 10: in a grant the master never put in force").isTrue();
            assertThat(kuifje.ask("probe")).as("step 10: refused inside the window, it stays joining")
                .startsWith(joining);
            assertThat(kuifje.ask("offset 6")).isEqualTo("ok");
            assertThat(kuifje.ask("probe")).as("step 10: past the window Kuifje undoes the join").isEqualTo("rows -");
            assertThat(kuifje.ask("offset 0")).isEqualTo("ok");
            assertThat(after("code ", kuifje.ask("code " + InstanceModel.MODEL_ID + " " + lostApp[0])))
                .as("step 10: the app keeps its new code").isEqualTo(renumbered);
            assertThat(kuifje.ask("key-of " + InstanceModel.MODEL_ID + " " + lostApp[1]))
                .as("step 10: its old spelling still names it").isEqualTo("key " + lostApp[0]);
            assertThat(CodeHomes.peerOf(renumbered)).as("step 10: its code lives on no server here").isNull();
            assertThat(add(codeOf(kuifje.ask("offer")))).as("step 10: a fresh code joins again").isEqualTo("Kuifje");
            assertThat(stateOf(only())).as("step 10: confirmed on the master").isEqualTo(LinkState.REACHABLE);
            assertThat(kuifje.ask("rows")).as("step 10: and on Kuifje")
                .startsWith("rows Robbedoes|managed_by|reachable|");
            assertThat(remove(only())).isEqualTo(LinkedServers.Removal.LEFT.token());

            // 11. The master records the link and stops before its confirming call (the add's commit alone, as a
            //     master that stopped right after it leaves it), and Kuifje cannot reach it: both sides say joining.
            //     Past the window Kuifje undoes its join; the master, reaching Kuifje again, is refused and forgets
            //     its unconfirmed row. Neither side is left linked alone, and nobody repaired anything.
            awaitSyncs();
            UUID heldId = Uuids.v7();
            String heldKey = SecureTokens.randomToken(32);
            LinkCalls.Answered held = (LinkCalls.Answered) LinkCalls.join(CHILD_ADDRESS,
                joinBody(codeOf(kuifje.ask("offer")), heldId, heldKey));
            assertThat(held.status()).as("step 11: Kuifje took the join").isEqualTo(200);
            List<String> heldCodes = codesOf(kuifje);
            assertThat(kuifje.ask("cut")).isEqualTo("ok");
            LinkedServers.recorded(heldId, "Kuifje", CHILD_ADDRESS, heldKey, held.body());
            livesOn(heldCodes, heldId.toString(), "step 11: recorded");
            assertThat(stateOf(only())).as("step 11: the master's row is not confirmed").isEqualTo(LinkState.JOINING);
            assertThat(kuifje.ask("rows")).as("step 11: nor Kuifje's").startsWith(joining);
            assertThat(kuifje.ask("offset 6")).isEqualTo("ok");
            assertThat(kuifje.ask("probe")).as("step 11: past the window, its master out of reach, Kuifje undoes it")
                .isEqualTo("rows -");
            assertThat(kuifje.ask("offset 0")).isEqualTo("ok");
            assertThat(kuifje.ask("mend")).isEqualTo("ok");
            awaitSyncs();
            LinkedServers.sweep();
            assertThat(LinkedServers.all()).as("step 11: refused by Kuifje, the master forgets its unconfirmed row")
                .isEmpty();
            livesOn(heldCodes, null, "step 11: the unconfirmed row forgotten");

            // 12. Refusals, each in words: a second master, a managed server adding one, a master getting a master,
            //     then a wrong, a used and an expired code, and the spent failure budget.
            String used = codeOf(kuifje.ask("offer"));
            add(used);
            assertThat(kuifje.ask("offer")).as("step 12: a managed server gets no second master")
                .isEqualTo("refused " + english(JoinRefusal.HAS_MASTER.message()));
            assertThat(kuifje.ask("add Gaston https://gaston.cluster.test WWWW-WWWW-WWWW"))
                .as("step 12: a managed server adds no server")
                .isEqualTo("refused This server is managed by Robbedoes; a managed server manages no other servers.");
            // An operation a fact keeps from running is offered dead with its reason, and refused in those words.
            DomainRefusal mastered = assertThrows(DomainRefusal.class, () -> OperationPipeline.invoke(
                OperationRequest.of(LinkedServerParts.OFFER_JOIN, CmsPlacementSurface.ADMIN_ACTION)
                    .caller(TestAccessContexts.allAllowed())));
            assertThat(english(mastered.shown())).as("step 12: a master gets no master")
                .isEqualTo(english(JoinRefusal.MANAGES_OTHERS.message()));
            remove(only());
            // The master's own address is refused before anything is sent.
            assertThat(words(assertThrows(Violations.class, () -> OperationPipeline.invoke(
                OperationRequest.of(LinkedServerParts.ADD, CmsPlacementSurface.ADMIN_ACTION)
                    .caller(TestAccessContexts.allAllowed())
                    .form(Map.of("name", "Self", "address", "https://" + MASTER_HOST,
                        "join_code", "WWWW-WWWW-WWWW"))))))
                .as("step 12: this server's own address")
                .isEqualTo("This is this server's own address. Enter the address of the other Hohenheim.");

            // The expired code first, then Kuifje's clock stays ahead: the failure budget only ever moves forward.
            String late = codeOf(kuifje.ask("offer"));
            assertThat(kuifje.ask("offset 11")).isEqualTo("ok");
            assertThat(refusedAdd(late)).as("step 12: an expired code")
                .isEqualTo(english(JoinRefusal.EXPIRED.message()));
            assertThat(refusedAdd("WWWW-WWWW-WWWW")).as("step 12: a wrong code")
                .isEqualTo(english(JoinRefusal.NO_SUCH_CODE.message()));
            assertThat(refusedAdd(used)).as("step 12: a used code")
                .isEqualTo(english(JoinRefusal.NO_SUCH_CODE.message()));
            refusedAdd("WWWW-WWWW-WWWX");
            refusedAdd("WWWW-WWWW-WWWY");
            String right = codeOf(kuifje.ask("offer"));
            assertThat(refusedAdd(right)).as("step 12: the budget is spent, so even the right code is not looked at")
                .isEqualTo(english(JoinRefusal.TOO_MANY_TRIES.message()));
            assertThat(LinkedServers.all()).as("step 12: no refused join linked anything").isEmpty();
        } finally {
            kuifje.close();
        }
    }

    /**
     * Three linked servers that never answer are all judged in one sweep within about one call's wait: the sweep asks
     * them at the same time, each check writing its own row.
     */
    @Test
    void silentServersAreAskedAtTheSameTime() throws Exception {
        List<Socket> held = new CopyOnWriteArrayList<>();
        List<OutboundTlsStub> routes = new ArrayList<>();
        // A listener that takes every connection and says nothing: each call waits for its own deadline.
        try (ExecutorService holder = Executors.newVirtualThreadPerTaskExecutor();
             ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            holder.submit(() -> {
                while (!silent.isClosed()) {
                    held.add(silent.accept());
                }
                return null;
            });
            try {
                for (String name : List.of("gaston", "lagaffe", "prunelle")) {
                    String host = name + ".cluster.test";
                    routes.add(OutboundFixture.routeTls(host, silent.getLocalPort()));
                    plant(name, "https://" + host);
                }

                // 1. One of them alone: Check now waits a whole call for nothing, the yardstick of one silent server.
                Now.Stopwatch one = Now.stopwatch();
                LinkedServers.check(LinkedServers.all().getFirst());
                Duration alone = Duration.ofNanos(one.elapsedNanos());
                assertThat(alone).as("step 1: a silent server costs a wait").isGreaterThan(Duration.ofSeconds(1));

                // 2. The sweep asks all three at the same time: together they take about as long as one, never three
                //    one after another.
                Now.Stopwatch all = Now.stopwatch();
                assertThat(LinkedServers.sweep()).as("step 2: all three asked").isEqualTo(3);
                Duration together = Duration.ofNanos(all.elapsedNanos());
                assertThat(together).as("step 2: judged within one wait, not one after another (one took %s)", alone)
                    .isLessThan(alone.multipliedBy(2));

                // 3. Each is judged on its own row: none answers.
                for (Row row : LinkedServers.all()) {
                    assertThat(stateOf(row)).as("step 3: %s does not answer", (Object) row.get(LinkedServerModel.NAME))
                        .isEqualTo(LinkState.NOT_ANSWERING);
                }
            } finally {
                // The planted rows go first: a later close failing must not leave them for the other journey.
                Models.get(LinkedServerModel.class).find().delete();
                for (int at = routes.size() - 1; at >= 0; at--) {
                    routes.get(at).close();
                }
                // The listener ends, then its accepting thread, so no connection is taken after the held ones close.
                silent.close();
                holder.close();
                for (Socket socket : held) {
                    socket.close();
                }
            }
        }
    }

    /** Plants a confirmed server this master manages at {@code address}, as an add leaves it before its first check. */
    private static void plant(String name, String address) {
        Row row = Models.get(LinkedServerModel.class).createEmptyRow();
        row.set(LinkedServerModel.ID, Uuids.v7());
        row.set(LinkedServerModel.NAME, name);
        row.set(LinkedServerModel.ROLE, LinkRole.MANAGES.token());
        row.set(LinkedServerModel.BASE_URL, address);
        row.set(LinkedServerModel.LINK_KEY, SecureTokens.randomToken(32));
        row.set(LinkedServerModel.REMOTE_TOKEN, "silent-" + name);
        row.set(LinkedServerModel.CONFIRMED_AT, Now.instant());
        Models.get(LinkedServerModel.class).insert(row, Change.PLAIN);
    }

    /** Boots Kuifje on its database and publishes how long it took. */
    private static ForkedHohenheim boot(Path directory, Path database, boolean freshIdentity, TestReporter reporter)
            throws Exception {
        ForkedHohenheim kuifje = ForkedHohenheim.start(directory, database, "Kuifje", MASTER_HOST, APP.port(),
            freshIdentity);
        reporter.publishEntry("childBootMs", String.valueOf(kuifje.bootTime().toMillis()));
        return kuifje;
    }

    private static String codeOf(String answer) {
        assertThat(answer).as("the child issued a code").startsWith("code ");
        return answer.substring("code ".length());
    }

    private static String add(String code) {
        return OperationPipeline.invoke(OperationRequest.of(LinkedServerParts.ADD, CmsPlacementSurface.ADMIN_ACTION)
            .caller(TestAccessContexts.allAllowed())
            .form(Map.of("name", "Kuifje", "address", CHILD_ADDRESS, "join_code", code))).value();
    }

    private static String refusedAdd(String code) {
        return words(assertThrows(Violations.class, () -> add(code)));
    }

    /** @return what an add answered: the name it linked, or "refused" and its words */
    private static Object addOrRefusal(String code) {
        try {
            return add(code);
        } catch (Violations refused) {
            return "refused " + words(refused);
        }
    }

    /**
     * @return a join's body as Robbedoes sends it, presenting {@code code}, a key of its own and its ticket for the
     *         code numbers
     */
    private static Map<String, Object> joinBody(String code) {
        return joinBody(code, Uuids.v7(), SecureTokens.randomToken(32));
    }

    /** @return a join's body as Robbedoes sends it for the row {@code id}, Kuifje to present {@code key} to it */
    private static Map<String, Object> joinBody(String code, UUID id, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "Robbedoes");
        body.put("base_url", "https://" + MASTER_HOST);
        body.put("token", ControllerIdentity.token());
        body.put("build", LinkedServers.ownVersion());
        body.put("key", key);
        body.put("ticket", ClusterCodes.ticket(id, key));
        return body;
    }

    /** @return what each call answered, the calls started together on threads of their own */
    @SafeVarargs
    private static List<Object> atOnce(Callable<Object>... calls) throws Exception {
        CyclicBarrier start = new CyclicBarrier(calls.length);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Object>> started = new ArrayList<>();
            for (Callable<Object> call : calls) {
                started.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    return call.call();
                }));
            }
            List<Object> answers = new ArrayList<>();
            for (Future<Object> answer : started) {
                answers.add(answer.get(60, TimeUnit.SECONDS));
            }
            return answers;
        }
    }

    /** @return a loopback port nothing listens on: the address of a server that answers nothing */
    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static String check(Row row) {
        return OperationPipeline.invoke(OperationRequest.of(LinkedServerParts.CHECK, CmsPlacementSurface.ADMIN_ACTION)
            .caller(TestAccessContexts.allAllowed())
            .subjectKeys(List.of(String.valueOf((Object) row.get(LinkedServerModel.ID))))).value();
    }

    private static String remove(Row row) {
        return OperationPipeline.invoke(OperationRequest.of(LinkedServerParts.REMOVE, CmsPlacementSurface.ADMIN_ACTION)
            .caller(TestAccessContexts.allAllowed())
            .subjectKeys(List.of(String.valueOf((Object) row.get(LinkedServerModel.ID))))).value();
    }

    /** @return the one linked server of the master */
    private static Row only() {
        List<Row> rows = LinkedServers.all();
        assertThat(rows).as("the master holds one link").hasSize(1);
        return rows.getFirst();
    }

    private static LinkState stateOf(Row row) {
        return LinkState.of(row, LinkedServers.ownVersion());
    }

    /** @return how many webhook bodies carry this alert's catalog key */
    private static int count(String key) {
        return (int) BODIES.stream().filter(body -> body.contains(key)).count();
    }

    private static String english(Microcopy copy) {
        return copy.tryResolve(LocaleChain.ofTags("en"), MessageResolvers.getDefault());
    }

    private static String words(Violations refused) {
        List<String> words = new ArrayList<>();
        for (Violation violation : refused) {
            words.add(english(violation.message()));
        }
        return String.join(" / ", words);
    }

    /** Copies a stopped server's SQLite file, with its write-ahead log when one is left. */
    private static void copy(Path from, Path to) throws Exception {
        Files.copy(from, to);
        for (String suffix : List.of("-wal", "-shm")) {
            Path side = from.resolveSibling(from.getFileName() + suffix);
            if (Files.exists(side)) {
                Files.copy(side, to.resolveSibling(to.getFileName() + suffix));
            }
        }
    }
}
