package be.elevenways.hohenheim.server.host;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.lease.Lease;
import be.elevenways.zenit.common.orm.lease.Leases;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The controller's identity on a host: one fenced zenit {@link Leases} lease per
 * server, held for the controller's lifetime (heartbeat-renewed) and re-acquired
 * lazily after a loss. The FENCE is the identity every runtime-outcome write is
 * conditional on -- the discipline half of the lease mechanism, which shipped with
 * zero production consumers until here.
 *
 * AIDEV-NOTE: acquisition happens OUTSIDE any transaction by construction of the call
 * sites (CMS row actions and boot paths run untransacted; zenit Leases refuses
 * in-transaction acquisition on SQLite loudly, so a future transacted call site fails
 * fast instead of deadlocking). A lease that was STOLEN (TTL expiry while stalled) is
 * discovered either here via {@link Lease#isHeld()} or by the zero-row guarded write;
 * on discovery the loser drops its hold and ABORTS -- cleanup is the winner's job.
 */
public final class HostLeases {

    static final String KEY_PREFIX = "hohenheim_host_";

    /** Mirrors zenit Leases' default TTL (the field is package-private there). */
    static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private static final HostLeases PRODUCTION = new HostLeases(Leases::of, DEFAULT_TTL);

    private final Function<Datasource, Leases> coordinators;
    private final Duration ttl;
    private final Map<Integer, Hold> held = new ConcurrentHashMap<>();

    private record Hold(@NonNull Leases coordinator, @NonNull Lease lease) {}

    /** The production identity: one per JVM, over the datasource the models resolve. */
    public static @NonNull HostLeases production() {
        return PRODUCTION;
    }

    /**
     * @param coordinators usually {@code Leases::of}; tests pass {@code Leases::independent}
     *                     to be a RIVAL controller arbitrated purely by the stored row
     * @param ttl how long a stall may last before the lease is stealable
     */
    public HostLeases(@NonNull Function<Datasource, Leases> coordinators, @NonNull Duration ttl) {
        this.coordinators = coordinators;
        this.ttl = ttl;
    }

    /** The lease key of one host. */
    public static @NonNull String keyFor(int serverId) {
        return KEY_PREFIX + serverId;
    }

    /**
     * The fence this controller mutates the host under, acquiring (or re-acquiring)
     * the host lease when needed.
     *
     * @throws Violations {@code host_lease_unavailable} when a rival controller holds it
     */
    public long requireFence(int serverId) {
        Lease lease = this.currentLease(serverId, true);
        if (lease == null) {
            throw Violations.ofForm(Microcopy.of("host_lease_unavailable")
                .withFilter("scope", "violations")
                .withArg("server", serverId));
        }
        return lease.fence();
    }

    /** Whether this controller currently holds the host's lease (no acquisition attempt). */
    public boolean isHeld(int serverId) {
        return this.currentLease(serverId, false) != null;
    }

    /**
     * The loser's discovery path: a guarded write matched zero rows, so this
     * controller's authority over the host is gone. Drops the hold (the owner-guarded
     * release cannot touch the winner's row) so the next operation re-arbitrates
     * through a fresh acquisition instead of reusing a dead fence.
     */
    public void fencedOut(int serverId) {
        Hold hold = this.held.remove(serverId);
        if (hold != null) {
            hold.lease().release();
        }
        Blast.slog("hohenheim.host.fenced_out", Map.of(
            "server", serverId,
            "message", "this controller's writes for host " + serverId + " no longer stick;"
                + " a rival controller took the host lease. Aborted without cleanup --"
                + " cleanup is the winner's job."));
    }

    /** Release every held host lease (shutdown). */
    public void releaseAll() {
        for (Integer serverId : this.held.keySet()) {
            Hold hold = this.held.remove(serverId);
            if (hold != null) {
                hold.lease().release();
            }
        }
    }

    private @Nullable Lease currentLease(int serverId, boolean acquire) {
        Datasource datasource = currentDatasource();
        Leases coordinator = this.coordinators.apply(datasource);
        Hold hold = this.held.get(serverId);
        if (hold != null && hold.coordinator() == coordinator && hold.lease().isHeld()) {
            return hold.lease();
        }
        if (hold != null) {
            this.held.remove(serverId, hold);
            try {
                // Stops the heartbeat job; the owner-guarded row update is best-effort
                // (a test datasource may already be gone).
                hold.lease().release();
            } catch (RuntimeException ignored) {
                // the row (or its whole database) no longer exists
            }
        }
        if (!acquire) {
            return null;
        }
        Lease lease = coordinator.acquire(keyFor(serverId), 5, this.ttl);
        if (lease == null) {
            return null;
        }
        this.held.put(serverId, new Hold(coordinator, lease));
        return lease;
    }

    /** The datasource the models resolve right now: the Db scope when one is active. */
    private static @NonNull Datasource currentDatasource() {
        Datasource scoped = Db.current();
        if (scoped != null) {
            return scoped;
        }
        Datasource fallback = Datasources.getDefault();
        if (fallback == null) {
            throw new IllegalStateException(
                "No datasource available for host leases: neither a Db scope nor a default");
        }
        return fallback;
    }
}
