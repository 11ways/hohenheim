package be.elevenways.hohenheim.server.application;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The intra-process monitors a PREVIEW convergence serializes on: one lock object per
 * (application, ref), so two threads building the same preview queue instead of racing.
 *
 * AIDEV-NOTE: nothing else in the controller can stand in for this, which is why a
 * release converge raced itself for so long. {@code ApplicationReleases.install()} is
 * synchronized on the CLASS and only guards installation; {@code HostLeases} is an
 * inter-CONTROLLER fence acquired once and then held for the process lifetime, so
 * "we hold host X" never means "we are idle on X". The RELEASE lane's application key moved
 * to {@code InstanceOperationLock} (2026-09-23), the one per-record operation lock every
 * instance verb now takes -- the application IS an instance record, so its checkout,
 * converge, rollback, drain and backup serialize there with a stop or destroy of the same
 * record. A preview has no record to key on until its first build mints one, which is why
 * it keeps this monitor.
 *
 * AIDEV-NOTE: the two key spaces are deliberately DISJOINT. A preview builds its own
 * hostname's workload and touches none of the application's release roles, so it keys on
 * (application, ref) and a preview build never blocks a production deploy; the release
 * lane keys on the application alone, because "which release serves" is one
 * application-wide decision. Lock ORDER where both are held: the application's operation
 * lock first (an application delete tears its previews down), then this monitor, then the
 * preview instance's operation lock -- nothing holding a preview monitor takes an
 * application's lock.
 *
 * Entries are never evicted: the key space is bounded by the record count, and a lock
 * that could be collected while a thread waits on it is not a lock.
 */
public final class ConvergenceLocks {

    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    private ConvergenceLocks() {
    }

    /** The monitor every preview deploy/teardown of one (application, ref) serializes on. */
    public static @NonNull Object forPreview(int applicationId, @NonNull String ref) {
        return forKey("preview:" + applicationId + "\n" + ref);
    }

    private static @NonNull Object forKey(@NonNull String key) {
        return LOCKS.computeIfAbsent(key, ignored -> new Object());
    }
}
