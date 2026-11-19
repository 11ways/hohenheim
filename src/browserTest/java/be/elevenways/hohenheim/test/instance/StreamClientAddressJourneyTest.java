package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.StreamProtocol;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.cms.PutOnline;
import be.elevenways.hohenheim.server.cms.SiteWrites;
import be.elevenways.hohenheim.server.proxy.ProxyProtocolV2;
import be.elevenways.hohenheim.server.proxy.ProxyReloadHooks;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.sitetype.StreamTarget;
import be.elevenways.hohenheim.test.FreePorts;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.operation.OperationRunStatus;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.RateLimitMiddleware;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.server.operation.OperationRun;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What a TCP stream's upstream learns about the client, over real sockets through the proxy's own relay: the public
 * port takes IPv4 and IPv6 clients alike, the upstream sees the proxy until {@code proxy_protocol_v2} is switched on,
 * then a PROXY v2 header naming the real client (TCP4 for an IPv4 one, TCP6 for an IPv6 one) before the client's own
 * bytes; a stream forwarding UDP refuses the setting.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class StreamClientAddressJourneyTest extends HohenheimTestBase {

    private static final String KIND = "hohenheim:stream";
    private static final int FAMILY_BYTE = 13;
    private static final int TCP4 = 0x11;
    private static final int TCP6 = 0x21;

    /** What the upstream got on one connection: the family byte of a header (-1 for none) and the header. */
    private record Seen(int family, ProxyProtocolV2.@Nullable Header header, InetSocketAddress peer, String line) {
    }

    private static ProxyServer proxy;
    private static ServerSocket upstream;
    private static JobRunner upstreams;
    private static final BlockingQueue<Seen> SEEN = new LinkedBlockingQueue<>();

    @BeforeAll
    static void boot() throws Exception {
        upstream = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        upstreams = JobRunner.create("stream-client-address-upstream");
        upstreams.startThread(() -> {
            while (!upstream.isClosed()) {
                try {
                    Socket client = upstream.accept();
                    upstreams.startThread(() -> answer(client));
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
        upstream.close();
        upstreams.shutdownNow();
    }

    @Test
    void aTcpStreamTakesBothFamiliesAndNamesTheRealClientInAProxyHeaderOnlyWhenAsked() throws Exception {
        RateLimitMiddleware.limiter().clear();
        int publicPort = FreePorts.tcpAndUdp();
        InetAddress ipv4 = InetAddress.getByName("127.0.0.1");
        InetAddress ipv6 = InetAddress.getByName("::1");

        // 1. A TCP stream put online with the setting left alone: it is off.
        OperationRun live = PutOnlineJourneyTest.ended(PutOnlineJourneyTest.runOf(OperationPipeline.invoke(
                OperationRequest.of(PutOnline.PUT_STREAM_ONLINE, CmsPlacementSurface.ADMIN_ACTION)
                    .caller(TenantConduits.operator()).form(Map.of(SiteModel.NAME.getName(), "r4p-mail",
                        "forward_host", "127.0.0.1", "forward_port", upstream.getLocalPort(),
                        "listen_port", publicPort, "protocol", "tcp")))),
            TenantConduits.operator(), "r4p-mail");
        assertThat(live.status()).as("step 1: the stream is online (%s)", live.outcome())
            .isEqualTo(OperationRunStatus.SUCCEEDED);
        Row site = Objects.requireNonNull(Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("r4p-mail")).first(), "the stream exists");
        int siteId = site.get(SiteModel.ID);
        assertThat(storedSettings(siteId)).as("step 1: no header asked").doesNotContainEntry("proxy_protocol_v2", true);

        // 2. The public port is bound on the wildcard; step 3 proves it takes both families (Java binds 0.0.0.0 as the
        //    dual-stack :: on an IPv6 host, see StreamRelays).
        InetSocketAddress bound = Objects.requireNonNull(proxy.getStreamTcpAddress(siteId), "the TCP port is bound");
        assertThat(bound.getAddress().isAnyLocalAddress()).as("step 2: every address").isTrue();

        // 3. Off: an IPv4 and an IPv6 client both reach the upstream, which gets no header and sees the proxy.
        for (InetAddress client : List.of(ipv4, ipv6)) {
            Seen seen = roundTrip(client, publicPort, "EHLO plain.example.test");
            assertThat(seen.family()).as("step 3: no PROXY header for a %s client", client).isEqualTo(-1);
            assertThat(seen.line()).as("step 3: the client's bytes arrive first and whole")
                .isEqualTo("EHLO plain.example.test");
            assertThat(seen.peer().getAddress().isLoopbackAddress()).as("step 3: the upstream sees the proxy").isTrue();
        }

        // 4. Switched on on the site form: an IPv4 client's connection starts with a TCP4 header naming it.
        edit(site, publicPort, "tcp", true);
        try (Socket client = connect(ipv4, publicPort)) {
            Seen seen = exchange(client, "EHLO four.example.test");
            assertThat(seen.family()).as("step 4: THE ASSERTION, a TCP4 header").isEqualTo(TCP4);
            ProxyProtocolV2.Header header = Objects.requireNonNull(seen.header());
            assertThat(header.command()).isEqualTo(ProxyProtocolV2.Command.PROXY);
            assertThat(header.sourceAddress()).as("step 4: the client's own address and port")
                .isEqualTo(client.getLocalSocketAddress());
            assertThat(header.destinationAddress()).as("step 4: the public address it connected to")
                .isEqualTo(new InetSocketAddress(ipv4, publicPort));
            assertThat(((InetSocketAddress) header.sourceAddress()).getAddress()).isInstanceOf(Inet4Address.class);
            assertThat(seen.line()).as("step 4: then the client's bytes, unchanged")
                .isEqualTo("EHLO four.example.test");
        }

        // 5. An IPv6 client: a TCP6 header carrying its IPv6 address.
        try (Socket client = connect(ipv6, publicPort)) {
            Seen seen = exchange(client, "EHLO six.example.test");
            assertThat(seen.family()).as("step 5: THE ASSERTION, a TCP6 header").isEqualTo(TCP6);
            ProxyProtocolV2.Header header = Objects.requireNonNull(seen.header());
            assertThat(header.sourceAddress()).as("step 5: the client's IPv6 address and port")
                .isEqualTo(client.getLocalSocketAddress());
            assertThat(((InetSocketAddress) header.sourceAddress()).getAddress()).isEqualTo(ipv6);
            assertThat(header.destinationAddress()).as("step 5: the public IPv6 address it connected to")
                .isEqualTo(new InetSocketAddress(ipv6, publicPort));
            assertThat(seen.line()).isEqualTo("EHLO six.example.test");
        }

        // 6. A stream forwarding UDP, alone or beside TCP, refuses the header on its field, in words; nothing changes.
        for (String protocol : List.of("udp", "both")) {
            Throwable refused = catchThrowable(() -> edit(site, publicPort, protocol, true));
            assertThat(refused).as("step 6: %s with the header is refused", protocol).isInstanceOf(Violations.class);
            Violation first = ((Violations) refused).all().getFirst();
            assertThat(first.path()).as("step 6: on the setting").endsWith("proxy_protocol_v2");
            assertThat(first.message().key()).as("step 6: saying why").isEqualTo("stream_proxy_protocol_tcp_only");
        }
        assertThat(roundTrip(ipv4, publicPort, "EHLO kept.example.test").family())
            .as("step 6: the stored stream still sends it").isEqualTo(TCP4);
        // Any other writer meets the same refusal in the stream's target, so the relay never forwards one headerless.
        for (StreamProtocol protocol : List.of(StreamProtocol.UDP, StreamProtocol.BOTH)) {
            assertThat(catchThrowable(() -> new StreamTarget(publicPort, protocol, "127.0.0.1", 1, 1000, false, true)))
                .as("step 6: the target refuses %s with a header", protocol)
                .isInstanceOf(IllegalArgumentException.class);
        }

        // 7. Switched off again: no header, the upstream sees the proxy once more.
        edit(site, publicPort, "tcp", false);
        assertThat(roundTrip(ipv6, publicPort, "EHLO off.example.test").family()).as("step 7: no header any more")
            .isEqualTo(-1);

        OperationPipeline.invoke(OperationRequest.of(SiteWrites.DELETE, CmsPlacementSurface.ADMIN_ACTION)
            .caller(TenantConduits.operator()).subjects(List.of(Models.get(SiteModel.class).findById(siteId))));
    }

    private static Map<String, Object> storedSettings(int siteId) {
        Map<String, Object> settings = RawValues.mapOrNull(
            Models.get(SiteModel.class).findById(siteId).get(SiteModel.SETTINGS));
        return settings == null ? Map.of() : settings;
    }

    /** The site form's edit of the stream, as the panel posts it. */
    private static void edit(Row site, int publicPort, String protocol, boolean proxyProtocol) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("listen_port", publicPort);
        settings.put("protocol", protocol);
        settings.put("forward_host", "127.0.0.1");
        settings.put("forward_port", upstream.getLocalPort());
        settings.put("connect_timeout", 10);
        settings.put("proxy_protocol_v2", proxyProtocol);
        Map<String, Object> form = new LinkedHashMap<>();
        form.put(SiteModel.NAME.getName(), site.get(SiteModel.NAME));
        form.put(SiteModel.UPSTREAM_KIND.getName(), KIND);
        form.put(SiteModel.SETTINGS.getName(), settings);
        form.put(SiteModel.ENABLED.getName(), true);
        OperationPipeline.invoke(OperationRequest.of(SiteWrites.UPDATE, CmsPlacementSurface.ADMIN_ACTION)
            .caller(TenantConduits.operator())
            .subjects(List.of(Models.get(SiteModel.class).findById(site.get(SiteModel.ID)))).form(form));
    }

    private static Socket connect(InetAddress address, int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(address, port), 2000);
        socket.setSoTimeout(5000);
        return socket;
    }

    private static Seen roundTrip(InetAddress address, int port, String text) throws Exception {
        try (Socket client = connect(address, port)) {
            return exchange(client, text);
        }
    }

    /**
     * Sends one line, waits for its echo through the relay and returns what the upstream saw of that connection. The
     * line is longer than a PROXY v2 fixed header, as the upstream's optional read waits for that many bytes.
     */
    private static Seen exchange(Socket client, String text) throws Exception {
        assertThat(text.length()).as("a line the upstream's header probe can read past")
            .isGreaterThanOrEqualTo(ProxyProtocolV2.FIXED_HEADER_LENGTH);
        SEEN.clear();
        OutputStream output = client.getOutputStream();
        output.write((text + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
        assertThat(readLine(client.getInputStream())).as("the line comes back through the relay").isEqualTo(text);
        return Objects.requireNonNull(SEEN.poll(5, TimeUnit.SECONDS), "the upstream saw the connection");
    }

    /** The upstream: records the first bytes of the connection, reads an optional PROXY header, echoes one line. */
    private static void answer(Socket connection) {
        try (connection) {
            byte[] first = new byte[ProxyProtocolV2.FIXED_HEADER_LENGTH];
            int[] recorded = {0};
            InputStream recording = new FilterInputStream(connection.getInputStream()) {
                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int count = super.read(buffer, offset, length);
                    for (int i = 0; i < count && recorded[0] < first.length; i++) {
                        first[recorded[0]++] = buffer[offset + i];
                    }
                    return count;
                }
            };
            BufferedInputStream input = new BufferedInputStream(recording);
            Optional<ProxyProtocolV2.Header> header = ProxyProtocolV2.readOptional(input);
            String line = readLine(input);
            connection.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
            connection.getOutputStream().flush();
            SEEN.add(new Seen(header.isPresent() ? first[FAMILY_BYTE] & 0xff : -1, header.orElse(null),
                (InetSocketAddress) connection.getRemoteSocketAddress(), line));
        } catch (IOException ended) {
            // the relay closed its side
        }
    }

    private static String readLine(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        int next;
        while ((next = input.read()) >= 0 && next != '\n') {
            line.append((char) next);
        }
        return line.toString();
    }
}
