package be.elevenways.hohenheim.test.network;

import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import org.junit.jupiter.api.BeforeAll;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.hohenheim.server.security.ProcessNetworkPolicy;
import be.elevenways.hohenheim.server.task.VerifyWorkloadIsolation;
import be.elevenways.hohenheim.test.live.LiveLane;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The managed-process tier's isolation against REAL nftables, REAL uids and REAL packets:
 * a host-shaped topology in a namespace with a mapped subuid range, and the production
 * applier deciding what a process running as the site's uid may still reach.
 *
 * AIDEV-NOTE: the identity half is the whole point, so every probe is run AS a uid and a
 * SECOND uid runs the same probes as a control. A test that only showed "the site uid
 * cannot reach the metadata service" would pass just as well against a blanket drop that
 * takes the control plane down with it; the control uid staying reachable is what proves
 * the rule is keyed on {@code meta skuid} and matches.
 */
class ProcessNetworkIsolationTest {

    /** nft table and container names are controller-namespaced, so this needs an identity. */
    @BeforeAll
    static void controllerIdentity() {
        HohenheimTestRuntime.ensureDatasource();
    }


    /** The site's run-as uid: the identity the OUTPUT rules are keyed on. */
    private static final int SITE_UID = 4242;

    /** Another uid on the same host: the control that must NOT be affected. */
    private static final int CONTROL_UID = 4343;

    private static final String SITE = "process-site";

    /** The cloud metadata address: host credentials one HTTP GET away. */
    private static final String METADATA = "169.254.169.254";

    /** A service on the host's own private address: the admin panel, a database, ssh. */
    private static final String HOST_SERVICE = "10.99.0.1";

    /** The resolver /etc/resolv.conf names, INSIDE a denied range (the crux of this slice). */
    private static final String RESOLVER = "10.99.0.53";

    /** A public address: egress that must KEEP working, or the policy is just a blackhole. */
    private static final String PUBLIC = "203.0.113.1";

    private static final int PORT = 8080;

