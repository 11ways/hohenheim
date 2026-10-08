package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A scheduled task as the operator reads it: its worded name and why its last run failed.
 *
 * AIDEV-NOTE: a task's name is catalog copy keyed by its id's path under the {@code task_label} scope, shipped in en
 * and nl for EVERY task the catalog holds, the framework's included (DashboardAttentionJourneyTest fails on one
 * without). zenit's TaskDescriptor carries no worded label, only an English description; the label belongs there,
 * and moves there once zenit core can take it.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class TaskWords {

    /** The catalog scope every task name ships under. */
    public static final String LABEL_SCOPE = "task_label";

    /**
     * The head of a run's stored error as zenit's TaskSchedule writes it: the exception's class name, then its
     * message, then the stack trace on indented "at" lines.
     */
    private static final Pattern THROWN = Pattern.compile("^(?:[a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*: ");

    private TaskWords() {
    }

    /** @return the task's worded name ("Back up databases") */
    public static @NonNull Microcopy label(@NonNull Identifier taskId) {
        return Microcopy.of(taskId.getPath()).withFilter("scope", LABEL_SCOPE);
    }

    /**
     * Why a run failed, in the words its failure carried: the message without the exception's class name or stack
     * trace, a stored refusal read back in its own words.
     *
     * @param stored the run's stored error, as {@code system_task_history.error} holds it
     * @return the reason, null when the run stored none
     */
    public static @Nullable Microcopy failure(@Nullable String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String message = stored;
        int trace = message.indexOf("\n\tat ");
        int indented = message.indexOf("\n    at ");
        int end = trace < 0 ? indented : indented < 0 ? trace : Math.min(trace, indented);
        if (end >= 0) {
            message = message.substring(0, end);
        }
        Matcher thrown = THROWN.matcher(message);
        if (thrown.find()) {
            message = message.substring(thrown.end());
        }
        message = HohenheimViolations.storedText(message.strip());
        return message.isBlank() || "null".equals(message) ? null : Microcopy.literal(message);
    }
}
