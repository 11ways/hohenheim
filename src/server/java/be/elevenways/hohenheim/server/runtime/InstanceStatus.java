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
 */
public record InstanceStatus(@NonNull ContainerState state, @Nullable Integer publishedPort,
                             @Nullable String publishedBind) {

    public InstanceStatus(@NonNull ContainerState state, @Nullable Integer publishedPort) {
        this(state, publishedPort, null);
    }

    public boolean running() {
        return this.state == ContainerState.RUNNING;
    }
}