    @Test
    void aSiteProcessLosesTheMetadataServiceAndTheHostButKeepsDnsAndTheInternet()
            throws IOException {
        LiveLane.require(LiveLane.Need.NETNS, UidMappedNetns.available(),
            "no /etc/subuid range or missing unshare/nsenter/nft/ip/python3/setpriv/newuidmap:"
            + " cannot build a uid-mapped netns");

        try (UidMappedNetns netns = new UidMappedNetns()) {
            LiveLane.require(LiveLane.Need.NETNS, netns.highestMappedUid() >= CONTROL_UID,
                "the /etc/subuid range is too small to map the control uid");
            wire(netns);
            Path resolvConf = writeResolvConf("nameserver " + RESOLVER + "\n");
            ProcessNetworkPolicy policy = netns.enforcingPolicy(resolvConf);

            // 1. Before any policy: a site process reaches everything on the host's
            //    private network. This is exactly the posture every process site has today.
            assertThat(netns.probe(SITE_UID, METADATA, PORT))
                .as("step 1: the metadata service is reachable with no policy")
                .isEqualTo("REACHABLE");
            assertThat(netns.probe(SITE_UID, HOST_SERVICE, PORT))
                .as("step 1: a host-local service is reachable with no policy")
                .isEqualTo("REACHABLE");
            assertThat(netns.probe(SITE_UID, RESOLVER, PORT))
                .as("step 1: a non-DNS port on the resolver host is reachable with no policy")
                .isEqualTo("REACHABLE");
            assertThat(netns.probe(SITE_UID, PUBLIC, PORT))
                .as("step 1: the public internet is reachable with no policy")
                .isEqualTo("REACHABLE");
            assertThat(netns.resolve(SITE_UID, RESOLVER))
                .as("step 1: DNS resolves with no policy")
                .isEqualTo("RESOLVED");

            // 2. Apply through the production applier, which verifies its own work by
            //    reading the chain back out of the kernel.
            policy.apply(SITE_UID, SITE);

            String chain = ProcessNetworkPolicy.outputChain(SITE_UID);
            NftRunner.Result listed = netns.asRoot("nft", "list", "chain", "inet",
                ProcessNetworkPolicy.table(), chain);
            assertThat(listed.ok()).as("step 2: the kernel lists the chain we just applied")
                .isTrue();
            assertThat(listed.stdout().replaceAll("\\s+", " "))
                .as("step 2: the KERNEL (not our bookkeeping) carries the uid-keyed denies"
                    + " and the port-53 carve-out")
                .contains("meta skuid " + SITE_UID + " ip daddr 169.254.0.0/16 drop")
                .contains("meta skuid " + SITE_UID + " ip daddr 10.0.0.0/8 drop")
                .contains("meta skuid " + SITE_UID + " ip daddr " + RESOLVER
                    + " udp dport 53 accept");

            // 3. The negative proof, run as the real run-as uid.
            assertThat(netns.probe(SITE_UID, METADATA, PORT))
                .as("step 3: the metadata service is gone for the site uid")
                .isEqualTo("BLOCKED");
            assertThat(netns.probe(SITE_UID, HOST_SERVICE, PORT))
                .as("step 3: host-local services are gone for the site uid")
                .isEqualTo("BLOCKED");
            assertThat(netns.probe(SITE_UID, RESOLVER, PORT))
                .as("step 3: the resolver carve-out is scoped to port 53 -- the resolver"
                    + " HOST is not otherwise reachable")
                .isEqualTo("BLOCKED");

            // 4. The positive anchors: without them a passing step 3 could just be a
            //    blackhole, and a process site that cannot resolve names is dead anyway.
            assertThat(netns.probe(SITE_UID, PUBLIC, PORT))
                .as("step 4: the site still reaches the internet by address literal")
                .isEqualTo("REACHABLE");
            assertThat(netns.resolve(SITE_UID, RESOLVER))
                .as("step 4: the site still resolves DNS through the private-range resolver")
                .isEqualTo("RESOLVED");

            // 5. The identity control: another uid on the same host is untouched, so the
            //    rule really is keyed on skuid and really does match.
            assertThat(netns.probe(CONTROL_UID, METADATA, PORT))
                .as("step 5: another uid on the same host still reaches the metadata service")
                .isEqualTo("REACHABLE");
            assertThat(netns.probe(CONTROL_UID, HOST_SERVICE, PORT))
                .as("step 5: another uid on the same host still reaches host services")
                .isEqualTo("REACHABLE");

            // 6. Kernel truth agrees with the applier, and is idempotent.
            assertThat(policy.isEnforced(SITE_UID, SITE))
                .as("step 6: the kernel-truth check confirms what was applied").isTrue();
            policy.apply(SITE_UID, SITE);
            assertThat(netns.probe(SITE_UID, METADATA, PORT))
                .as("step 6: re-applying keeps the deny in place").isEqualTo("BLOCKED");

            // 7. COUNTERFACTUAL: take the policy away and the escape comes back. A step 3
            //    that passes with the chain removed would mean the fixture, not the rules,
            //    was doing the blocking.
            policy.remove(SITE_UID, SITE);
            assertThat(policy.isEnforced(SITE_UID, SITE))
                .as("step 7: the kernel no longer carries the chain").isFalse();
            assertThat(netns.probe(SITE_UID, METADATA, PORT))
                .as("step 7: with the policy removed the metadata service is reachable again")
                .isEqualTo("REACHABLE");
            assertThat(netns.probe(SITE_UID, HOST_SERVICE, PORT))
                .as("step 7: with the policy removed host services are reachable again")
                .isEqualTo("REACHABLE");

            // 8. And back: the refusal is repeatable, not a one-shot fixture artefact.
            policy.apply(SITE_UID, SITE);
            assertThat(netns.probe(SITE_UID, METADATA, PORT))
                .as("step 8: re-applying blocks the metadata service again")
                .isEqualTo("BLOCKED");
        }
    }

