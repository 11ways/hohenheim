package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.session.Session;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one-shot carrier for an exec run's exit code and captured output, between the
 * POST that ran it and the PRG render that shows it.
 *
 * AIDEV-NOTE: exec output is command OUTPUT, not a notification -- it is page
 * CONTENT, so it is neither a toast nor (as it used to be) a query parameter.
 * Program output in a URL lands in access logs, proxy logs, the browser history and
 * anything the operator pastes. It rides the session instead, keyed by instance so a
 * result can never surface on another instance's tab, and popped on read so a reload
 * shows an empty form rather than a stale run.
 */
final class InstanceExecResults {

    private static final IdentifierKey<Map<String, String>> LAST_RUN =
        IdentifierKey.of("hohenheim.session", "instance_exec_result");

    private static final String KEY_INSTANCE = "instance";
    private static final String KEY_EXIT = "exit";
    private static final String KEY_OUTPUT = "output";

    /**
     * Output ceiling. The session is DURABLE storage (auth_sessions.data), so an
     * unbounded capture of a command that printed a megabyte would be written there
     * verbatim; the tail is the half an operator reads anyway.
     */
    private static final int MAX_OUTPUT_CHARS = 64 * 1024;

    private InstanceExecResults() {
    }

    /** The result of one run, or null when this instance has none pending. */
    record Run(@NonNull String exitCode, @NonNull String output) {}

    /** Stash a completed run for the instance's next render; sessionless conduits drop it. */
    static void stash(@NonNull Conduit conduit, int instanceId, int exitCode, @Nullable String output) {
        Session session = conduit.session();
        if (session.id().isEmpty()) {
            return;
        }
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put(KEY_INSTANCE, String.valueOf(instanceId));
        stored.put(KEY_EXIT, String.valueOf(exitCode));
        stored.put(KEY_OUTPUT, trimmed(output));
        session.set(LAST_RUN, stored);
    }

    /** The output, keeping only the tail once it exceeds the session ceiling. */
    private static @NonNull String trimmed(@Nullable String output) {
        if (output == null) {
            return "";
        }
        return output.length() <= MAX_OUTPUT_CHARS
            ? output
            : output.substring(output.length() - MAX_OUTPUT_CHARS);
    }

    /** Pop the pending run for this instance; a run stashed for another instance stays put. */
    static @Nullable Run pop(@NonNull Conduit conduit, int instanceId) {
        Session session = conduit.session();
        if (session.id().isEmpty()) {
            return null;
        }
        Map<String, String> stored = session.get(LAST_RUN);
        if (stored == null || !String.valueOf(instanceId).equals(stored.get(KEY_INSTANCE))) {
            return null;
        }
        session.remove(LAST_RUN);
        return new Run(String.valueOf(stored.get(KEY_EXIT)),
            stored.get(KEY_OUTPUT) == null ? "" : stored.get(KEY_OUTPUT));
    }
}
