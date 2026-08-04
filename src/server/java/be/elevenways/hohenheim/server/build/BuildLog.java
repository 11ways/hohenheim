package be.elevenways.hohenheim.server.build;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * THE capture of one build's output: bounded, redacting, and the same object for every
 * builder kind (the plan's "one log stream", concretely).
 *
 * AIDEV-NOTE: the cap is enforced on APPEND and the stream keeps being DRAINED past it.
 * Stopping the drain instead would block the daemon's writer and hang the build -- a
 * bounded log must bound memory, not the build. Exactly one marker line is emitted at the
 * moment of truncation so the reader knows output is missing rather than believing the
 * build went quiet.
 *
 * AIDEV-NOTE: redaction is VALUE-based and unconditional, and it happens before anything
 * is stored, because a builder we do not control decides what it echoes. A registry
 * credential printed by a base image's entrypoint is the same leak as one printed by us.
 */
public final class BuildLog {

    static final String TRUNCATION_MARKER =
        "\n[hohenheim] build log truncated at the configured cap\n";

    /** Shortest secret worth redacting; below this the redaction would eat the log. */
    private static final int MIN_REDACTABLE = 6;

    private final int maxBytes;
    private final List<String> secrets = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private boolean truncated;

    public BuildLog(int maxBytes) {
        this.maxBytes = Math.max(1024, maxBytes);
    }

    /**
     * Register a value that must never appear in the captured output.
     * Values shorter than {@value #MIN_REDACTABLE} characters are IGNORED, loudly in the
     * sense that {@link #redactedSecretCount()} does not count them: redacting a 3-char
     * string would replace half the log and hide the failure the log exists to explain.
     */
    public void redact(@NonNull String secret) {
        if (secret.length() >= MIN_REDACTABLE && !this.secrets.contains(secret)) {
            this.secrets.add(secret);
        }
    }

    public int redactedSecretCount() {
        return this.secrets.size();
    }

    /** Append output; redacted first, then bounded. */
    public void append(@NonNull String chunk) {
        if (chunk.isEmpty()) {
            return;
        }
        String safe = chunk;
        for (String secret : this.secrets) {
            safe = safe.replace(secret, "[redacted]");
        }
        int remaining = this.maxBytes - this.text.length();
        if (remaining <= 0) {
            markTruncated();
            return;
        }
        if (safe.length() > remaining) {
            this.text.append(safe, 0, remaining);
            markTruncated();
            return;
        }
        this.text.append(safe);
    }

    /** Append one controller-authored line (step markers, refusals, outcomes). */
    public void line(@NonNull String line) {
        append(line + "\n");
    }

    private void markTruncated() {
        if (!this.truncated) {
            this.truncated = true;
            this.text.append(TRUNCATION_MARKER);
        }
    }

    public boolean isTruncated() {
        return this.truncated;
    }

    /** The captured, redacted, bounded output. */
    public @NonNull String text() {
        return this.text.toString();
    }
}
