package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.host.HostLeases;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A database swap closes the outgoing database under the production controller's host leases,
 * and the first host operation on the new database still costs nothing.
 *
 * AIDEV-NOTE: the first requireFence after a swap finds a hold on the CLOSED datasource and
 * releases it. zenit Leases once read that closed datasource as lost storage and spent its 30s
 * storage-heal poll there -- the exact 30s that 15 default-lane tests each paid, hidden by a
 * release-before-close step in TestDatabases. Leases now drops a hold on a closed datasource
 * at once. The bound below is far above the fast path and far below that poll.
 */
class DatabaseSwapLeaseReleaseTest {

    /** A host id no fixture creates: the lease key needs no server row. */
    private static final int HOST_ID = 987_654;

    /** Far above the fast path, far below the 30s heal poll it guards against. */
    private static final Duration BOUND = Duration.ofSeconds(10);

    @Test
    void aSwapReleasesTheHostLeaseSoTheNextOperationDoesNotWaitOnTheClosedDatabase()
            throws Exception {
        // 1. A booted runtime on a private database takes the host's lease.
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        HostLeases leases = HostLeases.production();
        long firstFence = leases.requireFence(HOST_ID);
        assertThat(firstFence).as("step 1: the lease was taken on the first database")
            .isPositive();
        assertThat(leases.isHeld(HOST_ID)).as("step 1: and this controller holds it").isTrue();

        // 2. The swap closes that database; the hold must not survive it.
        TestDatabases.freshDatabase();
        long start = System.nanoTime();
        boolean stillHeld = leases.isHeld(HOST_ID);
        Duration lookup = Duration.ofNanos(System.nanoTime() - start);
        assertThat(stillHeld).as("step 2: no hold on the closed database survives the swap")
            .isFalse();
        assertThat(lookup).as("step 2: and asking costs no storage-heal wait").isLessThan(BOUND);

        // 3. The next operation acquires on the NEW database, just as fast.
        start = System.nanoTime();
        long secondFence = leases.requireFence(HOST_ID);
        Duration acquire = Duration.ofNanos(System.nanoTime() - start);
        assertThat(secondFence).as("step 3: the new database hands out its own lease")
            .isPositive();
        assertThat(acquire).as("step 3: without waiting on the closed database").isLessThan(BOUND);
        assertThat(leases.release(HOST_ID)).as("step 3: and the hold is handed back").isTrue();
    }
}
