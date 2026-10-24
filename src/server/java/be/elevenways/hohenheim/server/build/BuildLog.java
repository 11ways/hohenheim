package be.elevenways.hohenheim.server.build;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.holder.Subscription;
import be.elevenways.hohenheim.server.instance.ConsoleRedaction;
import be.elevenways.zenit.common.security.KnownValueRedactor;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * THE capture of one build's output: bounded, redacting, and the same object for every
 * builder kind (one log stream, concretely).
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
 * It is THE streaming redactor ({@link KnownValueRedactor}), because a daemon hands
 * output over in chunks that are not line-aligned: a secret split across two appends is held
 * back until it can be judged whole, and the held tail is released by {@link #flush} when the
 * build ends. The cap counts redacted text. An owner whose secrets could not be read makes the
 * log MASK every line ({@link ConsoleRedaction.Secrets#redactor()}): it fails closed, never open.
 *
 * AIDEV-NOTE: a viewer TAPS the log ({@link #subscribe}) and is handed exactly what was STORED -- the redacted,
 * capped delta of each append -- so a live viewer can never read a secret or a byte the persisted log would not hold.
 */
public final class BuildLog {

    static final String TRUNCATION_MARKER =
        "\n[hohenheim] build log truncated at the configured cap\n";

    /** One tap: what queues under the monitor, and what delivers outside it. */
    private record Tap(@NonNull Consumer<String> queue, @NonNull Runnable deliver) {}

    private final int maxBytes;
    private final KnownValueRedactor redactor;
    private final StringBuilder text = new StringBuilder();
    private boolean truncated;
    private final List<Tap> taps = new CopyOnWriteArrayList<>();

    /** A log with no owner secrets; values are declared through {@link #redact}. */
    public BuildLog(int maxBytes) {
        this(maxBytes, ConsoleRedaction.Secrets.NONE);
    }

    /** A log redacting an owner's secret set; a set that could not be read makes it mask every line. */
    public BuildLog(int maxBytes, ConsoleRedaction.@NonNull Secrets secrets) {
        this.maxBytes = Math.max(1024, maxBytes);
        this.redactor = secrets.redactor();
    }

    /**
     * Register a value that must never appear in the captured output.
     * A value shorter than {@link KnownValueRedactor#MIN_LENGTH} characters is REFUSED: redacting a 3-char string
     * would replace half the log and hide the failure the log exists to explain. The refusal is logged (never the
     * value) and {@link #redactedSecretCount()} does not count it.
     *
     * @return whether the value is redacted from this log
     */
    public boolean redact(@NonNull String secret) {
        boolean taken = this.redactor.addSecret(secret);
        if (!taken) {
            Blast.log("BUILD: a secret value shorter than", KnownValueRedactor.MIN_LENGTH,
                "characters is NOT redacted from this build log");
        }
        return taken;
    }

    public int redactedSecretCount() {
        return this.redactor.secretCount();
    }

    /**
     * Redact a text that is not output (a build's failure reason) through this log's secrets, as one whole stream of
     * its own: the log's stream and its held tail are untouched.
     *
     * @return null for null; the placeholder for a non-empty text when this log masks (its owner's secrets unreadable)
     */
    public @Nullable String redactedWhole(@Nullable String text) {
        return this.redactor.redactWhole(text);
    }

    /**
     * Tap the log: {@code queue} hears the text stored so far, then every stored delta, each exactly once and in order.
     *
     * AIDEV-NOTE: LOCK ORDER. The replay and every delta reach {@code queue} UNDER this log's monitor -- the monitor
     * every append stores under, which is what makes replay-then-attach exactly once -- so {@code queue} only queues:
     * it never blocks and never calls out. {@code deliver} runs after each append that queued, OUTSIDE the monitor,
     * and is where queued text may block on its way out (a channel submit waiting on the link monitor). The replay is
     * queued and not delivered here: the caller delivers it once it holds no lock of its own. Closing the tap never
     * takes this monitor, so a channel's onClose (holding the link monitor) never waits on an append.
     *
     * @return the handle that detaches the tap
     */
    public @NonNull Subscription subscribe(@NonNull Consumer<String> queue, @NonNull Runnable deliver) {
        Tap tap = new Tap(queue, deliver);
        synchronized (this) {
            queue.accept(this.text.toString());
            this.taps.add(tap);
        }
        return Subscription.of(() -> this.taps.remove(tap));
    }

    /** Append output; redacted first, then bounded. */
    public void append(@NonNull String chunk) {
        if (chunk.isEmpty()) {
            return;
        }
        List<Runnable> deliveries;
        synchronized (this) {
            deliveries = this.stored(this.redactor.feed(chunk));
        }
        deliver(deliveries);
    }

    /** Release the redactor's held tail into the log: the output ended, so a held secret prefix is stored masked. */
    public void flush() {
        List<Runnable> deliveries;
        synchronized (this) {
            deliveries = this.stored(this.redactor.flush());
        }
        deliver(deliveries);
    }

    /** @return the taps' deliveries, to run outside the monitor, once the redacted text is stored and its delta queued */
    private @NonNull List<Runnable> stored(@NonNull String safe) {
        int before = this.text.length();
        this.store(safe);
        if (this.text.length() == before || this.taps.isEmpty()) {
            return List.of();
        }
        String delta = this.text.substring(before);
        List<Runnable> deliveries = new ArrayList<>();
        for (Tap tap : this.taps) {
            tap.queue().accept(delta);
            deliveries.add(tap.deliver());
        }
        return deliveries;
    }

    /** Run the taps' deliveries; a failing viewer is logged, never the build's failure (the drain must go on). */
    private static void deliver(@NonNull List<Runnable> deliveries) {
        for (Runnable delivery : deliveries) {
            try {
                delivery.run();
            } catch (RuntimeException failed) {
                Blast.log("A build log viewer failed and was skipped:", failed);
            }
        }
    }

    private void store(@NonNull String safe) {
        if (safe.isEmpty()) {
            return;
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

    public synchronized boolean isTruncated() {
        return this.truncated;
    }

    /** The captured, redacted, bounded output; a tail held back as a possible secret is not in it until {@link #flush}. */
    public synchronized @NonNull String text() {
        return this.text.toString();
    }
}
