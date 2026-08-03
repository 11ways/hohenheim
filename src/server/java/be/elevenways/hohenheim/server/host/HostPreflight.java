package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verification, not declaration: every probe here reads KERNEL truth (a running
 * probe container's own /proc and cgroup files, a real nft transaction, a real
 * network create) instead of trusting daemon JSON -- the anti-theater stance
 * ContainerHardeningTest established. The report is STORED on the host record
 * ({@code capabilities} + {@code probed_at} + {@code preflight_ok}); the in-memory
 * Summary already proved itself useless to an allocator.
 *
 * AIDEV-NOTE: a check that cannot run (no sudo nft, ssh down) FAILS with the real
 * error text; there is no skip status. A host nobody could verify is a host nobody
 * may admit -- that refusal is the named prerequisite the container-hardening and
 * networking work both deferred to here.
 */
public final class HostPreflight {

    public static final String STATUS_PASS = "pass";
    public static final String STATUS_WARN = "warn";
    public static final String STATUS_FAIL = "fail";

    /** Oldest daemon API we accept: 1.41 = Docker 20.10, the floor our request shapes assume. */
    static final double MIN_API_VERSION = 1.41;

    /** User-defined network count beyond which the default address pool nears exhaustion. */
    static final int NETWORK_HEADROOM_WARN = 25;

    /** One named probe. Only {@code required} checks decide the verdict. */
    public record Check(@NonNull String name, @NonNull String status, boolean required,
                        @NonNull String detail) {

        public boolean failed() {
            return STATUS_FAIL.equals(this.status);
        }
    }

    /** The whole preflight outcome; {@code passed} = no REQUIRED check failed. */
    public record Report(@NonNull List<Check> checks, @NonNull Map<String, Object> facts,
                         boolean passed, @NonNull Instant at) {

        public @Nullable Check check(@NonNull String name) {
            for (Check check : this.checks) {
                if (check.name().equals(name)) {
                    return check;
                }
            }
            return null;
        }
    }

    /** The nft seam: local sudo in production, ssh for remote hosts, a netns runner in tests. */
    public interface NftProbe {
        NftRunner.@NonNull Result run(@NonNull List<String> nftArgs, @Nullable String stdin);
    }

    private HostPreflight() {
    }

    /**
     * Run the full preflight against one inventoried host and STORE the outcome on its
     * record (capabilities JSON, probed_at, preflight_ok, health columns).
     *
     * @throws IllegalArgumentException when no such server exists
     */
    public static @NonNull Report runAndStore(@NonNull String serverName) {
        ServerService servers = new ServerService();
        Row server = Models.get(ServerModel.class).findByName(serverName);
        if (server == null) {
            throw new IllegalArgumentException("No server named '" + serverName + "'");
        }
        Report report = run(servers.clientFor(serverName), nftProbeFor(server));
        store(serverName, report);
        return report;
    }

    /** The production nft seam per host mode: local sudo, or the same sudo command over ssh. */
    static @NonNull NftProbe nftProbeFor(@NonNull Row server) {
        if (ServerModel.MODE_SSH.equals(server.get(ServerModel.MODE))) {
            String target = server.get(ServerModel.SSH_TARGET);
            return (args, stdin) -> {
                List<String> argv = new ArrayList<>(List.of("ssh", "-o", "BatchMode=yes",
                    "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=10",
                    "--", target, "sudo", "-n", "--", "nft"));
                argv.addAll(args);
                return NftRunner.Sudo.execute(argv, stdin, 15);
            };
        }
        NftRunner local = new NftRunner.Sudo();
        return local::run;
    }

