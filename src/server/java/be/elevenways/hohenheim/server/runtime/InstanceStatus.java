package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Typed live status of one instance workload -- never a boolean, never a nullable map.
 *
 * @param state         the four-state daemon answer (absent != unreachable, always)
 * @param publishedPort the loopback host port serving {@code publishPort} when RUNNING
 *                      and published, else null
 */
public record InstanceStatus(@NonNull ContainerState state, @Nullable Integer publishedPort) {

    public boolean running() {
        return this.state == ContainerState.RUNNING;
    }
}
