package be.elevenways.hohenheim.server.instance;

import be.elevenways.protoblast.common.i18n.wording.ByteUnit;
import be.elevenways.hohenheim.HohenheimStatsFunctions;
import be.elevenways.hohenheim.HohenheimStatsFunctions.Metric;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.runtime.StatsFeed;
import be.elevenways.hohenheim.server.runtime.StatsReading;
import be.elevenways.hohenheim.server.runtime.StatsStreamSupport;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
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
     * @param cpuPercent   CPU use across all cores, 0..(100 * cores); 0 when {@code cpuReported} is false
     * @param memoryBytes  current working set
     * @param memoryLimit  the cgroup limit, or 0 when the daemon reports none
     * @param rxBytes      cumulative received bytes across every interface
     * @param txBytes      cumulative transmitted bytes
     * @param cores        the CPUs the daemon reports online for it, what {@code cpuPercent} is a share of (100 each)
     * @param cpuReported  false when the runtime gives this workload no CPU reading at all (a VM without its agent)
     */
    public record Sample(long at, double cpuPercent, long memoryBytes, long memoryLimit,
                         long rxBytes, long txBytes, int cores, boolean cpuReported) {

        /** A sample whose CPU figure the runtime reported. */
        public Sample(long at, double cpuPercent, long memoryBytes, long memoryLimit, long rxBytes, long txBytes,
                      int cores) {
            this(at, cpuPercent, memoryBytes, memoryLimit, rxBytes, txBytes, cores, true);
        }

        /** The wire shape both the channel and the page render from; metric keys come
         * from the one vocabulary the browser folds them back out with. An unreported CPU
         * carries no CPU figure, only the flag that says so. */
        public @NonNull Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(HohenheimStatsFunctions.AT_KEY, this.at);
            if (this.cpuReported) {
                map.put(Metric.CPU.key(), this.cpuPercent);
            } else {
                map.put(HohenheimStatsFunctions.CPU_UNREPORTED_KEY, true);
            }
            map.put(Metric.MEMORY.key(), this.memoryBytes);
            map.put(HohenheimStatsFunctions.MEMORY_LIMIT_KEY, this.memoryLimit);
            map.put(Metric.RX.key(), this.rxBytes);
            map.put(Metric.TX.key(), this.txBytes);
            map.put(HohenheimStatsFunctions.CORES_KEY, this.cores);
            return map;
        }
    }

    /**
     * Attach a viewer, replaying the ring first so a freshly opened tab has a chart
     * immediately rather than a minute of blankness.
     *
     * @return a handle whose {@code close()} detaches it (and tears the stream down when it
     *         was the last viewer)
     * @throws IOException when the workload has no stats to follow (absent, stopped, an
     *                     application nothing serves, or a driver without a streaming lane)
     */
    public static @NonNull Subscription subscribe(int instanceId, @NonNull Consumer<Sample> viewer)
            throws IOException {
        // An application's samples are its serving release's: the ring the overview and the Metrics tab seed from.
        Integer workloadId = ApplicationReleases.workloadIdOf(instanceId);
        if (workloadId == null) {
            throw new IOException("Nothing serves application " + instanceId + " yet");
        }
        Session session = SESSIONS.compute(workloadId, (id, existing) -> {
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

    /** @return how many viewers follow the instance's stream right now; a released link is no viewer */
    public static int viewers(int instanceId) {
        Session session = SESSIONS.get(instanceId);
        return session == null ? 0 : session.viewers.size();
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
        return samples.get(samples.size() - 1).memoryBytes() / ByteUnit.MEGABYTE.factor();
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

        private @Nullable StatsFeed stream;
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
            StatsFeed stream = this.stream;
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

        /** Turn the driver's readings into samples and fan each out to every viewer. */
        private void pump() {
            Decoder decoder = new Decoder();
            StatsFeed stream = this.stream;
            while (this.running && stream != null) {
                StatsReading reading = stream.next();
                if (reading == null) {
                    break;
                }
                Sample sample = decoder.next(reading);
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
            this.stop();
            SESSIONS.remove(this.instanceId, this);
        }
    }

    /**
     * Consecutive readings into samples. CPU is a DELTA between readings (every driver reports cumulative counters),
     * so the very first observation legitimately yields no CPU figure and is reported as zero rather than as a spike.
     *
     * AIDEV-NOTE: a reading whose counters have not moved since the last measured one (an Incus VM's metrics refresh
     * about every 8 seconds, slower than they are read) keeps the last measured window's share: that window is still
     * the newest measurement, and a zero would be a dip that never happened.
     */
    static final class Decoder {

        private @Nullable StatsReading base;
        private double share;

        @NonNull Sample next(@NonNull StatsReading reading) {
            StatsReading.CpuCounters cpu = reading.cpu();
            if (cpu == null) {
                return sampleOf(reading, 0, false);
            }
            StatsReading.CpuCounters previous = this.base == null ? null : this.base.cpu();
            if (previous == null) {
                this.base = reading;
            } else if (cpu.total() - previous.total() > 0) {
                double busy = cpu.busy() - previous.busy();
                this.share = busy > 0 ? busy / (cpu.total() - previous.total()) * reading.cores() * 100.0 : 0;
                this.base = reading;
            }
            return sampleOf(reading, this.share, true);
        }

        private static @NonNull Sample sampleOf(@NonNull StatsReading reading, double cpu, boolean reported) {
            return new Sample(reading.at(), cpu, reading.memoryBytes(), reading.memoryLimit(), reading.rxBytes(),
                reading.txBytes(), reading.cores(), reported);
        }
    }
}