    /**
     * The sweep's process pass against the same real kernel: divergence is READ out of the
     * kernel, repaired through the verified applier, and the site is contained when the
     * repair does not take.
     */
    @Test
    void theSweepRepairsAProcessChainAndContainsTheSiteWhenItCannotBeRepaired()
            throws IOException {
        LiveLane.require(LiveLane.Need.NETNS, UidMappedNetns.available(),
            "cannot build a uid-mapped netns");

        try (UidMappedNetns netns = new UidMappedNetns()) {
            LiveLane.require(LiveLane.Need.NETNS, netns.highestMappedUid() >= CONTROL_UID,
                "the /etc/subuid range is too small to map the second uid");
            wire(netns);
            Path resolvConf = writeResolvConf("nameserver " + RESOLVER + "\n");
            ProcessNetworkPolicy policy = netns.enforcingPolicy(resolvConf);

            List<String> killed = new CopyOnWriteArrayList<>();
            List<VerifyWorkloadIsolation.ProcessSubject> subjects = List.of(
                new VerifyWorkloadIsolation.ProcessSubject(SITE_UID, "site-a", () -> {
                    killed.add("site-a");
                    return "killed the child processes of site 'site-a'";
                }),
                new VerifyWorkloadIsolation.ProcessSubject(CONTROL_UID, "site-b", () -> {
                    killed.add("site-b");
                    return "killed the child processes of site 'site-b'";
                }));

            // 1. One site policied, one not: the sweep reports exactly that split and
            //    repairs the second, which only real packets can confirm.
            policy.apply(SITE_UID, "site-a");
            VerifyWorkloadIsolation.HostOutcome first =
                VerifyWorkloadIsolation.sweepProcesses(subjects, policy);
            assertThat(first).isNotNull();
            assertThat(first.verifiable()).as("step 1: the kernel answered").isTrue();
            assertThat(first.enforced()).as("step 1: the applied site is found enforced")
                .containsExactly("site 'site-a' (uid " + SITE_UID + ")");
            assertThat(first.repaired()).as("step 1: the unpolicied site is repaired")
                .containsExactly("site 'site-b' (uid " + CONTROL_UID + ")");
            assertThat(netns.probe(CONTROL_UID, METADATA, PORT))
                .as("step 1: the repair holds against real packets")
                .isEqualTo("BLOCKED");

            // 2. The reboot/flush shape: the whole table gone under running children.
            netns.setup("nft", "delete", "table", "inet", ProcessNetworkPolicy.table());
            assertThat(netns.probe(SITE_UID, METADATA, PORT))
                .as("step 2: with the table flushed the escape is back")
                .isEqualTo("REACHABLE");
            VerifyWorkloadIsolation.HostOutcome second =
                VerifyWorkloadIsolation.sweepProcesses(subjects, policy);
            assertThat(second).isNotNull();
            assertThat(second.repaired()).as("step 2: one sweep repairs both identities")
                .hasSize(2);
            assertThat(netns.probe(SITE_UID, METADATA, PORT))
                .as("step 2: and the deny is back in the kernel").isEqualTo("BLOCKED");
            assertThat(killed).as("step 2: nothing was contained: the repair took").isEmpty();

            // 3. Diverged AND unrepairable: the site is contained rather than left running
            //    unisolated. The kernel stays readable, only writes are refused.
            NftRunner readOnly = readOnlyRunner(netns);
            ProcessNetworkPolicy refusing = new ProcessNetworkPolicy(readOnly, () -> true,
                resolvConf);
            netns.setup("nft", "delete", "table", "inet", ProcessNetworkPolicy.table());
            VerifyWorkloadIsolation.HostOutcome third =
                VerifyWorkloadIsolation.sweepProcesses(subjects, refusing);
            assertThat(third).isNotNull();
            assertThat(third.verifiable()).as("step 3: the kernel was readable").isTrue();
            assertThat(third.contained()).as("step 3: both sites are contained")
                .hasSize(2);
            assertThat(killed).as("step 3: containment really killed both sites' children")
                .containsExactly("site-a", "site-b");

            // 4. Enforcement OFF is UNVERIFIABLE, never a reason to kill anything: the
            //    pre-enforcement decision, identical to the container tiers'.
            killed.clear();
            VerifyWorkloadIsolation.HostOutcome fourth = VerifyWorkloadIsolation.sweepProcesses(
                subjects, netns.disabledPolicy(resolvConf));
            assertThat(fourth).isNotNull();
            assertThat(fourth.verifiable()).as("step 4: reported unverifiable").isFalse();
            assertThat(fourth.errors()).as("step 4: and it says why, on the record")
                .anyMatch(error -> error.contains("security.nftables_enabled"));
            assertThat(killed).as("step 4: a host that merely cannot enforce kills nothing")
                .isEmpty();
        }
    }

    /** A runner that can READ the kernel but refuses every write: the unrepairable host. */
    private NftRunner readOnlyRunner(UidMappedNetns netns) {
        NftRunner real = netns.nftRunner();
        return (args, stdin) -> args.contains("-f")
            ? new NftRunner.Result(1, "", "Error: read-only test kernel")
            : real.run(args, stdin);
    }

