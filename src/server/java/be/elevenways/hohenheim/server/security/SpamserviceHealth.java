package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.function.LongSupplier;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sustained-outage detector for the spamservice integration: every fail-open
 * seam (reputation lookups, report provisioning) records its outcome here,
 * and SUSTAINED failure (>= {@value #FAILURE_COUNT_THRESHOLD} consecutive
 * failures spanning >= 5 minutes) produces exactly ONE outage notification;
 * the first success afterwards produces exactly ONE recovery notification.
 * Transient blips stay silent -- this exists to make silent degradation loud,
 * not to stream noise.
 */
public final class SpamserviceHealth {

    public static final SpamserviceHealth INSTANCE = new SpamserviceHealth();

    // Swappable handle so tests can observe the seams' wiring.
    private static volatile SpamserviceHealth active = INSTANCE;

    static final int FAILURE_COUNT_THRESHOLD = 5;
    static final long FAILURE_SPAN_MS = 5 * 60_000;

    private final LongSupplier clock;
    private final SecurityNotifier notifier;

    private final Object lock = new Object();
    private final Map<String, CapabilityState> capabilities = new LinkedHashMap<>();

    private SpamserviceHealth() {
        this(System::currentTimeMillis, Alerts::send);
    }

    /** Test constructor: inject the clock and the notification sink. */
    SpamserviceHealth(@NonNull LongSupplier clock, @NonNull SecurityNotifier notifier) {
        this.clock = clock;
        this.notifier = notifier;
    }

    /** The detector the fail-open seams report into. */
    public static @NonNull SpamserviceHealth active() {
        return active;
    }

    /** Test seam: replace the active detector (null restores the real one). */
    static void setActive(@Nullable SpamserviceHealth replacement) {
        active = replacement != null ? replacement : INSTANCE;
    }

    /**
     * Record one failed spamservice interaction.
     *
     * @param source which seam failed (e.g. "reputation lookup", "report provisioning")
     */
    public void recordFailure(@NonNull String source, @Nullable String detail) {
        boolean notifyOutage = false;
        long failures;
        long spanMs = 0;
        synchronized (this.lock) {
            CapabilityState state = this.capabilities.computeIfAbsent(source, ignored -> new CapabilityState());
            long now = this.clock.getAsLong();
            if (state.consecutiveFailures == 0) {
                state.firstFailureAtMs = now;
            }
            state.consecutiveFailures++;
            failures = state.consecutiveFailures;
            if (!state.outageNotified
                    && state.consecutiveFailures >= FAILURE_COUNT_THRESHOLD
                    && now - state.firstFailureAtMs >= FAILURE_SPAN_MS) {
                state.outageNotified = true;
                notifyOutage = true;
                spanMs = now - state.firstFailureAtMs;
            }
        }
        if (notifyOutage) {
            notify(NotificationEvents.SPAMSERVICE_OUTAGE, "Spamservice unreachable",
                "Spamservice has been failing for " + (spanMs / 60_000) + " minute(s) ("
                    + failures + " consecutive failures; last: " + source
                    + (detail != null ? " - " + detail : "")
                    + "). Reputation bans and security-event provisioning are degraded"
                    + " (fail-open) until it recovers.");
        }
    }

    /** Record one successful spamservice interaction; ends a notified outage. */
    public void recordSuccess(@NonNull String source) {
        boolean notifyRecovery = false;
        synchronized (this.lock) {
            CapabilityState state = this.capabilities.computeIfAbsent(source, ignored -> new CapabilityState());
            if (state.outageNotified) {
                notifyRecovery = true;
            }
            state.consecutiveFailures = 0;
            state.firstFailureAtMs = 0;
            state.outageNotified = false;
        }
        if (notifyRecovery) {
            notify(NotificationEvents.SPAMSERVICE_RECOVERED, "Spamservice recovered",
                "Spamservice " + source + " calls are succeeding again; reputation bans and"
                    + " security-event provisioning are back to normal.");
        }
    }

    private void notify(@NonNull NotificationEvents event, @NonNull String subject, @NonNull String message) {
        try {
            this.notifier.send(event, subject, message);
        } catch (RuntimeException e) {
            // A broken notification channel must never break the fail-open seams.
            Blast.log("SECURITY: could not send", event, "notification -", e.getMessage());
        }
    }

    /** Test seam: forget all outage state. */
    void resetForTests() {
        synchronized (this.lock) {
            this.capabilities.clear();
        }
    }

    private static final class CapabilityState {
        private long consecutiveFailures;
        private long firstFailureAtMs;
        private boolean outageNotified;
    }
}
