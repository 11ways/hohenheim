package be.elevenways.hohenheim.server.instance;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each running app's recent memory use, sampled by {@code ObserveInstanceMemory}, kept so the memory health checks
 * ({@link MemoryHealth}) can judge a state that lasts instead of one moment.
 *
 * AIDEV-NOTE: in memory ONLY, by decision: no migration may add a table for it. A restart empties it, and a check over
 * a window says nothing until samples cover that whole window again without a gap ({@link Track#covering}): an hour
 * after a boot for "stays above its reservation", but a whole week of uptime for "far below over a week", so a
 * controller redeployed more often than {@code capacity.memory_far_below_days} never raises that hint. A gap longer
 * than {@link #MAX_GAP} (missed sweeps, an app that was stopped) breaks coverage the same way, so a window is never
 * judged from a handful of samples. Bounded twice: samples older than the longest window go at every record, and an
 * app that is no longer running is forgotten by {@link #retain}.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class MemoryHistory {

    /** How often ObserveInstanceMemory takes a sample of every running app. */
    public static final Duration SAMPLE_INTERVAL = Duration.ofMinutes(5);

    /** The longest silence a window may hold and still count as watched: three missed sweeps. */
    public static final Duration MAX_GAP = Duration.ofMinutes(15);

    /** One reading of a workload's memory use. */
    public record Sample(@NonNull Instant at, long usedBytes) {
    }

    /** One app's samples, oldest first. */
    public record Track(@NonNull List<Sample> samples) {

        /**
         * @return the samples inside the window ending at {@code now}, or null when they do not cover it: a silence
         *     longer than {@link #MAX_GAP} at its start, between two samples or before {@code now}
         */
        public @Nullable List<Sample> covering(@NonNull Duration window, @NonNull Instant now) {
            Instant start = now.minus(window);
            List<Sample> inside = this.samples.stream().filter(sample -> sample.at().isAfter(start)).toList();
            Instant previous = start;
            for (Sample sample : inside) {
                if (Duration.between(previous, sample.at()).compareTo(MAX_GAP) > 0) {
                    return null;
                }
                previous = sample.at();
            }
            return inside.isEmpty() || Duration.between(previous, now).compareTo(MAX_GAP) > 0 ? null : inside;
        }
    }

    private static final Map<Integer, ArrayDeque<Sample>> TRACKS = new ConcurrentHashMap<>();

    private MemoryHistory() {
    }

    /**
     * Records one sample, dropping the app's samples older than {@code keep} before it.
     *
     * @param keep the longest window any check judges
     */
    public static void record(int instanceId, @NonNull Sample sample, @NonNull Duration keep) {
        ArrayDeque<Sample> track = TRACKS.computeIfAbsent(instanceId, id -> new ArrayDeque<>());
        synchronized (track) {
            Instant oldest = sample.at().minus(keep);
            while (!track.isEmpty() && track.peekFirst().at().isBefore(oldest)) {
                track.pollFirst();
            }
            track.addLast(sample);
        }
    }

    /** @return the app's track, null while nothing was recorded for it */
    public static @Nullable Track trackOf(int instanceId) {
        ArrayDeque<Sample> track = TRACKS.get(instanceId);
        if (track == null) {
            return null;
        }
        synchronized (track) {
            return new Track(List.copyOf(track));
        }
    }

    /** @return the apps that have a track */
    public static @NonNull Set<Integer> tracked() {
        return Set.copyOf(TRACKS.keySet());
    }

    /** Forgets every app outside {@code running}, so one that comes back is watched afresh. */
    public static void retain(@NonNull Set<Integer> running) {
        TRACKS.keySet().retainAll(running);
    }

    /** Forgets every app; the tests' reset. */
    public static void clear() {
        TRACKS.clear();
    }
}
