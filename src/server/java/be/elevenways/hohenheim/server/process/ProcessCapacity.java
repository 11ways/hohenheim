package be.elevenways.hohenheim.server.process;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.QuotaExceeded;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Host memory booking for MANAGED CHILD PROCESSES: the {@link InstanceCapacity} contract
 * against the same host budget, but booked per CHILD instead of per record.
 *
 * AIDEV-NOTE: the cardinality is the entire reason this is not the instance tier. One
 * instance record is one workload handle; one process SITE runs N children, scaled up and
 * down on a 15s CPU timer with a crash loop of five exits in twelve seconds. Booking a
 * site record would either charge for capacity that is not running or churn a record's
 * charge at that cadence, so the unit that is booked here is the unit that actually
 * exists: one spawned child, charged in {@code startProcessOnce} beside its port
 * allocation and released on every exit path beside it. The auto-scaler therefore stops
 * at the HOST budget instead of at its own hard cap of five.
 *
 * AIDEV-NOTE: a child is booked ONLY when its site declares {@code memory_limit_mb}, and
 * then it is also cgroup-capped at exactly that number ({@link
 * be.elevenways.hohenheim.server.ProcessConfinement}) or refused. Charge == cap, the
 * invariant {@code InstanceKindHandler.defaultFootprintMb} names. A site with no declared
 * limit books nothing, because a booking it cannot enforce is the paper limit this whole
 * gate exists to refuse.
 *
 * AIDEV-NOTE: a SEPARATE bucket from the instance tier's, and the two gates subtract each
 * other's usage rather than sharing one counter. The reason is recovery, not taste: a
 * controller that is killed leaks every process charge it holds (the children die with the
 * host or are reaped by {@link ProcessReaper}, but no exit path ran), so this bucket must
 * be resettable at boot -- and a shared counter cannot be reset without destroying the
 * instance charges, which are RECORD-derived and must survive. The cost is that the two
 * tiers' admissions are not atomic against each other: two simultaneous admissions can
 * over-book the host by one workload. That window is bounded, it is the same class of
 * looseness {@code Quotas} already documents for a concurrent limit change, and it is
 * strictly better than a budget that shrinks permanently after every crash.
 */
public final class ProcessCapacity {

    /** Consumer-namespaced bucket prefix; the host id follows it. */
    static final String BUCKET_PREFIX = "hohenheim:host_procmem_mb:";

    private ProcessCapacity() {
    }

    /** The process-memory bucket of one host. */
    public static @NonNull String bucketOf(int serverId) {
        return BUCKET_PREFIX + serverId;
    }

    /** How much child-process memory (MB) is currently booked on a host. */
    public static long bookedMbOn(int serverId) {
        return Quotas.usedOf(bucketOf(serverId));
    }

    /**
     * Book one child's declared memory on the local host.
     *
     * @param amountMb the child's declared (and cgroup-enforced) cap; 0 books nothing
     * @throws Violations {@code host_capacity_reached} naming the host, the request and
     *                    what is free
     */
    public static void reserve(int serverId, int amountMb) {
        if (amountMb <= 0) {
            return;
        }
        Row server = Models.get(ServerModel.class).findById(serverId);
        Long budget = server == null ? null : InstanceCapacity.budgetMbOf(server);
        if (budget == null) {
            // Same scope decision as InstanceCapacity.reserve: an unmeasured host still
            // COUNTS its usage (so the ledger is honest the moment a preflight lands), it
            // is simply judged against nothing.
            Quotas.reserve(bucketOf(serverId), amountMb, Long.MAX_VALUE);
            return;
        }
        long headroom = Math.max(0, budget - InstanceCapacity.bookedMbOn(serverId));
        try {
            Quotas.reserve(bucketOf(serverId), amountMb, headroom);
        } catch (QuotaExceeded full) {
            throw Violations.ofForm(violation("host_capacity_reached")
                .withArg("name", InstanceCapacity.hostLabel(serverId))
                .withArg("needed", amountMb)
                .withArg("free", Math.max(0, full.getLimit() - full.getUsed())));
        }
    }

    /** Hand one child's booking back; a non-positive amount is a no-op. */
    public static void release(int serverId, int amountMb) {
        if (amountMb > 0) {
            Quotas.release(bucketOf(serverId), amountMb);
        }
    }

    /**
     * Drop every process booking held on this host, because none of the children they
     * were taken for is still running.
     *
     * <p>Called at boot AFTER {@link ProcessReaper} has proven that: a charge whose child
     * outlived the controller must not be freed while the child is still holding the
     * memory, which is the same discipline {@link PortAllocator}'s boot sweep applies to a
     * still-bound port.
     *
     * @return how much was released
     */
    public static long resetOn(int serverId) {
        long used = bookedMbOn(serverId);
        if (used > 0) {
            Quotas.release(bucketOf(serverId), used);
            Blast.log("CAPACITY: released", used, "MB of managed-process bookings left by a"
                + " previous controller generation on server", serverId);
        }
        return used;
    }

    private static Microcopy violation(@Nullable String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
