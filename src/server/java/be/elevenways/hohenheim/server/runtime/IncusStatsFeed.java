package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.text.PrometheusText;
import be.elevenways.protoblast.common.time.Now;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An Incus workload's live readings, polled while somebody watches: memory, network and (for a container) CPU from
 * {@code /1.0/instances/<name>/state}, and a virtual machine's CPU from {@code /1.0/metrics}.
 *
 * AIDEV-NOTE: a VM's state reports {@code cpu.usage: -1} (measured on Incus 7.3); its CPU exists only as
 * {@code incus_cpu_seconds_total} in the Prometheus metrics, which the daemon refreshes about every 8 seconds
 * (measured on nightstrom). With the guest agent those are the guest's per-CPU modes, idle included, and busy is a
 * share of their sum. WITHOUT the agent Incus 7.3 reports the QEMU process's host seconds as cpu 0's {@code system}
 * mode with every other mode, idle included, at zero; their sum is then busy itself and read 100% for ever, so busy is
 * measured against elapsed time on the cores {@code limits.cpu} gives the VM, timed from when the counter last MOVED
 * (a cached counter read later must not stretch the window). The hub keeps the last measured window's share until the
 * counters move again, and metrics with no CPU lines at all read as "no CPU reading", never a flat zero.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class IncusStatsFeed implements StatsFeed {

    /** How often the daemon is read while somebody watches. */
    public static final long INTERVAL_MS = 2_000;

    private static final long READ_TIMEOUT_MS = 5_000;
    private static final String CPU_SECONDS = "incus_cpu_seconds_total";

    private final @NonNull IncusClient incus;
    private final @NonNull String handle;
    private final @NonNull IncusWorkloadType type;
    private final int limitCores;
    private final long intervalMs;
    private final Object lock = new Object();
    private boolean closed;
    private long nextAt;
    private double lastBusy = -1;
    private long busyMovedAt;

    /**
     * @param limitCores the CPUs the workload's share is of: a container's always, a VM's when no guest agent reports
     *                   its own CPUs
     * @param intervalMs the time between two reads
     */
    public IncusStatsFeed(@NonNull IncusClient incus, @NonNull String handle, @NonNull IncusWorkloadType type,
                          int limitCores, long intervalMs) {
        this.incus = incus;
        this.handle = handle;
        this.type = type;
        this.limitCores = Math.max(1, limitCores);
        this.intervalMs = intervalMs;
    }

    @Override
    public @Nullable StatsReading next() {
        synchronized (this.lock) {
            long wait;
            while (!this.closed && (wait = this.nextAt - Now.millis()) > 0) {
                try {
                    this.lock.wait(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (this.closed) {
                return null;
            }
            this.nextAt = Now.millis() + this.intervalMs;
        }
        try {
            Map<String, Object> state = this.incus.instanceState(this.handle, READ_TIMEOUT_MS);
            if (!"Running".equalsIgnoreCase(String.valueOf(state.get("status")))) {
                return null;
            }
            List<PrometheusText.Sample> metrics = this.type == IncusWorkloadType.VIRTUAL_MACHINE
                ? PrometheusText.parse(this.incus.metricsText()) : null;
            return readingOf(state, metrics, Now.millis());
        } catch (IOException unreadable) {
            Blast.log("STATS: Incus instance", this.handle, "could not be read:", unreadable.getMessage());
            return null;
        }
    }

    @Override
    public void close() {
        synchronized (this.lock) {
            this.closed = true;
            this.lock.notifyAll();
        }
    }

    private @NonNull StatsReading readingOf(@NonNull Map<String, Object> state,
                                            @Nullable List<PrometheusText.Sample> metrics, long at) {
        Map<String, Object> memory = RawValues.mapOrNull(state.get("memory"));
        long rx = 0;
        long tx = 0;
        if (RawValues.mapOrNull(state.get("network")) instanceof Map<String, Object> network) {
            for (Object value : network.values()) {
                Map<String, Object> iface = RawValues.mapOrNull(value);
                if (iface == null || "loopback".equals(iface.get("type"))) {
                    continue;
                }
                Map<String, Object> counters = RawValues.mapOrNull(iface.get("counters"));
                rx += RawValues.longAt(counters, "bytes_received");
                tx += RawValues.longAt(counters, "bytes_sent");
            }
        }
        StatsReading.CpuCounters cpu;
        int cores;
        if (metrics == null) {
            long usage = RawValues.longAt(RawValues.mapOrNull(state.get("cpu")), "usage");
            cores = this.limitCores;
            // The cgroup's nanoseconds against elapsed nanoseconds on every core the container may use.
            cpu = usage < 0 ? null : new StatsReading.CpuCounters(usage, at * 1_000_000d * cores);
        } else {
            Map<String, Double> byCpuAndMode = new HashMap<>();
            Set<String> cpus = new HashSet<>();
            for (PrometheusText.Sample sample : metrics) {
                if (CPU_SECONDS.equals(sample.name()) && this.handle.equals(sample.label("name"))) {
                    cpus.add(String.valueOf(sample.label("cpu")));
                    byCpuAndMode.put(sample.label("cpu") + "/" + sample.label("mode"), sample.value());
                }
            }
            double busy = 0;
            double idle = 0;
            for (Map.Entry<String, Double> entry : byCpuAndMode.entrySet()) {
                if (entry.getKey().endsWith("/idle") || entry.getKey().endsWith("/iowait")) {
                    idle += entry.getValue();
                } else {
                    busy += entry.getValue();
                }
            }
            if (byCpuAndMode.isEmpty()) {
                cores = Math.max(1, cpus.size());
                cpu = null;
            } else if (idle > 0) {
                cores = cpus.size();
                cpu = new StatsReading.CpuCounters(busy, busy + idle);
            } else {
                cores = this.limitCores;
                if (busy != this.lastBusy) {
                    this.lastBusy = busy;
                    this.busyMovedAt = at;
                }
                cpu = new StatsReading.CpuCounters(busy, this.busyMovedAt / 1000d * cores);
            }
        }
        return new StatsReading(at, cpu, cores, RawValues.longAt(memory, "usage"), RawValues.longAt(memory, "total"), rx, tx);
    }
}
