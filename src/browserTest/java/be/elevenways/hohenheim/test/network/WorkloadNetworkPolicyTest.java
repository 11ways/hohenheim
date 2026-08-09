package be.elevenways.hohenheim.test.network;

import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import org.junit.jupiter.api.BeforeAll;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.hohenheim.server.security.WorkloadNetwork;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.live.LiveLane;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The workload network policy against REAL nftables and REAL packets: a routed topology
 * in a private namespace, four destinations, and the production applier deciding which of
 * them a tenant may still reach.
 *
 * AIDEV-NOTE: every assertion here is a connect() attempt, not a rule string. A test that
 * asserts "we sent the right nft argv" proves the same nothing as asserting "we put
 * CapDrop in the JSON" -- the kernel is the authority, so the rules are applied to a
 * kernel and then packets are thrown at them. The BEFORE half of each step is the
 * counterfactual: every destination is reachable until the policy lands, and reachable
 * again after it is removed, so a passing AFTER cannot be an artefact of a broken fixture.
 */
class WorkloadNetworkPolicyTest {

    /** nft table and container names are controller-namespaced, so this needs an identity. */
    @BeforeAll
    static void controllerIdentity() {
        HohenheimTestRuntime.ensureDatasource();
    }


    private static final String TENANT_SUBNET = "172.31.5.0/24";
    private static final String TENANT_GATEWAY = "172.31.5.1";
    private static final String TENANT_ADDRESS = "172.31.5.2";
    private static final String TENANT_SUBNET_V6 = "fd00:31:5::/64";

    /** The cloud metadata address: instance credentials for the whole host. */
    private static final String METADATA = "169.254.169.254";

    /** Another tenant's container, on another private network of the same daemon. */
    private static final String OTHER_TENANT = "172.30.9.9";

    /** A public address: egress that must KEEP working, or the policy is just a blackhole. */
    private static final String PUBLIC = "203.0.113.5";

    /** Another workload over IPv6, in the ULA range a v6-enabled daemon hands out. */
    private static final String OTHER_TENANT_V6 = "fd00:169::254";

    /** A host service bound 0.0.0.0 -- the reverse proxy with the admin panel behind it. */
    private static final int HOST_SERVICE_PORT = 8080;

    private static final int PEER_PORT = 80;

