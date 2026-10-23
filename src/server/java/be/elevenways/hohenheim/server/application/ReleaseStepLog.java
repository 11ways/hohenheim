package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.server.instance.ConsoleRedaction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.KnownValueRedactor;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The step log of a release operation, written through ONE redacting stream per operation.
 *
 * AIDEV-NOTE: the redactor is per OPERATION, never per line. A step quotes daemon text, which can echo a secret
 * value, and a multi-line value (a PEM key) can span several step lines: a line redacted alone never shows it whole,
 * and a line cut off mid-secret would be stored raw. Every step line is therefore fed, with its newline, into the
 * operation's one {@link KnownValueRedactor}, and only that stream's OUTPUT is stored (the live frames are the stored
 * log, see {@link DeployProgress}). The timestamp is written where an output line starts, so a line the redactor held
 * back carries the time it was released. {@link #stageFinal} runs before the engine's terminal write stores the log:
 * it FLUSHES before staging the controller's last line, because the quoted output ended with the operation, and a
 * secret prefix it was cut off in would read as ordinary text once "release complete" followed it. No held tail is
 * lost, and a held secret prefix lands masked.
 *
 * AIDEV-NOTE: a secret cut off mid-operation and FOLLOWED by more output is no longer that value and is stored as
 * printed; exact-value matching cannot tell it from ordinary text (KnownValueRedactor's class note).
 *
 * AIDEV-NOTE: the secret set is read once, when the operation's first line is staged in this process; a value declared
 * later is not redacted from this operation (KnownValueRedactor.addSecret's rule). It FAILS CLOSED: an operation that
 * names no instance owner has no secret set, so each of its lines is stored as the placeholder, never raw.
 *
 * AIDEV-NOTE: a stream lives as long as ONE segment of the operation's work in this process, never until restart: the
 * engine runs every segment (a release, a rollback, a drain, a boot recovery) through {@code ReleaseEngine.segment},
 * whose finally {@link #close}s the stream however the segment ended, and {@link #stageFinal} forgets it before it
 * writes anything. A segment that follows (a drain after its release, a settle deferred past the lock, a boot
 * recovery) opens a NEW stream on the stored log; nothing ever reads a stream left behind by another segment.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
final class ReleaseStepLog {

    /** The open stream of each operation that staged a line and has not ended, by operation id. */
    private static final Map<Integer, KnownValueRedactor> STREAMS = new ConcurrentHashMap<>();

    private ReleaseStepLog() {
    }

    /** Feed one step line into the operation's stream and stage what it released onto the log the row carries. */
    static @NonNull Row stage(@NonNull Row op, @NonNull String line, @NonNull Instant at) {
        KnownValueRedactor stream = streamOf(op);
        return append(op, stream == null ? KnownValueRedactor.PLACEHOLDER + "\n" : stream.feed(line + "\n"), at);
    }

    /**
     * End the operation's stream around its last line: what the redactor held is released (a secret prefix masked),
     * then the line is fed and flushed on its own, and the stream is forgotten.
     */
    static @NonNull Row stageFinal(@NonNull Row op, @NonNull String line, @NonNull Instant at) {
        KnownValueRedactor stream = streamOf(op);
        if (stream == null) {
            return stage(op, line, at);
        }
        STREAMS.remove(op.get(ReleaseOperationModel.ID));
        endLine(append(op, stream.flush(), at));
        append(op, stream.feed(line + "\n"), at);
        endLine(append(op, stream.flush(), at));
        return op;
    }

    /**
     * End the operation's stream if this process still holds one: it is forgotten first, then what it held is staged
     * (a secret prefix masked), because the segment that quoted the output is over.
     *
     * @return whether text was staged onto the row's log
     */
    static boolean close(@NonNull Row op, @NonNull Instant at) {
        Integer id = op.get(ReleaseOperationModel.ID);
        KnownValueRedactor stream = id == null ? null : STREAMS.remove(id);
        if (stream == null) {
            return false;
        }
        String held = stream.flush();
        if (held.isEmpty()) {
            return false;
        }
        endLine(append(op, held, at));
        return true;
    }

    /** @return whether this process holds an open stream for the operation; package-private for its tests */
    static boolean isOpen(int operationId) {
        return STREAMS.containsKey(operationId);
    }

    /**
     * The operation's failure reason as it may be stored and shown: daemon or build text can quote a secret value, so
     * the whole reason is redacted as one stream through the owner's secrets ({@link ConsoleRedaction#redactWhole}).
     *
     * @return null for no reason; the placeholder when the operation names no instance owner (fail closed)
     */
    static @Nullable String redactedReason(@NonNull Row op, @Nullable String reason) {
        if (reason == null) {
            return null;
        }
        Integer applicationId = instanceOwnerOf(op);
        return applicationId == null ? KnownValueRedactor.PLACEHOLDER
            : ConsoleRedaction.redactWhole(reason, applicationId);
    }

    /** A masked tail ends without its newline; the next line must still start on a line of its own. */
    private static void endLine(@NonNull Row op) {
        String log = op.get(ReleaseOperationModel.STEP_LOG);
        if (log != null && !log.isEmpty() && !log.endsWith("\n")) {
            op.set(ReleaseOperationModel.STEP_LOG, log + "\n");
        }
    }

    /** @return the operation's stream, opened on its owner's secret values; null when it names no instance owner */
    private static @Nullable KnownValueRedactor streamOf(@NonNull Row op) {
        Integer id = op.get(ReleaseOperationModel.ID);
        Integer applicationId = instanceOwnerOf(op);
        if (id == null || applicationId == null) {
            return null;
        }
        return STREAMS.computeIfAbsent(id, opened -> ConsoleRedaction.redactorFor(applicationId));
    }

    /** @return the application the operation belongs to; null when it names no instance owner */
    private static @Nullable Integer instanceOwnerOf(@NonNull Row op) {
        Integer applicationId = op.get(ReleaseOperationModel.FOR_ID);
        return applicationId != null
            && InstanceModel.MODEL_ID.toString().equals(op.get(ReleaseOperationModel.FOR_MODEL)) ? applicationId : null;
    }

    /** Append released text to the row's log, opening every output line with the time it was released. */
    private static @NonNull Row append(@NonNull Row op, @NonNull String safe, @NonNull Instant at) {
        String existing = op.get(ReleaseOperationModel.STEP_LOG);
        StringBuilder log = new StringBuilder(existing == null ? "" : existing);
        int from = 0;
        while (from < safe.length()) {
            if (log.isEmpty() || log.charAt(log.length() - 1) == '\n') {
                log.append(at).append(' ');
            }
            int newline = safe.indexOf('\n', from);
            int to = newline < 0 ? safe.length() : newline + 1;
            log.append(safe, from, to);
            from = to;
        }
        op.set(ReleaseOperationModel.STEP_LOG, log.toString());
        return op;
    }
}
