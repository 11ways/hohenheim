package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.lease.ClaimedRows;
import be.elevenways.zenit.common.orm.lease.LeaseKeys;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * THE per-instance operation serialization: one claim per instance record that every runtime operation on that
 * record holds for its whole duration, across every controller over the control-plane database.
 *
 * AIDEV-NOTE: this replaced two mechanisms that each looked like a lock and were not. The
 * in-flight SET on InstanceService marked a record but excluded nobody (a second deploy ran
 * beside the first, and the first one's finally cleared the mark while the second still
 * ran); ConvergenceLocks.forApplication excluded converges but not the checkout that
 * precedes them, the drain that follows them, a backup, or a plain stop of the same record.
 * Every verb now asks HERE: deploy/stop/destroy/restart (InstanceService), capture, restore
 * and migration windows, the application's checkout + converge, rollback and drain, and the
 * status reconciler (which only ever takes an IDLE record and skips a busy one).
 *
 * AIDEV-NOTE: the claim is core's {@link ClaimedRows#hold} on {@code instances.claim_fence},
 * replacing the earlier pair of an in-process ReentrantLock and the HOST lease's fence on the
 * row. A client-keyed operation now declares Commands' outside-transaction mode and holds this SAME key; ClaimedRows
 * borrows that command's lease instead of acquiring another one. The hold is taken OUTSIDE any transaction and
 * heartbeats for as long as the operation runs, so it excludes this controller's other threads AND every rival
 * controller per record, which the in-process lock never could; a crashed holder stops heartbeating and the next
 * operation takes the record over after the controller's TTL with a strictly greater fence, so every late write of
 * the crashed holder matches nothing (InstanceOperationGuard writes through {@link #owned} only). The host lease
 * stays the authority to drive a host's daemon; it no longer fences instance rows. Inside a caller's SQLite
 * transaction no lease can be taken: the hold is unleased there and refuses a record a live holder has, so it can
 * never stamp over an operation running outside that transaction.
 *
 * AIDEV-NOTE: the claims are scoped per controller identity ({@link HostLeases#coordinators} and its TTL): a test's
 * second InstanceService over its own HostLeases IS another controller, arbitrated by the stored lease row alone. Two
 * databases in one JVM never share a record's claim: every lease and every hold is per datasource.
 *
 * Lock ORDER, where two are held: an application's key before any of its releases' keys,
 * and a preview's ConvergenceLocks monitor before its preview instance's key. Nothing takes
 * an application key while holding a release or preview key.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstanceOperationLock {

    /**
     * How long a {@link Contention#QUEUE} caller waits before it is refused after all:
     * longer than any sandbox build a converge may be running, short enough that a wedged
     * operation cannot pile up waiting threads forever.
     */
    public static final int QUEUE_SECONDS = 3600;

    /** How a caller meets an operation that is already running on the same record. */
    public enum Contention {

        /**
         * Refuse at once with {@code instance_operation_in_progress}: a person's second
         * click, an API call, a background step whose refusal is recorded.
         */
        REFUSE,

        /**
         * Wait for the running operation (up to {@link #QUEUE_SECONDS}): a forge push of
         * commit N+1 arriving during the release of N must still deploy N+1, and a drain
         * must run after the operation it belongs to, never beside the next one.
         */
        QUEUE;

        /** @return how long a caller of this policy waits for a claim another holder has, in seconds */
        public int waitSeconds() {
            return switch (this) {
                case REFUSE -> 0;
                case QUEUE -> QUEUE_SECONDS;
            };
        }
    }

    /** The lease-key prefix of an instance record's claim. */
    private static final LeaseKeys KEYS = InstanceOperations.KEYS;
    public static final String KEY_PREFIX = KEYS.prefix();

    /** The refusal of a record with no live row to hold, {@link #exclusive}'s answer once another claim ended it. */
    private static final String NOT_FOUND = "instance_not_found";

    private static final Map<HostLeases, InstanceOperationLock> BY_CONTROLLER = new ConcurrentHashMap<>();

    private final @NonNull ClaimedRows<Integer> rows;

    /** The threads waiting for a record's claim, for {@link #isQueued}. */
    private final Map<Integer, Set<Thread>> waiting = new ConcurrentHashMap<>();

    /** The records this controller failed to finish a destroy of. */
    private final UnfinishedDestroys unfinished = new UnfinishedDestroys();

    private InstanceOperationLock(@NonNull HostLeases controller) {
        this.rows = ClaimedRows.of(Models.get(InstanceModel.class), InstanceModel.ID, InstanceModel.CLAIM_FENCE,
                KEYS)
            .coordinatedBy(controller.coordinators())
            .withTtl(controller.ttl());
    }

    /** The claims of one controller identity. */
    public static @NonNull InstanceOperationLock of(@NonNull HostLeases controller) {
        return BY_CONTROLLER.computeIfAbsent(controller, InstanceOperationLock::new);
    }

    /** The claims of this process's production controller identity. */
    public static @NonNull InstanceOperationLock production() {
        return of(HostLeases.production());
    }

    /**
     * Run {@code body} holding the record's claim; re-entrant on the same thread.
     *
     * @throws Violations {@code instance_operation_in_progress} when another operation
     *         holds the record and the contention policy gives up; {@code instance_not_found}
     *         when there is no live record to hold
     */
    public <T> T exclusive(int instanceId, @NonNull Contention contention,
                            @NonNull Supplier<T> body) {
        return exclusive(instanceId, contention, body, false);
    }

    /** Holds a stored record, including a destroyed record whose retained data an operator removes. */
    public <T> T exclusiveStored(int instanceId, @NonNull Contention contention, @NonNull Supplier<T> body) {
        return exclusive(instanceId, contention, body, true);
    }

    private <T> T exclusive(int instanceId, @NonNull Contention contention, @NonNull Supplier<T> body,
                            boolean stored) {
        ClaimedRows<Integer>.Held hold = this.hold(instanceId, contention, stored);
        if (hold == null) {
            throw refusalFor(instanceId);
        }
        try (hold) {
            return body.get();
        }
    }

    /** {@link #exclusive(int, Contention, Supplier)} for a body without a result. */
    public void exclusive(int instanceId, @NonNull Contention contention, @NonNull Runnable body) {
        exclusive(instanceId, contention, () -> {
            body.run();
            return null;
        });
    }

    /**
     * Run {@code body} only when NO operation holds the record right now, this thread's own
     * included -- the reconciler's shape: a record somebody is working on is never "corrected".
     *
     * @return false when the record was busy and nothing ran
     */
    public boolean runIfIdle(int instanceId, @NonNull Runnable body) {
        if (this.rows.heldHere(instanceId) != null) {
            return false;
        }
        ClaimedRows<Integer>.Held hold = this.hold(instanceId, Contention.REFUSE);
        if (hold == null) {
            return false;
        }
        try (hold) {
            body.run();
            return true;
        }
    }

    /**
     * Takes a record no operation holds and, when an earlier destroy left it being destroyed
     * ({@link InstanceModel#isTerminating}) and {@code policy} admits a finish now ({@link UnfinishedDestroys}),
     * finishes that destroy: the destroy's own end, stopped and trashed.
     *
     * @param beforeWrite a test seam run where the end is written (InstanceService's beforeEndWrite)
     * @return whether this ended the record; false when it was held, gone, not being destroyed, or (automatic) not
     *         admitted or failed
     * @throws Violations {@code instance_destroy_unfinished} when a requested end failed, or any failed end inside a
     *         transaction, which must roll back what it wrote
     */
    public boolean finishTerminating(int instanceId, UnfinishedDestroys.@NonNull Policy policy,
                                     @NonNull Runnable beforeWrite) {
        if (this.rows.heldHere(instanceId) != null) {
            return false;
        }
        ClaimedRows<Integer>.Held hold = this.rows.hold(instanceId, 0, InstanceModel.ID.isNotNull(),
            UnaryOperator.identity());
        if (hold == null) {
            return false;
        }
        try (hold) {
            return this.finishClaimed(instanceId, beforeWrite, policy, policy.overridesBudget());
        }
    }

    /** Forgets the unfinished destroys of records another controller finished or that are gone. */
    void pruneUnfinished() {
        this.unfinished.prune();
    }

    /** @return this controller's failed destroy ends */
    @NonNull UnfinishedDestroys unfinished() {
        return this.unfinished;
    }

    /**
     * Finishes the destroy the record this thread just claimed was left in, when it was left in one; first writes the
     * report a held record still owes.
     *
     * @param refuse whether a record left being destroyed refuses the caller when this did not end it
     * @return whether this ended the record
     * @throws Violations {@code instance_destroy_unfinished} when refusing (the end failed, or is held or not due),
     *         and for a failed end inside a transaction
     */
    private boolean finishClaimed(int instanceId, @NonNull Runnable beforeWrite,
                                  UnfinishedDestroys.@NonNull Policy policy, boolean refuse) {
        Row record = this.owned(instanceId).first();
        if (record == null || !InstanceModel.isTerminating(record)) {
            this.unfinished.forgetOnCommit(instanceId);
            return false;
        }
        this.unfinished.report(this, instanceId);
        if (!this.unfinished.admits(instanceId, policy)) {
            if (refuse) {
                throw this.unfinished.refusal(instanceId, record);
            }
            return false;
        }
        boolean ended;
        try {
            ended = InstanceOperationGuard.finishDestroy(this, instanceId, InstanceService::trashFinishing,
                beforeWrite);
        } catch (RuntimeException failure) {
            this.unfinished.failed(instanceId, InstanceModel.nameOf(record),
                InstanceModel.stoppageCauseOf(record) == HohenheimActivityAction.DESTROY_UNFINISHED, failure);
            // Written now when owed (report skips it inside a transaction, which may still roll back).
            this.unfinished.report(this, instanceId);
            // AIDEV-NOTE: the end joins an enclosing transaction (no savepoints), so a failure inside one may have
            // written the stopped status already: it is thrown so that transaction rolls back, never swallowed.
            if (refuse || Models.get(InstanceModel.class).getResolvedDatasource().hasActiveTransaction()) {
                throw this.unfinished.refusal(instanceId, record);
            }
            return false;
        }
        // Ended here, or another claim took the record first: either way it is no longer this controller's to retry.
        this.unfinished.forgetOnCommit(instanceId);
        return ended;
    }

    /**
     * Runs {@code publish} once this thread's operation on the record has let go of it, at once when it holds none:
     * the way an operation states the outcome of its own row (a build, a backup, a deploy record), so a caller that
     * reads the outcome and acts is never refused by the operation it just read about.
     *
     * AIDEV-NOTE: a terminal status written INSIDE the claim was visible before the claim's release, and a client that
     * polled a release until it read succeeded and then rolled back met instance_operation_in_progress from that very
     * release (ApiWireSitesTest on a slow host). The publication runs once the claim was given back and forgotten
     * (zenit Held.afterRelease), and nothing waits for it, so another operation may take the record first: what is
     * published here is the operation's OWN row, which no later operation writes. The record's state is staged
     * instead ({@link #stageOutcome}), through its fence.
     *
     * AIDEV-NOTE: a hold that borrowed an operation command's lease publishes at its own close, inside the command
     * (ClaimedRows), so a zero-wait claimant can still meet the command's lease until the runner releases it after
     * the receipt. Closing that needs InstanceOperationHandlers' PowerResult to stop re-reading the record's status
     * inside the command (statusOf), since there the outcome would not be published yet.
     */
    public void afterRelease(int instanceId, @NonNull Runnable publish) {
        ClaimedRows<Integer>.Held hold = this.rows.heldHere(instanceId);
        if (hold == null) {
            publish.run();
            return;
        }
        hold.afterRelease(publish);
    }

    /**
     * {@link #afterRelease(int, Runnable)} for an operation row that names what it is for: published after the claim
     * of that instance record, at once when the row is for anything else.
     *
     * @param forModel the row's owner model id in its stored spelling
     */
    public void afterRelease(@Nullable String forModel, @Nullable Integer forId, @NonNull Runnable publish) {
        if (forId == null || !InstanceModel.MODEL_ID.toString().equals(forModel)) {
            publish.run();
            return;
        }
        this.afterRelease(forId, publish);
    }

    /**
     * Stages the record's outcome for when this thread's operation let go of it (zenit Held.stage): written through
     * the operation's fence, by its releaser or by an operation of this controller that takes the record first, and
     * dropped when a rival controller's claim won it. An outcome staged earlier on the same hold is written first, so
     * the writes keep their order and each runs once.
     *
     * AIDEV-NOTE: the work that stays inside the claim must still read the record as its own writes left it, so a
     * staged outcome is written early ({@link #settle}) by every next write through InstanceOperationGuard and by a
     * nested operation re-entering the hold; only the last outcome of the operation waits for the release.
     *
     * @throws IllegalStateException when this thread holds no claim on the record
     */
    void stageOutcome(int instanceId, ClaimedRows.@NonNull RowOutcome outcome) {
        this.requireHeld(instanceId).stage(outcome);
    }

    /** Writes the outcome this thread's operation staged on the record now, when there is one. */
    void settle(int instanceId) {
        ClaimedRows<Integer>.Held hold = this.rows.heldHere(instanceId);
        if (hold != null) {
            hold.settle();
        }
    }

    /**
     * Narrows a write to the record this thread's operation still owns: zero rows means the claim was lost (a rival
     * took the record over), and the caller records nothing.
     *
     * @throws IllegalStateException when this thread holds no claim on the record: an outcome write outside its
     *         operation is a wiring defect, never a write without a fence
     */
    @NonNull QueryBuilder<Row> owned(int instanceId) {
        return this.rows.owned(this.requireHeld(instanceId).rowClaim());
    }

    /**
     * The statement narrowed to the record this thread's operation owns now, buildable after the operation let go of
     * it: what a staged outcome writes beyond its own statement (zenit RowOutcome.write) goes through the same claim.
     *
     * @throws IllegalStateException when this thread holds no claim on the record
     */
    @NonNull Supplier<QueryBuilder<Row>> ownedLater(int instanceId) {
        ClaimedRows.RowClaim<Integer> claim = this.requireHeld(instanceId).rowClaim();
        return () -> this.rows.owned(claim);
    }

    /** @throws IllegalStateException when this thread holds no claim on the record */
    private ClaimedRows<Integer>.@NonNull Held requireHeld(int instanceId) {
        ClaimedRows<Integer>.Held hold = this.rows.heldHere(instanceId);
        if (hold == null) {
            throw new IllegalStateException("Instance " + instanceId + " is written outside an operation holding it;"
                + " every outcome write runs inside InstanceOperationLock.exclusive or runIfIdle");
        }
        return hold;
    }

    /** Whether {@code thread} is waiting for the record's claim (a test seam for the ordering proofs). */
    public boolean isQueued(int instanceId, @NonNull Thread thread) {
        Set<Thread> threads = this.waiting.get(instanceId);
        return threads != null && threads.contains(thread);
    }

    /** Whether any thread is waiting for the record's claim (a test seam for the ordering proofs). */
    public boolean isQueued(int instanceId) {
        Set<Thread> threads = this.waiting.get(instanceId);
        return threads != null && !threads.isEmpty();
    }

    private ClaimedRows<Integer>.@Nullable Held hold(int instanceId, @NonNull Contention contention) {
        return hold(instanceId, contention, false);
    }

    private ClaimedRows<Integer>.@Nullable Held hold(int instanceId, @NonNull Contention contention, boolean stored) {
        int wait = contention.waitSeconds();
        Set<Thread> threads = this.waiting.computeIfAbsent(instanceId, ignored -> ConcurrentHashMap.newKeySet());
        Thread current = Thread.currentThread();
        ClaimedRows<Integer>.Held own = this.rows.heldHere(instanceId);
        if (own != null) {
            // A nested operation reads the record as the enclosing one's writes left it.
            own.settle();
        }
        if (wait > 0 && own == null) {
            threads.add(current);
        }
        ClaimedRows<Integer>.Held held;
        try {
            held = this.rows.hold(instanceId, wait, InstanceModel.ID.isNotNull(),
                stored ? query -> query.withTrashed() : UnaryOperator.identity());
        } finally {
            threads.remove(current);
        }
        if (held == null || own != null) {
            return held;
        }
        // A fresh claim of a record an earlier destroy left being destroyed FINISHES that destroy and never runs it: a
        // live operation is then refused as for a missing record, a stored one goes on with the trashed record. One
        // whose end failed, or is not due again yet, is refused by name with why (UnfinishedDestroys).
        boolean finished;
        try {
            finished = this.finishClaimed(instanceId, () -> {}, UnfinishedDestroys.Policy.AUTOMATIC, true);
        } catch (RuntimeException failed) {
            held.close();
            throw failed;
        }
        if (finished && !stored) {
            held.close();
            return null;
        }
        return held;
    }

    private static @NonNull Violations refusalFor(int instanceId) {
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        if (row == null) {
            return notFound(instanceId);
        }
        return HohenheimViolations.instanceRefusal("instance_operation_in_progress", row, null);
    }

    /** @return the refusal of a record with no live row, the one answer every lookup of a missing instance gives */
    static @NonNull Violations notFound(int instanceId) {
        return Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of(NOT_FOUND).withArg("id", instanceId));
    }

    /**
     * Whether {@code refused} is only the refusal of a record with no live row to hold and a fresh read confirms the
     * record is no longer live: a teardown that met it was overtaken by another finish of the same destroy.
     */
    public static boolean refusedAsEnded(@NonNull Violations refused, int instanceId) {
        return !refused.isEmpty()
            && refused.all().stream().allMatch(violation -> NOT_FOUND.equals(violation.message().key()))
            && Models.get(InstanceModel.class).findById(instanceId) == null;
    }
}
