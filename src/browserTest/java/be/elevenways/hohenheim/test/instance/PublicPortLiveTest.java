package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.LiveIdOffsets;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Public port publishing against a REAL daemon, asserting KERNEL facts: the safe
 * default binds loopback only (and a non-loopback connect FAILS), a declared public
 * port is genuinely reachable from a non-loopback address, UDP works through the
 * pre-allocation strategy (record-after cannot see UDP at all), the pre-allocated
 * number survives stop/redeploy, and a fixed-port conflict is refused by the ledger
 * with the second container never created.
 */
class PublicPortLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-public-port-live-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // Unique per-class instance ids => unique daemon handles (no cross-class 409s).
        LiveIdOffsets.apply(datasource);
        HohenheimTestRuntime.ensureBooted();
        if (PrivateNetns.available()) {
            netns = new PrivateNetns();
            WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
        }
    }

    @AfterAll
    static void tearDown() {
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
            netns = null;
        }
    }

    @Test
    void loopbackDefaultHoldsAndPublicMustBeDeclared() throws Exception {
        DockerClient docker = requireDaemon("nginx:alpine");
        InetAddress outside = nonLoopbackAddress();

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            InstanceService service = new InstanceService();

            // ---- the safe default: NOTHING declared beyond a container port --------
            Map<String, Object> defaults = new LinkedHashMap<>();
            defaults.put("image", "nginx");
            defaults.put("tag", "alpine");
            defaults.put("container_port", 80);
            int quietId = instanceRecord("port-default", defaults);
            String quietHandle = "hohenheim-instance-" + quietId;

            // ---- the declared public workload --------------------------------------
            Map<String, Object> declared = new LinkedHashMap<>();
            declared.put("image", "nginx");
            declared.put("tag", "alpine");
            declared.put("container_port", 80);
            declared.put("port_exposure", "public");
            int publicId = instanceRecord("port-public", declared);
            String publicHandle = "hohenheim-instance-" + publicId;

            try {
                // 1. SAFE DEFAULT: deploy publishes on 127.0.0.1 -- asserted off the
                //    DAEMON's own inspect, never off the spec we sent.
                InstanceStatus quiet = service.deploy(quietId);
                assertThat(quiet.publishedPort())
                    .as("step 1: the default publication exists").isNotNull();
                assertThat(quiet.publishedBind())
                    .as("step 1: the DAEMON reports a loopback bind for the undeclared"
                        + " workload")
                    .isEqualTo("127.0.0.1");
                assertThat(hostBinding(docker, quietHandle, "80/tcp"))
                    .as("step 1: the raw inspect binding is 127.0.0.1")
                    .startsWith("127.0.0.1:");
                Row quietClaim = onlyClaimOf(quietId);
                assertThat((String) quietClaim.get(PortAllocationModel.HOST_IP))
                    .as("step 1: the ledger row records the loopback bind")
                    .isEqualTo("127.0.0.1");
                assertThat(PortLedger.isPreallocated(quietClaim))
                    .as("step 1: the default rides record-after, not pre-allocation")
                    .isFalse();

                // 2. Loopback answers; the SAME port on a non-loopback address REFUSES.
                assertThat(httpGet(InetAddress.getLoopbackAddress(), quiet.publishedPort()))
                    .as("step 2: the workload answers over loopback")
                    .contains("nginx");
                Throwable refused = catchThrowable(
                    () -> connectOnce(outside, quiet.publishedPort()));
                assertThat(refused)
                    .as("step 2: connecting to the loopback-bound port from a"
                        + " non-loopback address (" + outside.getHostAddress() + ") FAILS")
                    .isInstanceOf(IOException.class);

                // 3. DECLARED PUBLIC: the claim is PRE-ALLOCATED (mode-stamped,
                //    whole-host bind, inside the configured window) and the daemon
                //    bound exactly it on 0.0.0.0.
                InstanceStatus exposed = service.deploy(publicId);
                Row publicClaim = onlyClaimOf(publicId);
                assertThat(PortLedger.isPreallocated(publicClaim))
                    .as("step 3: the public claim carries the pre-allocation mode")
                    .isTrue();
                assertThat((String) publicClaim.get(PortAllocationModel.HOST_IP))
                    .as("step 3: the public claim is the whole-host bind")
                    .isEqualTo("");
                int claimedPort = publicClaim.get(PortAllocationModel.PORT);
                assertThat(claimedPort)
                    .as("step 3: the number came from the pre-allocation window")
                    .isBetween(30000, 31999);
                assertThat(exposed.publishedPort())
                    .as("step 3: the daemon bound the CLAIMED number")
                    .isEqualTo(claimedPort);
                assertThat(exposed.publishedBind())
                    .as("step 3: the daemon reports the public bind")
                    .isEqualTo("0.0.0.0");

                // 4. The declared public port is GENUINELY reachable from outside
                //    loopback, answering the workload's response.
                assertThat(httpGet(outside, claimedPort))
                    .as("step 4: the public port answers on " + outside.getHostAddress())
                    .contains("nginx");

                // 5. STOP keeps the reservation (the stable number is the point);
                //    REDEPLOY binds the SAME number again and it answers again.
                service.stop(publicId);
                Row kept = onlyClaimOf(publicId);
                assertThat(kept.get(PortAllocationModel.PORT).equals(claimedPort)
                        && !PortLedger.isReleasing(kept))
                    .as("step 5: the pre-allocated claim survives the stop, held")
                    .isTrue();
                InstanceStatus again = service.deploy(publicId);
                assertThat(again.publishedPort())
                    .as("step 5: redeploy binds the SAME pre-allocated number")
                    .isEqualTo(claimedPort);
                assertThat(httpGet(outside, claimedPort))
                    .as("step 5: and it answers again")
                    .contains("nginx");

                // 6. DESTROY releases the reservation with the workload.
                service.destroy(publicId);
                assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, publicId))
                    .as("step 6: destroy releases the pre-allocated claim")
                    .isEmpty();
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                quietDestroy(service, quietId);
                quietDestroy(service, publicId);
                quietRemoveVolume(docker, quietHandle);
                quietRemoveVolume(docker, publicHandle);
            }
        });
    }

    @Test
    void udpReachesTheWorkloadThroughPreallocation() throws Exception {
        DockerClient docker = requireDaemon("alpine:latest");
        InetAddress outside = nonLoopbackAddress();

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            InstanceService service = new InstanceService();
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("image", "alpine");
            settings.put("tag", "latest");
            settings.put("command", "sh /udp-pong.sh");
            settings.put("container_port", 5000);
            settings.put("port_protocol", "udp");
            settings.put("port_exposure", "public");
            int id = instanceRecord("port-udp", settings);
            String handle = "hohenheim-instance-" + id;
            // The responder script rides the config-file staging lane (create -> stage
            // -> start), because the settings command is whitespace-split and cannot
            // carry a shell one-liner itself.
            Row script = Models.get(InstanceFileModel.class).createEmptyRow();
            script.set(InstanceFileModel.INSTANCE_ID, id);
            script.set(InstanceFileModel.CONTAINER_PATH, "/udp-pong.sh");
            script.set(InstanceFileModel.CONTENT,
                "while true; do echo PONG | nc -u -l -p 5000 -w 1; done\n");
            script.set(InstanceFileModel.MODE, "0755");
            Models.get(InstanceFileModel.class).save(script);

            try {
                // 1. Deploy: the UDP claim is pre-allocated and HELD in the ledger.
                InstanceStatus status = service.deploy(id);
                Row claim = onlyClaimOf(id);
                assertThat((String) claim.get(PortAllocationModel.PROTOCOL))
                    .as("step 1: the ledger holds a UDP claim").isEqualTo("udp");
                assertThat(PortLedger.isPreallocated(claim))
                    .as("step 1: acquired through the PRE-ALLOCATION strategy --"
                        + " record-after cannot see UDP at all")
                    .isTrue();
                int port = claim.get(PortAllocationModel.PORT);
                assertThat(status.publishedPort())
                    .as("step 1: the daemon bound the claimed UDP port")
                    .isEqualTo(port);

                // 2. The daemon's OWN inspect shows the udp key bound 0.0.0.0.
                assertThat(hostBinding(docker, handle, "5000/udp"))
                    .as("step 2: the raw inspect binding is 0.0.0.0:" + port + "/udp")
                    .isEqualTo("0.0.0.0:" + port);

                // 3. A datagram from a NON-LOOPBACK address reaches the workload and
                //    its answer comes back -- reachability, not bookkeeping.
                assertThat(udpExchange(outside, port, "ping"))
                    .as("step 3: the UDP workload answered from outside loopback")
                    .isEqualTo("PONG");
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                quietDestroy(service, id);
                quietRemoveVolume(docker, handle);
            }
        });
    }

    @Test
    void fixedPortConflictIsRefusedByTheLedgerBeforeAnyContainer() throws Exception {
        DockerClient docker = requireDaemon("nginx:alpine");

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            int localId = ServerModel.localServerId();
            InstanceService service = new InstanceService();
            int fixedPort = 30777;
            Map<String, Object> first = new LinkedHashMap<>();
            first.put("image", "nginx");
            first.put("tag", "alpine");
            first.put("container_port", 80);
            first.put("port_exposure", "public");
            first.put("host_port", fixedPort);
            int winnerId = instanceRecord("port-fixed-winner", first);
            Map<String, Object> second = new LinkedHashMap<>(first);
            int loserId = instanceRecord("port-fixed-loser", second);
            String loserHandle = "hohenheim-instance-" + loserId;

            try {
                // 1. The first deploy claims and binds the declared fixed port.
                InstanceStatus won = service.deploy(winnerId);
                assertThat(won.publishedPort())
                    .as("step 1: the winner bound the declared fixed port")
                    .isEqualTo(fixedPort);

                // 2. The second deploy is REFUSED with the NAMED holder; the ledger
                //    still carries exactly ONE row for the tuple (rows, not logs).
                Throwable refused = catchThrowable(() -> service.deploy(loserId));
                assertThat(refused)
                    .as("step 2: the rival deploy is refused")
                    .isInstanceOf(Violations.class);
                assertThat(String.valueOf(((Violations) refused).all().get(0)
                        .message().args().asMap().get("holder")))
                    .as("step 2: the refusal names the holding instance")
                    .contains("port-fixed-winner");
                List<Row> rows = Models.get(PortAllocationModel.class).find()
                    .where(PortAllocationModel.SERVER_ID.eq(localId))
                    .and(PortAllocationModel.PORT.eq(fixedPort))
                    .all();
                assertThat(rows)
                    .as("step 2: exactly one ledger row holds the contested port")
                    .hasSize(1);
                assertThat((Integer) rows.get(0).get(PortAllocationModel.OWNER_ID))
                    .isEqualTo(winnerId);

                // 3. The loser's container was NEVER created: the claim precedes create,
                //    so the refusal leaves no daemon state behind.
                Throwable inspect = catchThrowable(() -> docker.inspectContainer(loserHandle));
                assertThat(inspect)
                    .as("step 3: the refused deploy created no container")
                    .isInstanceOf(DockerClient.ApiException.class);
                assertThat(((DockerClient.ApiException) inspect).isNotFound())
                    .as("step 3: the daemon answers 404 for the loser's handle")
                    .isTrue();
            } finally {
                quietDestroy(service, winnerId);
                quietDestroy(service, loserId);
                quietRemoveVolume(docker, "hohenheim-instance-" + winnerId);
                quietRemoveVolume(docker, loserHandle);
            }
        });
    }

    // -- fixtures and probes ---------------------------------------------------

    private static int instanceRecord(String name, Map<String, Object> settings) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, settings);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static Row onlyClaimOf(int instanceId) {
        List<Row> claims = PortLedger.claimsOf(InstanceModel.MODEL_ID, instanceId);
        assertThat(claims)
            .as("instance #" + instanceId + " holds exactly one port claim")
            .hasSize(1);
        return claims.get(0);
    }

    /** The daemon's own "HostIp:HostPort" for one inspect port key, or a loud failure. */
    private static String hostBinding(DockerClient docker, String handle, String portKey)
            throws IOException {
        Map<String, Object> inspect = docker.inspectContainer(handle);
        Object ports = inspect.get("NetworkSettings") instanceof Map<?, ?> ns
            ? ns.get("Ports") : null;
        Object bindings = ports instanceof Map<?, ?> map ? map.get(portKey) : null;
        if (bindings instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> binding) {
            return binding.get("HostIp") + ":" + binding.get("HostPort");
        }
        throw new IOException("Container " + handle + " publishes no " + portKey
            + " binding; ports = " + ports);
    }

    /**
     * The machine's PRIMARY non-loopback IPv4 -- the "outside" vantage point, resolved
     * from the default route (a connectionless UDP "connect" performs route selection
     * without sending a packet). Interface-enumeration order was the old pick and is
     * not stable under a parallel suite: Docker bridge interfaces come and go, and a
     * transient bridge gateway chosen as the vantage point blackholes every probe.
     * Falls back to enumeration on hosts with no default route.
     */
    private static InetAddress nonLoopbackAddress() throws IOException {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(InetAddress.getByName("192.0.2.1"), 53);
            InetAddress routed = probe.getLocalAddress();
            if (routed instanceof Inet4Address && !routed.isLoopbackAddress()
                    && !routed.isAnyLocalAddress()) {
                return routed;
            }
        } catch (IOException ignored) {
            // no default route: fall through to enumeration
        }
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface candidate = interfaces.nextElement();
            if (!candidate.isUp() || candidate.isLoopback()) {
                continue;
            }
            Enumeration<InetAddress> addresses = candidate.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    return address;
                }
            }
        }
        throw new IOException("This machine has no non-loopback IPv4 address to test with");
    }

    /** One TCP connect attempt with a short timeout; throws when nothing listens. */
    private static void connectOnce(InetAddress address, int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 1_500);
        }
    }

    /**
     * Minimal HTTP GET as a bounded poll: retried on connect/read failures AND on a
     * BLANK body -- the daemon-side proxy accepts as soon as the port is published and
     * drops the relayed connection while the workload is still starting, which reads
     * as a clean empty response, not an IOException. Still fails loudly after the
     * deadline (throws the last error, or returns the empty body for the assertion).
     */
    private static String httpGet(InetAddress address, int port) throws IOException {
        IOException last = null;
        String lastBody = "";
        for (int attempt = 0; attempt < 20; attempt++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, port), 1_500);
                socket.setSoTimeout(3_000);
                socket.getOutputStream().write(
                    ("GET / HTTP/1.0\r\nHost: test\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                String body = new String(socket.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
                if (!body.isBlank()) {
                    return body;
                }
                last = null;
                lastBody = body;
            } catch (IOException e) {
                last = e;
            }
            sleep(400);
        }
        if (last != null) {
            // Name the vantage point: a bare "Connect timed out" hides WHICH address
            // never answered, and that is the first diagnostic question.
            throw new IOException("no HTTP answer from " + address.getHostAddress() + ":"
                + port + " within the poll deadline", last);
        }
        return lastBody;
    }

    /** Send one datagram and await the reply, retried (the responder loops per second). */
    private static String udpExchange(InetAddress address, int port, String payload)
            throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(1_500);
                byte[] request = (payload + "\n").getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(request, request.length, address, port));
                byte[] buffer = new byte[512];
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                socket.receive(reply);
                return new String(reply.getData(), 0, reply.getLength(),
                    StandardCharsets.UTF_8).trim();
            } catch (SocketTimeoutException timeout) {
                last = timeout;
                sleep(300);
            }
        }
        throw last;
    }

    private static DockerClient requireDaemon(String image) {
        Assumptions.assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        Assumptions.assumeTrue(imagePresent(docker, image), image + " not present locally");
        Assumptions.assumeTrue(netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");
        return docker;
    }

    private static boolean imagePresent(DockerClient docker, String tag) {
        try {
            for (Object image : docker.listImages()) {
                Object repoTags = ((Map<?, ?>) image).get("RepoTags");
                if (repoTags instanceof List<?> tags && tags.contains(tag)) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    private static void quietDestroy(InstanceService service, int instanceId) {
        try {
            service.destroy(instanceId);
        } catch (RuntimeException ignored) {
            // best-effort cleanup; the assertions above are the test
        }
    }

    private static void quietRemoveVolume(DockerClient docker, String handle) {
        try {
            docker.removeVolume(handle + "-vol-data", true);
        } catch (IOException | RuntimeException ignored) {
            // these instances declare no volumes; remove is pure belt-and-braces
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
