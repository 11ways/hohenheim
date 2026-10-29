package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimRefusalReason;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.zenit.common.orm.lease.Lease;
import be.elevenways.zenit.common.orm.lease.LeaseKeys;
import be.elevenways.zenit.common.orm.lease.Leases;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.List;

/**
 * THE serialization of one managed database's contents: every dump, every restore and every background provisioning
 * of a record runs holding its lease, so a dump never captures a half-restored database, two restores never
 * interleave and a destroy never runs beside a provisioning.
 *
 * AIDEV-NOTE: the lease is core's {@link Leases} on the control-plane database, one key per database record, so it
 * excludes this controller's other threads and every rival controller, heartbeats while the work runs and expires
 * after its TTL when the holder died. It is what serializes a SHARED record, whose dump and restore take no engine
 * instance claim (DatabaseService.underCredentialClaim); a dedicated record holds both. Re-entrant on one thread: an
 * in-place restore holds it across its pre-restore dump and its load.
 *
 * AIDEV-NOTE: lock order. This lease is taken BEFORE a dedicated record's instance claim and before an engine's
 * monitor, never while holding either; its key ranks before the instance keys ({@link LeaseKeys#RANK}, by name).
 *
 * AIDEV-NOTE: only the nightly task and a background provisioning QUEUE ({@link InstanceOperationLock.Contention}).
 * Every other caller (an upload on a request thread, Back up now and a move on the provisioning pool, a restore run, a
 * destroy) REFUSEs at once with {@code database_busy}, so no request thread is parked for the hour a queue may wait.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class DatabaseContentLock {

    private static final LeaseKeys KEYS = LeaseKeys.declare(HohenheimIds.id("database_content"),
        "hohenheim_database_content_");

    private DatabaseContentLock() {
    }

    /**
     * Runs {@code work} holding the database's content lease, waiting behind a running dump or restore of it as
     * {@code contention} says.
     *
     * @throws IOException   what the work throws
     * @throws DomainRefusal {@code database_busy} when the lease stayed taken for the whole wait
     */
    static <T> T exclusive(int databaseId, InstanceOperationLock.@NonNull Contention contention,
                           DatabaseService.@NonNull ThrowingSupplier<T> work) throws IOException {
        Lease lease = leases().acquireReentrant(KEYS.key(databaseId), contention.waitSeconds());
        if (lease == null) {
            throw busy(databaseId);
        }
        try {
            return work.get();
        } finally {
            lease.release();
        }
    }

    /**
     * Refuses now when a dump or restore of the database runs, for work handed to a pool that must answer its caller
     * before it starts; the work itself still refuses a race it loses.
     *
     * @throws DomainRefusal {@code database_busy} while the lease is held, here or by a rival controller
     */
    public static void requireIdle(int databaseId) {
        if (leases().anyHeld(List.of(KEYS.key(databaseId)))) {
            throw busy(databaseId);
        }
    }

    /** @return whether the calling thread holds the database's content lease; what a dump or restore lane asserts */
    public static boolean isHeldHere(int databaseId) {
        return leases().heldBy(KEYS.key(databaseId), Thread.currentThread()) != null;
    }

    /** @return whether a thread of this process waits for the database's content lease; how a test finds a queued job */
    static boolean isAwaited(int databaseId) {
        return leases().isAwaited(KEYS.key(databaseId));
    }

    private static @NonNull DomainRefusal busy(int databaseId) {
        return new DomainRefusal(HohenheimRefusalReason.DATABASE_BUSY,
            "another dump, restore or provisioning of managed database " + databaseId + " is running");
    }

    private static @NonNull Leases leases() {
        return Leases.of(Models.get(DatabaseModel.class).getResolvedDatasource());
    }
}
