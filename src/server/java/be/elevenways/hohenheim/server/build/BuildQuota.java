package be.elevenways.hohenheim.server.build;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The five quotas a build runs under -- CPU, memory, disk, time and PIDs -- as ONE
 * value, because a build that enforces three of them is not sandboxed.
 *
 * AIDEV-NOTE: this exists BESIDE {@link ResourceLimits} rather than extending it, and
 * the split is the same provenance split ResourceLimits itself documents. ResourceLimits
 * is two cgroup knobs an operator configures per workload; this is the builder's own
 * enforcement contract, and three of its five members are not cgroup knobs at all: time
 * is a controller deadline, disk is a watchdog over the daemon's size accounting, and
 * the PID cap is a TIGHTENING of the ContainerHardening baseline (never a raise -- see
 * {@link #effectivePidsLimit()}). {@link #limits()} projects the two members that ARE
 * cgroup knobs onto the existing type so the container spec keeps one owner for them.
 *
 * AIDEV-NOTE: disk has no cgroup either. Docker's {@code --storage-opt size=} needs
 * overlay2 on xfs with prjquota, which hohenheim does not own and most hosts do not
 * have, so a spec-level disk cap would be a setting that silently does nothing -- the
 * exact security-theater shape this codebase hunts. What is enforced instead is real and
 * observable: the context pushed IN is capped before it is sent, the artifact read OUT is
 * capped during the read, and the writable layer is POLLED off the daemon's own
 * {@code SizeRw} accounting while the build runs, killing the build when it crosses.
 */
public record BuildQuota(double cpus, int memoryMb, long diskBytes, long timeoutMs,
                         int pidsLimit, int logBytes, long artifactBytes) {

    /** Never let a misconfigured setting produce an unbounded build. */
    private static final long MIN_TIMEOUT_MS = 10_000;
    private static final long MIN_DISK_BYTES = 16L * 1024 * 1024;
    private static final int MIN_MEMORY_MB = 128;
    private static final int MIN_PIDS = 16;
    private static final int MIN_LOG_BYTES = 4096;

    /** The host's configured build quota. */
    public static @NonNull BuildQuota fromSettings() {
        Double cpus = HohenheimSettings.VALUES.getValue(HohenheimSettings.Builds.CPU_LIMIT);
        Integer memoryMb = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Builds.MEMORY_LIMIT_MB);
        Integer diskMb = HohenheimSettings.VALUES.getValue(HohenheimSettings.Builds.DISK_LIMIT_MB);
        Integer timeoutSeconds = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Builds.TIMEOUT_SECONDS);
        Integer pids = HohenheimSettings.VALUES.getValue(HohenheimSettings.Builds.PIDS_LIMIT);
        Integer logKb = HohenheimSettings.VALUES.getValue(HohenheimSettings.Builds.LOG_LIMIT_KB);
        Integer artifactMb = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Builds.MAX_ARTIFACT_MB);
        return new BuildQuota(
            cpus != null && cpus > 0 ? cpus : 2.0,
            Math.max(MIN_MEMORY_MB, memoryMb != null ? memoryMb : 2048),
            Math.max(MIN_DISK_BYTES, (diskMb != null ? diskMb : 4096) * 1024L * 1024L),
            Math.max(MIN_TIMEOUT_MS, (timeoutSeconds != null ? timeoutSeconds : 900) * 1000L),
            Math.max(MIN_PIDS, pids != null ? pids : 256),
            Math.max(MIN_LOG_BYTES, (logKb != null ? logKb : 512) * 1024),
            Math.max(MIN_DISK_BYTES, (artifactMb != null ? artifactMb : 1024) * 1024L * 1024L));
    }

    /**
     * The same quota with the wall-clock budget REDUCED by time already consumed (a
     * builder pre-phase); never below the floor, so a spent budget still times out
     * through the sandbox's own recorded path instead of a zero-second race.
     */
    public @NonNull BuildQuota afterElapsed(long elapsedMs) {
        return new BuildQuota(this.cpus, this.memoryMb, this.diskBytes,
            Math.max(MIN_TIMEOUT_MS, this.timeoutMs - Math.max(0, elapsedMs)),
            this.pidsLimit, this.logBytes, this.artifactBytes);
    }

    /** The cgroup half, in the type the container spec already speaks. */
    public @NonNull ResourceLimits limits() {
        return ResourceLimits.of(this.memoryMb, this.cpus);
    }

    /**
     * The PID cap actually stamped on the build container.
     *
     * @return the SMALLER of the build quota and the host-wide container cap -- a build
     *         quota may tighten the hardening baseline, it may never widen it
     */
    public int effectivePidsLimit() {
        return Math.min(this.pidsLimit, ContainerHardening.pidsLimit());
    }

    public int diskLimitMb() {
        return (int) (this.diskBytes / (1024 * 1024));
    }

    public int timeoutSeconds() {
        return (int) (this.timeoutMs / 1000);
    }
}
