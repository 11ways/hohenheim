package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Typed live status of one instance workload -- never a boolean, never a nullable map.
 *
 * @param state         the four-state daemon answer (absent != unreachable, always)
 * @param publishedPort the host port serving the spec's publication when RUNNING and
 *                      published, else null
 * @param publishedBind the daemon's OWN report of the bind address serving that port
 *                      ({@code 127.0.0.1}, {@code 0.0.0.0} or {@code ::}); the fact the
 *                      exposure verification asserts against, never the spec we sent
 * @param liveness      whether the workload inside a RUNNING container is still alive;
 *                      {@link WorkloadLiveness#UNKNOWN} whenever the driver cannot say
 */
public record InstanceStatus(@NonNull ContainerState state, @Nullable Integer publishedPort,
                             @Nullable String publishedBind,
                             @NonNull WorkloadLiveness liveness) {

    /**
     * AIDEV-NOTE: the convenience constructors DERIVE liveness instead of defaulting to
     * SERVING, so a caller that never heard of the question cannot assert an answer to
     * it. RUNNING means "running, with nothing known against it"; every other state has
     * no workload to be alive, so it is UNKNOWN. Both real drivers pass liveness
     * explicitly; these two exist for fakes and for statuses we synthesize ourselves.
     */
    public InstanceStatus(@NonNull ContainerState state, @Nullable Integer publishedPort) {
        this(state, publishedPort, null);
    }

    public InstanceStatus(@NonNull ContainerState state, @Nullable Integer publishedPort,
                          @Nullable String publishedBind) {
        this(state, publishedPort, publishedBind,
            state == ContainerState.RUNNING ? WorkloadLiveness.SERVING : WorkloadLiveness.UNKNOWN);
    }

    public boolean running() {
        return this.state == ContainerState.RUNNING;
    }

    /**
     * The container runs but its workload was killed inside it -- the state an
     * OOM-killed engine leaves behind, which {@link #running()} alone reports as healthy.
     */
    public boolean workloadDead() {
        return this.running() && this.liveness == WorkloadLiveness.WORKLOAD_DEAD;
    }
}
