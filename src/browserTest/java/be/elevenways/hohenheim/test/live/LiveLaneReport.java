package be.elevenways.hohenheim.test.live;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * <p>It also makes a TRUNCATED run unmistakable: a Gradle task timeout kills the worker
 * JVM mid-flight, Gradle then reports the killed test as SKIPPED with no message, its
 * {@code finally} never runs (live-host credentials leak), and no JUnit event ever fires
 * for it -- so the skip report above is structurally blind to it. This listener therefore
 * keeps a live marker file naming every test that has not yet reported a result, deletes
 * it only when the test plan finishes, and prints a truncation banner from a shutdown
 * hook when the JVM dies with tests still unaccounted for.
 *
 * <p>Being the one thing that observes a forked JVM's whole lifetime also makes it the
 * home of the Docker namespace reap ({@link LiveNamespaces}): abandoned namespaces at
 * plan start, this JVM's own at plan finish. The two are the same story from two ends --
 * a run that was killed left both an unsettled test and a network eating address-pool
 * space, and only one of them used to be reported.
 *
 * AIDEV-NOTE: one report per forked JVM, each stamped with its pid, and it counts only
 * what THIS JVM ran -- browserTest shares JVMs across up to four parallel forks, so a
 * daemon-wide total would be a number about nothing.
 *
 * AIDEV-NOTE: the marker file is rewritten on every start/finish rather than only in the
 * shutdown hook, because a hook only runs on SIGTERM -- a SIGKILLed worker leaves nothing
 * behind except what was already on disk. Observed 2026-08-10: browserTestIsolated hit its
 * 30m task timeout, RestoreCapacityLiveTest was killed mid-test and reported SKIPPED, and
 * daystrom kept a stale root key its finally-block teardown would have swept.
 */
public final class LiveLaneReport implements TestExecutionListener {

    /** need key (or "unclassified") -> reason -> the tests that aborted for it. */
    private final Map<String, Map<String, List<String>>> skipped = new LinkedHashMap<>();

    /** Every planned-or-started test (uniqueId -> display name) with no verdict yet. */
    private final Map<String, String> unfinished = new ConcurrentHashMap<>();

