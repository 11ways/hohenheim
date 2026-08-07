package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A DECLARED in-workload health probe the RUNTIME runs and reports on, so "is this
 * workload healthy" is answerable from a status read instead of from a control-plane
 * poll. Single-workload by construction: it says nothing about any other instance.
 *
 * A driver that cannot run one refuses BY NAME (the cloud-init shape) rather than
 * creating a workload whose declared health nobody ever evaluates -- a gate that always
 * reports healthy is worse than no gate.
 *
 * @param command shell command run inside the workload; a zero exit is healthy
 */
public record HealthCheck(@NonNull String command,
                          int intervalSeconds,
                          int timeoutSeconds,
                          int retries,
                          int startPeriodSeconds) {

    /** Nanoseconds, which is the unit both the Docker API and Incus express these in. */
    public long intervalNanos() {
        return this.intervalSeconds * 1_000_000_000L;
    }

    public long timeoutNanos() {
        return this.timeoutSeconds * 1_000_000_000L;
    }

    public long startPeriodNanos() {
        return this.startPeriodSeconds * 1_000_000_000L;
    }
}
