package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.time.Backoff;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One controller's destroys whose end failed: spaces the automatic retries of each record by {@link #BACKOFF} and,
 * after {@link #AUTOMATIC_ATTEMPTS} failures, holds it and records why on the record itself.
 *
 * AIDEV-NOTE: the count and the due time live in memory on purpose, keyed like ClaimedRows' claims (datasource and
 * id). The durable half is the record's own stoppage ({@link HohenheimActivityAction#DESTROY_UNFINISHED}): a
 * restarted controller reads it and holds the record again after ONE more automatic attempt (the boot recovery is
 * itself a retry), so no migration carries a counter. Holding a record and REPORTING it are two facts: the report
 * (stoppage and history) is owed until a claim outside any transaction wrote it, and every later claim of the record
 * retries the report without retrying the end. Only a {@link Policy#REQUESTED} finish (an operator's Delete) retries
 * a held record; everything else is refused by name with the reason.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class UnfinishedDestroys {

    /** The wait after each failed finish before an automatic one runs again. */
    public static final Backoff BACKOFF = Backoff.exponential(Duration.ofMinutes(1), 4, Duration.ofHours(1));

    /** The failed finishes after which no automatic one runs again. */
    public static final int AUTOMATIC_ATTEMPTS = 5;

    /** Who asks for the end of a record left being destroyed. */
    public enum Policy {

        /** An operator's Delete: it runs whatever the backoff says, also on a held record. */
        REQUESTED,

        /** Everything else (a sweep, the boot, a background teardown): it runs only when due and not held. */
        AUTOMATIC;

        /** @return whether this finish runs even where the backoff or the bound would hold it back */
        boolean overridesBudget() {
            return switch (this) {
                case REQUESTED -> true;
                case AUTOMATIC -> false;
            };
        }
    }

    private final Map<List<Object>, Failures> failures = new ConcurrentHashMap<>();

    UnfinishedDestroys() {
    }

    /** @return whether a finish under {@code policy} may run now: requested, never failed here, or due and not held */
    boolean admits(int instanceId, @NonNull Policy policy) {
        if (policy.overridesBudget()) {
            return true;
        }
        Failures failed = this.failures.get(key(instanceId));
        return failed == null || failed.due();
    }

    /**
     * The record's end was written, or the record is no longer this controller's to end: its failures go once the
     * calling thread's transaction commits (at once outside one), so a rollback keeps its budget, hold and report.
     *
     * AIDEV-NOTE: the removal is a compare-and-remove of the failures counted when it was asked for, never of the key:
     * between this transaction's commit and its after-commit hook another thread may destroy the record again and
     * fail its end, and that newer failure (a new entry, or one more on this one) must keep its backoff.
     */
    void forgetOnCommit(int instanceId) {
        List<Object> key = key(instanceId);
        Observed observed = Observed.of(this.failures.get(key));
        if (observed == null) {
            return;
        }
        Models.get(InstanceModel.class).getResolvedDatasource().afterCommit(() -> this.forget(key, observed));
    }

    /**
     * Counts a failed end and logs it; the failure that reaches the bound (or the first one after a restart, for a
     * record held before it) holds the record and owes its report ({@link #report}). Needs no claim.
     *
     * @param heldBefore whether the record already carries the {@code destroy_unfinished} stoppage
     */
    void failed(int instanceId, @NonNull Object name, boolean heldBefore, @NonNull RuntimeException failure) {
        String reason = HohenheimViolations.reasonOf(failure);
        Failures.Counted[] counted = new Failures.Counted[1];
        // Counted inside the map's own update, so a concurrent forget never removes the entry between its lookup
        // and this count.
        this.failures.compute(key(instanceId), (key, current) -> {
            Failures failures = current != null ? current : new Failures();
            counted[0] = failures.count(reason, heldBefore);
            return failures;
        });
        if (counted[0].heldNow()) {
            Blast.log("INSTANCE: gave up finishing the destroy of", name, "(" + instanceId + ") after",
                counted[0].attempts(), "failed attempts -", reason, "- an operator's Delete retries it");
        } else if (!counted[0].held()) {
            Blast.log("INSTANCE: could not finish the destroy of", name, "(" + instanceId + "), attempt",
                counted[0].attempts(), "of", AUTOMATIC_ATTEMPTS, "-", reason, "- retried after", counted[0].delay());
        }
    }

    /**
     * Writes the report a held record still owes (its stoppage and history fact) through the claim this thread
     * holds; never inside a transaction, which a failure may still roll back. A failed write stays owed for the next
     * claim and is logged once.
     */
    void report(@NonNull InstanceOperationLock lock, int instanceId) {
        Failures failed = this.failures.get(key(instanceId));
        String reason = failed == null ? null : failed.owedReport();
        if (reason == null || Models.get(InstanceModel.class).getResolvedDatasource().hasActiveTransaction()) {
            return;
        }
        try {
            if (InstanceOperationGuard.stampDestroyUnfinished(lock, instanceId, reason)) {
                failed.reported();
            }
        } catch (RuntimeException unrecorded) {
            if (failed.firstReportFailure()) {
                Blast.log("INSTANCE: could not yet record why the destroy of", instanceId,
                    "is unfinished; the next claim retries it -", unrecorded.getMessage());
            }
        }
    }

    /**
     * @param stored the reason stored on the record, used when none was counted here
     * @return the refusal of an operation on a record left being destroyed whose end failed
     */
    @NonNull Violations refusal(int instanceId, @NonNull Object name, @Nullable String stored) {
        Failures failed = this.failures.get(key(instanceId));
        String reason = failed != null ? failed.reason() : null;
        return Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("instance_destroy_unfinished")
            .withArg("name", String.valueOf(name))
            .withArg("reason", String.valueOf(reason != null ? reason : stored)));
    }

    /** @return {@link #refusal(int, Object, String)} for a record as read */
    @NonNull Violations refusal(int instanceId, @NonNull Row record) {
        return this.refusal(instanceId, InstanceModel.nameOf(record), record.get(InstanceModel.STOPPAGE_DETAIL));
    }

    /**
     * Forgets the records of the current datasource that are no longer live and being destroyed: finished, trashed
     * or deleted by another controller, which no claim of this one will ever meet again. Never inside a transaction,
     * whose uncommitted reads could forget a record its rollback restores.
     */
    void prune() {
        Datasource datasource = Models.get(InstanceModel.class).getResolvedDatasource();
        if (datasource.hasActiveTransaction()) {
            return;
        }
        Map<Integer, Observed> observed = new HashMap<>();
        this.failures.forEach((key, failed) -> {
            if (key.get(0).equals(datasource)) {
                observed.put((Integer) key.get(1), new Observed(failed, failed.generation()));
            }
        });
        if (observed.isEmpty()) {
            return;
        }
        Set<Integer> live = new HashSet<>();
        for (Row row : Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.in(List.copyOf(observed.keySet())))
                .and(InstanceModel.STATUS.in(InstanceModel.TERMINATING_STATUSES))
                .all()) {
            live.add(row.get(InstanceModel.ID));
        }
        observed.forEach((id, failed) -> {
            if (!live.contains(id)) {
                this.forget(List.of(datasource, id), failed);
            }
        });
    }

    /** Removes the record's failures when they are still exactly the ones {@code observed}: no failure since. */
    private void forget(@NonNull List<Object> key, @NonNull Observed observed) {
        this.failures.computeIfPresent(key, (ignored, current) -> observed.isStill(current) ? null : current);
    }

    private static @NonNull List<Object> key(int instanceId) {
        return List.of(Models.get(InstanceModel.class).getResolvedDatasource(), instanceId);
    }

    /** One record's failures as read at one moment: the entry and how many failures it had counted then. */
    private record Observed(@NonNull Failures failures, long generation) {

        /** @return the observation of {@code failures}, null when there are none */
        static @Nullable Observed of(@Nullable Failures failures) {
            return failures == null ? null : new Observed(failures, failures.generation());
        }

        /** @return whether {@code current} is the observed entry with no failure counted since */
        boolean isStill(@NonNull Failures current) {
            return current == this.failures && current.generation() == this.generation;
        }
    }

    /** One record's failed finishes since its last success. */
    private static final class Failures {

        private final Backoff.Attempts attempts = BACKOFF.attempts();
        private @Nullable Instant nextAt;
        private @Nullable String reason;
        private boolean held;

        /** The failures counted on this entry, ever; a forget compares it ({@link Observed}). */
        private long generation;
        private boolean reportOwed;
        private boolean reportFailureLogged;

        /**
         * @param heldBefore whether the record was held before this controller counted it (a restart since)
         * @return this failure, counted
         */
        synchronized @NonNull Counted count(@NonNull String failure, boolean heldBefore) {
            this.generation++;
            Duration delay = this.attempts.next(null);
            this.nextAt = Now.instant().plus(delay);
            this.reason = failure;
            boolean heldNow = !this.held && (heldBefore || this.attempts.failed() >= AUTOMATIC_ATTEMPTS);
            if (heldNow) {
                this.held = true;
                this.reportOwed = true;
            }
            return new Counted(this.attempts.failed(), delay, heldNow, this.held);
        }

        synchronized boolean due() {
            return !this.held && (this.nextAt == null || !Now.instant().isBefore(this.nextAt));
        }

        synchronized @Nullable String reason() {
            return this.reason;
        }

        synchronized long generation() {
            return this.generation;
        }

        /** @return the reason to report, null when no report is owed */
        synchronized @Nullable String owedReport() {
            return this.reportOwed ? this.reason : null;
        }

        synchronized void reported() {
            this.reportOwed = false;
        }

        /** @return whether this is the first failed report, which alone is logged */
        synchronized boolean firstReportFailure() {
            boolean first = !this.reportFailureLogged;
            this.reportFailureLogged = true;
            return first;
        }

        /**
         * @param heldNow whether this failure held the record
         * @param held    whether the record is held after it
         */
        record Counted(int attempts, @NonNull Duration delay, boolean heldNow, boolean held) {
        }
    }
}