    /** The subset of {@link #unfinished} that actually began executing. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean hookArmed = new AtomicBoolean();
    private volatile TestPlan plan;
    private volatile Path markerFile;

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        this.plan = testPlan;
        for (TestIdentifier root : testPlan.getRoots()) {
            for (TestIdentifier id : testPlan.getDescendants(root)) {
                if (id.isTest()) {
                    trackPlanned(id.getUniqueId(), id.getLegacyReportingName());
                }
            }
        }
        this.markerFile = Path.of(
            System.getProperty("hohenheim.truncation.dir", "build/live-lane-truncations"),
            "jvm-" + ProcessHandle.current().pid() + ".txt");
        if (this.hookArmed.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(
                new Thread(this::reportTruncation, "live-lane-truncation"));
        }
        writeMarker();
        // The other half of the truncation story: a killed JVM's finally blocks never ran,
        // so its Docker networks are still on the daemon eating address-pool space. Reap
        // the namespaces of JVMs that are provably gone before this run needs a subnet.
        reportReaped("abandoned by a dead test jvm", LiveNamespaces.sweepAbandoned());
    }

    @Override
    public void executionStarted(TestIdentifier identifier) {
        if (identifier.isTest()) {
            trackStarted(identifier.getUniqueId(), identifier.getLegacyReportingName());
            writeMarker();
        }
    }

    @Override
    public void executionSkipped(TestIdentifier identifier, String reason) {
        settle(identifier);
    }

    @Override
    public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
        settle(identifier);
        if (result.getStatus() != TestExecutionResult.Status.ABORTED) {
            return;
        }
        record(identifier.getLegacyReportingName(),
            result.getThrowable().map(Throwable::getMessage).orElse(""));
    }

    /**
     * A settled identifier accounts for itself AND, when it is a container (a class-level
     * skip or a failed {@code @BeforeAll}), for every descendant that will now never fire
     * its own event.
     */
    private void settle(TestIdentifier identifier) {
        trackFinished(identifier.getUniqueId());
        TestPlan current = this.plan;
        if (current != null && !identifier.isTest()) {
            for (TestIdentifier child : current.getDescendants(identifier)) {
                trackFinished(child.getUniqueId());
            }
        }
        writeMarker();
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

    // -- truncation tracking (the JUnit plumbing above delegates here; the test drives
    // these directly for the same reason record() is package-private) ------------------

    void trackPlanned(@NonNull String id, @NonNull String name) {
        this.unfinished.put(id, name);
    }

    void trackStarted(@NonNull String id, @NonNull String name) {
        this.unfinished.putIfAbsent(id, name);
        this.inFlight.add(id);
    }

    void trackFinished(@NonNull String id) {
        this.unfinished.remove(id);
        this.inFlight.remove(id);
    }

    /**
     * The truncation text naming every test with no verdict in this JVM, or empty when
     * everything settled -- a completed run must never print it.
     */
    @NonNull String renderTruncation() {
        if (this.unfinished.isEmpty()) {
            return "";
        }
        StringBuilder report = new StringBuilder();
        long pid = ProcessHandle.current().pid();
        report.append("\n=== LIVE LANE TRUNCATION (jvm ").append(pid).append(") ===\n")
            .append("This JVM was torn down MID-RUN (task timeout or kill). The tests\n")
            .append("below have NO verdict -- a SKIPPED line for them is a lie, and an\n")
            .append("in-flight test's finally blocks (live-host cleanup!) never ran.\n");
        List<String> started = new ArrayList<>();
        List<String> neverStarted = new ArrayList<>();
        this.unfinished.forEach((id, name) ->
            (this.inFlight.contains(id) ? started : neverStarted).add(name));
        if (!started.isEmpty()) {
            report.append("  KILLED IN FLIGHT:\n");
            for (String name : started) {
                report.append("    - ").append(name).append('\n');
            }
        }
        if (!neverStarted.isEmpty()) {
            report.append("  never started in this jvm:\n");
            for (String name : neverStarted) {
                report.append("    - ").append(name).append('\n');
            }
        }
        report.append("=== END LIVE LANE TRUNCATION ===\n");
        return report.toString();
    }

    /** Keep the on-disk marker current so even a SIGKILL leaves the evidence behind. */
    private void writeMarker() {
        Path file = this.markerFile;
        if (file == null) {
            return;
        }
        try {
            String text = renderTruncation();
            if (text.isEmpty()) {
                Files.deleteIfExists(file);
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, text, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // the shutdown-hook banner still fires; a marker miss must not fail a test
        }
    }

    /** Shutdown-hook half: loud on the console when the JVM dies with tests unaccounted. */
    private void reportTruncation() {
        String text = renderTruncation();
        if (!text.isEmpty()) {
            System.err.print(text);
            System.err.flush();
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        // A finished plan settled everything; drop the tracking so the hook stays silent
        // and the marker disappears (a leftover marker file IS the truncation evidence).
        this.unfinished.clear();
        this.inFlight.clear();
        this.plan = null;
        writeMarker();
        // Whatever the verdict: this JVM's namespaces are dead the moment its plan is,
        // and a FAILED test is precisely the one whose teardown never ran.
        reportReaped("left behind by this jvm", LiveNamespaces.sweepOwn());
        String report = render();
        if (!report.isEmpty()) {
            System.out.println(report);
        }
    }

    /** Say what the namespace reaper removed; silence when it found nothing. */
    private void reportReaped(@NonNull String what, @NonNull List<String> removed) {
        if (removed.isEmpty()) {
            return;
        }
        StringBuilder report = new StringBuilder("\n=== LIVE LANE NAMESPACE REAP (jvm ")
            .append(ProcessHandle.current().pid()).append(") ===\n")
            .append("Docker resources ").append(what).append(":\n");
        for (String resource : removed) {
            report.append("    - ").append(resource).append('\n');
        }
        report.append("=== END LIVE LANE NAMESPACE REAP ===\n");
        System.out.println(report);
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