    @Test
    void aTenantLosesTheHostTheMetadataServiceAndItsNeighboursButKeepsTheInternet()
            throws IOException {
        LiveLane.require(LiveLane.Need.NETNS, PrivateNetns.available(),
            "unshare/nsenter/nft/ip/python3 unavailable: cannot build a private netns");

        try (PrivateNetns netns = new PrivateNetns()) {
            long tenant = netns.nested();
            long peer = netns.nested();
            wire(netns, tenant, peer);

            WorkloadNetworkPolicy policy = new WorkloadNetworkPolicy(netns.nftRunner(), () -> true);
            WorkloadNetwork network = new WorkloadNetwork("hohenheim-instance-1-net",
                TENANT_SUBNET, TENANT_GATEWAY, TENANT_SUBNET_V6);

            // 1. Before any policy: everything is reachable. This is exactly the posture
            //    every hohenheim container has on the shared default bridge today.
            assertThat(netns.probe(tenant, METADATA, PEER_PORT))
                .as("step 1: the metadata service is reachable with no policy")
                .isEqualTo("REACHABLE");
            assertThat(netns.probe(tenant, OTHER_TENANT, PEER_PORT))
                .as("step 1: another tenant's container is reachable with no policy")
                .isEqualTo("REACHABLE");
            assertThat(netns.probe(tenant, TENANT_GATEWAY, HOST_SERVICE_PORT))
                .as("step 1: a host service on the bridge gateway is reachable with no policy")
                .isEqualTo("REACHABLE");
            assertThat(netns.probe(tenant, OTHER_TENANT_V6, PEER_PORT))
                .as("step 1: an IPv6 neighbour is reachable with no policy")
                .isEqualTo("REACHABLE");
            assertThat(netns.probe(tenant, PUBLIC, PEER_PORT))
                .as("step 1: the public internet is reachable with no policy")
                .isEqualTo("REACHABLE");

            // 2. Apply the policy through the production applier, which verifies its own
            //    work by reading the chains back out of the kernel.
            policy.apply(network, Egress.OPEN);

            assertThat(netns.probe(tenant, METADATA, PEER_PORT))
                .as("step 2: the metadata service is blocked").isEqualTo("BLOCKED");
            assertThat(netns.probe(tenant, OTHER_TENANT, PEER_PORT))
                .as("step 2: another tenant's container is blocked").isEqualTo("BLOCKED");
            assertThat(netns.probe(tenant, TENANT_GATEWAY, HOST_SERVICE_PORT))
                .as("step 2: the host itself is blocked").isEqualTo("BLOCKED");
            assertThat(netns.probe(tenant, OTHER_TENANT_V6, PEER_PORT))
                .as("step 2: the IPv6 neighbour is blocked -- a v4-only policy would be a bypass")
                .isEqualTo("BLOCKED");
            assertThat(netns.probe(tenant, PUBLIC, PEER_PORT))
                .as("step 2: ordinary egress still works")
                .isEqualTo("REACHABLE");

            // 3. The kernel carries the rules, in a chain that is actually hooked.
            String ruleset = netns.inHost("nft", "list", "table", "inet",
                WorkloadNetworkPolicy.table()).stdout();
            assertThat(ruleset).as("step 3: the forward chain is hooked into forward")
                .contains("type filter hook forward");
            assertThat(ruleset).as("step 3: the metadata deny is in the kernel, not just in our JSON")
                .contains("ip saddr " + TENANT_SUBNET + " ip daddr 169.254.0.0/16 drop");
            assertThat(ruleset).as("step 3: the host deny covers the whole gateway, not a port list")
                .contains("ip saddr " + TENANT_SUBNET + " drop");

            // 4. Re-applying is idempotent: same rules, still exactly one copy of each.
            policy.apply(network, Egress.OPEN);
            String reapplied = netns.inHost("nft", "list", "table", "inet",
                WorkloadNetworkPolicy.table()).stdout();
            assertThat(occurrences(reapplied,
                "ip saddr " + TENANT_SUBNET + " ip daddr 169.254.0.0/16 drop"))
                .as("step 4: re-apply replaces the chain rather than appending a second copy")
                .isEqualTo(1);
            assertThat(netns.probe(tenant, METADATA, PEER_PORT))
                .as("step 4: still blocked after a re-apply").isEqualTo("BLOCKED");

            // 5. THE COUNTERFACTUAL: remove the policy and the same probes pass again, so
            //    step 2 measured the policy and not a broken fixture.
            policy.remove(network.name());
            assertThat(netns.inHost("nft", "list", "table", "inet",
                WorkloadNetworkPolicy.table()).stdout())
                .as("step 5: the chains are gone from the kernel")
                .doesNotContain("fwd_hohenheim_instance_1_net");
            assertThat(netns.probe(tenant, METADATA, PEER_PORT))
                .as("step 5: the metadata service is reachable again once the policy is removed")
                .isEqualTo("REACHABLE");
            assertThat(netns.probe(tenant, TENANT_GATEWAY, HOST_SERVICE_PORT))
                .as("step 5: the host is reachable again once the policy is removed")
                .isEqualTo("REACHABLE");

            // 6. Removing a policy that was never applied is an observed no-op, not an error.
            policy.remove("hohenheim-instance-does-not-exist-net");
        }
    }

