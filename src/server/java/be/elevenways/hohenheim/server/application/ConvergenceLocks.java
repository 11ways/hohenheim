package be.elevenways.hohenheim.server.application;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The intra-process monitors a convergence serializes on: one lock object per key, so two
 * threads converging the SAME subject queue instead of racing.
 *
 * AIDEV-NOTE: nothing else in the controller can stand in for this, which is why a
 * release converge raced itself for so long. {@code ApplicationReleases.install()} is
 * synchronized on the CLASS and only guards installation; {@code HostLeases} is an
 * inter-CONTROLLER fence acquired once and then held for the process lifetime, so
 * "we hold host X" never means "we are idle on X"; and {@code InstanceService}'s
 * IN_FLIGHT set is keyed on the RELEASE instance, which a second converge has not minted
 * yet at the moment it reads the serving one.
 *
 * AIDEV-NOTE: the two key spaces are deliberately DISJOINT. A preview builds its own
 * hostname's workload and touches none of the application's release roles, so it keys on
 * (application, ref) and a preview build never blocks a production deploy; the release
 * lane keys on the application alone, because "which release serves" is one
 * application-wide decision. One mechanism, two key spaces, and no lock ordering between
 * them to get wrong.
 *
 * Entries are never evicted: the key space is bounded by the record count, and a lock
 * that could be collected while a thread waits on it is not a lock.
 */
public final class ConvergenceLocks {

    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    private ConvergenceLocks() {
    }

    /** The monitor every release convergence of one application serializes on. */
    public static @NonNull Object forApplication(int applicationId) {
        return forKey("application:" + applicationId);
    }

    /** The monitor every preview deploy/teardown of one (application, ref) serializes on. */
    public static @NonNull Object forPreview(int applicationId, @NonNull String ref) {
        return forKey("preview:" + applicationId + "\n" + ref);
    }

    private static @NonNull Object forKey(@NonNull String key) {
        return LOCKS.computeIfAbsent(key, ignored -> new Object());
    }
}
