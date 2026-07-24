package be.elevenways.hohenheim.server.security;

import be.elevenways.zenit.common.security.KnownSecurityEvents;
import be.elevenways.zenit.common.security.SecurityEventTypes;
import be.elevenways.zenit.server.security.SecurityEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Local scoring must consume negative known events while leaving positive signals analytics-only. */
class HohenheimSecurityTest {

    private static ThreatScorer scorer() {
        return new ThreatScorer(() -> 1_000_000_000L, () -> 300, () -> 1, () -> 0, () -> 1);
    }

    @Test
    void successfulLoginsNeverEnterTheLocalThreatScorer() {
        ThreatScorer scorer = scorer();
        SecurityEvent event = new SecurityEvent(SecurityEventTypes.AUTH_LOGIN_SUCCEEDED,
            "203.0.113.80", 1_000_000_000L, Map.of());

        HohenheimSecurity.acceptLocalEvent(event, scorer);
        HohenheimSecurity.acceptLocalEvent(event, scorer);

        assertThat(scorer.isOverThreshold("203.0.113.80")).isFalse();
    }

    @Test
    void everyCentrallyClassifiedPositiveKnownEventStaysOutOfTheScorer() {
        String type = "test.challenge_succeeded";
        SecurityEventClassification.registerPositive(type);
        assertThat(KnownSecurityEvents.all()).contains(type);
        ThreatScorer scorer = scorer();

        HohenheimSecurity.acceptLocalEvent(
            new SecurityEvent(type, "203.0.113.81", 1_000_000_000L, null), scorer);
        HohenheimSecurity.acceptLocalEvent(
            new SecurityEvent(type, "203.0.113.81", 1_000_000_001L, null), scorer);

        assertThat(scorer.isOverThreshold("203.0.113.81")).isFalse();
    }

    @Test
    void negativeKnownEventsStillEnterTheLocalThreatScorer() {
        ThreatScorer scorer = scorer();
        SecurityEvent event = new SecurityEvent(SecurityEventTypes.AUTH_LOGIN_FAILED,
            "203.0.113.82", 1_000_000_000L, null);

        HohenheimSecurity.acceptLocalEvent(event, scorer);

        assertThat(scorer.isOverThreshold("203.0.113.82")).isTrue();
    }
}
