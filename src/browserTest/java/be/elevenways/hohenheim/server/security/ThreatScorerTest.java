package be.elevenways.hohenheim.server.security;

import be.elevenways.zenit.common.security.KnownSecurityEvents;
import be.elevenways.zenit.common.security.SecurityEventTypes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure windowing/decay/weighting tests for {@link ThreatScorer} with an
 * injected clock (the IpReputationWindowingTest successor). No DB.
 */
class ThreatScorerTest {

    private static final int WINDOW_SECONDS = 300;
    private static final int BAN_THRESHOLD = 25;
    private static final int DECAY_PER_HIT = 2;
    private static final String MISS = "proxy.domain_miss";

    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    private ThreatScorer newScorer() {
        return new ThreatScorer(now::get,
            () -> WINDOW_SECONDS, () -> BAN_THRESHOLD, () -> DECAY_PER_HIT, () -> 1);
    }

    @Test
    void inWindowMissesBanAboveThreshold() {
        ThreatScorer scorer = newScorer();

        for (int i = 0; i < BAN_THRESHOLD + 1; i++) {
            scorer.recordEvent("10.0.0.1", MISS, 1);
            now.addAndGet(1000);
        }

        assertThat(scorer.isOverThreshold("10.0.0.1")).isTrue();
        assertThat(scorer.isOverThreshold("10.0.0.2")).isFalse();
    }

    @Test
    void exactlyAtThresholdIsNotBanned() {
        ThreatScorer scorer = newScorer();

        for (int i = 0; i < BAN_THRESHOLD; i++) {
            scorer.recordEvent("10.0.0.3", MISS, 1);
        }

        assertThat(scorer.isOverThreshold("10.0.0.3")).isFalse();
    }

    @Test
    void outOfWindowMissesExpire() {
        ThreatScorer scorer = newScorer();

        for (int i = 0; i < BAN_THRESHOLD + 5; i++) {
            scorer.recordEvent("10.0.0.4", MISS, 1);
        }
        assertThat(scorer.isOverThreshold("10.0.0.4")).isTrue();

        // Step past the window: the old misses no longer count.
        now.addAndGet((WINDOW_SECONDS + 1) * 1000L);
        assertThat(scorer.isOverThreshold("10.0.0.4")).isFalse();

        // And the score really is windowed, not cumulative.
        int score = scorer.recordEvent("10.0.0.4", MISS, 1);
        assertThat(score).isEqualTo(1);
    }

    @Test
    void hitDecayLiftsABan() {
        ThreatScorer scorer = newScorer();

        for (int i = 0; i < BAN_THRESHOLD + 3; i++) {
            scorer.recordEvent("10.0.0.5", MISS, 1);
        }
        assertThat(scorer.isOverThreshold("10.0.0.5")).isTrue();

        // Each hit forgives DECAY_PER_HIT points; two hits bring 28 down to 24 (< threshold).
        scorer.recordHit("10.0.0.5");
        scorer.recordHit("10.0.0.5");

        assertThat(scorer.isOverThreshold("10.0.0.5")).isFalse();
    }

    @Test
    void hitsForUntrackedIpsAreNoOps() {
        ThreatScorer scorer = newScorer();
        scorer.recordHit("10.0.0.6");
        assertThat(scorer.isOverThreshold("10.0.0.6")).isFalse();
    }

    @Test
    void eventsAreWeightedByType() {
        ThreatScorer scorer = newScorer();

        // auth.lockout weighs 10: three lockouts = 30 points > 25.
        assertThat(scorer.weightOf("auth.lockout")).isEqualTo(10);
        scorer.recordEvent("10.0.1.1", "auth.lockout", 3);
        assertThat(scorer.isOverThreshold("10.0.1.1")).isTrue();

        // auth.login_failed weighs 3, ws.* events 2, unknown types default 1.
        assertThat(scorer.weightOf("auth.login_failed")).isEqualTo(3);
        assertThat(scorer.weightOf("ws.origin_refused")).isEqualTo(2);
        assertThat(scorer.weightOf("something.custom")).isEqualTo(1);
    }

    @Test
    void countMultipliesTheWeight() {
        ThreatScorer scorer = newScorer();
        int score = scorer.recordEvent("10.0.1.2", "auth.login_failed", 4);
        assertThat(score).isEqualTo(12);
        assertThat(scorer.isOverThreshold("10.0.1.2")).isFalse();
    }

    @Test
    void crossingTheThresholdFiresTheAutoBanTrigger() {
        ThreatScorer scorer = newScorer();
        List<String> banned = new ArrayList<>();
        scorer.setAutoBanTrigger((ip, type, score) ->
            banned.add(ip + "|" + type));

        for (int i = 0; i < BAN_THRESHOLD; i++) {
            scorer.recordEvent("10.0.2.1", MISS, 1);
        }
        assertThat(banned).isEmpty();

        scorer.recordEvent("10.0.2.1", MISS, 1);
        assertThat(banned).contains("10.0.2.1|" + MISS);
    }

