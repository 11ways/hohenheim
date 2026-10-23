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
 * later is not redacted from this operation (KnownValueRedactor.addSecret's rule). An operation resumed by boot recovery
 * starts a new stream on its stored log. It FAILS CLOSED: an operation that names no instance owner has no secret set,
 * so each of its lines is stored as the placeholder, never raw.
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
        endLine(append(op, stream.flush(), at));
        append(op, stream.feed(line + "\n"), at);
        endLine(append(op, stream.flush(), at));
        STREAMS.remove(op.get(ReleaseOperationModel.ID));
        return op;
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
        Integer applicationId = op.get(ReleaseOperationModel.FOR_ID);
        if (id == null || applicationId == null
                || !InstanceModel.MODEL_ID.toString().equals(op.get(ReleaseOperationModel.FOR_MODEL))) {
            return null;
        }
        return STREAMS.computeIfAbsent(id, opened -> ConsoleRedaction.redactorFor(applicationId));
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
