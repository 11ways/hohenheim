package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.util.BlastString;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE isolation policy every container hohenheim creates is stamped with, applied
 * inside {@link DockerClient#createContainer} so no call site can omit it.
 *
 * AIDEV-NOTE: this is a POLICY, not a caller option, and the shape enforces that. Every
 * authority that creates a container passes a {@link Profile} to createContainer; they
 * cannot pass "none", they cannot hand-add a capability, and they cannot set any of the
 * {@link #ESCAPE_KEYS} -- a spec carrying one is REFUSED, so a later feature cannot
 * quietly reintroduce {@code Privileged}. A NEW authority added later reaches the same
 * funnel or it reaches no daemon at all.
 *
 * AIDEV-NOTE: this note used to enumerate "the four container authorities (stacks, Docker
 * sites, managed databases, instances)", and after three lowerings that count is wrong in a
 * way a future reader would act on. Stacks, Docker sites and managed databases all OWN
 * instances now, so the instance runtime is the single container authority for workloads;
 * what remains beside it is {@code BuildSandbox} (the build lane, which also passes the
 * tighter pids cap) and {@code HostPreflight} (the throwaway probe container). Place a new
 * authority against THAT map, not against a count. ResourceLimits is the caller-supplied half (cgroup caps
 * the operator configures per workload); this is the half the operator does not get
 * to weaken.
 *
 * AIDEV-NOTE: a {@link Profile} is an IMAGE-SHAPE declaration, NOT a trust declaration.
 * All four authorities declare {@link #SERVICE} today, tenant-authored instances
 * included, because ordinary published images are all built the same way (chown the data
 * directory as root, then drop to a service user). Nobody decided tenants are trusted.
 * The tenant-vs-operator boundary lives in the parts of this class that no profile can
 * move -- drop-ALL as the base, no-new-privileges, the pids cap and the structural
 * refusals -- plus the per-workload network policy
 * ({@code be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy}), which as of the
 * 2026-08-06 waves covers every Docker tier: instances, stacks, Docker site releases and
 * managed databases each get their own network with the tenant-range denies applied and
 * read-back-verified. Host-process sites live in the host's own netns and cannot be given a
 * per-workload NETWORK, which is why they carry a uid-keyed nft policy instead
 * ({@code ProcessNetworkPolicy}); as of 2026-08-07 they are no longer a hardening hole
 * either -- {@code SystemUsers.executionBuilder} gives every spawn the same floor this
 * class stamps on a container (no-new-privileges plus a process cap, as
 * {@code setpriv --no-new-privs} and RLIMIT_NPROC/TasksMax), and a declared memory limit is
 * a real cgroup scope ({@code ProcessConfinement}) or a refusal.
 */
public final class ContainerHardening {

    /**
     * A workload kind's declared capability needs on top of the drop-ALL baseline.
     * Everything else in the baseline (no-new-privileges, the pids cap, the escape
     * refusals) is identical for every profile and is deliberately not parameterized.
     */
    public record Profile(@NonNull String name, @NonNull List<String> capabilities) {}

    /**
     * Drop ALL, add nothing back: a container that never needs to touch a uid, a gid
     * or a file it does not already own.
     *
     * AIDEV-NOTE: as of 2026-08-03 NO production workload kind declares this -- all four
     * authorities declare {@link #SERVICE}, because every one of them runs images of the
     * chown-then-drop shape (the instance tier moved here after the product decision that
     * generic tenant images must work out of the box). It is kept, and it is not theater:
     * it is the base every profile is built from (the drop-ALL half of {@link #applyTo}
     * is unconditional), it is what the hardening tests pin the alpine-shaped containers
     * to, and a kind whose image genuinely needs nothing declares it without a new
     * mechanism. If it is still undeclared by production code when a third profile is
     * proposed, that is the moment to argue it away -- not before.
     */
    public static final Profile STRICT = new Profile("strict", List.of());

    /**
     * The "root entrypoint chowns its data directory then drops to a service user"
     * image shape -- the overwhelming majority of published server images.
     *
     * AIDEV-NOTE: this set is measured, not copied. Against a real daemon,
     * postgres:17-alpine, mysql:8.0, mongo:7, redis:7-alpine and nginx:alpine all fail
     * to start under STRICT ("chown ... Operation not permitted", "failed switching to
     * 'postgres'", "setpriv: setresuid failed") and all start under this set. Narrower
     * variants are a TRAP and were measured too: dropping DAC_OVERRIDE and/or FOWNER
     * lets postgres come up on a FIRST boot and report "accepting connections", and
     * then the container dies on the next restart -- a silent-success shape, do not
     * "tighten" this without re-running the restart case. It is still far below the
     * Docker default: NET_RAW (ARP/DNS spoofing on the bridge the container sits on;
     * every Docker tier now has its own network, and note that dropping NET_RAW never
     * blocked an ordinary connect() to a neighbour, which is what the network policy is
     * for -- a service that genuinely needs it declares it, see {@link #DECLARABLE}),
     * SETPCAP, SETFCAP, MKNOD,
     * SYS_CHROOT, AUDIT_WRITE, KILL and NET_BIND_SERVICE are all gone.
     */
    public static final Profile SERVICE = new Profile("service",
        List.of("CHOWN", "DAC_OVERRIDE", "FOWNER", "SETGID", "SETUID"));

    /**
     * THE closed set of capabilities an OPERATOR-authored workload may declare on top of
     * {@link #SERVICE}, each with the reason it is on the list.
     *
     * AIDEV-NOTE: an ALLOW-list, never a deny-list, and never open-ended. The input is
     * compose-shaped content, and "CapAdd takes whatever string you type" is a privilege
     * escalation surface, not a feature -- {@code SYS_ADMIN}, {@code SYS_PTRACE},
     * {@code DAC_READ_SEARCH} and {@code SYS_MODULE} are container escapes in practice, and
     * a deny-list of today's known-bad names would silently admit tomorrow's. Declaring one
     * of these is an IMAGE-SHAPE statement exactly like declaring a {@link Profile} is (see
     * the class note): it says what the published image needs to start, and it does NOT
     * move the tenant boundary, which is the structural refusals, the pids cap and the
     * per-workload network policy -- none of which any declaration can touch.
     *
     * AIDEV-NOTE: what is deliberately NOT here, with the reason, because these are the
     * ones an operator will ask for. {@code NET_ADMIN} would let a service reconfigure its
     * own interface address, and the workload network policy's accept rules are keyed on
     * the workload SUBNET -- a service that can give itself an address is a service that
     * can leave its own policy's scope. {@code SYS_NICE} grants SCHED_FIFO, which is not
     * bounded by the cpu cgroup quota, so one spinning real-time thread is a host DoS.
     * {@code MKNOD} is only harmless while the device cgroup stays restrictive, and this
     * class refuses {@code DeviceCgroupRules} rather than verifying it. {@code SETPCAP} and
     * {@code SETFCAP} exist to move capabilities around, which is the thing being bounded.
     * {@code NET_BIND_SERVICE} is not refused for danger but for pointlessness: Docker sets
     * {@code net.ipv4.ip_unprivileged_port_start=0} in every container, so it grants
     * nothing and a knob nobody can observe is theater.
     */
    public static final Map<String, String> DECLARABLE = orderedMap(
        "NET_RAW", "raw and packet sockets: ping, traceroute and DHCP clients in an"
            + " entrypoint. It is in Docker's own default set, and hohenheim's tenant-range"
            + " denies are DESTINATION-keyed, so a spoofed source address does not reach"
            + " anything a well-behaved one could not.",
        "IPC_LOCK", "mlock: database engines and secret-handling processes pin pages so"
            + " they never reach swap. The memory cgroup cap still bounds how much.",
        "SYS_CHROOT", "chroot: privilege-separating daemons (sshd, some entrypoints) call"
            + " it at startup. Without CAP_SYS_ADMIN there is no mount to escape through,"
            + " and it is in Docker's own default set.",
        "KILL", "signal a process owned by another uid: multi-uid supervisors (s6-overlay,"
            + " supervisord) need it to stop their children. Bounded by the pid namespace.",
        "AUDIT_WRITE", "write audit records: login/su in several base images refuse to run"
            + " without it. In Docker's own default set.");

    /**
     * Refusal reasons for capabilities an operator is likely to reach for, so the error
     * teaches instead of only saying no; anything else falls back to naming the allow-list.
     */
    private static final Map<String, String> REFUSAL_REASONS = Map.of(
        "SYS_ADMIN", "it is the container escape -- mount, pivot_root and the cgroup"
            + " filesystem are all one call away",
        "SYS_PTRACE", "it reads and writes the memory of any process in the namespace,"
            + " including one that still holds credentials",
        "SYS_MODULE", "it loads kernel modules, which is host root by definition",
        "DAC_READ_SEARCH", "it grants open_by_handle_at, which reaches files outside the"
            + " container's own filesystem",
        "NET_ADMIN", "it lets the workload change its own address, and the network policy"
            + " that isolates it is keyed on the subnet that address belongs to",
        "SYS_NICE", "SCHED_FIFO is not bounded by the cpu cgroup quota, so it is a host"
            + " denial of service",
        "MKNOD", "it is only harmless while the device cgroup stays restrictive, and this"
            + " policy refuses DeviceCgroupRules rather than verifying it",
        "SETFCAP", "moving capabilities around is exactly what this policy bounds",
        "SETPCAP", "moving capabilities around is exactly what this policy bounds",
        "NET_BIND_SERVICE", "it grants nothing here: Docker sets"
            + " net.ipv4.ip_unprivileged_port_start=0 in every container");

    /** Fallback pids cap when the setting is unreadable; also the setting's default. */
    public static final int DEFAULT_PIDS_LIMIT = 512;

    /** Fallback log rotation size in MB when the setting is unreadable; also its default. */
    public static final int DEFAULT_LOG_MAX_SIZE_MB = 10;

    /** Fallback rotated-log file count when the setting is unreadable; also its default. */
    public static final int DEFAULT_LOG_MAX_FILES = 3;

    /**
     * HostConfig keys a caller may never set: each one is either a documented container
     * escape or a way to opt back out of this policy.
     *
     * AIDEV-NOTE: {@code CapAdd}/{@code CapDrop}/{@code SecurityOpt}/{@code PidsLimit}
     * are in here because the profile owns them -- a caller that wants a capability
     * declares a profile, it does not append one. {@code Binds} and bind-type
     * {@code Mounts} are refused separately in {@link #refuseEscapes} because a bind of
     * /var/run/docker.sock is root on the host; no tier binds host paths today and none
     * should start without revisiting this class. {@code UsernsMode} is refused rather
     * than set: per-container userns REMAPPING does not exist in the Docker API (it is
     * daemon-level {@code userns-remap} in daemon.json, which hohenheim does not own),
     * and the only thing the field can do here is "host", i.e. opt a container OUT of
     * remapping on a daemon that has it configured.
     *
     * AIDEV-NOTE: {@code LogConfig} joined this list with the log cap itself. It is not a
     * privilege escape -- it is here for the OTHER reason the list exists: the policy owns
     * the key, so no caller can hand back the unbounded default (or a driver whose output
     * the console lane cannot read) by setting it.
     */
    public static final List<String> ESCAPE_KEYS = List.of(
        "Privileged", "CapAdd", "CapDrop", "SecurityOpt", "PidsLimit", "UsernsMode",
        "Devices", "DeviceCgroupRules", "DeviceRequests", "CgroupParent", "Sysctls",
        "Binds", "ReadonlyPaths", "MaskedPaths", "LogConfig");

    /**
     * HostConfig namespace-sharing keys: "host" defeats the container boundary outright,
     * and {@code container:<id>} joins ANOTHER container's namespace.
     *
     * AIDEV-NOTE: the {@code container:} half is the network isolation's opt-out. A
     * workload created with {@code NetworkMode: "container:<other>"} runs in the other
     * container's network namespace -- it gets that container's addresses, is not on its
     * own private network, and the per-workload policy chains (which are scoped to a
     * SUBNET) match nothing for it. The private network would then be defeated by a
     * string, which is why this refusal lives here at the funnel and not in the instance
     * driver.
     */
    private static final List<String> NAMESPACE_KEYS = List.of(
        "PidMode", "IpcMode", "UTSMode", "NetworkMode", "CgroupnsMode");

    /** Prefix of a namespace value that joins another container's namespace. */
    private static final String JOIN_CONTAINER = "container:";

    private ContainerHardening() {}

    /** Insertion-ordered map literal; {@link Map#of} would scramble the declared order. */
    private static Map<String, String> orderedMap(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put(pairs[index], pairs[index + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Build the profile a workload actually runs with from a BASE profile plus the
     * capabilities its author declared, refusing anything outside {@link #DECLARABLE}.
     *
     * <p>AIDEV-NOTE: this is the whole per-service capability mechanism, and it lives here
     * rather than in the deployer on purpose -- {@code CapAdd} is an {@link #ESCAPE_KEYS}
     * entry, so a caller has never been able to append one, and the only way to get a
     * capability is to come through a {@link Profile}. Making the declaration a profile
     * FACTORY keeps that true: the validation cannot be skipped by constructing a Profile
     * directly, because a Profile carrying an undeclarable capability is exactly what this
     * refuses to produce, and every four-authority call site already passes a Profile.
     *
     * @param base       the tier's image-shape profile, normally {@link #SERVICE}
     * @param name       what to call the derived profile (the workload, for diagnostics)
     * @param declared   capability names as authored; case and a {@code CAP_} prefix are
     *                   normalized, blanks are dropped, duplicates collapse
     * @return the base profile itself when nothing extra is declared
     * @throws IllegalArgumentException naming the capability and the reason it is refused
     */
    public static @NonNull Profile declaring(@NonNull Profile base, @NonNull String name,
                                             @NonNull List<String> declared) {
        List<String> capabilities = new ArrayList<>(base.capabilities());
        for (String raw : declared) {
            String capability = normalizeCapability(raw);
            if (capability.isEmpty()) {
                continue;
            }
            if (!DECLARABLE.containsKey(capability) && !capabilities.contains(capability)) {
                throw new IllegalArgumentException("REFUSED capability '" + capability
                    + "' declared by '" + name + "': "
                    + REFUSAL_REASONS.getOrDefault(capability,
                        "it is not on the declarable allow-list")
                    + ". Declarable capabilities are " + DECLARABLE.keySet()
                    + "; everything else stays dropped.");
            }
            if (!capabilities.contains(capability)) {
                capabilities.add(capability);
            }
        }
        return capabilities.size() == base.capabilities().size()
            ? base : new Profile(name, List.copyOf(capabilities));
    }

    /** {@code cap_net_raw}, {@code NET_RAW} and {@code CAP_NET_RAW} are the same thing. */
    public static @NonNull String normalizeCapability(@NonNull String raw) {
        String capability = BlastString.upper(raw.trim());
        return capability.startsWith("CAP_") ? capability.substring(4) : capability;
    }

    /**
     * Stamp the baseline onto a container spec, refusing one that already carries an
     * escape.
     *
     * @param containerSpec the /containers/create body; its HostConfig is created when absent
     * @param profile       the workload kind's declared capability needs
     * @throws IllegalArgumentException when the spec sets a key this policy owns or a
     *                                  host namespace -- loud, never silently overwritten
     */
    public static void applyTo(@NonNull Map<String, Object> containerSpec, @NonNull Profile profile) {
        applyTo(containerSpec, profile, null);
    }

    /**
     * Stamp the baseline, optionally with a TIGHTER process cap than the host default.
     *
     * AIDEV-NOTE: {@code tighterPidsLimit} can only ever LOWER the cap -- the value used
     * is the minimum of it and {@link #pidsLimit()}. That direction is the whole reason
     * this parameter is allowed to exist next to {@code PidsLimit} being an
     * {@link #ESCAPE_KEYS} entry: a caller still cannot raise or remove the cap, and a
     * workload class that wants a smaller one (the build sandbox: a Dockerfile has no
     * business forking 512 processes) does not need a second funnel to get it.
     *
     * @param tighterPidsLimit the caller's own cap, or null for the host default
     */
    public static void applyTo(@NonNull Map<String, Object> containerSpec,
                               @NonNull Profile profile,
                               @Nullable Integer tighterPidsLimit) {
        Object existing = containerSpec.get("HostConfig");
        Map<String, Object> hostConfig;
        if (existing instanceof Map<?, ?> map) {
            hostConfig = new LinkedHashMap<>();
            map.forEach((key, value) -> hostConfig.put(String.valueOf(key), value));
        } else {
            hostConfig = new LinkedHashMap<>();
        }

        refuseEscapes(hostConfig);

        hostConfig.put("CapDrop", List.of("ALL"));
        if (!profile.capabilities().isEmpty()) {
            hostConfig.put("CapAdd", List.copyOf(profile.capabilities()));
        }
        // Blocks every setuid/file-capability escalation lane inside the container, which
        // is what makes dropping SETPCAP/SETFCAP stick rather than being re-earned.
        hostConfig.put("SecurityOpt", List.of("no-new-privileges"));
        int pids = pidsLimit();
        if (tighterPidsLimit != null && tighterPidsLimit > 0) {
            pids = Math.min(pids, tighterPidsLimit);
        }
        hostConfig.put("PidsLimit", (long) pids);
        hostConfig.put("LogConfig", logConfig());

        containerSpec.put("HostConfig", hostConfig);
    }

    /** @return the configured per-container process cap, never below 1 */
    public static int pidsLimit() {
        Integer configured = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Security.CONTAINER_PIDS_LIMIT);
        return configured != null && configured > 0 ? configured : DEFAULT_PIDS_LIMIT;
    }

    /**
     * The bounded logging every managed container is stamped with.
     *
     * AIDEV-NOTE: an unrotated log is a TENANT-DRIVEN host-availability failure, which is
     * why the cap lives in this funnel and not at a call site: a workload that prints in a
     * loop fills the host's disk, and the daemon's own default for the json-file driver is
     * one file with no maximum size at all. Every managed container therefore gets
     * {@code max-size} x {@code max-file} as a hard ceiling on what it can occupy.
     *
     * AIDEV-NOTE: the DRIVER is stamped too, not just its options, and that is deliberate
     * rather than incidental. Only {@code json-file} and {@code local} answer
     * {@code GET /containers/{id}/logs}, which is the lane the console tab and the
     * readiness matcher both read through -- a daemon configured with any other default
     * driver would silently take the console away, and a caller cannot re-point it because
     * {@code LogConfig} is an {@link #ESCAPE_KEYS} entry. Naming the driver we depend on is
     * the honest half of depending on it.
     */
    private static @NonNull Map<String, Object> logConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("max-size", logMaxSizeMb() + "m");
        config.put("max-file", String.valueOf(logMaxFiles()));
        Map<String, Object> logConfig = new LinkedHashMap<>();
        logConfig.put("Type", "json-file");
        logConfig.put("Config", config);
        return logConfig;
    }

    /** @return the configured rotation size in MB for one container log file, never below 1 */
    public static int logMaxSizeMb() {
        Integer configured = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Security.CONTAINER_LOG_MAX_SIZE_MB);
        return configured != null && configured > 0 ? configured : DEFAULT_LOG_MAX_SIZE_MB;
    }

    /** @return how many rotated log files one container keeps, never below 1 */
    public static int logMaxFiles() {
        Integer configured = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Security.CONTAINER_LOG_MAX_FILES);
        return configured != null && configured > 0 ? configured : DEFAULT_LOG_MAX_FILES;
    }

    private static void refuseEscapes(Map<String, Object> hostConfig) {
        for (String key : ESCAPE_KEYS) {
            if (hostConfig.containsKey(key)) {
                throw new IllegalArgumentException("REFUSED to create container: HostConfig."
                    + key + " is owned by ContainerHardening and may not be set by a caller."
                    + " A workload that needs a capability declares a ContainerHardening.Profile;"
                    + " nothing declares a privilege escape.");
            }
        }
        for (String key : NAMESPACE_KEYS) {
            Object value = hostConfig.get(key);
            if ("host".equals(value)) {
                throw new IllegalArgumentException("REFUSED to create container: HostConfig."
                    + key + " = \"host\" shares a host namespace with the container, which is a"
                    + " container escape by definition.");
            }
            if (value instanceof String text && text.startsWith(JOIN_CONTAINER)) {
                throw new IllegalArgumentException("REFUSED to create container: HostConfig."
                    + key + " = \"" + text + "\" joins another container's namespace, which"
                    + " inherits that container's isolation instead of having any of its own"
                    + " (a workload sharing another workload's network namespace is not on its"
                    + " own private network and no per-workload policy applies to it).");
            }
        }
        if (hostConfig.get("Mounts") instanceof List<?> mounts) {
            for (Object mount : mounts) {
                if (mount instanceof Map<?, ?> entry && "bind".equals(entry.get("Type"))) {
                    throw new IllegalArgumentException("REFUSED to create container: a bind mount"
                        + " of host path '" + entry.get("Source") + "' is not an isolation boundary"
                        + " (a bind of the Docker socket is root on the host). Use a named volume"
                        + " or a tmpfs.");
                }
            }
        }
    }
}
