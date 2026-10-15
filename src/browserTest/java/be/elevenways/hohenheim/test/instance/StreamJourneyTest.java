package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.cms.ProxyAttention;
import be.elevenways.hohenheim.server.cms.PutOnline;
import be.elevenways.hohenheim.server.cms.SiteWrites;
import be.elevenways.hohenheim.server.proxy.ProxyReloadHooks;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.operation.OperationRunStatus;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.RateLimitMiddleware;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.server.operation.OperationRun;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * npmplus's streams in "Put something online": a public port of the proxy's host forwarded, unread, to an address,
 * over real sockets through the proxy's own relay. The port is claimed in the port ledger (a clash is refused), the
 * access list applies by address only, and a delete closes the port and frees the claim.
 *
 * AIDEV-NOTE: not gated on a live lane: the relay is the proxy's own (no nft, no root, no daemon), so this journey
 * runs in every unit run against real TCP and UDP sockets on loopback.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class StreamJourneyTest extends HohenheimTestBase {

    private static final String KIND = "hohenheim:stream";

    private static ProxyServer proxy;
    private static ServerSocket tcpEcho;
    private static DatagramSocket udpEcho;

    @BeforeAll
    static void boot() throws Exception {
        tcpEcho = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!tcpEcho.isClosed()) {
                try {
                    Socket client = tcpEcho.accept();
                    Thread.ofVirtual().start(() -> echo(client));
                } catch (IOException closed) {
                    return;
                }
            }
        });
        // The same number as the TCP echo, so one stream forwarding both transports reaches an echo either way.
        udpEcho = new DatagramSocket(tcpEcho.getLocalPort(), InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            byte[] buffer = new byte[2048];
            while (!udpEcho.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udpEcho.receive(packet);
                    udpEcho.send(new DatagramPacket(packet.getData(), packet.getLength(), packet.getSocketAddress()));
                } catch (IOException closed) {
                    return;
                }
            }
        });
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
        tcpEcho.close();
        udpEcho.close();
    }

    @Test
    void aStreamForwardsItsPublicPortClaimsItRefusesAClashFollowsItsAccessListAndFreesThePortOnDelete()
            throws Exception {
        RateLimitMiddleware.limiter().clear();
        AccessContext operator = TenantConduits.operator();
        int publicPort = freePort();

        // 1. The chooser offers a TCP/UDP stream among the addresses, and its card opens a wizard of its own: what it
        //    forwards to, then its public port; no address and no HTTPS step.
        String chooser = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE).body();
        assertThat(chooser).as("step 1: the stream is a choice of its own").contains("kind:" + KIND);
        String wizard = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?kind=" + KIND).body();
        assertThat(wizard).as("step 1: where it forwards to, then its ports")
            .contains("data-zf-step=\"where\"").contains("data-zf-step=\"ports\"")
            .doesNotContain("data-zf-step=\"https\"");
        assertThat(wizard).as("step 1: the ports step asks the public port, the protocol and the access list")
            .contains("name=\"listen_port\"").contains("name=\"protocol\"").contains("name=\"access_list_id\"")
            .contains("name=\"forward_host\"").doesNotContain("name=\"hostname\"");
        assertThat(wizard).as("step 1: its access-list help speaks of address rules only, as a stream applies them")
            .contains("only its address rules apply to a stream").doesNotContain("addresses, passwords, sign-in");

        // 2. One run claims the public port on the proxy's host and forwards it.
        OperationRun live = run(stream("ssh", publicPort, "tcp", null), operator);
        assertThat(live.status()).as("step 2: the run succeeded (%s)", live.outcome())
            .isEqualTo(OperationRunStatus.SUCCEEDED);
        assertThat(Objects.requireNonNull(live.outcome()).key()).as("step 2: its words say what forwards where")
            .isEqualTo("outcome_stream_live");
        Row site = siteNamed("r4a-ssh");
        int siteId = site.get(SiteModel.ID);
        Row claim = PortLedger.holderOf(PortLedger.claimKeyOf(ServerModel.localServerId(), "", publicPort, "tcp"));
        assertThat(claim).as("step 2: the port is claimed in the ledger").isNotNull();
        assertThat(PortLedger.isOwnedBy(claim, SiteModel.MODEL_ID, siteId)).as("step 2: by the stream").isTrue();

        // 3. THE ASSERTION: a connection to the public port reaches the upstream and back, through the real relay.
        assertThat(tcpRoundTrip(publicPort, "ping")).as("step 3: the TCP stream forwards both ways").isEqualTo("ping");

        // 3b. Its page frames it as a stream, never a website: where clients connect, no address asked or missing,
        //     no path to protect.
        String overview = adminGet("/admin/sites/" + siteId + "/page/overview").body();
        assertThat(overview).as("step 3b: THE ASSERTION, the overview says where clients connect")
            .contains("Connect to").contains(String.valueOf(publicPort)).contains("Forwards port " + publicPort);
        assertThat(overview).as("step 3b: and nothing a website has, its More menu offering no address either")
            .doesNotContain("No address yet").doesNotContain("Protect a path").doesNotContain("Add address")
            .doesNotContain("Add an address");

        // 3c. Its edit form speaks of a stream: the access-list pick says what the wizard's said (address rules
        //     only), the Enabled switch what switching off a stream stops, and no sign-in provider is offered, as its
        //     save refuses one.
        String form = adminGet("/admin/sites/" + siteId).body();
        assertThat(form).as("step 3c: THE ASSERTION, the site form's access-list help is the stream's")
            .contains("name=\"access_list_id\"").contains("only its address rules apply to a stream")
            .doesNotContain("addresses, passwords, sign-in");
        assertThat(form).as("step 3c: its Enabled help is the stream's, never the website's offline page")
            .contains("the stream forwards nothing").doesNotContain("requests get the offline page");
        assertThat(form).as("step 3c: no auth provider pick").doesNotContain("name=\"auth_provider_id\"");

        // 3d. The Sites list shows the stream's port where a website shows its hostnames, and no HTTPS state.
        String list = adminGet("/admin/sites?q=r4a-ssh").body();
        assertThat(list).as("step 3d: its port in the address cell").contains(publicPort + "/tcp");
        assertThat(list).as("step 3d: no missing address, no HTTPS state").doesNotContain("No address");

        // 4. A second stream on the same port is refused in the run, naming the holder; nothing is created.
        OperationRun clash = run(stream("clash", publicPort, "tcp", null), operator);
        assertThat(clash.status()).as("step 4: the clash is refused").isEqualTo(OperationRunStatus.REFUSED);
        assertThat(Objects.requireNonNull(clash.outcome()).key()).as("step 4: naming the port's holder")
            .isEqualTo("port_conflict");
        assertThat(Models.get(SiteModel.class).find().where(SiteModel.NAME.eq("r4a-clash")).first())
            .as("step 4: no second stream exists").isNull();
        assertThat(tcpRoundTrip(publicPort, "still")).as("step 4: the first one still forwards").isEqualTo("still");

        // 5. Switched to TCP and UDP on the site form: datagrams are forwarded too, under a UDP claim of their own.
        edit(site, publicPort, "both", null);
        assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(ServerModel.localServerId(), "", publicPort, "udp")))
            .as("step 5: the UDP port is claimed too").isNotNull();
        assertThat(udpRoundTrip(publicPort, "pong")).as("step 5: THE ASSERTION, UDP forwards both ways")
            .isEqualTo("pong");
        assertThat(tcpRoundTrip(publicPort, "both")).as("step 5: and TCP still does").isEqualTo("both");

        // 6. An access list denying the client's network: connections and datagrams from it are dropped.
        int deny = accessList("r4a deny loopback", AccessRuleModel.TYPE_IP_DENY,
            Map.of(AccessRuleModel.NETWORK.getName(), "127.0.0.0/8"));
        edit(site, publicPort, "both", deny);
        assertThat(tcpRoundTrip(publicPort, "denied")).as("step 6: a denied client's connection is closed").isNull();
        assertThat(udpRoundTrip(publicPort, "denied")).as("step 6: and its datagrams dropped").isNull();

        // 7. A list asking for a password cannot apply to a stream: refused on the pick, in words, by the wizard and
        //    by the site form alike.
        int basic = accessList("r4a basic auth", AccessRuleModel.TYPE_BASIC_AUTH,
            Map.of("username", "someone", "password", "secret-words"));
        DomainRefusal wizardRefusal = catchThrowableOfType(DomainRefusal.class,
            () -> OperationPipeline.invoke(request(stream("basic", freePort(), "tcp", basic))));
        assertThat(List.of(String.valueOf(wizardRefusal.field()),
                Objects.requireNonNull(wizardRefusal.words()).key()))
            .as("step 7: the wizard refuses it before any run")
            .containsExactly(SiteModel.ACCESS_LIST_ID.getName(), "stream_access_list_credentials");
        Throwable formRefusal = catchThrowable(() -> edit(site, publicPort, "both", basic));
        assertThat(formRefusal).as("step 7: the site form refuses it").isInstanceOf(Violations.class);
        Violation first = ((Violations) formRefusal).all().getFirst();
        assertThat(List.of(first.fieldName(), first.message().key())).as("step 7: on the pick, in the same words")
            .containsExactly(SiteModel.ACCESS_LIST_ID.getName(), "stream_access_list_credentials");

        // 7b. Another program took the port while the stream was switched off: switched on again, the proxy cannot
        //     open it. THE ASSERTION: the dashboard raises it as an error naming the port, who holds it and what to
        //     do, and the site's verdict gives the same words; once the port is free, the supervisor opens it and the
        //     item goes.
        edit(site, publicPort, "tcp", null);
        switchOn(siteId, false);
        try (ServerSocket squatter = new ServerSocket()) {
            squatter.bind(new InetSocketAddress("0.0.0.0", publicPort));
            switchOn(siteId, true);
            List<AttentionItem> failing = streamItems();
            assertThat(failing).as("step 7b: one item, an error").hasSize(1)
                .allSatisfy(item -> assertThat(item.severity()).isEqualTo(AttentionSeverity.ERROR));
            AttentionItem item = failing.getFirst();
            assertThat(item.title().resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver()))
                .as("step 7b: titled by the stream and its port").contains("r4a-ssh").contains(String.valueOf(publicPort));
            String words = Objects.requireNonNull(item.detail()).resolve(LocaleChain.ofTags("en"),
                Zenit.getMessageResolver());
            assertThat(words).as("step 7b: which port, who holds it and what to do")
                .contains("Port " + publicPort + "/tcp").contains("is already held by a program running as")
                .contains("give the stream another public port");
            assertThat(adminGet("/admin/sites/" + siteId + "/page/overview").body())
                .as("step 7b: the site's verdict says the same").contains("Not forwarding")
                .contains("is already held by");
        }
        proxy.superviseListeners();
        assertThat(streamItems()).as("step 7b: the freed port is opened by the supervisor and the item goes").isEmpty();
        assertThat(tcpRoundTrip(publicPort, "back")).as("step 7b: and it forwards again").isEqualTo("back");

        // 8. Delete: the port closes and both claims go, so the port can be claimed again at once.
        OperationPipeline.invoke(OperationRequest.of(SiteWrites.DELETE, CmsPlacementSurface.ADMIN_ACTION)
            .caller(operator).subjects(List.of(Models.get(SiteModel.class).findById(siteId))));
        assertThat(PortLedger.claimsOf(SiteModel.MODEL_ID, siteId)).as("step 8: the claims are freed").isEmpty();
        assertThat(catchThrowable(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), publicPort), 2000);
            }
        })).as("step 8: THE ASSERTION, nothing listens on the public port any more").isInstanceOf(IOException.class);
        OperationRun again = run(stream("again", publicPort, "udp", null), operator);
        assertThat(again.status()).as("step 8: the freed port is claimed again (%s)", again.outcome())
            .isEqualTo(OperationRunStatus.SUCCEEDED);
        assertThat(udpRoundTrip(publicPort, "again")).as("step 8: and forwards").isEqualTo("again");
        OperationPipeline.invoke(OperationRequest.of(SiteWrites.DELETE, CmsPlacementSurface.ADMIN_ACTION)
            .caller(operator).subjects(List.of(siteNamed("r4a-again"))));
        assertThat(Models.get(PortAllocationModel.class).find()
                .where(PortAllocationModel.OWNER_MODEL.eq(SiteModel.MODEL_ID.toString())).all())
            .as("step 8: no stream holds a port any more").isEmpty();
    }

    /**
     * The failing proxy listeners that are streams; an HTTPS listener another class left failing in this shared JVM
     * (a privileged port it could not bind) is not this journey's.
     */
    private static List<AttentionItem> streamItems() {
        List<AttentionItem> failing = new ArrayList<>();
        ProxyAttention.failedProxyListeners(failing);
        return failing.stream().filter(item -> item.title().key().equals("stream_not_forwarding")).toList();
    }

    private static Map<String, Object> stream(String name, int publicPort, String protocol, Integer accessList) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put(SiteModel.NAME.getName(), "r4a-" + name);
        form.put("forward_host", "127.0.0.1");
        form.put("forward_port", tcpEcho.getLocalPort());
        form.put("listen_port", publicPort);
        form.put("protocol", protocol);
        if (accessList != null) {
            form.put(SiteModel.ACCESS_LIST_ID.getName(), accessList);
        }
        return form;
    }

    /** The site form's edit of a stream, as the panel posts it: TCP and UDP forward to the echo servers' ports. */
    private static void edit(Row site, int publicPort, String protocol, Integer accessList) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("listen_port", publicPort);
        settings.put("protocol", protocol);
        settings.put("forward_host", "127.0.0.1");
        settings.put("forward_port", tcpEcho.getLocalPort());
        settings.put("connect_timeout", 10);
        Map<String, Object> form = new LinkedHashMap<>();
        form.put(SiteModel.NAME.getName(), site.get(SiteModel.NAME));
        form.put(SiteModel.UPSTREAM_KIND.getName(), KIND);
        form.put(SiteModel.SETTINGS.getName(), settings);
        form.put(SiteModel.ENABLED.getName(), true);
        form.put(SiteModel.ACCESS_LIST_ID.getName(), accessList);
        OperationPipeline.invoke(OperationRequest.of(SiteWrites.UPDATE, CmsPlacementSurface.ADMIN_ACTION)
            .caller(TenantConduits.operator())
            .subjects(List.of(Models.get(SiteModel.class).findById(site.get(SiteModel.ID)))).form(form));
    }

    /** Switches a stream on or off with a plain save, as the enable switch does. */
    private static void switchOn(int siteId, boolean enabled) {
        Row stored = Models.get(SiteModel.class).findById(siteId);
        stored.set(SiteModel.ENABLED, enabled);
        Models.get(SiteModel.class).save(stored);
    }

    private static OperationRequest request(Map<String, Object> form) {
        return OperationRequest.of(PutOnline.PUT_STREAM_ONLINE, CmsPlacementSurface.ADMIN_ACTION)
            .caller(TenantConduits.operator()).form(form);
    }

    private static OperationRun run(Map<String, Object> form, AccessContext viewer) throws InterruptedException {
        return PutOnlineJourneyTest.ended(PutOnlineJourneyTest.runOf(OperationPipeline.invoke(request(form))), viewer,
            String.valueOf(form.get(SiteModel.NAME.getName())));
    }

    private static Row siteNamed(String name) {
        return Objects.requireNonNull(Models.get(SiteModel.class).find().where(SiteModel.NAME.eq(name)).first(),
            "the site " + name + " exists");
    }

    private static int accessList(String name, String type, Map<String, Object> data) {
        Row list = Models.get(AccessListModel.class).createEmptyRow();
        list.set(AccessListModel.NAME, name);
        list.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ALL);
        Models.get(AccessListModel.class).save(list);
        Row rule = Models.get(AccessRuleModel.class).createEmptyRow();
        rule.set(AccessRuleModel.ACCESS_LIST_ID, list.get(AccessListModel.ID));
        rule.set(AccessRuleModel.TYPE, type);
        rule.set(AccessRuleModel.DATA, new LinkedHashMap<>(data));
        rule.set(AccessRuleModel.ENABLED, true);
        Models.get(AccessRuleModel.class).save(rule);
        return list.get(AccessListModel.ID);
    }

    /** @return what came back through the public port, null when the relay closed the connection without an answer */
    private static String tcpRoundTrip(int port, String text) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write((text + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            InputStream input = socket.getInputStream();
            StringBuilder line = new StringBuilder();
            int next;
            while ((next = input.read()) >= 0 && next != '\n') {
                line.append((char) next);
            }
            return next < 0 && line.isEmpty() ? null : line.toString();
        } catch (IOException reset) {
            return null;
        }
    }

    /** @return the datagram that came back through the public port, null when none came within two seconds */
    private static String udpRoundTrip(int port, String text) throws IOException {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            socket.setSoTimeout(2000);
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(payload, payload.length, InetAddress.getLoopbackAddress(), port));
            DatagramPacket answer = new DatagramPacket(new byte[2048], 2048);
            socket.receive(answer);
            return new String(answer.getData(), 0, answer.getLength(), StandardCharsets.UTF_8);
        } catch (SocketTimeoutException silent) {
            return null;
        }
    }

    private static void echo(Socket client) {
        try (client) {
            client.getInputStream().transferTo(client.getOutputStream());
        } catch (IOException ended) {
            // the relay closed its side
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
