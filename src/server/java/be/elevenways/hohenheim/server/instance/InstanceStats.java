package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimStatsFunctions.Metric;
import be.elevenways.hohenheim.server.runtime.ConsoleStream;
import be.elevenways.hohenheim.server.runtime.StatsStreamSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.dry.Dry;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * THE live-stats consumer of the streaming seam: one {@code followStats} stream per
 * OBSERVED instance, decoded into typed samples, fanned out to every viewer and torn down
 * the moment the last one leaves.
 *
 * Bounded by construction, which is the whole reason a hub exists instead of a stream per
 * viewer: ONE daemon stream per instance no matter how many tabs are open, a ring of
 * {@link #HISTORY} samples and nothing else retained. This is live OBSERVATION -- there is
 * deliberately no metrics store, no rollup and no disk anywhere in this class.
 *
 * AIDEV-NOTE: the ring is what makes a RECONNECT bounded too. A viewer that drops and
 * comes back replays at most HISTORY samples and then follows live; it never asks the
 * daemon to re-send anything, because the daemon cannot.
 */
public final class InstanceStats {

    /** Samples kept per instance; docker emits roughly one per second. */
    public static final int HISTORY = 60;

    private static final Map<Integer, Session> SESSIONS = new ConcurrentHashMap<>();

    private InstanceStats() {
    }

    /**
     * One decoded sample.
     *
     * @param cpuPercent   CPU use across all cores, 0..(100 * cores)
     * @param memoryBytes  current working set
     * @param memoryLimit  the cgroup limit, or 0 when the daemon reports none
     * @param rxBytes      cumulative received bytes across every interface
     * @param txBytes      cumulative transmitted bytes
     */
    public record Sample(long at, double cpuPercent, long memoryBytes, long memoryLimit,
                         long rxBytes, long txBytes) {

        /** The wire shape both the channel and the page render from; metric keys come
         * from the one vocabulary the browser folds them back out with. */
        public @NonNull Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("at", this.at);
            map.put(Metric.CPU.key(), this.cpuPercent);
            map.put(Metric.MEMORY.key(), this.memoryBytes);
            map.put("memory_limit", this.memoryLimit);
            map.put(Metric.RX.key(), this.rxBytes);
            map.put(Metric.TX.key(), this.txBytes);
            return map;
        }
    }

    /**
     * Attach a viewer, replaying the ring first so a freshly opened tab has a chart
     * immediately rather than a minute of blankness.
     *
     * @return a handle whose {@code close()} detaches it (and tears the stream down when it
     *         was the last viewer)
     * @throws IOException when the workload has no stats to follow (absent, stopped, or a
     *                     driver without a streaming lane)
     */
    public static @NonNull Subscription subscribe(int instanceId, @NonNull Consumer<Sample> viewer)
            throws IOException {
        Session session = SESSIONS.compute(instanceId, (id, existing) -> {
            if (existing != null && existing.isLive()) {
                return existing;
            }
            return new Session(id);
        });
        session.start();
        session.attach(viewer);
        return () -> session.detach(viewer);
    }

    /** Detach handle; closing twice is a no-op. */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override void close();
    }

    /** The samples currently held for an instance, oldest first; empty when nobody watches. */
    public static @NonNull List<Sample> history(int instanceId) {
        Session session = SESSIONS.get(instanceId);
        return session == null ? List.of() : session.history();
    }

    /**
     * The most recent working set (MB) of an instance, or null when this hub holds no
     * sample for it.
     *
     * AIDEV-NOTE: deliberately NEVER opens a stream. A live figure exists exactly while
     * somebody watches the workload, and a REST read that started a daemon stats stream
     * per workload would turn one host listing into N daemon streams for one number.
     * Absent is an honest answer here; an invented one is not.
     */
    public static @Nullable Long lastMemoryMb(int instanceId) {
        List<Sample> samples = history(instanceId);
        if (samples.isEmpty()) {
            return null;
        }
        return samples.get(samples.size() - 1).memoryBytes() / (1024L * 1024L);
    }

    /** Close every live stream; the boot/shutdown seam and the test cleanup hook. */
    public static void shutdown() {
        for (Session session : SESSIONS.values()) {
            session.stop();
        }
        SESSIONS.clear();
    }

    // -- one instance's stream ------------------------------------------------

    private static final class Session {

        private final int instanceId;
        private final List<Consumer<Sample>> viewers = new CopyOnWriteArrayList<>();
        private final List<Sample> ring = new ArrayList<>();

        private @Nullable ConsoleStream stream;
        private volatile boolean running;

        Session(int instanceId) {
            this.instanceId = instanceId;
        }

        boolean isLive() {
            return this.running;
        }

        synchronized void start() throws IOException {
            if (this.running) {
                return;
            }
            InstanceService.Resolved resolved = new InstanceService().resolve(this.instanceId);
            if (!(resolved.runtime() instanceof StatsStreamSupport support)) {
                throw new IOException("The driver of instance " + this.instanceId
                    + " has no live-stats lane");
            }
            this.stream = support.openStats(resolved.spec().handle());
            this.running = true;
            Thread pump = new Thread(this::pump, "hohenheim-instance-stats-" + this.instanceId);
            pump.setDaemon(true);
            pump.start();
        }

        void attach(@NonNull Consumer<Sample> viewer) {
            for (Sample sample : this.history()) {
                viewer.accept(sample);
            }
            this.viewers.add(viewer);
        }

        void detach(@NonNull Consumer<Sample> viewer) {
            this.viewers.remove(viewer);
            if (this.viewers.isEmpty()) {
                // Nobody is watching: the daemon stream is a real socket and a real
                // per-second sampling cost, so it dies with its last viewer.
                this.stop();
                SESSIONS.remove(this.instanceId, this);
            }
        }

        synchronized void stop() {
            this.running = false;
            ConsoleStream stream = this.stream;
            this.stream = null;
            if (stream != null) {
                stream.close();
            }
        }

        synchronized @NonNull List<Sample> history() {
            return List.copyOf(this.ring);
        }

        private synchronized void record(@NonNull Sample sample) {
            this.ring.add(sample);
            while (this.ring.size() > HISTORY) {
                this.ring.remove(0);
            }
        }

        /**
         * Decode the daemon's NDJSON samples. Docker chunks one JSON object per interval,
         * but a chunk boundary can split one, so the buffer is drained line by line and a
         * partial tail is kept.
         */
        private void pump() {
            StringBuilder buffer = new StringBuilder();
            Map<String, Object> previous = null;
            ConsoleStream stream = this.stream;
            while (this.running && stream != null) {
                ConsoleStream.Chunk chunk = stream.next();
                if (chunk == null) {
                    break;
                }
                buffer.append(new String(chunk.data(), StandardCharsets.UTF_8));
                int newline;
                while ((newline = buffer.indexOf("\n")) >= 0) {
                    String line = buffer.substring(0, newline).trim();
                    buffer.delete(0, newline + 1);
                    if (line.isEmpty()) {
                        continue;
                    }
                    Map<String, Object> parsed = parseSample(line);
                    if (parsed == null) {
                        continue;
                    }
                    Sample sample = decode(parsed, previous);
                    previous = parsed;
                    if (sample == null) {
                        continue;
                    }
                    this.record(sample);
                    for (Consumer<Sample> viewer : this.viewers) {
                        try {
                            viewer.accept(sample);
                        } catch (RuntimeException viewerFailure) {
                            Blast.log("STATS: viewer of instance", this.instanceId,
                                "threw:", viewerFailure.getMessage());
                        }
                    }
                }
                // A stats line is small; a buffer this size means the stream is not what we
                // think it is, and growing it without bound is how one wedged daemon
                // becomes an OOM.
                if (buffer.length() > 1_000_000) {
                    Blast.log("STATS: instance", this.instanceId,
                        "sent an unparseable stats stream; closing");
                    break;
                }
            }
            this.stop();
            SESSIONS.remove(this.instanceId, this);
        }

        @SuppressWarnings("unchecked")
        private static @Nullable Map<String, Object> parseSample(@NonNull String line) {
            try {
                Object parsed = new Dry().parse(line);
                return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
            } catch (RuntimeException notJson) {
                return null;
            }
        }
    }

    /**
     * Turn one raw docker stats object into a sample. CPU is a DELTA between consecutive
     * samples (the daemon reports cumulative nanoseconds), so the very first observation
     * legitimately yields no CPU figure and is reported as zero rather than as a spike.
     */
    static @Nullable Sample decode(@NonNull Map<String, Object> current,
                                   @Nullable Map<String, Object> previous) {
        double cpu = 0;
        Map<String, Object> cpuStats = child(current, "cpu_stats");
        // AIDEV-NOTE: the previous SAMPLE, never the daemon's own precpu_stats on the first
        // observation. Docker zeroes precpu on the first stream sample, so dividing by it
        // yields the container's LIFETIME AVERAGE dressed up as an instantaneous reading --
        // a number that looks live and is not. No previous sample means no CPU figure.
        Map<String, Object> preCpuStats = previous == null ? null : child(previous, "cpu_stats");
        if (cpuStats != null && preCpuStats != null) {
            long usage = number(child(cpuStats, "cpu_usage"), "total_usage");
            long previousUsage = number(child(preCpuStats, "cpu_usage"), "total_usage");
            long system = number(cpuStats, "system_cpu_usage");
            long previousSystem = number(preCpuStats, "system_cpu_usage");
            long cores = Math.max(1, number(cpuStats, "online_cpus"));
            long usageDelta = usage - previousUsage;
            long systemDelta = system - previousSystem;
            if (usageDelta > 0 && systemDelta > 0) {
                cpu = (usageDelta / (double) systemDelta) * cores * 100.0;
            }
        }

        Map<String, Object> memoryStats = child(current, "memory_stats");
        long memory = number(memoryStats, "usage");
        long limit = number(memoryStats, "limit");

        long rx = 0;
        long tx = 0;
        Map<String, Object> networks = child(current, "networks");
        if (networks != null) {
            for (Object value : networks.values()) {
                if (value instanceof Map<?, ?> iface) {
                    rx += number(castMap(iface), "rx_bytes");
                    tx += number(castMap(iface), "tx_bytes");
                }
            }
        }
        return new Sample(System.currentTimeMillis(), cpu, memory, limit, rx, tx);
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> child(@Nullable Map<String, Object> parent,
                                                       @NonNull String key) {
        Object value = parent == null ? null : parent.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> castMap(@NonNull Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static long number(@Nullable Map<String, Object> parent, @NonNull String key) {
        Object value = parent == null ? null : parent.get(key);
        return value instanceof Number n ? n.longValue() : 0;
    }
}
