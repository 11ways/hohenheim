package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.server.runtime.ConsoleStream;
import be.elevenways.hohenheim.server.runtime.ConsoleStreamSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * ONE live console attachment of one instance: pumps the driver's {@link ConsoleStream}
 * on a virtual thread, keeps a bounded replay ring, fans chunks out to listeners (the
 * admin WebSocket), assembles lines for the readiness matcher, and records the stop
 * observations that suppress crash detection. Lifecycle is owned by
 * {@link InstanceConsoles}. The only database write it performs is flushing its own ring
 * through the {@link InstanceConsoleLogs.Sink} the hub hands it -- status writes stay with
 * the hub, which owns the fence.
 *
 * AIDEV-NOTE: REDACTION IS AT INGEST. Every byte the driver produces passes through the
 * session's {@link ConsoleRedaction.Redactor} before it touches the ring, the line matcher,
 * a viewer or the persisted log -- so raw output exists in exactly one place (a local
 * variable in {@link #pump()}) and no copy of a secret survives behind it. Redacting per
 * consumer instead would mean keeping the raw text and trusting every future reader, which
 * is the leak rather than the fix.
 */
final class InstanceConsoleSession {

    /** Replay ring cap: recent console text kept for a subscribing viewer. */
    private static final int RING_CAP = 64 * 1024;

    /** A console line longer than this is matched in slices; nothing accumulates past it. */
    private static final int LINE_CAP = 8 * 1024;

    /** How often the ring is written down; the proclog cadence, for the same reason. */
    private static final long FLUSH_INTERVAL_MS = 30_000;

    interface ExitListener {
        /** Runs once, on the pump thread, after the stream terminated. */
        void onStreamEnd(@NonNull InstanceConsoleSession session,
                         ConsoleStream.@NonNull Termination termination, @NonNull String detail);
    }

    private final int instanceId;
    private final @NonNull String handle;
    private final ConsoleStreamSupport.@NonNull Console console;
    private final @Nullable String stopCommand;
    private final @NonNull ExitListener exitListener;
    private final ConsoleRedaction.@NonNull Redactor redactor;
    private final InstanceConsoleLogs.@Nullable Sink logSink;

    private volatile long lastFlushAt;

    private final StringBuilder ring = new StringBuilder();
    private final StringBuilder currentLine = new StringBuilder();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final CountDownLatch ended = new CountDownLatch(1);

    private volatile boolean stopObserved;
    private volatile boolean closedByHub;

    // Readiness matcher state, guarded by `this` (armed after the STARTING stamp so the
    // async RUNNING flip can never race the deploy thread's own stamp; arming scans the
    // ring first, so a line that arrived before arming still matches).
    private @Nullable String readinessLine;
    private @Nullable Runnable readinessAction;
    private boolean readinessMatched;

    InstanceConsoleSession(int instanceId, @NonNull String handle,
                           ConsoleStreamSupport.@NonNull Console console,
                           @Nullable String stopCommand,
                           ConsoleRedaction.@NonNull Redactor redactor,
                           InstanceConsoleLogs.@Nullable Sink logSink,
                           @NonNull ExitListener exitListener) {
        this.instanceId = instanceId;
        this.handle = handle;
        this.console = console;
        this.stopCommand = stopCommand == null || stopCommand.isBlank()
            ? null : stopCommand.trim();
        this.redactor = redactor;
        this.logSink = logSink;
        this.lastFlushAt = System.currentTimeMillis();
        this.exitListener = exitListener;
        JobRunner.startVirtualThread(this::pump);
    }

    /** Teach the live redactor a value declared secret after this session opened. */
    void addSecret(@NonNull String secret) {
        this.redactor.addSecret(secret);
    }

    int instanceId() {
        return this.instanceId;
    }

    @NonNull String handle() {
        return this.handle;
    }

    boolean stdinDelivered() {
        return this.console.stdinDelivered();
    }

    /** The stream is over (any termination). */
    boolean isEnded() {
        return this.ended.getCount() == 0;
    }

    private void pump() {
        ConsoleStream stream = this.console.stream();
        ConsoleStream.Chunk chunk;
        while ((chunk = stream.next()) != null) {
            this.deliver(this.redactor.feed(new String(chunk.data(), StandardCharsets.UTF_8)));
        }
        // Whatever the redactor was holding back as a possible secret prefix turned out not
        // to be one; it is ordinary output and must not be swallowed.
        this.deliver(this.redactor.flush());
        // The episode is over: write it down once more, unthrottled, so the last output
        // before an exit is in the history rather than only in a ring about to be dropped.
        this.persistLog();
        this.ended.countDown();
        ConsoleStream.Termination termination = stream.termination();
        try {
            this.exitListener.onStreamEnd(this, this.closedByHub
                ? ConsoleStream.Termination.CONSUMER_CLOSED : termination, stream.detail());
        } catch (RuntimeException e) {
            Blast.log("CONSOLE: exit handling for instance", this.instanceId,
                "failed:", e.getMessage());
        }
    }

    /** Fan out ALREADY-REDACTED text; every caller passes it through the redactor first. */
    private void deliver(@NonNull String text) {
        if (text.isEmpty()) {
            return;
        }
        Runnable matched = null;
        synchronized (this) {
            this.ring.append(text);
            if (this.ring.length() > RING_CAP) {
                this.ring.delete(0, this.ring.length() - RING_CAP);
            }
            matched = this.feedLines(text);
        }
        for (Consumer<String> listener : this.listeners) {
            try {
                listener.accept(text);
            } catch (RuntimeException ignored) {
                // one dead viewer must not kill the pump
            }
        }
        if (matched != null) {
            matched.run();
        }
        if (System.currentTimeMillis() - this.lastFlushAt >= FLUSH_INTERVAL_MS) {
            this.persistLog();
        }
    }

    /** Write the (already redacted) ring into this episode's row; no-op without a sink. */
    private void persistLog() {
        InstanceConsoleLogs.Sink sink = this.logSink;
        if (sink == null) {
            return;
        }
        this.lastFlushAt = System.currentTimeMillis();
        String text;
        synchronized (this) {
            text = this.ring.toString();
        }
        sink.flush(text);
    }

    /** Test seam: write the episode down right now instead of waiting for the interval. */
    void flushLogNow() {
        this.persistLog();
    }

    /** Assemble lines and run the readiness matcher; returns the action to fire, once. */
    private @Nullable Runnable feedLines(@NonNull String text) {
        Runnable fire = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || this.currentLine.length() >= LINE_CAP) {
                if (fire == null) {
                    fire = this.matchLine(this.currentLine.toString());
                }
                this.currentLine.setLength(0);
                if (c != '\n') {
                    this.currentLine.append(c);
                }
            } else if (c != '\r') {
                this.currentLine.append(c);
            }
        }
        return fire;
    }

    private @Nullable Runnable matchLine(@NonNull String line) {
        if (this.readinessMatched || this.readinessLine == null || this.readinessAction == null) {
            return null;
        }
        if (!line.contains(this.readinessLine)) {
            return null;
        }
        this.readinessMatched = true;
        Runnable action = this.readinessAction;
        this.readinessAction = null;
        return action;
    }

    /**
     * Seed the ring with console output the daemon buffered BEFORE this attach (the
     * deferred-attach shape: Incus only opens a console on a running workload). Runs
     * before {@link #armReadiness}, whose backlog scan then covers the seeded text.
     * The live stream may repeat some of it -- harmless for matching, and a viewer
     * merely sees the boot text; line-splitting deliberately does not run over the
     * seed, so a partial live chunk cannot fuse with backlog text into a false line.
     */
    void seedBacklog(@NonNull String text) {
        if (text.isEmpty()) {
            return;
        }
        // The backlog is a COMPLETE text of its own, so it gets its own one-shot redaction
        // rather than the streaming buffer -- feeding it through the live redactor would
        // interleave it with the running stream's held-back tail.
        String safe = ConsoleRedaction.redactWhole(text, this.redactor);
        synchronized (this) {
            this.ring.insert(0, safe);
            if (this.ring.length() > RING_CAP) {
                this.ring.delete(0, this.ring.length() - RING_CAP);
            }
        }
    }

    /**
     * Arm the readiness matcher AFTER the caller stamped {@code starting}: scans the
     * output already seen (so a line that raced the arm still counts), then watches
     * live lines. The action runs at most once, off the arming or pump thread.
     */
    void armReadiness(@NonNull String line, @NonNull Runnable action) {
        Runnable fire = null;
        synchronized (this) {
            this.readinessLine = line;
            this.readinessAction = action;
            if (this.ring.indexOf(line) >= 0) {
                this.readinessMatched = true;
                this.readinessAction = null;
                fire = action;
            }
        }
        if (fire != null) {
            fire.run();
        }
    }

    synchronized boolean readinessMatched() {
        return this.readinessMatched;
    }

    /**
     * Subscribe a viewer: replays the ring, then live chunks -- under the ring lock, so
     * no chunk is lost or doubled between replay and subscription.
     */
    void subscribe(@NonNull Consumer<String> listener) {
        synchronized (this) {
            if (this.ring.length() > 0) {
                listener.accept(this.ring.toString());
            }
            this.listeners.add(listener);
        }
    }

    void unsubscribe(@NonNull Consumer<String> listener) {
        this.listeners.remove(listener);
    }

    /**
     * Send one console line to the workload's stdin. A line equal to the template's
     * stop command marks the coming exit as OBSERVED, which suppresses crash detection
     * -- whether it was typed by an operator or sent by the graceful-stop path.
     *
     * @throws IOException when stdin does not reach the workload or the stream is over
     */
    void sendCommand(@NonNull String command) throws IOException {
        if (!this.console.stdinDelivered()) {
            throw new IOException("Container '" + this.handle + "' was created without"
                + " OpenStdin; console input cannot reach it (redeploy to fix)");
        }
        String line = command.stripTrailing();
        if (this.stopCommand != null && line.trim().equals(this.stopCommand)) {
            this.stopObserved = true;
        }
        this.console.stream().writeStdin((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** The template's stop command, or null when it declares none. */
    @Nullable String stopCommand() {
        return this.stopCommand;
    }

    /** Mark the coming exit as operator-intended (stop/destroy paths). */
    void markStopExpected() {
        this.stopObserved = true;
    }

    boolean stopObserved() {
        return this.stopObserved;
    }

    /** Wait for the stream to end (graceful-stop path). */
    boolean awaitEnd(long timeoutMs) {
        try {
            return this.ended.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Hub-side close: the exit listener sees CONSUMER_CLOSED regardless of race. */
    void close() {
        this.closedByHub = true;
        this.console.stream().close();
    }
}