    /**
     * Egress is a DECLARED fact: the same network under {@link Egress#NONE} loses the
     * internet while inbound connections keep their reply leg, and re-declaring
     * {@link Egress#OPEN} restores it -- so the closed posture is the rules, not a
     * fixture accident.
     */
    @Test
    void aDeclaredClosedEgressBlocksTheInternetWhileRepliesKeepFlowing() throws IOException {
        LiveLane.require(LiveLane.Need.NETNS, PrivateNetns.available(),
            "unshare/nsenter/nft/ip/python3 unavailable: cannot build a private netns");

        try (PrivateNetns netns = new PrivateNetns()) {
            long tenant = netns.nested();
            long peer = netns.nested();
            wire(netns, tenant, peer);
            netns.listen(tenant, 9090);

            WorkloadNetworkPolicy policy = new WorkloadNetworkPolicy(netns.nftRunner(), () -> true);
            WorkloadNetwork network = new WorkloadNetwork("hohenheim-db-egress-net",
                TENANT_SUBNET, TENANT_GATEWAY, TENANT_SUBNET_V6);

            // 1. Declared OPEN: the public internet is reachable.
            policy.apply(network, Egress.OPEN);
            assertThat(netns.probe(tenant, PUBLIC, PEER_PORT))
                .as("step 1: declared-open egress keeps the internet")
                .isEqualTo("REACHABLE");

            // 2. Re-declared NONE: the very same probe is now blocked, and the kernel
            //    carries the final saddr-scoped drop.
            policy.apply(network, Egress.NONE);
            assertThat(netns.probe(tenant, PUBLIC, PEER_PORT))
                .as("step 2: declared-closed egress blocks the internet")
                .isEqualTo("BLOCKED");
            assertThat(netns.inHost("nft", "list", "table", "inet",
                WorkloadNetworkPolicy.table()).stdout())
                .as("step 2: the kernel carries the egress drop, not just our intent")
                .contains("ip saddr " + TENANT_SUBNET + " drop");

            // 3. Inbound still answers: a connection INTO the closed workload gets its
            //    reply leg through the established/related accept that precedes the drop.
            assertThat(netns.probe(peer, TENANT_ADDRESS, 9090))
                .as("step 3: closed egress still answers inbound connections")
                .isEqualTo("REACHABLE");

            // 4. THE COUNTERFACTUAL: re-declare OPEN and the internet returns, so step 2
            //    measured the declaration and not a broken route.
            policy.apply(network, Egress.OPEN);
            assertThat(netns.probe(tenant, PUBLIC, PEER_PORT))
                .as("step 4: declaring egress open again restores the internet")
                .isEqualTo("REACHABLE");
        }
    }

    /**
     * The two ways this applier is not {@link be.elevenways.hohenheim.server.security.NftService}:
     * it refuses when enforcement is off, and it disbelieves a successful exit code.
     */
    @Test
    void theApplierRefusesWhenDisabledAndDisbelievesALyingNft() throws IOException {
        LiveLane.require(LiveLane.Need.NETNS, PrivateNetns.available(),
            "unshare/nsenter/nft/ip/python3 unavailable: cannot build a private netns");

        try (PrivateNetns netns = new PrivateNetns()) {
            WorkloadNetwork network = new WorkloadNetwork("hohenheim-instance-2-net",
                TENANT_SUBNET, TENANT_GATEWAY, null);

            // 1. Enforcement off: apply refuses, loudly, and nothing reaches the kernel.
            WorkloadNetworkPolicy disabled =
                new WorkloadNetworkPolicy(netns.nftRunner(), () -> false);
            assertThatThrownBy(() -> disabled.apply(network, Egress.OPEN))
                .as("step 1: a host that cannot enforce the policy refuses instead of pretending")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("REFUSED to deploy")
                .hasMessageContaining("security.nftables_enabled");
            assertThat(netns.inHost("nft", "list", "ruleset").stdout())
                .as("step 1: the kernel has no table at all after the refusal")
                .doesNotContain(WorkloadNetworkPolicy.table());

            // 2. A real apply lands.
            WorkloadNetworkPolicy policy = new WorkloadNetworkPolicy(netns.nftRunner(), () -> true);
            policy.apply(network, Egress.OPEN);
            assertThat(netns.inHost("nft", "list", "ruleset").stdout())
                .as("step 2: the enabled applier really wrote the chain")
                .contains("ip saddr " + TENANT_SUBNET + " ip daddr 169.254.0.0/16 drop");

            // 3. An nft that exits 0 without doing anything -- the exact shape NftService
            //    reports as success -- is caught by the read-back and REFUSED.
            NftRunner lying = (args, stdin) -> args.contains("-f")
                ? new NftRunner.Result(0, "", "")
                : netns.nftRunner().run(args, stdin);
            WorkloadNetworkPolicy lied = new WorkloadNetworkPolicy(lying, () -> true);
            WorkloadNetwork moved = new WorkloadNetwork(network.name(),
                "172.31.6.0/24", "172.31.6.1", null);
            assertThatThrownBy(() -> lied.apply(moved, Egress.OPEN))
                .as("step 3: exit 0 is not proof; the kernel is re-read and the lie is caught")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("nft reported success but the kernel does not carry the rule")
                .hasMessageContaining("172.31.6.0/24");

            // 4. A chain that vanished between apply and verify is caught the same way.
            netns.inHost("nft", "delete", "table", "inet", WorkloadNetworkPolicy.table());
            assertThatThrownBy(() -> lied.apply(network, Egress.OPEN))
                .as("step 4: a missing chain is a refusal, never a shrug")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not exist in the kernel");
        }
    }

