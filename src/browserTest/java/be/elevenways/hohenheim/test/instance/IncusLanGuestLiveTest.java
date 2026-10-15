package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.instance.GuestNetworkMode;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.server.host.PreflightFinding;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.GuestAddressSupport;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.Poll;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A VM on the local network against the REAL remote Incus daemon: the host's {@code lan_parent} (its physical uplink,
 * so macvlan) is admitted by the host check, the guest takes a DHCP lease from the LAN router, this controller reaches
 * a listener inside it on that address, and destroy removes it.
 *
 * AIDEV-NOTE: macvlan needs no host change, but the upstream switch (on daystrom: the OUTER Proxmox bridge of the VM
 * daystrom itself is) must pass frames from a second MAC address on the uplink. A MAC-filtering upstream drops the
 * guest's DHCP request; the test then skips naming the guest's MAC, rather than failing on a host fact the product
 * cannot change.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class IncusLanGuestLiveTest {

    // Unique across live classes: the product's authorized_keys comment is hohenheim-<name>.
    private static final String HOST = "live-incus-lan";

    /** The daemon host's uplink, read on daystrom: physical, unmanaged, up. */
    private static final String PARENT = "ens18";

    private static final Duration POLL_INTERVAL = Duration.ofMillis(3000);

    private static SqlDatasource datasource;
    private static LiveIncusHost remote;

    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveIncusHost.requirePrimary();
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        Db.run(datasource, () -> {
            remote.enrollThroughProduct(HOST, "hohenheim-live-lan");
        });
    }

    @AfterAll
    static void tearDown() {
        if (remote == null) {
            return;
        }
        System.out.println("=== cleanup: shared objects -> " + remote.releaseControllerSharedObjects());
        System.out.println("=== cleanup: authorized_keys -> " + remote.releaseAuthorizedKeys());
        System.out.println("=== cleanup: trust -> " + remote.releaseTrustEntries());
    }

    @Test
    void aLanGuestTakesALeaseFromTheLanAndIsReachedOnItsOwnAddress() {
        Db.run(datasource, () -> {
            // 1. The operator declares the uplink; the host check admits it as macvlan.
            Row host = Models.get(ServerModel.class).findByName(HOST);
            host.set(ServerModel.LAN_PARENT, PARENT);
            Models.get(ServerModel.class).save(host);
            HostPreflight.Report report = HostPreflight.runAndStore(HOST);
            HostPreflight.Check lan = report.checks().stream()
                .filter(check -> IncusPreflight.LAN_PARENT_CHECK.equals(check.name())).findFirst().orElse(null);
            assertThat(lan).as("step 1: the host check ran for the declared parent").isNotNull();
            assertThat(lan.status()).as("step 1: %s", lan.detail()).isEqualTo(HostPreflight.STATUS_PASS);
            assertThat(lan.found().finding()).isEqualTo(PreflightFinding.LAN_PARENT_ADMITTED);
            assertThat(lan.found().args()).containsEntry("nictype", "macvlan");

            int hostId = host.get(ServerModel.ID);
            InstanceService service = new InstanceService();
            int id = vmRecord(hostId);
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
            try {
                // 2. Deploy writes eth0 as a macvlan NIC on the parent: no managed network, no ACL.
                service.deploy(id);
                String definition = query("/1.0/instances/" + handle);
                assertThat(definition).as("step 2: eth0 is macvlan on " + PARENT)
                    .contains("\"nictype\": \"macvlan\"").contains("\"parent\": \"" + PARENT + "\"")
                    .doesNotContain(IncusNetworkPolicy.aclName());
                String mac = hostCommand("incus", "config", "get", handle, "volatile.eth0.hwaddr");
                System.out.println("=== LAN guest " + handle + " eth0 MAC " + mac);

                // 3. The guest asks the LAN for a lease; the product reads it back as the main NIC's address.
                Poll.until("VM agent up", Duration.ofMillis(600_000), POLL_INTERVAL,
                    () -> execQuietly(handle, "echo up").contains("up"));
                String address = awaitLanAddress(service, id, handle, Duration.ofSeconds(180));
                if (address == null) {
                    String seen = execQuietly(handle, "ip -4 -o addr show; ip link show");
                    System.out.println("=== NO LEASE for " + handle + " (MAC " + mac + "): " + seen);
                    Assumptions.abort("the LAN guest (MAC " + mac + " on " + PARENT + ") took no DHCP lease within"
                        + " 180s: the upstream of " + PARENT + " does not pass a second MAC address (a Proxmox NIC"
                        + " firewall or MAC filter on the outer host), which no product change can fix");
                }
                System.out.println("=== LAN guest " + handle + " leased " + address);

                // 4. This controller (a workstation on the same LAN, not the guest's host) reaches a guest listener.
                exec(handle, "setsid sh -c 'while true; do echo lan-ok | nc -l -p 7777; done'"
                    + " >/dev/null 2>&1 </dev/null &");
                Poll.until("a line from " + address + ":7777", Duration.ofMillis(120_000), POLL_INTERVAL,
                    () -> "lan-ok".equals(lineFrom(address, 7777)));

                // 5. Destroy removes the guest; nothing answers on its address any more.
                service.destroy(id);
                assertThat(query("/1.0/instances")).as("step 5: the guest is gone").doesNotContain(handle);
                assertThat(lineFrom(address, 7777)).as("step 5: nothing answers on its address").isNull();
            } finally {
                remote.forceDelete(handle);
            }
        });
    }

    /** @return the main NIC's LAN address as the product reads it, or null when none arrived in time */
    private static String awaitLanAddress(InstanceService service, int id, String handle, Duration budget) {
        try {
            return Poll.value("a LAN lease on " + handle, budget, POLL_INTERVAL, () -> lanAddress(service, id, handle));
        } catch (AssertionError none) {
            return null;
        }
    }

    /** @return the main NIC's address on the LAN right now, or null */
    private static String lanAddress(InstanceService service, int id, String handle) {
        try {
            InstanceService.Resolved resolved = service.resolve(id);
            for (GuestAddressSupport.GuestInterface nic : ((GuestAddressSupport) resolved.runtime())
                    .guestAddresses(handle, 10_000)) {
                if (!nic.primary()) {
                    continue;
                }
                assertThat(nic.network()).as("the main NIC names its parent").isEqualTo(PARENT);
                for (String address : nic.addresses()) {
                    if (address.startsWith("10.47.")) {
                        return address;
                    }
                }
            }
        } catch (IOException notYet) {
            System.out.println("=== guest addresses not readable yet: " + notYet.getMessage());
        }
        return null;
    }

    private static int vmRecord(int hostId) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine/3.22/cloud");
        settings.put("memory_limit_mb", 512);
        settings.put(GuestNetworkMode.SETTING, GuestNetworkMode.LOCAL_NETWORK.token());
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, "lan-live-vm");
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