    /**
     * The probe battery against one daemon, with an injectable nft seam so tests can
     * point the nft half at a private netns while the container half runs against the
     * real daemon.
     */
    public static @NonNull Report run(@NonNull DockerClient docker, @NonNull NftProbe nft) {
        List<Check> checks = new ArrayList<>();
        Map<String, Object> facts = new LinkedHashMap<>();

        Map<String, Object> info = probeDaemon(docker, checks, facts);
        if (info != null) {
            checkApiVersion(facts, checks);
            checkDaemonPosture(info, checks);
            probeContainerKernel(docker, checks);
            checkNetworkHeadroom(docker, checks, facts);
        }
        checkNftables(nft, checks);

        boolean passed = info != null;
        for (Check check : checks) {
            if (check.required() && check.failed()) {
                passed = false;
            }
        }
        return new Report(List.copyOf(checks), facts, passed, Instant.now());
    }

    // -- individual probes ----------------------------------------------------

    private static @Nullable Map<String, Object> probeDaemon(DockerClient docker,
                                                             List<Check> checks,
                                                             Map<String, Object> facts) {
        try {
            Map<String, Object> version = docker.version();
            Map<String, Object> info = docker.info();
            facts.put("docker_version", stringOf(version.get("Version")));
            facts.put("api_version", stringOf(version.get("ApiVersion")));
            facts.put("kernel_version", stringOf(version.get("KernelVersion")));
            facts.put("os", stringOf(info.get("OperatingSystem")));
            facts.put("os_type", stringOf(info.get("OSType")));
            facts.put("architecture", stringOf(info.get("Architecture")));
            facts.put("ncpu", numberOf(info.get("NCPU")));
            facts.put("mem_total", numberOf(info.get("MemTotal")));
            facts.put("cgroup_version", stringOf(info.get("CgroupVersion")));
            facts.put("cgroup_driver", stringOf(info.get("CgroupDriver")));
            facts.put("containers", numberOf(info.get("Containers")));
            facts.put("containers_running", numberOf(info.get("ContainersRunning")));
            facts.put("images", numberOf(info.get("Images")));
            checks.add(new Check("daemon", STATUS_PASS, true,
                "Docker " + facts.get("docker_version") + " reachable"));
            return info;
        } catch (Exception error) {
            HostProbe.Outcome outcome = HostProbe.classify(error);
            checks.add(new Check("daemon", STATUS_FAIL, true,
                outcome.kind().token + ": " + outcome.detail()));
            return null;
        }
    }

    private static void checkApiVersion(Map<String, Object> facts, List<Check> checks) {
        String api = String.valueOf(facts.get("api_version"));
        double parsed;
        try {
            parsed = Double.parseDouble(api);
        } catch (NumberFormatException e) {
            checks.add(new Check("api_version", STATUS_FAIL, true,
                "unparseable daemon API version '" + api + "'"));
            return;
        }
        checks.add(new Check("api_version", parsed >= MIN_API_VERSION ? STATUS_PASS : STATUS_FAIL,
            true, "daemon API " + api + " (minimum " + MIN_API_VERSION + ")"));
    }

    /** Daemon-declared posture: userns remap, LSM presence, default-bridge IPv6. Advisory. */
    private static void checkDaemonPosture(Map<String, Object> info, List<Check> checks) {
        List<String> securityOptions = new ArrayList<>();
        if (info.get("SecurityOptions") instanceof List<?> options) {
            for (Object option : options) {
                securityOptions.add(String.valueOf(option));
            }
        }
        boolean userns = securityOptions.stream().anyMatch(o -> o.contains("name=userns"));
        checks.add(new Check("userns_remap", userns ? STATUS_PASS : STATUS_WARN, false,
            userns ? "daemon runs with userns-remap"
                   : "no userns-remap: container root is host root (daemon.json userns-remap)"));
        boolean apparmor = securityOptions.stream().anyMatch(o -> o.contains("name=apparmor"));
        boolean selinux = securityOptions.stream().anyMatch(o -> o.contains("name=selinux"));
        checks.add(new Check("lsm", (apparmor || selinux) ? STATUS_PASS : STATUS_WARN, false,
            apparmor ? "AppArmor active" : selinux ? "SELinux active" : "no AppArmor/SELinux"));
    }