    /**
     * The pre-enforcement host: the applier refuses BY NAME and leaves no chain behind, so
     * the spawn path has nothing to run a child under.
     */
    @Test
    void aHostWithoutKernelEnforcementRefusesToIsolateAndThereforeRefusesTheSite()
            throws IOException {
        LiveLane.require(LiveLane.Need.NETNS, UidMappedNetns.available(),
            "cannot build a uid-mapped netns");

        try (UidMappedNetns netns = new UidMappedNetns()) {
            Path resolvConf = writeResolvConf("nameserver " + RESOLVER + "\n");
            ProcessNetworkPolicy policy = netns.disabledPolicy(resolvConf);

            assertThatThrownBy(() -> policy.apply(SITE_UID, SITE))
                .as("step 1: the refusal names the site and the setting")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("REFUSED to start site '" + SITE + "'")
                .hasMessageContaining("security.nftables_enabled is off");

            NftRunner.Result listed = netns.asRoot("nft", "list", "chain", "inet",
                ProcessNetworkPolicy.table(), ProcessNetworkPolicy.outputChain(SITE_UID));
            assertThat(listed.ok())
                .as("step 2: nothing was applied to the kernel by the refused call")
                .isFalse();
        }
    }

    /**
     * The resolver carve-out decision itself: which nameservers get a hole punched, on the
     * two host shapes that behave differently.
     */
    @Test
    void onlyAResolverInsideADeniedRangeGetsAHolePunched() throws IOException {
        // 1. The loopback-stub host (systemd-resolved, daystrom): NO carve-out at all,
        //    because loopback is not in the denied vocabulary in the first place.
        assertThat(policyFor("nameserver 127.0.0.53\n").resolvers())
            .as("step 1: a loopback stub resolver needs no hole in the policy")
            .isEmpty();

        // 2. The private-resolver host (this workstation: nameserver 10.47.0.2): exactly
        //    the denied addresses, once each, and nothing else in the file.
        assertThat(policyFor("# comment\n"
                + "search example.internal\n"
                + "nameserver 10.47.0.2\n"
                + "nameserver 1.1.1.1\n"
                + "nameserver 10.47.0.2\n"
                + "nameserver fd00::53\n"
                + "nameserver dns.example.com\n").resolvers()
                .stream().map(InetAddress::getHostAddress).toList())
            .as("step 2: only denied-range literals are carved out, deduplicated, in order")
            .containsExactly("10.47.0.2", "fd00:0:0:0:0:0:0:53");

        // 3. No resolver configuration at all is the TIGHT answer, never a refusal to
        //    apply the policy: a host with no readable resolver has none to protect.
        assertThat(policyFor(null).resolvers())
            .as("step 3: an unreadable resolv.conf yields no carve-out")
            .isEmpty();
    }

    private ProcessNetworkPolicy policyFor(String resolvConf) throws IOException {
        Path path = resolvConf == null
            ? Path.of("/nonexistent-hohenheim-resolv.conf")
            : writeResolvConf(resolvConf);
        return new ProcessNetworkPolicy((args, stdin) ->
            new NftRunner.Result(0, "", ""), () -> true, path);
    }

    private Path writeResolvConf(String body) throws IOException {
        Path path = Files.createTempFile("hohenheim-resolv", ".conf");
        path.toFile().deleteOnExit();
        Files.writeString(path, body);
        return path;
    }

    /** The host shape a process site actually lives in: several local addresses, listeners. */
    private void wire(UidMappedNetns netns) throws IOException {
        netns.setup("ip", "link", "set", "lo", "up");
        netns.setup("ip", "link", "add", "d0", "type", "dummy");
        netns.setup("ip", "addr", "add", HOST_SERVICE + "/24", "dev", "d0");
        netns.setup("ip", "addr", "add", RESOLVER + "/24", "dev", "d0");
        netns.setup("ip", "addr", "add", METADATA + "/16", "dev", "d0");
        netns.setup("ip", "addr", "add", PUBLIC + "/24", "dev", "d0");
        netns.setup("ip", "link", "set", "d0", "up");
        for (String address : new String[] {HOST_SERVICE, RESOLVER, METADATA, PUBLIC}) {
            netns.listen(address, PORT);
        }
        netns.listenDns(RESOLVER);
    }
}
