package be.elevenways.hohenheim.server.runtime;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.dry.Dry;
import be.elevenways.protoblast.common.time.Now;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * Docker's stats stream ({@code /containers/{id}/stats?stream=1}, one JSON object per interval) read as
 * {@link StatsReading}s.
 *
 * AIDEV-NOTE: a chunk boundary can split one object, so the buffer is drained line by line and a partial tail is
 * kept; a buffer past {@link #MAX_BUFFER} means the stream is not what it should be, and the feed ends rather than
 * growing without bound (one wedged daemon must not become an OOM).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class DockerStatsFeed implements StatsFeed {

    private static final int MAX_BUFFER = 1_000_000;

    private final @NonNull ConsoleStream stream;
    private final StringBuilder buffer = new StringBuilder();
    private final ConsoleText text = new ConsoleText();

    public DockerStatsFeed(@NonNull ConsoleStream stream) {
        this.stream = stream;
    }

    @Override
    public @Nullable StatsReading next() {
        while (true) {
            int newline;
            while ((newline = this.buffer.indexOf("\n")) >= 0) {
                String line = this.buffer.substring(0, newline).trim();
                this.buffer.delete(0, newline + 1);
                Map<String, Object> parsed = line.isEmpty() ? null : parse(line);
                if (parsed != null) {
                    return readingOf(parsed);
                }
            }
            if (this.buffer.length() > MAX_BUFFER) {
                Blast.log("STATS: an unparseable Docker stats stream; closing");
                return null;
            }
            ConsoleStream.Chunk chunk = this.stream.next();
            if (chunk == null) {
                return null;
            }
            this.buffer.append(this.text.decode(chunk));
        }
    }

    @Override
    public void close() {
        this.stream.close();
    }

    /**
     * One raw Docker stats object as a reading: the container's cumulative CPU nanoseconds against the host's system
     * nanoseconds, over the CPUs the daemon reports online.
     *
     * AIDEV-NOTE: the hub takes the share against the PREVIOUS reading, never the daemon's own precpu_stats: Docker
     * zeroes precpu on the first stream sample, and dividing by it yields the container's LIFETIME AVERAGE dressed up
     * as an instantaneous reading.
     */
    static @NonNull StatsReading readingOf(@NonNull Map<String, Object> current) {
        Map<String, Object> cpuStats = child(current, "cpu_stats");
        long cores = Math.max(1, number(cpuStats, "online_cpus"));
        StatsReading.CpuCounters cpu = new StatsReading.CpuCounters(
            number(child(cpuStats, "cpu_usage"), "total_usage"), number(cpuStats, "system_cpu_usage"));
        Map<String, Object> memoryStats = child(current, "memory_stats");
        long rx = 0;
        long tx = 0;
        Map<String, Object> networks = child(current, "networks");
        if (networks != null) {
            for (Object value : networks.values()) {
                if (value instanceof Map<?, ?> iface) {
                    rx += number(RawValues.map(iface), "rx_bytes");
                    tx += number(RawValues.map(iface), "tx_bytes");
                }
            }
        }
        return new StatsReading(Now.millis(), cpu, (int) cores, number(memoryStats, "usage"),
            number(memoryStats, "limit"), rx, tx);
    }

    private static @Nullable Map<String, Object> parse(@NonNull String line) {
        try {
            return RawValues.mapOrNull(new Dry().parse(line));
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private static @Nullable Map<String, Object> child(@Nullable Map<String, Object> parent, @NonNull String key) {
        return RawValues.mapOrNull(parent == null ? null : parent.get(key));
    }

    private static long number(@Nullable Map<String, Object> parent, @NonNull String key) {
        Object value = parent == null ? null : parent.get(key);
        return value instanceof Number n ? n.longValue() : 0;
    }
}
