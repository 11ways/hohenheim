package be.elevenways.hohenheim.server.docker;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * Optional, OPERATOR-CONFIGURED cgroup caps: memory in MiB and CPUs as a decimal
 * (1.5 = one and a half cores). Null or non-positive members mean "unlimited".
 *
 * AIDEV-NOTE: this type is two cgroup knobs and NOTHING about isolation -- the name once
 * read as though a container carrying a ResourceLimits was confined, and it never was.
 * Container isolation (capability dropping, no-new-privileges, the per-container process
 * cap, the privilege-escape refusals) is {@link ContainerHardening}, applied inside
 * DockerClient.createContainer where no caller can omit it. The split is deliberate and
 * is about PROVENANCE: what is here is per-workload configuration an operator chooses,
 * what is there is policy an operator does not get to weaken. Do not merge them.
 *
 * AIDEV-NOTE: THE recorded verdict on the missing third dimension, because this is where
 * the next reader will come looking for it. The instance-tier gate clause says runtime
 * limits cover "pids, logs and ephemeral disk"; pids and logs are enforced in
 * {@link ContainerHardening#applyTo}, and EPHEMERAL (root) DISK is STRUCK for the Docker
 * tier rather than pending. Three reasons, each sufficient: the tier is UNDECLARABLE
 * ({@code DockerContainerKind.SETTINGS_SCHEMA} has no root-disk field, so there is nothing
 * to charge or stamp); {@code --storage-opt size=} is accepted only on overlay2 backed by
 * XFS with pquota, a HOST filesystem property hohenheim neither owns nor can verify
 * through the API, so the identical declaration would enforce on some hosts and silently
 * do nothing on most; and the honest branch is already taken everywhere else -- the disk
 * view reports null as UNMEASURED instead of a fabricated zero, and
 * {@code RootDiskSizeSupport} is a DECLARED capability the Incus runtime implements and
 * the Docker runtime does not, so a spec carrying {@code rootDiskGb} is refused by name.
 * Do not add a memoryMb-style {@code diskGb} member here to make the sentence true.
 */
public record ResourceLimits(@Nullable Integer memoryMb, @Nullable Double cpus) {

    private static final ResourceLimits NONE = new ResourceLimits(null, null);

    public static ResourceLimits none() {
        return NONE;
    }

    public static ResourceLimits of(@Nullable Integer memoryMb, @Nullable Double cpus) {
        return new ResourceLimits(memoryMb, cpus);
    }

    /** Read {@code memory_limit_mb} / {@code cpu_limit} out of a site-settings map. */
    public static ResourceLimits fromSettings(Map<String, Object> settings) {
        return new ResourceLimits(
            asInteger(settings.get("memory_limit_mb")),
            asDouble(settings.get("cpu_limit")));
    }

    /**
     * The instance-tier reading: an absent or non-positive {@code memory_limit_mb} falls
     * back to the KIND's declared footprint instead of meaning "unlimited".
     *
     * AIDEV-NOTE: this is the half that makes the host-capacity ledger honest rather than
     * advisory. InstanceCapacity books exactly {@link #memoryMb()} as read here, and this
     * is the value the driver stamps as the cgroup / VM memory cap -- so a workload
     * physically cannot exceed its own booking. Never charge through this and cap through
     * the plain {@link #fromSettings(Map)}: that is the zero-denominator gate all over
     * again, just with a nicer number in it. CPU is deliberately NOT defaulted -- it is
     * timeshared, nothing is booked against it, and a surprise CPU cap would throttle
     * workloads for a budget that does not exist.
     */
    public static ResourceLimits fromSettings(Map<String, Object> settings,
                                              int defaultMemoryMb) {
        Integer declared = asInteger(settings.get("memory_limit_mb"));
        return new ResourceLimits(
            declared != null && declared > 0 ? declared : defaultMemoryMb,
            asDouble(settings.get("cpu_limit")));
    }

    /** The memory this configuration is booked and capped at (MB). */
    public int bookedMemoryMb() {
        return this.memoryMb != null && this.memoryMb > 0 ? this.memoryMb : 0;
    }

    /** Stamp Docker HostConfig entries (Memory bytes, NanoCpus) for the active caps. */
    public void applyTo(Map<String, Object> hostConfig) {
        if (memoryMb != null && memoryMb > 0) {
            hostConfig.put("Memory", memoryMb * 1024L * 1024L);
        }
        if (cpus != null && cpus > 0) {
            hostConfig.put("NanoCpus", (long) (cpus * 1_000_000_000L));
        }
    }

    private static @Nullable Integer asInteger(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static @Nullable Double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
