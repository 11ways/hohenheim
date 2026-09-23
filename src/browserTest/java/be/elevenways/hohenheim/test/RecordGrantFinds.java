package be.elevenways.hohenheim.test;

import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.common.orm.model.hook.BeforeFind;
import be.elevenways.zenit.common.orm.query.criteria.CompositeCriteria;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.orm.query.criteria.FieldCriteria;
import be.elevenways.zenit.common.orm.query.criteria.InCriteria;
import be.elevenways.zenit.common.orm.query.criteria.NotCriteria;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.temporal.Temporal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Counts the record-grant finds one request performs, names where each one came from and which
 * grant set it read, so a per-request query budget says WHICH caller moved instead of only a
 * number, and a test can assert the memo property itself: no identical grant query twice.
 *
 * AIDEV-NOTE: the hook is installed ONCE per JVM and records only while a recording is open.
 * The budget tests used to add a fresh counting hook per test run, which stayed registered
 * on the shared schema for the rest of the JVM. Finds run on the request's own threads, not
 * the test thread, so the recording is process-wide: a budget test must not overlap another
 * test that reads grants.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class RecordGrantFinds {

    /** Frames from these packages are the query machinery, never the caller worth naming. */
    private static final List<String> MACHINERY = List.of(
        "java.", "jdk.", "sun.",
        "be.elevenways.zenit.common.orm.",
        "be.elevenways.zenit.auth.server.RecordGrants",
        "be.elevenways.zenit.auth.server.RecordGrantCapabilityChecker",
        RecordGrantFinds.class.getName());

    /** How many caller frames one find is attributed to. */
    private static final int CALLER_FRAMES = 6;

    private static volatile ConcurrentLinkedQueue<Find> recording;

    /** One observed find: the caller chain and the grant query it ran. */
    private record Find(@NonNull String caller, @NonNull String query) {
    }

    static {
        RecordGrantModel.SCHEMA.addBeforeFindHook(RecordGrantFinds::observe);
    }

    private RecordGrantFinds() {
    }

    /**
     * The finds of one recording.
     *
     * @param count   every find
     * @param callers each distinct "caller [query]" line with its tally
     * @param repeats each grant query that ran more than once, with its tally
     */
    public record Result(int count, @NonNull Map<String, Long> callers, @NonNull Map<String, Long> repeats) {

        /** One line per distinct caller and query, for an assertion description or the log. */
        public @NonNull String describe() {
            return this.callers.entrySet().stream()
                .map(entry -> entry.getValue() + "x " + entry.getKey())
                .collect(Collectors.joining("\n  ", "\n  ", ""));
        }
    }

    /**
     * Record every record-grant find while {@code action} runs.
     *
     * @throws Exception whatever the action throws, after the recording is closed
     */
    public static @NonNull Result during(@NonNull ThrowingRunnable action) throws Exception {
        ConcurrentLinkedQueue<Find> finds = new ConcurrentLinkedQueue<>();
        recording = finds;
        try {
            action.run();
        } finally {
            recording = null;
        }
        Map<String, Long> callers = new TreeMap<>(finds.stream().collect(Collectors.groupingBy(
            find -> find.caller() + " [" + find.query() + "]", Collectors.counting())));
        Map<String, Long> repeats = new TreeMap<>(finds.stream()
            .collect(Collectors.groupingBy(Find::query, Collectors.counting())));
        repeats.values().removeIf(times -> times < 2);
        return new Result(finds.size(), callers, repeats);
    }

    /** An action that may throw a checked exception, like an HTTP round trip. */
    @FunctionalInterface
    public interface ThrowingRunnable {

        /** Run the action. */
        void run() throws Exception;
    }

    private static void observe(@NonNull BeforeFind context) {
        ConcurrentLinkedQueue<Find> finds = recording;
        if (finds == null) {
            return;
        }
        String callers = StackWalker.getInstance().walk(frames -> frames
            .filter(frame -> MACHINERY.stream().noneMatch(frame.getClassName()::startsWith))
            .limit(CALLER_FRAMES)
            .map(frame -> simpleName(frame.getClassName()) + "." + frame.getMethodName()
                + ":" + frame.getLineNumber())
            .collect(Collectors.joining(" < ")));
        finds.add(new Find(callers, describe(context.getQueryContext().getCriteria())));
    }

    /**
     * The criteria as text: field comparisons by name and value, a moment as {@code <time>} so
     * the expiry cut-off every grant query carries does not make two identical reads differ.
     */
    private static @NonNull String describe(Criteria criteria) {
        if (criteria == null) {
            return "all";
        }
        if (criteria instanceof CompositeCriteria composite) {
            return composite.getOperator() + composite.getChildren().stream()
                .map(RecordGrantFinds::describe).sorted().collect(Collectors.joining(", ", "(", ")"));
        }
        if (criteria instanceof NotCriteria not) {
            return "NOT " + describe(not.getChild());
        }
        if (criteria instanceof InCriteria<?, ?> in) {
            return in.getField().getName() + " in " + in.getValues().stream()
                .map(String::valueOf).sorted().collect(Collectors.joining(",", "[", "]"));
        }
        if (criteria instanceof FieldCriteria<?, ?> field) {
            Object value = field.getValue();
            return field.getField().getName() + " " + simpleName(criteria.getClass().getName())
                + " " + (value instanceof Temporal || value instanceof Date ? "<time>" : value);
        }
        return simpleName(criteria.getClass().getName());
    }

    private static @NonNull String simpleName(@NonNull String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
