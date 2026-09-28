package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.server.proxy.auth.ProxyAuthThrottle;
import be.elevenways.hohenheim.server.source.GitWebhookHandler;
import be.elevenways.zenit.common.security.KnownSecurityEvents;
import be.elevenways.zenit.common.security.SecurityEventTypes;
import be.elevenways.zenit.server.security.SecurityEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * A RATE_LIMITED refusal weighs what its refusing policy is worth: a forge hammering its
     * webhook never bans itself, a shared overflow refusal never bans anyone, while proxy-auth
     * refusals (credential guessing) and a policy nobody classified still ban.
     */
    @Test
    void rateLimitedRefusalsAreScoredByTheirRefusingPolicy() {
        ThreatScorer scorer = new ThreatScorer(() -> 1_000_000_000L, () -> 300, () -> 25,
            () -> 0, () -> 1);
        List<String> banned = new ArrayList<>();
        scorer.setAutoBanTrigger((ip, type, score) -> banned.add(ip));

        // 1. Two hundred webhook refusals from one forge address: never a ban.
        for (int i = 0; i < 200; i++) {
            HohenheimSecurity.acceptLocalEvent(rateLimited("140.82.112.1",
                Map.of(SecurityEventTypes.RATE_LIMITED_POLICY,
                    GitWebhookHandler.POLICY.bucketName())), scorer);
        }
        assertThat(banned).as("step 1: webhook refusals score nothing").isEmpty();
        assertThat(scorer.isOverThreshold("140.82.112.1"))
            .as("step 1: and leave the forge address unscored").isFalse();

        // 2. A refusal spent from the shared overflow bucket scores nothing, even under the
        //    credential-guessing policy.
        for (int i = 0; i < 200; i++) {
            HohenheimSecurity.acceptLocalEvent(rateLimited("198.51.100.7", Map.of(
                SecurityEventTypes.RATE_LIMITED_POLICY, ProxyAuthThrottle.POLICY.bucketName(),
                SecurityEventTypes.RATE_LIMITED_SHARED_BUDGET, "true")), scorer);
        }
        assertThat(banned).as("step 2: a shared-budget refusal bans nobody").isEmpty();

        // 3. Proxy-auth refusals keep their weight: guessing crosses the threshold.
        for (int i = 0; i < 30; i++) {
            HohenheimSecurity.acceptLocalEvent(rateLimited("203.0.113.90",
                Map.of(SecurityEventTypes.RATE_LIMITED_POLICY,
                    ProxyAuthThrottle.POLICY.bucketName())), scorer);
        }
        assertThat(banned).as("step 3: proxy-auth refusals still ban").contains("203.0.113.90");

        // 4. An unclassified policy fails closed toward banning, as does a bare refusal.
        for (int i = 0; i < 30; i++) {
            HohenheimSecurity.acceptLocalEvent(rateLimited("203.0.113.91",
                Map.of(SecurityEventTypes.RATE_LIMITED_POLICY, "someone.else")), scorer);
            HohenheimSecurity.acceptLocalEvent(rateLimited("203.0.113.92", null), scorer);
        }
        assertThat(banned).as("step 4: an unknown or unnamed policy keeps the type's weight")
            .contains("203.0.113.91", "203.0.113.92");
    }

    private static SecurityEvent rateLimited(String ip, Map<String, String> detail) {
        return new SecurityEvent(SecurityEventTypes.RATE_LIMITED, ip, 1_000_000_000L, detail);
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
