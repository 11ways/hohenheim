package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * THE per-instance operation serialization: one reentrant lock per instance record that
 * every runtime operation on that record holds for its whole duration.
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
 * AIDEV-NOTE: why neither zenit Commands nor a core Leases lease is the mechanism, decided
 * 2026-09-23. Commands runs its body inside ONE database transaction; an instance operation
 * is minutes of daemon work (image pulls, sandbox builds, probe windows), and holding a
 * write transaction that long on SQLite would stall every other write in the controller. A
 * per-record core lease adds nothing the host lease does not already guarantee: HostLeases
 * gives one controller authority over a whole HOST and fences every outcome write, so a
 * rival controller can never make an operation stick, and what was actually missing was
 * exclusion INSIDE one controller. A lease would also refuse outright inside a caller's
 * SQLite transaction (Leases.canAcquireHere), which a zenit-cms mutation can be.
 *
 * AIDEV-NOTE: the lock is scoped per (controller identity, datasource). The controller half
 * keeps the rival-controller simulation honest (a test's second InstanceService over its
 * own HostLeases IS another controller and must reach the host fence, not this lock); the
 * datasource half keeps two databases in one JVM from sharing instance #1's lock.
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
    static final long QUEUE_SECONDS = 3600;

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

    private record Scope(@NonNull Object controller, @NonNull Datasource datasource) {}

    private static final Map<Scope, Map<Integer, ReentrantLock>> LOCKS = new ConcurrentHashMap<>();

    private static final Map<Object, InstanceOperationLock> BY_CONTROLLER = new ConcurrentHashMap<>();

    private final @NonNull Object controller;

    private InstanceOperationLock(@NonNull Object controller) {
        this.controller = controller;
    }

    /** The lock set of one controller identity. */
    public static @NonNull InstanceOperationLock of(@NonNull HostLeases controller) {
        return BY_CONTROLLER.computeIfAbsent(controller, InstanceOperationLock::new);
    }

    /** The lock set of this process's production controller identity. */
    public static @NonNull InstanceOperationLock production() {
        return of(HostLeases.production());
    }

    /**
     * Run {@code body} holding the record's lock; re-entrant on the same thread.
     *
     * @throws Violations {@code instance_operation_in_progress} when another operation
     *         holds the record and the contention policy gives up
     */
    public <T> T exclusive(int instanceId, @NonNull Contention contention,
                           @NonNull Supplier<T> body) {
        ReentrantLock lock = lockOf(instanceId);
        if (!acquire(lock, contention)) {
            throw inProgress(instanceId);
        }
        try {
            return body.get();
        } finally {
            lock.unlock();
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
        ReentrantLock lock = lockOf(instanceId);
        if (lock.isLocked() || !lock.tryLock()) {
            return false;
        }
        try {
            body.run();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Whether any thread of this controller holds the record's lock right now. */
    public boolean isBusy(int instanceId) {
        ReentrantLock lock = scopeLocks().get(instanceId);
        return lock != null && lock.isLocked();
    }

    /** Whether {@code thread} is queued behind the record's lock (a test seam for the ordering proofs). */
    public boolean isQueued(int instanceId, @NonNull Thread thread) {
        ReentrantLock lock = scopeLocks().get(instanceId);
        return lock != null && lock.hasQueuedThread(thread);
    }

    private static boolean acquire(@NonNull ReentrantLock lock, @NonNull Contention contention) {
        return switch (contention) {
            case REFUSE -> lock.tryLock();
            case QUEUE -> {
                try {
                    yield lock.tryLock(QUEUE_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    yield false;
                }
            }
        };
    }

    /**
     * The record's lock in this scope; entries are never evicted, because the key space is
     * bounded by the record count and a lock collected while a thread waits is not a lock.
     */
    private @NonNull ReentrantLock lockOf(int instanceId) {
        return scopeLocks().computeIfAbsent(instanceId, ignored -> new ReentrantLock());
    }

    private @NonNull Map<Integer, ReentrantLock> scopeLocks() {
        return LOCKS.computeIfAbsent(new Scope(this.controller, Db.currentOrDefault()),
            ignored -> new ConcurrentHashMap<>());
    }

    private static @NonNull Violations inProgress(int instanceId) {
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        String name = row != null ? String.valueOf((Object) row.get(InstanceModel.NAME))
            : String.valueOf(instanceId);
        return Violations.ofForm(Microcopy.of("instance_operation_in_progress")
            .withFilter("scope", "violations")
            .withArg("name", name));
    }
}
