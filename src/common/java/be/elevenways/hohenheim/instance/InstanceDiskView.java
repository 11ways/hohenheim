package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The instance's OBSERVED root-disk usage, as the overview renders it.
 *
 * {@code measured} false is a DECLARED runtime asymmetry, never breakage: only a driver
 * implementing {@code RootDiskUsageSupport} stamps these columns, and Docker enforces no
 * root quota and measures none, so its whole tier stays null forever. A zero bar would
 * claim an empty disk; this record makes the surface say "not measured" and name the
 * runtime that cannot measure it.
 *
 * @param enforced whether the daemon reports a real ceiling (a 0 limit rations nothing)
 * @param runtime  the host runtime, so the page can say WHICH runtime does not measure
 */
@HawkeyeClass
public record InstanceDiskView(
    boolean measured,
    boolean enforced,
    long usedBytes,
    long limitBytes,
    @Nullable String observedAtIso,
    @NonNull String runtime
) {

    /** Percent of the enforced ceiling in use, 0 when nothing is rationed. */
    public int percent() {
        if (!this.measured || !this.enforced || this.limitBytes <= 0) {
            return 0;
        }
        long percent = this.usedBytes * 100 / this.limitBytes;
        return (int) Math.min(100, Math.max(0, percent));
    }
}
