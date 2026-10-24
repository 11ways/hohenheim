package be.elevenways.hohenheim.server.instance;

import be.elevenways.zenit.common.security.KnownValueRedactor;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One redacting stream over an owner's secret values that FAILS CLOSED: when the values could not be read, every
 * output line is replaced by {@link KnownValueRedactor#PLACEHOLDER} instead of passing through raw.
 *
 * AIDEV-NOTE: the matching itself is {@link KnownValueRedactor}'s (by exact value, held-back tail, minimum length).
 * This class only adds the masking mode zenit's redactor cannot express: an empty secret set there means "nothing to
 * hide", which is the wrong answer when the set is empty because the variable table could not be read. In masking
 * mode the line structure is kept (one placeholder per output line) so a console ring and a step log stay readable as
 * lines; the text itself never leaves.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public final class SecretRedactor {

    private final KnownValueRedactor stream;
    /** The values the stream took, for a whole text redacted apart from it ({@link #redactWhole}). */
    private final Set<String> secrets = new LinkedHashSet<>();
    private boolean masking;
    /** Masking only: the current output line has text that its placeholder has not answered yet. */
    private boolean lineOpen;

    private SecretRedactor(boolean masking) {
        this.stream = KnownValueRedactor.of(Set.of());
        this.masking = masking;
    }

    /** @return a stream over the owner's values; a masking one when they could not be read */
    public static @NonNull SecretRedactor of(ConsoleRedaction.@NonNull Secrets secrets) {
        SecretRedactor redactor = new SecretRedactor(!secrets.readable());
        for (String secret : secrets.values()) {
            redactor.addSecret(secret);
        }
        return redactor;
    }

    /**
     * Teach this stream a value declared secret ({@link KnownValueRedactor#addSecret(String)}'s rules).
     *
     * @return false when the value is too short to be redacted; a masking stream hides it anyway and answers true
     */
    public synchronized boolean addSecret(@Nullable String secret) {
        boolean taken = this.stream.addSecret(secret);
        if (taken) {
            this.secrets.add(secret);
        }
        return taken || (this.masking && secret != null);
    }

    /**
     * Switch to masking for the rest of the stream: the owner's values turned out to be unreadable. Text the matcher
     * still held back is never released; it counts as an unanswered line.
     */
    public synchronized void maskAll() {
        if (this.masking) {
            return;
        }
        this.masking = true;
        if (!this.stream.flush().isEmpty()) {
            this.lineOpen = true;
        }
    }

    /** @return whether this stream masks every line (its owner's values could not be read) */
    public synchronized boolean masks() {
        return this.masking;
    }

    /** @return how many distinct values the matcher replaces */
    public synchronized int secretCount() {
        return this.stream.secretCount();
    }

    /** @return the text safe to emit now, possibly empty */
    public synchronized @NonNull String feed(@NonNull String chunk) {
        if (!this.masking) {
            return this.stream.feed(chunk);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < chunk.length(); i++) {
            if (chunk.charAt(i) == '\n') {
                out.append(this.lineOpen ? KnownValueRedactor.PLACEHOLDER + "\n" : "\n");
                this.lineOpen = false;
            } else {
                this.lineOpen = true;
            }
        }
        return out.toString();
    }

    /** End the stream: a held secret prefix is released masked, an unanswered masked line as its placeholder. */
    public synchronized @NonNull String flush() {
        if (!this.masking) {
            return this.stream.flush();
        }
        String out = this.lineOpen ? KnownValueRedactor.PLACEHOLDER : "";
        this.lineOpen = false;
        return out;
    }

    /**
     * Redact a WHOLE text (a failure reason) through this stream's values as one stream of its own; this stream and
     * its held tail are untouched.
     *
     * @return null for null; the placeholder for a non-empty text when this stream masks
     */
    public synchronized @Nullable String redactWhole(@Nullable String text) {
        if (text == null) {
            return null;
        }
        if (this.masking) {
            return text.isEmpty() ? text : KnownValueRedactor.PLACEHOLDER;
        }
        KnownValueRedactor whole = KnownValueRedactor.of(this.secrets);
        return whole.feed(text) + whole.flush();
    }
}
