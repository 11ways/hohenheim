package be.elevenways.hohenheim.server.proxy;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure windowing/decay tests for {@link IpReputationTracker} with an injected clock. No DB.
 */
class IpReputationWindowingTest {

    private static final int WINDOW_SECONDS = 300;
    private static final int BAN_THRESHOLD = 25;
    private static final int DECAY_PER_HIT = 2;

    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    private IpReputationTracker newTracker() {
        return new IpReputationTracker(now::get,
            () -> WINDOW_SECONDS, () -> BAN_THRESHOLD, () -> DECAY_PER_HIT);
    }

    @Test
    void inWindowMissesBanAboveThreshold() {
        IpReputationTracker tracker = newTracker();

        for (int i = 0; i < BAN_THRESHOLD + 1; i++) {
            tracker.recordMiss("10.0.0.1");
            now.addAndGet(1000);
        }

        assertThat(tracker.isBanned("10.0.0.1")).isTrue();
        assertThat(tracker.isBanned("10.0.0.2")).isFalse();
    }

    @Test
    void exactlyAtThresholdIsNotBanned() {
        IpReputationTracker tracker = newTracker();

        for (int i = 0; i < BAN_THRESHOLD; i++) {
            tracker.recordMiss("10.0.0.3");
        }

        assertThat(tracker.isBanned("10.0.0.3")).isFalse();
    }

    @Test
    void outOfWindowMissesExpire() {
        IpReputationTracker tracker = newTracker();

        for (int i = 0; i < BAN_THRESHOLD + 5; i++) {
            tracker.recordMiss("10.0.0.4");
        }
        assertThat(tracker.isBanned("10.0.0.4")).isTrue();

        // Step past the window: the old misses no longer count.
        now.addAndGet((WINDOW_SECONDS + 1) * 1000L);
        assertThat(tracker.isBanned("10.0.0.4")).isFalse();

        // And the count really is windowed, not cumulative.
        int count = tracker.recordMiss("10.0.0.4");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void hitDecayLiftsABan() {
        IpReputationTracker tracker = newTracker();

        for (int i = 0; i < BAN_THRESHOLD + 3; i++) {
            tracker.recordMiss("10.0.0.5");
        }
        assertThat(tracker.isBanned("10.0.0.5")).isTrue();

        // Each hit forgives DECAY_PER_HIT misses; two hits bring 28 down to 24 (< threshold).
        tracker.recordHit("10.0.0.5");
        tracker.recordHit("10.0.0.5");

        assertThat(tracker.isBanned("10.0.0.5")).isFalse();
    }

    @Test
    void hitsForUntrackedIpsAreNoOps() {
        IpReputationTracker tracker = newTracker();
        tracker.recordHit("10.0.0.6");
        assertThat(tracker.isBanned("10.0.0.6")).isFalse();
    }
}
