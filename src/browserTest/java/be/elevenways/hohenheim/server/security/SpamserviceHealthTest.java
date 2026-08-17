package be.elevenways.hohenheim.server.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure outage-detector tests with an injected clock and recording notifier:
 * transient failures stay silent, sustained failure notifies exactly once,
 * and recovery notifies exactly once.
 */
class SpamserviceHealthTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final List<String> sent = new ArrayList<>();

    private SpamserviceHealth newHealth() {
        return new SpamserviceHealth(now::get,
            (event, subject, message) -> sent.add(event.token()));
    }

    @Test
    void aSingleTransientFailureStaysSilent() {
        SpamserviceHealth health = newHealth();
        health.recordFailure("reputation lookup", "timeout");
        health.recordSuccess("reputation lookup");
        assertThat(sent).isEmpty();
    }

    @Test
    void manyFailuresWithinAShortSpanStaySilent() {
        SpamserviceHealth health = newHealth();
        // 20 consecutive failures, but all within one minute: not sustained yet.
        for (int i = 0; i < 20; i++) {
            health.recordFailure("reputation lookup", "down");
            now.addAndGet(3_000);
        }
        assertThat(sent).isEmpty();
    }

    @Test
    void fewFailuresSpanningLongStaySilent() {
        SpamserviceHealth health = newHealth();
        // Only 3 failures, even over 30 minutes: below the count threshold.
        for (int i = 0; i < 3; i++) {
            health.recordFailure("reputation lookup", "down");
            now.addAndGet(10 * 60_000);
        }
        assertThat(sent).isEmpty();
    }

    @Test
    void sustainedFailureNotifiesOnceAndRecoveryNotifiesOnce() {
        SpamserviceHealth health = newHealth();

        // 5+ consecutive failures spanning 5+ minutes: exactly ONE outage notification.
        for (int i = 0; i < 10; i++) {
            health.recordFailure("reputation lookup", "connection refused");
            now.addAndGet(90_000);
        }
        assertThat(sent).containsExactly("spamservice_outage");

        // A different capability owns an independent outage window.
        for (int i = 0; i < 10; i++) {
            health.recordFailure("report provisioning", "still down");
            now.addAndGet(90_000);
        }
        assertThat(sent).containsExactly("spamservice_outage", "spamservice_outage");

        // Recovery is capability-specific too.
        health.recordSuccess("reputation lookup");
        assertThat(sent).containsExactly("spamservice_outage", "spamservice_outage", "spamservice_recovered");

        // More successes stay silent.
        health.recordSuccess("reputation lookup");
        health.recordSuccess("reputation lookup");
        assertThat(sent).containsExactly("spamservice_outage", "spamservice_outage", "spamservice_recovered");
        health.recordSuccess("report provisioning");
        assertThat(sent).containsExactly("spamservice_outage", "spamservice_outage",
            "spamservice_recovered", "spamservice_recovered");
    }

    @Test
    void aSuccessResetsTheConsecutiveWindow() {
        SpamserviceHealth health = newHealth();
        for (int i = 0; i < 4; i++) {
            health.recordFailure("reputation lookup", "down");
            now.addAndGet(2 * 60_000);
        }
        health.recordSuccess("reputation lookup");
        // The next failures start a fresh window: 4 more (below 5) stay silent
        // even though 8 failures happened over 16+ minutes in total.
        for (int i = 0; i < 4; i++) {
            health.recordFailure("reputation lookup", "down");
            now.addAndGet(2 * 60_000);
        }
        assertThat(sent).isEmpty();

        // And a fifth failure past the span DOES notify.
        health.recordFailure("reputation lookup", "down");
        assertThat(sent).containsExactly("spamservice_outage");
    }

    @Test
    void successInOneCapabilityDoesNotMaskAnotherCapabilityOutage() {
        SpamserviceHealth health = newHealth();
        for (int i = 0; i < 5; i++) {
            health.recordFailure("report provisioning", "down");
            health.recordSuccess("reputation lookup");
            now.addAndGet(90_000);
        }
        assertThat(sent).containsExactly("spamservice_outage");

        health.recordSuccess("reputation lookup");
        assertThat(sent).containsExactly("spamservice_outage");
        health.recordSuccess("report provisioning");
        assertThat(sent).containsExactly("spamservice_outage", "spamservice_recovered");
    }

    @Test
    void aBrokenNotifierNeverThrowsIntoTheFailOpenSeams() {
        SpamserviceHealth health = new SpamserviceHealth(now::get,
            (event, subject, message) -> {
                throw new IllegalStateException("channel broken");
            });
        for (int i = 0; i < 10; i++) {
            health.recordFailure("reputation lookup", "down");
            now.addAndGet(90_000);
        }
        health.recordSuccess("reputation lookup");
        // Reaching here without an exception is the assertion.
    }
}
