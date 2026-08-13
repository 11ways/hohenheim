package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.server.BootSettle;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.lease.Leases;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The boot-recovery discipline: what a settle sweep may touch, and under what authority.
 *
 * WHY IT EXISTS: five sweeps settled work a killed controller left behind, and each
 * carried its own copy of the same process-start clock comparison. Two defects lived in
 * that shape. The clock compared ANOTHER controller's timestamps against THIS JVM's start
 * (several hohenheim processes over one control-plane database is a supported deployment
 * -- HohenheimRoles splits the roles across processes), and the settle itself reached
 * {@code HostLeases.requireFence}, which ACQUIRES on miss and holds for the process
 * lifetime: a boot sweep therefore seized the lease of every host any stuck record sat on
 * and fenced the rightful controller out of hosts this process never intended to drive.
 *
 * {@code BootSettle} is the one helper all five now share, and the lease -- not the clock
 * -- is the authority: a host a rival holds is skipped, and a host taken purely to settle
 * is handed straight back.
 */
class BootSettleLeaseTest {

    private static SqlDatasource datasource;

    private static final int HOST = 4242;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
    }

    @Test
    void aSettleBorrowsTheHostLeaseAndSkipsAHostARivalHolds() {
        Db.run(datasource, () -> {
            HostLeases mine = new HostLeases(Leases::of, Duration.ofSeconds(30));
            AtomicInteger ran = new AtomicInteger();

            // 1. THE CLOCK, which is the second guard and only judges rows this controller
            //    could have written. Anything stamped before this JVM started is a corpse;
            //    anything stamped inside its lifetime is a live operation; an unstamped row
            //    reads as a corpse, matching every copy this replaced.
            assertThat(BootSettle.writtenByThisProcess(
                    BootSettle.processStart().minusSeconds(60)))
                .as("step 1: a write from before this process started is a corpse")
                .isFalse();
            assertThat(BootSettle.writtenByThisProcess(Instant.now()))
                .as("step 1: a write from this process's lifetime is a live operation")
                .isTrue();
            assertThat(BootSettle.writtenByThisProcess(null))
                .as("step 1: an unstamped row reads as a corpse")
                .isFalse();

            // 2. THE BORROW: nobody holds the host, so the settle runs -- and the lease is
            //    handed back afterwards. (Pre-fix the settle went through requireFence,
            //    which keeps the hold for the process lifetime.)
            assertThat(mine.isHeld(HOST))
                .as("step 2 precondition: this controller holds nothing yet").isFalse();
            assertThat(BootSettle.underBorrowedHostLease(mine, HOST, ran::incrementAndGet))
                .as("step 2: an unheld host is settled")
                .isTrue();
            assertThat(ran.get()).as("step 2: and the settle body really ran").isEqualTo(1);
            assertThat(mine.isHeld(HOST))
                .as("step 2: THE FIX -- a lease taken purely to settle is released again,"
                    + " so the sweep never fences the host's real controller out")
                .isFalse();

            // 3. A lease this controller ALREADY held is its own and is KEPT: the borrow
            //    must not release authority the caller acquired for real work.
            mine.requireFence(HOST);
            assertThat(BootSettle.underBorrowedHostLease(mine, HOST, ran::incrementAndGet))
                .as("step 3: an already-held host is settled too").isTrue();
            assertThat(ran.get()).as("step 3: the body ran again").isEqualTo(2);
            assertThat(mine.isHeld(HOST))
                .as("step 3: and the pre-existing hold survives the settle")
                .isTrue();
            mine.release(HOST);

            // 4. A RIVAL CONTROLLER holds the host: the settle is SKIPPED entirely. Its
            //    records are that controller's to settle -- it is the one whose clock the
            //    timestamps belong to, and the one whose live operation must not be
            //    "recovered" out from under it.
            Leases rivalCoordinator = Leases.independent(datasource);
            HostLeases rival = new HostLeases(d -> rivalCoordinator, Duration.ofSeconds(30));
            rival.requireFence(HOST);
            assertThat(BootSettle.underBorrowedHostLease(mine, HOST, ran::incrementAndGet))
                .as("step 4: a host another controller holds is skipped, not seized")
                .isFalse();
            assertThat(ran.get())
                .as("step 4: STATE -- the settle body never ran").isEqualTo(2);
            assertThat(mine.isHeld(HOST))
                .as("step 4: and this controller took no lease on it")
                .isFalse();

            // 5. Once the rival lets go, the same sweep settles normally -- the skip is a
            //    deferral to the owner, never a permanent refusal.
            rival.release(HOST);
            assertThat(BootSettle.underBorrowedHostLease(mine, HOST, ran::incrementAndGet))
                .as("step 5: with the host free again the settle proceeds")
                .isTrue();
            assertThat(ran.get()).as("step 5: and the body ran").isEqualTo(3);
            assertThat(mine.isHeld(HOST))
                .as("step 5: still borrowed, still returned").isFalse();
        });
    }
}