    @Test
    void v6AddressesScoreAsTheirSlash64() {
        ThreatScorer scorer = newScorer();
        List<String> banned = new ArrayList<>();
        scorer.setAutoBanTrigger((ip, type, score) -> banned.add(ip));

        // Rotating addresses inside ONE /64 accumulate on a single actor key.
        for (int i = 0; i < BAN_THRESHOLD + 1; i++) {
            scorer.recordEvent("2001:db8:1:2::" + Integer.toHexString(i + 1), MISS, 1);
        }

        assertThat(banned).containsExactly("2001:db8:1:2::/64");
        assertThat(scorer.isOverThreshold("2001:db8:1:2::abcd")).isTrue();
        // A neighboring /64 is a different actor.
        assertThat(scorer.isOverThreshold("2001:db8:1:3::1")).isFalse();

        // Hits from any address in the /64 decay the shared score.
        scorer.recordHit("2001:db8:1:2::ffff");
        assertThat(scorer.isOverThreshold("2001:db8:1:2::5")).isFalse();
    }

    @Test
    void hugeCountsAreCappedByTheRingBuffer() {
        ThreatScorer scorer = newScorer();
        // A million LOCAL occurrences must neither hang nor overflow: capacity caps the points.
        int score = scorer.recordEvent("10.0.3.1", "auth.lockout", 1_000_000);
        assertThat(score).isEqualTo(Math.max(64, BAN_THRESHOLD * 2));
        assertThat(scorer.isOverThreshold("10.0.3.1")).isTrue();
    }

    @Test
    void localEventsKeepFullWeightSemantics() {
        ThreatScorer scorer = newScorer();
        // 3 local lockouts = 30 points in ONE call (no per-event cap locally).
        int score = scorer.recordEvent("10.0.5.3", "auth.lockout", 3);
        assertThat(score).isEqualTo(30);
        assertThat(scorer.isOverThreshold("10.0.5.3")).isTrue();
    }

    /**
     * The DRIFT GUARD: zenit owns the event-type vocabulary, this class owns what each
     * type is worth, and a type that gains no verdict here silently scores the default
     * weight -- a scoring decision nobody made, invisible until an attack does not add up.
     */
    @Test
    void everyKnownSecurityEventTypeCarriesADeliberateVerdict() {
        // 1. Touch the classes that DECLARE types into the registry: hohenheim's own
        //    (proxy.domain_miss) and the positive classification (auth.login_succeeded).
        //    Without this the registry could hold only zenit's static-block entries.
        HohenheimSecurity.scorer();
        assertThat(SecurityEventClassification.isPositive(SecurityEventTypes.AUTH_LOGIN_SUCCEEDED))
            .as("step 1: the positive classification is loaded")
            .isTrue();

        // 2. The vocabulary is not empty, so step 3 is a real claim rather than a walk
        //    over nothing.
        List<String> known = KnownSecurityEvents.all();
        assertThat(known)
            .as("step 2: zenit's known vocabulary reaches this test")
            .contains(SecurityEventTypes.AUTH_LOGIN_FAILED, SecurityEventTypes.DOMAIN_MISS)
            .hasSizeGreaterThanOrEqualTo(8);

        // 3. THE CLAIM: every known type is weighted, ws.-ruled, declared default, or
        //    positive. A new type in zenit lands here as a failure naming itself.
        List<String> unclassified = new ArrayList<>();
        for (String type : known) {
            if (!ThreatScorer.isClassified(type)) {
                unclassified.add(type);
            }
        }
        assertThat(unclassified)
            .as("step 3: these security event types have no scoring verdict -- give each"
                + " one a weight in ThreatScorer.EVENT_WEIGHTS, declare it in"
                + " DEFAULT_WEIGHTED with the reason, or classify it positive in"
                + " SecurityEventClassification")
            .isEmpty();

        // 4. And classification is not a rubber stamp: an event type nobody has ever
        //    declared is UNclassified, which is what makes step 3 able to fail.
        assertThat(ThreatScorer.isClassified("app.some_future_event"))
            .as("step 4: an undeclared type is unclassified, so the guard has teeth")
            .isFalse();

        // 5. The weights themselves still answer, and the ws. rule still covers both
        //    handshake refusals without either being listed by name.
        ThreatScorer scorer = newScorer();
        assertThat(scorer.weightOf(SecurityEventTypes.AUTH_LOCKOUT))
            .as("step 5: a lockout stays the heaviest single event").isEqualTo(10);
        assertThat(scorer.weightOf(SecurityEventTypes.WS_ORIGIN_REFUSED))
            .as("step 5: ws.origin_refused rides the ws. rule").isEqualTo(2);
        assertThat(scorer.weightOf(SecurityEventTypes.WS_AUTH_REFUSED))
            .as("step 5: ws.auth_refused rides the same rule").isEqualTo(2);
    }
}
