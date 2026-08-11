package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Kernel-truth isolation verification as data: whether the posture requires it, whether a
 * lane exists to read the kernel at all, the stored verdict and ITS OWN age, and which
 * endpoint the verdict is about (a blank {@code incus_url} means the CONTROLLER's own
 * socket -- the machine named must be readable off the page, never inferred).
 *
 * @param endpointNote the local-socket spelling ({@code unix://...}) when the record
 *                     addresses the controller's own daemon, {@code ""} for a real remote
 */
@HawkeyeClass
public record KernelIsolationView(
    boolean required,
    boolean laneAvailable,
    @Nullable String status,
    @Nullable String checkedAtIso,
    String endpointNote
) {

    public boolean proven() {
        return "pass".equals(this.status);
    }
}
