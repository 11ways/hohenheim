package be.elevenways.hohenheim.server.cluster;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.cluster.JoinRefusal;
import be.elevenways.hohenheim.cluster.LinkRole;
import be.elevenways.hohenheim.cluster.LinkState;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.cms.ClusterAttention;
import be.elevenways.hohenheim.server.cms.LinkedServerParts;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.MessageResolvers;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.util.Uuids;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.delivery.DeliveryChannel;
import be.elevenways.zenit.delivery.server.transport.DsnOrigin;
import be.elevenways.zenit.delivery.server.transport.TransportTypes;
import be.elevenways.zenit.notifications.server.NotificationDispatcher;
import be.elevenways.zenit.notifications.server.Notifications;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.server.security.SecureTokens;
import be.elevenways.zenit.server.setting.ServerSettings;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * and refuse what the one-master rule and the join code forbid; a sweep asks silent servers at the same time.
 *
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

        // The master's calls to Kuifje: its name over TLS, relayed to whichever child listens now.
        relay = TlsRelay.open();
        child = OutboundFixture.routeTls(CHILD_HOST, relay.port());
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
        Models.get(LinkedServerModel.class).find().delete();
    }

    @Test
    void twoHohenheimsJoinCheckEachOtherAndPart(@TempDir Path directory, TestReporter reporter) throws Exception {
        Path kuifjeDb = directory.resolve("kuifje.db");
        ForkedHohenheim kuifje = boot(directory, kuifjeDb, true, reporter);
        relay.start(child.serverContext(), kuifje.port());
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
            int sent = relay.accepted();
            assertThat(LinkedServers.sweep()).as("step 7: the sweep asks it nothing").isZero();
            assertThat(check(other)).isEqualTo(LinkState.OTHER_SERVER.token());
            assertThat(remove(other)).as("step 7: removed here only").isEqualTo(LinkedServers.Removal.NOT_SENT.token());
            assertThat(relay.accepted()).as("step 7: nothing was sent there").isEqualTo(sent);
            assertThat(LinkedServers.all()).isEmpty();
            // The copy still holds the link; removing it there finds no link here any more.
            assertThat(kuifje.ask("remove-master")).isEqualTo("removed " + LinkedServers.Removal.LEFT.token());
            assertThat(kuifje.ask("rows")).isEqualTo("rows -");

            // 8. Remove from the master with Kuifje up: both sides forget the link.
            add(codeOf(kuifje.ask("offer")));
            assertThat(remove(only())).as("step 8: removed on both")
                .isEqualTo(LinkedServers.Removal.LEFT.token());
            assertThat(kuifje.ask("rows")).as("step 8: Kuifje forgot it too").isEqualTo("rows -");
            // And with Kuifje down: the master forgets it and says Kuifje still holds it until removed there.
            add(codeOf(kuifje.ask("offer")));
            kuifje.close();
            assertThat(remove(only())).as("step 8: removed here only")
                .isEqualTo(LinkedServers.Removal.KEPT_THERE.token());
            assertThat(LinkedServers.all()).isEmpty();
            kuifje = boot(directory, copyDb, false, reporter);
            relay.retarget(kuifje.port());
            assertThat(kuifje.ask("rows")).as("step 8: Kuifje still holds the link")
                .startsWith("rows Robbedoes|managed_by|");
            assertThat(kuifje.ask("remove-master")).isEqualTo("removed " + LinkedServers.Removal.LEFT.token());

            // 9. One link is made at a time. Two joins reach Kuifje at the same instant, each with a valid code of
            //    its own: exactly one links a master, the other is refused in words. Two adds on the master at once:
            //    exactly one links Kuifje, the other is refused in words.
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

            // 10. Refusals, each in words: a second master, a managed server adding one, a master getting a master,
            //     then a wrong, a used and an expired code, and the spent failure budget.
            String used = codeOf(kuifje.ask("offer"));
            add(used);
            assertThat(kuifje.ask("offer")).as("step 10: a managed server gets no second master")
                .isEqualTo("refused " + english(JoinRefusal.HAS_MASTER.message()));
            assertThat(kuifje.ask("add Gaston https://gaston.cluster.test WWWW-WWWW-WWWW"))
                .as("step 10: a managed server adds no server")
                .isEqualTo("refused This server is managed by Robbedoes; a managed server manages no other servers.");
            // An operation a fact keeps from running is offered dead with its reason, and refused in those words.
            DomainRefusal mastered = assertThrows(DomainRefusal.class, () -> OperationPipeline.invoke(
                OperationRequest.of(LinkedServerParts.OFFER_JOIN, CmsPlacementSurface.ADMIN_ACTION)
                    .caller(TestAccessContexts.allAllowed())));
            assertThat(english(mastered.shown())).as("step 10: a master gets no master")
                .isEqualTo(english(JoinRefusal.MANAGES_OTHERS.message()));
            remove(only());
            // The master's own address is refused before anything is sent.
            assertThat(words(assertThrows(Violations.class, () -> OperationPipeline.invoke(
                OperationRequest.of(LinkedServerParts.ADD, CmsPlacementSurface.ADMIN_ACTION)
                    .caller(TestAccessContexts.allAllowed())
                    .form(Map.of("name", "Self", "address", "https://" + MASTER_HOST,
                        "join_code", "WWWW-WWWW-WWWW"))))))
                .as("step 10: this server's own address")
                .isEqualTo("This is this server's own address. Enter the address of the other Hohenheim.");

            // The expired code first, then Kuifje's clock stays ahead: the failure budget only ever moves forward.
            String late = codeOf(kuifje.ask("offer"));
            assertThat(kuifje.ask("offset 11")).isEqualTo("ok");
            assertThat(refusedAdd(late)).as("step 10: an expired code")
                .isEqualTo(english(JoinRefusal.EXPIRED.message()));
            assertThat(refusedAdd("WWWW-WWWW-WWWW")).as("step 10: a wrong code")
                .isEqualTo(english(JoinRefusal.NO_SUCH_CODE.message()));
            assertThat(refusedAdd(used)).as("step 10: a used code")
                .isEqualTo(english(JoinRefusal.NO_SUCH_CODE.message()));
            refusedAdd("WWWW-WWWW-WWWX");
            refusedAdd("WWWW-WWWW-WWWY");
            String right = codeOf(kuifje.ask("offer"));
            assertThat(refusedAdd(right)).as("step 10: the budget is spent, so even the right code is not looked at")
                .isEqualTo(english(JoinRefusal.TOO_MANY_TRIES.message()));
            assertThat(LinkedServers.all()).as("step 10: no refused join linked anything").isEmpty();
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

    /** Plants a server this master manages at {@code address}, as an add leaves it before its first check. */
    private static void plant(String name, String address) {
        Row row = Models.get(LinkedServerModel.class).createEmptyRow();
        row.set(LinkedServerModel.ID, Uuids.v7());
        row.set(LinkedServerModel.NAME, name);
        row.set(LinkedServerModel.ROLE, LinkRole.MANAGES.token());
        row.set(LinkedServerModel.BASE_URL, address);
        row.set(LinkedServerModel.LINK_KEY, SecureTokens.randomToken(32));
        row.set(LinkedServerModel.REMOTE_TOKEN, "silent-" + name);
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

    /** @return a join's body as Robbedoes sends it, presenting {@code code} and a key of its own */
    private static Map<String, Object> joinBody(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "Robbedoes");
        body.put("base_url", "https://" + MASTER_HOST);
        body.put("token", ControllerIdentity.token());
        body.put("build", LinkedServers.ownVersion());
        body.put("key", SecureTokens.randomToken(32));
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
