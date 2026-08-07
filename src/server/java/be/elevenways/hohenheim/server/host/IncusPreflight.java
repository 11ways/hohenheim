package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusKernelIsolation;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.incus.IncusClients;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Incus half of host preflight, stored through the SAME funnel as the Docker
 * battery ({@link HostPreflight#store}). Every check reads the daemon's own answers
 * over the pinned+identified lane; the trust check in particular is proven by an
 * AUTHENTICATED call, because {@code GET /1.0} answers untrusted clients too and an
 * "api reachable" pass would otherwise say nothing about the enrolled identity.
 *
 * AIDEV-NOTE: the observed Incus version lands in the stored facts
 * ({@code incus_version}) exactly like {@code docker_version} does -- daystrom is a
 * rolling-release host, and a behaviour change must show up as a recorded difference,
 * not a mystery flake.
 */
public final class IncusPreflight {

    /** The stored check name every kernel-truth gate reads its evidence from. */
    public static final String KERNEL_LANE_CHECK = "kernel_isolation_lane";

    private IncusPreflight() {
    }

    /** Run the battery against one host record and STORE the outcome. */
    public static HostPreflight.@NonNull Report runAndStore(@NonNull Row server) {
        HostPreflight.Report report;
        try {
            report = run(IncusClients.forServer(server));
        } catch (HostKeys.HostTrustException refusal) {
            // Same stance as the ssh lane: an unpinned host cannot be probed at all,
            // and that refusal IS the stored verdict.
            HostProbe.Outcome outcome = HostProbe.classify(refusal);
            report = new HostPreflight.Report(List.of(new HostPreflight.Check("daemon",
                HostPreflight.STATUS_FAIL, true,
                outcome.kind().token + ": " + outcome.detail())),
                Map.of(), false, Instant.now(), outcome);
        }
        report = withKernelLaneCheck(server, report);
        HostPreflight.store(String.valueOf((Object) server.get(ServerModel.NAME)), report);
        return report;
    }

    /**
     * PROVE, by transacting on it, that kernel-truth isolation verification can read this
     * host's own kernel -- and make that proof REQUIRED for a host whose posture accepts
     * tenant workloads.
     *
     * AIDEV-NOTE: this used to report {@code IncusKernelIsolation.available()} as a
     * non-required WARN, which was two defects in one line. It reported a CLAIM (a runner
     * could be constructed) as if it were evidence, and it let a host accept hostile
     * tenants with no isolation verification at all -- the exact silent-skip that turned a
     * real isolation loss into a 1-in-7 "flake" nobody was watching. It now runs the same
     * nft transaction the Docker battery's required {@code nftables} check runs, through
     * the lane the verifier itself would use, and its REQUIRED-ness follows
     * {@link ServerModel#acceptsTenantWorkloads}: a storage-only or operator-only host
     * still enrols, confirms, admits and holds backups with no lane, which is the recorded
     * backup-target decision and must not regress.
     *
     * AIDEV-NOTE: a LOCALLY addressed daemon satisfies this through the plain sudo runner
     * -- hohenheim running natively on the host it manages is the shipping configuration
     * (docs/deploy-native.md), and demanding an ssh lane to reach the machine we are
     * already on would be nonsense. What the probe proves there is the thing that actually
     * varies: that passwordless {@code sudo nft} is really granted.
     */
    private static HostPreflight.@NonNull Report withKernelLaneCheck(
            @NonNull Row server, HostPreflight.@NonNull Report report) {
        boolean required = ServerModel.acceptsTenantWorkloads(server);
        HostPreflight.Check check;
        try {
            NftRunner nft = IncusKernelIsolation.kernelRunner(server);
            check = nft == null
                ? new HostPreflight.Check(KERNEL_LANE_CHECK, HostPreflight.STATUS_FAIL, required,
                    "no trusted ssh admin lane on this record, so this host's workload"
                        + " isolation can only be read from the daemon's own configuration"
                        + " and stays UNCONFIRMED in the kernel; declare an ssh target and"
                        + " confirm its host key, or set the posture to trusted-only")
                : HostPreflight.nftablesProbe(nft, KERNEL_LANE_CHECK, required);
        } catch (RuntimeException unreachable) {
            check = new HostPreflight.Check(KERNEL_LANE_CHECK, HostPreflight.STATUS_FAIL, required,
                "could not build the kernel-truth verifier: " + unreachable.getMessage());
        }
        List<HostPreflight.Check> checks = new ArrayList<>(report.checks());
        checks.add(check);
        boolean passed = report.passed() && !(check.required() && check.failed());
        return new HostPreflight.Report(List.copyOf(checks), report.facts(), passed,
            report.at(), report.daemonFailure());
    }

    /** The battery itself, injectable for tests. */
    public static HostPreflight.@NonNull Report run(@NonNull IncusClient client) {
        List<HostPreflight.Check> checks = new ArrayList<>();
        Map<String, Object> facts = new LinkedHashMap<>();
        HostProbe.Outcome[] daemonFailure = new HostProbe.Outcome[1];

        Map<String, Object> server = probeDaemon(client, checks, facts, daemonFailure);
        if (server != null) {
            checkTrusted(server, checks);
            checkDriver(facts, checks);
            checkStorage(client, checks, facts);
            checkNetwork(client, checks, facts);
            checkAclSupport(client, checks);
        }

        boolean passed = server != null;
        for (HostPreflight.Check check : checks) {
            if (check.required() && check.failed()) {
                passed = false;
            }
        }
        return new HostPreflight.Report(List.copyOf(checks), facts, passed,
            Instant.now(), daemonFailure[0]);
    }

    private static Map<String, Object> probeDaemon(IncusClient client,
                                                   List<HostPreflight.Check> checks,
                                                   Map<String, Object> facts,
                                                   HostProbe.Outcome[] failure) {
        try {
            Map<String, Object> server = client.server();
            Map<String, Object> environment =
                server.get("environment") instanceof Map<?, ?> map
                    ? castMap(map) : Map.of();
            facts.put("incus_version", stringOf(environment.get("server_version")));
            facts.put("api_version", stringOf(server.get("api_version")));
            facts.put("kernel_version", stringOf(environment.get("kernel_version")));
            facts.put("os", stringOf(environment.get("os_name")));
            facts.put("os_type", BlastString.lower(stringOf(environment.get("kernel"))));
            facts.put("architecture", stringOf(environment.get("kernel_architecture")));
            facts.put("server_name", stringOf(environment.get("server_name")));
            facts.put("project", stringOf(environment.get("project")));
            facts.put("driver", stringOf(environment.get("driver")));
            facts.put("auth", stringOf(server.get("auth")));
            checks.add(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true,
                "Incus " + facts.get("incus_version") + " reachable (API "
                    + facts.get("api_version") + ")"));
            return server;
        } catch (Exception error) {
            HostProbe.Outcome outcome = HostProbe.classify(error);
            failure[0] = outcome;
            checks.add(new HostPreflight.Check("daemon", HostPreflight.STATUS_FAIL, true,
                outcome.kind().token + ": " + outcome.detail()));
            return null;
        }
    }

    /**
     * {@code auth} must be {@code trusted}: {@code GET /1.0} answers ANY TLS client,
     * so reachability alone proves nothing about the enrolled identity.
     */
    private static void checkTrusted(Map<String, Object> server,
                                     List<HostPreflight.Check> checks) {
        String auth = stringOf(server.get("auth"));
        checks.add(new HostPreflight.Check("trusted",
            "trusted".equals(auth) ? HostPreflight.STATUS_PASS : HostPreflight.STATUS_FAIL,
            true, "trusted".equals(auth)
                ? "the daemon trusts this client's certificate"
                : "the daemon answers but reports this client '" + auth
                    + "': enroll the client certificate (trust token or incus config trust)"));
    }

    /** System containers need the lxc driver; a qemu-only daemon cannot run this tier. */
    private static void checkDriver(Map<String, Object> facts,
                                    List<HostPreflight.Check> checks) {
        String driver = stringOf(facts.get("driver"));
        boolean lxc = ("|" + driver.replace(" ", "") + "|").contains("|lxc|");
        checks.add(new HostPreflight.Check("driver_lxc",
            lxc ? HostPreflight.STATUS_PASS : HostPreflight.STATUS_FAIL, true,
            "daemon drivers: '" + driver + "'"
                + (lxc ? "" : " -- no lxc driver, system containers cannot run")));
    }

    private static void checkStorage(IncusClient client, List<HostPreflight.Check> checks,
                                     Map<String, Object> facts) {
        try {
            List<Map<String, Object>> pools = client.storagePools();
            List<String> described = new ArrayList<>();
            boolean created = false;
            for (Map<String, Object> pool : pools) {
                described.add(pool.get("name") + "(" + pool.get("driver") + ","
                    + pool.get("status") + ")");
                created |= "Created".equalsIgnoreCase(stringOf(pool.get("status")));
            }
            facts.put("storage_pools", String.join(" ", described));
            checks.add(new HostPreflight.Check("storage_pool",
                created ? HostPreflight.STATUS_PASS : HostPreflight.STATUS_FAIL, true,
                created ? "storage pools: " + String.join(", ", described)
                    : "no CREATED storage pool -- instances have nowhere to live ("
                        + described + ")"));
        } catch (IOException error) {
            checks.add(new HostPreflight.Check("storage_pool", HostPreflight.STATUS_FAIL,
                true, "could not list storage pools: " + error.getMessage()));
        }
    }

    private static void checkNetwork(IncusClient client, List<HostPreflight.Check> checks,
                                     Map<String, Object> facts) {
        try {
            List<Map<String, Object>> networks = client.networks();
            String managed = null;
            for (Map<String, Object> network : networks) {
                if (Boolean.TRUE.equals(network.get("managed"))
                        && "bridge".equals(network.get("type"))) {
                    managed = stringOf(network.get("name"));
                    break;
                }
            }
            facts.put("managed_bridge", managed != null ? managed : "");
            checks.add(new HostPreflight.Check("managed_network",
                managed != null ? HostPreflight.STATUS_PASS : HostPreflight.STATUS_FAIL,
                true, managed != null
                    ? "managed bridge '" + managed + "' present"
                    : "no managed bridge network -- containers would have no connectivity"));
        } catch (IOException error) {
            checks.add(new HostPreflight.Check("managed_network", HostPreflight.STATUS_FAIL,
                true, "could not list networks: " + error.getMessage()));
        }
    }

    /**
     * The isolation prerequisite, proven by DOING it: create a probe network ACL, read
     * it back, delete it. Tenant isolation on this tier RIDES the daemon's ACL support
     * (see {@link IncusNetworkPolicy}); a daemon that cannot carry an ACL cannot isolate
     * a tenant, and that must surface here rather than on a tenant deploy.
     */
    private static void checkAclSupport(IncusClient client, List<HostPreflight.Check> checks) {
        String name = "hohenheim-preflight-acl-" + Long.toHexString(System.nanoTime());
        try {
            client.createNetworkAcl(Map.of(
                "name", name,
                "description", "hohenheim preflight probe",
                "egress", List.of(Map.of("action", "reject",
                    "destination", "169.254.0.0/16", "state", "enabled")),
                "ingress", List.of(),
                "config", Map.of()));
            Map<String, Object> readBack = client.networkAcl(name);
            boolean visible = readBack != null
                && readBack.get("egress") instanceof List<?> egress && !egress.isEmpty();
            try {
                client.deleteNetworkAcl(name);
            } catch (IOException cleanup) {
                // a stuck probe ACL is the reconciler's, not this verdict's, problem
            }
            checks.add(new HostPreflight.Check("network_acl",
                visible ? HostPreflight.STATUS_PASS : HostPreflight.STATUS_FAIL, true,
                visible ? "network ACL created and read back -- tenant isolation is enforceable"
                    : "the daemon accepted an ACL but it did not read back with its rule;"
                        + " tenant isolation cannot be enforced on this host"));
        } catch (Exception error) {
            checks.add(new HostPreflight.Check("network_acl", HostPreflight.STATUS_FAIL, true,
                "could not create a probe network ACL, so tenant isolation is not"
                    + " enforceable: " + error.getMessage()));
        }
    }

    private static String stringOf(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
