package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.task.TaskContext;
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
 * AIDEV-NOTE: two readings of one finding. The failure's MESSAGE is what the dashboard shows, so it is worded
 * through copy (scope {@code isolation_finding}) and stored resolved in the installation's content locale, like
 * every stored reason. The raw lines ("<host>: isolation UNCONFIRMED: ...") are the machine reading that alerts and
 * tests key on: they ride the exception ({@link IsolationUnresolved#findings()}), the alert body and the run's own
 * reports ({@link #publish(TaskContext)}), never the shown sentence.
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
    /** The same findings in words, one sentence each, for the run's shown failure. */
    private final List<Microcopy> said = new ArrayList<>();

    /** @param sweep the operator-facing name of the sweep, e.g. "Workload isolation" */
    public IsolationFindings(@NonNull String sweep) {
        this.sweep = sweep;
    }

    /** Workloads whose isolation could not be checked or repaired, or not contained: a person must be told. */
    public void escalated(@NonNull String subject, @NonNull List<String> detail) {
        for (String line : detail) {
            this.escalations.add(subject + ": " + line);
        }
        if (!detail.isEmpty()) {
            this.said.add(HohenheimMicrocopy.ISOLATION_FINDING.of("not_repaired").withArg("subject", subject)
                .withArg("count", detail.size()));
        }
    }

    /** The sweep could not confirm isolation here, for no reason it can word; nothing was stopped for it. */
    public void unconfirmed(@NonNull String subject, @NonNull List<String> detail) {
        unconfirmed(subject, detail, null);
    }

    /**
     * The sweep could not confirm isolation here; nothing was stopped for it.
     *
     * @param why why, in words ({@link #enforcementOff}, {@link #daemonUnreachable}, {@link #noFirewallLane}); null
     *            when the sweep cannot word it
     */
    public void unconfirmed(@NonNull String subject, @NonNull List<String> detail, @Nullable Microcopy why) {
        this.unconfirmed.add(detail.isEmpty()
            ? subject + ": isolation UNCONFIRMED"
            : subject + ": isolation UNCONFIRMED: " + String.join("; ", detail));
        this.said.add(why == null ? HohenheimMicrocopy.ISOLATION_FINDING.of("unconfirmed").withArg("subject", subject)
            : HohenheimMicrocopy.ISOLATION_FINDING.of("unconfirmed_because").withArg("subject", subject)
                .withArg("reason", why));
    }

    /** @return why a host cannot be checked: per-workload enforcement is switched off over this many networks */
    public static @NonNull Microcopy enforcementOff(int networks) {
        return HohenheimMicrocopy.ISOLATION_FINDING.of("enforcement_off").withArg("count", networks);
    }

    /** @return why a host cannot be checked: its container daemon cannot be reached */
    public static @NonNull Microcopy daemonUnreachable() {
        return HohenheimMicrocopy.ISOLATION_FINDING.of("daemon_unreachable");
    }

    /** @return why a host cannot be checked: nothing reads the firewall of the machine its daemon runs on */
    public static @NonNull Microcopy noFirewallLane() {
        return HohenheimMicrocopy.ISOLATION_FINDING.of("no_firewall_lane");
    }

    /**
     * One host's sweep outcome. An unverifiable host is UNCONFIRMED; on a verifiable one the sweep DID read the kernel,
     * so every workload it cut off and every error left is the security-consequential half and escalates every run.
     *
     * @param tag             the sweep's log prefix
     * @param unverifiableWhy why a host of this sweep cannot be verified, for the log
     * @param why             the same in words, null when the sweep cannot word it
     * @param cutLabel        how the log names the workloads the sweep cut off (STOPPED, CONTAINED)
     */
    public void host(@NonNull String tag, @NonNull String unverifiableWhy, @Nullable Microcopy why,
                     @NonNull String server, boolean verifiable, @NonNull List<String> enforced,
                     @NonNull List<String> repaired, @NonNull String cutLabel, @NonNull List<String> cut,
                     @NonNull List<String> errors) {
        if (!verifiable) {
            Blast.log(tag, server, "cannot be kernel-verified" + unverifiableWhy
                + "; its workloads' isolation is UNCONFIRMED:", errors);
            this.unconfirmed(server, errors, why);
            return;
        }
        if (!repaired.isEmpty() || !cut.isEmpty() || !errors.isEmpty()) {
            Blast.log(tag, server, "- enforced", enforced.size(), ", repaired", repaired, ", " + cutLabel, cut,
                ", errors", errors);
        }
        for (String line : cut) {
            this.escalations.add(server + ": " + line);
        }
        if (!cut.isEmpty()) {
            this.said.add(HohenheimMicrocopy.ISOLATION_FINDING.of("cut_off").withArg("subject", server)
                .withArg("count", cut.size()));
        }
        this.escalated(server, errors);
    }

    /** @return whether the sweep found nothing an operator needs to know about */
    public boolean isClean() {
        return this.escalations.isEmpty() && this.unconfirmed.isEmpty();
    }

    /**
     * {@link #publish()} from a task executor: the raw findings first land in the run's own reports, the machine
     * reading beside the worded failure.
     *
     * @throws IsolationUnresolved when anything was reported
     */
    public void publish(@NonNull TaskContext ctx) {
        for (String line : this.escalations) {
            ctx.report(line);
        }
        for (String line : this.unconfirmed) {
            ctx.report(line);
        }
        publish();
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
            alert(HohenheimMicrocopy.ALERT.of("isolation_contained_subject")
                    .withArg("sweep", this.sweep).withArg("count", this.escalations.size()),
                String.join("\n", this.escalations));
        }

        String signature = String.join("\n", this.unconfirmed);
        if (!this.unconfirmed.isEmpty()
                && !signature.equals(LAST_UNCONFIRMED.get(this.sweep))) {
            alert(HohenheimMicrocopy.ALERT.of("isolation_unconfirmed_subject")
                    .withArg("sweep", this.sweep).withArg("count", this.unconfirmed.size()),
                signature);
        }
        if (this.unconfirmed.isEmpty()) {
            LAST_UNCONFIRMED.remove(this.sweep);
        } else {
            LAST_UNCONFIRMED.put(this.sweep, signature);
        }

        List<String> everything = new ArrayList<>(this.escalations);
        everything.addAll(this.unconfirmed);
        List<String> sentences = new ArrayList<>();
        for (Microcopy sentence : this.said) {
            sentences.add(HohenheimViolations.textOf(sentence));
        }
        throw new IsolationUnresolved(String.join(". ", sentences), everything);
    }

    /** An alerting failure must never swallow the isolation failure it was reporting. */
    private void alert(@NonNull Microcopy subject, @NonNull String detail) {
        // The sweep's sentences are the words; the raw lines (their UNCONFIRMED token) stay the detail after them.
        Alerts.trySend(NotificationEvents.WORKLOAD_ISOLATION, "isolation#" + this.sweep, subject,
            HohenheimMicrocopy.ALERT.of("isolation_body").withArg("said", List.copyOf(this.said))
                .withArg("detail", detail));
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

    /**
     * What a sweep throws when it did not come back clean; the task run records it. Its message is the findings in
     * words, its {@link #findings()} the raw lines.
     */
    public static final class IsolationUnresolved extends RuntimeException {

        private final @NonNull List<String> findings;

        IsolationUnresolved(@NonNull String message, @NonNull List<String> findings) {
            super(message);
            this.findings = List.copyOf(findings);
        }

        /** @return the raw finding lines ("<host>: isolation UNCONFIRMED: ..."), escalations first */
        public @NonNull List<String> findings() {
            return this.findings;
        }
    }
}
