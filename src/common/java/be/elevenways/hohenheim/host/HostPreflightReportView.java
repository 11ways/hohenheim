package be.elevenways.hohenheim.host;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
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
 * @param mustPass    the required checks, the ones that did not pass first: they decide admission
 * @param advice      the advisory checks, the ones that did not pass first: they never block
 * @param probedAtIso when the report was taken, null when the host was never probed
 * @param passed      the stored overall verdict
 */
@HawkeyeClass
public record HostPreflightReportView(
    @Nullable KernelIsolationView kernel,
    @NonNull List<PreflightCheckView> mustPass,
    @NonNull List<PreflightCheckView> advice,
    @NonNull List<HostFactView> facts,
    @Nullable String probedAtIso,
    boolean passed
) {

    /** @return the overall verdict in the check vocabulary's own words ("Passed", "Failed") */
    public Microcopy summaryLabel() {
        return this.summary().label();
    }

    /** @return the overall verdict's badge variant, read off the same member as its words */
    public BadgeVariant summaryVariant() {
        return this.summary().badgeVariant();
    }

    private PreflightStatus summary() {
        return this.passed ? PreflightStatus.PASS : PreflightStatus.FAIL;
    }
}
