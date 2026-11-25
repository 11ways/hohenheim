package be.elevenways.hohenheim.server.traffic;

import be.elevenways.hohenheim.server.runtime.StatsReading;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.traffic.common.TrafficTransfer;
import be.elevenways.zenit.traffic.server.TrafficStore;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * THE one turning of an app's network counters into counted traffic: every reading a workload's stats lane gives (the
 * memory sweep's, the live hub's while someone watches) grows its app's totals by what the counters grew since that
 * workload's previous reading.
 *
 * AIDEV-NOTE: one baseline per workload, whoever read it, so the sweep and the hub never count the same bytes twice. A
 * first reading only sets the baseline: what the counters held before was counted then or never, so a restart of
 * Hohenheim loses what moved between the last reading before it and the first after. A counter below its baseline was
 * reset (the container started again with fresh interfaces): what it holds now moved since the reset and counts, what
 * moved between the previous reading and the reset is unknowable and lost, never guessed. A reading no newer than the
 * baseline (the other reader's, overlapping) counts nothing. The bytes are stamped with Now when they are counted,
 * never with the reading's own time, which a remote daemon's clock writes and the store's periods would follow forward.
 * Each release and container keeps its own baseline and counts under its app (ApplicationReleases.linkOwnerOf), so an
 * app's releases and containers add up.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class AppCounters {

    /** A workload's latest reading, and the app it counts under. */
    private record Baseline(int appId, long at, long rxBytes, long txBytes) {
    }

    private static final Map<Integer, Baseline> BASELINES = new ConcurrentHashMap<>();

    private AppCounters() {
    }

    /**
     * Counts what the workload's counters grew since its previous reading under its app; never throws.
     *
     * @param appId      the app the workload runs for (ApplicationReleases.linkOwnerOf of its row)
     * @param workloadId the workload whose counters were read: a release, a container, the app itself
     */
    public static void observe(int appId, int workloadId, @NonNull StatsReading reading) {
        AtomicReference<TrafficTransfer> grown = new AtomicReference<>();
        BASELINES.compute(workloadId, (id, previous) -> {
            Baseline now = new Baseline(appId, reading.at(), reading.rxBytes(), reading.txBytes());
            if (previous == null || previous.appId() != appId) {
                return now;
            }
            if (reading.at() <= previous.at()) {
                return previous;
            }
            long sent = grownBy(previous.txBytes(), reading.txBytes());
            long received = grownBy(previous.rxBytes(), reading.rxBytes());
            if (sent > 0 || received > 0) {
                grown.set(new TrafficTransfer(Now.instant(), HohenheimTraffic.appSource(appId), sent, received));
            }
            return now;
        });
        TrafficTransfer transfer = grown.get();
        if (transfer == null) {
            return;
        }
        // Between the stop step and the next start nothing counts; the process is ending, so the bytes are logged.
        TrafficStore store = HohenheimTraffic.store(HohenheimTraffic.Counted.APPS);
        try {
            if (store == null) {
                throw new IllegalStateException("traffic counting is stopped");
            }
            store.record(transfer);
        } catch (RuntimeException uncounted) {
            Blast.slog("traffic.app_bytes_not_counted", Map.of("app", appId, "sent", transfer.bytesSent(),
                "received", transfer.bytesReceived(), "reason", String.valueOf(uncounted)));
        }
    }

    /** Forgets every workload outside {@code running}, so one that runs again starts from a fresh baseline. */
    public static void retain(@NonNull Set<Integer> running) {
        BASELINES.keySet().retainAll(running);
    }

    /** @return what a counter grew by, from a previous reading to this one; a lower one was reset and counts whole */
    static long grownBy(long previous, long current) {
        if (current < 0) {
            return 0;
        }
        return current >= previous ? current - previous : current;
    }
}
