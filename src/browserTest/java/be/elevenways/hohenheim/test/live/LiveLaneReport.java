package be.elevenways.hohenheim.test.live;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes a skipped live test VISIBLE. Registered through {@code META-INF/services}, so it
 * observes every JVM this suite forks and needs no per-class wiring -- which is the point:
 * a report that depended on each test remembering to enrol would be exactly the kind of
 * "does less than it claims" it exists to catch.
 *
 * <p>It reports EVERY abort, not only {@link LiveLane}'s: an abort with no
 * {@code [live:...]} marker is listed as UNCLASSIFIED, so a gate that was never migrated
 * still shows up rather than vanishing. Completeness here does not depend on the migration
 * being exhaustive.
 *
 * AIDEV-NOTE: one report per forked JVM, each stamped with its pid, and it counts only
 * what THIS JVM ran -- browserTest shares JVMs across up to four parallel forks, so a
 * daemon-wide total would be a number about nothing.
 */
public final class LiveLaneReport implements TestExecutionListener {

    /** need key (or "unclassified") -> reason -> the tests that aborted for it. */
    private final Map<String, Map<String, List<String>>> skipped = new LinkedHashMap<>();

    @Override
    public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
        if (result.getStatus() != TestExecutionResult.Status.ABORTED) {
            return;
        }
        record(identifier.getLegacyReportingName(),
            result.getThrowable().map(Throwable::getMessage).orElse(""));
    }

    /**
     * Classify ONE abort by the need its message names, or as {@code unclassified}. The
     * listener glue above is the only caller in production; the test drives this directly
     * because the classification, not the JUnit plumbing, is the mechanism.
     */
    void record(@NonNull String testName, @Nullable String message) {
        String need = "unclassified";
        String reason = message == null ? "" : message;
        if (reason.startsWith(LiveLane.MARKER_PREFIX)) {
            int close = reason.indexOf(']');
            if (close > 0) {
                need = reason.substring(LiveLane.MARKER_PREFIX.length(), close);
                reason = reason.substring(close + 1).trim();
            }
        }
        this.skipped.computeIfAbsent(need, key -> new LinkedHashMap<>())
            .computeIfAbsent(reason, key -> new ArrayList<>())
            .add(testName);
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        String report = render();
        if (!report.isEmpty()) {
            System.out.println(report);
        }
    }

    /** The report text, or empty when this JVM skipped nothing. */
    @NonNull String render() {
        if (this.skipped.isEmpty()) {
            return "";
        }
        StringBuilder report = new StringBuilder();
        long pid = ProcessHandle.current().pid();
        report.append("\n=== LIVE LANE REPORT (jvm ").append(pid).append(") ===\n");
        report.append("These tests did NOT run. A skip is not a pass.\n");
        this.skipped.forEach((need, reasons) -> {
            int total = reasons.values().stream().mapToInt(List::size).sum();
            report.append("  ").append(need).append("  (").append(total)
                .append(" skipped in this jvm)\n");
            reasons.forEach((reason, tests) -> {
                report.append("      ").append(reason).append('\n');
                for (String test : tests) {
                    report.append("        - ").append(test).append('\n');
                }
            });
        });
        report.append("  Declare what this host can satisfy to make its skips FAIL:\n")
            .append("    -Dhohenheim.live.require=")
            .append(String.join(",", this.skipped.keySet()))
            .append("   (currently: ")
            .append(LiveLane.required().isEmpty() ? "nothing required"
                : LiveLane.required().toString())
            .append(")\n")
            .append("=== END LIVE LANE REPORT ===\n");
        return report.toString();
    }
}
