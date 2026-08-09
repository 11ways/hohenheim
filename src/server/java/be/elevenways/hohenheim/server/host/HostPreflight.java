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
import java.time.format.DateTimeParseException;
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

    /**
     * The stored fact carrying a host's total memory in BYTES, written by both batteries
     * and read as the denominator of every placement decision on that host.
     */
    public static final String MEM_TOTAL_FACT = "mem_total";

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

    /**
     * The whole preflight outcome; {@code passed} = no REQUIRED check failed.
     * {@code daemonFailure} carries the TYPED classification of an unreachable daemon so
     * persistence never has to re-derive a kind out of prose it composed itself.
     */
    public record Report(@NonNull List<Check> checks, @NonNull Map<String, Object> facts,
                         boolean passed, @NonNull Instant at,
                         HostProbe.@Nullable Outcome daemonFailure) {

        public @Nullable Check check(@NonNull String name) {
            for (Check check : this.checks) {
                if (check.name().equals(name)) {
                    return check;
                }
            }
            return null;
        }
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
        // The runtime declaration is DATA on the record: an Incus host gets the Incus
        // battery through the same store funnel, never a Docker probe aimed at it.
        if (ServerModel.isIncus(server)) {
            return IncusPreflight.runAndStore(server);
        }
        Report report;
        try {
            // The SAME per-server nft lane the workload policy applier uses -- what this
            // probe proves is what a deploy will later rely on, not a lookalike.
            report = run(servers.clientFor(serverName), NftRunner.forServer(server));
        } catch (HostKeys.HostTrustException refusal) {
            // An unpinned host cannot be probed at all -- that refusal is itself the
            // verdict, and it must be STORED like any other, not thrown at the operator
            // as an untyped stack trace.
            HostProbe.Outcome outcome = HostProbe.classify(refusal);
            report = new Report(List.of(new Check("daemon", STATUS_FAIL, true,
                outcome.kind().token + ": " + outcome.detail())),
                Map.of(), false, Instant.now(), outcome);
        }
        store(serverName, report);
        return report;
    }

    /**
     * The probe battery against one daemon, with an injectable nft seam so tests can
     * point the nft half at a private netns while the container half runs against the
     * real daemon.
     */
    public static @NonNull Report run(@NonNull DockerClient docker, @NonNull NftRunner nft) {
        List<Check> checks = new ArrayList<>();
        Map<String, Object> facts = new LinkedHashMap<>();
        HostProbe.Outcome[] daemonFailure = new HostProbe.Outcome[1];

        Map<String, Object> info = probeDaemon(docker, checks, facts, daemonFailure);
        if (info != null) {
            checkApiVersion(facts, checks);
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
        return new Report(List.copyOf(checks), facts, passed, Instant.now(), daemonFailure[0]);
    }

    // -- individual probes ----------------------------------------------------

    private static @Nullable Map<String, Object> probeDaemon(DockerClient docker,
                                                             List<Check> checks,
                                                             Map<String, Object> facts,
                                                             HostProbe.Outcome[] failure) {
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
            facts.put(MEM_TOTAL_FACT, numberOf(info.get("MemTotal")));
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
            failure[0] = outcome;
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

    /**
     * Kernel truth from INSIDE a running hardened probe container: delegated pids
     * controller, the pids cap actually enforced, seccomp filtering, no_new_privs,
     * user-namespace remapping and LSM confinement. A {@code PidsLimit} a host silently
     * ignores is exactly the reports-success defect the hardening baseline would
     * otherwise rest on.
     *
     * AIDEV-NOTE: userns and the LSM label moved HERE from the daemon's own
     * {@code SecurityOptions} list on 2026-08-07, for the reason the
     * {@code kernel_isolation_lane} check moved the day before: {@code name=userns} in an
     * Info response is a CLAIM about how the daemon was configured, and this battery's
     * whole stance is that a claim is not evidence. {@code /proc/self/uid_map} is the
     * kernel's own answer -- an unremapped container reads {@code 0 0 4294967295}, a
     * remapped one reads a non-zero host offset with a bounded range -- and
     * {@code /proc/1/attr/current} is the LSM label the kernel actually attached, which is
     * empty or unreadable when no LSM confines the container at all.
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
                    + " grep -E '^(Seccomp|NoNewPrivs):' /proc/1/status; echo '---';"
                    + " cat /proc/self/uid_map 2>/dev/null; echo '---';"
                    // Trailing "|| true": a kernel with no LSM answers this read with
                    // EINVAL, and the LAST command decides sh's exit code -- without it
                    // the whole probe reads as "the exec failed" on every LSM-less host.
                    + " cat /proc/1/attr/current 2>/dev/null || true"));
            if (result.exitCode() != 0) {
                checks.add(new Check("container_kernel", STATUS_FAIL, true,
                    "kernel probe exec failed: " + result.output()));
                return;
            }
            String[] sections = result.output().split("---");
            String controllers = sections.length > 0 ? sections[0].trim() : "";
            String pidsMax = sections.length > 1 ? sections[1].trim() : "";
            String status = sections.length > 2 ? sections[2].trim() : "";
            String uidMap = sections.length > 3 ? sections[3].trim() : "";
            String lsmLabel = sections.length > 4 ? sections[4].trim() : "";
            checks.add(usernsCheck(uidMap));
            checks.add(lsmCheck(lsmLabel));

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

    /** The uid_map an UNREMAPPED container reads: the whole host uid space, offset zero. */
    public static final String IDENTITY_UID_MAP = "0 0 4294967295";

    /**
     * The user-namespace verdict read off a probe workload's own {@code /proc/self/uid_map}.
     *
     * AIDEV-NOTE: ADVISORY on purpose, and this is an availability decision, not an
     * oversight. Docker's {@code userns-remap} is off by default on every distribution and
     * turning it on changes volume ownership semantics for every existing workload, so a
     * REQUIRED check here would refuse admission to essentially every Docker host in
     * existence, both live twins included -- refusing a host that already carries
     * production workloads over a posture nobody ships is a worse outcome than a warning
     * nobody can miss. The Incus battery reaches the OPPOSITE conclusion for its own tier
     * and says why there: unprivileged is the Incus DEFAULT, so a host without it is
     * misconfigured rather than ordinary.
     *
     * @param uidMap the probe container's first uid_map line, or empty when unreadable
     */
    static @NonNull Check usernsCheck(@NonNull String uidMap) {
        String first = uidMap.isEmpty() ? "" : uidMap.lines().findFirst().orElse("").trim()
            .replaceAll("\\s+", " ");
        if (first.isEmpty()) {
            return new Check("userns_remap", STATUS_WARN, false,
                "the probe container's /proc/self/uid_map could not be read, so whether"
                    + " container root is host root is UNKNOWN on this host");
        }
        boolean remapped = !IDENTITY_UID_MAP.equals(first);
        return new Check("userns_remap", remapped ? STATUS_PASS : STATUS_WARN, false,
            remapped ? "user namespace remapped: uid_map reads '" + first + "'"
                : "no user-namespace remapping: uid_map reads '" + first
                    + "', so container root IS host root (daemon.json userns-remap)");
    }

    /**
     * The LSM verdict read off a probe workload's own {@code /proc/1/attr/current}.
     *
     * AIDEV-NOTE: ADVISORY, and daystrom is exactly why -- it runs Arch, whose kernel
     * carries {@code capability,landlock,lockdown,yama,bpf} and NO AppArmor or SELinux at
     * all (measured: {@code /sys/module/apparmor/parameters/enabled} reads 'N'). Absence of
     * an LSM is a distribution fact, not a misconfiguration an operator can fix at
     * admission time, and refusing on it would make the reference host inadmissible. The
     * label is still worth reading, because "AppArmor is loaded" and "AppArmor confines
     * THIS container" are different facts and only the second one is evidence: an
     * {@code unconfined} label on a host whose daemon advertises apparmor is precisely the
     * silent gap the Info-based check could never see.
     *
     * @param label the probe container's pid-1 LSM label; empty when no LSM confines it
     */
    static @NonNull Check lsmCheck(@NonNull String label) {
        String confinement = label.trim();
        boolean confined = !confinement.isEmpty() && !confinement.startsWith("unconfined");
        return new Check("lsm", confined ? STATUS_PASS : STATUS_WARN, false,
            confined ? "pid 1 runs under LSM confinement '" + confinement + "'"
                : "no LSM confines the probe container (pid 1 label: '"
                    + (confinement.isEmpty() ? "<none>" : confinement)
                    + "'); AppArmor/SELinux add no layer on this host");
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
    private static void checkNftables(NftRunner nft, List<Check> checks) {
        checks.add(nftablesProbe(nft, "nftables", true));
    }

    /**
     * ONE nft transaction against whatever kernel the runner reaches, named by the caller.
     *
     * AIDEV-NOTE: shared with the Incus battery's {@code kernel_isolation_lane} check
     * deliberately -- that check has to be EVIDENCE that the daemon host's ruleset is
     * readable and writable, and "we could have built a runner" is a claim, not evidence.
     * A probe table is used rather than reading {@code table bridge incus} because that
     * table does not exist on a daemon that has never run a workload, so its absence would
     * fail a perfectly healthy new host.
     *
     * @param required whether a failure of this probe sinks the whole preflight verdict
     */
    static @NonNull Check nftablesProbe(@NonNull NftRunner nft, @NonNull String name,
                                        boolean required) {
        String table = "hohenheim_preflight_" + Long.toHexString(System.nanoTime());
        try {
            NftRunner.Result added = nft.run(List.of("-f", "-"),
                "add table inet " + table + "\n");
            if (!added.ok()) {
                return new Check(name, STATUS_FAIL, required,
                    "nft add table refused: " + added.failureText());
            }
            NftRunner.Result listed = nft.run(List.of("list", "table", "inet", table), null);
            boolean visible = listed.ok() && listed.stdout().contains(table);
            nft.run(List.of("delete", "table", "inet", table), null);
            return new Check(name, visible ? STATUS_PASS : STATUS_FAIL, required,
                visible ? "nft transaction applied and read back from the kernel"
                    : "nft add reported success but the table did not read back: "
                        + listed.failureText());
        } catch (Exception error) {
            return new Check(name, STATUS_FAIL, required,
                "nft probe failed: " + error.getMessage());
        }
    }

    /** The stored key holding the per-check map; every entry carries its own {@code at}. */
    static final String CHECKS_KEY = "checks";

    /** The stored key holding per-FACT provenance: fact name to ISO instant. */
    static final String FACTS_AT_KEY = "facts_at";

    /**
     * The status one named check carried in the host's LAST STORED report, or null when
     * the host has never been probed or that check never ran.
     *
     * AIDEV-NOTE: gates read this instead of re-probing. A gate that ran a live ssh nft
     * transaction on every instance create would make a momentary network blip refuse
     * tenant work, and would put a remote command on the hot path of every placement. The
     * stored status is evidence that the kernel WAS reachable through this exact lane; the
     * gate pairs it with a LIVE read of whether the record still declares that lane, so a
     * removed target or a quarantine closes the gate instantly rather than at next probe.
     */
    public static @Nullable String storedCheckStatus(@NonNull Row server,
                                                     @NonNull String checkName) {
        Object status = storedCheckField(server, checkName, "status");
        return status != null ? String.valueOf(status) : null;
    }

    /**
     * When the stored verdict of one named check was actually PRODUCED.
     *
     * <p>Since {@link #store} merges rather than replaces, a stored verdict can be older
     * than {@code probed_at}: the last run may never have asked that question. The stamp is
     * what makes that difference visible instead of silently reading as current.
     *
     * @return null for a record written before per-check stamps existed
     */
    public static @Nullable Instant storedCheckAt(@NonNull Row server,
                                                  @NonNull String checkName) {
        return parseInstant(storedCheckField(server, checkName, "at"));
    }

    private static @Nullable Object storedCheckField(@NonNull Row server,
                                                     @NonNull String checkName,
                                                     @NonNull String field) {
        if (!(server.get(ServerModel.CAPABILITIES) instanceof Map<?, ?> capabilities)
                || !(capabilities.get(CHECKS_KEY) instanceof Map<?, ?> checks)
                || !(checks.get(checkName) instanceof Map<?, ?> check)) {
            return null;
        }
        return check.get(field);
    }

    /**
     * When one named stored FACT was last measured.
     *
     * @return null for a record written before per-fact stamps existed, or a fact no
     *         stored report ever produced
     */
    public static @Nullable Instant factMeasuredAt(@NonNull Row server,
                                                   @NonNull String factName) {
        if (!(server.get(ServerModel.CAPABILITIES) instanceof Map<?, ?> capabilities)
                || !(capabilities.get(FACTS_AT_KEY) instanceof Map<?, ?> stamps)) {
            return null;
        }
        return parseInstant(stamps.get(factName));
    }

    private static @Nullable Instant parseInstant(@Nullable Object raw) {
        if (raw instanceof Instant instant) {
            return instant;
        }
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(raw));
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    // -- persistence ----------------------------------------------------------

    /**
     * Store one report on the host record; also refreshes the typed health columns.
     *
     * AIDEV-NOTE: public because it is THE funnel -- both batteries persist through it,
     * and a fixture that needs a host to carry a realistic stored verdict must go through
     * it too rather than hand-writing the capabilities shape somewhere else.
     *
     * AIDEV-NOTE: it MERGES, and that is the fix for a diagnostic action that cordoned a
     * healthy host. It used to replace {@code capabilities} wholesale, so a preflight that
     * could not reach the daemon -- a network blip, a paused daemon, a bad ssh moment --
     * erased every fact the previous run had measured, {@code mem_total} included. The host
     * stayed admitted while {@link be.elevenways.hohenheim.server.instance.InstancePlacement}
     * skipped it as UNMEASURED, so running preflight to diagnose a host removed it from the
     * pool. A check that could not run is not a check that failed, and a fact nobody
     * re-measured is not a fact that changed.
     *
     * The fail-closed direction is untouched: {@code preflight_ok} still follows THIS run's
     * required checks, and a check this run DID produce -- including the deliberate
     * {@code unanswered} FAILs the Incus battery writes when its probe instance never
     * answered -- overwrites whatever was stored. Only questions this run never asked keep
     * their previous answer, and every retained answer carries the stamp of when it was
     * produced ({@link #storedCheckAt}, {@link #factMeasuredAt}) so its age is readable
     * rather than inherited from {@code probed_at}.
     */
    public static void store(@NonNull String serverName, @NonNull Report report) {
        Row server = Models.get(ServerModel.class).findByName(serverName);
        if (server == null) {
            return;
        }
        Map<String, Object> capabilities = new LinkedHashMap<>(storedMap(server, null));
        Map<String, Object> checkMap = new LinkedHashMap<>(storedMap(server, CHECKS_KEY));
        Map<String, Object> factsAt = new LinkedHashMap<>(storedMap(server, FACTS_AT_KEY));
        String at = report.at().toString();
        for (Map.Entry<String, Object> fact : report.facts().entrySet()) {
            capabilities.put(fact.getKey(), fact.getValue());
            factsAt.put(fact.getKey(), at);
        }
        for (Check check : report.checks()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", check.status());
            entry.put("required", check.required());
            entry.put("detail", check.detail());
            entry.put("at", at);
            checkMap.put(check.name(), entry);
        }
        capabilities.put(CHECKS_KEY, checkMap);
        capabilities.put(FACTS_AT_KEY, factsAt);
        server.set(ServerModel.CAPABILITIES, capabilities);
        server.set(ServerModel.PROBED_AT, report.at());
        server.set(ServerModel.PREFLIGHT_OK, report.passed());
        server.set(ServerModel.CONTROLLER_VERSION, controllerVersion());
        Models.get(ServerModel.class).save(server);
        // Health goes through THE typed funnel, which also quarantines a host whose key
        // changed. Re-parsing the kind back out of the detail string was a second,
        // divergent classifier over text this class had just composed.
        if (report.daemonFailure() != null) {
            HostProbe.recordFailure(serverName, report.daemonFailure());
        } else if (report.check("daemon") != null) {
            HostProbe.recordSuccess(serverName);
        }
        Blast.slog("hohenheim.host.preflight", Map.of(
            "server", serverName,
            "passed", report.passed(),
            "checks", checkMap.size()));
    }

    /**
     * One stored map off the host's capabilities: the whole block when {@code key} is null,
     * else the named sub-map. Always a copyable {@code Map<String, Object>} view, empty
     * when the record carries nothing there.
     */
    private static @NonNull Map<String, Object> storedMap(@NonNull Row server,
                                                          @Nullable String key) {
        if (!(server.get(ServerModel.CAPABILITIES) instanceof Map<?, ?> capabilities)) {
            return Map.of();
        }
        Map<?, ?> source = capabilities;
        if (key != null) {
            if (!(capabilities.get(key) instanceof Map<?, ?> nested)) {
                return Map.of();
            }
            source = nested;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    /** This controller build's version string; "dev" when running from classes. */
    public static @NonNull String controllerVersion() {
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
