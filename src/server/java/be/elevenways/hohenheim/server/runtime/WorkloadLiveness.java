package be.elevenways.hohenheim.server.runtime;

/**
 * Whether the workload INSIDE a running container is still the thing we started -- the
 * question {@link ContainerState} cannot answer, because a container whose entrypoint
 * outlives its real workload is still {@code RUNNING} to every daemon.
 *
 * AIDEV-NOTE: this is a SECOND axis, deliberately not a fifth ContainerState. The four
 * states are what a daemon says about the CONTAINER and are shared verbatim with
 * ManagedDatabase.LiveStatus; folding "the engine died inside it" into them would have
 * silently re-aimed every {@code state() == RUNNING} check in the tree (site routing,
 * install gating, migration presence) at a different question.
 *
 * AIDEV-NOTE: PRESSURE IS NOT A KILL. On cgroup v2 {@code memory.events} carries both
 * {@code max} (the ceiling was hit and memory was reclaimed -- survivable and normal for
 * a busy workload) and {@code oom_kill} (the kernel actually killed something). Reading
 * {@code max} would report a healthy busy workload as broken. Measured on this codebase's
 * Docker 29.6 / cgroup v2 host: 400 MB of page-cache churn in a 64 MB container produced
 * {@code max 6091, oom_kill 0} and {@code OOMKilled=false}, while one OOM-killed CHILD
 * produced {@code max 39, oom_kill 1}, {@code OOMKilled=true} and {@code Running=true}.
 */
public enum WorkloadLiveness {

    /** The daemon reports the container running and knows of no kill inside it. */
    SERVING,

    /**
     * The container still runs, but the kernel OOM-killed a process in its cgroup: the
     * engine or app is gone while the entrypoint (and therefore the container) lives on.
     *
     * AIDEV-NOTE: this STICKS until the workload is restarted, because the daemon's flag
     * does. That is deliberate -- a container that lost a process to the OOM killer is
     * not trustworthy again just because it kept running -- but it does mean a workload
     * that recovered on its own keeps reporting dead until it is restarted.
     */
    WORKLOAD_DEAD,

    /**
     * The driver cannot answer for this workload, or the container is not running at all.
     * A driver that has no OOM signal REFUSES BY NAME with this rather than claiming
     * {@link #SERVING} -- the {@code exitCode}-on-a-stopped-Incus-workload precedent.
     */
    UNKNOWN
}
