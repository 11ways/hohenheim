package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The OPERATOR-REACHABLE half of both isolation sweeps: what they found, published where a
 * person finds it without looking.
 *
 * AIDEV-NOTE: this class exists because both sweeps reported EVERYTHING through
 * {@code Blast.log} and nothing else. Neither ever threw, so every run recorded COMPLETED
 * and {@code AttentionCollector.failedTasks} could not fire for them; neither referenced
 * {@code Alerts}; and there was no isolation collector and no isolation notification event
 * anywhere. "This host's workloads' isolation is UNCONFIRMED" and "containment failed"
 * therefore reached a log file only -- the exact shape the repo rule was written against
 * after the six-day HTTPS outage, applied to a SECURITY BOUNDARY. A getter, a log line or
 * an internal state field is not visibility.
 *
 * Two tiers, because they have different half-lives:
 *  - ESCALATIONS (a workload was cut off, or could not be) alert EVERY run. They are
 *    episodic and self-clearing: a contained instance leaves the live-status filter, so the
 *    next sweep no longer sees it. Suppressing these would be suppressing the one message
 *    that says a tenant just lost availability to protect its neighbours.
 *  - UNCONFIRMED (the sweep could not read the kernel at all, or enforcement is switched
 *    off under running workloads) alerts only on TRANSITION, the HostProbe rule: these
 *    states persist for as long as the misconfiguration does, and a five-minute cron would
 *    turn one of them into 288 identical messages a day, which is how an operator learns to
 *    ignore the channel.
 *
 * BOTH tiers fail the task run, which is what makes the dashboard carry it: the failure
 * lands on the newest history row for the task type and {@code AttentionCollector
 * .failedTasks} projects that as an attention item until a clean run clears it. That half
 * is idempotent by construction and cannot spam anything.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
public final class IsolationFindings {

    /**
     * Last published unconfirmed signature per sweep; empty entry means "currently clean".
     *
     * AIDEV-NOTE: in memory on purpose, and the consequence is DECLARED: a controller
     * restart re-arms the alert. That is the right direction for a security boundary -- a
     * fresh process re-announcing an unresolved unverifiable host costs one message, while
     * persisting the suppression would let a restart silently inherit the silence. Both
     * sweeps are boot-scheduled, so the re-announcement happens immediately, not eventually.
     */
    private static final Map<String, String> LAST_UNCONFIRMED = new ConcurrentHashMap<>();

    private final @NonNull String sweep;
    private final List<String> escalations = new ArrayList<>();
    private final List<String> unconfirmed = new ArrayList<>();

    /** @param sweep the operator-facing name of the sweep, e.g. "Workload isolation" */
    public IsolationFindings(@NonNull String sweep) {
        this.sweep = sweep;
    }

    /** A workload was CONTAINED, or its containment failed: a person must be told. */
    public void escalated(@NonNull String subject, @NonNull List<String> detail) {
        for (String line : detail) {
            this.escalations.add(subject + ": " + line);
        }
    }

    /** The sweep could not confirm isolation here; nothing was stopped for it. */
    public void unconfirmed(@NonNull String subject, @NonNull List<String> detail) {
        this.unconfirmed.add(detail.isEmpty()
            ? subject + ": isolation UNCONFIRMED"
            : subject + ": isolation UNCONFIRMED: " + String.join("; ", detail));
    }

    /** @return whether the sweep found nothing an operator needs to know about */
    public boolean isClean() {
        return this.escalations.isEmpty() && this.unconfirmed.isEmpty();
    }

    /**
     * Send what must be sent, then FAIL the run so the dashboard carries the rest.
     *
     * @throws IsolationUnresolved when anything was reported; the caller is a task executor
     *         and must let it escape
     */
    public void publish() {
        if (isClean()) {
            // A clean sweep re-arms the transition alert, so a problem that comes back
            // announces itself again instead of being swallowed as "already reported".
            LAST_UNCONFIRMED.remove(this.sweep);
            return;
        }

        if (!this.escalations.isEmpty()) {
            alert(this.sweep + ": " + this.escalations.size() + " workload(s) contained",
                String.join("\n", this.escalations));
        }

        String signature = String.join("\n", this.unconfirmed);
        if (!this.unconfirmed.isEmpty()
                && !signature.equals(LAST_UNCONFIRMED.get(this.sweep))) {
            alert(this.sweep + ": isolation UNCONFIRMED on " + this.unconfirmed.size()
                + " subject(s)", signature);
        }
        if (this.unconfirmed.isEmpty()) {
            LAST_UNCONFIRMED.remove(this.sweep);
        } else {
            LAST_UNCONFIRMED.put(this.sweep, signature);
        }

        List<String> everything = new ArrayList<>(this.escalations);
        everything.addAll(this.unconfirmed);
        throw new IsolationUnresolved(this.sweep + " did not come back clean: "
            + String.join(" | ", everything));
    }

    /** An alerting failure must never swallow the isolation failure it was reporting. */
    private void alert(@NonNull String subject, @NonNull String message) {
        try {
            Alerts.send(NotificationEvents.WORKLOAD_ISOLATION, subject, message);
        } catch (RuntimeException notifyFailed) {
            Blast.log("ISOLATION: could not send the isolation notification -",
                notifyFailed.getMessage());
        }
    }

    /**
     * Forget the transition state so a test can observe the first-time alert.
     *
     * AIDEV-NOTE: TEST SEAM and the only one, the WorkloadNetworkPolicy.overrideForTest
     * precedent. Production never calls it; the alternative is a test that passes or fails
     * depending on which class ran before it in a shared JVM.
     */
    public static void forgetTransitionStateForTest(@Nullable String sweep) {
        if (sweep == null) {
            LAST_UNCONFIRMED.clear();
        } else {
            LAST_UNCONFIRMED.remove(sweep);
        }
    }

    /** What a sweep throws when it did not come back clean; the task run records it. */
    public static final class IsolationUnresolved extends RuntimeException {
        IsolationUnresolved(@NonNull String message) {
            super(message);
        }
    }
}
