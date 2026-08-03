package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.HohenheimSettings;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE isolation policy every container hohenheim creates is stamped with, applied
 * inside {@link DockerClient#createContainer} so no call site can omit it.
 *
 * AIDEV-NOTE: this is a POLICY, not a caller option, and the shape enforces that.
 * The four container authorities (stacks, Docker sites, managed databases, instances)
 * pass a {@link Profile} to createContainer; they cannot pass "none", they cannot
 * hand-add a capability, and they cannot set any of the {@link #ESCAPE_KEYS} -- a spec
 * carrying one is REFUSED, so a later feature cannot quietly reintroduce
 * {@code Privileged}. A fifth authority added later reaches the same funnel or it
 * reaches no daemon at all. ResourceLimits is the caller-supplied half (cgroup caps
 * the operator configures per workload); this is the half the operator does not get
 * to weaken.
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
     * or a file it does not already own. The floor, and what a workload kind that
     * declares nothing gets.
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
     * Docker default: NET_RAW (ARP/DNS spoofing on the shared bridge every site and
     * database sits on), SETPCAP, SETFCAP, MKNOD, SYS_CHROOT, AUDIT_WRITE, KILL and
     * NET_BIND_SERVICE are all gone.
     */
    public static final Profile SERVICE = new Profile("service",
        List.of("CHOWN", "DAC_OVERRIDE", "FOWNER", "SETGID", "SETUID"));

    /** Fallback pids cap when the setting is unreadable; also the setting's default. */
    public static final int DEFAULT_PIDS_LIMIT = 512;

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
     */
    public static final List<String> ESCAPE_KEYS = List.of(
        "Privileged", "CapAdd", "CapDrop", "SecurityOpt", "PidsLimit", "UsernsMode",
        "Devices", "DeviceCgroupRules", "DeviceRequests", "CgroupParent", "Sysctls",
        "Binds", "ReadonlyPaths", "MaskedPaths");

    /** HostConfig namespace-sharing keys whose "host" value defeats the container boundary. */
    private static final List<String> NAMESPACE_KEYS = List.of(
        "PidMode", "IpcMode", "UTSMode", "NetworkMode", "CgroupnsMode");

    private ContainerHardening() {}

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
        hostConfig.put("PidsLimit", (long) pidsLimit());

        containerSpec.put("HostConfig", hostConfig);
    }

    /** @return the configured per-container process cap, never below 1 */
    public static int pidsLimit() {
        Integer configured = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Security.CONTAINER_PIDS_LIMIT);
        return configured != null && configured > 0 ? configured : DEFAULT_PIDS_LIMIT;
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
            if ("host".equals(hostConfig.get(key))) {
                throw new IllegalArgumentException("REFUSED to create container: HostConfig."
                    + key + " = \"host\" shares a host namespace with the container, which is a"
                    + " container escape by definition.");
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