    /**
     * Kernel truth from INSIDE a running hardened probe container: delegated pids
     * controller, the pids cap actually enforced, seccomp filtering, no_new_privs.
     * A {@code PidsLimit} a host silently ignores is exactly the reports-success
     * defect the hardening baseline would otherwise rest on.
     */
    private static void probeContainerKernel(DockerClient docker, List<Check> checks) {
        String name = "hohenheim-preflight-" + System.nanoTime();
        int expectedPids = ContainerHardening.pidsLimit();
        try {
            docker.ensureImage("alpine", "latest");
            docker.createContainer(name, Map.of(
                "Image", "alpine:latest",
                "Cmd", List.of("sleep", "60")), ContainerHardening.STRICT);
            docker.startContainer(name);
            DockerClient.ExecResult result = docker.exec(name, List.of("sh", "-c",
                "cat /sys/fs/cgroup/cgroup.controllers 2>/dev/null; echo '---';"
                    + " cat /sys/fs/cgroup/pids.max 2>/dev/null; echo '---';"
                    + " grep -E '^(Seccomp|NoNewPrivs):' /proc/1/status"));
            if (result.exitCode() != 0) {
                checks.add(new Check("container_kernel", STATUS_FAIL, true,
                    "kernel probe exec failed: " + result.output()));
                return;
            }
            String[] sections = result.output().split("---");
            String controllers = sections.length > 0 ? sections[0].trim() : "";
            String pidsMax = sections.length > 1 ? sections[1].trim() : "";
            String status = sections.length > 2 ? sections[2].trim() : "";

            boolean pidsDelegated = controllers.contains("pids");
            checks.add(new Check("cgroup_pids_controller",
                pidsDelegated ? STATUS_PASS : STATUS_FAIL, true,
                pidsDelegated ? "cgroup v2 controllers: " + controllers
                    : "pids controller not delegated (controllers: '" + controllers
                        + "'); a PidsLimit on this host enforces NOTHING"));

            boolean pidsEnforced = String.valueOf(expectedPids).equals(pidsMax);
            checks.add(new Check("pids_limit_enforced",
                pidsEnforced ? STATUS_PASS : STATUS_FAIL, true,
                "pids.max inside the container reads '" + pidsMax + "' (configured "
                    + expectedPids + ")"));

            boolean seccomp = status.lines().anyMatch(l ->
                l.startsWith("Seccomp:") && l.trim().endsWith("2"));
            checks.add(new Check("seccomp", seccomp ? STATUS_PASS : STATUS_FAIL, true,
                seccomp ? "seccomp filter mode active on pid 1"
                    : "seccomp NOT filtering (unconfined daemon profile?): " + status));

            boolean nnp = status.lines().anyMatch(l ->
                l.startsWith("NoNewPrivs:") && l.trim().endsWith("1"));
            checks.add(new Check("no_new_privs", nnp ? STATUS_PASS : STATUS_FAIL, true,
                nnp ? "no_new_privs set on pid 1" : "no_new_privs NOT set: " + status));
        } catch (Exception error) {
            checks.add(new Check("container_kernel", STATUS_FAIL, true,
                "probe container failed: " + error.getMessage()));
        } finally {
            try {
                docker.removeContainer(name, true);
            } catch (IOException ignored) {
                // never created, or already gone
            }
        }
    }

    /**
     * Address-pool headroom, proven by DOING it: create one probe network and remove
     * it. Docker's default pool exhausts after roughly 30 user-defined networks and
     * every instance now creates one, so "could not allocate subnet" must surface
     * here, not on a tenant deploy.
     */
    private static void checkNetworkHeadroom(DockerClient docker, List<Check> checks,
                                             Map<String, Object> facts) {
        String name = "hohenheim-preflight-net-" + System.nanoTime();
        int networks = 0;
        try {
            networks = docker.listNetworks().size();
            facts.put("networks", networks);
            docker.createNetwork(name, null, null, null, false);
            docker.removeNetwork(name);
            checks.add(new Check("network_headroom",
                networks > NETWORK_HEADROOM_WARN ? STATUS_WARN : STATUS_PASS, true,
                "one more user-defined network is allocatable (" + networks + " exist"
                    + (networks > NETWORK_HEADROOM_WARN
                        ? "; nearing default-address-pools exhaustion" : "") + ")"));
        } catch (Exception error) {
            checks.add(new Check("network_headroom", STATUS_FAIL, true,
                "could not create a probe network (" + networks + " exist): "
                    + error.getMessage()));
            try {
                docker.removeNetwork(name);
            } catch (IOException ignored) {
                // never created
            }
        }
    }

