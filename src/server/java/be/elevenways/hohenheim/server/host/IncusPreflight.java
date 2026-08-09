package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusKernelIsolation;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.incus.IncusClients;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

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

    /** The stored check name carrying the user-namespace verdict of a real instance. */
    public static final String USERNS_CHECK = "container_userns";

    /** The stored check name carrying the seccomp verdict of a real instance. */
    public static final String SECCOMP_CHECK = "container_seccomp";

    /** Run the battery against one host record and STORE the outcome. */
    public static HostPreflight.@NonNull Report runAndStore(@NonNull Row server) {
        HostPreflight.Report report;
        try {
            IncusClient client = IncusClients.forServer(server);
            report = withContainerKernelChecks(server, run(client), client);
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

    /**
     * PROVE what the kernel does to a real workload on this host, by running one:
     * user-namespace remapping and seccomp filtering, read out of a probe instance's own
     * {@code /proc}, plus the LSM label the kernel attached to it.
     *
     * AIDEV-NOTE: this is the Incus half of the check the Docker battery has always run
     * inside its probe container, and it is a probe INSTANCE for the same reason it is a
     * probe container there -- the daemon's own {@code /1.0} answer is a claim, and in
     * Incus 7.3 it is not even that: {@code environment.kernel_features} and
     * {@code lxc_features} come back as EMPTY maps (measured on daystrom), so there is no
     * daemon-side evidence to relay even if relaying were acceptable. The probe rides the
     * SAME simplestreams lane real instances use, so a host that cannot answer this also
     * could not have run a tenant workload.
     *
     * AIDEV-NOTE: REQUIRED-ness follows {@link ServerModel#acceptsTenantWorkloads}, the
     * {@code kernel_isolation_lane} rule, and for the Incus tier the verdict is the
     * OPPOSITE of the Docker battery's advisory stance on the same question: an
     * unprivileged (userns-remapped) container is the Incus DEFAULT, so a tenant-accepting
     * host that hands a workload the host's own uid range is misconfigured, not merely
     * old-fashioned. A storage-only or operator-only host keeps enrolling, and its probe
     * still runs and still records what it found -- absence of a verdict is what this
     * whole battery exists to stop.
     */
    private static HostPreflight.@NonNull Report withContainerKernelChecks(
            @NonNull Row server, HostPreflight.@NonNull Report report,
            @NonNull IncusClient client) {
        boolean required = ServerModel.acceptsTenantWorkloads(server);
        List<HostPreflight.Check> checks = new ArrayList<>(report.checks());
        checks.addAll(probeInstanceKernel(client, required));
        boolean passed = report.passed();
        for (HostPreflight.Check check : checks) {
            if (check.required() && check.failed()) {
                passed = false;
            }
        }
        return new HostPreflight.Report(List.copyOf(checks), report.facts(), passed,
            report.at(), report.daemonFailure());
    }

    /** The image the kernel probe runs; a system container of the ordinary tenant shape. */
    static final String PROBE_IMAGE = "alpine/3.22";

    /** How long the probe instance may take to accept its one exec. */
    private static final long PROBE_EXEC_TIMEOUT_MS = 30_000;

    private static @NonNull List<HostPreflight.Check> probeInstanceKernel(
            @NonNull IncusClient client, boolean required) {
        String name = "hohenheim-preflight-" + Long.toHexString(System.nanoTime());
        try {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("type", "image");
            source.put("protocol", "simplestreams");
            source.put("server", IncusInstanceRuntime.IMAGE_SERVER);
            source.put("alias", PROBE_IMAGE);
            client.createInstance(Map.of(
                "name", name,
                "type", "container",
                "source", source,
                "config", Map.of("user.hohenheim.probe", "preflight")));
            client.changeState(name, "start", 30, false);
            IncusClient.ExecResult result = awaitProbeExec(client, name);
            if (result == null) {
                return List.of(unanswered(USERNS_CHECK, required,
                        "the probe instance never accepted an exec"),
                    unanswered(SECCOMP_CHECK, required,
                        "the probe instance never accepted an exec"),
                    unanswered("lsm", false, "the probe instance never accepted an exec"));
            }
            String[] sections = result.output().split("---");
            String uidMap = sections.length > 0 ? sections[0].trim() : "";
            String status = sections.length > 1 ? sections[1].trim() : "";
            String lsmLabel = sections.length > 2 ? sections[2].trim() : "";
            return List.of(usernsCheck(uidMap, required), seccompCheck(status, required),
                HostPreflight.lsmCheck(lsmLabel));
        } catch (Exception error) {
            String detail = "the kernel probe instance could not be run ("
                + error.getMessage() + "), so what this host does to a workload is UNKNOWN";
            return List.of(unanswered(USERNS_CHECK, required, detail),
                unanswered(SECCOMP_CHECK, required, detail),
                unanswered("lsm", false, detail));
        } finally {
            try {
                client.changeState(name, "stop", 10, true);
            } catch (IOException | RuntimeException alreadyDown) {
                // never started, or already gone
            }
            try {
                client.deleteInstance(name);
            } catch (IOException | RuntimeException alreadyGone) {
                // a stuck probe instance is the reconciler's, not this verdict's, problem
            }
        }
    }

    /**
     * One exec, retried until the freshly started instance accepts it.
     *
     * @return null when the window closed with no answer -- never a fabricated one
     */
    private static IncusClient.@Nullable ExecResult awaitProbeExec(@NonNull IncusClient client,
                                                                   @NonNull String name) {
        long deadline = System.currentTimeMillis() + PROBE_EXEC_TIMEOUT_MS;
        do {
            try {
                IncusClient.ExecResult result = client.exec(name, List.of("sh", "-c",
                    "cat /proc/self/uid_map; echo '---';"
                        + " grep -E '^(Seccomp|NoNewPrivs):' /proc/1/status; echo '---';"
                        // Trailing "|| true": daystrom's kernel carries no LSM and
                        // answers this read with EINVAL, and the LAST command decides
                        // sh's exit code -- without it the probe retried until its
                        // window closed and reported UNKNOWN on a perfectly good host.
                        + " cat /proc/1/attr/current 2>/dev/null || true"),
                    Map.of(), 10_000);
                if (result.exitCode() == 0) {
                    return result;
                }
            } catch (IOException | RuntimeException notReady) {
                // the instance is still coming up; the deadline is the only verdict
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    /** A check whose question was asked and NOT answered: a fail, never a silent pass. */
    private static HostPreflight.@NonNull Check unanswered(@NonNull String name,
                                                           boolean required,
                                                           @NonNull String detail) {
        return new HostPreflight.Check(name, HostPreflight.STATUS_FAIL, required,
            "UNKNOWN: " + detail);
    }

    /** The Incus verdict on the same uid_map the Docker battery reads, but REQUIRED. */
    private static HostPreflight.@NonNull Check usernsCheck(@NonNull String uidMap,
                                                            boolean required) {
        HostPreflight.Check advisory = HostPreflight.usernsCheck(uidMap);
        boolean remapped = HostPreflight.STATUS_PASS.equals(advisory.status());
        return new HostPreflight.Check(USERNS_CHECK,
            remapped ? HostPreflight.STATUS_PASS : HostPreflight.STATUS_FAIL, required,
            remapped ? advisory.detail()
                : advisory.detail() + "; an Incus system container is unprivileged by"
                    + " DEFAULT, so this host is configured to hand workloads the host's"
                    + " own uid range");
    }

    /** Seccomp filter mode on the probe instance's pid 1, read from {@code /proc}. */
    private static HostPreflight.@NonNull Check seccompCheck(@NonNull String status,
                                                             boolean required) {
        boolean filtering = status.lines().anyMatch(line ->
            line.startsWith("Seccomp:") && line.trim().endsWith("2"));
        return new HostPreflight.Check(SECCOMP_CHECK,
            filtering ? HostPreflight.STATUS_PASS : HostPreflight.STATUS_FAIL, required,
            filtering ? "seccomp filter mode active on the probe instance's pid 1"
                : "seccomp is NOT filtering inside a workload on this host: " + status);
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
            recordResources(client, facts, checks);
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
     * The host's measured CPU count and total memory, under the SAME fact names the Docker
     * battery stores them.
     *
     * AIDEV-NOTE: {@code mem_total} is no longer a display fact -- InstanceCapacity reads
     * it back as the denominator of every placement decision on this host, and the Incus
     * battery simply never recorded it (only ServerService's live summary probe did, in
     * memory). A host whose resources cannot be read gets a REQUIRED failing check rather
     * than a missing fact, because a silently absent budget is the zero-denominator gate
     * this whole seam exists to remove; placement skips such a host by name either way,
     * and the operator should see the reason on the preflight report, not only at create.
     */
    private static void recordResources(IncusClient client, Map<String, Object> facts,
                                        List<HostPreflight.Check> checks) {
        try {
            Map<String, Object> resources = client.resources();
            if (resources.get("cpu") instanceof Map<?, ?> cpu
                    && cpu.get("total") instanceof Number total) {
                facts.put("ncpu", total.intValue());
            }
            if (resources.get("memory") instanceof Map<?, ?> memory
                    && memory.get("total") instanceof Number total) {
                facts.put(HostPreflight.MEM_TOTAL_FACT, total.longValue());
            }
        } catch (IOException | RuntimeException unreadable) {
            checks.add(new HostPreflight.Check("resources", HostPreflight.STATUS_FAIL, true,
                "the daemon's resource inventory could not be read, so this host has no"
                    + " memory budget to place against: " + unreadable.getMessage()));
            return;
        }
        boolean measured = facts.get(HostPreflight.MEM_TOTAL_FACT) instanceof Number bytes
            && bytes.longValue() > 0;
        checks.add(new HostPreflight.Check("resources",
            measured ? HostPreflight.STATUS_PASS : HostPreflight.STATUS_FAIL, true,
            measured ? "host reports " + facts.get("ncpu") + " CPUs and "
                    + facts.get(HostPreflight.MEM_TOTAL_FACT) + " bytes of memory"
                : "the daemon reported no total memory, so this host has no memory budget"
                    + " to place against"));
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
