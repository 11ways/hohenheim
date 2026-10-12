package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One driver-neutral resource reading of a workload: what every driver's own encoding decodes into before the stats
 * hub turns consecutive readings into samples.
 *
 * @param at          when it was read (epoch millis)
 * @param cpu         the CPU counters, or null when the runtime gives this workload no CPU reading at all
 * @param cores       the CPUs the reading's share is of (100 each); at least 1
 * @param memoryBytes current working set
 * @param memoryLimit the memory the workload may use, or 0 when the runtime reports none
 * @param rxBytes     cumulative received bytes across every interface
 * @param txBytes     cumulative transmitted bytes
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public record StatsReading(long at, @Nullable CpuCounters cpu, int cores, long memoryBytes, long memoryLimit,
                           long rxBytes, long txBytes) {

    /**
     * Two cumulative CPU counters in one unit: what the workload used ({@code busy}) and what all of its {@code cores}
     * had ({@code total}), so the share between two readings is {@code delta busy / delta total * cores * 100}.
     *
     * AIDEV-NOTE: each driver maps its own counters onto these: Docker's container usage against the host's system
     * usage, an Incus container's cgroup usage against elapsed time times its cores, an Incus VM's guest busy seconds
     * against all of its guest seconds.
     */
    public record CpuCounters(double busy, double total) {
    }
}