    /**
     * Real nftables on the host's OWN netns: add a probe table, read it back, delete
     * it. The networking work proved its rule strings in a private namespace but could
     * NOT prove the host accepts them; this check is that missing proof.
     */
    private static void checkNftables(NftProbe nft, List<Check> checks) {
        String table = "hohenheim_preflight_" + Long.toHexString(System.nanoTime());
        try {
            NftRunner.Result added = nft.run(List.of("-f", "-"),
                "add table inet " + table + "\n");
            if (!added.ok()) {
                checks.add(new Check("nftables", STATUS_FAIL, true,
                    "nft add table refused: " + added.failureText()));
                return;
            }
            NftRunner.Result listed = nft.run(List.of("list", "table", "inet", table), null);
            boolean visible = listed.ok() && listed.stdout().contains(table);
            nft.run(List.of("delete", "table", "inet", table), null);
            checks.add(new Check("nftables", visible ? STATUS_PASS : STATUS_FAIL, true,
                visible ? "nft transaction applied and read back from the kernel"
                    : "nft add reported success but the table did not read back: "
                        + listed.failureText()));
        } catch (Exception error) {
            checks.add(new Check("nftables", STATUS_FAIL, true,
                "nft probe failed: " + error.getMessage()));
        }
    }

    // -- persistence ----------------------------------------------------------

    /** Store one report on the host record; also refreshes the typed health columns. */
    static void store(@NonNull String serverName, @NonNull Report report) {
        Row server = Models.get(ServerModel.class).findByName(serverName);
        if (server == null) {
            return;
        }
        Map<String, Object> capabilities = new LinkedHashMap<>(report.facts());
        Map<String, Object> checkMap = new LinkedHashMap<>();
        for (Check check : report.checks()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", check.status());
            entry.put("required", check.required());
            entry.put("detail", check.detail());
            checkMap.put(check.name(), entry);
        }
        capabilities.put("checks", checkMap);
        server.set(ServerModel.CAPABILITIES, capabilities);
        server.set(ServerModel.PROBED_AT, report.at());
        server.set(ServerModel.PREFLIGHT_OK, report.passed());
        server.set(ServerModel.CONTROLLER_VERSION, controllerVersion());
        Check daemon = report.check("daemon");
        if (daemon != null && !daemon.failed()) {
            server.set(ServerModel.LAST_SEEN_AT, report.at());
            server.set(ServerModel.LAST_ERROR_KIND, null);
            server.set(ServerModel.LAST_ERROR, null);
        } else if (daemon != null) {
            server.set(ServerModel.LAST_ERROR_KIND, daemon.detail().contains(":")
                ? daemon.detail().substring(0, daemon.detail().indexOf(':')) : "unreachable");
            server.set(ServerModel.LAST_ERROR, daemon.detail());
        }
        Models.get(ServerModel.class).save(server);
        Blast.slog("hohenheim.host.preflight", Map.of(
            "server", serverName,
            "passed", report.passed(),
            "checks", checkMap.size()));
    }

    /** This controller build's version string; "dev" when running from classes. */
    static @NonNull String controllerVersion() {
        String version = HostPreflight.class.getPackage() != null
            ? HostPreflight.class.getPackage().getImplementationVersion() : null;
        return version != null ? version : "dev";
    }

    private static String stringOf(@Nullable Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static Object numberOf(@Nullable Object value) {
        return value instanceof Number number ? number : 0;
    }
}
