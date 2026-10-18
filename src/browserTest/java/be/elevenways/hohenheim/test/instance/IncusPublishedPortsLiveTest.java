package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.incus.IncusEndpoint;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A VM's published port against the REAL remote Incus daemon: a TCP connection from this controller to the host port
 * reaches a listener inside the guest through the NAT proxy and the isolation ACL, and destroy removes the proxy and
 * frees the claim.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class IncusPublishedPortsLiveTest {

    // Unique across live classes: the product's authorized_keys comment is hohenheim-<name>.
    private static final String HOST = "live-incus-ports";

    private static final Duration POLL_INTERVAL = Duration.ofMillis(2000);

    private static SqlDatasource datasource;
    private static LiveIncusHost remote;
    private static String enrolledFingerprint;

    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveIncusHost.requirePrimary();
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        Db.run(datasource, () -> {
            enrolledFingerprint = remote.enrollThroughProduct(HOST, "hohenheim-live-ports");
        });
    }

    @AfterAll
    static void tearDown() {
        if (remote == null) {
            return;
        }
        System.out.println("=== cleanup: shared objects -> " + remote.releaseControllerSharedObjects());
        System.out.println("=== cleanup: authorized_keys -> " + remote.releaseAuthorizedKeys());
        if (enrolledFingerprint != null) {
            try {
                remote.removeTrustEntry(enrolledFingerprint);
            } catch (IOException ignored) {
                // nothing enrolled, nothing to remove
            }
        }
    }

    @Test
    void aHostPortReachesTheGuestThroughTheAclAndDestroyFreesIt() {
        Db.run(datasource, () -> {
            int hostId = Models.get(ServerModel.class).findByName(HOST).get(ServerModel.ID);
            InstanceService service = new InstanceService();
            int id = vmRecord(hostId);
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
            try {
                // 1. Deploy claims the port and lands a NAT proxy to the guest's pinned address.
                service.deploy(id);
                List<Row> claims = PortLedger.claimsOf(InstanceModel.MODEL_ID, id);
                assertThat(claims).as("step 1: one claim for the published port").hasSize(1);
                int hostPort = claims.get(0).get(PortAllocationModel.PORT);
                String devices = query("/1.0/instances/" + handle);
                assertThat(devices).as("step 1: the proxy listens on every host address at the claimed port")
                    .contains("tcp:0.0.0.0:" + hostPort).contains("\"nat\": \"true\"");

                // 2. A listener in the guest, reached from this controller through the host port: NAT, Docker's
                //    forward chain and the isolation ACL (whose egress rejects private ranges) all let it through.
                Poll.until("VM agent up", Duration.ofMillis(600_000), POLL_INTERVAL,
                    () -> execQuietly(handle, "echo up").contains("up"));
                exec(handle, "setsid sh -c 'while true; do echo hohport-ok | nc -l -p 7777; done'"
                    + " >/dev/null 2>&1 </dev/null &");
                String daemonHost = IncusEndpoint.parse(remote.url()).host();
                Poll.until("a line through host port " + hostPort, Duration.ofMillis(120_000), POLL_INTERVAL,
                    () -> "hohport-ok".equals(lineFrom(daemonHost, hostPort)));

                // 3. Destroy removes the workload, its proxy with it, and frees the claim.
                service.destroy(id);
                assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, id)).as("step 3: the claim is freed")
                    .noneMatch(claim -> !PortLedger.isReleasing(claim));
                assertThat(hostCommand("sh", "-c", "nft list ruleset | grep -c 'dport " + hostPort + " ' || true"))
                    .as("step 3: no DNAT rule for the port is left").isEqualTo("0");
                assertThat(lineFrom(daemonHost, hostPort)).as("step 3: nothing answers on it").isNull();
            } finally {
                remote.forceDelete(handle);
            }
        });
    }

    private static int vmRecord(int hostId) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine/3.22/cloud");
        settings.put("memory_limit_mb", 512);
        settings.put("published_ports", Map.of("test", "7777/tcp"));
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, "ports-live-vm");
        row.set(InstanceModel.KIND, "hohenheim:vm");
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.SERVER_ID, hostId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    /** The first line a TCP connection to {@code host:port} reads, or null when none arrives. */
    private static String lineFrom(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(3000);
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                .readLine();
        } catch (IOException unanswered) {
            return null;
        }
    }

    private static String exec(String handle, String command) {
        try {
            return remote.exec(handle, command);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String execQuietly(String handle, String command) {
        try {
            return remote.exec(handle, command);
        } catch (IOException notReady) {
            return "";
        }
    }

    private static String hostCommand(String... command) {
        try {
            return remote.hostCommand(command);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String query(String path) {
        try {
            return remote.query(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
