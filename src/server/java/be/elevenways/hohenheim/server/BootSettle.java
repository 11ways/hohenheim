package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.management.ManagementFactory;
import java.time.Instant;

/**
 * THE boot-recovery discipline, in one place: what counts as a corpse a settle sweep may
 * touch, and under what authority it may touch it.
 *
 * Every sweep that settles work a killed controller left behind (interrupted captures and
 * restores, uploading backup rows, payload-orphaned snapshot rows, mid-flight migrations,
 * in-flight release operations) asks the same two questions, and each used to answer them
 * with its own copy of the same five lines.
 *
 * AIDEV-NOTE: THE CROSS-CONTROLLER CAVEAT, which the copies did not state and could not
 * honour. {@link #writtenByThisProcess} compares a stored timestamp against THIS JVM's
 * start time, and that comparison is only meaningful for rows THIS controller wrote:
 * another controller's clock is not ours, its process start is not ours, and a row it
 * wrote one second ago can trivially read as "written before this process started" and
 * therefore as a corpse. Several hohenheim processes over one control-plane database is a
 * supported deployment ({@code HohenheimRoles} splits the roles across processes), so the
 * hazard is real rather than theoretical.
 *
 * What makes the clock safe is {@link #underBorrowedHostLease}: the host LEASE, not the
 * clock, is the authority. A controller doing live work on a host holds that host's lease
 * for the duration (every operation funnel calls {@code requireFence} first), so a sweep
 * that can take the lease has proven no rival is working there, and one that cannot take
 * it skips the row and leaves it to the controller that owns the host. The clock then only
 * ever judges rows nobody else is currently authoritative for -- it is the second guard,
 * not the first.
 *
 * AIDEV-NOTE: and the lease is BORROWED, never seized. {@code HostLeases.requireFence}
 * acquires on miss and holds for the process lifetime, which is correct for an operation
 * this controller is about to drive and badly wrong for a sweep that only looks: a boot
 * sweep over every stuck record used to take -- and keep -- the lease of every host any of
 * them sat on, fencing the rightful controller out of hosts this process never intended to
 * drive. Anything acquired purely to settle is handed straight back.
 *
 * @author Jelle De Loecker
 */
public final class BootSettle {

    private BootSettle() {
    }

    /** When THIS JVM started; anything written before it belongs to a previous process. */
    public static @NonNull Instant processStart() {
        return Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    /**
     * Whether a row's last write happened inside THIS process's lifetime, in which case it
     * is a live operation and not a corpse.
     *
     * @param lastWrite the row's {@code updated_at}, falling back to {@code created_at};
     *        null (neither stamped) reads as a corpse, matching every previous copy
     */
    public static boolean writtenByThisProcess(@Nullable Instant lastWrite) {
        return lastWrite != null && !lastWrite.isBefore(processStart());
    }

    /**
     * Run a settle for one host under a BORROWED host lease: acquired when this controller
     * does not already hold it, and released again afterwards so a sweep never keeps
     * authority over a host it does not drive.
     *
     * @return true when the body ran; false when a rival controller holds the host, which
     *         means the row is that controller's to settle and this sweep must not touch it
     */
    public static boolean underBorrowedHostLease(@NonNull HostLeases leases, int serverId,
                                                 @NonNull Runnable body) {
        boolean alreadyHeld = leases.isHeld(serverId);
        if (!alreadyHeld) {
            try {
                leases.requireFence(serverId);
            } catch (Violations rivalHolds) {
                Blast.log("BOOT SETTLE: host", serverId, "is held by another controller;"
                    + " leaving its interrupted work to the controller that owns it");
                return false;
            }
        }
        try {
            body.run();
            return true;
        } finally {
            if (!alreadyHeld) {
                leases.release(serverId);
            }
        }
    }
}
