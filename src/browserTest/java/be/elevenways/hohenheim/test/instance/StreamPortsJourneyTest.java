package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.ports.StreamPort;
import be.elevenways.hohenheim.ports.StreamProtocol;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.cms.PutOnline;
import be.elevenways.hohenheim.server.cms.SitePortMoves;
import be.elevenways.hohenheim.server.cms.SiteWrites;
import be.elevenways.hohenheim.server.proxy.ProxyReloadHooks;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.test.FreePorts;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.operation.OperationRunStatus;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.orm.revision.RevisionModel;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.RateLimitMiddleware;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.server.operation.OperationRun;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * One TCP/UDP stream carries every port of one service: a mail server's stream is put online with three ports to one
 * address, each forwarding to its own port there; a fourth is added, one changed and one removed on the site form; a
 * port another stream holds is refused by name; a second stream's two ports are moved into it while a client stays
 * connected, the emptied stream lands in the trash, and the Apps list shows the service as one row naming its ports,
 * and a revision stored in the single-port shape before the upgrade restores as a list of one.
 *
 * AIDEV-NOTE: the proxy's own relay over real loopback sockets, as StreamJourneyTest: no nft, no root, no daemon. Each
 * upstream answers with its own name, so a round trip proves which port at the address a public port reached.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class StreamPortsJourneyTest extends HohenheimTestBase {

    private static final String KIND = "hohenheim:stream";

    private static final List<ServerSocket> UPSTREAMS = new ArrayList<>();

    private static ProxyServer proxy;
    private static JobRunner echoes;

    @BeforeAll
    static void boot() throws Exception {
        echoes = JobRunner.create("stream-ports-journey-echo");
        for (int i = 0; i < 6; i++) {
            ServerSocket upstream = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            UPSTREAMS.add(upstream);
            String name = "u" + i;
            echoes.startThread(() -> {
                while (!upstream.isClosed()) {
                    try {
                        Socket client = upstream.accept();
                        echoes.startThread(() -> echo(client, name));
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
        }
        proxy = ProxyTestSupport.startProxy();
        ServerMain.adoptProxyServer(proxy);
        ProxyReloadHooks.install();
    }

    @AfterAll
    static void tearDown() throws IOException {
        ServerMain.adoptProxyServer(null);
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        for (ServerSocket upstream : UPSTREAMS) {
            upstream.close();
        }
        echoes.shutdownNow();
    }

    @Test
    void oneStreamCarriesEveryPortOfAMailServerAndTakesInThePortsOfAnother() throws Exception {
        RateLimitMiddleware.limiter().clear();
        AccessContext operator = TenantConduits.operator();
        List<Integer> port = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            port.add(FreePorts.tcpAndUdp());
        }

        // 1. Put online through the panel's wizard: one stream, three public ports, each to its own port upstream.
        Map<String, String> three = new LinkedHashMap<>();
        three.put(String.valueOf(port.get(0)), upstream(0) + "/tcp");
        three.put(String.valueOf(port.get(1)), upstream(1) + "/tcp");
        three.put(String.valueOf(port.get(2)), upstream(2) + "/tcp");
        OperationRun live = run(stream("r5-mail", three), operator);
        assertThat(live.status()).as("step 1: the run succeeded (%s)", live.outcome())
            .isEqualTo(OperationRunStatus.SUCCEEDED);
        assertThat(english(live)).as("step 1: its words name every port")
            .contains(port.get(0) + ", " + port.get(1) + ", " + port.get(2));
        Row mail = siteNamed("r5-mail");
        int mailId = mail.get(SiteModel.ID);
        for (int i = 0; i < 3; i++) {
            assertThat(roundTrip(port.get(i), "EHLO " + i)).as("step 1: THE ASSERTION, port %s reaches its own"
                + " port upstream", port.get(i)).isEqualTo("u" + i + ":EHLO " + i);
            assertThat(PortLedger.isOwnedBy(Objects.requireNonNull(claim(port.get(i))), SiteModel.MODEL_ID, mailId))
                .as("step 1: port %s is the stream's in the ledger", port.get(i)).isTrue();
        }

        // 2. The site's page shows the ports as rows of a table the form adds to, changes and removes from.
        String page = adminGet("/admin/" + HohenheimSlugs.SITES + "/" + mailId).body();
        assertThat(page).as("step 2: the site form holds the ports table").contains("Public ports")
            .contains(String.valueOf(port.get(0))).contains(upstream(0) + "/tcp")
            .contains(String.valueOf(port.get(2))).contains(upstream(2) + "/tcp");

        // 3. A fourth port added on the site form, one changed to another port upstream, one removed.
        Map<String, String> four = new LinkedHashMap<>(three);
        four.put(String.valueOf(port.get(3)), upstream(3) + "/tcp");
        edit(mail, four);
        assertThat(roundTrip(port.get(3), "added")).as("step 3: the added port forwards").isEqualTo("u3:added");
        assertThat(roundTrip(port.get(0), "kept")).as("step 3: the others keep forwarding").isEqualTo("u0:kept");
        four.put(String.valueOf(port.get(1)), upstream(2) + "/tcp");
        four.remove(String.valueOf(port.get(2)));
        edit(mail, four);
        assertThat(roundTrip(port.get(1), "changed")).as("step 3: the changed port reaches its new port upstream")
            .isEqualTo("u2:changed");
        assertThat(roundTrip(port.get(2), "removed")).as("step 3: THE ASSERTION, the removed port is closed")
            .isNull();
        assertThat(claim(port.get(2))).as("step 3: and free in the ledger").isNull();

        // 4. Another stream asking a port this one holds is refused, naming the stream and the port.
        OperationRun clash = run(stream("r5-clash", Map.of(String.valueOf(port.get(0)), upstream(5) + "/tcp")),
            operator);
        assertThat(clash.status()).as("step 4: the clash is refused").isEqualTo(OperationRunStatus.REFUSED);
        assertThat(english(clash)).as("step 4: THE ASSERTION, by the holding stream and its port")
            .contains("r5-mail").contains(String.valueOf(port.get(0)));
        assertThat(Models.get(SiteModel.class).find().where(SiteModel.NAME.eq("r5-clash")).first())
            .as("step 4: nothing was made").isNull();

        // 5. A second stream of the same address with two ports; a client connects to one and stays connected.
        Map<String, String> two = new LinkedHashMap<>();
        two.put(String.valueOf(port.get(4)), upstream(4) + "/tcp");
        two.put(String.valueOf(port.get(5)), upstream(5) + "/tcp");
        assertThat(run(stream("r5-mail-submission", two), operator).status()).as("step 5: the second stream")
            .isEqualTo(OperationRunStatus.SUCCEEDED);
        Row second = siteNamed("r5-mail-submission");
        int secondId = second.get(SiteModel.ID);
        try (Socket held = connect(port.get(4))) {
            assertThat(exchange(held, "before")).as("step 5: the held connection works").isEqualTo("u4:before");

            // 6. A stream of another address cannot take them: refused in words, nothing moves.
            assertThat(run(stream("r5-elsewhere", Map.of(String.valueOf(FreePorts.tcpAndUdp()), "25/tcp"), "10.0.0.9"),
                operator).status()).as("step 6: a stream elsewhere").isEqualTo(OperationRunStatus.SUCCEEDED);
            Throwable elsewhere = catchThrowable(() -> move(second, siteNamed("r5-elsewhere")));
            assertThat(elsewhere).as("step 6: refused").isInstanceOf(Violations.class);
            assertThat(((Violations) elsewhere).all().getFirst().message().key())
                .as("step 6: because it forwards to another address").isEqualTo("site_ports_move_other_address");

            // 7. THE MOVE: the second stream's ports into the first, in one go.
            move(second, Models.get(SiteModel.class).findById(mailId));
            Map<String, Object> ports = RawValues.map(RawValues.map(Models.get(SiteModel.class).findById(mailId)
                .get(SiteModel.SETTINGS)).get("ports"));
            assertThat(ports.keySet()).as("step 7: THE ASSERTION, the first stream holds every port").containsExactly(
                String.valueOf(port.get(0)), String.valueOf(port.get(1)), String.valueOf(port.get(3)),
                String.valueOf(port.get(4)), String.valueOf(port.get(5)));
            assertThat(Models.get(SiteModel.class).findById(secondId)).as("step 7: the second is gone").isNull();
            Row trashed = Models.get(SiteModel.class).find().withTrashed().where(SiteModel.ID.eq(secondId)).first();
            assertThat(trashed.get(SiteModel.DELETED_AT)).as("step 7: into the trash").isNotNull();
            assertThat(PortLedger.isOwnedBy(Objects.requireNonNull(claim(port.get(5))), SiteModel.MODEL_ID, mailId))
                .as("step 7: the moved ports are the first stream's in the ledger").isTrue();
            assertThat(exchange(held, "after")).as("step 7: THE ASSERTION, the held connection never dropped")
                .isEqualTo("u4:after");
            assertThat(roundTrip(port.get(5), "moved")).as("step 7: a new connection forwards too")
                .isEqualTo("u5:moved");
        }

        // 8. The Apps list shows the mail server as one row naming every port, the emptied stream nowhere.
        String apps = adminGet("/admin/" + HohenheimSlugs.APPS + "?_search=r5-mail").body();
        assertThat(rows(apps, "website-" + mailId)).as("step 8: one row for the mail server").isEqualTo(1);
        assertThat(rows(apps, "website-" + secondId)).as("step 8: none for the emptied stream").isZero();
        assertThat(apps).as("step 8: THE ASSERTION, its ports summed up in the row")
            .contains(port.get(0) + ", " + port.get(1) + ", " + port.get(3) + ", " + port.get(4) + ", "
                + port.get(5));

        // 9. A revision stored before the upgrade keeps the single-port shape; restoring it gives a list of one.
        oldShapeRevision(mailId, port.get(0), upstream(3));
        SiteModel.REVISIONABLE.restore(Models.get(SiteModel.class), mailId, 1);
        assertThat(StreamPort.of(RawValues.map(Models.get(SiteModel.class).findById(mailId).get(SiteModel.SETTINGS))))
            .as("step 9: THE ASSERTION, the old revision restores as a list of one")
            .containsExactly(new StreamPort(port.get(0), upstream(3), StreamProtocol.TCP, false));
        assertThat(roundTrip(port.get(0), "restored")).as("step 9: and its port forwards where the revision said")
            .isEqualTo("u3:restored");

        for (String name : List.of("r5-mail", "r5-elsewhere")) {
            OperationPipeline.invoke(OperationRequest.of(SiteWrites.DELETE, CmsPlacementSurface.ADMIN_ACTION)
                .caller(operator).subjects(List.of(siteNamed(name))));
        }
        assertThat(PortLedger.claimsOf(SiteModel.MODEL_ID, mailId)).as("step 8: the claims are freed").isEmpty();
    }

    private static int upstream(int index) {
        return UPSTREAMS.get(index).getLocalPort();
    }

    private static Map<String, Object> stream(String name, Map<String, String> ports) {
        return stream(name, ports, "127.0.0.1");
    }

    private static Map<String, Object> stream(String name, Map<String, String> ports, String host) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put(SiteModel.NAME.getName(), name);
        form.put("forward_host", host);
        form.put("ports", StreamPortRows.of(ports));
        return form;
    }

    /** The site form's edit of the stream's ports, as the panel posts it. */
    private static void edit(Row site, Map<String, String> ports) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("forward_host", "127.0.0.1");
        settings.put("ports", StreamPortRows.of(ports));
        settings.put("connect_timeout", 10);
        Map<String, Object> form = new LinkedHashMap<>();
        form.put(SiteModel.NAME.getName(), site.get(SiteModel.NAME));
        form.put(SiteModel.UPSTREAM_KIND.getName(), KIND);
        form.put(SiteModel.SETTINGS.getName(), settings);
        form.put(SiteModel.ENABLED.getName(), true);
        OperationPipeline.invoke(OperationRequest.of(SiteWrites.UPDATE, CmsPlacementSurface.ADMIN_ACTION)
            .caller(TenantConduits.operator())
            .subjects(List.of(Models.get(SiteModel.class).findById(site.get(SiteModel.ID)))).form(form));
    }

    /** The panel's Move ports action on {@code from}, picking {@code into}. */
    private static void move(Row from, Row into) {
        OperationPipeline.invoke(OperationRequest.of(SitePortMoves.MOVE, CmsPlacementSurface.ADMIN_ACTION)
            .caller(TenantConduits.operator()).subjects(List.of(from))
            .form(Map.of(SitePortMoves.INTO, String.valueOf((Object) into.get(SiteModel.ID)))));
    }

    /** Rewrites the site's first revision as the single-port shape stored it before the ports became a list. */
    @SuppressWarnings("unchecked")
    private static void oldShapeRevision(int siteId, int listen, int forward) {
        RevisionModel revisions = new RevisionModel(Models.get(SiteModel.class).getResolvedDatasource());
        Row revision = Objects.requireNonNull(revisions.find()
            .where(RevisionModel.MODEL.eq(SiteModel.MODEL_ID.toString()))
            .where(RevisionModel.RECORD_ID.eq(String.valueOf(siteId)))
            .where(RevisionModel.REVISION.eq(1)).first(), "the site's first revision");
        Map<String, Object> snapshot = (Map<String, Object>) Zenit.DRY.parse(revision.get(RevisionModel.SNAPSHOT));
        Map<String, Object> fields = (Map<String, Object>) snapshot.get("fields");
        Map<String, Object> settings = new LinkedHashMap<>((Map<String, Object>) fields.get("settings"));
        settings.remove("ports");
        settings.put("listen_port", listen);
        settings.put("forward_port", forward);
        settings.put("protocol", "tcp");
        settings.put("proxy_protocol_v2", false);
        fields.put("settings", settings);
        revision.set(RevisionModel.SNAPSHOT, Zenit.DRY.stringify(snapshot));
        revisions.save(revision);
    }

    private static OperationRun run(Map<String, Object> form, AccessContext viewer) throws InterruptedException {
        return PutOnlineJourneyTest.ended(PutOnlineJourneyTest.runOf(OperationPipeline.invoke(
                OperationRequest.of(PutOnline.PUT_STREAM_ONLINE, CmsPlacementSurface.ADMIN_ACTION)
                    .caller(TenantConduits.operator()).form(form))), viewer,
            String.valueOf(form.get(SiteModel.NAME.getName())));
    }

    private static String english(OperationRun run) {
        return Objects.requireNonNull(run.outcome()).resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver());
    }

    private static Row siteNamed(String name) {
        return Objects.requireNonNull(Models.get(SiteModel.class).find().where(SiteModel.NAME.eq(name)).first(),
            "the site " + name + " exists");
    }

    private static Row claim(int port) {
        return PortLedger.holderOf(PortLedger.claimKeyOf(HostModel.localHostId(), "", port, "tcp"));
    }

    private static int rows(String list, String key) {
        Matcher matcher = Pattern.compile("data-row-key=\"" + Pattern.quote(key) + "\"").matcher(list);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
        socket.setSoTimeout(5000);
        return socket;
    }

    /** @return the upstream's answer to one line on an open connection, null when the relay closed it */
    private static String exchange(Socket socket, String line) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            .readLine();
    }

    /** @return what came back through the public port, null when nothing listens or the relay closed it */
    private static String roundTrip(int port, String line) {
        try (Socket socket = connect(port)) {
            return exchange(socket, line);
        } catch (IOException refused) {
            return null;
        }
    }

    /** Answers every line with the upstream's name before it, until the relay closes the connection. */
    private static void echo(Socket client, String name) {
        try (client) {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(),
                StandardCharsets.UTF_8));
            OutputStream out = client.getOutputStream();
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                out.write((name + ":" + line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException ended) {
            // the relay closed its side
        }
    }
}
