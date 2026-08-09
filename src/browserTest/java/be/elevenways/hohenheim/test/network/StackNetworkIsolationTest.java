package be.elevenways.hohenheim.test.network;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.security.TenantNetworkRanges;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.stack.StackInstances;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stack tier's isolation with REAL packets against the EXACT chains a REAL deploy
 * applied: a stack is deployed from RECORDS through {@link StackRuntime} onto a real
 * daemon, the kernel of the enforcing "host" (a private netns) is read back, and then a
 * namespace impersonating a stack service -- an address inside the SHARED stack network's
 * REAL subnet, routed through those very chains -- throws packets at the denied and
 * allowed destinations. Since the Phase 7 lowering that shared network is a policied LINK
 * network owned by the stack record, and each service ALSO has its own workload network;
 * this test pins the shared one, which is the lane siblings actually talk over.
 *
 * AIDEV-NOTE: two positive anchors on purpose. The Docker-layer one (service alias ping
 * between the two real containers) proves the per-stack network still does its job; the
 * packet-layer one (same-subnet sibling REACHABLE through the forward hook) proves the
 * own-subnet accept precedes the RFC1918 denies -- which is what keeps stack services
 * talking on a host with br_netfilter, where intra-bridge traffic DOES traverse the
 * forward hook. The closing counterfactual (remove the policy, the metadata probe
 * passes again) proves the BLOCKED answers measured the policy, not a broken fixture.
 */
class StackNetworkIsolationTest {

    private static SqliteDatasource datasource;
    private static PrivateNetns installed;

    /** nft table and container names are controller-namespaced, so this needs an identity. */
    @BeforeAll
    static void bootRecords() throws Exception {
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(
            Files.createTempDirectory("hh-stackiso-enc").resolve("keys.dry")));
        File db = File.createTempFile("hohenheim-stackiso-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterAll
    static void unwireNetns() {
        PrivateNetns.uninstall(installed);
        installed = null;
    }


    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String TEST_IMAGE = "alpine:latest";

    /** The cloud metadata address: instance credentials for the whole host. */
    private static final String METADATA = "169.254.169.254";

    /** A public address: egress a stack DECLARES open, so it must keep working. */
    private static final String PUBLIC = "203.0.113.5";

    /** A host service bound 0.0.0.0 -- the reverse proxy with the admin panel behind it. */
    private static final int HOST_SERVICE_PORT = 8080;

    private static final int PEER_PORT = 80;
    private static final int SIBLING_PORT = 9191;

    @Test
    void aStackServiceLosesMetadataAndHostButKeepsSiblingsAndTheInternet()
            throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, TEST_IMAGE);
        LiveLane.require(LiveLane.Need.NETNS, PrivateNetns.available(),
            "unshare/nsenter/nft/ip/python3 unavailable: cannot build a private netns");

