package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;

/**
 * Capability: the driver can report how much of its root disk a workload is ACTUALLY
 * occupying -- the observed half of {@link RootDiskSizeSupport}'s declared one.
 *
 * AIDEV-NOTE: DECLARING this interface is the capability statement, exactly like
 * RootDiskSizeSupport. A driver that cannot observe root-disk usage simply does not
 * implement it, and every consumer then has NO number for that tier rather than a
 * plausible-looking zero. That is the point: a "disk 92% full" item computed for a tier
 * where nothing is measured or enforced is worse than silence, because an operator would
 * act on it.
 *
 * MEASURED (daystrom, Incus 7.3 + btrfs, 2026-08-07): {@code /1.0/instances/{n}/state}
 * carries {@code disk.root.usage} always, and {@code disk.root.total} only when a size is
 * declared -- a container launched with {@code -d root,size=2GiB} read back
 * {@code total 2147483648, usage 12267520}, one launched without a size read back
 * {@code total 0, usage 12439552}. So usage is always honest and the LIMIT is honest by
 * being zero when there is none. Docker is deliberately absent from this interface: it
 * accepts {@code --storage-opt size=2G} on overlayfs/ext4 and then enforces nothing
 * (measured: 2.5GB written into a 2G declaration), so neither half of the pair would mean
 * anything there.
 */
public interface RootDiskUsageSupport {

    /**
     * What the daemon reports for the workload's root device.
     *
     * @param usedBytes  bytes currently occupied
     * @param limitBytes the ENFORCED ceiling, or 0 when the workload declares none --
     *                   never a guess, never the pool's size
     */
    record DiskUsage(long usedBytes, long limitBytes) {

        /** Whether a percentage of a real ceiling can be computed at all. */
        public boolean bounded() {
            return this.limitBytes > 0 && this.usedBytes >= 0;
        }

        /** Fraction of the enforced ceiling in use, or -1 when there is no ceiling. */
        public double fractionUsed() {
            return this.bounded() ? (double) this.usedBytes / this.limitBytes : -1;
        }
    }

    /**
     * @return the observation, or null when the workload is not running or the daemon
     *         reports no disk for it -- never a synthesized zero
     * @throws IOException when the daemon could not be asked at all
     */
    @Nullable DiskUsage rootDiskUsage(@NonNull InstanceSpec spec) throws IOException;
}
