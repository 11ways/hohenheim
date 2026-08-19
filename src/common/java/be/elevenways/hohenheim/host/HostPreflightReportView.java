package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * The whole stored preflight report as ONE widget payload: the kernel-truth verdict,
 * every check with its own stamp, the measured facts, and when the report was taken.
 *
 * The parts travel together because they are one reading: a check list without the
 * probe stamp cannot say how old it is, and a stamp without the checks says nothing.
 *
 * @param probedAtIso when the report was taken, null when the host was never probed
 * @param passed      the stored overall verdict
 */
@HawkeyeClass
public record HostPreflightReportView(
    @Nullable KernelIsolationView kernel,
    @NonNull List<PreflightCheckView> checks,
    @NonNull List<HostFactView> facts,
    @Nullable String probedAtIso,
    boolean passed
) {}