    /**
     * A v6-enabled network with no v6 subnet cannot be covered, so no policy object is
     * built for it -- the alternative is a v4-only policy on a v6-reachable container.
     */
    @Test
    void aNetworkThatIsIpv6EnabledWithoutAnIpv6SubnetIsRefused() {
        Map<String, Object> inspect = Map.of(
            "Name", "hohenheim-instance-3-net",
            "EnableIPv6", true,
            "IPAM", Map.of("Config", List.of(
                Map.of("Subnet", "172.31.7.0/24", "Gateway", "172.31.7.1"))));

        assertThatThrownBy(() -> WorkloadNetwork.fromInspect(inspect))
            .as("a v4-only policy on a v6-enabled network is a bypass, not a partial win")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("IPv6-enabled but reports no IPv6 subnet");
    }

    /** Build a routed three-namespace topology: tenant -- host/gateway -- peer. */
    private static void wire(PrivateNetns netns, long tenant, long peer) throws IOException {
        netns.setup("sysctl", "-w", "net.ipv4.ip_forward=1");
        netns.setup("sysctl", "-w", "net.ipv6.conf.all.forwarding=1");
        netns.setup("ip", "link", "set", "lo", "up");

        netns.setup("ip", "link", "add", "vt0", "type", "veth", "peer", "name", "vt1");
        netns.setup("ip", "link", "add", "vp0", "type", "veth", "peer", "name", "vp1");
        netns.setup("ip", "link", "set", "vt1", "netns", String.valueOf(tenant));
        netns.setup("ip", "link", "set", "vp1", "netns", String.valueOf(peer));

        // The "bridge gateway" side, plus every range the peer namespace impersonates.
        netns.setup("ip", "addr", "add", TENANT_GATEWAY + "/24", "dev", "vt0");
        netns.setup("ip", "addr", "add", "fd00:31:5::1/64", "dev", "vt0", "nodad");
        netns.setup("ip", "link", "set", "vt0", "up");
        netns.setup("ip", "addr", "add", "169.254.169.1/24", "dev", "vp0");
        netns.setup("ip", "addr", "add", "172.30.9.1/24", "dev", "vp0");
        netns.setup("ip", "addr", "add", "203.0.113.1/24", "dev", "vp0");
        netns.setup("ip", "addr", "add", "fd00:169::1/64", "dev", "vp0", "nodad");
        netns.setup("ip", "link", "set", "vp0", "up");

        netns.setupNested(tenant, "ip", "link", "set", "lo", "up");
        netns.setupNested(tenant, "ip", "addr", "add", TENANT_ADDRESS + "/24", "dev", "vt1");
        netns.setupNested(tenant, "ip", "addr", "add", "fd00:31:5::2/64", "dev", "vt1", "nodad");
        netns.setupNested(tenant, "ip", "link", "set", "vt1", "up");
        netns.setupNested(tenant, "ip", "route", "add", "default", "via", TENANT_GATEWAY);
        netns.setupNested(tenant, "ip", "-6", "route", "add", "default", "via", "fd00:31:5::1");

        netns.setupNested(peer, "ip", "link", "set", "lo", "up");
        netns.setupNested(peer, "ip", "addr", "add", METADATA + "/24", "dev", "vp1");
        netns.setupNested(peer, "ip", "addr", "add", OTHER_TENANT + "/24", "dev", "vp1");
        netns.setupNested(peer, "ip", "addr", "add", PUBLIC + "/24", "dev", "vp1");
        netns.setupNested(peer, "ip", "addr", "add", OTHER_TENANT_V6 + "/64", "dev", "vp1", "nodad");
        netns.setupNested(peer, "ip", "link", "set", "vp1", "up");
        netns.setupNested(peer, "ip", "route", "add", "default", "via", "169.254.169.1");
        netns.setupNested(peer, "ip", "-6", "route", "add", "default", "via", "fd00:169::1");

        netns.listen(peer, PEER_PORT);
        netns.listenInHost(HOST_SERVICE_PORT);
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }
}
