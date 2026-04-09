package be.elevenways.hohenheim.server.stats;

import be.elevenways.protoblast.common.Blast;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects real-time server and per-site statistics.
 * Samples every 5 seconds, keeps 180 samples (15 minutes of history).
 * Equivalent to the original Node.js StatsCollector.
 */
public class StatsCollector {

    private static final int SAMPLE_INTERVAL_MS = 5000;
    private static final int MAX_HISTORY = 180;

    // Global counters (atomics, incremented from request handlers)
    private final AtomicLong hitCounter = new AtomicLong(0);
    private final AtomicLong connectionCounter = new AtomicLong(0);
    private final AtomicLong incomingBytes = new AtomicLong(0);
    private final AtomicLong outgoingBytes = new AtomicLong(0);

    // Per-site counters: siteId -> AtomicLong
    private final ConcurrentHashMap<Integer, AtomicLong> siteHits = new ConcurrentHashMap<>();

    // History: circular buffer of snapshots
    private final LinkedList<StatsSnapshot> history = new LinkedList<>();

    // Activity log: last 50 events
    private final LinkedList<ActivityEvent> activityLog = new LinkedList<>();
    private static final int MAX_ACTIVITY = 50;

    // Listeners for real-time updates
    private final Set<StatsListener> listeners = new CopyOnWriteArraySet<>();

    // Previous sample values for delta calculation
    private long prevHits = 0;
    private long prevConnections = 0;
    private long prevIncoming = 0;
    private long prevOutgoing = 0;

    private ScheduledExecutorService scheduler;

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stats-collector");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::sample, SAMPLE_INTERVAL_MS, SAMPLE_INTERVAL_MS,
            TimeUnit.MILLISECONDS);
        Blast.log("StatsCollector started (every", SAMPLE_INTERVAL_MS / 1000, "s)");
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    // -----------------------------------------------------------------------
    // Counter increment methods (called from request handlers)
    // -----------------------------------------------------------------------

    public void recordHit(int siteId) {
        hitCounter.incrementAndGet();
        siteHits.computeIfAbsent(siteId, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void recordConnection() {
        connectionCounter.incrementAndGet();
    }

    public void recordBytes(long incoming, long outgoing) {
        incomingBytes.addAndGet(incoming);
        outgoingBytes.addAndGet(outgoing);
    }

    // -----------------------------------------------------------------------
    // Activity events
    // -----------------------------------------------------------------------

    public void addActivity(String type, String message) {
        ActivityEvent event = new ActivityEvent(System.currentTimeMillis(), type, message);
        synchronized (activityLog) {
            activityLog.addLast(event);
            while (activityLog.size() > MAX_ACTIVITY) activityLog.removeFirst();
        }
        for (StatsListener listener : listeners) {
            listener.onActivity(event);
        }
    }

    // -----------------------------------------------------------------------
    // Listeners
    // -----------------------------------------------------------------------

    public void addListener(StatsListener listener) {
        listeners.add(listener);
    }

    public void removeListener(StatsListener listener) {
        listeners.remove(listener);
    }

    // -----------------------------------------------------------------------
    // Snapshot access
    // -----------------------------------------------------------------------

    public List<StatsSnapshot> getHistory() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public List<ActivityEvent> getActivityLog() {
        synchronized (activityLog) {
            return new ArrayList<>(activityLog);
        }
    }

    public StatsSnapshot getLatest() {
        synchronized (history) {
            return history.isEmpty() ? null : history.getLast();
        }
    }

    // -----------------------------------------------------------------------
    // Sampling
    // -----------------------------------------------------------------------

    private void sample() {
        try {
            long hits = hitCounter.get();
            long conns = connectionCounter.get();
            long incoming = incomingBytes.get();
            long outgoing = outgoingBytes.get();

            double intervalSec = SAMPLE_INTERVAL_MS / 1000.0;
            double hitsPerSec = (hits - prevHits) / intervalSec;
            double connsPerSec = (conns - prevConnections) / intervalSec;
            long incomingDelta = incoming - prevIncoming;
            long outgoingDelta = outgoing - prevOutgoing;

            prevHits = hits;
            prevConnections = conns;
            prevIncoming = incoming;
            prevOutgoing = outgoing;

            // Build per-site stats
            Map<Integer, Long> siteHitSnapshot = new HashMap<>();
            for (var entry : siteHits.entrySet()) {
                siteHitSnapshot.put(entry.getKey(), entry.getValue().get());
            }

            StatsSnapshot snapshot = new StatsSnapshot(
                System.currentTimeMillis(),
                hits, conns,
                hitsPerSec, connsPerSec,
                incomingDelta, outgoingDelta,
                siteHitSnapshot
            );

            synchronized (history) {
                history.addLast(snapshot);
                while (history.size() > MAX_HISTORY) history.removeFirst();
            }

            for (StatsListener listener : listeners) {
                listener.onStats(snapshot);
            }
        } catch (Exception e) {
            Blast.log("StatsCollector: sample failed:", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Data classes
    // -----------------------------------------------------------------------

    public record StatsSnapshot(
        long timestamp,
        long totalHits,
        long totalConnections,
        double hitsPerSecond,
        double connectionsPerSecond,
        long incomingBytesDelta,
        long outgoingBytesDelta,
        Map<Integer, Long> siteHits
    ) {}

    public record ActivityEvent(long timestamp, String type, String message) {}

    public interface StatsListener {
        void onStats(StatsSnapshot snapshot);
        void onActivity(ActivityEvent event);
    }
}