        PrivateNetns netns = PrivateNetns.installEnforcing();
        installed = netns;
        String stackName = "hhiso-" + Long.toHexString(System.nanoTime());
        StackRuntime runtime = new StackRuntime(docker, datasource);
        int[] ids = new int[3];
        Db.run(datasource, () -> {
            StackModel stacks = Models.get(StackModel.class);
            Row stack = stacks.createEmptyRow();
            stack.set(StackModel.NAME, stackName);
            stack.set(StackModel.ENABLED, true);
            stack.set(StackModel.SERVER_ID, ServerModel.localServerId());
            stacks.save(stack);
            ids[0] = stack.get(StackModel.ID);
            StackServiceModel services = Models.get(StackServiceModel.class);
            int index = 1;
            for (String name : List.of("web", "db")) {
                Row service = services.createEmptyRow();
                service.set(StackServiceModel.STACK_ID, ids[0]);
                service.set(StackServiceModel.NAME, name);
                service.set(StackServiceModel.ENABLED, true);
                service.set(StackServiceModel.IMAGE, TEST_IMAGE);
                service.set(StackServiceModel.COMMAND, List.of("sleep", "600"));
                service.set(StackServiceModel.RESTART_POLICY, "no");
                services.save(service);
                ids[index++] = service.get(StackServiceModel.ID);
            }
        });
        try {
            runtime.deploy(ids[0], "isolation test");
            String networkName = WorkloadNetworks.networkName(
                StackInstances.networkHandle(stackName));

                // 1. docker network inspect: both services sit on the SHARED stack
                //    network, and the subnet the daemon REALLY assigned is what
                //    everything below keys on.
                Map<String, Object> network = docker.inspectNetwork(networkName);
                assertThat(asMap(network.get("Containers")))
                    .as("step 1: both stack services are attached to the shared network")
                    .hasSize(2);
                String subnet = subnetOf(network);
                String gateway = gatewayOf(network);

                // 2. Docker-layer positive anchor: the services reach each other by
                //    COMPOSE SERVICE NAME on that network -- the alias the lowering
                //    carries through as an endpoint alias on the link network.
                DockerClient.ExecResult alias = docker.exec(containerOf(ids[1]),
                    List.of("ping", "-c", "1", "-W", "2", "db"));
                assertThat(alias.exitCode())
                    .as("step 2: stack services still reach each other by alias: %s%s",
                        alias.stdout(), alias.stderr())
                    .isEqualTo(0);

                // 3. The enforcing kernel carries the full deny vocabulary for the REAL
                //    subnet -- read out of nft list ruleset, not out of our intent.
                String key = WorkloadNetworkPolicy.chainKey(networkName);
                String ruleset = netns.inHost("nft", "list", "ruleset").stdout();
                // Deliberate evidence in the test log: the kernel's own rendering of
                // what the deploy applied, next to the probes below.
                System.out.println("STACK-ISOLATION kernel ruleset for " + networkName
                    + " (subnet " + subnet + "):\n" + ruleset);
                for (String denied : TenantNetworkRanges.DENIED_V4) {
                    assertThat(ruleset)
                        .as("step 3: the kernel denies " + denied + " for the stack subnet")
                        .contains("ip saddr " + subnet + " ip daddr " + denied + " drop");
                }
                assertThat(ruleset)
                    .as("step 3: the stack keeps its own subnet")
                    .contains("ip saddr " + subnet + " ip daddr " + subnet + " accept");
                String inputChain = netns.inHost("nft", "list", "chain", "inet",
                    WorkloadNetworkPolicy.table(), WorkloadNetworkPolicy.inputChain(key)).stdout();
                assertThat(inputChain)
                    .as("step 3: the input chain denies the stack the host itself")
                    .contains("ip saddr " + subnet + " drop");

                // 4. REAL packets through those chains: a namespace with an address
                //    inside the stack's subnet, default-routed via the stack's gateway.
                long service = netns.nested();
                long sibling = netns.nested();
                long peer = netns.nested();
                String serviceAddress = addressIn(subnet, 200);
                String siblingAddress = addressIn(subnet, 201);
                wire(netns, service, sibling, peer, gateway, serviceAddress, siblingAddress);

                assertThat(netns.probe(service, METADATA, PEER_PORT))
                    .as("step 4: the metadata service is BLOCKED for a stack service")
                    .isEqualTo("BLOCKED");
                assertThat(netns.probe(service, gateway, HOST_SERVICE_PORT))
                    .as("step 4: the host itself is BLOCKED for a stack service")
                    .isEqualTo("BLOCKED");
                assertThat(netns.probe(service, siblingAddress, SIBLING_PORT))
                    .as("step 4: a sibling on the SAME stack subnet stays REACHABLE"
                        + " through the forward hook (the br_netfilter shape)")
                    .isEqualTo("REACHABLE");
                assertThat(netns.probe(service, PUBLIC, PEER_PORT))
                    .as("step 4: the DECLARED-open egress keeps the internet")
                    .isEqualTo("REACHABLE");

                // 5. THE COUNTERFACTUAL: remove the policy and the same metadata probe
                //    passes, so step 4 measured the chains and not a broken route.
                netns.enforcingPolicy().remove(networkName);
                assertThat(netns.probe(service, METADATA, PEER_PORT))
                    .as("step 5: without the policy the metadata service is reachable again")
                    .isEqualTo("REACHABLE");
        } finally {
            try {
                runtime.destroy(ids[0], true);
            } catch (IOException ignored) {
                // best effort; the assertions above are the outcome
            }
        }
    }

    /** THE daemon-side container name of a service, through its owned instance. */
    private static String containerOf(int serviceId) {
        Integer[] instanceId = new Integer[1];
        Db.run(datasource, () -> {
            Row instance = StackInstances.owned(serviceId);
            instanceId[0] = instance == null ? null : instance.get(InstanceModel.ID);
        });
        assertThat(instanceId[0]).as("service %s owns an instance", serviceId).isNotNull();
        return ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId[0]);
    }

    // -- fixture ---------------------------------------------------------------

    /**
     * A routed topology whose forward path IS the enforcing kernel: the "service" and
     * "sibling" namespaces carry /32 addresses inside the stack's real subnet, so every
     * packet between them and to the outside traverses the host's forward hook where
     * the deploy's chains sit -- same-subnet traffic included.
     */
    private static void wire(PrivateNetns netns, long service, long sibling, long peer,
                             String gateway, String serviceAddress, String siblingAddress)
            throws IOException {
        netns.setup("sysctl", "-w", "net.ipv4.ip_forward=1");
        netns.setup("ip", "link", "set", "lo", "up");

        netns.setup("ip", "link", "add", "vt0", "type", "veth", "peer", "name", "vt1");
        netns.setup("ip", "link", "add", "vs0", "type", "veth", "peer", "name", "vs1");
        netns.setup("ip", "link", "add", "vp0", "type", "veth", "peer", "name", "vp1");
        netns.setup("ip", "link", "set", "vt1", "netns", String.valueOf(service));
        netns.setup("ip", "link", "set", "vs1", "netns", String.valueOf(sibling));
        netns.setup("ip", "link", "set", "vp1", "netns", String.valueOf(peer));

        // The gateway identity on every stack-facing leg; /32 host routes pick the leg.
        netns.setup("ip", "addr", "add", gateway + "/32", "dev", "vt0");
        netns.setup("ip", "addr", "add", gateway + "/32", "dev", "vs0");
        netns.setup("ip", "link", "set", "vt0", "up");
        netns.setup("ip", "link", "set", "vs0", "up");
        netns.setup("ip", "route", "add", serviceAddress + "/32", "dev", "vt0");
        netns.setup("ip", "route", "add", siblingAddress + "/32", "dev", "vs0");

        // The outside world: the metadata range and a public range live behind vp0.
        netns.setup("ip", "addr", "add", "169.254.169.1/24", "dev", "vp0");
        netns.setup("ip", "addr", "add", "203.0.113.1/24", "dev", "vp0");
        netns.setup("ip", "link", "set", "vp0", "up");

        for (long[] pair : new long[][] {{service, 0}, {sibling, 1}}) {
            long ns = pair[0];
            String address = pair[1] == 0 ? serviceAddress : siblingAddress;
            netns.setupNested(ns, "ip", "link", "set", "lo", "up");
            netns.setupNested(ns, "ip", "addr", "add", address + "/32",
                "dev", pair[1] == 0 ? "vt1" : "vs1");
            netns.setupNested(ns, "ip", "link", "set", pair[1] == 0 ? "vt1" : "vs1", "up");
            netns.setupNested(ns, "ip", "route", "add", gateway + "/32",
                "dev", pair[1] == 0 ? "vt1" : "vs1");
            netns.setupNested(ns, "ip", "route", "add", "default", "via", gateway);
        }

        netns.setupNested(peer, "ip", "link", "set", "lo", "up");
        netns.setupNested(peer, "ip", "addr", "add", METADATA + "/24", "dev", "vp1");
        netns.setupNested(peer, "ip", "addr", "add", PUBLIC + "/24", "dev", "vp1");
        netns.setupNested(peer, "ip", "link", "set", "vp1", "up");
        netns.setupNested(peer, "ip", "route", "add", "default", "via", "169.254.169.1");

        netns.listen(peer, PEER_PORT);
        netns.listen(sibling, SIBLING_PORT);
        netns.listenInHost(HOST_SERVICE_PORT);
    }

    // -- helpers ---------------------------------------------------------------

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String subnetOf(Map<String, Object> network) {
        return ipamValue(network, "Subnet");
    }

    private static String gatewayOf(Map<String, Object> network) {
        return ipamValue(network, "Gateway");
    }

    private static String ipamValue(Map<String, Object> network, String field) {
        Object configs = asMap(network.get("IPAM")).get("Config");
        if (configs instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> config
                        && config.get("Subnet") instanceof String subnet
                        && !subnet.contains(":")
                        && config.get(field) instanceof String value && !value.isBlank()) {
                    return value;
                }
            }
        }
        throw new IllegalStateException("network has no IPv4 " + field + ": " + network);
    }

    /** The host address {@code offset} above the subnet base, e.g. 172.18.0.0/16 + 200. */
    private static String addressIn(String subnet, int offset) {
        String[] parts = subnet.split("/")[0].split("\\.");
        long base = 0;
        for (String part : parts) {
            base = (base << 8) | Integer.parseInt(part);
        }
        long address = base + offset;
        return ((address >> 24) & 0xff) + "." + ((address >> 16) & 0xff) + "."
            + ((address >> 8) & 0xff) + "." + (address & 0xff);
    }
}
