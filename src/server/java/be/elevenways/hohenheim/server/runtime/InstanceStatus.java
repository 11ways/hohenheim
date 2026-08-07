package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Typed live status of one instance workload -- never a boolean, never a nullable map.
 *
 * @param state          the four-state daemon answer (absent != unreachable, always)
 * @param publishedPorts every host binding the daemon reports for this workload, in the
 *                       daemon's own order; empty whenever nothing is published or there
 *                       is no workload to publish
 * @param liveness       whether the workload inside a RUNNING container is still alive;
 *                       {@link WorkloadLiveness#UNKNOWN} whenever the driver cannot say
 * @param health         the verdict of the spec's DECLARED health check as the runtime
 *                       evaluates it; {@link HealthState#NONE} when none was declared,
 *                       which is NOT the same answer as healthy
 */
public record InstanceStatus(@NonNull ContainerState state,
                             @NonNull List<PublishedPort> publishedPorts,
                             @NonNull WorkloadLiveness liveness,
                             @NonNull HealthState health) {

    /**
     * The four honest answers to "did the declared health check pass": no check exists,
     * it is still inside its start period, it passed, it failed. NONE never counts as
     * healthy -- a gate that reads an undeclared check as passing is a gate that always
     * passes.
     */
    public enum HealthState {
        NONE, STARTING, HEALTHY, UNHEALTHY
    }

    /** The pre-health shape: a driver that evaluates no declared check. */
    public InstanceStatus(@NonNull ContainerState state,
                          @NonNull List<PublishedPort> publishedPorts,
                          @NonNull WorkloadLiveness liveness) {
        this(state, publishedPorts, liveness, HealthState.NONE);
    }

    /**
     * One host binding as the DAEMON reports it: which container port it serves, and the
     * bind address the exposure verification asserts against (never the spec we sent).
     *
     * @param bind {@code 127.0.0.1}, {@code 0.0.0.0} or {@code ::}, or null when the
     *             daemon named none
     */
    public record PublishedPort(int containerPort, @NonNull String protocol,
                                int hostPort, @Nullable String bind) {
    }

    /**
     * AIDEV-NOTE: the convenience constructors DERIVE liveness instead of defaulting to
     * SERVING, so a caller that never heard of the question cannot assert an answer to
     * it. RUNNING means "running, with nothing known against it"; every other state has
     * no workload to be alive, so it is UNKNOWN. Both real drivers pass liveness
     * explicitly; these exist for fakes and for statuses we synthesize ourselves.
     */
    public InstanceStatus(@NonNull ContainerState state, @Nullable Integer publishedPort) {
        this(state, publishedPort, null);
    }

    public InstanceStatus(@NonNull ContainerState state, @Nullable Integer publishedPort,
                          @Nullable String publishedBind) {
        this(state, publishedPort, publishedBind,
            state == ContainerState.RUNNING ? WorkloadLiveness.SERVING : WorkloadLiveness.UNKNOWN);
    }

    /**
     * The single-binding shape, for synthesized statuses and fakes. The container port is
     * unknown here (0), so a status built this way answers {@link #publishedPort()} but
     * never {@link #publishedFor}: only a driver reading the daemon knows which container
     * port a binding serves, and guessing one would let the publication verification pass
     * on a binding nobody identified.
     */
    public InstanceStatus(@NonNull ContainerState state, @Nullable Integer publishedPort,
                          @Nullable String publishedBind, @NonNull WorkloadLiveness liveness) {
        this(state, publishedPort == null ? List.<PublishedPort>of()
            : List.of(new PublishedPort(0, PortPublication.TCP, publishedPort, publishedBind)),
            liveness, HealthState.NONE);
    }

    /** The FIRST published host port, or null -- the single-publication reading. */
    public @Nullable Integer publishedPort() {
        return this.publishedPorts.isEmpty() ? null : this.publishedPorts.get(0).hostPort();
    }

    /** The bind address of {@link #publishedPort()}, or null. */
    public @Nullable String publishedBind() {
        return this.publishedPorts.isEmpty() ? null : this.publishedPorts.get(0).bind();
    }

    /** The daemon's binding for one declared (container port, protocol), or null. */
    public @Nullable PublishedPort publishedFor(int containerPort, @NonNull String protocol) {
        for (PublishedPort published : this.publishedPorts) {
            if (published.containerPort() == containerPort
                    && published.protocol().equals(protocol)) {
                return published;
            }
        }
        return null;
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
