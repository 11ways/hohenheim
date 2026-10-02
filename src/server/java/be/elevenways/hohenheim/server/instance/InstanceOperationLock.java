package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.InstanceModel;
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
 * AIDEV-NOTE: the claim is core's {@link ClaimedRows#hold} on {@code instances.claim_fence}, decided 2026-10-02
 * (module-fit item 35), replacing the 2026-09-23 pair of an in-process ReentrantLock and the HOST lease's fence on the
 * row. Why it is not zenit Commands still holds: Commands runs its body inside ONE transaction, and an instance
 * operation is minutes of daemon work. Why it is now a lease: the hold is taken OUTSIDE any transaction and
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
    static final int QUEUE_SECONDS = 3600;

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
        QUEUE
    }

    /** The lease-key prefix of an instance record's claim. */
    private static final LeaseKeys KEYS = LeaseKeys.declare(HohenheimIds.id("instance"), "hohenheim_instance_");
    public static final String KEY_PREFIX = KEYS.prefix();

    private static final Map<HostLeases, InstanceOperationLock> BY_CONTROLLER = new ConcurrentHashMap<>();

    private final @NonNull ClaimedRows<Integer> rows;

    /** The threads waiting for a record's claim, for {@link #isQueued}. */
    private final Map<Integer, Set<Thread>> waiting = new ConcurrentHashMap<>();

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
        ClaimedRows<Integer>.Held hold = this.hold(instanceId, contention);
        if (hold == null) {
            throw refusalFor(instanceId);
        }
        try {
            return body.get();
        } finally {
            hold.close();
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
        try {
            body.run();
            return true;
        } finally {
            hold.close();
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
        ClaimedRows<Integer>.Held hold = this.rows.heldHere(instanceId);
        if (hold == null) {
            throw new IllegalStateException("Instance " + instanceId + " is written outside an operation holding it;"
                + " every outcome write runs inside InstanceOperationLock.exclusive or runIfIdle");
        }
        return this.rows.owned(hold.rowClaim());
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
        int wait = switch (contention) {
            case REFUSE -> 0;
            case QUEUE -> QUEUE_SECONDS;
        };
        Set<Thread> threads = this.waiting.computeIfAbsent(instanceId, ignored -> ConcurrentHashMap.newKeySet());
        Thread current = Thread.currentThread();
        if (wait > 0 && this.rows.heldHere(instanceId) == null) {
            threads.add(current);
        }
        try {
            return this.rows.hold(instanceId, wait, InstanceModel.ID.isNotNull(), UnaryOperator.identity());
        } finally {
            threads.remove(current);
        }
    }

    private static @NonNull Violations refusalFor(int instanceId) {
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        if (row == null) {
            return Violations.ofForm(HohenheimViolations.text("instance_not_found").withArg("id", instanceId));
        }
        return Violations.ofForm(HohenheimViolations.text("instance_operation_in_progress")
            .withArg("name", String.valueOf((Object) row.get(InstanceModel.NAME))));
    }
}
