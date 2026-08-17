package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.common.security.SecurityEventTypes;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Per-source-IP threat scoring (the generalized successor of the proxy's
 * IpReputationTracker): WEIGHTED security events are counted in a sliding
 * window and decayed by real route hits, so a hostname-scanning bot gets
 * banned while a user with one stale bookmark recovers. Crossing the
 * threshold triggers the installed auto-ban callback (BanService).
 * Enforcement NEVER reads the scorer directly: ban rows are the only
 * enforcement truth, so every refused IP has an auditable, liftable row;
 * {@link #isOverThreshold} remains as a query for tests and metrics only.
 *
 * Only IPs that have scored are tracked; the per-IP ring buffer caps how many
 * score points can count, so a ban threshold above the buffer capacity
 * (2x threshold, min 64) is unreachable.
 *
 * @author  Jelle De Loecker
 * @since   0.3.0
 */
public final class ThreatScorer {

    private static final int MAX_ENTRIES = 50_000;
    private static final long EVICT_AGE_MS = 3_600_000;
    private static final long SETTINGS_TTL_MS = 10_000;

    // AIDEV-NOTE: hardcoded per-type weights on purpose -- they become settings
    // when someone actually needs to tune them, not before. Unknown types use
    // the security.default_event_weight setting.
    //
    // AIDEV-NOTE: keyed by the SecurityEventTypes CONSTANTS, never by re-spelled
    // literals. The literals were a silent second copy of zenit's vocabulary: a rename
    // there compiled fine here and quietly demoted the renamed event to the default
    // weight, which is a scoring change nobody would see. {@link #isClassified} plus
    // ThreatScorerTest's coverage journey close the other half -- a NEW zenit event type
    // now fails the build until somebody decides what it is worth.
    private static final Map<String, Integer> EVENT_WEIGHTS = Map.of(
        SecurityEventTypes.AUTH_LOGIN_FAILED, 3,
        SecurityEventTypes.AUTH_LOCKOUT, 10,
        SecurityEventTypes.RATE_LIMITED, 1,
        SecurityEventTypes.CSRF_FAILURE, 2,
        SecurityEventTypes.DOMAIN_MISS, 1);
    private static final int WS_EVENT_WEIGHT = 2;

    /** Every {@code ws.*} handshake refusal shares {@link #WS_EVENT_WEIGHT}. */
    private static final String WS_PREFIX = "ws.";

    /**
     * Event types DELIBERATELY left at {@code security.default_event_weight}, each with
     * the reason -- the declaration slot that keeps "we decided this is worth 1" distinct
     * from "nobody has looked at it yet". Empty today: every known type is either weighted
     * above, covered by the {@code ws.} rule, or classified positive.
     */
    private static final Map<String, String> DEFAULT_WEIGHTED = Map.of();

    /**
     * Callback fired when an actor's weighted score crosses the ban threshold.
     * The ip is the ACTOR KEY: the exact address for v4, the {@code <network>/64}
     * for v6 (rotating addresses inside one /64 share a score).
     */
    public interface AutoBanTrigger {
        void onThresholdCrossed(String ip, String eventType, int score);
    }

    private final LongSupplier clock;
    private final IntSupplier windowSecondsSource;
    private final IntSupplier banThresholdSource;
    private final IntSupplier decayPerHitSource;
    private final IntSupplier defaultWeightSource;

    private volatile @Nullable AutoBanTrigger autoBanTrigger;

    // Settings snapshot, refreshed at most every SETTINGS_TTL_MS (read on the hot path).
    // AIDEV-NOTE: initial readAt must be 0, not Long.MIN_VALUE -- "now - MIN_VALUE" overflows
    // negative, the staleness check never fires, and settings changes are silently ignored.
    private volatile long settingsReadAt = 0;
    private volatile int windowSeconds = 300;
    private volatile int banThreshold = 25;
    private volatile int decayPerHit = 2;
    private volatile int defaultWeight = 1;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /** A ring buffer of score-point timestamps; slot value 0 means empty/forgiven. */
    private static final class Entry {
        final long[] pointTimestamps;
        int head;
        long lastTouchMs;

        Entry(int capacity) {
            this.pointTimestamps = new long[capacity];
        }

        synchronized void addPoints(long now, int points) {
            for (int i = 0; i < points; i++) {
                pointTimestamps[head] = now;
                head = (head + 1) % pointTimestamps.length;
            }
            lastTouchMs = now;
        }

        synchronized int recentPointCount(long windowStart) {
            int count = 0;
            for (long timestamp : pointTimestamps) {
                if (timestamp > 0 && timestamp > windowStart) {
                    count++;
                }
            }
            return count;
        }

        /** Forgive the n oldest remaining points. */
        synchronized void decay(int n) {
            for (int k = 0; k < n; k++) {
                int oldestIndex = -1;
                long oldest = Long.MAX_VALUE;
                for (int i = 0; i < pointTimestamps.length; i++) {
                    long timestamp = pointTimestamps[i];
                    if (timestamp > 0 && timestamp < oldest) {
                        oldest = timestamp;
                        oldestIndex = i;
                    }
                }
                if (oldestIndex == -1) {
                    return;
                }
                pointTimestamps[oldestIndex] = 0;
            }
        }
    }

    public ThreatScorer() {
        this(System::currentTimeMillis,
            () -> HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.DOMAIN_MISS_WINDOW_SECONDS),
            () -> HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.DOMAIN_MISS_BAN_THRESHOLD),
            () -> HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.DOMAIN_MISS_DECAY_PER_HIT),
            () -> HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.DEFAULT_EVENT_WEIGHT));
    }

    /** Test constructor: inject the clock and the windowing knobs. */
    ThreatScorer(LongSupplier clock, IntSupplier windowSeconds,
                 IntSupplier banThreshold, IntSupplier decayPerHit,
                 IntSupplier defaultWeight) {
        this.clock = clock;
        this.windowSecondsSource = windowSeconds;
        this.banThresholdSource = banThreshold;
        this.decayPerHitSource = decayPerHit;
        this.defaultWeightSource = defaultWeight;
    }

    /** Install the callback fired when an IP crosses the ban threshold (null clears). */
    public void setAutoBanTrigger(@Nullable AutoBanTrigger trigger) {
        this.autoBanTrigger = trigger;
    }

    /**
     * Whether somebody has actually DECIDED what this event type is worth, as opposed to
     * it falling through to the default weight because nobody noticed it exists.
     *
     * @return true when the type carries an explicit weight, rides the {@code ws.} rule,
     *         is declared default-weighted, or is classified positive (never scored)
     */
    public static boolean isClassified(@Nullable String type) {
        return type != null
            && (EVENT_WEIGHTS.containsKey(type)
                || type.startsWith(WS_PREFIX)
                || DEFAULT_WEIGHTED.containsKey(type)
                || SecurityEventClassification.isPositive(type));
    }

    /** The ban-score weight of one occurrence of this event type. */
    public int weightOf(String type) {
        Integer weight = EVENT_WEIGHTS.get(type);
        if (weight != null) {
            return weight;
        }
        if (type != null && type.startsWith(WS_PREFIX)) {
            return WS_EVENT_WEIGHT;
        }
        refreshSettings();
        return Math.max(1, defaultWeight);
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
        defaultWeight = defaultWeightSource.getAsInt();
    }

    /**
     * The scoring key of a source address: v6 collapses to its /64 (one actor
     * controls the whole network), everything else stays as given.
     */
    private static String keyFor(String ip) {
        if (ip != null && ip.indexOf(':') >= 0) {
            String key = IpLiterals.subnetKey(ip);
            if (key != null) {
                return key;
            }
        }
        return ip;
    }

    /** Record a successful route hit: forgive some of this actor's oldest score points. */
    public void recordHit(String ip) {
        Entry entry = entries.get(keyFor(ip));
        if (entry == null) {
            return;
        }
        refreshSettings();
        entry.decay(decayPerHit);
    }

    /**
     * Record weighted occurrences of a local (in-process) event for this IP
     * and return its current in-window score. Fires the auto-ban trigger when
     * the score crosses the threshold (the trigger dedupes already-banned IPs
     * itself).
     */
    public int recordEvent(String ip, String type, int count) {
        int points = Math.max(1, weightOf(type)) * Math.max(1, count);
        return record(ip, type, points);
    }

    private int record(String ip, String type, int points) {
        refreshSettings();
        long now = clock.getAsLong();

        String key = keyFor(ip);
        Entry entry = entries.computeIfAbsent(key,
            k -> new Entry(Math.max(64, banThreshold * 2)));
        entry.addPoints(now, Math.min(points, entry.pointTimestamps.length));

        if (entries.size() > MAX_ENTRIES) {
            long cutoff = now - EVICT_AGE_MS;
            entries.entrySet().removeIf(e -> e.getValue().lastTouchMs < cutoff);
        }

        int score = entry.recentPointCount(now - windowSeconds * 1000L);
        AutoBanTrigger trigger = this.autoBanTrigger;
        if (trigger != null && score > banThreshold) {
            trigger.onThresholdCrossed(key, type, score);
        }
        return score;
    }

    /** Whether this source actor's in-window weighted score exceeds the ban threshold. */
    public boolean isOverThreshold(String ip) {
        Entry entry = entries.get(keyFor(ip));
        if (entry == null) {
            return false;
        }
        refreshSettings();
        long now = clock.getAsLong();
        return entry.recentPointCount(now - windowSeconds * 1000L) > banThreshold;
    }
}
