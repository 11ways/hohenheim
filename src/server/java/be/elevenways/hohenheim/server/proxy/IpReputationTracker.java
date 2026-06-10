package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Per-source-IP reputation for the proxy: misses are counted in a sliding window and decayed by
 * real route hits, so a hostname-scanning bot gets banned while a user with one stale bookmark
 * recovers. Consulted both at the HTTP stage and, for HTTPS, at the TLS handshake (via
 * {@code SniKeyManager}) to drop bad IPs before a certificate is served.
 *
 * Only IPs that have missed are tracked; the per-IP ring buffer caps how many misses can count,
 * so a ban threshold above the buffer capacity (2x threshold, min 64) is unreachable.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class IpReputationTracker {

    private static final int MAX_ENTRIES = 50_000;
    private static final long EVICT_AGE_MS = 3_600_000;
    private static final long SETTINGS_TTL_MS = 10_000;

    private final LongSupplier clock;
    private final IntSupplier windowSecondsSource;
    private final IntSupplier banThresholdSource;
    private final IntSupplier decayPerHitSource;

    // Settings snapshot, refreshed at most every SETTINGS_TTL_MS (read on the hot path).
    // AIDEV-NOTE: initial readAt must be 0, not Long.MIN_VALUE -- "now - MIN_VALUE" overflows
    // negative, the staleness check never fires, and settings changes are silently ignored.
    private volatile long settingsReadAt = 0;
    private volatile int windowSeconds = 300;
    private volatile int banThreshold = 25;
    private volatile int decayPerHit = 2;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /** A ring buffer of miss timestamps; slot value 0 means empty/forgiven. */
    private static final class Entry {
        final long[] missTimestamps;
        int head;
        long lastTouchMs;

        Entry(int capacity) {
            this.missTimestamps = new long[capacity];
        }

        synchronized void addMiss(long now) {
            missTimestamps[head] = now;
            head = (head + 1) % missTimestamps.length;
            lastTouchMs = now;
        }

        synchronized int recentMissCount(long windowStart) {
            int count = 0;
            for (long timestamp : missTimestamps) {
                if (timestamp > 0 && timestamp > windowStart) {
                    count++;
                }
            }
            return count;
        }

        /** Forgive the n oldest remaining misses. */
        synchronized void decay(int n) {
            for (int k = 0; k < n; k++) {
                int oldestIndex = -1;
                long oldest = Long.MAX_VALUE;
                for (int i = 0; i < missTimestamps.length; i++) {
                    long timestamp = missTimestamps[i];
                    if (timestamp > 0 && timestamp < oldest) {
                        oldest = timestamp;
                        oldestIndex = i;
                    }
                }
                if (oldestIndex == -1) {
                    return;
                }
                missTimestamps[oldestIndex] = 0;
            }
        }
    }

    public IpReputationTracker() {
        this(System::currentTimeMillis,
            () -> HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.DOMAIN_MISS_WINDOW_SECONDS),
            () -> HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.DOMAIN_MISS_BAN_THRESHOLD),
            () -> HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.DOMAIN_MISS_DECAY_PER_HIT));
    }

    /** Test constructor: inject the clock and the three windowing knobs. */
    IpReputationTracker(LongSupplier clock, IntSupplier windowSeconds,
                        IntSupplier banThreshold, IntSupplier decayPerHit) {
        this.clock = clock;
        this.windowSecondsSource = windowSeconds;
        this.banThresholdSource = banThreshold;
        this.decayPerHitSource = decayPerHit;
    }

    private void refreshSettings() {
        long now = clock.getAsLong();
        if (now - settingsReadAt < SETTINGS_TTL_MS) {
            return;
        }
        settingsReadAt = now;
        windowSeconds = windowSecondsSource.getAsInt();
        banThreshold = banThresholdSource.getAsInt();
        decayPerHit = decayPerHitSource.getAsInt();
    }

    /** Record a successful route hit: forgive some of this IP's oldest misses. */
    public void recordHit(String ip) {
        Entry entry = entries.get(ip);
        if (entry == null) {
            return;
        }
        refreshSettings();
        entry.decay(decayPerHit);
    }

    /** Record a domain miss for this IP and return its current in-window miss count. */
    public int recordMiss(String ip) {
        refreshSettings();
        long now = clock.getAsLong();

        Entry entry = entries.computeIfAbsent(ip,
            k -> new Entry(Math.max(64, banThreshold * 2)));
        entry.addMiss(now);

        if (entries.size() > MAX_ENTRIES) {
            long cutoff = now - EVICT_AGE_MS;
            entries.entrySet().removeIf(e -> e.getValue().lastTouchMs < cutoff);
        }

        return entry.recentMissCount(now - windowSeconds * 1000L);
    }

    /** Whether this source IP is currently reputation-banned (over the in-window miss threshold). */
    public boolean isBanned(String ip) {
        Entry entry = entries.get(ip);
        if (entry == null) {
            return false;
        }
        refreshSettings();
        long now = clock.getAsLong();
        return entry.recentMissCount(now - windowSeconds * 1000L) > banThreshold;
    }
}
